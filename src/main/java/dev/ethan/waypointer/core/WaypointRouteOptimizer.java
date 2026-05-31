package dev.ethan.waypointer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reorders sequenced routes with a deterministic nearest-neighbor pass.
 */
public final class WaypointRouteOptimizer {

    public static final class Result {
        public final boolean changed;
        public final int selectedIndex;

        private Result(boolean changed, int selectedIndex) {
            this.changed = changed;
            this.selectedIndex = selectedIndex;
        }
    }

    private static final class RouteBlock {
        final int originalStart;
        final int originalEndExclusive;
        final Waypoint anchor;

        private RouteBlock(int originalStart, int originalEndExclusive, Waypoint anchor) {
            this.originalStart = originalStart;
            this.originalEndExclusive = originalEndExclusive;
            this.anchor = anchor;
        }
    }

    private WaypointRouteOptimizer() {}

    public static Result optimizeNearestNeighbor(WaypointGroup group, int preferredStartIndex) {
        Objects.requireNonNull(group, "group");

        List<RouteBlock> blocks = collectRouteBlocks(group);
        if (blocks.size() < 2) {
            return new Result(false, -1);
        }

        int startBlockIndex = normalizeStartBlockIndex(group, blocks, preferredStartIndex);
        boolean[] visited = new boolean[blocks.size()];
        List<RouteBlock> ordered = new ArrayList<>(blocks.size());

        RouteBlock current = blocks.get(startBlockIndex);
        visited[startBlockIndex] = true;
        ordered.add(current);

        while (ordered.size() < blocks.size()) {
            int nextIndex = nearestUnvisitedBlock(blocks, visited, current.anchor);
            visited[nextIndex] = true;
            current = blocks.get(nextIndex);
            ordered.add(current);
        }

        List<Waypoint> optimized = flattenBlocks(group, ordered);
        if (sameReferenceOrder(group, optimized)) {
            return new Result(false, -1);
        }

        group.replaceWaypoints(optimized);
        group.resetProgress();
        return new Result(true, group.isEmpty() ? -1 : 0);
    }

    private static List<RouteBlock> collectRouteBlocks(WaypointGroup group) {
        List<RouteBlock> blocks = new ArrayList<>();
        int index = 0;
        while (index < group.size()) {
            if (group.isSubwaypoint(index)) {
                index++;
                continue;
            }
            int end = group.childEndExclusive(index);
            blocks.add(new RouteBlock(index, end, group.get(index)));
            index = Math.max(index + 1, end);
        }
        return blocks;
    }

    private static int normalizeStartBlockIndex(WaypointGroup group,
                                                List<RouteBlock> blocks,
                                                int preferredStartIndex) {
        int candidate = preferredStartIndex >= 0 && preferredStartIndex < group.size()
                ? preferredStartIndex
                : group.currentMainIndex();
        if (candidate >= 0 && candidate < group.size() && group.isSubwaypoint(candidate)) {
            candidate = group.parentMainIndex(candidate);
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).originalStart == candidate) return i;
        }
        return 0;
    }

    private static int nearestUnvisitedBlock(List<RouteBlock> blocks,
                                             boolean[] visited,
                                             Waypoint current) {
        int bestIndex = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < blocks.size(); i++) {
            if (visited[i]) continue;
            double distance = distanceSquared(current, blocks.get(i).anchor);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            throw new IllegalStateException("optimizer had no unvisited waypoint block");
        }
        return bestIndex;
    }

    private static List<Waypoint> flattenBlocks(WaypointGroup group, List<RouteBlock> ordered) {
        List<Waypoint> output = new ArrayList<>(group.size());
        for (RouteBlock block : ordered) {
            for (int i = block.originalStart; i < block.originalEndExclusive; i++) {
                output.add(group.get(i));
            }
        }
        return output;
    }

    private static boolean sameReferenceOrder(WaypointGroup group, List<Waypoint> optimized) {
        if (group.size() != optimized.size()) return false;
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i) != optimized.get(i)) return false;
        }
        return true;
    }

    private static double distanceSquared(Waypoint a, Waypoint b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
