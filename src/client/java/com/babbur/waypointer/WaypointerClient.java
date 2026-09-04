package com.babbur.waypointer;

import com.babbur.waypointer.api.DefaultWaypointerApi;
import com.babbur.waypointer.api.WaypointerApi;
import com.babbur.waypointer.api.WaypointerApiEntrypoints;
import com.babbur.waypointer.chat.ChatCoordDetector;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.chat.ChatImportDetector;
import com.babbur.waypointer.chat.HypixelPlayerRankSource;
import com.babbur.waypointer.commands.WaypointerCommands;
import com.babbur.waypointer.commands.DungeonCommands;
import com.babbur.waypointer.commands.CrystalHollowsCommands;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsStore;
import com.babbur.waypointer.crystal.CrystalHollowsTracker;
import com.babbur.waypointer.crystal.WishingCompassController;
import com.babbur.waypointer.dungeon.DungeonChestInteractionGuard;
import com.babbur.waypointer.dungeon.EtherwarpAlignmentCue;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.DungeonRoomZoneBridge;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.DungeonTriggerDetector;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.input.WaypointerKeybinds;
import com.babbur.waypointer.i18n.RemoteLocales;
import com.babbur.waypointer.location.LocationTracker;
import com.babbur.waypointer.progression.ProximityTracker;
import com.babbur.waypointer.progression.TempWaypointCleaner;
import com.babbur.waypointer.progression.WorldJoinProgressReset;
import com.babbur.waypointer.render.TracerRenderer;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.HappySnowmanSession;
import com.babbur.waypointer.render.gpu.OverlayRenderer;
import com.babbur.waypointer.screen.WaypointerGuiScreens;
import com.babbur.waypointer.screen.preview.RoutePreviewPipAdapter;
import com.babbur.waypointer.screen.WaypointerScreen;
import com.babbur.waypointer.update.WaypointerUpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WaypointerClient implements ClientModInitializer {

    private static ActiveGroupManager manager;
    private static Storage storage;
    private static WaypointerConfig config;
    private static WaypointerApi api;
    private static DungeonConfig dungeonConfig;
    private static DungeonStateTracker dungeonTracker;
    private static ChatImportDetector chatImportDetector;
    private static CrystalHollowsStore crystalHollowsStore;
    private static CrystalHollowsTracker crystalHollowsTracker;
    private static WishingCompassController crystalHollowsCompass;
    private static WaypointerKeybinds keybinds;
    private static OverlayRenderer overlayRenderer;
    private static boolean dungeonRouteInDungeonContext;
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

    /** Player chat bypasses Fabric's game-message modifier, so its mixin calls this. */
    public static Component decorateChatImport(Component message) {
        ChatImportDetector detector = chatImportDetector;
        return detector == null ? message : detector.decorate(message);
    }

    public static CrystalHollowsTracker crystalHollowsTracker() {
        return crystalHollowsTracker;
    }

    public static WishingCompassController crystalHollowsCompass() {
        return crystalHollowsCompass;
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
        migrateLegacyDungeonRoutes();
        storage.attach(manager);
        Minecraft minecraft = Minecraft.getInstance();
        api = new DefaultWaypointerApi(manager, minecraft::isSameThread, minecraft);

        new LocationTracker(manager, config).install();
        installCrystalHollowsSubsystem();
        HypixelPlayerRankSource.install();
        DungeonChestInteractionGuard chestInteractionGuard = new DungeonChestInteractionGuard();
        chestInteractionGuard.install();
        new ProximityTracker(manager, config, chestInteractionGuard, dungeonConfig).install();
        new TempWaypointCleaner(manager).install();
        new WorldJoinProgressReset(manager, config).install();
        HappySnowmanSession.install();
        RoutePreviewPipAdapter.install();
        WaypointRenderer waypointRenderer = new WaypointRenderer(manager, config, dungeonConfig);
        waypointRenderer.install();
        overlayRenderer = OverlayRenderer.install(waypointRenderer, manager, config);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (overlayRenderer != null) overlayRenderer.resetScene();
        });
        new TracerRenderer(manager, config, dungeonConfig).install();
        new EtherwarpAlignmentCue(manager, config::etherwarpAlignmentSound).install();
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
        chatImportDetector = new ChatImportDetector(config, chatImportCache);
        chatImportDetector.install();
        RemoteLocales.install();
        WaypointerUpdateChecker.install();
        int apiEntrypoints = WaypointerApiEntrypoints.invokeFabricEntrypoints(api);
        if (apiEntrypoints > 0) {
            Waypointer.LOGGER.info("Invoked {} Waypointer API integration(s)", apiEntrypoints);
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> storage.pumpPendingSnapshot());
        ClientLifecycleEvents.CLIENT_STOPPING.register(WaypointerClient::onClientStopping);

        Waypointer.LOGGER.info("Waypointer client ready -- {} route(s) loaded", manager.allGroups().size());
    }

    private static void onClientStopping(Minecraft client) {
        if (overlayRenderer != null) {
            runShutdownStep("overlay renderer", overlayRenderer::close);
            overlayRenderer = null;
        }
        runShutdownStep("waypoint storage", storage::flush);
        runShutdownStep("configuration", config::flush);
        if (dungeonConfig != null) {
            runShutdownStep("dungeon configuration", dungeonConfig::flush);
        }
        if (crystalHollowsTracker != null) {
            runShutdownStep("Crystal Hollows storage", crystalHollowsTracker::flush);
        }
    }

    private static void migrateLegacyDungeonRoutes() {
        Path legacyFile = FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID)
                .resolve("dungeon_rooms.json");
        if (!Files.isRegularFile(legacyFile)) return;
        try {
            DungeonRouteImporter.Result decoded =
                    DungeonRouteImporter.parse(Files.readString(legacyFile));
            int migrated = DungeonRoomRouteLibrary.installMissingLegacyRoutes(
                    manager, decoded.groups());
            if (migrated > 0) {
                storage.save(manager);
                Waypointer.LOGGER.info(
                        "Migrated {} legacy dungeon route(s) into waypoint storage; kept {} as a backup",
                        migrated, legacyFile);
            }
        } catch (Exception failure) {
            Waypointer.LOGGER.warn(
                    "Could not migrate legacy dungeon routes from {}; leaving the file untouched",
                    legacyFile, failure);
        }
    }

    private static void runShutdownStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            Waypointer.LOGGER.error("Failed to close {} during client shutdown", name, failure);
        }
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
        dungeonTracker = new DungeonStateTracker(manager, dungeonConfig);
        dungeonRouteInDungeonContext = false;
        manager.addZoneListener(WaypointerClient::onDungeonRouteContextChanged);
        Zone currentZone = manager.currentZone();
        if (currentZone != null) onDungeonRouteContextChanged(currentZone);
        dungeonTracker.install();
        new DungeonRoomZoneBridge(manager, dungeonTracker).install();
        new DungeonRoomRouteSync(manager, dungeonTracker, dungeonConfig).install();
        new DungeonTriggerDetector(dungeonTracker, dungeonConfig, manager).install();
        new DungeonCommands(dungeonTracker, dungeonConfig, manager).install();
    }

    private static void installCrystalHollowsSubsystem() {
        crystalHollowsStore = CrystalHollowsStore.loadDefault();
        crystalHollowsTracker = new CrystalHollowsTracker(manager, config, crystalHollowsStore);
        crystalHollowsTracker.install();
        crystalHollowsCompass = new WishingCompassController(crystalHollowsTracker, config);
        crystalHollowsCompass.install();
        new CrystalHollowsCommands(crystalHollowsTracker, crystalHollowsCompass, config).install();
    }

    private static void onDungeonRouteContextChanged(Zone zone) {
        boolean nowBroadDungeon = isBroadDungeonRouteZone(zone);
        boolean nowDungeonContext = isDungeonRouteContextZone(zone);
        if (nowBroadDungeon && !dungeonRouteInDungeonContext) {
            resetDungeonRoomRouteGroupProgress();
        }
        dungeonRouteInDungeonContext = nowDungeonContext;
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
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    private static boolean isDungeonRouteContextZone(Zone zone) {
        if (isBroadDungeonRouteZone(zone)) return true;
        return zone != null && DungeonRoomData.entry(zone.id()) != null;
    }

    private static boolean isBroadDungeonRouteZone(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        return id.equals("dungeon") || id.startsWith("dungeon_f") || id.startsWith("dungeon_m");
    }
}
