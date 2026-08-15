package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class EtherwarpTargetResolver {
    private EtherwarpTargetResolver() {
    }

    interface TargetSpace {
        BlockPos firstHit(Vec3 from, Vec3 to);

        boolean hasLandingSpace(BlockPos supportBlock);
    }

    public static Optional<BlockPos> resolve(
            ClientLevel level, Player player, EtherwarpAbility ability) {
        if (level == null || player == null || ability == null
                || !ability.canUse(player.isShiftKeyDown())) return Optional.empty();
        return resolve(player.getEyePosition(), player.getLookAngle(), ability.range(),
                targetSpace(level, player));
    }

    static Optional<BlockPos> resolve(
            Vec3 eye, Vec3 lookDirection, double range, TargetSpace space) {
        if (eye == null || lookDirection == null || space == null
                || !Double.isFinite(range) || range <= 0.0D
                || lookDirection.lengthSqr() < 1.0E-12D) {
            return Optional.empty();
        }
        Vec3 target = eye.add(lookDirection.normalize().scale(range));
        BlockPos support = space.firstHit(eye, target);
        return support != null && space.hasLandingSpace(support)
                ? Optional.of(support) : Optional.empty();
    }

    public static boolean alignsWith(BlockPos supportBlock, Waypoint waypoint) {
        if (supportBlock == null || waypoint == null) return false;
        if (supportBlock.getX() != waypoint.x() || supportBlock.getZ() != waypoint.z()) {
            return false;
        }
        // Floor-placed waypoints (placeNewWaypointsBelowPlayer, the default) store
        // the support block itself; feet-placed waypoints store the landing block.
        return supportBlock.getY() == waypoint.y()
                || supportBlock.getY() == waypoint.y() - 1;
    }

    static boolean landingBlocksAreAir(boolean loaded, boolean feetAir, boolean headAir) {
        return loaded && feetAir && headAir;
    }

    private static TargetSpace targetSpace(ClientLevel level, Player player) {
        return new TargetSpace() {
            @Override
            public BlockPos firstHit(Vec3 from, Vec3 to) {
                HitResult hit = level.clip(new ClipContext(
                        from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, player));
                return hit instanceof BlockHitResult blockHit
                        && hit.getType() == HitResult.Type.BLOCK
                        ? blockHit.getBlockPos() : null;
            }

            @Override
            public boolean hasLandingSpace(BlockPos supportBlock) {
                BlockPos feet = supportBlock.above();
                BlockPos head = feet.above();
                boolean loaded = level.hasChunk(
                        supportBlock.getX() >> 4, supportBlock.getZ() >> 4)
                        && level.hasChunk(head.getX() >> 4, head.getZ() >> 4);
                if (!loaded) return false;
                return landingBlocksAreAir(
                        true, level.getBlockState(feet).isAir(),
                        level.getBlockState(head).isAir());
            }
        };
    }
}
