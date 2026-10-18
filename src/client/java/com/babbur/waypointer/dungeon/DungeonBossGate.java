package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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
        Zone zone = manager.currentZone();
        int seen = DungeonBossArea.floorNumber(zone == null ? null : zone.id());
        if (seen > 0) floor = seen;
        manager.setInDungeonBoss(tracker.currentRoom() == null
                && DungeonBossArea.contains(floor, player.getX(), player.getZ()));
    }
}
