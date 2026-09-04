package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.minecraft.client.Minecraft;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class HypixelApiZoneSource implements ZoneSource {

    private static final int REFINE_POLL_TICKS = 2;
    private static volatile DebugSnapshot lastDebugSnapshot;
    private static volatile String lastServerName;

    private Zone lastEmitted;
    private Zone lastRawPacketZone;
    private Consumer<Zone> listener;
    private int tickCounter;

    @Override
    public void register(Consumer<Zone> listener) {
        this.listener = listener;
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
        api.createHandler(ClientboundLocationPacket.class, this::handleLocationPacket);

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetLocation());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetLocation());
    }

    private void handleLocationPacket(ClientboundLocationPacket packet) {
        String serverType = packet.getServerType().map(s -> s.name()).orElse(null);
        String map  = packet.getMap().orElse(null);
        String mode = packet.getMode().orElse(null);
        lastServerName = packet.getServerName();

        lastRawPacketZone = Zone.resolve(serverType, map, mode);
        emitRefined(serverType, map, mode);
    }

    private void onTick(Minecraft mc) {
        if (mc.level == null || mc.getCurrentServer() == null) {
            resetLocation();
            return;
        }
        if (++tickCounter < REFINE_POLL_TICKS) return;
        tickCounter = 0;
        if (lastRawPacketZone == null) return;
        if (!shouldPollSidebarRefinement(lastRawPacketZone)) return;
        emitRefined(null, null, null);
    }

    private void resetLocation() {
        lastRawPacketZone = null;
        lastServerName = null;
        tickCounter = 0;
        lastDebugSnapshot = null;
        if (lastEmitted == null) return;
        lastEmitted = null;
        if (listener != null) listener.accept(null);
    }

    private static boolean shouldPollSidebarRefinement(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        if ("dwarven_mines".equals(id) || "mineshaft".equals(id)) return true;
        return CatacombsFloorRefiner.shouldPoll(zone);
    }

    private void emitRefined(String serverType, String map, String mode) {
        Minecraft mc = Minecraft.getInstance();
        String blob = SidebarTexts.collectColorStripped(mc);
        String sidebarText = blob != null ? blob : "";
        Zone refined = Zone.refineIfDwarvenMinesContext(lastRawPacketZone, sidebarText);
        refined = CatacombsFloorRefiner.refine(refined, sidebarText);
        lastDebugSnapshot = new DebugSnapshot(
                serverType, map, mode, lastServerName, lastRawPacketZone, refined, Instant.now());

        if (!Objects.equals(refined, lastEmitted)) {
            lastEmitted = refined;
            if (serverType != null) {
                Waypointer.LOGGER.info("Location event: {} / map={} mode={} -> zone={} (raw={})",
                        serverType, map, mode, refined, lastRawPacketZone);
            } else {
                Waypointer.LOGGER.debug("Location refine (tick): raw={} -> zone={}", lastRawPacketZone, refined);
            }
            if (listener != null) listener.accept(refined);
        }
    }

    public static DebugSnapshot debugSnapshot() {
        return lastDebugSnapshot;
    }

    public static String lastServerName() {
        return lastServerName;
    }

    public record DebugSnapshot(
            String serverType,
            String map,
            String mode,
            String serverName,
            Zone rawZone,
            Zone refinedZone,
            Instant capturedAt) {}
}
