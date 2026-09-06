package com.babbur.waypointer.catalog;

import com.babbur.waypointer.Waypointer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PublisherIdentityStore {
    private static final int CURRENT_SCHEMA = 2;
    private static final int LEGACY_SCHEMA = 1;
    private static final int MAX_FILE_BYTES = 16 * 1024;
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Object CREATION_LOCK = new Object();

    private final Path file;
    private final Path sharedFile;

    public PublisherIdentityStore(Path file) {
        this(file, null);
    }

    private PublisherIdentityStore(Path file, Path sharedFile) {
        this.file = file.toAbsolutePath().normalize();
        this.sharedFile = sharedFile == null ? null : sharedFile.toAbsolutePath().normalize();
    }

    public static PublisherIdentityStore defaultLocation() {
        Path file = FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID)
                .resolve("publisher")
                .resolve("identity.json");
        return forProfile(file, sharedIdentityFile(
                System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")),
                System.getenv("APPDATA"), System.getenv("XDG_CONFIG_HOME")));
    }

    static PublisherIdentityStore forProfile(Path profileFile, Path sharedFile) {
        if (profileFile.toAbsolutePath().normalize().equals(sharedFile.toAbsolutePath().normalize())) {
            return new PublisherIdentityStore(profileFile);
        }
        return new PublisherIdentityStore(profileFile, sharedFile);
    }

    static Path sharedIdentityFile(String osName, Path home, String appData, String xdgConfig) {
        String os = osName.toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("mac")) base = home.resolve("Library/Application Support");
        else if (os.contains("win")) base = absoluteOrDefault(appData, home.resolve("AppData/Roaming"));
        else base = absoluteOrDefault(xdgConfig, home.resolve(".config"));
        return base.resolve("waypointer/publisher/identity.json");
    }

    private static Path absoluteOrDefault(String value, Path fallback) {
        if (value == null || value.isBlank()) return fallback;
        Path path = Path.of(value);
        return path.isAbsolute() ? path : fallback;
    }

    public Path file() {
        return file;
    }

    public PublisherIdentity loadOrCreate() {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return load();
        PublisherIdentity inherited = sharedFile == null ? null
                : new PublisherIdentityStore(sharedFile).loadOrCreate();
        loadOrCreate(inherited);
        return load();
    }

    private PublisherIdentity loadOrCreate(PublisherIdentity inherited) {
        synchronized (CREATION_LOCK) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return load();
            Path parent = file.getParent();
            if (parent == null) {
                throw new CatalogStorageException("Publisher identity path has no parent");
            }
            try {
                Files.createDirectories(parent);
                Path lockFile = parent.resolve("identity.lock");
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = channel.lock()) {
                    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                        writeNew(inherited == null
                                ? PublisherIdentity.generate(Instant.now()) : inherited);
                    }
                    return readIdentity();
                }
            } catch (IOException failure) {
                throw new CatalogStorageException(
                        "Could not lock the publisher identity", failure);
            }
        }
    }

    public PublisherIdentity load() {
        PublisherIdentity local = readIdentity();
        PublisherIdentity shared = sharedIdentity(local);
        if (shared == null || !shared.publisherId().equals(local.publisherId())
                || shared.publisherName() == null) return local;
        if (local.publisherName() != null) {
            requireMatchingName(local, shared);
            return local;
        }
        synchronized (CREATION_LOCK) {
            try (FileChannel channel = FileChannel.open(file.getParent().resolve("identity.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                PublisherIdentity current = readIdentity();
                if (!current.publisherId().equals(shared.publisherId())) {
                    throw new CatalogStorageException("Publisher identity changed while synchronizing its name");
                }
                requireMatchingName(current, shared);
                if (current.publisherName() == null) writeReplacement(shared);
                return readIdentity();
            } catch (IOException failure) {
                throw new CatalogStorageException("Could not synchronize the publisher name", failure);
            }
        }
    }

    private static void requireMatchingName(PublisherIdentity local, PublisherIdentity shared) {
        if (local.publisherName() != null && !local.publisherName().equals(shared.publisherName())) {
            throw new CatalogStorageException("Publisher names disagree for the same identity. "
                    + "Restore a matching identity.json backup.");
        }
    }

    private PublisherIdentity readIdentity() {
        rejectUnsafeFile();
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_FILE_BYTES) throw invalidIdentity(null);
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            int schema = root.get("schema").getAsInt();
            if ((schema != LEGACY_SCHEMA && schema != CURRENT_SCHEMA)
                    || !PublisherIdentity.ALGORITHM.equals(root.get("algorithm").getAsString())) {
                throw invalidIdentity(null);
            }
            byte[] publicBytes = decode(root, "publicKeySpki");
            byte[] privateBytes = decode(root, "privateKeyPkcs8");
            Instant createdAt = Instant.parse(root.get("createdAt").getAsString());
            KeyFactory keyFactory = KeyFactory.getInstance(PublisherIdentity.ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            String publisherName = schema >= CURRENT_SCHEMA && root.has("publisherName")
                    ? PublisherNamePolicy.requireValid(
                            root.get("publisherName").getAsString())
                    : null;
            PublisherIdentity identity = new PublisherIdentity(
                    publicKey, privateKey, createdAt, publisherName);
            byte[] probe = PublisherIdentity.identityProbe();
            if (!identity.verifies(probe, identity.sign(probe))) throw invalidIdentity(null);
            return identity;
        } catch (IOException | DateTimeParseException failure) {
            throw invalidIdentity(failure);
        } catch (Exception failure) {
            throw invalidIdentity(failure);
        }
    }

    public PublisherIdentity savePublisherName(PublisherIdentity identity, String name) {
        if (identity == null) throw new IllegalArgumentException("Publisher identity is required");
        String validName = PublisherNamePolicy.requireValid(name);
        synchronized (CREATION_LOCK) {
            Path parent = file.getParent();
            if (parent == null) {
                throw new CatalogStorageException("Publisher identity path has no parent");
            }
            try {
                Files.createDirectories(parent);
                Path lockFile = parent.resolve("identity.lock");
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = channel.lock()) {
                    PublisherIdentity stored = readIdentity();
                    if (!stored.publisherId().equals(identity.publisherId())) {
                        throw new IllegalStateException(
                                "Publisher identity changed before its name was saved");
                    }
                    if (stored.publisherName() != null && !stored.publisherName().equals(validName)) {
                        throw new IllegalStateException(
                                "The publisher name is permanent and cannot be changed");
                    }
                    PublisherIdentity named = stored.withPublisherName(validName);
                    if (sharedFile != null) {
                        PublisherIdentityStore shared = new PublisherIdentityStore(sharedFile);
                        PublisherIdentity sharedIdentity = sharedIdentity(stored);
                        if (sharedIdentity != null
                                && sharedIdentity.publisherId().equals(named.publisherId())) {
                            // Claim the shared name under its cross-process lock before updating the profile.
                            shared.savePublisherName(sharedIdentity, validName);
                        }
                    }
                    if (stored.publisherName() == null) writeReplacement(named);
                    return readIdentity();
                }
            } catch (IOException failure) {
                throw new CatalogStorageException(
                        "Could not save the publisher name", failure);
            }
        }
    }

    private PublisherIdentity sharedIdentity(PublisherIdentity local) {
        if (sharedFile == null) return null;
        try {
            return new PublisherIdentityStore(sharedFile).loadOrCreate(local);
        } catch (CatalogStorageException failure) {
            // A damaged backup must not lock a player out of a valid profile key.
            Waypointer.LOGGER.warn("Could not synchronize the shared publisher identity; "
                    + "the current profile identity is still available", failure);
            return null;
        }
    }

    private void writeNew(PublisherIdentity identity) {
        Path parent = file.getParent();
        if (parent == null) {
            throw new CatalogStorageException("Publisher identity path has no parent");
        }
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(file)) throw invalidIdentity(null);
            byte[] json = serialize(identity).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            CatalogAtomicFile.create(
                    file, json, "identity-", PublisherIdentityStore::setOwnerOnlyPermissions);
        } catch (IOException failure) {
            throw new CatalogStorageException("Could not save the publisher identity", failure);
        }
    }

    private void writeReplacement(PublisherIdentity identity) {
        Path parent = file.getParent();
        if (parent == null) {
            throw new CatalogStorageException("Publisher identity path has no parent");
        }
        try {
            rejectUnsafeFile();
            byte[] json = serialize(identity).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            CatalogAtomicFile.replace(
                    file, json, "identity-", PublisherIdentityStore::setOwnerOnlyPermissions);
        } catch (IOException failure) {
            throw new CatalogStorageException("Could not save the publisher name", failure);
        }
    }

    private void rejectUnsafeFile() {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidIdentity(null);
        }
    }

    private static byte[] decode(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) throw invalidIdentity(null);
        try {
            return Base64.getUrlDecoder().decode(root.get(key).getAsString());
        } catch (IllegalArgumentException failure) {
            throw invalidIdentity(failure);
        }
    }

    private static String serialize(PublisherIdentity identity) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", CURRENT_SCHEMA);
        root.addProperty("algorithm", PublisherIdentity.ALGORITHM);
        root.addProperty("publicKeySpki",
                PublisherIdentity.base64Url(identity.publicKeyEncoded()));
        root.addProperty("privateKeyPkcs8",
                PublisherIdentity.base64Url(identity.privateKeyEncoded()));
        root.addProperty("createdAt", identity.createdAt().toString());
        if (identity.publisherName() != null) {
            root.addProperty("publisherName", identity.publisherName());
        }
        return root.toString() + System.lineSeparator();
    }

    static void setOwnerOnlyPermissions(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("The filesystem cannot protect the publisher private key");
        }
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(List.of(ownerOnly));
    }

    private static CatalogStorageException invalidIdentity(Throwable cause) {
        String message = "Publisher identity is invalid. Restore identity.json from a backup or move it aside manually.";
        return cause == null ? new CatalogStorageException(message)
                : new CatalogStorageException(message, cause);
    }
}
