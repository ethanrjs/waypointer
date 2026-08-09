package com.babbur.waypointer.progression;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/** Removes expired temporary waypoints and clears them between server sessions. */
public final class TempWaypointCleaner {

    private static final int CHECK_INTERVAL_TICKS = 40;

    private final ActiveGroupManager manager;
    private int tickCounter;

    public TempWaypointCleaner(ActiveGroupManager manager) {
        this.manager = manager;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clearAllTemps());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearAllTemps());
    }

    private void onTick(Minecraft mc) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        boolean anyRemoved = false;
        for (WaypointGroup g : manager.allGroups()) {
            if (g.removeExpired(now) > 0) anyRemoved = true;
        }
        if (anyRemoved) manager.fireTransientDataChanged();
    }

    private void clearAllTemps() {
        manager.clearTemporaryWaypoints();
    }
}
