package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Last-frame renderer decisions exposed to the debug report. */
public final class RenderDiagnostics {

    private static final String PENDING_OUTCOME = "pending renderer decision";
    private static final Map<String, MutableGroupSnapshot> GROUPS = new LinkedHashMap<>();
    private static final Set<WaypointGroup> SUBMITTED_DUNGEON_PATHS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static TracerSettings tracerSettings = new TracerSettings(
            false, 0.0, 0.0, false, false, 0.0, false, false, false);
    private static DungeonPathSettings dungeonPathSettings = new DungeonPathSettings(false, false);
    private static volatile boolean detailedCaptureEnabled;
    private static volatile boolean irisHudFallbackActive;
    private static volatile long updatedAtEpochMillis;

    private RenderDiagnostics() {
    }

    public static synchronized void setDetailedCaptureEnabled(boolean enabled) {
        if (detailedCaptureEnabled == enabled) return;
        detailedCaptureEnabled = enabled;
        GROUPS.clear();
        tracerSettings = new TracerSettings(
                false, 0.0, 0.0, false, false, 0.0, false, false, false);
        dungeonPathSettings = new DungeonPathSettings(false, false);
        updatedAtEpochMillis = 0L;
    }

    public static long lastUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public static synchronized Snapshot snapshot() {
        List<GroupSnapshot> groups = new ArrayList<>(GROUPS.size());
        for (MutableGroupSnapshot group : GROUPS.values()) {
            groups.add(group.snapshot());
        }
        return new Snapshot(
                updatedAtEpochMillis,
                tracerSettings,
                dungeonPathSettings,
                List.copyOf(groups));
    }

    static void beginFrame(Iterable<WaypointGroup> groups,
                           WaypointerConfig config,
                           boolean irisHudFallbackActiveNow) {
        SUBMITTED_DUNGEON_PATHS.clear();
        irisHudFallbackActive = irisHudFallbackActiveNow;
        if (!detailedCaptureEnabled) return;

        synchronized (RenderDiagnostics.class) {
            if (!detailedCaptureEnabled) return;
            updatedAtEpochMillis = System.currentTimeMillis();
            tracerSettings = new TracerSettings(
                    config.showTracer(),
                    config.tracerOpacity(),
                    config.tracerThickness(),
                    config.matchTracerToWaypointColor(),
                    config.hideWaypointsNearPlayer(),
                    config.hideWaypointsNearRadius(),
                    config.hideTracerOnStaticRoutes(),
                    config.irisShaderHudFallback(),
                    irisHudFallbackActiveNow);
            dungeonPathSettings = new DungeonPathSettings(
                    config.showDungeonEntryPathToFirstWaypoint(),
                    config.showDungeonEntryPathToFollowingWaypoints());

            GROUPS.clear();
            if (groups == null) return;
            for (WaypointGroup group : groups) {
                if (group == null || group.routeKind() != WaypointGroup.RouteKind.DUNGEON) continue;
                boolean eligible = WaypointRenderer.shouldRenderDungeonEntryPath(
                        group, config.showDungeonEntryPathToFollowingWaypoints());
                GROUPS.put(group.id(), MutableGroupSnapshot.from(group, eligible));
            }
        }
    }

    static void recordPathLookup(WaypointGroup group,
                                 GroundPathfinder.PathResult result,
                                 boolean cacheHit,
                                 long cacheAgeNanos) {
        if (!detailedCaptureEnabled) return;
        synchronized (RenderDiagnostics.class) {
            MutableGroupSnapshot state = state(group);
            if (state == null || result == null) return;

            GroundPathfinder.Diagnostics diagnostics = result.diagnostics();
            int pointCount = result.points().size();
            boolean drawableSuccess = diagnostics.success() && pointCount >= 2;
            String reason = diagnostics.reason().description();
            if (diagnostics.success() && pointCount < 2) {
                reason = "fewer than 2 drawable points";
            }
            state.path = new PathSnapshot(
                    cacheHit ? "hit" : "miss",
                    Math.max(0L, cacheAgeNanos) / 1_000_000.0,
                    drawableSuccess ? "success" : "empty",
                    pointCount,
                    diagnostics.computeTimeNanos() / 1_000_000.0,
                    BlockPosition.from(diagnostics.rawStart()),
                    BlockPosition.from(diagnostics.rawGoal()),
                    BlockPosition.from(diagnostics.resolvedStart()),
                    BlockPosition.from(diagnostics.resolvedGoal()),
                    diagnostics.expansions(),
                    diagnostics.expansionLimit(),
                    reason);
            if (!drawableSuccess) {
                state.finalOutcome = "nothing submitted: dungeon path empty";
            }
        }
    }

    static void recordDungeonPathSubmission(WaypointGroup group, boolean submitted) {
        if (group == null) return;
        if (submitted) {
            SUBMITTED_DUNGEON_PATHS.add(group);
        } else {
            SUBMITTED_DUNGEON_PATHS.remove(group);
        }
        if (!detailedCaptureEnabled) return;
        synchronized (RenderDiagnostics.class) {
            MutableGroupSnapshot state = state(group);
            if (state == null) return;
            state.dungeonPathSubmitted = submitted;
            if (submitted) {
                state.finalOutcome = "replaced by dungeon path";
            } else if ("empty".equals(state.path.result())) {
                state.finalOutcome = "nothing submitted: dungeon path empty";
            }
        }
    }

    static boolean shouldSuppressStraightTracer(WaypointGroup group) {
        return group != null && shouldSuppressStraightTracer(
                irisHudFallbackActive, SUBMITTED_DUNGEON_PATHS.contains(group));
    }

    static boolean shouldSuppressStraightTracer(boolean irisHudFallbackActive,
                                                boolean dungeonPathSubmitted) {
        return dungeonPathSubmitted && !irisHudFallbackActive;
    }

    static void recordStraightTracerSuppressed(WaypointGroup group) {
        if (!detailedCaptureEnabled) return;
        synchronized (RenderDiagnostics.class) {
            MutableGroupSnapshot state = state(group);
            if (state == null) return;
            state.straightTracerSuppressed = true;
            state.finalOutcome = "replaced by dungeon path";
        }
    }

    static void recordStraightTracerSubmitted(WaypointGroup group) {
        if (!detailedCaptureEnabled) return;
        synchronized (RenderDiagnostics.class) {
            MutableGroupSnapshot state = state(group);
            if (state == null) return;
            state.straightTracerSubmitted = true;
            state.straightTracerSuppressed = false;
            state.finalOutcome = "straight tracer submitted";
        }
    }

    static void recordNoStraightTracer(Iterable<WaypointGroup> groups, String reason) {
        if (!detailedCaptureEnabled || groups == null) return;
        synchronized (RenderDiagnostics.class) {
            for (WaypointGroup group : groups) {
                MutableGroupSnapshot state = state(group);
                if (state == null || state.dungeonPathSubmitted) continue;
                if ("empty".equals(state.path.result())) {
                    state.finalOutcome = "nothing submitted: dungeon path empty; " + reason;
                } else {
                    state.finalOutcome = "nothing submitted: " + reason;
                }
            }
        }
    }

    static void recordNoStraightTracer(WaypointGroup group, String reason) {
        if (!detailedCaptureEnabled) return;
        synchronized (RenderDiagnostics.class) {
            MutableGroupSnapshot state = state(group);
            if (state == null || state.dungeonPathSubmitted) return;
            if ("empty".equals(state.path.result())) {
                state.finalOutcome = "nothing submitted: dungeon path empty; " + reason;
            } else {
                state.finalOutcome = "nothing submitted: " + reason;
            }
        }
    }

    private static MutableGroupSnapshot state(WaypointGroup group) {
        return group == null ? null : GROUPS.get(group.id());
    }

    public record Snapshot(long updatedAtEpochMillis,
                           TracerSettings tracer,
                           DungeonPathSettings dungeonPath,
                           List<GroupSnapshot> groups) {
        public Snapshot {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    public record TracerSettings(boolean enabled,
                                 double opacity,
                                 double thickness,
                                 boolean inheritsWaypointColor,
                                 boolean hideNearPlayer,
                                 double hideNearPlayerRadius,
                                 boolean hideOnStaticRoutes,
                                 boolean irisHudFallbackConfigured,
                                 boolean irisHudFallbackActive) {
    }

    public record DungeonPathSettings(boolean entryPathToFirstWaypoint,
                                      boolean continueAfterFirstWaypoint) {
    }

    public record GroupSnapshot(String groupId,
                                String groupName,
                                String zoneId,
                                String loadMode,
                                int currentIndex,
                                TargetSnapshot currentTarget,
                                boolean entryPathEligible,
                                boolean straightTracerSuppressed,
                                boolean straightTracerSubmitted,
                                boolean dungeonPathSubmitted,
                                PathSnapshot path,
                                String finalOutcome) {
    }

    public record TargetSnapshot(String name,
                                 int blockX,
                                 int blockY,
                                 int blockZ,
                                 double worldX,
                                 double worldY,
                                 double worldZ) {
        private static TargetSnapshot from(Waypoint waypoint) {
            if (waypoint == null) return null;
            return new TargetSnapshot(
                    waypoint.name(),
                    waypoint.x(),
                    waypoint.y(),
                    waypoint.z(),
                    waypoint.centerX(),
                    waypoint.centerY(),
                    waypoint.centerZ());
        }
    }

    public record PathSnapshot(String cacheStatus,
                               double cacheAgeMillis,
                               String result,
                               int pointCount,
                               double computeTimeMillis,
                               BlockPosition rawStart,
                               BlockPosition rawGoal,
                               BlockPosition resolvedStart,
                               BlockPosition resolvedGoal,
                               int expansions,
                               int expansionLimit,
                               String reason) {
        private static PathSnapshot notAttempted() {
            return new PathSnapshot(
                    "not attempted",
                    0.0,
                    "not attempted",
                    0,
                    0.0,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    "path not attempted this frame");
        }
    }

    public record BlockPosition(int x, int y, int z) {
        private static BlockPosition from(BlockPos position) {
            return position == null
                    ? null
                    : new BlockPosition(position.getX(), position.getY(), position.getZ());
        }
    }

    private static final class MutableGroupSnapshot {
        private final String groupId;
        private final String groupName;
        private final String zoneId;
        private final String loadMode;
        private final int currentIndex;
        private final TargetSnapshot currentTarget;
        private final boolean entryPathEligible;
        private boolean straightTracerSuppressed;
        private boolean straightTracerSubmitted;
        private boolean dungeonPathSubmitted;
        private PathSnapshot path = PathSnapshot.notAttempted();
        private String finalOutcome = PENDING_OUTCOME;

        private MutableGroupSnapshot(String groupId, String groupName, String zoneId,
                                     String loadMode, int currentIndex,
                                     TargetSnapshot currentTarget,
                                     boolean entryPathEligible) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.zoneId = zoneId;
            this.loadMode = loadMode;
            this.currentIndex = currentIndex;
            this.currentTarget = currentTarget;
            this.entryPathEligible = entryPathEligible;
        }

        private static MutableGroupSnapshot from(WaypointGroup group,
                                                 boolean entryPathEligible) {
            return new MutableGroupSnapshot(
                    group.id(),
                    group.name(),
                    group.zoneId(),
                    group.loadMode().name(),
                    group.currentIndex(),
                    TargetSnapshot.from(group.current()),
                    entryPathEligible);
        }

        private GroupSnapshot snapshot() {
            return new GroupSnapshot(
                    groupId,
                    groupName,
                    zoneId,
                    loadMode,
                    currentIndex,
                    currentTarget,
                    entryPathEligible,
                    straightTracerSuppressed,
                    straightTracerSubmitted,
                    dungeonPathSubmitted,
                    path,
                    finalOutcome);
        }
    }
}
