package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
            var tick = StructureRelayController.class.getDeclaredMethod("tick", net.minecraft.client.Minecraft.class);
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
        assertFalse(controller.syncDue(399_000, 15_980)); // Normal 20 TPS.
        assertTrue(controller.syncDue(400_000, 16_000));
        assertFalse(controller.syncDue(154_000, 10_000)); // Server disallows sync before 55 seconds.
        assertTrue(controller.syncDue(155_000, 10_000)); // Stalled game clock.
        assertFalse(controller.syncDue(279_000, 12_864)); // 16 TPS, less than 45 seconds drift.
        assertTrue(controller.syncDue(325_000, 13_600)); // 16 TPS, resync before 60 seconds drift.
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
