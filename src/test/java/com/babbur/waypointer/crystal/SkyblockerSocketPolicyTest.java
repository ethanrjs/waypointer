package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SkyblockerSocketPolicyTest {
    @Test void requiresFreshPermissionAndRecoversAfterRevocationOrFailure() {
        var now = new AtomicLong(1_000);
        var calls = new AtomicInteger();
        var response = new AtomicReference<>(CompletableFuture.<Boolean>completedFuture(true));
        var policy = new SkyblockerSocketPolicy(() -> { calls.incrementAndGet(); return response.get(); }, now::get);
        assertFalse(policy.allowed());
        policy.refresh();
        policy.refresh();
        assertTrue(policy.allowed());
        assertEquals(1, calls.get());
        now.addAndGet(SkyblockerSocketPolicy.POLL_MILLIS);
        response.set(CompletableFuture.completedFuture(false));
        policy.refresh();
        policy.refresh();
        assertFalse(policy.allowed());
        assertEquals("remotely_disabled", policy.status());
        now.addAndGet(SkyblockerSocketPolicy.POLL_MILLIS);
        response.set(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        policy.refresh();
        policy.refresh();
        assertFalse(policy.allowed());
        assertEquals("policy_unavailable", policy.status());
        now.addAndGet(SkyblockerSocketPolicy.POLL_MILLIS);
        response.set(CompletableFuture.completedFuture(true));
        policy.refresh();
        policy.refresh();
        assertTrue(policy.allowed());
        now.addAndGet(SkyblockerSocketPolicy.MAX_AGE_MILLIS);
        assertFalse(policy.allowed());
        assertEquals("policy_expired", policy.status());
    }

    @Test void hungOrLateResponsesCannotGrantIndefinitePermission() {
        var now = new AtomicLong();
        var response = new CompletableFuture<Boolean>();
        var policy = new SkyblockerSocketPolicy(() -> response, now::get);
        policy.refresh();
        now.set(10_000);
        policy.refresh();
        assertTrue(response.isCancelled());
        assertFalse(policy.allowed());
        assertFalse(response.complete(true));

        var late = new CompletableFuture<Boolean>();
        policy = new SkyblockerSocketPolicy(() -> late, now::get);
        policy.refresh();
        late.complete(true);
        now.addAndGet(SkyblockerSocketPolicy.MAX_AGE_MILLIS);
        policy.refresh();
        assertFalse(policy.allowed());
    }

    @Test void requiresAnActualJsonBoolean() {
        assertTrue(SkyblockerSocketPolicy.parse("{\"skyblockerSocketEnabled\":true}"));
        assertFalse(SkyblockerSocketPolicy.parse("{\"skyblockerSocketEnabled\":false}"));
        for (String invalid : new String[]{"{}", "null", "[]", "invalid", "{\"skyblockerSocketEnabled\":\"true\"}",
                "{\"skyblockerSocketEnabled\":1}", "{\"skyblockerSocketEnabled\":null}"}) {
            assertThrows(RuntimeException.class, () -> SkyblockerSocketPolicy.parse(invalid), invalid);
        }
    }
}
