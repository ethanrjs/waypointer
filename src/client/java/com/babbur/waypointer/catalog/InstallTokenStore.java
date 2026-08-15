package com.babbur.waypointer.catalog;

import com.babbur.waypointer.Waypointer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Mints the anonymous install token the catalog uses to count one download per
 * device per route. The token is an HMAC of a random per-device secret over
 * the route ID, so the server can dedupe repeat installs without ever seeing
 * an identifier that links this device's installs across routes.
 */
public final class InstallTokenStore {

    private static final int SECRET_BYTES = 32;
    private static final int SCHEMA = 1;

    private static volatile InstallTokenStore shared;

    private final Path file;
    private volatile byte[] secret;

    public InstallTokenStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public static InstallTokenStore shared() {
        InstallTokenStore store = shared;
        if (store == null) {
            store = new InstallTokenStore(FabricLoader.getInstance().getConfigDir()
                    .resolve(Waypointer.MOD_ID)
                    .resolve("publisher")
                    .resolve("install-token.json"));
            shared = store;
        }
        return store;
    }

    /**
     * Returns the token for one route, or null when the secret cannot be read
     * or created; a tokenless install still counts once per IP on the server.
     */
    public String tokenFor(String routeId) {
        if (routeId == null || routeId.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(loadOrCreateSecret(), "HmacSHA256"));
            byte[] digest = mac.doFinal(routeId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception failure) {
            Waypointer.LOGGER.warn("Could not mint an install token", failure);
            return null;
        }
    }

    private synchronized byte[] loadOrCreateSecret() throws Exception {
        if (secret != null) return secret;
        byte[] loaded = readSecret();
        if (loaded == null) {
            loaded = new byte[SECRET_BYTES];
            new SecureRandom().nextBytes(loaded);
            JsonObject root = new JsonObject();
            root.addProperty("schema", SCHEMA);
            root.addProperty("secret", Base64.getEncoder().encodeToString(loaded));
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            CatalogAtomicFile.replace(file,
                    (root + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    "install-token-");
        }
        secret = loaded;
        return loaded;
    }

    private byte[] readSecret() {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema").getAsInt() != SCHEMA) return null;
            byte[] decoded = Base64.getDecoder().decode(root.get("secret").getAsString());
            return decoded.length == SECRET_BYTES ? decoded : null;
        } catch (Exception invalid) {
            // A damaged file just means this device counts once more per route.
            return null;
        }
    }
}
