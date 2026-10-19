package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;
import com.babbur.waypointer.util.BoundedBody;
import com.google.gson.JsonParser;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class SkyblockerAuthenticationTest {
    @Test void signsSyntheticProofWithTemporaryServiceIdentityWithoutPrivateKeyBytes() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        UUID uuid = UUID.randomUUID();
        String proof = SkyblockerAuthentication.proof(uuid, keys.getPublic(), new byte[]{1, 2, 3},
                2000, keys.getPrivate(), "26.1.2", "1.10.0", 1000);
        var json = JsonParser.parseString(proof).getAsJsonObject();
        assertEquals("skyblocker", json.get("mod").getAsString());
        assertEquals("26.1.2", json.get("minecraftVersion").getAsString());
        assertEquals("1.10.0", json.get("modVersion").getAsString());
        assertEquals(uuid.toString(), json.getAsJsonObject("keyPair").get("uuid").getAsString());
        assertFalse(proof.contains(Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())));
        assertEquals(Set.of("keyPair", "signedData", "mod", "minecraftVersion", "modVersion"), json.keySet());
        var signed = json.getAsJsonObject("signedData");
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        byte[] original = Base64.getDecoder().decode(signed.get("original").getAsString());
        assertEquals(16, original.length);
        verifier.update(original);
        assertTrue(verifier.verify(Base64.getDecoder().decode(signed.get("signed").getAsString())));
        assertThrows(SkyblockerAuthentication.SafeFailure.class, () -> SkyblockerAuthentication.proof(uuid, keys.getPublic(),
                new byte[]{1}, 1000, keys.getPrivate(), "26.1.2", "1.10.0", 1000));
    }

    @Test void tokenNeverAppearsInToStringAndRefreshesBeforeExpiry() {
        var token = SkyblockerAuthentication.parseToken("{\"token\":\"secret.example\",\"expiresAt\":600001}".getBytes(StandardCharsets.UTF_8), 1);
        assertFalse(token.toString().contains("secret.example"));
        assertFalse(token.needsRefresh(300000));
        assertTrue(token.needsRefresh(300001));
        assertThrows(SkyblockerAuthentication.SafeFailure.class, () -> SkyblockerAuthentication.parseToken(
                "{\"token\":\"bad\\r\\nheader\",\"expiresAt\":600001}".getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(SkyblockerAuthentication.SafeFailure.class, () -> SkyblockerAuthentication.parseToken(
                "{\"token\":\"expired\",\"expiresAt\":1}".getBytes(StandardCharsets.UTF_8), 1));
    }

    @Test void oversizedResponsesFailAsInvalidResponses() {
        var subscriber = new BoundedBody(16384, () -> new SkyblockerAuthentication.SafeFailure(
                SkyblockerAuthentication.FailureCause.INVALID_RESPONSE));
        boolean[] cancelled = {false};
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {}
            public void cancel() { cancelled[0] = true; }
        });
        subscriber.onNext(List.of(ByteBuffer.allocate(16384)));
        subscriber.onNext(List.of(ByteBuffer.allocate(1)));
        assertTrue(cancelled[0]);
        var failure = assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertEquals(SkyblockerAuthentication.FailureCause.INVALID_RESPONSE,
                SkyblockerAuthentication.failureCause(failure));
    }

    @Test void requestUsesCanonicalEndpointWithoutSendingDuringConstruction() {
        var request = SkyblockerAuthentication.authenticationRequest("synthetic proof", "Waypointer/test");
        assertEquals("https://api.azureaaron.net/authenticate", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Waypointer/test", request.headers().firstValue("User-Agent").orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
    }

    @Test void failureClassificationAndMalformedResponsesNeverExposeExternalText() {
        var timeout = new CompletionException(new HttpTimeoutException("private body"));
        assertEquals(SkyblockerAuthentication.FailureCause.TIMEOUT, SkyblockerAuthentication.failureCause(timeout));
        var connect = new ConnectException("private address");
        connect.initCause(new UnresolvedAddressException());
        assertEquals(SkyblockerAuthentication.FailureCause.DNS, SkyblockerAuthentication.failureCause(connect));
        assertEquals(SkyblockerAuthentication.FailureCause.TLS,
                SkyblockerAuthentication.failureCause(new SSLHandshakeException("private key")));
        assertEquals(SkyblockerAuthentication.FailureCause.HTTP_REJECTED,
                SkyblockerAuthentication.failureCause(new SkyblockerAuthentication.Rejected(403)));
        for (String body : List.of("private response body", "{\"token\":\"private-token\",\"expiresAt\":\"private-expiry\"}")) {
            var failure = assertThrows(SkyblockerAuthentication.SafeFailure.class,
                    () -> SkyblockerAuthentication.parseToken(body.getBytes(StandardCharsets.UTF_8), 0));
            assertEquals(SkyblockerAuthentication.FailureCause.INVALID_RESPONSE, failure.reason());
            assertFalse(failure.toString().contains("private"));
            assertNull(failure.getCause());
        }
    }
}
