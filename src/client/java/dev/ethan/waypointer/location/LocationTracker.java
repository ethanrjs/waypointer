package dev.ethan.waypointer.location;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;

/**
 * Picks the best available {@link ZoneSource} at boot and routes its signals into
 * {@link ActiveGroupManager#onZoneChanged(Zone)}.
 *
 * Zone detection is backed by hypixel-mod-api. The older scoreboard source was
 * too noisy for reliable route activation, so the API mod is now required.
 */
public final class LocationTracker {

    private final ActiveGroupManager manager;
    private ZoneSource source;

    public LocationTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
    }

    public void install() {
        source = new HypixelApiZoneSource();
        Waypointer.LOGGER.info("Location: using Hypixel Mod API source");
        source.register(manager::onZoneChanged);
    }

    public ZoneSource source() {
        return source;
    }
}
