package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.function.BooleanSupplier;

/** Uses the Hypixel Mod API when available, otherwise the scoreboard fallback. */
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
        ClientTickEvents.END_CLIENT_TICK.register(this::detectPrivateWorld);
    }

    static ZoneSource createSource(boolean hypixelApiLoaded) {
        return hypixelApiLoaded ? new HypixelApiZoneSource() : new ScoreboardZoneResolver();
    }

    private void detectPrivateWorld(Minecraft minecraft) {
        Zone current = manager.currentZone();
        manager.onZoneChanged(zoneAfterPrivateWorldCheck(
                current, minecraft.level != null, minecraft.getCurrentServer() != null));
    }

    static Zone zoneAfterPrivateWorldCheck(Zone current, boolean worldLoaded, boolean remoteServer) {
        if (!worldLoaded) return null;
        if (!remoteServer) return Zone.PRIVATE_WORLD;
        return Zone.PRIVATE_WORLD.equals(current) ? null : current;
    }

    public ZoneSource source() {
        return source;
    }
}
