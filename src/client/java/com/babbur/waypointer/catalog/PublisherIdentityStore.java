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

    public PublisherIdentityStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static PublisherIdentityStore defaultLocation() {
        Path file = FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID)
                .resolve("publisher")
                .resolve("identity.json");
        return new PublisherIdentityStore(file);
    }

    public Path file() {
        return file;
    }

    public PublisherIdentity loadOrCreate() {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return load();
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
                        writeNew(PublisherIdentity.generate(Instant.now()));
                    }
                    return load();
                }
            } catch (IOException failure) {
                throw new CatalogStorageException(
                        "Could not lock the publisher identity", failure);
            }
        }
    }

    public PublisherIdentity load() {
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
                    PublisherIdentity stored = load();
                    if (!stored.publisherId().equals(identity.publisherId())) {
                        throw new IllegalStateException(
                                "Publisher identity changed before its name was saved");
                    }
                    if (stored.publisherName() != null) {
                        if (!stored.publisherName().equals(validName)) {
                            throw new IllegalStateException(
                                    "The publisher name is permanent and cannot be changed");
                        }
                        return stored;
                    }
                    PublisherIdentity named = stored.withPublisherName(validName);
                    writeReplacement(named);
                    return load();
                }
            } catch (IOException failure) {
                throw new CatalogStorageException(
                        "Could not save the publisher name", failure);
            }
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
