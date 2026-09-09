package com.babbur.waypointer.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointLabelAppearanceTest {
    @Test void unnamedDungeonDetailsAreNotTreatedAsTheNameRow() {
        var config = new com.babbur.waypointer.config.WaypointerConfig();
        config.setShowWaypointNames(true);
        var group = com.babbur.waypointer.core.WaypointGroup.create("Room", "dungeon");
        group.setRouteKind(com.babbur.waypointer.core.WaypointGroup.RouteKind.DUNGEON);
        var point = com.babbur.waypointer.core.Waypoint.at(1, 64, 3);
        assertFalse(WaypointRenderer.hasNameRow(config, group, point));
        assertTrue(WaypointRenderer.hasNameRow(config, group, point.withName("Secret")));
        group.setRouteKind(com.babbur.waypointer.core.WaypointGroup.RouteKind.REGULAR);
        assertTrue(WaypointRenderer.hasNameRow(config, group, point));
        config.setShowWaypointNames(false);
        assertFalse(WaypointRenderer.hasNameRow(config, group, point));
    }

    @Test void touchingLabelsDoNotOverlapButIntersectingLabelsDo() {
        assertFalse(WaypointLabelAppearance.overlaps(0, 0, 10, 10, 10, 0, 20, 10));
        assertTrue(WaypointLabelAppearance.overlaps(0, 0, 10, 10, 9, 9, 20, 20));
        assertFalse(WaypointLabelAppearance.overlaps(0, 0, 10, 10, 0, 11, 10, 20));
    }

    @Test void optionalLabelDetailsDisappearTogetherWithoutHidingTheName() {
        var config = new com.babbur.waypointer.config.WaypointerConfig();
        config.setShowWaypointNames(true);
        config.setShowWaypointDistances(true);
        config.setShowRouteProgress(true);
        config.setLabelShowRouteName(true);
        config.setLabelShowCoordinates(true);
        var group = com.babbur.waypointer.core.WaypointGroup.create("Route", "hub");
        var waypoint = new com.babbur.waypointer.core.Waypoint(1, 64, 3, "Home", 0xFFFFFF, 0, 0);
        group.add(waypoint);
        assertEquals(java.util.List.of("Home"), WaypointRenderer.labelRows(config, group, 0, waypoint, false, "current", 20, "50.0%"));
        assertEquals(java.util.List.of("Home", "Route", "50.0%", "20m", "1, 64, 3"),
                WaypointRenderer.labelRows(config, group, 0, waypoint, true, "current", 20, "50.0%"));
        config.setShowWaypointNames(false);
        assertTrue(WaypointRenderer.labelRows(config, group, 0, waypoint, false, "current", 20, "50.0%").isEmpty());
        assertEquals(0, WaypointLabelAppearance.labelWidth(0, false, false, 1));
    }

    @Test void previewAndHudShareFocusDetailCullingAndPriorityBoundaries() {
        var cfg = new com.babbur.waypointer.config.WaypointerConfig();
        cfg.setLabelDetailOnDemand(true);
        cfg.setLabelDetailDistance(16);
        assertTrue(WaypointLabelAppearance.focused(116, 84, 100, 100));
        assertFalse(WaypointLabelAppearance.focused(116.01, 100, 100, 100));
        assertTrue(WaypointLabelAppearance.showDetail(cfg, 256, false));
        assertFalse(WaypointLabelAppearance.showDetail(cfg, 256.01, false));
        assertTrue(WaypointLabelAppearance.showDetail(cfg, 10000, true));
        assertTrue(WaypointLabelAppearance.nearScreen(-64, 0, 300, 200));
        assertFalse(WaypointLabelAppearance.nearScreen(-64.01, 0, 300, 200));
        assertFalse(WaypointLabelAppearance.nearScreen(100, 264.01, 300, 200));
        assertTrue(WaypointLabelAppearance.priority(true, false) < WaypointLabelAppearance.priority(false, true));
        assertTrue(WaypointLabelAppearance.priority(true, true) < WaypointLabelAppearance.priority(true, false));
        assertEquals(18, WaypointLabelAppearance.labelWidth(0, false, true, 2));
        assertEquals(48, WaypointLabelAppearance.labelWidth(20, true, false, 2));
    }

    @Test void nextRoleSkipsSubwaypointsButSubwaypointsKeepTheirBadge() {
        var group = com.babbur.waypointer.core.WaypointGroup.create("Route", "hub");
        var main = new com.babbur.waypointer.core.Waypoint(1, 64, 3, "", 0xFFFFFF, 0, 0);
        var child = new com.babbur.waypointer.core.Waypoint(2, 64, 3, "", 0xFFFFFF,
                com.babbur.waypointer.core.Waypoint.FLAG_SUBWAYPOINT, 0);
        group.add(main); group.add(child); group.add(main); group.add(main);
        group.setCurrentIndex(0);
        assertEquals("sub", WaypointRenderer.labelRole(group, 1, child, WaypointRenderer.State.UPCOMING));
        assertEquals("next", WaypointRenderer.labelRole(group, 2, main, WaypointRenderer.State.UPCOMING));
        assertEquals("upcoming", WaypointRenderer.labelRole(group, 3, main, WaypointRenderer.State.UPCOMING));
        assertEquals("completed", WaypointRenderer.labelRole(group, 0, main, WaypointRenderer.State.COMPLETED));
    }

    @Test void rejectedLabelsDoNotBlockLaterLabelsAndAcceptedOrderStaysStable() {
        var cfg = new com.babbur.waypointer.config.WaypointerConfig();
        cfg.setPreventLabelOverlap(true);
        var first = candidate(cfg, 0, 10);
        var rejected = candidate(cfg, 5, 15);
        var touchesFirstButOverlapsRejected = candidate(cfg, 10, 20);
        var last = candidate(cfg, 30, 40);
        var drawn = new java.util.ArrayList<WaypointRenderer.LabelCandidate>();
        assertTrue(WaypointRenderer.acceptLabel(first, drawn));
        assertFalse(WaypointRenderer.acceptLabel(rejected, drawn));
        assertTrue(WaypointRenderer.acceptLabel(touchesFirstButOverlapsRejected, drawn));
        assertTrue(WaypointRenderer.acceptLabel(last, drawn));
        assertEquals(java.util.List.of(first, touchesFirstButOverlapsRejected, last), drawn);
    }

    private static WaypointRenderer.LabelCandidate candidate(com.babbur.waypointer.config.WaypointerConfig config,
                                                               double left, double right) {
        var label = new WaypointRenderer.LabelCandidate();
        label.appearance = config;
        label.left = left; label.right = right;
        label.top = 0; label.bottom = 10;
        return label;
    }

    @Test void repeatedDistanceAndOrdinaryIndexLabelsReuseBoundedCacheEntries() {
        assertSame(WaypointRenderer.distanceLabel(24), WaypointRenderer.distanceLabel(24));
        assertEquals("4096m", WaypointRenderer.distanceLabel(4096));
        assertEquals("-1m", WaypointRenderer.distanceLabel(-1));
        var group = com.babbur.waypointer.core.WaypointGroup.create("Route", "hub");
        var point = new com.babbur.waypointer.core.Waypoint(0, 64, 0, "", 0xFFFFFF, 0, 0);
        group.add(point);
        assertSame(WaypointRenderer.labelFor(group, 0, point, false), WaypointRenderer.labelFor(group, 0, point, false));
    }

    @Test void IconsHaveDistinctCrispGeometryAndNoneIsEmpty() {
        assertFalse(WaypointLabelAppearance.iconPixel("NONE", 0, 0));
        assertTrue(WaypointLabelAppearance.iconPixel("STAR", 0, 0));
        assertFalse(WaypointLabelAppearance.iconPixel("DIAMOND", 0, 0));
        assertTrue(WaypointLabelAppearance.iconPixel("DIAMOND", 0, 4));
        assertTrue(WaypointLabelAppearance.iconPixel("SQUARE", 4, 4));
        assertFalse(WaypointLabelAppearance.iconPixel("CIRCLE", 4, 4));
        assertTrue(WaypointLabelAppearance.iconPixel("CIRCLE", 4, 0));
    }
}
