package dev.ethan.waypointer.progression;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Resets every group's {@link WaypointGroup#currentIndex()} when the client
 * connects to a world, so a play session starts at the beginning of each route
 * unless the player opts out via {@link WaypointerConfig#resetProgressOnWorldJoin()}.
 *
 * Wired to {@link ClientPlayConnectionEvents#JOIN} (single-player load and
 * multiplayer connect), not dimension changes -- the player keeps progress while
 * moving between overworld and nether in the same session.
 */
public final class WorldJoinProgressReset {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public WorldJoinProgressReset(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!config.resetProgressOnWorldJoin()) return;
            for (WaypointGroup g : manager.allGroups()) {
                g.resetProgress();
            }
            manager.fireDataChanged();
        });
    }
}
