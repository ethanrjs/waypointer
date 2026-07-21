package com.babbur.waypointer.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundPathfinderTest {

    @Test
    void pathfindsAroundBlockedCellsInThreeDimensions() {
        Set<String> blocked = Set.of("1,0,0", "2,0,0", "3,0,0");
        GroundPathfinder.Grid grid = (x, y, z) ->
                y == 0
                        && x >= 0 && x <= 4
                        && z >= 0 && z <= 1
                        && !blocked.contains(key(x, y, z));

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(4, 0, 0),
                16,
                64);

        assertFalse(path.isEmpty());
        assertEquals(new BlockPos(0, 0, 0), path.get(0));
        assertEquals(new BlockPos(4, 0, 0), path.get(path.size() - 1));
        assertTrue(path.stream().anyMatch(pos -> pos.getZ() == 1));
        assertFalse(path.stream().anyMatch(pos -> blocked.contains(key(pos))));
    }

    @Test
    void acceptsStraightVerticalRoutesWhenSpaceIsClear() {
        GroundPathfinder.Grid grid = (x, y, z) -> x == 0 && z == 0 && y >= 0 && y <= 5;

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(0, 5, 0),
                16,
                64);

        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(0, 5, 0)), path);
    }

    @Test
    void returnsEmptyInsteadOfStraightFallbackWhenNoRouteExists() {
        GroundPathfinder.Grid grid = (x, y, z) -> y == 0 && z == 0 && x >= 0 && x <= 2 && x != 1;

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(2, 0, 0),
                16,
                64);

        assertTrue(path.isEmpty());
    }

    @Test
    void diagnosesNoPassableStartAndPreservesRequestedEndpoints() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos goal = new BlockPos(2, 0, 0);
        GroundPathfinder.GridPathResult result = GroundPathfinder.findPathResult(
                (x, y, z) -> x == 2 && y == 0 && z == 0,
                start,
                goal,
                16,
                64,
                12,
                32);

        assertTrue(result.cells().isEmpty());
        assertEquals(GroundPathfinder.FailureReason.NO_PASSABLE_START,
                result.diagnostics().reason());
        assertEquals(start, result.diagnostics().rawStart());
        assertEquals(goal, result.diagnostics().rawGoal());
        assertEquals(start, result.diagnostics().resolvedStart());
        assertEquals(goal, result.diagnostics().resolvedGoal());
        assertEquals(0, result.diagnostics().expansions());
        assertEquals(64, result.diagnostics().expansionLimit());
        assertTrue(result.diagnostics().computeTimeNanos() >= 0L);
    }

    @Test
    void diagnosesTargetsOutsideDistanceLimitWithoutExpanding() {
        GroundPathfinder.GridPathResult result = GroundPathfinder.findPathResult(
                (x, y, z) -> true,
                new BlockPos(0, 0, 0),
                new BlockPos(20, 0, 0),
                4,
                64,
                12,
                32);

        assertTrue(result.cells().isEmpty());
        assertEquals(GroundPathfinder.FailureReason.OUTSIDE_DISTANCE_LIMIT,
                result.diagnostics().reason());
        assertEquals(0, result.diagnostics().expansions());
    }

    @Test
    void distinguishesExpansionLimitFromNoRouteWithinBounds() {
        GroundPathfinder.Grid detourGrid = (x, y, z) ->
                y == 0
                        && x >= 0 && x <= 4
                        && z >= 0 && z <= 1
                        && !(x == 1 && z == 0);
        GroundPathfinder.GridPathResult limited = GroundPathfinder.findPathResult(
                detourGrid,
                new BlockPos(0, 0, 0),
                new BlockPos(4, 0, 0),
                16,
                1,
                12,
                32);

        GroundPathfinder.Grid isolatedEndpoints = (x, y, z) ->
                y == 0 && z == 0 && (x == 0 || x == 2);
        GroundPathfinder.GridPathResult exhausted = GroundPathfinder.findPathResult(
                isolatedEndpoints,
                new BlockPos(0, 0, 0),
                new BlockPos(2, 0, 0),
                16,
                64,
                12,
                32);

        assertEquals(GroundPathfinder.FailureReason.EXPANSION_LIMIT_EXHAUSTED,
                limited.diagnostics().reason());
        assertEquals(1, limited.diagnostics().expansions());
        assertEquals(1, limited.diagnostics().expansionLimit());
        assertEquals(GroundPathfinder.FailureReason.NO_ROUTE_WITHIN_BOUNDS,
                exhausted.diagnostics().reason());
        assertTrue(exhausted.diagnostics().expansions() < exhausted.diagnostics().expansionLimit());
    }

    @Test
    void doesNotCutDiagonallyThroughBlockedCorners() {
        Set<BlockPos> walkable = Set.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 1));
        GroundPathfinder.Grid grid = (x, y, z) -> walkable.contains(new BlockPos(x, y, z));

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 1),
                4,
                16);

        assertTrue(path.isEmpty());
    }

    @Test
    void allowsDiagonalMovementWhenBothSidesOfCornerAreWalkable() {
        Set<BlockPos> walkable = Set.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(1, 0, 1));
        GroundPathfinder.Grid grid = (x, y, z) -> walkable.contains(new BlockPos(x, y, z));

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 1),
                4,
                16);

        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 1)), path);
    }

    @Test
    void returnsEmptyWhenGoalIsOutsideBoundedSearch() {
        GroundPathfinder.Grid grid = (x, y, z) -> true;

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(20, 0, 0),
                4,
                64);

        assertTrue(path.isEmpty());
    }

    @Test
    void acceptsClearRoutesBeyondDefaultDistanceWhenUnbounded() {
        GroundPathfinder.Grid grid = (x, y, z) -> y == 0 && z == 0 && x >= 0 && x <= 300;

        List<BlockPos> path = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(300, 0, 0),
                GroundPathfinder.NO_DISTANCE_LIMIT,
                64);

        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(300, 0, 0)), path);
    }

    @Test
    void widerSearchPaddingFindsRoomScaleDetours() {
        GroundPathfinder.Grid grid = (x, y, z) ->
                y == 0
                        && x >= 0 && x <= 10
                        && z >= -20 && z <= 20
                        && !(x == 5 && z >= -12 && z <= 12);

        List<BlockPos> narrow = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(10, 0, 0),
                GroundPathfinder.NO_DISTANCE_LIMIT,
                2_000);
        List<BlockPos> wide = GroundPathfinder.findPath(
                grid,
                new BlockPos(0, 0, 0),
                new BlockPos(10, 0, 0),
                GroundPathfinder.NO_DISTANCE_LIMIT,
                8_000,
                20,
                4);

        assertTrue(narrow.isEmpty());
        assertFalse(wide.isEmpty());
        assertTrue(wide.stream().anyMatch(pos -> Math.abs(pos.getZ()) > 12));
    }

    @Test
    void cachedLineStartCanFollowPlayerWithoutRepathing() {
        List<Vec3> points = new java.util.ArrayList<>(List.of(
                new Vec3(0.0, 64.12, 0.0),
                new Vec3(5.5, 65.5, 5.5)));

        GroundPathfinder.moveLineStart(points, new Vec3(2.25, 70.0, -1.5));

        assertEquals(new Vec3(2.25, 70.12, -1.5), points.get(0));
        assertEquals(new Vec3(5.5, 65.5, 5.5), points.get(1));
    }

    private static String key(BlockPos pos) {
        return key(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
