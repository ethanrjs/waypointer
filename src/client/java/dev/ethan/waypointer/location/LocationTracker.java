package dev.ethan.waypointer.location;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Picks the best available {@link ZoneSource} at boot and routes its signals into
 * {@link ActiveGroupManager#onZoneChanged(Zone)}.
 *
 * Resolution order:
 *
 *   1. {@code hypixel-mod-api} installed -- use {@link HypixelApiZoneSource}
 *      (authoritative, event-driven).
 *   2. Otherwise -- use {@link ScoreboardZoneResolver} (polls sidebar each tick).
 *
 * This keeps the mod useful standalone but automatically upgrades when the API mod is present.
 */
public final class LocationTracker {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private ZoneSource source;

    public LocationTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        boolean hypixelApi = !config.preferScoreboardFallback()
                && FabricLoader.getInstance().isModLoaded("hypixel-mod-api");
        if (hypixelApi) {
            source = new HypixelApiZoneSource();
            Waypointer.LOGGER.info("Location: using Hypixel Mod API source");
        } else {
            source = new ScoreboardZoneResolver();
            Waypointer.LOGGER.info("Location: using scoreboard fallback"
                    + (config.preferScoreboardFallback() ? " (forced by config)" : " (install hypixel-mod-api for better accuracy)"));
        }
        source.register(manager::onZoneChanged);
    }

    public ZoneSource source() {
        return source;
    }
}
