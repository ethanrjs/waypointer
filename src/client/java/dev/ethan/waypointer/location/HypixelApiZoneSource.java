package dev.ethan.waypointer.location;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.Zone;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Authoritative zone source backed by the Hypixel Mod API's location event packet.
 * Hypixel sends this packet every time the player switches instances, so we never
 * have to poll anything -- each packet is a guaranteed correct state update.
 *
 * Debounced on the Zone id so two identical transitions (e.g. re-login) don't
 * trigger duplicate load/unload cycles in {@code ActiveGroupManager}.
 */
public final class HypixelApiZoneSource implements ZoneSource {

    private Zone lastZone;

    @Override
    public void register(Consumer<Zone> listener) {
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
        api.createHandler(ClientboundLocationPacket.class, packet -> {
            String serverType = packet.getServerType().map(s -> s.name()).orElse(null);
            String map  = packet.getMap().orElse(null);
            String mode = packet.getMode().orElse(null);

            Zone z = Zone.resolve(serverType, map, mode);
            if (!Objects.equals(z, lastZone)) {
                lastZone = z;
                Waypointer.LOGGER.info("Location event: {} / map={} mode={} -> zone={}", serverType, map, mode, z);
                listener.accept(z);
            }
        });
    }
}
