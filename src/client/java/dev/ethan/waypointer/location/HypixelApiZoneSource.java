package dev.ethan.waypointer.location;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Authoritative zone source backed by the Hypixel Mod API's location event packet.
 * Hypixel sends this packet every time the player switches instances, so we never
 * have to poll anything -- each packet is a guaranteed correct state update.
 *
 * <p>Dwarven glacite sub-areas are a special case: the packet often keeps reporting
 * {@code mining_3} (Dwarven Mines) while the sidebar shows {@code Glacite Tunnels},
 * {@code Great Glacite Lake}, etc. We therefore refine {@link Zone#resolve(String, String, String)} output
 * using the live sidebar (same signals as Skyblocker's {@code Area.DwarvenMines})
 * and re-check every few ticks so walking between tunnels and the hub without a new
 * packet still updates the zone id.
 *
 * <p>Debounced on the Zone id so two identical transitions (e.g. re-login) don't
 * trigger duplicate load/unload cycles in {@code ActiveGroupManager}.
 */
public final class HypixelApiZoneSource implements ZoneSource {

    private static final int REFINE_POLL_TICKS = 2;

    private Zone lastEmitted;
    /** Last raw zone from {@link Zone#resolve(String, String, String)} before sidebar refinement. */
    private Zone lastRawPacketZone;
    private int tickCounter;

    @Override
    public void register(Consumer<Zone> listener) {
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
        api.createHandler(ClientboundLocationPacket.class, packet -> {
            String serverType = packet.getServerType().map(s -> s.name()).orElse(null);
            String map  = packet.getMap().orElse(null);
            String mode = packet.getMode().orElse(null);

            Zone raw = Zone.resolve(serverType, map, mode);
            lastRawPacketZone = raw;
            emitRefined(listener, serverType, map, mode);
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> onTick(mc, listener));
    }

    private void onTick(Minecraft mc, Consumer<Zone> listener) {
        if (++tickCounter < REFINE_POLL_TICKS) return;
        tickCounter = 0;
        if (lastRawPacketZone == null) return;
        String id = lastRawPacketZone.id();
        if (!"dwarven_mines".equals(id) && !"mineshaft".equals(id)) return;
        emitRefined(listener, null, null, null);
    }

    /**
     * @param serverType map mode only used for logging on packet path; may be null on tick path
     */
    private void emitRefined(Consumer<Zone> listener, String serverType, String map, String mode) {
        Minecraft mc = Minecraft.getInstance();
        String blob = SidebarTexts.collectColorStripped(mc);
        Zone refined = Zone.refineIfDwarvenMinesContext(lastRawPacketZone, blob != null ? blob : "");

        if (!Objects.equals(refined, lastEmitted)) {
            lastEmitted = refined;
            if (serverType != null) {
                Waypointer.LOGGER.info("Location event: {} / map={} mode={} -> zone={} (raw={})",
                        serverType, map, mode, refined, lastRawPacketZone);
            } else {
                Waypointer.LOGGER.debug("Location refine (tick): raw={} -> zone={}", lastRawPacketZone, refined);
            }
            listener.accept(refined);
        }
    }
}
