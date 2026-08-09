package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/** Marks secrets complete from actions the client can observe. */
public final class DungeonTriggerDetector {

    private static final double ENTITY_TRIGGER_RANGE_SQ = 36.0;
    private static final double ITEM_PICKUP_PLAYER_RANGE_SQ = 36.0;
    private static final double ETHERWARP_TRIGGER_RANGE_SQ = 9.0;
    private static final double ETHERWARP_JUMP_DISTANCE_SQ = 4.0;
    private static final long ETHERWARP_ARM_WINDOW_MS = 1_500L;
    private static final float SECRET_BAT_SOUND_VOLUME = 0.1f;

    private static final List<String> SECRET_ITEM_NAMES = List.of(
            "health potion", "healing potion", "healing viii", "healing 8",
            "decoy", "inflatable jerry", "spirit leap", "trap", "training weights",
            "defuse kit", "dungeon chest key", "treasure talisman", "revive stone",
            "architect's first draft", "secret dye", "candycomb");

    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final ActiveGroupManager manager;

    private long etherwarpArmedAtMillis;
    private Vec3 lastTickPosition;

    public DungeonTriggerDetector(DungeonStateTracker tracker,
                                  DungeonConfig config,
                                  ActiveGroupManager manager) {
        this.tracker = tracker;
        this.config = config;
        this.manager = manager;
    }

    public void install() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                onUseItem(player.getItemInHand(hand), player.isShiftKeyDown());
            }
            return InteractionResult.PASS;
        });
        ClientEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnload);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        DungeonSoundHook.setListener(this::onSoundPacket);
    }

    private void onUseItem(ItemStack held, boolean sneaking) {
        if (!sneaking || held == null || held.isEmpty()) return;
        if (tracker.currentRoom() == null) return;
        if (DungeonItemIdentity.isEtherwarpItem(held)) {
            etherwarpArmedAtMillis = System.currentTimeMillis();
        }
    }

    private void checkEtherwarpLanding(LocalPlayer player) {
        Vec3 previous = lastTickPosition;
        Vec3 current = player.position();
        lastTickPosition = current;

        if (previous == null || etherwarpArmedAtMillis == 0L) return;
        if (System.currentTimeMillis() - etherwarpArmedAtMillis > ETHERWARP_ARM_WINDOW_MS) {
            etherwarpArmedAtMillis = 0L;
            return;
        }
        if (previous.distanceToSqr(current) < ETHERWARP_JUMP_DISTANCE_SQ) return;

        etherwarpArmedAtMillis = 0L;
        if (tracker.currentRoom() == null) return;
        advanceCurrentAction(Waypoint.FLAG_DUNGEON_ETHERWARP,
                current.x, current.y, current.z);
    }

    private void onEntityUnload(Entity entity, ClientLevel level) {
        if (!(entity instanceof ItemEntity item)) return;
        if (tracker.currentRoom() == null) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || player.distanceToSqr(entity) > ITEM_PICKUP_PLAYER_RANGE_SQ
                || !isSecretItemName(item.getItem().getHoverName().getString())) {
            return;
        }
        advanceCurrentAction(
                Waypoint.FLAG_DUNGEON_ITEM, entity.getX(), entity.getY(), entity.getZ());

    }

    private static boolean isSecretItemName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return false;
        String name = rawName.toLowerCase(Locale.ROOT);
        for (String secretName : SECRET_ITEM_NAMES) {
            if (name.contains(secretName)) return true;
        }
        return false;
    }

    private void onSoundPacket(SoundEvent sound, float volume, double x, double y, double z) {
        if (volume != SECRET_BAT_SOUND_VOLUME) return;
        if (sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) return;
        if (tracker.currentRoom() == null) return;
        if (sound == SoundEvents.BAT_DEATH) {
            advanceCurrentAction(Waypoint.FLAG_DUNGEON_BAT, x, y, z);
        }

    }

    private void onTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            lastTickPosition = null;
            etherwarpArmedAtMillis = 0L;
            return;
        }
        checkEtherwarpLanding(player);
    }

    private void advanceCurrentAction(int actionFlag, double x, double y, double z) {
        if (manager == null) return;
        for (WaypointGroup group : manager.activeGroups()) {
            int currentIndex = group.currentIndex();
            if (currentIndex < 0 || currentIndex >= group.size()) continue;
            Waypoint current = group.get(currentIndex);
            if (!current.hasFlag(actionFlag)) continue;
            double dx = current.centerX() - x;
            double dy = current.centerY() - y;
            double dz = current.centerZ() - z;
            if (dx * dx + dy * dy + dz * dz > ENTITY_TRIGGER_RANGE_SQ) continue;
            boolean secret = current.hasFlag(Waypoint.FLAG_DUNGEON_SECRET);
            group.advancePast(currentIndex);
            if (secret) DungeonSecretCompletionSound.play(config);
        }
    }
}
