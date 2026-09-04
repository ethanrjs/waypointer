package com.babbur.waypointer.crystal;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;

/** Version-specific Magma Cube package bridge for Minecraft 26.2. */
public final class MagmaCubeCompat {
    private MagmaCubeCompat() {}

    public static boolean isLargeMagmaCube(Entity entity) {
        return entity instanceof MagmaCube cube && cube.getSize() > 10;
    }
}
