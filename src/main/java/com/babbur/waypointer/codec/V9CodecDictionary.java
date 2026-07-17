package com.babbur.waypointer.codec;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class V9CodecDictionary {

    static final String RESOURCE_PATH = "/assets/waypointer/codec/v9-deflate-dictionary.bin";
    static final int EXPECTED_BYTES = 32 * 1024;
    static final String EXPECTED_SHA256 =
            "3798dadf42567196cfe5ad4def1f22be276ffbc91e18ade1e935bef65d85e3b6";
    static final byte[] BYTES = load();

    private V9CodecDictionary() {}

    private static byte[] load() {
        try (InputStream input = V9CodecDictionary.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("missing v9 codec dictionary resource " + RESOURCE_PATH);
            }
            byte[] bytes = input.readAllBytes();
            if (bytes.length != EXPECTED_BYTES) {
                throw new IllegalStateException("v9 codec dictionary has " + bytes.length
                        + " bytes; expected " + EXPECTED_BYTES);
            }
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!EXPECTED_SHA256.equals(hash)) {
                throw new IllegalStateException("v9 codec dictionary SHA-256 mismatch: " + hash);
            }
            return bytes;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
