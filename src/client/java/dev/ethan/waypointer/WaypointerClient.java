package dev.ethan.waypointer;

import dev.ethan.waypointer.chat.ChatCoordDetector;
import dev.ethan.waypointer.chat.ChatImportCache;
import dev.ethan.waypointer.chat.ChatImportDetector;
import dev.ethan.waypointer.commands.WaypointerCommands;
import dev.ethan.waypointer.config.Storage;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.input.WaypointerKeybinds;
import dev.ethan.waypointer.location.LocationTracker;
import dev.ethan.waypointer.progression.ProximityTracker;
import dev.ethan.waypointer.progression.WorldJoinProgressReset;
import dev.ethan.waypointer.render.TracerRenderer;
import dev.ethan.waypointer.render.WaypointRenderer;
import dev.ethan.waypointer.screen.WaypointerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

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

    public static ActiveGroupManager manager() { return manager; }
    public static Storage storage()            { return storage; }
    public static WaypointerConfig config()    { return config; }

    @Override
    public void onInitializeClient() {
        config = WaypointerConfig.load();
        manager = new ActiveGroupManager();
        storage = Storage.defaultLocation();
        storage.load(manager);

        new LocationTracker(manager, config).install();
        new ProximityTracker(manager, config).install();
        new WorldJoinProgressReset(manager, config).install();
        new WaypointRenderer(manager, config).install();
        new TracerRenderer(manager, config).install();

        ChatImportCache chatImportCache = new ChatImportCache();
        new WaypointerCommands(manager, storage, config, chatImportCache, WaypointerClient::openGui).install();
        new WaypointerKeybinds(WaypointerClient::openGui, manager, config).install();
        new ChatCoordDetector(config).install();
        new ChatImportDetector(config, chatImportCache).install();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            storage.save(manager);
            config.save();
        });
        manager.addDataListener(() -> storage.save(manager));

        Waypointer.LOGGER.info("Waypointer client ready -- {} group(s) loaded", manager.allGroups().size());
    }

    private static void openGui() {
        WaypointerScreen.open(manager, config);
    }
}
