package dev.ethan.waypointer.debug;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds an in-memory large-route scenario for the in-game debug menu.
 *
 * <p>The probe deliberately avoids touching {@link WaypointerConfig} setters or
 * storage. It is a read-only diagnostic workload: allocate synthetic route data,
 * capture counts through the same {@link PerformanceStats} path used by live
 * reports, and return copyable results to the debug screen.
 */
public final class PerformanceStressProbe {

    public static final int DEFAULT_WAYPOINT_COUNT = 12_000;
    public static final int DEFAULT_VISIBLE_OVERLAY_WAYPOINT_COUNT = 2_048;

    private static final int WAYPOINT_SPACING_BLOCKS = 32;
    private static final int DENSE_GRID_SPACING_BLOCKS = 4;
    private static final int LIVE_OVERLAY_SPACING_BLOCKS = 4;
    private static final int LIVE_OVERLAY_COLUMNS = 64;
    private static final int WAYPOINT_Y = 64;
    private static final String STRESS_ZONE_ID = "debug-stress";
    private static final String STRESS_ZONE_NAME = "Debug Stress";
    private static final String STRESS_ROUTE_NAME = "Debug Stress Route";
    private static final String LIVE_OVERLAY_ID_PREFIX = "debug-stress-live::";
    private static final String LIVE_OVERLAY_NAME = "Debug Stress Overlay";

    public static final class ScenarioResult {
        public final String name;
        public final String routeShape;
        public final int waypointCount;
        public final int localizedProbeIndex;
        public final double localizedPlayerX;
        public final double localizedPlayerY;
        public final double localizedPlayerZ;
        public final long setupNanos;
        public final long worstCaseCaptureNanos;
        public final long localizedCaptureNanos;
        public final PerformanceStats worstCaseStats;
        public final PerformanceStats localizedStats;

        private ScenarioResult(String name,
                               String routeShape,
                               int waypointCount,
                               int localizedProbeIndex,
                               double localizedPlayerX,
                               double localizedPlayerY,
                               double localizedPlayerZ,
                               long setupNanos,
                               long worstCaseCaptureNanos,
                               long localizedCaptureNanos,
                               PerformanceStats worstCaseStats,
                               PerformanceStats localizedStats) {
            this.name = Objects.requireNonNull(name, "name");
            this.routeShape = Objects.requireNonNull(routeShape, "routeShape");
            this.waypointCount = waypointCount;
            this.localizedProbeIndex = localizedProbeIndex;
            this.localizedPlayerX = localizedPlayerX;
            this.localizedPlayerY = localizedPlayerY;
            this.localizedPlayerZ = localizedPlayerZ;
            this.setupNanos = setupNanos;
            this.worstCaseCaptureNanos = worstCaseCaptureNanos;
            this.localizedCaptureNanos = localizedCaptureNanos;
            this.worstCaseStats = Objects.requireNonNull(worstCaseStats, "worstCaseStats");
            this.localizedStats = Objects.requireNonNull(localizedStats, "localizedStats");
        }
    }

    public static final class LiveOverlayResult {
        public final String groupId;
        public final String zoneId;
        public final String zoneName;
        public final int waypointCount;
        public final int replacedWaypointCount;
        public final int centerX;
        public final int centerY;
        public final int centerZ;
        public final int spacingBlocks;
        public final int columns;
        public final int rows;

        private LiveOverlayResult(String groupId,
                                  String zoneId,
                                  String zoneName,
                                  int waypointCount,
                                  int replacedWaypointCount,
                                  int centerX,
                                  int centerY,
                                  int centerZ,
                                  int spacingBlocks,
                                  int columns,
                                  int rows) {
            this.groupId = Objects.requireNonNull(groupId, "groupId");
            this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
            this.zoneName = Objects.requireNonNull(zoneName, "zoneName");
            this.waypointCount = waypointCount;
            this.replacedWaypointCount = replacedWaypointCount;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.spacingBlocks = spacingBlocks;
            this.columns = columns;
            this.rows = rows;
        }
    }

    public static final class ClearOverlayResult {
        public final int groupsRemoved;
        public final int waypointsRemoved;

        private ClearOverlayResult(int groupsRemoved, int waypointsRemoved) {
            this.groupsRemoved = groupsRemoved;
            this.waypointsRemoved = waypointsRemoved;
        }
    }

    public static final class Result {
        public final int waypointCount;
        public final int waypointSpacingBlocks;
        public final int localizedProbeIndex;
        public final double localizedPlayerX;
        public final double localizedPlayerY;
        public final double localizedPlayerZ;
        public final long setupNanos;
        public final long worstCaseCaptureNanos;
        public final long localizedCaptureNanos;
        public final PerformanceStats worstCaseStats;
        public final PerformanceStats localizedStats;
        public final List<ScenarioResult> scenarios;

        private Result(int waypointCount,
                       int waypointSpacingBlocks,
                       int localizedProbeIndex,
                       double localizedPlayerX,
                       double localizedPlayerY,
                       double localizedPlayerZ,
                       long setupNanos,
                       long worstCaseCaptureNanos,
                       long localizedCaptureNanos,
                       PerformanceStats worstCaseStats,
                       PerformanceStats localizedStats,
                       List<ScenarioResult> scenarios) {
            this.waypointCount = waypointCount;
            this.waypointSpacingBlocks = waypointSpacingBlocks;
            this.localizedProbeIndex = localizedProbeIndex;
            this.localizedPlayerX = localizedPlayerX;
            this.localizedPlayerY = localizedPlayerY;
            this.localizedPlayerZ = localizedPlayerZ;
            this.setupNanos = setupNanos;
            this.worstCaseCaptureNanos = worstCaseCaptureNanos;
            this.localizedCaptureNanos = localizedCaptureNanos;
            this.worstCaseStats = Objects.requireNonNull(worstCaseStats, "worstCaseStats");
            this.localizedStats = Objects.requireNonNull(localizedStats, "localizedStats");
            this.scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
        }
    }

    private PerformanceStressProbe() {
    }

    public static Result runDefault() {
        return run(DEFAULT_WAYPOINT_COUNT);
    }

    public static Result run(int waypointCount) {
        int safeCount = Math.max(1, waypointCount);

        List<ScenarioResult> scenarios = new ArrayList<>();

        long staticSetupStart = System.nanoTime();
        WaypointGroup staticLine = largeStaticRoute(safeCount);
        long staticSetupNanos = System.nanoTime() - staticSetupStart;
        ScenarioResult primary = captureScenario(
                "Static line",
                safeCount + " points, " + WAYPOINT_SPACING_BLOCKS + "-block spacing",
                staticLine,
                staticSetupNanos);
        scenarios.add(primary);

        int denseCount = Math.min(safeCount, DEFAULT_VISIBLE_OVERLAY_WAYPOINT_COUNT);
        long denseSetupStart = System.nanoTime();
        WaypointGroup denseGrid = denseStaticGridRoute(denseCount);
        long denseSetupNanos = System.nanoTime() - denseSetupStart;
        scenarios.add(captureScenario(
                "Dense grid",
                denseCount + " points, " + DENSE_GRID_SPACING_BLOCKS + "-block grid",
                denseGrid,
                denseSetupNanos));

        long sequenceSetupStart = System.nanoTime();
        WaypointGroup sequenceLine = largeSequenceRoute(safeCount);
        long sequenceSetupNanos = System.nanoTime() - sequenceSetupStart;
        scenarios.add(captureScenario(
                "Sequence line",
                safeCount + " points, sequence visibility",
                sequenceLine,
                sequenceSetupNanos));

        return new Result(
                safeCount,
                WAYPOINT_SPACING_BLOCKS,
                primary.localizedProbeIndex,
                primary.localizedPlayerX,
                primary.localizedPlayerY,
                primary.localizedPlayerZ,
                primary.setupNanos,
                primary.worstCaseCaptureNanos,
                primary.localizedCaptureNanos,
                primary.worstCaseStats,
                primary.localizedStats,
                scenarios);
    }

    public static LiveOverlayResult installLiveOverlay(ActiveGroupManager manager,
                                                       Zone zone,
                                                       int centerX,
                                                       int centerY,
                                                       int centerZ) {
        return installLiveOverlay(manager, zone, centerX, centerY, centerZ,
                DEFAULT_VISIBLE_OVERLAY_WAYPOINT_COUNT);
    }

    public static LiveOverlayResult installLiveOverlay(ActiveGroupManager manager,
                                                       Zone zone,
                                                       int centerX,
                                                       int centerY,
                                                       int centerZ,
                                                       int waypointCount) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(zone, "zone");

        int safeCount = Math.max(1, waypointCount);
        String groupId = liveOverlayGroupId(zone.id());
        WaypointGroup previous = manager.get(groupId);
        int replacedWaypointCount = previous == null ? 0 : previous.size();
        WaypointGroup group = liveOverlayRoute(groupId, zone, centerX, centerY, centerZ, safeCount);
        manager.add(group);
        int rows = (safeCount + LIVE_OVERLAY_COLUMNS - 1) / LIVE_OVERLAY_COLUMNS;

        return new LiveOverlayResult(
                groupId,
                zone.id(),
                zone.displayName(),
                safeCount,
                replacedWaypointCount,
                centerX,
                centerY,
                centerZ,
                LIVE_OVERLAY_SPACING_BLOCKS,
                LIVE_OVERLAY_COLUMNS,
                rows);
    }

    public static ClearOverlayResult clearLiveOverlays(ActiveGroupManager manager) {
        Objects.requireNonNull(manager, "manager");

        List<String> ids = new ArrayList<>();
        int waypointsRemoved = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (isLiveOverlayGroupId(group.id())) {
                ids.add(group.id());
                waypointsRemoved += group.size();
            }
        }

        for (String id : ids) {
            manager.remove(id);
        }

        return new ClearOverlayResult(ids.size(), waypointsRemoved);
    }

    private static WaypointGroup largeStaticRoute(int count) {
        WaypointGroup group = generatedRouteGroup(STRESS_ROUTE_NAME, STRESS_ZONE_ID,
                WaypointGroup.LoadMode.STATIC);
        for (int i = 0; i < count; i++) {
            group.add(Waypoint.at(i * WAYPOINT_SPACING_BLOCKS, WAYPOINT_Y, 0));
        }
        return group;
    }

    private static WaypointGroup largeSequenceRoute(int count) {
        WaypointGroup group = generatedRouteGroup(STRESS_ROUTE_NAME + " Sequence", STRESS_ZONE_ID,
                WaypointGroup.LoadMode.SEQUENCE);
        for (int i = 0; i < count; i++) {
            group.add(Waypoint.at(i * WAYPOINT_SPACING_BLOCKS, WAYPOINT_Y, 0));
        }
        return group;
    }

    private static WaypointGroup denseStaticGridRoute(int count) {
        WaypointGroup group = generatedRouteGroup(STRESS_ROUTE_NAME + " Dense Grid", STRESS_ZONE_ID,
                WaypointGroup.LoadMode.STATIC);
        int rows = (count + LIVE_OVERLAY_COLUMNS - 1) / LIVE_OVERLAY_COLUMNS;
        int startX = -(LIVE_OVERLAY_COLUMNS / 2) * DENSE_GRID_SPACING_BLOCKS;
        int startZ = -(rows / 2) * DENSE_GRID_SPACING_BLOCKS;
        for (int i = 0; i < count; i++) {
            int column = i % LIVE_OVERLAY_COLUMNS;
            int row = i / LIVE_OVERLAY_COLUMNS;
            int x = startX + column * DENSE_GRID_SPACING_BLOCKS;
            int z = startZ + row * DENSE_GRID_SPACING_BLOCKS;
            group.add(Waypoint.at(x, WAYPOINT_Y, z).withColor(overlayColor(i, count)));
        }
        return group;
    }

    private static WaypointGroup liveOverlayRoute(String groupId,
                                                  Zone zone,
                                                  int centerX,
                                                  int centerY,
                                                  int centerZ,
                                                  int count) {
        WaypointGroup group = new WaypointGroup(groupId, LIVE_OVERLAY_NAME, zone.id());
        group.setEnabled(true);
        group.setTemp(true);
        configureGeneratedRoute(group, WaypointGroup.LoadMode.STATIC);
        group.setDefaultRadius(0.5);

        int rows = (count + LIVE_OVERLAY_COLUMNS - 1) / LIVE_OVERLAY_COLUMNS;
        int startX = centerX - (LIVE_OVERLAY_COLUMNS / 2) * LIVE_OVERLAY_SPACING_BLOCKS;
        int startZ = centerZ - (rows / 2) * LIVE_OVERLAY_SPACING_BLOCKS;
        for (int i = 0; i < count; i++) {
            int column = i % LIVE_OVERLAY_COLUMNS;
            int row = i / LIVE_OVERLAY_COLUMNS;
            int x = startX + column * LIVE_OVERLAY_SPACING_BLOCKS;
            int z = startZ + row * LIVE_OVERLAY_SPACING_BLOCKS;
            Waypoint waypoint = Waypoint.at(x, centerY, z)
                    .withColor(overlayColor(i, count))
                    .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L);
            group.add(waypoint);
        }
        return group;
    }

    private static ScenarioResult captureScenario(String name,
                                                  String routeShape,
                                                  WaypointGroup group,
                                                  long setupNanos) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(routeShape, "routeShape");
        Objects.requireNonNull(group, "group");

        ActiveGroupManager manager = managerWithActiveStressRoute(group);
        WaypointerConfig config = stressConfig();

        long worstStart = System.nanoTime();
        PerformanceStats worstCaseStats = PerformanceStats.capture(manager, config);
        long worstCaseCaptureNanos = System.nanoTime() - worstStart;

        int localizedProbeIndex = Math.min(100, group.size() - 1);
        Waypoint probe = group.get(localizedProbeIndex);
        double localizedPlayerX = probe.x() + 0.5;
        double localizedPlayerY = probe.y() + 0.5;
        double localizedPlayerZ = probe.z() + 0.5;

        long localizedStart = System.nanoTime();
        PerformanceStats localizedStats = PerformanceStats.capture(manager, config,
                localizedPlayerX, localizedPlayerY, localizedPlayerZ);
        long localizedCaptureNanos = System.nanoTime() - localizedStart;

        return new ScenarioResult(
                name,
                routeShape,
                group.size(),
                localizedProbeIndex,
                localizedPlayerX,
                localizedPlayerY,
                localizedPlayerZ,
                setupNanos,
                worstCaseCaptureNanos,
                localizedCaptureNanos,
                worstCaseStats,
                localizedStats);
    }

    private static String liveOverlayGroupId(String zoneId) {
        return LIVE_OVERLAY_ID_PREFIX + (zoneId == null ? "" : zoneId);
    }

    private static boolean isLiveOverlayGroupId(String groupId) {
        return groupId != null && groupId.startsWith(LIVE_OVERLAY_ID_PREFIX);
    }

    private static int overlayColor(int index, int total) {
        double t = total <= 1 ? 0.0 : index / (double) (total - 1);
        int red = (int) Math.round(64.0 + 191.0 * t);
        int green = (int) Math.round(96.0 + 128.0 * (1.0 - Math.abs(2.0 * t - 1.0)));
        int blue = (int) Math.round(255.0 - 191.0 * t);
        return (red << 16) | (green << 8) | blue;
    }

    private static WaypointGroup generatedRouteGroup(String name,
                                                     String zoneId,
                                                     WaypointGroup.LoadMode loadMode) {
        WaypointGroup group = WaypointGroup.create(name, zoneId);
        configureGeneratedRoute(group, loadMode);
        return group;
    }

    private static void configureGeneratedRoute(WaypointGroup group,
                                                WaypointGroup.LoadMode loadMode) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(loadMode, "loadMode");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(loadMode);
    }

    private static ActiveGroupManager managerWithActiveStressRoute(WaypointGroup group) {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone(STRESS_ZONE_ID, STRESS_ZONE_NAME));
        manager.add(group);
        return manager;
    }

    private static WaypointerConfig stressConfig() {
        return new WaypointerConfig();
    }
}
