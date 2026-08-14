package com.babbur.waypointer.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CatalogRequestSigner {
    static final String SCHEME = "WAYPOINTER-CATALOG-V2";
    public static final String PRODUCTION_AUDIENCE = "waypointer-catalog:production";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CatalogRequestSigner() {
    }

    public static Map<String, String> sign(
            PublisherIdentity identity, String audience,
            String method, String path, byte[] body) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return sign(identity, audience, method, path, body,
                Instant.now().getEpochSecond(), nonce);
    }

    static Map<String, String> sign(
            PublisherIdentity identity, String audience,
            String method, String path, byte[] body,
            long timestamp, byte[] nonce) {
        String nonceText = PublisherIdentity.base64Url(nonce);
        byte[] preimage = preimage(
                audience, method, path, identity.publisherId(), timestamp, nonceText, body);
        String signature = PublisherIdentity.base64Url(identity.sign(preimage));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Waypointer-Publisher", identity.publisherId());
        headers.put("X-Waypointer-Public-Key", identity.publicKeyBase64());
        headers.put("X-Waypointer-Timestamp", Long.toString(timestamp));
        headers.put("X-Waypointer-Nonce", nonceText);
        headers.put("X-Waypointer-Signature", signature);
        return Map.copyOf(headers);
    }

    static byte[] preimage(
            String audience, String method, String path,
            String publisherId, long timestamp,
            String nonce, byte[] body) {
        if (audience == null || !audience.matches("[a-z0-9:-]{1,64}")) {
            throw new IllegalArgumentException("audience has an invalid format");
        }
        if (method == null || !method.matches("[A-Z]+")) {
            throw new IllegalArgumentException("method must contain uppercase ASCII letters");
        }
        if (path == null || !path.matches("/[A-Za-z0-9_./-]+")) {
            throw new IllegalArgumentException("path has an invalid format");
        }
        if (publisherId == null || !publisherId.matches("wp_[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("publisher ID has an invalid format");
        }
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{22}")) {
            throw new IllegalArgumentException("nonce has an invalid format");
        }
        String bodyHash = PublisherIdentity.base64Url(sha256(body));
        String canonical = SCHEME + '\n'
                + audience + '\n'
                + method + '\n'
                + path + '\n'
                + publisherId + '\n'
                + timestamp + '\n'
                + nonce + '\n'
                + bodyHash + '\n';
        return canonical.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not hash the catalog request", failure);
        }
    }
}
