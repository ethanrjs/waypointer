package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;
import com.babbur.waypointer.config.WaypointerConfig;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.world.entity.player.ProfileKeyPair;
import org.junit.jupiter.api.Test;

class SkyblockerStructureControllerTest {
    @Test void staleHandshakeCallbackCannotOverwriteTheCurrentRejection() throws Exception {
        var controller = new SkyblockerStructureController(null, new WaypointerConfig());
        set(controller, "generation", 2L);
        controller.recordHandshakeFailure(2L, new SkyblockerAuthentication.Rejected(403));
        var rejection = controller.diagnostics(0);
        assertEquals(403, rejection.lastFailureStatus());
        controller.recordHandshakeFailure(1L, new java.util.concurrent.CancellationException());
        assertEquals(rejection, controller.diagnostics(0));
    }

    @Test void successfulAuthenticationConnectsWithoutWaitingForTheAttemptTimeout() throws Exception {
        var controller = new SkyblockerStructureController(null, new WaypointerConfig());
        var keys = new ProfileKeyPairManager() {
            public CompletableFuture<Optional<ProfileKeyPair>> prepareKeyPair() { return new CompletableFuture<>(); }
            public boolean shouldRefreshKeyPair() { return false; }
        };
        var attempt = new SkyblockerStructureController.AuthAttempt(keys, UUID.randomUUID(), "test", "test", "test");
        set(controller, "authentication", attempt);
        set(controller, "nextAttempt", 31_000L);
        assertFalse(controller.completeAuthentication(null, 2_000));
        var leave = SkyblockerStructureController.class.getDeclaredMethod("leaveLobby");
        leave.setAccessible(true);
        for (int i = 0; i < 100; i++) leave.invoke(controller);
        assertFalse(attempt.result.isDone());
        assertEquals("pending", controller.diagnostics(2_000).authentication());
        attempt.result.complete(new SkyblockerAuthentication.Token("test", 9999999L));
        assertTrue(controller.completeAuthentication(null, 2_000));
        assertEquals("ready", controller.diagnostics(2_000).authentication());
        assertEquals(0, controller.diagnostics(2_000).backoffRemainingMillis());
    }

    @Test void optOutCancelsAuthenticationRetainedAcrossALobbyHop() throws Exception {
        var config = new WaypointerConfig();
        var controller = new SkyblockerStructureController(null, config);
        var shared = new CompletableFuture<Optional<ProfileKeyPair>>();
        var keys = new ProfileKeyPairManager() {
            public CompletableFuture<Optional<ProfileKeyPair>> prepareKeyPair() { return shared; }
            public boolean shouldRefreshKeyPair() { return false; }
        };
        var attempt = new SkyblockerStructureController.AuthAttempt(keys, UUID.randomUUID(), "test", "test", "test");
        set(controller, "authentication", attempt);
        var leave = SkyblockerStructureController.class.getDeclaredMethod("leaveLobby");
        leave.setAccessible(true);
        leave.invoke(controller);
        assertFalse(attempt.result.isDone());
        config.setCrystalHollowsSkyblockerSharing(false);
        var tick = SkyblockerStructureController.class.getDeclaredMethod("tick", Minecraft.class);
        tick.setAccessible(true);
        tick.invoke(controller, new Object[]{null});
        assertTrue(attempt.result.isCancelled());
        assertFalse(shared.isCancelled());
        assertEquals("none", controller.diagnostics(2_000).authentication());
    }

    @Test void authenticationCompletedOutsideTheLobbyCannotConnectWithAnExpiredToken() throws Exception {
        var controller = new SkyblockerStructureController(null, new WaypointerConfig());
        var keys = new ProfileKeyPairManager() {
            public CompletableFuture<Optional<ProfileKeyPair>> prepareKeyPair() { return new CompletableFuture<>(); }
            public boolean shouldRefreshKeyPair() { return false; }
        };
        var attempt = new SkyblockerStructureController.AuthAttempt(keys, UUID.randomUUID(), "test", "test", "test");
        attempt.result.complete(new SkyblockerAuthentication.Token("test", 500_000L));
        set(controller, "authentication", attempt);
        assertFalse(controller.completeAuthentication(null, 600_000));
        assertEquals("none", controller.diagnostics(600_000).authentication());
        assertEquals(0, controller.diagnostics(600_000).backoffRemainingMillis());
    }

    @Test void lobbyHopsReuseAuthenticationAndPreserveFailureBackoffWhileIdleTicksStayIdle() throws Exception {
        var config = new WaypointerConfig();
        var controller = new SkyblockerStructureController(null, config);
        set(controller, "token", new SkyblockerAuthentication.Token("test", 9999999L));
        set(controller, "nextAttempt", 31_000L);
        var leave = SkyblockerStructureController.class.getDeclaredMethod("leaveLobby");
        leave.setAccessible(true);
        for (int i = 0; i < 100; i++) leave.invoke(controller);
        assertEquals("ready", controller.diagnostics(2_000).authentication());
        assertEquals(29_000, controller.diagnostics(2_000).backoffRemainingMillis());
        assertEquals(0, controller.diagnostics(2_000).generation());
        config.setCrystalHollowsSkyblockerSharing(false);
        var tick = SkyblockerStructureController.class.getDeclaredMethod("tick", Minecraft.class);
        tick.setAccessible(true);
        tick.invoke(controller, new Object[]{null});
        assertEquals("none", controller.diagnostics(2_000).authentication());
    }

    @Test void noChatReportsFallbackUsesOnlyTheAuthenticationManagerWithoutChangingTheGetter() {
        var shared = new CompletableFuture<Optional<ProfileKeyPair>>();
        ProfileKeyPairManager actual = new ProfileKeyPairManager() {
            public CompletableFuture<Optional<ProfileKeyPair>> prepareKeyPair() { return shared; }
            public boolean shouldRefreshKeyPair() { return false; }
        };
        var client = new FakeKeyOwner(actual);
        assertSame(actual, SkyblockerStructureController.authenticationKeys(client,
                ProfileKeyPairManager.EMPTY_KEY_MANAGER, true, FakeKeyOwner.class));
        assertSame(ProfileKeyPairManager.EMPTY_KEY_MANAGER, SkyblockerStructureController.authenticationKeys(client,
                ProfileKeyPairManager.EMPTY_KEY_MANAGER, false, FakeKeyOwner.class));
        assertSame(actual, SkyblockerStructureController.authenticationKeys(null, actual, true, Object.class));
        assertThrows(SkyblockerAuthentication.SafeFailure.class, () -> SkyblockerStructureController.authenticationKeys(
                new Object(), ProfileKeyPairManager.EMPTY_KEY_MANAGER, true, Object.class));
        assertFalse(shared.isDone()); // Merely choosing the manager never requests keys or starts authentication.
    }

    private static final class FakeKeyOwner {
        private final ProfileKeyPairManager profileKeyPairManager;
        private FakeKeyOwner(ProfileKeyPairManager keys) { profileKeyPairManager = keys; }
    }

    @Test void emptyProfileKeysProduceASpecificSafeCauseWithoutAnExchange() throws Exception {
        var attempt = new SkyblockerStructureController.AuthAttempt(ProfileKeyPairManager.EMPTY_KEY_MANAGER,
                UUID.randomUUID(), "test", "test", "Waypointer/test");
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> attempt.result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        var cause = SkyblockerAuthentication.failureCause(failure);
        assertEquals(SkyblockerAuthentication.FailureCause.PROFILE_KEYS_UNAVAILABLE, cause);
        var exchange = attempt.getClass().getDeclaredField("exchange");
        exchange.setAccessible(true);
        assertNull(exchange.get(attempt));
        assertTrue(SkyblockerStructureController.failureMessage(cause, 0).contains("profile keys"));
    }

    @Test void diagnosticsExposeCountsAndSafeFailureWithoutAccountOrPayloadData() throws Exception {
        var controller = new SkyblockerStructureController(null, new WaypointerConfig());
        set(controller, "server", "private-server");
        set(controller, "player", UUID.randomUUID());
        set(controller, "token", new SkyblockerAuthentication.Token("private-token", 9999999L));
        set(controller, "nextAttempt", 5000L);
        set(controller, "generation", 12L);
        set(controller, "failures", 2);
        set(controller, "published", java.util.EnumSet.of(CrystalHollowsStructure.MINES_OF_DIVAN));
        set(controller, "lastFailureCause", SkyblockerAuthentication.FailureCause.TIMEOUT);
        set(controller, "lastFailureStage", "authentication");
        var first = controller.diagnostics(3000);
        assertEquals(first, controller.diagnostics(3000));
        assertEquals("ready", first.authentication());
        assertEquals(2000, first.backoffRemainingMillis());
        assertEquals(12, first.generation());
        assertEquals(2, first.failures());
        assertEquals(1, first.publishedCount());
        assertEquals(-1, first.inboundQueueCount());
        assertEquals(SkyblockerAuthentication.FailureCause.TIMEOUT, first.lastFailureCause());
        assertFalse(first.toString().contains("private"));
        assertEquals(0, controller.diagnostics(6000).backoffRemainingMillis());
        set(controller, "denied", true);
        assertEquals(0, controller.diagnostics(3000).backoffRemainingMillis());
    }

    @Test void optOutClosesPendingAndOpenSocketsWithoutReadingMinecraft() throws Exception {
        for (boolean opened : new boolean[]{false, true}) {
            var config = new WaypointerConfig();
            config.setCrystalHollowsSkyblockerSharing(false);
            assertFalse(config.crystalHollowsSkyblockerSharing());
            var controller = new SkyblockerStructureController(null, config);
            var future = new CompletableFuture<WebSocket>();
            var listener = new AtomicReference<WebSocket.Listener>();
            var connection = new StructureRelayConnection((uri, receiver) -> {
                listener.set(receiver);
                return future;
            });
            set(controller, "connection", connection);
            var sends = new AtomicInteger();
            var socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                        if (method.getName().equals("sendText")) sends.incrementAndGet();
                        return null;
                    });
            connection.connect(URI.create("wss://example.invalid"));
            if (opened) {
                listener.get().onOpen(socket);
                listener.get().onText(socket, "queued peer payload", true);
            }
            var tick = SkyblockerStructureController.class.getDeclaredMethod("tick", Minecraft.class);
            tick.setAccessible(true);
            tick.invoke(controller, new Object[]{null});
            assertTrue(future.isCancelled());
            assertFalse(connection.isOpen());
            assertFalse(connection.isConnecting());
            assertNull(connection.poll());
            listener.get().onOpen(socket);
            listener.get().onText(socket, "late peer payload", true);
            assertNull(connection.poll());
            assertEquals(0, sends.get());
        }
    }

    @Test void cancellationDoesNotCancelSharedMinecraftKeysOrStartAnAuthExchange() throws Exception {
        var shared = new CompletableFuture<Optional<ProfileKeyPair>>();
        var keys = new ProfileKeyPairManager() {
            public CompletableFuture<Optional<ProfileKeyPair>> prepareKeyPair() { return shared; }
            public boolean shouldRefreshKeyPair() { return false; }
        };
        var attempt = new SkyblockerStructureController.AuthAttempt(keys, UUID.randomUUID(), "test", "test", "Waypointer/test");
        attempt.cancel();
        assertFalse(shared.isCancelled());
        assertTrue(attempt.result.isCancelled());
        shared.complete(Optional.empty());
        var exchange = attempt.getClass().getDeclaredField("exchange");
        exchange.setAccessible(true);
        assertNull(exchange.get(attempt));
    }

    @Test void rejectedStatusSurvivesFutureWrappingWithoutLoggingBodies() {
        assertEquals(403, SkyblockerStructureController.status(new java.util.concurrent.CompletionException(
                new SkyblockerAuthentication.Rejected(403))));
        assertEquals(0, SkyblockerStructureController.status(new IllegalStateException("unavailable")));
    }

    private static void set(Object object, String field, Object value) throws Exception {
        var reflected = object.getClass().getDeclaredField(field);
        reflected.setAccessible(true);
        reflected.set(object, value);
    }
}
