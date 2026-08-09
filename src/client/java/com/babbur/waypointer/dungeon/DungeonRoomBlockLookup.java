package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

final class DungeonRoomBlockLookup implements DungeonRoomData.BlockLookup {
    private final ClientLevel level;

    DungeonRoomBlockLookup(ClientLevel level) {
        this.level = level;
    }

    @Override
    public String blockIdAt(int x, int y, int z) {
        return BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock())
                .toString();
    }
}
