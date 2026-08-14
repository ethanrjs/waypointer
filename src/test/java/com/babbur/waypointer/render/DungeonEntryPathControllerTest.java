package com.babbur.waypointer.render;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonEntryPathControllerTest {
    @Test
    void prepareReusesSuccessfulPathUntilTheExactRetryBoundary() {
        assertEquals(250_000_000L, DungeonEntryPathController.SUCCESS_RETRY_NANOS);
        AtomicInteger computations = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        DungeonEntryPathController controller = new DungeonEntryPathController(
                (level, playerPosition, target) -> {
                    computations.incrementAndGet();
                    return successfulPath(playerPosition, target);
                },
                now::get);
        WaypointGroup group = dungeonGroup(Waypoint.at(10, 64, 0));
        Object world = new Object();

        DungeonEntryPathController.PreparedPaths first = controller.prepareInWorld(
                List.of(group), new Vec3(0.25D, 64.0D, 0.25D), world, false);
        assertEquals(1, computations.get());
        assertFalse(first.lookups().get(0).cacheHit());
        assertEquals(0L, first.lookups().get(0).cacheAgeNanos());

        now.set(DungeonEntryPathController.SUCCESS_RETRY_NANOS - 1L);
        DungeonEntryPathController.PreparedPaths cached = controller.prepareInWorld(
                List.of(group), new Vec3(0.75D, 64.0D, 0.75D), world, false);
        assertEquals(1, computations.get());
        assertTrue(cached.lookups().get(0).cacheHit());
        assertEquals(DungeonEntryPathController.SUCCESS_RETRY_NANOS - 1L,
                cached.lookups().get(0).cacheAgeNanos());
        assertEquals(new Vec3(0.75D, 64.12D, 0.75D),
                cached.submissions().get(0).points().get(0));

        now.set(DungeonEntryPathController.SUCCESS_RETRY_NANOS);
        DungeonEntryPathController.PreparedPaths retried = controller.prepareInWorld(
                List.of(group), new Vec3(0.8D, 64.0D, 0.8D), world, false);
        assertEquals(2, computations.get());
        assertFalse(retried.lookups().get(0).cacheHit());
        assertEquals(0L, retried.lookups().get(0).cacheAgeNanos());
    }

    @Test
    void movingToAnotherBlockInvalidatesTheCachedPathBeforeItsTtl() {
        AtomicInteger computations = new AtomicInteger();
        DungeonEntryPathController controller = new DungeonEntryPathController(
                (level, playerPosition, target) -> {
                    computations.incrementAndGet();
                    return successfulPath(playerPosition, target);
                },
                () -> DungeonEntryPathController.SUCCESS_RETRY_NANOS - 1L);
        WaypointGroup group = dungeonGroup(Waypoint.at(10, 64, 0));
        Object world = new Object();

        controller.prepareInWorld(
                List.of(group), new Vec3(0.9D, 64.0D, 0.5D), world, false);
        DungeonEntryPathController.PreparedPaths moved = controller.prepareInWorld(
                List.of(group), new Vec3(1.0D, 64.0D, 0.5D), world, false);

        assertEquals(2, computations.get());
        assertFalse(moved.lookups().get(0).cacheHit());
    }

    @Test
    void pathfinderFailureFallsBackAndRetriesAtTheFailureBoundary() {
        assertEquals(500_000_000L, DungeonEntryPathController.FAILURE_RETRY_NANOS);
        AtomicInteger computations = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        DungeonEntryPathController controller = new DungeonEntryPathController(
                (level, playerPosition, target) -> {
                    computations.incrementAndGet();
                    throw new IllegalStateException("blocked test path");
                },
                now::get);
        WaypointGroup group = dungeonGroup(Waypoint.at(10, 70, -4));
        Object world = new Object();

        DungeonEntryPathController.PreparedPaths first = controller.prepareInWorld(
                List.of(group), new Vec3(2.5D, 64.0D, 3.5D), world, false);
        GroundPathfinder.Diagnostics diagnostics =
                first.lookups().get(0).result().diagnostics();
        assertEquals(1, computations.get());
        assertTrue(first.submissions().get(0).points().isEmpty());
        assertEquals(GroundPathfinder.FailureReason.CALCULATION_FAILED,
                diagnostics.reason());
        assertEquals(new BlockPos(2, 64, 3), diagnostics.rawStart());
        assertEquals(new BlockPos(10, 70, -4), diagnostics.rawGoal());
        assertFalse(first.lookups().get(0).cacheHit());

        now.set(DungeonEntryPathController.FAILURE_RETRY_NANOS - 1L);
        DungeonEntryPathController.PreparedPaths cached = controller.prepareInWorld(
                List.of(group), new Vec3(2.75D, 64.0D, 3.75D), world, false);
        assertEquals(1, computations.get());
        assertTrue(cached.lookups().get(0).cacheHit());

        now.set(DungeonEntryPathController.FAILURE_RETRY_NANOS);
        DungeonEntryPathController.PreparedPaths retried = controller.prepareInWorld(
                List.of(group), new Vec3(2.75D, 64.0D, 3.75D), world, false);
        assertEquals(2, computations.get());
        assertFalse(retried.lookups().get(0).cacheHit());
    }

    @Test
    void changingWorldIdentityClearsTheLivePathCache() {
        AtomicInteger computations = new AtomicInteger();
        DungeonEntryPathController controller = new DungeonEntryPathController(
                (level, playerPosition, target) -> {
                    computations.incrementAndGet();
                    return successfulPath(playerPosition, target);
                },
                () -> 10L);
        WaypointGroup group = dungeonGroup(Waypoint.at(10, 64, 0));
        Vec3 player = new Vec3(0.5D, 64.0D, 0.5D);
        Object firstWorld = new Object();
        Object secondWorld = new Object();

        controller.prepareInWorld(List.of(group), player, firstWorld, false);
        DungeonEntryPathController.PreparedPaths cached = controller.prepareInWorld(
                List.of(group), player, firstWorld, false);
        DungeonEntryPathController.PreparedPaths reset = controller.prepareInWorld(
                List.of(group), player, secondWorld, false);

        assertEquals(2, computations.get());
        assertTrue(cached.lookups().get(0).cacheHit());
        assertFalse(reset.lookups().get(0).cacheHit());
    }

    @Test
    void prepareIncludesOnlyEligibleDungeonEntryTargets() {
        AtomicInteger computations = new AtomicInteger();
        DungeonEntryPathController controller = new DungeonEntryPathController(
                (level, playerPosition, target) -> {
                    computations.incrementAndGet();
                    return successfulPath(playerPosition, target);
                },
                () -> 0L);
        WaypointGroup eligible = dungeonGroup(Waypoint.at(10, 64, 0));
        WaypointGroup following = dungeonGroup(
                Waypoint.at(20, 64, 0), Waypoint.at(21, 64, 0));
        following.setCurrentTargetIndex(1);
        WaypointGroup regular = WaypointGroup.create("Regular", "altar");
        regular.add(Waypoint.at(30, 64, 0));
        WaypointGroup temporary = dungeonGroup(Waypoint.at(40, 64, 0));
        temporary.setTemp(true);
        WaypointGroup complete = dungeonGroup(Waypoint.at(50, 64, 0));
        complete.setCurrentTargetIndex(complete.size());
        WaypointGroup subwaypoint = dungeonGroup(
                Waypoint.at(60, 64, 0),
                Waypoint.at(61, 64, 0).withFlags(
                        Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SKIP_ON_STAND));
        subwaypoint.advancePast(0);
        WaypointGroup empty = dungeonGroup();
        List<WaypointGroup> groups = Arrays.asList(
                eligible, following, regular, temporary, complete,
                subwaypoint, empty, null);
        Object world = new Object();

        DungeonEntryPathController.PreparedPaths entryOnly = controller.prepareInWorld(
                groups, new Vec3(0.5D, 64.0D, 0.5D), world, false);
        assertEquals(1, entryOnly.submissions().size());
        assertSame(eligible, entryOnly.submissions().get(0).group());
        assertEquals(1, computations.get());

        DungeonEntryPathController.PreparedPaths withFollowing =
                controller.prepareInWorld(
                        groups, new Vec3(0.5D, 64.0D, 0.5D), world, true);
        assertEquals(2, withFollowing.submissions().size());
        assertSame(eligible, withFollowing.submissions().get(0).group());
        assertSame(following, withFollowing.submissions().get(1).group());
        assertEquals(2, computations.get());
    }

    @Test
    void prepareReturnsNoWorkForMissingInputs() {
        DungeonEntryPathController controller = new DungeonEntryPathController(
                (level, playerPosition, target) -> {
                    throw new AssertionError("pathfinder must not run");
                },
                () -> 0L);
        WaypointGroup group = dungeonGroup(Waypoint.at(10, 64, 0));
        Object world = new Object();

        assertTrue(controller.prepareInWorld(
                null, Vec3.ZERO, world, false).submissions().isEmpty());
        assertTrue(controller.prepareInWorld(
                List.of(group), null, world, false).submissions().isEmpty());
        assertTrue(controller.prepareInWorld(
                List.of(group), Vec3.ZERO, null, false).submissions().isEmpty());
    }

    private static GroundPathfinder.PathResult successfulPath(
            Vec3 playerPosition, Waypoint target) {
        BlockPos start = GroundPathfinder.floorPos(playerPosition);
        BlockPos goal = GroundPathfinder.targetBlock(target);
        List<Vec3> points = new ArrayList<>(List.of(
                new Vec3(playerPosition.x, playerPosition.y + 0.12D, playerPosition.z),
                new Vec3(target.centerX(), target.centerY(), target.centerZ())));
        return new GroundPathfinder.PathResult(
                points,
                new GroundPathfinder.Diagnostics(
                        start,
                        goal,
                        start,
                        goal,
                        GroundPathfinder.FailureReason.NONE,
                        1,
                        8_000,
                        1L));
    }

    private static WaypointGroup dungeonGroup(Waypoint... waypoints) {
        WaypointGroup group = WaypointGroup.create("Dungeon path", "altar");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        for (Waypoint waypoint : waypoints) group.add(waypoint);
        return group;
    }
}
