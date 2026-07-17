package com.babbur.waypointer.debug;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Captures a copyable performance snapshot from Waypointer's live model.
 *
 * <p>The values are intentionally counts and estimates, not profiler claims:
 * they describe how much work the current config asks hot paths to consider
 * before camera frustum, distance, and GPU-side effects enter the picture.
 */
public record PerformanceStats(
        Instant capturedAt,
        String currentZoneId,
        String currentZoneName,
        int knownZoneCount,
        int totalGroups,
        int activeGroups,
        int enabledGroups,
        int tempGroups,
        int totalWaypoints,
        int tempWaypoints,
        int activeWaypoints,
        int activeVisibleWaypoints,
        int activeLabelCandidates,
        int activeStaticWaypoints,
        int activeSequenceWaypoints,
        int staticGroups,
        int sequenceGroups,
        int estimatedLineBoxVertices,
        int estimatedFillBoxVertices,
        int estimatedBeamVertices,
        int estimatedProximityIndexVisitsPerTick,
        long usedMemoryBytes,
        long maxMemoryBytes,
        GroupStats largestGroup,
        GroupStats largestActiveGroup,
        List<GroupStats> activeGroupStats) {

    private static final int LINE_BOX_VERTICES = 24;
    private static final int FILLED_BOX_VERTICES = 24;
    private static final int FLAT_BEAM_VERTICES = 16;
    private static final int TEXTURED_BEAM_VERTICES = 32;

    public static PerformanceStats capture(ActiveGroupManager manager,
                                           WaypointerConfig config) {
        return capture(manager, config, 0.0, 0.0, 0.0, false);
    }

    public static PerformanceStats capture(ActiveGroupManager manager,
                                           WaypointerConfig config,
                                           double playerX,
                                           double playerY,
                                           double playerZ) {
        return capture(manager, config, playerX, playerY, playerZ, true);
    }

    private static PerformanceStats capture(ActiveGroupManager manager,
                                            WaypointerConfig config,
                                            double playerX,
                                            double playerY,
                                            double playerZ,
                                            boolean hasPlayerPosition) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(config, "config");

        List<WaypointGroup> active = manager.activeGroups();
        List<GroupStats> activeStats = new ArrayList<>(active.size());
        Runtime runtime = Runtime.getRuntime();

        int enabledGroups = 0;
        int tempGroups = 0;
        int totalWaypoints = 0;
        int tempWaypoints = 0;
        int staticGroups = 0;
        int sequenceGroups = 0;
        GroupStats largestGroup = null;

        for (WaypointGroup group : manager.allGroups()) {
            if (group.enabled()) enabledGroups++;
            if (group.temp()) tempGroups++;
            if (group.loadMode() == WaypointGroup.LoadMode.STATIC) staticGroups++;
            else sequenceGroups++;

            totalWaypoints += group.size();
            for (Waypoint waypoint : group.waypoints()) {
                if (waypoint.isTemp()) tempWaypoints++;
            }

            GroupStats stats = groupStats(group, config,
                    playerX, playerY, playerZ, hasPlayerPosition);
            if (largestGroup == null || stats.waypoints() > largestGroup.waypoints()) {
                largestGroup = stats;
            }
        }

        int activeWaypoints = 0;
        int activeVisibleWaypoints = 0;
        int activeLabelCandidates = 0;
        int activeStaticWaypoints = 0;
        int activeSequenceWaypoints = 0;
        int estimatedBeamVertices = 0;
        int estimatedProximityVisits = 0;

        for (WaypointGroup group : active) {
            GroupStats stats = groupStats(group, config,
                    playerX, playerY, playerZ, hasPlayerPosition);
            activeStats.add(stats);

            activeWaypoints += stats.waypoints();
            activeVisibleWaypoints += stats.renderableWaypoints();
            activeLabelCandidates += stats.labelCandidates();
            estimatedProximityVisits += stats.proximityIndexVisitsPerTick();

            if (group.loadMode() == WaypointGroup.LoadMode.STATIC) {
                activeStaticWaypoints += stats.waypoints();
            } else {
                activeSequenceWaypoints += stats.waypoints();
            }
            estimatedBeamVertices += estimatedBeamVertices(group, config);
        }

        activeStats.sort(Comparator.comparingInt(GroupStats::waypoints).reversed());
        GroupStats largestActiveGroup = activeStats.isEmpty() ? null : activeStats.get(0);

        int lineVertices = drawsLines(config)
                ? activeVisibleWaypoints * LINE_BOX_VERTICES
                : 0;
        int fillVertices = drawsFills(config)
                ? activeVisibleWaypoints * FILLED_BOX_VERTICES
                : 0;
        Zone currentZone = manager.currentZone();

        return new PerformanceStats(
                Instant.now(),
                currentZone == null ? "(none)" : currentZone.id(),
                currentZone == null ? "(none)" : currentZone.displayName(),
                manager.knownZoneIds().size(),
                manager.allGroups().size(),
                active.size(),
                enabledGroups,
                tempGroups,
                totalWaypoints,
                tempWaypoints,
                activeWaypoints,
                activeVisibleWaypoints,
                activeLabelCandidates,
                activeStaticWaypoints,
                activeSequenceWaypoints,
                staticGroups,
                sequenceGroups,
                lineVertices,
                fillVertices,
                estimatedBeamVertices,
                estimatedProximityVisits,
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory(),
                largestGroup,
                largestActiveGroup,
                List.copyOf(activeStats));
    }

    private static GroupStats groupStats(WaypointGroup group,
                                         WaypointerConfig config,
                                         double playerX,
                                         double playerY,
                                         double playerZ,
                                         boolean hasPlayerPosition) {
        int renderable = countRenderableWaypoints(group, config);
        int labels = countLabelCandidates(group, config);

        return new GroupStats(
                group.id(),
                group.name(),
                group.zoneId(),
                group.loadMode().name(),
                group.enabled(),
                group.temp(),
                group.waypoints().size(),
                group.currentIndex(),
                renderable,
                labels,
                proximityIndexVisits(group, config,
                        playerX, playerY, playerZ, hasPlayerPosition));
    }

    private static int countRenderableWaypoints(WaypointGroup group,
                                                WaypointerConfig config) {
        Counter counter = new Counter();
        group.forEachVisibleIndex(index -> {
            if (isRenderable(group, config, index)) counter.value++;
        });
        return counter.value;
    }

    private static int countLabelCandidates(WaypointGroup group,
                                            WaypointerConfig config) {
        if (!config.showWaypointNames() && !config.showWaypointDistances()) return 0;

        Counter counter = new Counter();
        group.forEachVisibleIndex(index -> {
            if (!isRenderable(group, config, index)) return;
            if (group.get(index).hasFlag(Waypoint.FLAG_HIDE_NAME)) return;
            counter.value++;
        });
        return counter.value;
    }

    private static boolean isRenderable(WaypointGroup group,
                                        WaypointerConfig config,
                                        int index) {
        if (config.hideReachedStaticWaypointsUntilCycleComplete()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && group.isStaticWaypointReached(index)) {
            return false;
        }

        Waypoint waypoint = group.get(index);
        if (renderState(group, index) == RenderState.COMPLETED
                && (!config.showCompleted()
                || waypoint.hasFlag(Waypoint.FLAG_HIDE_BEACON))) {
            return false;
        }
        return true;
    }

    private static int estimatedBeamVertices(WaypointGroup group,
                                             WaypointerConfig config) {
        if (config.beaconOpacity() <= 0.0) return 0;
        int beamVertices = config.useBeaconBeamTextures()
                ? TEXTURED_BEAM_VERTICES
                : FLAT_BEAM_VERTICES;
        return switch (config.beaconBeamMode()) {
            case OFF -> 0;
            case CURRENT -> hasCurrentBeam(group, config) ? beamVertices : 0;
            case ALL_VISIBLE -> countRenderableWaypoints(group, config) * beamVertices;
        };
    }

    private static boolean hasCurrentBeam(WaypointGroup group,
                                          WaypointerConfig config) {
        int index = currentBeamIndex(group);
        return index >= 0 && index < group.size() && isRenderable(group, config, index);
    }

    private static int currentBeamIndex(WaypointGroup group) {
        if (group.isEmpty()) return -1;
        if (group.isComplete()) return group.size() - 1;
        return Math.max(0, Math.min(group.currentIndex(), group.size() - 1));
    }

    private static int proximityIndexVisits(WaypointGroup group,
                                            WaypointerConfig config,
                                            double playerX,
                                            double playerY,
                                            double playerZ,
                                            boolean hasPlayerPosition) {
        if (group.temp()) return 0;
        if (config.hideReachedStaticWaypointsUntilCycleComplete()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            return hasPlayerPosition
                    ? nearbyCandidateCount(group, 0, playerX, playerY, playerZ)
                    : group.size();
        }
        if (group.isComplete()) return 0;
        if (!config.skipAheadMechanicEnabled() || !group.skipAheadEnabled()) return 1;
        return hasPlayerPosition
                ? nearbyCandidateCount(group, group.currentIndex(), playerX, playerY, playerZ)
                : Math.max(0, group.size() - group.currentIndex());
    }

    private static int nearbyCandidateCount(WaypointGroup group, int minimumIndex,
                                            double playerX, double playerY,
                                            double playerZ) {
        Counter counter = new Counter();
        group.forEachNearbyIndex(playerX, playerY, playerZ, group.maxEffectiveRadius(), i -> {
            if (i >= minimumIndex && !group.isProximitySuppressed(i)) counter.value++;
            return true;
        });
        return counter.value;
    }

    private static RenderState renderState(WaypointGroup group, int index) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            return RenderState.CURRENT;
        }
        if (index < group.currentIndex()) return RenderState.COMPLETED;
        if (index == group.currentIndex()) return RenderState.CURRENT;
        return RenderState.UPCOMING;
    }

    private static boolean drawsLines(WaypointerConfig config) {
        return config.beaconOpacity() > 0.0
                && config.boxStyle() != WaypointerConfig.BoxStyle.FILLED;
    }

    private static boolean drawsFills(WaypointerConfig config) {
        return config.beaconOpacity() > 0.0
                && config.boxStyle() != WaypointerConfig.BoxStyle.OUTLINED;
    }

    private enum RenderState {
        COMPLETED,
        CURRENT,
        UPCOMING
    }

    private static final class Counter {
        int value;
    }

    public record GroupStats(
            String id,
            String name,
            String zoneId,
            String loadMode,
            boolean enabled,
            boolean temp,
            int waypoints,
            int currentIndex,
            int renderableWaypoints,
            int labelCandidates,
            int proximityIndexVisitsPerTick) {
    }
}
