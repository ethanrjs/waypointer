package com.babbur.waypointer.catalog;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

public final class PublisherIdentity {
    static final String ALGORITHM = "Ed25519";

    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final Instant createdAt;
    private final String publisherId;
    private final String publisherName;

    PublisherIdentity(PublicKey publicKey, PrivateKey privateKey, Instant createdAt) {
        this(publicKey, privateKey, createdAt, null);
    }

    PublisherIdentity(
            PublicKey publicKey, PrivateKey privateKey, Instant createdAt,
            String publisherName) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.createdAt = createdAt;
        this.publisherId = publisherId(publicKey.getEncoded());
        this.publisherName = publisherName;
    }

    static PublisherIdentity generate(Instant now) {
        try {
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            return new PublisherIdentity(pair.getPublic(), pair.getPrivate(), now);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create a publisher identity", failure);
        }
    }

    public String publisherId() {
        return publisherId;
    }

    public String shortPublisherId() {
        return publisherId.substring(0, Math.min(publisherId.length(), 14));
    }

    public String publicKeyBase64() {
        return base64Url(publicKey.getEncoded());
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String publisherName() {
        return publisherName;
    }

    PublisherIdentity withPublisherName(String name) {
        return new PublisherIdentity(publicKey, privateKey, createdAt, name);
    }

    public byte[] sign(byte[] message) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not sign the catalog request", failure);
        }
    }

    boolean verifies(byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception failure) {
            return false;
        }
    }

    byte[] publicKeyEncoded() {
        return publicKey.getEncoded().clone();
    }

    byte[] privateKeyEncoded() {
        return privateKey.getEncoded().clone();
    }

    static String publisherId(byte[] publicKeySpki) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKeySpki);
            return "wp_" + base64Url(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not derive the publisher ID", failure);
        }
    }

    static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] identityProbe() {
        return "waypointer-publisher-identity".getBytes(StandardCharsets.US_ASCII);
    }
}
