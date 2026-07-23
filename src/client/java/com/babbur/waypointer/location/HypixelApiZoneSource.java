package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.minecraft.client.Minecraft;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Authoritative zone source backed by the Hypixel Mod API's location event packet.
 * Hypixel sends this packet every time the player switches instances, so we never
 * have to poll anything -- each packet is a guaranteed correct state update.
 *
 * <p>Glacite Mineshaft layouts are a special case: the packet can stay on the
 * broad Dwarven or mineshaft location while the sidebar carries the layout code.
 * We re-check that code every few ticks. Connected Dwarven surface areas stay in
 * the packet-backed {@code dwarven_mines} zone.
 *
 * <p>Debounced on the Zone id so two identical transitions (e.g. re-login) don't
 * trigger duplicate load/unload cycles in {@code ActiveGroupManager}.
 */
public final class HypixelApiZoneSource implements ZoneSource {

    private static final int REFINE_POLL_TICKS = 2;
    private static volatile DebugSnapshot lastDebugSnapshot;

    private Zone lastEmitted;
    /** Last raw zone from {@link Zone#resolve(String, String, String)} before sidebar refinement. */
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
    }

    private void handleLocationPacket(ClientboundLocationPacket packet) {
        String serverType = packet.getServerType().map(s -> s.name()).orElse(null);
        String map  = packet.getMap().orElse(null);
        String mode = packet.getMode().orElse(null);

        lastRawPacketZone = Zone.resolve(serverType, map, mode);
        emitRefined(serverType, map, mode);
    }

    private void onTick(Minecraft mc) {
        if (++tickCounter < REFINE_POLL_TICKS) return;
        tickCounter = 0;
        if (lastRawPacketZone == null) return;
        if (!shouldPollSidebarRefinement(lastRawPacketZone)) return;
        emitRefined(null, null, null);
    }

    private static boolean shouldPollSidebarRefinement(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        if ("dwarven_mines".equals(id) || "mineshaft".equals(id)) return true;
        return CatacombsFloorRefiner.shouldPoll(zone);
    }

    /**
     * @param serverType map mode only used for logging on packet path; may be null on tick path
     */
    private void emitRefined(String serverType, String map, String mode) {
        Minecraft mc = Minecraft.getInstance();
        String blob = SidebarTexts.collectColorStripped(mc);
        String sidebarText = blob != null ? blob : "";
        Zone refined = Zone.refineIfDwarvenMinesContext(lastRawPacketZone, sidebarText);
        refined = CatacombsFloorRefiner.refine(refined, sidebarText);
        lastDebugSnapshot = new DebugSnapshot(serverType, map, mode, lastRawPacketZone, refined, Instant.now());

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

    public record DebugSnapshot(
            String serverType,
            String map,
            String mode,
            Zone rawZone,
            Zone refinedZone,
            Instant capturedAt) {}
}
