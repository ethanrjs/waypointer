package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.loader.api.FabricLoader;

import java.util.function.BooleanSupplier;

/**
 * Picks the best available {@link ZoneSource} at boot and routes its signals into
 * {@link ActiveGroupManager#onZoneChanged(Zone)}.
 *
 * Resolution order:
 *
 *   1. {@code hypixel-mod-api} installed -- use {@link HypixelApiZoneSource}
 *      (authoritative, event-driven).
 *   2. Otherwise -- use {@link ScoreboardZoneResolver} as a defensive fallback.
 *
 * Hypixel Mod API is a dependency now, so there is no user-facing override for
 * forcing scoreboard detection.
 */
public final class LocationTracker {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final BooleanSupplier hypixelApiLoaded;
    private ZoneSource source;

    public LocationTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, () -> FabricLoader.getInstance().isModLoaded("hypixel-mod-api"));
    }

    LocationTracker(ActiveGroupManager manager, WaypointerConfig config, BooleanSupplier hypixelApiLoaded) {
        this.manager = manager;
        this.config = config;
        this.hypixelApiLoaded = hypixelApiLoaded;
    }

    public void install() {
        boolean hypixelApi = hypixelApiLoaded.getAsBoolean();
        source = createSource(hypixelApi);
        if (source instanceof HypixelApiZoneSource) {
            Waypointer.LOGGER.info("Location: using Hypixel Mod API source");
        } else {
            Waypointer.LOGGER.info("Location: using scoreboard fallback (hypixel-mod-api missing)");
        }
        source.register(manager::onZoneChanged);
    }

    static ZoneSource createSource(boolean hypixelApiLoaded) {
        return hypixelApiLoaded ? new HypixelApiZoneSource() : new ScoreboardZoneResolver();
    }

    public ZoneSource source() {
        return source;
    }
}
