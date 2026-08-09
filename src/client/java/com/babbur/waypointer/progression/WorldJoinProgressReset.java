package com.babbur.waypointer.progression;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class WorldJoinProgressReset {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public WorldJoinProgressReset(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            boolean resetProgress = config.resetProgressOnWorldJoin();
            resetForWorldJoin(manager, resetProgress);
            if (resetProgress) manager.fireDataChanged();
        });
    }

    static void resetForWorldJoin(ActiveGroupManager manager, boolean resetProgress) {
        if (!resetProgress) return;
        for (WaypointGroup group : manager.allGroups()) {
            group.resetProgress();
        }
    }
}
