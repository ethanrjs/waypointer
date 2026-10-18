package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.function.BooleanSupplier;

public final class LocationTracker {
    private static LocationTracker installed;

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
        installed = this;
        boolean hypixelApi = hypixelApiLoaded.getAsBoolean();
        source = createSource(hypixelApi);
        if (source instanceof HypixelApiZoneSource) {
            Waypointer.LOGGER.info("Location: using Hypixel Mod API with scoreboard fallback");
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

    private static final Zone SINGLEPLAYER_ZONE = Zone.fromId(
            System.getProperty("waypointer.test.singleplayerZone", Zone.PRIVATE_WORLD.id()));

    static Zone zoneAfterPrivateWorldCheck(Zone current, boolean worldLoaded, boolean remoteServer) {
        if (!worldLoaded) return null;
        if (!remoteServer) return SINGLEPLAYER_ZONE;
        return Zone.PRIVATE_WORLD.equals(current) ? null : current;
    }

    public ZoneSource source() {
        return source;
    }

    public static String debugLine() {
        if (installed == null || installed.source == null) return "not installed";
        return installed.source instanceof HypixelApiZoneSource api ? api.detectionState()
                : installed.source instanceof ScoreboardZoneResolver sidebar ? sidebar.detectionState()
                : installed.source.getClass().getSimpleName();
    }
}
