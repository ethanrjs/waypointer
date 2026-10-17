package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Tells the route manager when the player is in the boss, so boss routes stay out of the clear. */
public final class DungeonBossGate {

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private int floor;

    public DungeonBossGate(ActiveGroupManager manager, DungeonStateTracker tracker) {
        this.manager = manager;
        this.tracker = tracker;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || !tracker.inDungeon()) {
            floor = 0;
            manager.setInDungeonBoss(false);
            return;
        }
        // Rooms replace the floor zone while inside them, so remember the floor last seen.
        Zone zone = manager.currentZone();
        int seen = DungeonBossArea.floorNumber(zone == null ? null : zone.id());
        if (seen > 0) floor = seen;
        manager.setInDungeonBoss(tracker.currentRoom() == null
                && DungeonBossArea.contains(floor, player.getX(), player.getZ()));
    }
}
