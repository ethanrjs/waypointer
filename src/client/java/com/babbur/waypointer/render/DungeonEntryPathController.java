package com.babbur.waypointer.render;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class DungeonEntryPathController {
    static final long SUCCESS_RETRY_NANOS = 250_000_000L;
    static final long FAILURE_RETRY_NANOS = 500_000_000L;
    private static final int PATH_CACHE_SIZE = 8;
    private static final int MAXIMUM_EXPANSIONS = 8_000;
    private static final int SEARCH_PADDING = 48;

    @FunctionalInterface
    interface PathComputer {
        GroundPathfinder.PathResult find(
                ClientLevel level, Vec3 playerPosition, Waypoint target);
    }

    private Object cachedWorldIdentity;
    private final PathComputer pathComputer;
    private final LongSupplier nanoTime;
    private final Map<PathTarget, CachedPath> pathCache =
            new LinkedHashMap<>(PATH_CACHE_SIZE + 1, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PathTarget, CachedPath> eldest) {
                    return size() > PATH_CACHE_SIZE;
                }
            };

    DungeonEntryPathController() {
        this((level, playerPosition, target) -> GroundPathfinder.findPathResult(
                        level,
                        playerPosition,
                        target,
                        GroundPathfinder.NO_DISTANCE_LIMIT,
                        MAXIMUM_EXPANSIONS,
                        SEARCH_PADDING,
                        SEARCH_PADDING),
                System::nanoTime);
    }

    DungeonEntryPathController(PathComputer pathComputer, LongSupplier nanoTime) {
        this.pathComputer = Objects.requireNonNull(pathComputer, "pathComputer");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    record LookupDiagnostics(
            WaypointGroup group,
            GroundPathfinder.PathResult result,
            boolean cacheHit,
            long cacheAgeNanos) {
    }

    record Submission(WaypointGroup group, List<Vec3> points) {
        Submission {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    record PreparedPaths(List<Submission> submissions, List<LookupDiagnostics> lookups) {
        PreparedPaths {
            submissions = submissions == null ? List.of() : List.copyOf(submissions);
            lookups = lookups == null ? List.of() : List.copyOf(lookups);
        }

        static PreparedPaths empty() {
            return new PreparedPaths(List.of(), List.of());
        }
    }

    PreparedPaths prepare(
            Iterable<WaypointGroup> groups,
            Vec3 playerPosition,
            ClientLevel level,
            boolean includeFollowingWaypoints) {
        return prepareInWorld(
                groups, playerPosition, level, level, includeFollowingWaypoints);
    }

    PreparedPaths prepareInWorld(
            Iterable<WaypointGroup> groups,
            Vec3 playerPosition,
            Object worldIdentity,
            boolean includeFollowingWaypoints) {
        return prepareInWorld(
                groups, playerPosition, worldIdentity, null, includeFollowingWaypoints);
    }

    private PreparedPaths prepareInWorld(
            Iterable<WaypointGroup> groups,
            Vec3 playerPosition,
            Object worldIdentity,
            ClientLevel level,
            boolean includeFollowingWaypoints) {
        if (worldIdentityChanged(cachedWorldIdentity, worldIdentity)) {
            resetForWorld(worldIdentity);
        }
        if (groups == null || playerPosition == null || worldIdentity == null) {
            return PreparedPaths.empty();
        }

        List<Submission> submissions = new ArrayList<>();
        List<LookupDiagnostics> lookups = new ArrayList<>();
        for (WaypointGroup group : groups) {
            if (!shouldPrepare(group, includeFollowingWaypoints)) continue;
            Waypoint target = group.current();
            if (target == null) continue;

            PathLookup lookup = lookup(level, playerPosition, target);
            lookups.add(new LookupDiagnostics(
                    group, lookup.result(), lookup.cacheHit(), lookup.cacheAgeNanos()));
            submissions.add(new Submission(group, lookup.result().points()));
        }
        return new PreparedPaths(submissions, lookups);
    }

    static boolean shouldPrepare(WaypointGroup group, boolean includeFollowingWaypoints) {
        int currentIndex = group == null ? -1 : group.currentIndex();
        return group != null
                && !group.temp()
                && !group.isComplete()
                && (currentIndex == 0 || includeFollowingWaypoints)
                && group.size() > 0
                && !group.isSubwaypoint(currentIndex)
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    static boolean shouldReuse(
            BlockPos cachedStart,
            boolean cachedPathSucceeded,
            long computedAtNanos,
            BlockPos start,
            long nowNanos) {
        long interval = cachedPathSucceeded
                ? SUCCESS_RETRY_NANOS : FAILURE_RETRY_NANOS;
        return Objects.equals(start, cachedStart)
                && Math.max(0L, nowNanos - computedAtNanos) < interval;
    }

    static <T> boolean worldIdentityChanged(T cachedWorld, T nextWorld) {
        return cachedWorld != nextWorld;
    }

    private void resetForWorld(Object worldIdentity) {
        cachedWorldIdentity = worldIdentity;
        pathCache.clear();
    }

    private PathLookup lookup(ClientLevel level, Vec3 playerPosition, Waypoint target) {
        BlockPos start = GroundPathfinder.floorPos(playerPosition);
        PathTarget targetKey = PathTarget.from(target);
        long now = nanoTime.getAsLong();
        CachedPath cached = pathCache.get(targetKey);
        if (cached != null && shouldReuse(
                cached.start(), cached.result().diagnostics().success(),
                cached.computedAtNanos(), start, now)) {
            GroundPathfinder.moveLineStart(cached.result().points(), playerPosition);
            return new PathLookup(
                    cached.result(), true, Math.max(0L, now - cached.computedAtNanos()));
        }

        long startedAtNanos = nanoTime.getAsLong();
        GroundPathfinder.PathResult result;
        try {
            result = pathComputer.find(level, playerPosition, target);
        } catch (RuntimeException error) {
            Waypointer.LOGGER.warn(
                    "Dungeon entry path calculation failed; using tracer fallback", error);
            BlockPos goal = GroundPathfinder.targetBlock(target);
            result = new GroundPathfinder.PathResult(
                    List.of(), new GroundPathfinder.Diagnostics(
                            start,
                            goal,
                            start,
                            goal,
                            GroundPathfinder.FailureReason.CALCULATION_FAILED,
                            0,
                            MAXIMUM_EXPANSIONS,
                            Math.max(0L, nanoTime.getAsLong() - startedAtNanos)));
        }
        pathCache.put(targetKey, new CachedPath(start, result, now));
        return new PathLookup(result, false, 0L);
    }

    private record PathTarget(
            BlockPos block, double centerX, double centerY, double centerZ) {
        static PathTarget from(Waypoint target) {
            return new PathTarget(
                    GroundPathfinder.targetBlock(target),
                    target.centerX(), target.centerY(), target.centerZ());
        }
    }

    private record CachedPath(
            BlockPos start,
            GroundPathfinder.PathResult result,
            long computedAtNanos) {
    }

    private record PathLookup(
            GroundPathfinder.PathResult result,
            boolean cacheHit,
            long cacheAgeNanos) {
    }
}
