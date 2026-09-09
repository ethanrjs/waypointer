package com.babbur.waypointer.crystal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** hysky proof of profile-key possession. No access tokens or private-key bytes are serialized. */
public final class SkyblockerAuthentication {
    // hysky's legacy route redirects here (307). Use the canonical endpoint without forwarding proofs to redirects.
    static final URI ENDPOINT = URI.create("https://api.azureaaron.net/authenticate");
    private static final int MAX_RESPONSE = 16_384;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private SkyblockerAuthentication() {}

    /** Fixed, user-safe causes. Never derive diagnostic text from exception messages or response bodies. */
    public enum FailureCause {
        NONE("No failure recorded"),
        PROFILE_KEYS_UNAVAILABLE("Minecraft profile keys are unavailable. Restart Minecraft and sign in again"),
        PROFILE_KEYS_EXPIRED("Minecraft profile keys have expired. Restart Minecraft and sign in again"),
        PROOF_FAILED("Minecraft profile proof could not be created"),
        INVALID_RESPONSE("The authentication service returned an invalid response"),
        HTTP_REJECTED("The service rejected the request"),
        TIMEOUT("The service request timed out"),
        DNS("The service address could not be resolved"),
        TLS("A secure connection to the service could not be established"),
        CONNECT("The service connection could not be opened"),
        NETWORK("The service connection failed"),
        CANCELLED("The request was cancelled"),
        UNKNOWN("The service request failed");

        private final String explanation;
        FailureCause(String explanation) { this.explanation = explanation; }
        public String explanation() { return explanation; }
    }

    public static final class SafeFailure extends RuntimeException {
        private final FailureCause reason;
        public SafeFailure(FailureCause reason) { super(reason.explanation()); this.reason = reason; }
        public FailureCause reason() { return reason; }
    }

    public static FailureCause failureCause(Throwable error) {
        FailureCause fallback = FailureCause.UNKNOWN;
        // Bounded traversal also tolerates malformed exception cause chains.
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            if (error instanceof SafeFailure safe) return safe.reason();
            if (error instanceof Rejected || error instanceof java.net.http.WebSocketHandshakeException) return FailureCause.HTTP_REJECTED;
            if (error instanceof java.net.http.HttpTimeoutException || error instanceof java.util.concurrent.TimeoutException) return FailureCause.TIMEOUT;
            if (error instanceof java.net.UnknownHostException || error instanceof java.nio.channels.UnresolvedAddressException) return FailureCause.DNS;
            if (error instanceof javax.net.ssl.SSLException) return FailureCause.TLS;
            if (error instanceof java.util.concurrent.CancellationException) return FailureCause.CANCELLED;
            if (error instanceof java.net.ConnectException) fallback = FailureCause.CONNECT;
            else if (error instanceof java.io.IOException && fallback == FailureCause.UNKNOWN) fallback = FailureCause.NETWORK;
        }
        return fallback;
    }

    public static String proof(UUID uuid, PublicKey publicKey, byte[] mojangSignature, long expiresAt,
                               PrivateKey privateKey, String minecraft, String version, long now) throws Exception {
        if (expiresAt <= now) throw new SafeFailure(FailureCause.PROFILE_KEYS_EXPIRED);
        if (uuid == null || mojangSignature == null || mojangSignature.length == 0)
            throw new SafeFailure(FailureCause.PROFILE_KEYS_UNAVAILABLE);
        byte[] original = new byte[16];
        new SecureRandom().nextBytes(original);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(original);
        Base64.Encoder base64 = Base64.getEncoder();
        JsonObject key = new JsonObject();
        key.addProperty("uuid", uuid.toString());
        key.addProperty("publicKey", base64.encodeToString(publicKey.getEncoded()));
        key.addProperty("publicKeySignature", base64.encodeToString(mojangSignature));
        key.addProperty("expiresAt", expiresAt);
        JsonObject signed = new JsonObject();
        signed.addProperty("original", base64.encodeToString(original));
        signed.addProperty("signed", base64.encodeToString(signer.sign()));
        JsonObject payload = new JsonObject();
        payload.add("keyPair", key);
        payload.add("signedData", signed);
        // Temporary compatibility trial: restore waypointer once its service identity is accepted, before publishing.
        payload.addProperty("mod", "skyblocker");
        payload.addProperty("minecraftVersion", minecraft);
        payload.addProperty("modVersion", version);
        return payload.toString();
    }

    public static CompletableFuture<Token> authenticate(String proof, String userAgent) {
        HttpRequest request = authenticationRequest(proof, userAgent);
        CompletableFuture<HttpResponse<byte[]>> exchange = HTTP.sendAsync(request, ignored -> new LimitedBody());
        CompletableFuture<Token> result = exchange.thenApply(response -> {
            if (response.statusCode() != 200) throw new Rejected(response.statusCode());
            return parseToken(response.body(), System.currentTimeMillis());
        }).orTimeout(20, java.util.concurrent.TimeUnit.SECONDS);
        result.whenComplete((value, error) -> { if (error != null) exchange.cancel(true); });
        return result;
    }

    static HttpRequest authenticationRequest(String proof, String userAgent) {
        return HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(proof, StandardCharsets.UTF_8)).build();
    }

    static Token parseToken(byte[] body, long now) {
        try {
            return parseTokenBody(body, now);
        } catch (RuntimeException invalid) {
            // Gson and numeric parsing errors can contain parts of the authentication response.
            throw new SafeFailure(FailureCause.INVALID_RESPONSE);
        }
    }

    private static Token parseTokenBody(byte[] body, long now) {
        if (body.length > MAX_RESPONSE) throw new IllegalArgumentException("Invalid authentication response");
        JsonObject json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        String token = json.get("token").getAsString();
        long expires = json.get("expiresAt").getAsBigDecimal().longValueExact();
        // Header-safe, bounded, short-lived. Never put token or response body in an error or log.
        if (token.isEmpty() || token.length() > 8192 || !token.matches("[A-Za-z0-9._~+/=-]+")
                || expires <= now + 300_000) {
            throw new IllegalArgumentException("Invalid authentication response");
        }
        return new Token(token, expires);
    }

    public static final class Token {
        private final String value;
        private final long expiresAt;
        Token(String value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
        public String authorization() { return "Bearer " + value; }
        public boolean needsRefresh(long now) { return now >= expiresAt - 300_000; }
        @Override public String toString() { return "hysky token [redacted]"; }
    }

    public static final class Rejected extends RuntimeException {
        private final int status;
        public Rejected(int status) { super("hysky authentication HTTP " + status); this.status = status; }
        public int status() { return status; }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new SafeFailure(FailureCause.INVALID_RESPONSE));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
