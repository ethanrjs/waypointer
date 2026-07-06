package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class DungeonWaypointSkipRules {

    private DungeonWaypointSkipRules() {}

    public static int defaultFlagsAt(int x, int y, int z) {
        return flagsForBlock(blockStateAt(x, y, z));
    }

    public static int flagsForTriggerAt(DungeonWaypointTrigger trigger, int x, int y, int z) {
        BlockState state = blockStateAt(x, y, z);
        if (state != null) return flagsForBlock(state);
        return isInteractTrigger(trigger)
                ? Waypoint.FLAG_SKIP_ON_INTERACT
                : Waypoint.FLAG_SKIP_ON_STAND;
    }

    static int flagsForBlock(BlockState state) {
        return isInteractBlock(state)
                ? Waypoint.FLAG_SKIP_ON_INTERACT
                : Waypoint.FLAG_SKIP_ON_STAND;
    }

    private static boolean isInteractTrigger(DungeonWaypointTrigger trigger) {
        return trigger == DungeonWaypointTrigger.OPEN_CHEST
                || trigger == DungeonWaypointTrigger.FLIP_LEVER
                || trigger == DungeonWaypointTrigger.INTERACT_BLOCK;
    }

    private static boolean isInteractBlock(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.ENDER_CHEST)
                || state.is(Blocks.LEVER)
                || state.is(BlockTags.BUTTONS);
    }

    private static BlockState blockStateAt(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        return mc.level.getBlockState(new BlockPos(x, y, z));
    }
}
