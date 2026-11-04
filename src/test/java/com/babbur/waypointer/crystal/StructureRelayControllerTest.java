package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureRelayControllerTest {
    @Test
    void confidenceUpgradeAtSameCoordinatesIsPublishedImmediately() {
        var compass = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                600, 90, 300, SightingConfidence.COMPASS, "compass", 1);
        var entity = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                600, 90, 300, SightingConfidence.ENTITY, "entity", 2);
        assertNotEquals(StructureRelayController.publicationKey(compass),
                StructureRelayController.publicationKey(entity));
    }

    @TempDir Path directory;

    @Test
    void diagnosticsAreReadOnlyAndDoNotExposePublicationKeys() throws Exception {
        var controller = new StructureRelayController(null, new WaypointerConfig());
        set(controller, "nextConnect", 5000L);
        set(controller, "failures", 3);
        set(controller, "diagnosticGeneration", 7L);
        var sent = new LinkedHashMap<String, Long>();
        sent.put("private-publication-key", 1000L);
        set(controller, "sent", sent);
        set(controller, "publications", new ArrayDeque<>(List.of(1000L, 2000L)));
        var first = controller.diagnostics(3000);
        assertEquals(first, controller.diagnostics(3000));
        assertEquals(2000, first.backoffRemainingMillis());
        assertEquals(3, first.failures());
        assertEquals(7, first.generation());
        assertEquals(1, first.sentCacheCount());
        assertEquals(2, first.publicationWindowCount());
        assertEquals(-1, first.inboundQueueCount());
        assertEquals("not_required", first.authentication());
        assertFalse(first.toString().contains("private-publication-key"));
        assertEquals(0, controller.diagnostics(6000).backoffRemainingMillis());
        assertEquals(1, sent.size());
    }

    @Test
    void optOutCancelsPendingAndDiscardsQueuedPeerData() throws Exception {
        for (boolean opened : new boolean[]{false, true}) {
            var config = new WaypointerConfig();
            set(config, "crystalHollowsRemoteSharing", false);
            var store = new CrystalHollowsStore(directory.resolve("structures.json"));
            var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), config, store);
            var lobby = new CrystalHollowsLobbyState("m14ap", System.currentTimeMillis(), 1);
            lobby.merge(new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                    600, 100, 300, SightingConfidence.SHARED_REMOTE, "relay", System.currentTimeMillis()));
            set(tracker, "lobby", lobby);
            var pending = new CompletableFuture<WebSocket>();
            var listener = new AtomicReference<WebSocket.Listener>();
            var connection = new StructureRelayConnection((uri, receiver) -> {
                listener.set(receiver);
                return pending;
            });
            var controller = new StructureRelayController(tracker, config);
            set(controller, "connection", connection);
            set(controller, "lobby", lobby);
            AtomicInteger sends = new AtomicInteger();
            var socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                        if (method.getName().equals("sendText")) sends.incrementAndGet();
                        return null;
                    });
            connection.connect(URI.create("wss://example.invalid"));
            if (opened) {
                listener.get().onOpen(socket);
                listener.get().onText(socket, "{\"type\":\"snapshot\",\"sightings\":[]}", true);
            }
            var tick = StructureRelayController.class.getDeclaredMethod("tick", Minecraft.class);
            tick.setAccessible(true);
            tick.invoke(controller, new Object[]{null});
            assertTrue(pending.isCancelled());
            assertFalse(connection.isOpen());
            assertFalse(connection.isConnecting());
            assertNull(connection.poll());
            assertTrue(lobby.sightings().isEmpty());
            listener.get().onOpen(socket);
            listener.get().onText(socket, "late", true);
            assertNull(connection.poll());
            assertEquals(0, sends.get());
            store.discardPendingSave();
        }
    }

    @Test
    void snapshotsStayInfrequentButResyncBeforeLagExceedsRelayTolerance() throws Exception {
        var controller = new StructureRelayController(null, null);
        set(controller, "lastSyncAt", 100_000L);
        set(controller, "lastSyncAge", 10_000L);
        set(controller, "nextSync", 400_000L);
        assertFalse(controller.syncDue(399_000, 15_980));
        assertTrue(controller.syncDue(400_000, 16_000));
        assertFalse(controller.syncDue(154_000, 10_000));
        assertTrue(controller.syncDue(155_000, 10_000));
        assertFalse(controller.syncDue(279_000, 12_864));
        assertTrue(controller.syncDue(325_000, 13_600));
    }

    @Test
    void firstClosingCountdownBypassesSyncThrottleOnlyOnce() throws Exception {
        var controller = new StructureRelayController(null, null);
        set(controller, "lastSyncAt", 100_000L);
        set(controller, "lastSyncAge", 10_000L);
        set(controller, "nextSync", 400_000L);
        assertTrue(controller.syncDue(101_000, 10_020, 60));
        assertTrue(controller.syncDue(154_000, 11_080, 191));
        assertTrue(controller.syncDue(155_000, 11_100, 190));
        set(controller, "reportedClosing", true);
        assertFalse(controller.syncDue(156_000, 11_120, 189));
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
