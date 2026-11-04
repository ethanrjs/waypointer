package com.babbur.waypointer.crystal;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class SkyblockerSocketPolicy {
    static final long POLL_MILLIS = 60_000;
    static final long MAX_AGE_MILLIS = 120_000;
    private static final URI ENDPOINT = URI.create("https://waypointermod.com/api/client-policy");
    private final Supplier<CompletableFuture<Boolean>> fetch;
    private final LongSupplier clock;
    private CompletableFuture<Boolean> pending;
    private long nextPoll = Long.MIN_VALUE;
    private long requestedAt;
    private long expiresAt;
    private boolean enabled;
    private String status = "policy_unavailable";

    SkyblockerSocketPolicy(HttpClient http) {
        this(() -> http.sendAsync(HttpRequest.newBuilder(ENDPOINT)
                        .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                        .header("Cache-Control", "no-cache").GET().build(),
                        SkyblockerAuthentication.limitedBody())
                .thenApply(response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("Policy unavailable");
                    return parse(new String(response.body(), StandardCharsets.UTF_8));
                }), () -> System.nanoTime() / 1_000_000);
    }

    SkyblockerSocketPolicy(Supplier<CompletableFuture<Boolean>> fetch, LongSupplier clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    void refresh() {
        long now = clock.getAsLong();
        if (pending != null && pending.isDone()) {
            try {
                enabled = Boolean.TRUE.equals(pending.join());
                expiresAt = requestedAt + MAX_AGE_MILLIS;
                status = enabled ? "allowed" : "remotely_disabled";
            } catch (RuntimeException unavailable) {
                enabled = false;
                status = "policy_unavailable";
            }
            pending = null;
        }
        if (pending != null && now - requestedAt >= 10_000) {
            pending.cancel(true);
            pending = null;
            enabled = false;
            status = "policy_unavailable";
        }
        if (pending == null && now >= nextPoll) {
            requestedAt = now;
            nextPoll = now + POLL_MILLIS;
            try {
                pending = fetch.get();
            } catch (RuntimeException unavailable) {
                enabled = false;
                status = "policy_unavailable";
            }
        }
    }

    boolean allowed() {
        return enabled && clock.getAsLong() < expiresAt;
    }

    String status() {
        return enabled && !allowed() ? "policy_expired" : status;
    }

    static boolean parse(String json) {
        var value = JsonParser.parseString(json).getAsJsonObject().get("skyblockerSocketEnabled");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid socket policy");
        }
        return value.getAsBoolean();
    }
}
