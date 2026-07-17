package com.babbur.waypointer.render;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class GroundPathfinder {

    private static final int DEFAULT_MAX_DISTANCE = 128;
    private static final int DEFAULT_MAX_EXPANSIONS = 2_000;
    static final int NO_DISTANCE_LIMIT = Integer.MAX_VALUE;
    private static final int SEARCH_PADDING = 12;
    private static final int SEARCH_VERTICAL_PADDING = 32;
    private static final int TARGET_SEARCH_RADIUS = 4;
    private static final double LINE_Y_OFFSET = 0.12D;
    private static final double LINE_SAMPLE_STEP = 0.45D;
    private static final int[][] NEIGHBORS = buildNeighbors();

    private GroundPathfinder() {
    }

    interface Grid {
        boolean walkable(int x, int y, int z);
    }

    static BlockPos floorPos(Vec3 pos) {
        if (pos == null) return null;
        return floorPos(pos.x, pos.y, pos.z);
    }

    static BlockPos floorPos(double x, double y, double z) {
        return new BlockPos(blockCoordinate(x), blockCoordinate(y), blockCoordinate(z));
    }

    static BlockPos targetBlock(Waypoint target) {
        return target == null ? null : new BlockPos(target.x(), target.y(), target.z());
    }

    static void moveLineStart(List<Vec3> points, Vec3 playerPos) {
        if (points == null || points.isEmpty() || playerPos == null) return;
        points.set(0, lineStart(playerPos));
    }

    static List<Vec3> findPath(ClientLevel level, Vec3 playerPos, Waypoint target) {
        return findPath(level, playerPos, target, DEFAULT_MAX_DISTANCE, DEFAULT_MAX_EXPANSIONS);
    }

    static List<Vec3> findPath(ClientLevel level, Vec3 playerPos, Waypoint target,
                               int maxDistance, int maxExpansions) {
        return findPath(level, playerPos, target, maxDistance, maxExpansions,
                SEARCH_PADDING, SEARCH_VERTICAL_PADDING);
    }

    static List<Vec3> findPath(ClientLevel level, Vec3 playerPos, Waypoint target,
                               int maxDistance, int maxExpansions,
                               int searchPadding, int searchVerticalPadding) {
        if (level == null || playerPos == null || target == null) return List.of();

        BlockPos rawStart = floorPos(playerPos);
        BlockPos rawGoal = targetBlock(target);
        BlockPos start = nearestPassable(level, rawStart, 1, 2);
        BlockPos goal = nearestPassable(level, rawGoal, TARGET_SEARCH_RADIUS, TARGET_SEARCH_RADIUS);
        if (start == null || goal == null) return List.of();

        Grid grid = (x, y, z) -> isWalkableForPlayer(level, x, y, z);
        List<BlockPos> cells = findPath(grid, start, goal, maxDistance, maxExpansions,
                searchPadding, searchVerticalPadding);
        if (cells.isEmpty()) return List.of();
        return toLinePoints(playerPos, target, cells);
    }

    static List<BlockPos> findPath(Grid grid, BlockPos start, BlockPos goal,
                                   int maxDistance, int maxExpansions) {
        return findPath(grid, start, goal, maxDistance, maxExpansions,
                SEARCH_PADDING, SEARCH_VERTICAL_PADDING);
    }

    static List<BlockPos> findPath(Grid grid, BlockPos start, BlockPos goal,
                                   int maxDistance, int maxExpansions,
                                   int searchPadding, int searchVerticalPadding) {
        if (grid == null || start == null || goal == null) return List.of();
        if (maxDistance < 0 || maxExpansions <= 0) return List.of();
        if (!grid.walkable(start.getX(), start.getY(), start.getZ())) return List.of();
        if (!grid.walkable(goal.getX(), goal.getY(), goal.getZ())) return List.of();

        Cell startCell = new Cell(start.getX(), start.getY(), start.getZ());
        Cell goalCell = new Cell(goal.getX(), goal.getY(), goal.getZ());
        if (startCell.equals(goalCell)) return List.of(start);
        double maxDistanceSq = (double) maxDistance * maxDistance;
        if (distanceSquared(startCell, goalCell) > maxDistanceSq) return List.of();
        if (lineClear(grid, startCell, goalCell)) {
            return List.of(start, goal);
        }

        int horizontalPadding = Math.max(0, searchPadding);
        int verticalPadding = Math.max(0, searchVerticalPadding);
        int minX = Math.min(start.getX(), goal.getX()) - horizontalPadding;
        int maxX = Math.max(start.getX(), goal.getX()) + horizontalPadding;
        int minY = Math.min(start.getY(), goal.getY()) - verticalPadding;
        int maxY = Math.max(start.getY(), goal.getY()) + verticalPadding;
        int minZ = Math.min(start.getZ(), goal.getZ()) - horizontalPadding;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + horizontalPadding;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<Cell, Double> best = new HashMap<>();
        Node first = new Node(startCell, null, 0.0D, heuristic(startCell, goalCell));
        open.add(first);
        best.put(startCell, 0.0D);

        int expansions = 0;
        while (!open.isEmpty() && expansions++ < maxExpansions) {
            Node current = open.poll();
            Double knownBest = best.get(current.cell);
            if (knownBest == null || current.cost > knownBest + 1.0E-6D) continue;
            if (current.cell.equals(goalCell)) {
                return simplifyBySight(grid, reconstruct(current));
            }

            for (int[] neighbor : NEIGHBORS) {
                Cell next = new Cell(
                        current.cell.x + neighbor[0],
                        current.cell.y + neighbor[1],
                        current.cell.z + neighbor[2]);
                if (!inside(next, minX, maxX, minY, maxY, minZ, maxZ)) continue;
                if (distanceSquared(startCell, next) > maxDistanceSq) continue;
                if (!canTraverse(grid, current.cell, next)) continue;

                double nextCost = current.cost
                        + Math.sqrt(neighbor[0] * neighbor[0]
                                + neighbor[1] * neighbor[1]
                                + neighbor[2] * neighbor[2]);
                Double previous = best.get(next);
                if (previous != null && previous <= nextCost) continue;
                best.put(next, nextCost);
                open.add(new Node(next, current, nextCost, nextCost + heuristic(next, goalCell)));
            }
        }
        return List.of();
    }

    private static BlockPos nearestPassable(ClientLevel level, BlockPos center,
                                            int horizontalRadius, int verticalRadius) {
        if (level == null || center == null) return null;

        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int radius = 0; radius <= horizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                        int x = center.getX() + dx;
                        int y = center.getY() + dy;
                        int z = center.getZ() + dz;
                        if (!isWalkableForPlayer(level, x, y, z)) continue;

                        int score = Math.abs(dx) + Math.abs(dz) + Math.abs(dy);
                        if (score >= bestScore) continue;
                        bestScore = score;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean isWalkableForPlayer(ClientLevel level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = new BlockPos(x, y + 1, z);
        BlockPos support = new BlockPos(x, y - 1, z);
        return isPassable(level, feet)
                && isPassable(level, head)
                && !isPassable(level, support);
    }

    private static boolean isPassable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private static List<Vec3> toLinePoints(Vec3 playerPos, Waypoint target, List<BlockPos> cells) {
        if (cells.isEmpty()) return List.of();

        List<Vec3> points = new ArrayList<>(cells.size() + 1);
        points.add(lineStart(playerPos));
        for (int i = 1; i < cells.size(); i++) {
            points.add(center(cells.get(i)));
        }

        Vec3 targetPoint = new Vec3(target.centerX(), target.centerY(), target.centerZ());
        Vec3 currentLast = points.get(points.size() - 1);
        if (currentLast.distanceToSqr(targetPoint) > 1.0E-6D) {
            points.add(targetPoint);
        }
        return points;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static Vec3 lineStart(Vec3 playerPos) {
        return new Vec3(playerPos.x, playerPos.y + LINE_Y_OFFSET, playerPos.z);
    }

    private static List<BlockPos> reconstruct(Node end) {
        ArrayList<BlockPos> path = new ArrayList<>();
        for (Node node = end; node != null; node = node.previous) {
            path.add(new BlockPos(node.cell.x, node.cell.y, node.cell.z));
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static List<BlockPos> simplifyBySight(Grid grid, List<BlockPos> path) {
        if (path.size() <= 2) return path;

        ArrayList<BlockPos> out = new ArrayList<>();
        int cursor = 0;
        out.add(path.get(cursor));
        while (cursor < path.size() - 1) {
            int next = path.size() - 1;
            Cell from = cell(path.get(cursor));
            while (next > cursor + 1 && !lineClear(grid, from, cell(path.get(next)))) {
                next--;
            }
            out.add(path.get(next));
            cursor = next;
        }
        return out;
    }

    private static boolean lineClear(Grid grid, Cell from, Cell to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(length / LINE_SAMPLE_STEP));
        Cell previous = from;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            Cell current = new Cell(
                    blockCoordinate(from.x + dx * t),
                    blockCoordinate(from.y + dy * t),
                    blockCoordinate(from.z + dz * t));
            if (!current.equals(previous) && !canTraverse(grid, previous, current)) return false;
            previous = current;
        }
        return true;
    }

    private static boolean canTraverse(Grid grid, Cell from, Cell to) {
        int dx = Integer.compare(to.x, from.x);
        int dz = Integer.compare(to.z, from.z);
        if (Math.abs(to.x - from.x) > 1
                || Math.abs(to.y - from.y) > 1
                || Math.abs(to.z - from.z) > 1) return false;
        if (!grid.walkable(to.x, to.y, to.z)) return false;

        if (dx == 0 || dz == 0) return true;

        int cornerY = Math.max(from.y, to.y);
        return grid.walkable(from.x + dx, cornerY, from.z)
                && grid.walkable(from.x, cornerY, from.z + dz);
    }

    private static Cell cell(BlockPos pos) {
        return new Cell(pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean inside(Cell cell, int minX, int maxX, int minY, int maxY,
                                  int minZ, int maxZ) {
        return cell.x >= minX
                && cell.x <= maxX
                && cell.y >= minY
                && cell.y <= maxY
                && cell.z >= minZ
                && cell.z <= maxZ;
    }

    private static double heuristic(Cell from, Cell goal) {
        return distance(from, goal);
    }

    private static double distance(Cell a, Cell b) {
        return Math.sqrt(distanceSquared(a, b));
    }

    private static double distanceSquared(Cell a, Cell b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int blockCoordinate(double value) {
        return (int) Math.floor(value);
    }

    private static int[][] buildNeighbors() {
        int[][] neighbors = new int[26][3];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    neighbors[index++] = new int[] { dx, dy, dz };
                }
            }
        }
        return neighbors;
    }

    private record Cell(int x, int y, int z) {
    }

    private record Node(Cell cell, Node previous, double cost, double score) {
    }
}
