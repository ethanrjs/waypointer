package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRequestSignerTest {
    @Test
    void signatureBindsExactBodyAndRequestFields() throws Exception {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        byte[] body = "{\"payload\":\"WP:test\"}".getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[16];
        for (int index = 0; index < nonce.length; index++) nonce[index] = (byte) index;

        Map<String, String> headers = CatalogRequestSigner.sign(
                identity, CatalogRequestSigner.PRODUCTION_AUDIENCE,
                "POST", "/api/routes", body, 1_786_500_000L, nonce);
        byte[] preimage = CatalogRequestSigner.preimage(
                CatalogRequestSigner.PRODUCTION_AUDIENCE,
                "POST", "/api/routes", identity.publisherId(), 1_786_500_000L,
                headers.get("X-Waypointer-Nonce"), body);
        byte[] signature = Base64.getUrlDecoder().decode(
                headers.get("X-Waypointer-Signature"));

        assertTrue(identity.verifies(preimage, signature));
        byte[] changedBody = "{\"payload\":\"WP:changed\"}".getBytes(StandardCharsets.UTF_8);
        byte[] changedPreimage = CatalogRequestSigner.preimage(
                CatalogRequestSigner.PRODUCTION_AUDIENCE,
                "POST", "/api/routes", identity.publisherId(), 1_786_500_000L,
                headers.get("X-Waypointer-Nonce"), changedBody);
        assertFalse(identity.verifies(changedPreimage, signature));

        String bodyHash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body));
        assertEquals("WAYPOINTER-CATALOG-V2\n"
                        + "waypointer-catalog:production\n"
                        + "POST\n/api/routes\n"
                        + identity.publisherId() + "\n"
                        + "1786500000\n"
                        + headers.get("X-Waypointer-Nonce") + "\n"
                        + bodyHash + "\n",
                new String(preimage, StandardCharsets.US_ASCII));
    }

    @Test
    void signatureCannotMoveBetweenDeploymentAudiences() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        byte[] body = new byte[0];
        byte[] nonce = new byte[16];
        Map<String, String> headers = CatalogRequestSigner.sign(
                identity, CatalogRequestSigner.PRODUCTION_AUDIENCE,
                "DELETE", "/api/routes/Abcdefghijklmnopqrstuv",
                body, 1_786_500_000L, nonce);
        byte[] signature = Base64.getUrlDecoder().decode(
                headers.get("X-Waypointer-Signature"));
        byte[] staging = CatalogRequestSigner.preimage(
                "waypointer-catalog:staging", "DELETE",
                "/api/routes/Abcdefghijklmnopqrstuv", identity.publisherId(),
                1_786_500_000L, headers.get("X-Waypointer-Nonce"), body);

        assertFalse(identity.verifies(staging, signature));
    }
}
