package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtherwarpTargetResolverTest {

    @Test
    void etherwarpRequiresSneaking() {
        EtherwarpAbility ability = new EtherwarpAbility(EtherwarpAbility.BASE_RANGE);

        assertFalse(ability.canUse(false));
        assertTrue(ability.canUse(true));
    }

    @Test
    void rayUsesTheAbilityRangeAndReturnsOnlySafeLandings() {
        AtomicReference<Vec3> rayEnd = new AtomicReference<>();
        BlockPos support = new BlockPos(10, 63, 0);
        EtherwarpTargetResolver.TargetSpace safe = new EtherwarpTargetResolver.TargetSpace() {
            @Override
            public BlockPos firstHit(Vec3 from, Vec3 to) {
                rayEnd.set(to);
                return support;
            }

            @Override
            public boolean hasLandingSpace(BlockPos ignored) {
                return true;
            }
        };

        assertEquals(support, EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(2, 0, 0), 61, safe).orElseThrow());
        assertEquals(new Vec3(61, 0, 0), rayEnd.get());
        assertTrue(EtherwarpTargetResolver.alignsWith(
                support, Waypoint.at(10, 64, 0)));
    }

    @Test
    void baseAndMaximumRangeUseExactNormalizedRayEndpoints() {
        Vec3 eye = new Vec3(1.0D, 65.0D, -2.0D);
        Vec3 direction = new Vec3(3.0D, 4.0D, 0.0D);
        BlockPos support = new BlockPos(30, 63, -2);
        List<Vec3> rayEnds = new ArrayList<>();
        EtherwarpTargetResolver.TargetSpace safe = new EtherwarpTargetResolver.TargetSpace() {
            @Override
            public BlockPos firstHit(Vec3 from, Vec3 to) {
                rayEnds.add(to);
                return support;
            }

            @Override
            public boolean hasLandingSpace(BlockPos ignored) {
                return true;
            }
        };

        assertEquals(support, EtherwarpTargetResolver.resolve(
                eye, direction, EtherwarpAbility.BASE_RANGE, safe).orElseThrow());
        assertEquals(support, EtherwarpTargetResolver.resolve(
                eye,
                direction,
                EtherwarpAbility.BASE_RANGE + EtherwarpAbility.MAX_TUNERS,
                safe).orElseThrow());
        assertEquals(List.of(
                        eye.add(direction.normalize().scale(57.0D)),
                        eye.add(direction.normalize().scale(61.0D))),
                rayEnds);
    }

    @Test
    void invalidRangeDirectionAndRayMissFailBeforeLandingChecks() {
        AtomicInteger rays = new AtomicInteger();
        AtomicInteger landingChecks = new AtomicInteger();
        EtherwarpTargetResolver.TargetSpace miss = new EtherwarpTargetResolver.TargetSpace() {
            @Override
            public BlockPos firstHit(Vec3 from, Vec3 to) {
                rays.incrementAndGet();
                return null;
            }

            @Override
            public boolean hasLandingSpace(BlockPos ignored) {
                landingChecks.incrementAndGet();
                return true;
            }
        };

        assertTrue(EtherwarpTargetResolver.resolve(
                null, Vec3.ZERO, 57.0D, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, null, 57.0D, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, Vec3.ZERO, 57.0D, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), 0.0D, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), -1.0D, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), Double.NaN, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D),
                Double.POSITIVE_INFINITY, miss).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), 57.0D, null).isEmpty());
        assertEquals(0, rays.get());
        assertEquals(0, landingChecks.get());

        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), 57.0D, miss).isEmpty());
        assertEquals(1, rays.get());
        assertEquals(0, landingChecks.get());
    }

    @Test
    void blockedLandingAndZeroDirectionFailClosed() {
        EtherwarpTargetResolver.TargetSpace blocked = new EtherwarpTargetResolver.TargetSpace() {
            @Override
            public BlockPos firstHit(Vec3 from, Vec3 to) {
                return new BlockPos(10, 63, 0);
            }

            @Override
            public boolean hasLandingSpace(BlockPos ignored) {
                return false;
            }
        };

        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, Vec3.ZERO, 57, blocked).isEmpty());
        assertTrue(EtherwarpTargetResolver.resolve(
                Vec3.ZERO, new Vec3(1, 0, 0), 57, blocked).isEmpty());
        assertFalse(EtherwarpTargetResolver.alignsWith(
                new BlockPos(10, 64, 0), Waypoint.at(10, 64, 0)));
        assertFalse(EtherwarpTargetResolver.alignsWith(
                null, Waypoint.at(10, 64, 0)));
        assertFalse(EtherwarpTargetResolver.alignsWith(
                new BlockPos(10, 63, 0), null));
        assertFalse(EtherwarpTargetResolver.landingBlocksAreAir(true, false, true));
        assertFalse(EtherwarpTargetResolver.landingBlocksAreAir(true, true, false));
        assertFalse(EtherwarpTargetResolver.landingBlocksAreAir(false, true, true));
        assertTrue(EtherwarpTargetResolver.landingBlocksAreAir(true, true, true));
    }

}
