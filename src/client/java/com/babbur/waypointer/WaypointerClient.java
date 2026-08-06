package com.babbur.waypointer;

import com.babbur.waypointer.api.DefaultWaypointerApi;
import com.babbur.waypointer.api.WaypointerApi;
import com.babbur.waypointer.api.WaypointerApiEntrypoints;
import com.babbur.waypointer.chat.ChatCoordDetector;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.chat.ChatImportDetector;
import com.babbur.waypointer.chat.HypixelPlayerRankSource;
import com.babbur.waypointer.commands.WaypointerCommands;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.debug.DeveloperModeMonitor;
import com.babbur.waypointer.dungeon.DungeonCommands;
import com.babbur.waypointer.dungeon.DungeonChestInteractionGuard;
import com.babbur.waypointer.dungeon.DungeonMapCheckmarks;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.DungeonRoomZoneBridge;
import com.babbur.waypointer.dungeon.DungeonRouteDownloader;
import com.babbur.waypointer.dungeon.DungeonRouteSession;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.DungeonTriggerDetector;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.input.WaypointerKeybinds;
import com.babbur.waypointer.location.LocationTracker;
import com.babbur.waypointer.progression.ProximityTracker;
import com.babbur.waypointer.progression.TempWaypointCleaner;
import com.babbur.waypointer.progression.WorldJoinProgressReset;
import com.babbur.waypointer.render.TracerRenderer;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.HappySnowmanSession;
import com.babbur.waypointer.screen.WaypointerGuiScreens;
import com.babbur.waypointer.screen.preview.RoutePreviewPipAdapter;
import com.babbur.waypointer.screen.WaypointerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-side bootstrap.
 *
 * Owns the singleton {@link ActiveGroupManager} for the whole session.
 * Other client components are spawned here so the wiring is one-stop and
 * easy to read -- no mystery lifecycles in static init blocks elsewhere.
 */
public final class WaypointerClient implements ClientModInitializer {

    private static ActiveGroupManager manager;
    private static Storage storage;
    private static WaypointerConfig config;
    private static WaypointerApi api;
    private static DungeonConfig dungeonConfig;
    private static DungeonStateTracker dungeonTracker;
    private static DungeonRouteSession dungeonRouteSession;
    private static DungeonRouteDownloader dungeonRouteDownloader;
    private static DeveloperModeMonitor developerModeMonitor;
    private static WaypointerKeybinds keybinds;
    private static boolean dungeonRouteSessionInDungeonContext;
    private static Screen suspendedWaypointerGuiScreen;

    public static ActiveGroupManager manager() {
        return manager;
    }

    public static Storage storage() {
        return storage;
    }

    public static WaypointerConfig config() {
        return config;
    }

    public static WaypointerApi api() {
        return api;
    }

    public static DungeonConfig dungeonConfig() {
        return dungeonConfig;
    }

    public static DungeonStateTracker dungeonTracker() {
        return dungeonTracker;
    }

    public static DungeonRouteSession dungeonRouteSession() {
        return dungeonRouteSession;
    }

    public static DungeonRouteDownloader dungeonRouteDownloader() {
        return dungeonRouteDownloader;
    }

    public static DeveloperModeMonitor developerModeMonitor() {
        return developerModeMonitor;
    }

    public static WaypointerKeybinds keybinds() {
        return keybinds;
    }

    @Override
    public void onInitializeClient() {
        config = WaypointerConfig.load();
        dungeonConfig = DungeonConfig.load();
        manager = new ActiveGroupManager();
        storage = Storage.defaultLocation();
        storage.load(manager);
        // attach AFTER load so rehydration doesn't trigger a no-op write.
        storage.attach(manager);
        Minecraft minecraft = Minecraft.getInstance();
        api = new DefaultWaypointerApi(manager, minecraft::isSameThread, minecraft);

        new LocationTracker(manager, config).install();
        HypixelPlayerRankSource.install();
        DungeonChestInteractionGuard chestInteractionGuard = new DungeonChestInteractionGuard();
        chestInteractionGuard.install();
        new ProximityTracker(manager, config, chestInteractionGuard, dungeonConfig).install();
        new TempWaypointCleaner(manager).install();
        new WorldJoinProgressReset(manager, config).install();
        HappySnowmanSession.install();
        RoutePreviewPipAdapter.install();
        new WaypointRenderer(manager, config, dungeonConfig).install();
        new TracerRenderer(manager, config, dungeonConfig).install();
        WaypointRepositionMode.install();

        installDungeonSubsystem(chestInteractionGuard);
        if (!config.dungeonWaypointsFeatureEnabled()) {
            Waypointer.LOGGER.info("Legacy dungeon feature flag is off; dungeon room detection still installs.");
        }

        ChatImportCache chatImportCache = new ChatImportCache();
        new WaypointerCommands(manager, storage, config, chatImportCache, WaypointerClient::openGui).install();
        keybinds = new WaypointerKeybinds(WaypointerClient::openGui, manager, config);
        keybinds.install();
        new ChatCoordDetector(config, manager).install();
        new ChatImportDetector(config, chatImportCache).install();
        int apiEntrypoints = WaypointerApiEntrypoints.invokeFabricEntrypoints(api);
        if (apiEntrypoints > 0) {
            Waypointer.LOGGER.info("Invoked {} Waypointer API integration(s)", apiEntrypoints);
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(WaypointerClient::onClientStopping);

        Waypointer.LOGGER.info("Waypointer client ready -- {} route(s) loaded", manager.allGroups().size());
    }

    private static void onClientStopping(Minecraft client) {
        storage.flush();
        config.flush();
        if (dungeonConfig != null) dungeonConfig.flush();
        DungeonRoomData.flush();
        if (developerModeMonitor != null) developerModeMonitor.flushAndShutdown();
    }

    public static void openGui() {
        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = MinecraftCompat.screen(mc);
        if (WaypointerGuiScreens.owns(currentScreen)) {
            suspendedWaypointerGuiScreen = currentScreen;
            MinecraftCompat.setScreen(mc, null);
            return;
        }

        Screen resume = resumableWaypointerGuiScreen();
        if (resume != null) {
            suspendedWaypointerGuiScreen = null;
            MinecraftCompat.setScreen(mc, resume);
            return;
        }

        WaypointerScreen.open(manager, config);
    }

    private static Screen resumableWaypointerGuiScreen() {
        Screen screen = suspendedWaypointerGuiScreen;
        if (screen == null) return null;
        if (!WaypointerGuiScreens.owns(screen)) {
            suspendedWaypointerGuiScreen = null;
            return null;
        }
        return screen;
    }

    private static void installDungeonSubsystem(DungeonChestInteractionGuard chestInteractionGuard) {
        DungeonRoomData.loadDefaultCustomStore();
        dungeonTracker = new DungeonStateTracker(manager, dungeonConfig);
        dungeonRouteSession = new DungeonRouteSession();
        dungeonRouteSessionInDungeonContext = false;
        manager.addZoneListener(WaypointerClient::onDungeonRouteSessionZoneChanged);
        Zone currentZone = manager.currentZone();
        if (currentZone != null) onDungeonRouteSessionZoneChanged(currentZone);
        dungeonTracker.install();
        new DungeonRoomZoneBridge(manager, dungeonTracker).install();
        new DungeonRoomRouteSync(manager, dungeonTracker, dungeonRouteSession, dungeonConfig).install();
        new DungeonTriggerDetector(
                dungeonTracker, dungeonRouteSession, chestInteractionGuard, dungeonConfig, manager)
                .install();
        new DungeonMapCheckmarks(dungeonTracker, dungeonRouteSession, dungeonConfig).install();
        developerModeMonitor = new DeveloperModeMonitor(dungeonTracker, dungeonConfig, manager);
        developerModeMonitor.install();
        dungeonRouteDownloader = new DungeonRouteDownloader(manager, dungeonConfig);
        dungeonRouteDownloader.install();
        new DungeonCommands(dungeonTracker, dungeonConfig, dungeonRouteSession,
                dungeonRouteDownloader, manager).install();
    }

    private static void onDungeonRouteSessionZoneChanged(Zone zone) {
        boolean nowBroadDungeon = isBroadDungeonZoneForRouteSession(zone);
        boolean nowDungeonContext = isDungeonRouteSessionContextZone(zone);
        if (nowBroadDungeon && !dungeonRouteSessionInDungeonContext && dungeonRouteSession != null) {
            dungeonRouteSession.resetAll();
            resetDungeonRoomRouteGroupProgress();
        }
        dungeonRouteSessionInDungeonContext = nowDungeonContext;
    }

    private static void resetDungeonRoomRouteGroupProgress() {
        if (manager == null) return;

        boolean changed = false;
        for (var group : manager.allGroups()) {
            if (!isDungeonRoomRouteGroup(group)) continue;
            int beforeIndex = group.currentIndex();
            boolean beforeComplete = group.isComplete();
            group.resetProgress();
            if (beforeIndex != group.currentIndex() || beforeComplete != group.isComplete()) {
                changed = true;
            }
        }
        if (changed) {
            manager.fireDataChanged();
        }
    }

    private static boolean isDungeonRoomRouteGroup(com.babbur.waypointer.core.WaypointGroup group) {
        return group != null
                && !group.temp()
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    private static boolean isDungeonRouteSessionContextZone(Zone zone) {
        if (isBroadDungeonZoneForRouteSession(zone)) return true;
        return zone != null && DungeonRoomData.definition(zone.id()) != null;
    }

    private static boolean isBroadDungeonZoneForRouteSession(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        return id.equals("dungeon") || id.startsWith("dungeon_f") || id.startsWith("dungeon_m");
    }
}
