package com.babbur.waypointer.debug;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTimingDiagnosticsTest {
    @Test
    void routesBundlesOnlyFromCurrentConnectionAndClearsOnDisconnect() {
        Connection current = new Connection(PacketFlow.CLIENTBOUND);
        Connection previous = new Connection(PacketFlow.CLIENTBOUND);
        var estimator = ServerTimingDiagnostics.estimator();
        UUID player = UUID.randomUUID();
        try {
            ServerTimingDiagnostics.joined(current, null, player);
            var packet = new ClientboundSetTimePacket(100, Map.of());
            ServerTimingDiagnostics.received(previous, packet, 1_000_000_000L);
            ServerTimingDiagnostics.received(null, packet, 1_000_000_000L);
            assertEquals(0, estimator.snapshot(1_000_000_000L).shortWindow().samples());
            ServerTimingDiagnostics.received(current, new ClientboundBundlePacket(List.of(
                    packet, new ClientboundTickingStatePacket(10, true))), 1_000_000_000L);
            var snapshot = estimator.snapshot(1_000_000_000L);
            assertEquals(1, snapshot.shortWindow().samples());
            assertEquals(10, snapshot.targetTps());
            assertTrue(snapshot.frozen());
            ServerTimingDiagnostics.received(current, latency(UUID.randomUUID(), 900), 2_000_000_000L);
            assertEquals(-1, estimator.snapshot(2_000_000_000L).latestPing());
            ServerTimingDiagnostics.received(current, latency(player, 120), 3_000_000_000L);
            assertEquals(120, estimator.snapshot(8_000_000_000L).latestPing());
            assertEquals(5, estimator.snapshot(8_000_000_000L).pingAgeSeconds());
            ServerTimingDiagnostics.received(current, latency(player, 120), 9_000_000_000L);
            assertEquals(0, estimator.snapshot(9_000_000_000L).pingAgeSeconds());
            ServerTimingDiagnostics.disconnected(previous);
            assertEquals(1, estimator.snapshot(9_000_000_000L).shortWindow().samples());
            ServerTimingDiagnostics.disconnected(current);
            ServerTimingDiagnostics.received(current, packet, 10_000_000_000L);
            assertEquals(0, estimator.snapshot(10_000_000_000L).shortWindow().samples());
            assertEquals("disconnected", estimator.snapshot(10_000_000_000L).resetReason());
        } finally {
            ServerTimingDiagnostics.disconnected(current);
        }
    }

    private static ClientboundPlayerInfoUpdatePacket latency(UUID player, int millis) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY),
                    ClientboundPlayerInfoUpdatePacket.Action.class);
            buffer.writeVarInt(1);
            buffer.writeUUID(player);
            buffer.writeVarInt(millis);
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
