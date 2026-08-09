package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class DungeonWaypointSkipRules {

    private DungeonWaypointSkipRules() {}

    public static int defaultFlagsAt(int x, int y, int z) {
        return flagsForBlock(blockStateAt(x, y, z));
    }

    public static int flagsForTriggerAt(DungeonWaypointTrigger trigger, int x, int y, int z) {
        BlockState state = blockStateAt(x, y, z);
        if (state != null) {
            int semantic = flagsForTrigger(trigger);
            return flagsForBlock(state) | (semantic & Waypoint.DUNGEON_METADATA_FLAGS);
        }
        return flagsForTrigger(trigger);
    }

    public static int flagsForTrigger(DungeonWaypointTrigger trigger) {
        if (trigger == null) return Waypoint.FLAG_SKIP_ON_STAND;
        return switch (trigger) {
            case DUNGEONBREAKER -> Waypoint.FLAG_SKIP_ON_MINE
                    | Waypoint.FLAG_DUNGEON_DUNGEONBREAKER;
            case BREAK_BLOCKS -> Waypoint.FLAG_SKIP_ON_MINE;
            case USE_SUPERBOOM -> Waypoint.FLAG_SKIP_ON_INTERACT
                    | Waypoint.FLAG_DUNGEON_SUPERBOOM;
            case ETHERWARP -> Waypoint.FLAG_SKIP_ON_STAND
                    | Waypoint.FLAG_DUNGEON_ETHERWARP;
            case THROW_PEARL -> Waypoint.FLAG_SKIP_ON_STAND
                    | Waypoint.FLAG_DUNGEON_PEARL;
            case PICKUP_ITEM -> Waypoint.FLAG_SKIP_ON_STAND
                    | Waypoint.FLAG_DUNGEON_ITEM;
            case KILL_BAT -> Waypoint.FLAG_SKIP_ON_STAND
                    | Waypoint.FLAG_DUNGEON_BAT;
            default -> isInteractTrigger(trigger)
                    ? Waypoint.FLAG_SKIP_ON_INTERACT
                    : Waypoint.FLAG_SKIP_ON_STAND;
        };
    }

    static int flagsForBlock(BlockState state) {
        return isInteractBlock(state)
                ? Waypoint.FLAG_SKIP_ON_INTERACT
                : Waypoint.FLAG_SKIP_ON_STAND;
    }

    private static boolean isInteractTrigger(DungeonWaypointTrigger trigger) {
        return trigger == DungeonWaypointTrigger.OPEN_CHEST
                || trigger == DungeonWaypointTrigger.FLIP_LEVER
                || trigger == DungeonWaypointTrigger.INTERACT_BLOCK
                || trigger == DungeonWaypointTrigger.ANY_SECRET;
    }

    private static boolean isInteractBlock(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.ENDER_CHEST)
                || state.is(Blocks.LEVER)
                || state.is(BlockTags.BUTTONS)
                || state.getBlock() instanceof AbstractSkullBlock;
    }

    private static BlockState blockStateAt(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        return mc.level.getBlockState(new BlockPos(x, y, z));
    }
}
