package dev.ethan.waypointer;

import dev.ethan.waypointer.api.DefaultWaypointerApi;
import dev.ethan.waypointer.api.WaypointerApi;
import dev.ethan.waypointer.api.WaypointerApiEntrypoints;
import dev.ethan.waypointer.chat.ChatCoordDetector;
import dev.ethan.waypointer.chat.ChatImportCache;
import dev.ethan.waypointer.chat.ChatImportDetector;
import dev.ethan.waypointer.commands.WaypointerCommands;
import dev.ethan.waypointer.config.Storage;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.DungeonCommands;
import dev.ethan.waypointer.dungeon.DungeonMapCheckmarks;
import dev.ethan.waypointer.dungeon.DungeonRoomRouteSync;
import dev.ethan.waypointer.dungeon.DungeonRoomZoneBridge;
import dev.ethan.waypointer.dungeon.DungeonRouteDownloader;
import dev.ethan.waypointer.dungeon.DungeonRouteSession;
import dev.ethan.waypointer.dungeon.DungeonStateTracker;
import dev.ethan.waypointer.dungeon.DungeonTriggerDetector;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.input.WaypointRepositionMode;
import dev.ethan.waypointer.input.WaypointerKeybinds;
import dev.ethan.waypointer.location.LocationTracker;
import dev.ethan.waypointer.progression.ProximityTracker;
import dev.ethan.waypointer.progression.TempWaypointCleaner;
import dev.ethan.waypointer.progression.WorldJoinProgressReset;
import dev.ethan.waypointer.render.TracerRenderer;
import dev.ethan.waypointer.render.WaypointRenderer;
import dev.ethan.waypointer.screen.WaypointerGuiScreens;
import dev.ethan.waypointer.screen.WaypointerScreen;
import dev.ethan.waypointer.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
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

    @Override
    public void onInitializeClient() {
        config = WaypointerConfig.load();
        manager = new ActiveGroupManager();
        storage = Storage.defaultLocation();
        storage.load(manager);
        // attach AFTER load so rehydration doesn't trigger a no-op write.
        storage.attach(manager);
        api = new DefaultWaypointerApi(manager);

        new LocationTracker(manager, config).install();
        new ProximityTracker(manager, config).install();
        new TempWaypointCleaner(manager).install();
        new WorldJoinProgressReset(manager, config).install();
        new WaypointRenderer(manager, config).install();
        new TracerRenderer(manager, config).install();
        WaypointRepositionMode.install();

        installDungeonSubsystem();
        if (!config.dungeonWaypointsFeatureEnabled()) {
            Waypointer.LOGGER.info("Legacy dungeon feature flag is off; dungeon room detection still installs.");
        }

        ChatImportCache chatImportCache = new ChatImportCache();
        new WaypointerCommands(manager, storage, config, chatImportCache, WaypointerClient::openGui).install();
        new WaypointerKeybinds(WaypointerClient::openGui, manager, config).install();
        new ChatCoordDetector(config, manager).install();
        new ChatImportDetector(config, chatImportCache).install();
        int apiEntrypoints = WaypointerApiEntrypoints.invokeFabricEntrypoints(api);
        if (apiEntrypoints > 0) {
            Waypointer.LOGGER.info("Invoked {} Waypointer API integration(s)", apiEntrypoints);
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(WaypointerClient::onClientStopping);

        // Fire-and-forget update check. Runs on a daemon thread with a 5s
        // startup delay so it doesn't race with world-load chat spam. Looking
        // the version up through FabricLoader means we don't have to remember
        // to bump a second place on release.
        String modVersion = FabricLoader.getInstance().getModContainer(Waypointer.MOD_ID)
                .map(WaypointerClient::modVersionFromContainer)
                .orElse("0.0.0");
        new UpdateChecker(modVersion, config.checkForUpdates()).start();

        Waypointer.LOGGER.info("Waypointer client ready -- {} group(s) loaded", manager.allGroups().size());
    }

    private static void onClientStopping(Minecraft client) {
        storage.flush();
        config.flush();
        if (dungeonConfig != null) dungeonConfig.flush();
        DungeonRoomData.flush();
    }

    private static String modVersionFromContainer(ModContainer container) {
        return container.getMetadata().getVersion().getFriendlyString();
    }

    public static void openGui() {
        Minecraft mc = Minecraft.getInstance();
        if (WaypointerGuiScreens.owns(mc.screen)) {
            suspendedWaypointerGuiScreen = mc.screen;
            mc.setScreen(null);
            return;
        }

        Screen resume = resumableWaypointerGuiScreen();
        if (resume != null) {
            suspendedWaypointerGuiScreen = null;
            mc.setScreen(resume);
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

    private static void installDungeonSubsystem() {
        dungeonConfig = DungeonConfig.load();
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
        new DungeonTriggerDetector(dungeonTracker, dungeonRouteSession).install();
        new DungeonMapCheckmarks(dungeonTracker, dungeonRouteSession, dungeonConfig).install();
        dungeonRouteDownloader = new DungeonRouteDownloader(manager, dungeonConfig);
        dungeonRouteDownloader.install();
        new DungeonCommands(dungeonTracker, dungeonConfig, dungeonRouteSession,
                dungeonRouteDownloader).install();
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

    private static boolean isDungeonRoomRouteGroup(dev.ethan.waypointer.core.WaypointGroup group) {
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
