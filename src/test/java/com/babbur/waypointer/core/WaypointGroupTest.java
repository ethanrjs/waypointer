package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointGroupTest {

    private static Waypoint wp(int x, int y, int z) {
        return Waypoint.at(x, y, z);
    }

    private static WaypointGroup route() {
        WaypointGroup g = WaypointGroup.create("route", "dungeon_f7");
        g.add(wp(0, 70, 0));
        g.add(wp(10, 70, 0));
        g.add(wp(20, 70, 0));
        g.add(wp(30, 70, 0));
        return g;
    }

    @Test
    void insert_shiftsCurrentIndexWhenInsertingBeforeIt() {
        WaypointGroup g = route();
        g.advancePast(1);                 // currentIndex = 2
        g.insert(0, wp(-10, 70, 0));      // insert at 0 shifts everything right
        assertEquals(3, g.currentIndex());
        assertEquals(5, g.size());
    }

    @Test
    void remove_decrementsCurrentWhenRemovingBeforeIt() {
        WaypointGroup g = route();
        g.advancePast(2);                 // currentIndex = 3
        g.remove(0);
        assertEquals(2, g.currentIndex());
        assertEquals(3, g.size());
    }

    @Test
    void remove_clampsToEndWhenRemovingTail() {
        WaypointGroup g = route();
        g.advancePast(2);                 // currentIndex = 3 (the last one)
        g.remove(3);                      // remove the current one
        assertEquals(3, g.currentIndex()); // clamped to size()
        assertTrue(g.isComplete());
    }

    @Test
    void remove_middleSubwaypointKeepsRemainingSiblingsAttachedToTheirParent() {
        WaypointGroup g = WaypointGroup.create("Subwaypoints", "dungeon_f7");
        g.add(wp(0, 70, 0));
        for (int i = 1; i <= 5; i++) {
            g.add(wp(i, 70, 0));
            assertTrue(g.toggleSubwaypoint(i));
        }

        g.remove(3);

        assertEquals(List.of(0, 1, 2, 4, 5), g.waypoints().stream().map(Waypoint::x).toList());
        for (int i = 1; i < g.size(); i++) {
            assertTrue(g.isSubwaypoint(i), "remaining child " + i + " was promoted");
            assertEquals(0, g.parentMainIndex(i));
        }
    }

    @Test
    void move_currentIndexFollowsMovedWaypoint() {
        WaypointGroup g = route();
        g.advancePast(0);                 // currentIndex = 1 -> points at (10,70,0)
        g.move(1, 3);                     // that waypoint moves to end
        assertEquals(3, g.currentIndex());
        Waypoint now = g.current();
        assertNotNull(now);
        assertEquals(10, now.x());
    }

    @Test
    void move_currentIndexAdjustsWhenJumpingOverIt() {
        WaypointGroup g = route();
        g.advancePast(1);                 // currentIndex = 2
        g.move(0, 3);                     // moves first element past current
        assertEquals(1, g.currentIndex()); // current waypoint is now at position 1
    }

    @Test
    void advancePast_monotonic() {
        WaypointGroup g = route();
        g.advancePast(2);
        g.advancePast(1);                 // should NOT go backward
        assertEquals(3, g.currentIndex());
    }

    @Test
    void retreatToPreviousTarget_movesBackOneMainWaypoint() {
        WaypointGroup g = route();
        g.advancePast(1);

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(1, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void retreatToPreviousTarget_returnsFalseAtFirstWaypoint() {
        WaypointGroup g = route();

        assertFalse(g.retreatToPreviousTarget());

        assertEquals(0, g.currentIndex());
    }

    @Test
    void retreatToPreviousTarget_fromCompleteRouteTargetsLastMainWaypoint() {
        WaypointGroup g = route();
        g.advancePast(3);
        assertTrue(g.isComplete());

        assertTrue(g.retreatToPreviousTarget());

        assertFalse(g.isComplete());
        assertEquals(3, g.currentIndex());
    }

    @Test
    void retreatToPreviousTarget_walksExplicitSubwaypointTargetsBackward() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.setCurrentTargetIndex(2);

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(1, g.currentIndex());
        assertEquals(g.get(1), g.current());
        assertEquals(0, g.parentMainIndex(g.currentIndex()));
        assertEquals(-1, g.activeSubwaypointParentIndex());

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(0, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void retreatToPreviousTarget_fromHeldSubwaypointParentReturnsToThatParent() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.advancePast(0);
        assertEquals(3, g.currentIndex());
        assertEquals(0, g.activeSubwaypointParentIndex());

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(0, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void focusNewWaypoint_setsCurrentAndSuppressesProximity() {
        WaypointGroup g = route();
        g.markStaticWaypointReached(0);

        g.focusNewWaypoint(2);

        assertEquals(2, g.currentIndex());
        assertTrue(g.isProximitySuppressed(2));
        assertFalse(g.isStaticWaypointReached(0));
    }

    @Test
    void moveWaypointTo_focusesMovedWaypointAndClearsStaticReachState() {
        WaypointGroup g = route();
        g.markStaticWaypointReached(0);

        g.moveWaypointTo(2, 25, 71, -4);

        Waypoint moved = g.get(2);
        assertEquals(25, moved.x());
        assertEquals(71, moved.y());
        assertEquals(-4, moved.z());
        assertEquals(2, g.currentIndex());
        assertTrue(g.isProximitySuppressed(2));
        assertFalse(g.isStaticWaypointReached(0));
    }

    @Test
    void moveWaypointToPrecise_focusesMovedWaypointAndStoresSixteenths() {
        WaypointGroup g = route();
        g.markStaticWaypointReached(0);

        g.moveWaypointToPrecise(2, 403, 1144, -21);

        Waypoint moved = g.get(2);
        assertEquals(403, moved.preciseX());
        assertEquals(1144, moved.preciseY());
        assertEquals(-21, moved.preciseZ());
        assertEquals(25.1875, moved.centerX());
        assertEquals(71.5, moved.centerY());
        assertEquals(-1.3125, moved.centerZ());
        assertEquals(25, moved.x());
        assertEquals(71, moved.y());
        assertEquals(-2, moved.z());
        assertEquals(2, g.currentIndex());
        assertTrue(g.isProximitySuppressed(2));
        assertFalse(g.isStaticWaypointReached(0));
    }

    @Test
    void restartIfRouteCompleted_wraps_when_enabled() {
        WaypointGroup g = route();
        g.advancePast(3);
        assertTrue(g.isComplete());
        g.restartIfRouteCompleted(true);
        assertEquals(0, g.currentIndex());
        assertFalse(g.isComplete());
    }

    @Test
    void restartIfRouteCompleted_noop_when_disabled() {
        WaypointGroup g = route();
        g.advancePast(3);
        assertTrue(g.isComplete());
        g.restartIfRouteCompleted(false);
        assertTrue(g.isComplete());
    }

    @Test
    void effectiveRadius_prefersWaypointOverrideOverGroupDefault() {
        WaypointGroup g = route();
        g.setDefaultRadius(3.0);
        Waypoint wide = wp(0, 0, 0).withRadius(8.5);
        Waypoint thin = wp(0, 0, 0);
        assertEquals(8.5, g.effectiveRadius(wide));
        assertEquals(3.0, g.effectiveRadius(thin));
    }

    @Test
    void defaultRadiusIsAlwaysFiniteAndBounded() {
        WaypointGroup group = route();

        group.setDefaultRadius(Double.POSITIVE_INFINITY);
        assertEquals(Waypoint.DEFAULT_REACH_RADIUS, group.defaultRadius());

        group.setDefaultRadius(1_000_000.0);
        assertEquals(Waypoint.MAX_REACH_RADIUS, group.defaultRadius());
    }

    @Test
    void oversizedProximityScanFallsBackWithoutWalkingBillionsOfCells() {
        WaypointGroup group = route();
        List<Integer> visited = new ArrayList<>();

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> group.forEachNearbyIndex(0, 70, 0, Double.POSITIVE_INFINITY, index -> {
                    visited.add(index);
                    return true;
                }));

        assertEquals(List.of(0, 1, 2, 3).stream().sorted().toList(),
                visited.stream().sorted().toList());
    }

    @Test
    void gradientAuto_assignsDistinctColors() {
        WaypointGroup g = route();
        int c0 = g.get(0).color();
        int cN = g.get(g.size() - 1).color();
        assertNotEquals(c0, cN, "first and last waypoints should differ in AUTO gradient");
    }

        @Test
    void staticColorModeRecolorsExistingWaypointsAndFutureAdditions() {
        WaypointGroup g = route();

        g.setStaticColor(0x123456);
        g.setGradientMode(WaypointGroup.GradientMode.STATIC);

        for (Waypoint waypoint : g.waypoints()) {
            assertEquals(0x123456, waypoint.color());
        }

        g.add(Waypoint.at(40, 70, 0).withColor(0xABCDEF));

        assertEquals(0x123456, g.get(g.size() - 1).color());
    }

        @Test
    void manualColorModePreservesIndividualWaypointColors() {
        WaypointGroup g = WaypointGroup.create("manual", "hub");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(Waypoint.at(0, 70, 0).withColor(0x111111));
        g.add(Waypoint.at(1, 70, 0).withColor(0x222222));

        g.setStaticColor(0x00FF00);
        g.setGradientStartColor(0xAAAAAA);
        g.setGradientEndColor(0xBBBBBB);

        assertEquals(0x111111, g.get(0).color());
        assertEquals(0x222222, g.get(1).color());
    }

    @Test
    void colorModeCycleRestoresManualMainAndSubwaypointColors() {
        WaypointGroup group = WaypointGroup.create("subway colors", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(0, 70, 0).withColor(0xAA1100)
                .withFlags(Waypoint.FLAG_LOCKED_COLOR));
        group.add(Waypoint.at(1, 70, 0).withColor(0xFF9900)
                .withFlags(Waypoint.FLAG_LOCKED_COLOR));
        group.add(Waypoint.at(2, 70, 0).withColor(0x0033CC));
        assertTrue(group.toggleSubwaypoint(1));
        group.setGradientStartColor(0x123456);
        group.setGradientEndColor(0xABCDEF);

        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        assertEquals(0xAA1100, group.get(0).color());
        assertEquals(0xFF9900, group.get(1).color());

        group.setStaticColor(0x556677);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        assertTrue(group.waypoints().stream().allMatch(w -> w.color() == 0x556677));

        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        assertEquals(List.of(0xAA1100, 0xFF9900, 0x0033CC),
                group.waypoints().stream().map(Waypoint::color).toList());
        assertEquals(0x123456, group.gradientStartColor());
        assertEquals(0xABCDEF, group.gradientEndColor());

        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        assertEquals(0xAA1100, group.get(0).color());
        assertEquals(0xFF9900, group.get(1).color());
        assertEquals(0x123456, group.gradientStartColor());
        assertEquals(0xABCDEF, group.gradientEndColor());
    }

    @Test
    void gradientLockedColor_isNotOverwritten() {
        WaypointGroup g = WaypointGroup.create("x", "z");
        int lockedColor = 0xFF00FF;
        g.add(Waypoint.at(0, 0, 0).withColor(lockedColor).withFlags(Waypoint.FLAG_LOCKED_COLOR));
        g.add(Waypoint.at(1, 0, 0));
        g.add(Waypoint.at(2, 0, 0));
        assertEquals(lockedColor, g.get(0).color(), "locked waypoint keeps its color through gradient");
    }

    @Test
    void forEachVisibleIndex_staticMode_returnsEverythingInOrder() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visible,
                "STATIC should surface every index so shared routes are fully rendered");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_atStart_showsCurrentAndNext() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(0);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 0, 1 }, visible,
                "SEQUENCE at index 0 has no previous; should show current + next only");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_middle_showsPrevCurrentNext() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(2);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 1, 2, 3 }, visible,
                "SEQUENCE in the middle should show the prev/current/next triple");
    }

    @Test
    void forEachVisibleIndex_dungeonRoomSequence_showsAllRemainingWaypoints() {
        WaypointGroup group = WaypointGroup.create("Dungeon Room", "altar");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(1, 70, 0));
        group.add(Waypoint.at(2, 70, 0));
        group.add(Waypoint.at(3, 70, 0));

        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(group));

        group.setCurrentIndex(2);
        assertArrayEquals(new int[] { 2, 3 }, visibleIndices(group),
                "completed room waypoints stay hidden while every remaining one is surfaced");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_atEnd_showsPrevAndCurrent() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(3);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 2, 3 }, visible,
                "SEQUENCE at the last index has no next; should show prev + current only");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_afterCompletion_fallsBackToLastPoint() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(g.size()); // past the end -> isComplete()

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { g.size() - 1 }, visible,
                "completed SEQUENCE routes should still render the final point as a 'made it' marker");
    }

    @Test
    void restartIfRouteCompleted_keepsLastParentSubwaypointsVisibleWhileLoopingToStart() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(3);
        g.setCurrentIndex(2);

        g.advancePast(2);
        assertTrue(g.isComplete());
        g.restartIfRouteCompleted(true);

        assertEquals(0, g.currentIndex(), "tracer target should wrap back to the first main waypoint");
        assertEquals(2, g.activeSubwaypointParentIndex(),
                "the reached final parent should stay visually active after the wrap");
        assertArrayEquals(new int[] { 2, 3, 0, 1 }, visibleIndices(g),
                "final parent subwaypoints should remain visible while the route points back to #1");
    }

    @Test
    void forEachVisibleIndex_emptyGroup_returnsEmpty() {
        WaypointGroup g = WaypointGroup.create("empty", "dungeon_f7");
        assertEquals(0, visibleIndices(g).length);

        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        assertEquals(0, visibleIndices(g).length,
                "empty groups never render, regardless of load mode");
    }

    @Test
    void subwaypoint_toggle_rejectsFirstWaypointAndInfersNearestMainParent() {
        WaypointGroup g = route();

        assertFalse(g.toggleSubwaypoint(0), "the first row cannot become a child");
        assertTrue(g.toggleSubwaypoint(1));
        assertTrue(g.toggleSubwaypoint(2));

        assertTrue(g.isSubwaypoint(1));
        assertTrue(g.isSubwaypoint(2));
        assertEquals(0, g.parentMainIndex(1));
        assertEquals(0, g.parentMainIndex(2),
                "a child after another child still belongs to the nearest previous main waypoint");
        assertEquals("#1.1", g.displayIndexLabel(1));
        assertEquals("#1.2", g.displayIndexLabel(2));
    }

    @Test
    void subwaypoint_toggle_promotesChildBackToMain() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);

        assertTrue(g.toggleSubwaypoint(1));

        assertFalse(g.isSubwaypoint(1));
        assertEquals(4, g.mainWaypointCount());
    }

    @Test
    void subwaypoint_toggle_clearsSubwaypointOnlyStyleFlagsWhenPromoted() {
        WaypointGroup g = route();
        assertTrue(g.toggleSubwaypoint(1));

        Waypoint styled = g.get(1).withFlags(g.get(1).flags()
                | Waypoint.FLAG_SMALL_SUBWAYPOINT
                | Waypoint.FLAG_FILLED_SUBWAYPOINT
                | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED);
        g.set(1, styled);
        assertTrue(g.get(1).hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT));
        assertTrue(g.get(1).hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
        assertTrue(g.get(1).hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));

        assertTrue(g.toggleSubwaypoint(1));

        assertFalse(g.isSubwaypoint(1));
        assertFalse(g.get(1).hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT));
        assertFalse(g.get(1).hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
        assertFalse(g.get(1).hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));
    }

    @Test
    void forEachVisibleIndex_keepsFlaggedDungeonSubwaypointUntilNextMainIsReached() {
        WaypointGroup g = route();
        g.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.set(1, g.get(1).withFlags(g.get(1).flags()
                | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));

        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(g));

        g.advancePast(0);

        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(g));

        g.advancePast(3);

        assertArrayEquals(new int[] { 3 }, visibleIndices(g));
    }

    @Test
    void forEachVisibleIndex_oneWaypointHideAfterParentSubwaypointReappearsAfterReset() {
        WaypointGroup g = WaypointGroup.create("single", "test_zone");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.add(Waypoint.at(0, 0, 0));
        g.add(Waypoint.at(1, 0, 0)
                .withFlags(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));
        g.toggleSubwaypoint(1);

        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(g));

        g.advancePast(0);
        g.restartIfRouteCompleted(true);

        assertEquals(0, g.activeSubwaypointParentIndex());
        assertArrayEquals(new int[] { 0 }, visibleIndices(g));

        g.resetProgress();

        assertEquals(-1, g.activeSubwaypointParentIndex());
        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(g));
    }

    @Test
    void forEachVisibleIndex_sequenceMode_showsCurrentMainChildrenAndAdjacentMains() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.setCurrentIndex(0);

        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(g),
                "current parent should render its subwaypoints plus the next main waypoint");

        g.setCurrentIndex(3);

        assertArrayEquals(new int[] { 0, 3 }, visibleIndices(g),
                "children only render while their parent main waypoint is current");
    }

    @Test
    void forEachVisibleIndex_canKeepHideAfterParentSubwaypointsUntilNextMain() {
        WaypointGroup g = WaypointGroup.create("route", "hub");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.add(wp(0, 70, 0));
        g.add(wp(10, 70, 0));
        g.add(wp(20, 70, 0));
        g.add(wp(30, 70, 0));
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.set(1, g.get(1).withFlags(g.get(1).flags()
                | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));
        g.set(2, g.get(2).withFlags(g.get(2).flags()
                | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));
        g.advancePast(0);

        List<Integer> indices = new ArrayList<>();
        g.forEachVisibleIndex(true, indices::add);

        assertArrayEquals(new int[] { 0, 1, 2, 3 },
                indices.stream().mapToInt(Integer::intValue).toArray());
        assertArrayEquals(new int[] { 0, 3 }, visibleIndices(g));

        g.advancePast(3);
        indices.clear();
        g.forEachVisibleIndex(true, indices::add);
        assertArrayEquals(new int[] { 3 },
                indices.stream().mapToInt(Integer::intValue).toArray());
    }

    @Test
    void advancePast_skipsSubwaypointsAndKeepsCurrentOnMainWaypoints() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        g.advancePast(0);

        assertEquals(3, g.currentIndex());
        assertEquals(2, g.currentMainOrdinal());
    }

    @Test
    void advancePast_parentWithSubwaypoints_keepsParentChildrenVisibleWhileTargetingNextMain() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        g.advancePast(0);

        assertEquals(3, g.currentIndex(), "tracer target should advance to the next main waypoint");
        assertEquals(0, g.activeSubwaypointParentIndex(),
                "reached parent should stay visually active while targeting the next main");
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(g),
                "reached parent subwaypoints should remain visible until the next main is reached");

        g.advancePast(3);

        assertEquals(-1, g.activeSubwaypointParentIndex());
        assertArrayEquals(new int[] { 3 }, visibleIndices(g));
    }

    @Test
    void visibleIndices_afterSubwaypointParentAdvance_doNotShowNextParentsChildrenEarly() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        for (int i = 0; i < 5; i++) {
            g.add(Waypoint.at(i * 10, 0, 0));
        }
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(3);

        g.advancePast(0);

        assertEquals(2, g.currentIndex());
        assertArrayEquals(new int[] { 0, 1, 2, 4 }, visibleIndices(g),
                "the next parent's subwaypoints should wait until that parent is reached");

        g.advancePast(2);

        assertEquals(4, g.currentIndex());
        assertArrayEquals(new int[] { 2, 3, 4 }, visibleIndices(g),
                "the visual hold should move to the newly reached subwaypoint parent");
    }

    @Test
    void setCurrentIndex_onSubwaypointTargetsItsParent() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);

        g.setCurrentIndex(1);

        assertEquals(0, g.currentIndex());
        assertEquals(g.get(0), g.current());
    }

    @Test
    void remove_parentPromotesItsSubwaypointsToMainWaypoints() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        g.remove(0);

        assertEquals(3, g.size());
        assertFalse(g.isSubwaypoint(0));
        assertFalse(g.isSubwaypoint(1));
        assertEquals(3, g.mainWaypointCount());
    }

    @Test
    void moveBy_parentCarriesSubwaypointBlock() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        int movedTo = g.moveBy(0, 1);

        assertEquals(1, movedTo);
        assertEquals(30, g.get(0).x());
        assertEquals(0, g.get(1).x());
        assertTrue(g.isSubwaypoint(2));
        assertTrue(g.isSubwaypoint(3));
        assertEquals(1, g.parentMainIndex(2));
        assertEquals(1, g.parentMainIndex(3));
    }

    @Test
    void moveBy_subwaypointReordersWithinSiblingBlockOnly() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        int movedTo = g.moveBy(1, 1);

        assertEquals(2, movedTo);
        assertEquals(20, g.get(1).x());
        assertEquals(10, g.get(2).x());
        assertTrue(g.isSubwaypoint(1));
        assertTrue(g.isSubwaypoint(2));
        assertEquals(0, g.parentMainIndex(1));
        assertEquals(0, g.parentMainIndex(2));
    }

    @Test
    void loadMode_defaultsToSequence() {
        WaypointGroup g = WaypointGroup.create("r", "z");
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode(),
                "loaded routes default to SEQUENCE so the intended order is visible");
    }

    @Test
    void create_canSeedSkipAheadFromGlobalDefault() {
        WaypointGroup enabled = WaypointGroup.create("on", "hub", true);
        WaypointGroup disabled = WaypointGroup.create("off", "hub", false);

        assertTrue(enabled.skipAheadEnabled());
        assertFalse(disabled.skipAheadEnabled());
    }

    @Test
    void manager_getOrCreateActiveGroupSeedsSkipAheadDefault() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup route = manager.getOrCreateActiveGroup(false);

        assertFalse(route.skipAheadEnabled());
    }

    @Test
    void manager_addTempWaypointCreatesStaticLeaveScopedTempBucketForCurrentZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        WaypointGroup bucket = manager.addTempWaypoint(100, 64, -200);

        assertEquals("temp::dungeon_f7", bucket.id());
        assertEquals("Temporary", bucket.name());
        assertTrue(bucket.temp());
        assertEquals(WaypointGroup.LoadMode.STATIC, bucket.loadMode());
        assertEquals(1, bucket.size());
        assertEquals(bucket, manager.firstActiveGroup());

        Waypoint waypoint = bucket.get(0);
        assertEquals(100, waypoint.x());
        assertEquals(64, waypoint.y());
        assertEquals(-200, waypoint.z());
        assertTrue(waypoint.isTemp());
        assertEquals(Waypoint.TEMP_UNTIL_LEAVE, waypoint.tempMode());
    }

    @Test
    void manager_addTempWaypointCanUseConfiguredTimedExpiry() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        WaypointGroup bucket = manager.addTempWaypoint(
                100, 64, -200, "", Waypoint.TEMP_TIME, 123_456L);

        Waypoint waypoint = bucket.get(0);
        assertEquals(Waypoint.TEMP_TIME, waypoint.tempMode());
        assertEquals(123_456L, waypoint.expiresAtMillis());
    }

    @Test
    void manager_getOrCreateActiveGroupDoesNotReuseTempBucket() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup bucket = manager.addTempWaypoint(1, 70, 2);

        WaypointGroup route = manager.getOrCreateActiveGroup();

        assertNotSame(bucket, route);
        assertFalse(route.temp());
        assertEquals("hub", route.zoneId());
    }

    @Test
    void manager_chatTempWaypointIncludesSenderInGroupAndWaypointName() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup bucket = manager.addTempWaypoint(1, 2, 3, "\u00A7eFrom \u00A7dBabbur");

        assertEquals("Temporary", bucket.name());
        assertEquals(1, bucket.size());
        assertEquals("\u00A7eFrom \u00A7dBabbur", bucket.get(0).name());
        assertTrue(bucket.get(0).isTemp());
    }

    @Test
    void manager_addTempWaypointReusesBucketAndInvalidatesActiveCache() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        WaypointGroup firstBucket = manager.addTempWaypoint(1, 70, 2);
        assertEquals(1, manager.activeGroups().size());

        WaypointGroup secondBucket = manager.addTempWaypoint(3, 71, 4);

        assertSame(firstBucket, secondBucket);
        assertEquals(2, manager.activeGroups().get(0).size(),
                "second temp waypoint should be visible through refreshed active cache");
    }

    @Test
    void manager_tempWaypointFocusHidesOtherActiveGroupsUntilCleared() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route();
        route.setZoneId("hub");
        manager.add(route);

        WaypointGroup bucket = manager.addTempWaypoint(1, 70, 2);
        bucket.add(Waypoint.at(3, 71, 4).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        manager.focusTempWaypoint(bucket, 1);

        assertEquals(List.of(bucket), manager.activeGroups());
        assertArrayEquals(new int[] { 1 }, visibleIndices(bucket),
                "focus should render only the newly-added temp waypoint");
        assertEquals(bucket.get(1), bucket.current(),
                "the tracer target follows the focused temporary waypoint");

        manager.clearTempWaypointFocus();

        assertEquals(List.of(route, bucket), manager.activeGroups());
        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(bucket));
    }

    @Test
    void manager_tempWaypointFocusClearsWhenFocusedTempIsRemoved() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route();
        route.setZoneId("hub");
        manager.add(route);

        WaypointGroup bucket = manager.addTempWaypoint(1, 70, 2);
        manager.focusTempWaypoint(bucket, 0);
        bucket.remove(0);

        assertEquals(List.of(route, bucket), manager.activeGroups());
    }

    @Test
    void exportSnapshotCarriesEverythingTheExportersRead() {
        WaypointGroup group = WaypointGroup.create("Shared route", "dungeon_f7");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setDefaultRadius(4.5);
        group.setSkipAheadEnabled(false);
        group.setStaticColor(0x123456);
        group.setGradientStartColor(0xAABBCC);
        group.setGradientEndColor(0xDDEEFF);
        group.add(wp(1, 70, 1).withName("first"));
        group.add(wp(2, 70, 2).withName("second"));

        WaypointGroup snapshot = group.exportSnapshot();

        assertEquals(group.id(), snapshot.id());
        assertEquals(group.name(), snapshot.name());
        assertEquals(group.zoneId(), snapshot.zoneId());
        assertEquals(group.loadMode(), snapshot.loadMode());
        assertEquals(group.routeKind(), snapshot.routeKind());
        assertEquals(group.gradientMode(), snapshot.gradientMode());
        assertEquals(group.defaultRadius(), snapshot.defaultRadius());
        assertEquals(group.skipAheadEnabled(), snapshot.skipAheadEnabled());
        assertEquals(group.staticColor(), snapshot.staticColor());
        assertEquals(group.gradientStartColor(), snapshot.gradientStartColor());
        assertEquals(group.gradientEndColor(), snapshot.gradientEndColor());
        assertEquals(group.waypoints(), snapshot.waypoints());
    }

    @Test
    void routeKindDefaultsToRegularAndCanBeSetExplicitly() {
        WaypointGroup group = WaypointGroup.create("Route", "admin");

        assertEquals(WaypointGroup.RouteKind.REGULAR, group.routeKind());

        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);

        assertEquals(WaypointGroup.RouteKind.DUNGEON, group.routeKind());
    }

    @Test
    void exportSnapshotDoesNotRepaintWaypoints() {
        // Building the copy through the colour setters would re-run the gradient
        // and change what gets exported. MANUAL keeps per-waypoint colours, so a
        // repaint would be visible here.
        WaypointGroup group = WaypointGroup.create("Manual colours", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(wp(0, 70, 0).withColor(0xFF0000));
        group.add(wp(1, 70, 1).withColor(0x0000FF));

        WaypointGroup snapshot = group.exportSnapshot();

        assertEquals(0xFF0000, snapshot.get(0).color());
        assertEquals(0x0000FF, snapshot.get(1).color());
    }

    @Test
    void exportSnapshotIsDetachedFromLaterEdits() {
        // The encode runs on a worker thread, so the copy must not observe
        // anything the client thread does to the live route afterwards.
        WaypointGroup group = WaypointGroup.create("Live route", "hub");
        group.add(wp(0, 70, 0));

        WaypointGroup snapshot = group.exportSnapshot();
        group.add(wp(5, 70, 5));
        group.setName("renamed");

        assertEquals(1, snapshot.size());
        assertEquals(2, group.size());
        assertEquals("Live route", snapshot.name());
    }

    @Test
    void disablingCurrentWaypointSkipsItButKeepsItInTheEditorList() {
        WaypointGroup group = route();

        assertTrue(group.toggleWaypointDisabled(0));
        assertEquals(4, group.size());
        assertTrue(group.get(0).isDisabled());
        assertEquals(1, group.currentIndex());
        assertArrayEquals(new int[]{1, 2}, visibleIndices(group));

        assertTrue(group.toggleWaypointDisabled(0));
        assertFalse(group.get(0).isDisabled());
        assertEquals(1, group.currentIndex(), "re-enabling does not rewind route progress");
    }

    @Test
    void disablingEveryWaypointCompletesTheRouteWithoutAHiddenTarget() {
        WaypointGroup group = route();

        for (int i = 0; i < group.size(); i++) {
            assertTrue(group.setWaypointDisabled(i, true));
        }

        assertTrue(group.isComplete());
        assertNull(group.current());
        assertArrayEquals(new int[0], visibleIndices(group));
        assertEquals(0, group.enabledMainWaypointCount());
    }

    private static int[] visibleIndices(WaypointGroup group) {
        List<Integer> indices = new ArrayList<>();
        group.forEachVisibleIndex(indices::add);
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }
}
