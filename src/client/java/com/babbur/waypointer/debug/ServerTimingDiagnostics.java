package com.babbur.waypointer.debug;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import java.util.UUID;

/** Passive observations; never sends probes or touches Minecraft state from the network thread. */
public final class ServerTimingDiagnostics {
    private static final ServerTickEstimator ESTIMATOR = new ServerTickEstimator();
    private static Connection connection;
    private static ClientLevel world;
    private static UUID playerId;

    private ServerTimingDiagnostics() {}

    public static ServerTickEstimator estimator() {
        return ESTIMATOR;
    }

    public static void install() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                joined(handler.getConnection(), client.level, client.getUser().getProfileId()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnected(handler.getConnection()));
        ClientTickEvents.START_CLIENT_TICK.register(ServerTimingDiagnostics::tick);
    }

    static synchronized void joined(Connection source, ClientLevel level, UUID profileId) {
        connection = source;
        world = level;
        playerId = profileId;
        reset("joined server");
        readInitialTickState();
    }

    static synchronized void disconnected(Connection source) {
        if (connection == source) {
            connection = null;
            world = null;
            playerId = null;
            reset("disconnected");
        }
    }

    private static void reset(String reason) {
        ESTIMATOR.reset(reason);
    }

    private static void readInitialTickState() {
        if (world != null) {
            ESTIMATOR.setTickState(world.tickRateManager().tickrate(), world.tickRateManager().isFrozen());
        }
    }

    private static synchronized void tick(Minecraft client) {
        if (connection == null) return;
        if (world != client.level) {
            world = client.level;
            reset("world changed");
            readInitialTickState();
        }
        if (world == null) return;
        long now = System.nanoTime();
        ESTIMATOR.recordClientTick(now);
    }

    public static void received(Connection source, Packet<?> packet, long receivedNanos) {
        if (!(packet instanceof ClientboundSetTimePacket || packet instanceof ClientboundTickingStatePacket
                || packet instanceof ClientboundRespawnPacket || packet instanceof BundlePacket<?>
                || packet instanceof ClientboundPlayerInfoUpdatePacket)) return;
        synchronized (ServerTimingDiagnostics.class) {
            if (source == null || source != connection) return;
            observe(packet, receivedNanos);
        }
    }

    private static void observe(Packet<?> packet, long receivedNanos) {
        if (packet instanceof ClientboundSetTimePacket time) {
            ESTIMATOR.recordTime(time.gameTime(), receivedNanos);
        } else if (packet instanceof ClientboundTickingStatePacket state) {
            ESTIMATOR.setTickState(state.tickRate(), state.isFrozen());
        } else if (packet instanceof ClientboundRespawnPacket) {
            reset("server respawn/world transfer");
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket info
                && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY)) {
            // Timestamp actual server latency updates; polling PlayerInfo would keep stale values falsely fresh.
            for (var entry : info.entries()) {
                if (entry.profileId().equals(playerId)) ESTIMATOR.recordPing(entry.latency(), receivedNanos);
            }
        } else if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> child : bundle.subPackets()) observe(child, receivedNanos);
        }
    }
}
