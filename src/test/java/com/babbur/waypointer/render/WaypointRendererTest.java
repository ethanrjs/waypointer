package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointRendererTest {

    @Test
    void depthPassDetectionTracksDepthCheckedAndThroughWallWaypointsSeparately() {
        WaypointGroup normalOnly = groupWith(waypoint(0));
        WaypointGroup depthOnly = groupWith(waypoint(Waypoint.FLAG_DEPTH_CHECKED));
        WaypointGroup mixed = groupWith(waypoint(0), waypoint(Waypoint.FLAG_DEPTH_CHECKED));

        assertFalse(WaypointRenderer.hasDepthCheckedWaypoint(List.of(normalOnly)));
        assertTrue(WaypointRenderer.hasThroughWallWaypoint(List.of(normalOnly)));

        assertTrue(WaypointRenderer.hasDepthCheckedWaypoint(List.of(depthOnly)));
        assertFalse(WaypointRenderer.hasThroughWallWaypoint(List.of(depthOnly)));

        assertTrue(WaypointRenderer.hasDepthCheckedWaypoint(List.of(mixed)));
        assertTrue(WaypointRenderer.hasThroughWallWaypoint(List.of(mixed)));
    }

    @Test
    void paintedGroupDetectionRequiresPaintAndAtLeastOneWaypoint() {
        WaypointGroup emptyPainted = WaypointGroup.create("Empty", "hub");
        emptyPainted.setPaint(WaypointPaint.solid(0x123456));
        WaypointGroup painted = groupWith(waypoint(0));
        painted.setPaint(WaypointPaint.solid(0x654321));

        assertFalse(WaypointRenderer.hasPaintedGroup(List.of(emptyPainted)));
        assertTrue(WaypointRenderer.hasPaintedGroup(List.of(emptyPainted, painted)));

        painted.setPaintEnabled(false);
        assertFalse(WaypointRenderer.hasPaintedGroup(List.of(painted)));

        WaypointGroup inherited = groupWith(waypoint(0));
        assertTrue(WaypointRenderer.hasPaintedGroup(
                List.of(inherited), WaypointPaint.solid(0xABCDEF)));
    }

    @Test
    void routeSegmentsWithAnyDepthCheckedEndpointUseDepthCheckedPass() {
        Waypoint normal = waypoint(0);
        Waypoint depthChecked = waypoint(Waypoint.FLAG_DEPTH_CHECKED);

        assertTrue(WaypointRenderer.routeSegmentMatchesDepthPass(normal, normal, false));
        assertFalse(WaypointRenderer.routeSegmentMatchesDepthPass(normal, normal, true));

        assertFalse(WaypointRenderer.routeSegmentMatchesDepthPass(normal, depthChecked, false));
        assertTrue(WaypointRenderer.routeSegmentMatchesDepthPass(normal, depthChecked, true));
        assertFalse(WaypointRenderer.routeSegmentMatchesDepthPass(depthChecked, normal, false));
        assertTrue(WaypointRenderer.routeSegmentMatchesDepthPass(depthChecked, normal, true));
        assertTrue(WaypointRenderer.routeSegmentMatchesDepthPass(depthChecked, depthChecked, true));
    }

    @Test
    void routeLineCollectorKeepsMixedDepthSegmentsInDepthPass() {
        WaypointGroup group = groupWith(waypoint(0), waypoint(Waypoint.FLAG_DEPTH_CHECKED));

        assertEquals(List.of(), routeLineSegments(group, false));
        assertEquals(List.of("0-1"), routeLineSegments(group, true));
    }

    @Test
    void anyEtherwarpRouteLineStartsAtCrouchingEyeHeightAboveWaypointBlock() {
        Waypoint waypoint = waypointAt(10, 64, -5, 0);

        assertEquals(new net.minecraft.world.phys.Vec3(10.5, 64.5, -4.5),
                WaypointRenderer.routeLineStart(waypoint, false, 1.27f));
        net.minecraft.world.phys.Vec3 etherwarpStart =
                WaypointRenderer.routeLineStart(waypoint, true, 1.27f);
        assertEquals(10.5, etherwarpStart.x);
        assertEquals(66.27, etherwarpStart.y, 0.00001);
        assertEquals(-4.5, etherwarpStart.z);
    }

    @Test
    void routeLineStartAtCameraClipsForwardAndOffsetsScreenDown() {
        var camera = new net.minecraft.world.phys.Vec3(10.5, 66.27, -4.5);
        var target = new net.minecraft.world.phys.Vec3(14.5, 66.27, -4.5);
        var screenDown = new net.minecraft.world.phys.Vec3(0.0, -1.0, 0.0);

        var clipped = WaypointRenderer.clipLineStartOutsideCamera(
                camera, target, camera, screenDown, 0.25, 0.12);

        var displacement = clipped.subtract(camera);
        var direction = target.subtract(camera).normalize();
        var perpendicular = displacement.subtract(direction.scale(displacement.dot(direction)));
        assertEquals(0.25, displacement.dot(direction), 0.00001);
        assertEquals(0.12, perpendicular.length(), 0.00001);
        assertTrue(perpendicular.dot(screenDown) > 0.0);
        assertTrue(displacement.cross(direction).length() > 0.0);
    }

    @Test
    void routeLineStartOutsideCameraClearanceRemainsUnchanged() {
        var camera = new net.minecraft.world.phys.Vec3(0.0, 64.0, 0.0);
        var start = new net.minecraft.world.phys.Vec3(0.0, 64.26, 0.0);

        assertEquals(start, WaypointRenderer.clipLineStartOutsideCamera(
                start, new net.minecraft.world.phys.Vec3(5.0, 64.0, 0.0), camera,
                new net.minecraft.world.phys.Vec3(0.0, -1.0, 0.0), 0.25, 0.12));
    }

    @Test
    void routeLineFullyInsideCameraClearanceCollapsesAtItsTarget() {
        var camera = new net.minecraft.world.phys.Vec3(0.0, 64.0, 0.0);
        var target = new net.minecraft.world.phys.Vec3(0.1, 64.0, 0.0);

        assertEquals(target, WaypointRenderer.clipLineStartOutsideCamera(
                camera, target, camera, new net.minecraft.world.phys.Vec3(0.0, -1.0, 0.0),
                0.25, 0.12));
    }

    @Test
    void routeLineCollectorSkipsSubwaypointsWithoutBreakingMainConnector() {
        WaypointGroup group = groupWith(
                waypoint(0),
                waypoint(0),
                waypoint(Waypoint.FLAG_DEPTH_CHECKED));
        assertTrue(group.toggleSubwaypoint(1));

        assertEquals(List.of("0-2"), routeLineSegments(group, true));
        assertEquals(List.of(), routeLineSegments(group, false));
    }

    @Test
    void focusedDungeonConnectorFollowsMineChildrenOneSegmentAtATime() {
        int mineFlags = Waypoint.FLAG_SKIP_ON_MINE;
        WaypointGroup group = groupWith(
                waypointAt(0, 64, 0, mineFlags),
                waypointAt(1, 64, 0, mineFlags | Waypoint.FLAG_SUBWAYPOINT),
                waypointAt(2, 64, 0, mineFlags | Waypoint.FLAG_SUBWAYPOINT),
                waypointAt(3, 64, 0, 0));

        assertEquals(List.of(), focusedDungeonRouteLineSegments(group, false));
        group.advancePast(0);
        assertEquals(List.of("0-1"), focusedDungeonRouteLineSegments(group, false));
        group.advancePast(1);
        assertEquals(List.of("1-2"), focusedDungeonRouteLineSegments(group, false));
        group.advancePast(2);
        assertEquals(List.of("2-3"), focusedDungeonRouteLineSegments(group, false));
    }

    @Test
    void dungeonLabelsOnlySurfaceTheFocusedPairAndNeverInventNumericNames() {
        WaypointGroup group = groupWith(waypoint(0), waypoint(0), waypoint(0));

        assertTrue(WaypointRenderer.isFocusedDungeonRouteLabel(group, 0));
        assertFalse(WaypointRenderer.isFocusedDungeonRouteLabel(group, 1));
        group.advancePast(0);
        assertTrue(WaypointRenderer.isFocusedDungeonRouteLabel(group, 0));
        assertTrue(WaypointRenderer.isFocusedDungeonRouteLabel(group, 1));
        assertFalse(WaypointRenderer.isFocusedDungeonRouteLabel(group, 2));

        assertFalse(WaypointRenderer.shouldShowDungeonWaypointName(waypoint(0)));
        assertTrue(WaypointRenderer.shouldShowDungeonWaypointName(
                new Waypoint(1, 64, 1, "TP", Waypoint.DEFAULT_COLOR, 0, 0.0)));
    }

    @Test
    void routeLineTraversalStreamsDenseRoutesInOrder() {
        WaypointGroup group = WaypointGroup.create("Dense Render Test", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        for (int i = 0; i < 2_048; i++) {
            group.add(waypointAt(i, 64, 0, 0));
        }

        int[] segmentCount = { 0 };
        int[] lastFrom = { -1 };
        int[] lastTo = { -1 };
        WaypointRenderer.forEachRouteLineSegment(group, false, i -> true, (from, to) -> {
            segmentCount[0]++;
            lastFrom[0] = from;
            lastTo[0] = to;
        });

        assertEquals(2_047, segmentCount[0]);
        assertEquals(2_046, lastFrom[0]);
        assertEquals(2_047, lastTo[0]);
    }

    @Test
    void subwaypointShapeBoundsConvertsAcceptedLocalShapeToWorldBounds() {
        Waypoint subwaypoint = waypointAt(10, 64, -5, Waypoint.FLAG_SUBWAYPOINT);

        AABB actual = WaypointRenderer.subwaypointShapeBounds(subwaypoint,
                new AABB(0.25, 0.0, 0.125, 0.75, 0.5, 0.875));

        assertAabbEquals(new AABB(10.25, 64.0, -4.875, 10.75, 64.5, -4.125), actual);
    }

    @Test
    void tinySubwaypointShapeBoundsRejectsPartialBlockShape() {
        Waypoint tinySubwaypoint = waypointAt(10, 64, -5,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT);
        Waypoint tinyInteractSubwaypoint = waypointAt(10, 64, -5,
                Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_SKIP_ON_INTERACT);
        AABB partialShape = new AABB(0.25, 0.0, 0.125, 0.75, 0.5, 0.875);

        assertFalse(WaypointRenderer.usesBlockShapeBounds(tinySubwaypoint));
        assertNull(WaypointRenderer.blockShapeWaypointBounds(tinySubwaypoint, partialShape));
        assertFalse(WaypointRenderer.usesBlockShapeBounds(tinyInteractSubwaypoint));
        assertNull(WaypointRenderer.blockShapeWaypointBounds(tinyInteractSubwaypoint, partialShape));
    }

    @Test
    void subwaypointShapeBoundsRejectsFullInvalidAndMainWaypointShapes() {
        Waypoint subwaypoint = waypointAt(10, 64, -5, Waypoint.FLAG_SUBWAYPOINT);
        Waypoint mainWaypointWithStyleFlags = waypointAt(10, 64, -5,
                Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_FILLED_SUBWAYPOINT);
        AABB partialShape = new AABB(0.25, 0.0, 0.25, 0.75, 0.5, 0.75);

        assertNull(WaypointRenderer.subwaypointShapeBounds(mainWaypointWithStyleFlags, partialShape));
        assertNull(WaypointRenderer.subwaypointShapeBounds(subwaypoint,
                new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
        assertNull(WaypointRenderer.subwaypointShapeBounds(subwaypoint,
                new AABB(0.0, 0.0, 0.0, 0.0, 1.0, 1.0)));
        assertNull(WaypointRenderer.subwaypointShapeBounds(subwaypoint,
                new AABB(0.0, 0.0, 0.0, Double.NaN, 1.0, 1.0)));
    }

    @Test
    void blockShapeWaypointBoundsAcceptsBlockTriggerWaypointShapes() {
        Waypoint interactWaypoint = waypointAt(10, 64, -5, Waypoint.FLAG_SKIP_ON_INTERACT);
        Waypoint mineWaypoint = waypointAt(10, 64, -5, Waypoint.FLAG_SKIP_ON_MINE);
        AABB localBounds = new AABB(0.375, 0.125, 0.25, 0.625, 0.875, 0.75);

        AABB interactBounds = WaypointRenderer.blockShapeWaypointBounds(
                interactWaypoint, localBounds);
        AABB mineBounds = WaypointRenderer.blockShapeWaypointBounds(mineWaypoint, localBounds);

        AABB expected = new AABB(10.375, 64.125, -4.75, 10.625, 64.875, -4.25);
        assertAabbEquals(expected, interactBounds);
        assertAabbEquals(expected, mineBounds);
    }

    @Test
    void lineOfSightSamplePointCoversCenterAndAllCorners() {
        AABB bounds = new AABB(1.0, 2.0, 3.0, 5.0, 8.0, 13.0);

        assertEquals("3.0,5.0,8.0", vecKey(WaypointRenderer.lineOfSightSamplePoint(bounds, 0)));

        java.util.Set<String> corners = new java.util.HashSet<>();
        for (int i = 1; i <= 8; i++) {
            corners.add(vecKey(WaypointRenderer.lineOfSightSamplePoint(bounds, i)));
        }

        assertEquals(8, corners.size());
        assertTrue(corners.contains("1.0,2.0,3.0"));
        assertTrue(corners.contains("5.0,2.0,3.0"));
        assertTrue(corners.contains("1.0,8.0,3.0"));
        assertTrue(corners.contains("5.0,8.0,3.0"));
        assertTrue(corners.contains("1.0,2.0,13.0"));
        assertTrue(corners.contains("5.0,2.0,13.0"));
        assertTrue(corners.contains("1.0,8.0,13.0"));
        assertTrue(corners.contains("5.0,8.0,13.0"));
    }

    @Test
    void lineOfSightAcceptsAHitAtTheSampleIndependentOfCameraDistance() {
        var sample = new net.minecraft.world.phys.Vec3(10.5, 64.0, -4.5);

        assertTrue(WaypointRenderer.lineOfSightHitReachesSample(
                new net.minecraft.world.phys.Vec3(10.5, 63.995, -4.5), sample));
        assertFalse(WaypointRenderer.lineOfSightHitReachesSample(
                new net.minecraft.world.phys.Vec3(10.5, 63.98, -4.5), sample));
    }

    @Test
    void waypointBoxBoundsAlignsTinyFallbackToSixteenthTextureCell() {
        int flags = Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT;
        Waypoint smallSubwaypoint = new Waypoint(10, 64, -5,
                "", Waypoint.DEFAULT_COLOR, flags, 0.0,
                Waypoint.TEMP_NONE, 0L,
                10 * Waypoint.PRECISE_SCALE,
                64 * Waypoint.PRECISE_SCALE,
                -5 * Waypoint.PRECISE_SCALE);

        AABB actual = WaypointRenderer.waypointBoxBounds(null, smallSubwaypoint);

        assertAabbEquals(new AABB(10.0, 64.0, -5.0, 10.0625, 64.0625, -4.9375), actual);
    }

    @Test
    void waypointBoxBoundsIgnoresSubwaypointStyleFlagsOnMainWaypoints() {
        Waypoint mainWaypointWithStyleFlags = waypointAt(10, 64, -5,
                Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_FILLED_SUBWAYPOINT);

        AABB actual = WaypointRenderer.waypointBoxBounds(null, mainWaypointWithStyleFlags);

        assertAabbEquals(new AABB(10.0, 64.0, -5.0, 11.0, 65.0, -4.0), actual);
    }

    @Test
    void onScreenCheckAcceptsInclusiveScreenEdgesOnlyForFiniteCoordinates() {
        assertTrue(WaypointRenderer.isOnScreen(0.0, 0.0, 800, 600));
        assertTrue(WaypointRenderer.isOnScreen(800.0, 600.0, 800, 600));

        assertFalse(WaypointRenderer.isOnScreen(-0.1, 100.0, 800, 600));
        assertFalse(WaypointRenderer.isOnScreen(100.0, -0.1, 800, 600));
        assertFalse(WaypointRenderer.isOnScreen(800.1, 100.0, 800, 600));
        assertFalse(WaypointRenderer.isOnScreen(100.0, 600.1, 800, 600));
        assertFalse(WaypointRenderer.isOnScreen(Double.NaN, 100.0, 800, 600));
        assertFalse(WaypointRenderer.isOnScreen(100.0, Double.POSITIVE_INFINITY, 800, 600));
        assertFalse(WaypointRenderer.isOnScreen(0.0, 0.0, 0, 600));
        assertFalse(WaypointRenderer.isOnScreen(0.0, 0.0, 800, 0));
    }

    @Test
    void labelScaleUsesUserScaleWhenDistanceScalingIsDisabled() {
        assertFloatEquals(1.25f,
                WaypointRenderer.labelScaleForDepth(12.0, 70.0f, false, 1.25));
        assertFloatEquals(4.0f,
                WaypointRenderer.labelScaleForDepth(12.0, 70.0f, false, 12.0));
        assertFloatEquals(1.0f,
                WaypointRenderer.labelScaleForDepth(12.0, 70.0f, false, Double.NaN));
    }

    @Test
    void labelScaleTracksDepthAndFovWhenDistanceScalingIsEnabled() {
        assertFloatEquals(1.0f,
                WaypointRenderer.labelScaleForDepth(24.0, 70.0f, true, 1.0));
        assertFloatEquals(2.0f,
                WaypointRenderer.labelScaleForDepth(12.0, 70.0f, true, 1.0));
        assertFloatEquals(0.5f,
                WaypointRenderer.labelScaleForDepth(48.0, 70.0f, true, 1.0));
        assertFloatEquals(0.25f,
                WaypointRenderer.labelScaleForDepth(0.0, 70.0f, true, 1.0));
    }

    @Test
    void progressPercentFormattingClampsAndRoundsForHudLabels() {
        assertEquals("0.0%", WaypointRenderer.formatProgressPercent(-1.0));
        assertEquals("0.0%", WaypointRenderer.formatProgressPercent(Double.NaN));
        assertEquals("33.3%", WaypointRenderer.formatProgressPercent(33.34));
        assertEquals("33.4%", WaypointRenderer.formatProgressPercent(33.35));
        assertEquals("100.0%", WaypointRenderer.formatProgressPercent(100.9));
    }

    @Test
    void irisFallbackMovesWaypointOutlinesToTheHudWithoutSuppressingFills() {
        assertTrue(WaypointRenderer.worldBoxOutlinesEnabled(
                WaypointerConfig.BoxStyle.OUTLINED, false));
        assertFalse(WaypointRenderer.worldBoxOutlinesEnabled(
                WaypointerConfig.BoxStyle.OUTLINED, true));
        assertFalse(WaypointRenderer.worldBoxOutlinesEnabled(
                WaypointerConfig.BoxStyle.FILLED, false));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                WaypointRenderer.hudFallbackBoxStyle(WaypointerConfig.BoxStyle.FILLED));
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED,
                WaypointRenderer.hudFallbackBoxStyle(
                        WaypointerConfig.BoxStyle.FILLED_OUTLINED));
    }

    @Test
    void irisHudLinesUseIntegerWidthAndDenseSampling() {
        assertEquals(3.0, WaypointRenderer.crispHudLineThickness(3.75));
        assertEquals(100, WaypointRenderer.screenLineSampleCount(100.0, 0.0));
        assertEquals(70, WaypointRenderer.screenLineSampleCount(70.0, 70.0));
    }

    @Test
    void editModeSubtitleNamesTheExitCommand() {
        assertEquals("EDIT MODE (exit: /wp editmode)", WaypointRenderer.editModeSubtitleText());
    }

    @Test
    void dungeonEntryPathReuseRequiresSameStartAndFreshCachedPath() {
        BlockPos oldStart = new BlockPos(0, 64, 0);
        BlockPos newStart = new BlockPos(1, 64, 0);

        assertTrue(WaypointRenderer.shouldReuseDungeonEntryPath(oldStart, 0L, oldStart, 249_999_999L));
        assertFalse(WaypointRenderer.shouldReuseDungeonEntryPath(oldStart, 0L, oldStart, 250_000_000L));
        assertFalse(WaypointRenderer.shouldReuseDungeonEntryPath(oldStart, 0L, newStart, 249_999_999L));
        assertFalse(WaypointRenderer.shouldReuseDungeonEntryPath(oldStart, 0L, newStart, 250_000_000L));
    }

    @Test
    void dungeonEntryPathFollowingWaypointsRequiresOptIn() {
        WaypointGroup group = groupWith(waypoint(0), waypointAt(2, 64, 2, 0));
        group.setZoneId("altar");
        group.setCurrentIndex(1);

        assertFalse(WaypointRenderer.shouldRenderDungeonEntryPath(group, false));
        assertTrue(WaypointRenderer.shouldRenderDungeonEntryPath(group, true));
    }

    @Test
    void dungeonEntryPathDoesNotTargetSubwaypoints() {
        WaypointGroup group = groupWith(
                waypoint(0),
                waypointAt(2, 64, 2, Waypoint.FLAG_SUBWAYPOINT));
        group.setZoneId("altar");
        group.advancePast(0);

        assertFalse(WaypointRenderer.shouldRenderDungeonEntryPath(group, true));
    }

    @Test
    void hideBeaconWaypointsDisappearAsSoonAsProgressMovesPastThem() {
        Waypoint hidden = waypoint(Waypoint.FLAG_HIDE_BEACON);

        assertFalse(WaypointRenderer.shouldForceHideReachedWaypoint(1, 1, hidden));
        assertTrue(WaypointRenderer.shouldForceHideReachedWaypoint(1, 2, hidden));
        assertFalse(WaypointRenderer.shouldForceHideReachedWaypoint(1, 2, waypoint(0)));
    }

    @Test
    void dungeonLinesAndTracersUseDungeonDefaultsIndependently() {
        WaypointerConfig config = new WaypointerConfig();
        DungeonConfig dungeonConfig = new DungeonConfig();
        WaypointGroup normal = groupWith(waypoint(0));
        WaypointGroup dungeon = groupWith(waypoint(0));
        dungeon.setZoneId("altar");

        config.setShowRouteLines(false);
        config.setShowTracer(true);
        assertFalse(WaypointRenderer.routeLinesEnabled(normal, config, dungeonConfig));
        assertTrue(WaypointRenderer.routeLinesEnabled(dungeon, config, dungeonConfig));
        assertTrue(TracerRenderer.tracersEnabled(normal, config, dungeonConfig));
        assertFalse(TracerRenderer.tracersEnabled(dungeon, config, dungeonConfig));

        config.setShowRouteLines(true);
        config.setShowTracer(false);
        dungeonConfig.setShowDungeonRouteLines(false);
        dungeonConfig.setShowDungeonTracers(true);
        assertTrue(WaypointRenderer.routeLinesEnabled(normal, config, dungeonConfig));
        assertFalse(WaypointRenderer.routeLinesEnabled(dungeon, config, dungeonConfig));
        assertFalse(TracerRenderer.tracersEnabled(normal, config, dungeonConfig));
        assertTrue(TracerRenderer.tracersEnabled(dungeon, config, dungeonConfig));
    }

    private static WaypointGroup groupWith(Waypoint... waypoints) {
        WaypointGroup group = WaypointGroup.create("Render Test", "hub");
        for (Waypoint waypoint : waypoints) {
            group.add(waypoint);
        }
        return group;
    }

    private static Waypoint waypoint(int flags) {
        return new Waypoint(1, 64, 1, "", Waypoint.DEFAULT_COLOR, flags, 0.0);
    }

    private static Waypoint waypointAt(int x, int y, int z, int flags) {
        return new Waypoint(x, y, z, "", Waypoint.DEFAULT_COLOR, flags, 0.0);
    }

    private static List<String> routeLineSegments(WaypointGroup group, boolean depthCheckedPass) {
        List<String> segments = new ArrayList<>();
        WaypointRenderer.forEachRouteLineSegment(group, depthCheckedPass, i -> true,
                (from, to) -> segments.add(from + "-" + to));
        return segments;
    }

    private static List<String> focusedDungeonRouteLineSegments(
            WaypointGroup group, boolean depthCheckedPass) {
        List<String> segments = new ArrayList<>();
        WaypointRenderer.forEachFocusedDungeonRouteLineSegment(
                group, depthCheckedPass, i -> true,
                (from, to) -> segments.add(from + "-" + to));
        return segments;
    }

    private static void assertAabbEquals(AABB expected, AABB actual) {
        assertEquals(expected.minX, actual.minX, 0.0001);
        assertEquals(expected.minY, actual.minY, 0.0001);
        assertEquals(expected.minZ, actual.minZ, 0.0001);
        assertEquals(expected.maxX, actual.maxX, 0.0001);
        assertEquals(expected.maxY, actual.maxY, 0.0001);
        assertEquals(expected.maxZ, actual.maxZ, 0.0001);
    }

    private static void assertFloatEquals(float expected, float actual) {
        assertTrue(Math.abs(expected - actual) < 0.0001f,
                "expected " + expected + " but was " + actual);
    }

    private static String vecKey(net.minecraft.world.phys.Vec3 vec) {
        return vec.x + "," + vec.y + "," + vec.z;
    }
}
