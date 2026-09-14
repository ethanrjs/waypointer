package com.babbur.waypointer.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointLabelAppearanceTest {
    @Test void nearestLabelPaintsLastWithoutChangingBudgetOrOverlapSelection() {
        var config = new com.babbur.waypointer.config.WaypointerConfig();
        config.setPreventLabelOverlap(true);
        var near = candidate(config, 0, 10);
        near.distanceSquared = 159.31 * 159.31;
        var far = candidate(config, 0, 10);
        far.distanceSquared = 262.17 * 262.17;
        var candidates = java.util.List.of(near, far);
        var selected = new java.util.ArrayList<WaypointRenderer.LabelCandidate>();
        WaypointRenderer.selectLabelsForDrawing(candidates, 2, 2, false, selected);
        assertEquals(java.util.List.of(far, near), selected);
        WaypointRenderer.selectLabelsForDrawing(candidates, 2, 1, false, selected);
        assertEquals(java.util.List.of(near), selected);
        WaypointRenderer.selectLabelsForDrawing(candidates, 2, 2, true, selected);
        assertEquals(java.util.List.of(near), selected);
        // Existing focused/current-route priority still controls collision acceptance.
        WaypointRenderer.selectLabelsForDrawing(java.util.List.of(far, near), 2, 2, true, selected);
        assertEquals(java.util.List.of(far), selected);
        far.left = 11;
        far.right = 21;
        WaypointRenderer.selectLabelsForDrawing(candidates, 2, 2, true, selected);
        assertEquals(java.util.List.of(far, near), selected);
    }

    @Test void compassTargetKeepsOneDescriptiveLabelWithoutHidingUnrelatedWaypoints() throws Exception {
        var point = com.babbur.waypointer.core.Waypoint.at(541, 122, 699);
        var markTarget = com.babbur.waypointer.crystal.CompassMarkerState.class
                .getDeclaredMethod("markTarget", com.babbur.waypointer.core.Waypoint.class);
        markTarget.setAccessible(true);
        markTarget.invoke(null, point);
        var target = new WaypointRenderer.LabelCandidate();
        target.group = com.babbur.waypointer.core.WaypointGroup.create("Temporary", "crystal_hollows");
        target.waypoint = point;
        target.focused = true;
        var generated = new WaypointRenderer.LabelCandidate();
        generated.group = new com.babbur.waypointer.core.WaypointGroup(
                "crystal_hollows:structure:wishing_target", "Compass target", "crystal_hollows");
        generated.group.setRuntimeOnly(true);
        generated.waypoint = point.withName("Compass target: King Yolkar / Lost Precursor City");
        generated.rows.add(generated.waypoint.name());
        var unrelated = new WaypointRenderer.LabelCandidate();
        unrelated.group = com.babbur.waypointer.core.WaypointGroup.create("User route", "crystal_hollows");
        unrelated.waypoint = generated.waypoint;
        var labels = new java.util.ArrayList<>(java.util.List.of(target, unrelated));
        assertEquals(2, WaypointRenderer.retainDistinctCompassLabels(labels, 2));
        labels.add(generated);
        generated.waypoint = generated.waypoint.withPos(542, 122, 699);
        assertEquals(3, WaypointRenderer.retainDistinctCompassLabels(labels, 3));
        generated.waypoint = generated.waypoint.withPos(541, 122, 699);
        assertEquals(2, WaypointRenderer.retainDistinctCompassLabels(labels, 3));
        assertTrue(labels.subList(0, 2).contains(generated));
        assertTrue(labels.subList(0, 2).contains(unrelated));
        assertTrue(generated.focused);
        assertEquals(java.util.List.of("Compass target: King Yolkar / Lost Precursor City"), generated.rows);
    }

    @Test void sequencePositionIndexIsReusableAndPreservesEarlierRoutes() {
        var positions = WaypointRenderer.newLabelPositionIndex();
        var group = com.babbur.waypointer.core.WaypointGroup.create("Route", "hub");
        group.setCurrentIndex(0);
        var labels = new java.util.ArrayList<WaypointRenderer.LabelCandidate>();
        var prefix = new WaypointRenderer.LabelCandidate();
        labels.add(prefix);
        for (int i = 0; i < 2048; i++) {
            var label = new WaypointRenderer.LabelCandidate();
            label.group = group;
            label.index = i;
            label.waypoint = com.babbur.waypointer.core.Waypoint.at(i % 1024, 64, 0);
            labels.add(label);
        }
        int count = WaypointRenderer.retainDistinctSequenceLabels(labels, 1, labels.size(), positions);
        assertEquals(1025, count);
        assertSame(prefix, labels.getFirst());
        for (int i = 1; i < count; i++) assertEquals(i - 1, labels.get(i).index);
        assertTrue(positions.isEmpty());
        assertEquals(count, WaypointRenderer.retainDistinctSequenceLabels(labels, 1, count, positions));
    }

    @Test void coincidentSequenceContextKeepsCurrentCustomLabelAndItsDetailRows() {
        var group = com.babbur.waypointer.core.WaypointGroup.create("MEOW", "dwarven_mines");
        var point = com.babbur.waypointer.core.Waypoint.at(-36, 199, -123);
        group.add(point); group.add(point.withName("{distance} {index} {total} {route}")); group.add(point);
        group.setCurrentIndex(1);
        var labels = new java.util.ArrayList<WaypointRenderer.LabelCandidate>();
        var positions = WaypointRenderer.newLabelPositionIndex();
        for (int index : new int[]{0, 2, 1}) {
            var label = new WaypointRenderer.LabelCandidate();
            label.group = group; label.waypoint = group.get(index); label.index = index;
            labels.add(label);
        }
        var current = labels.get(2);
        current.rows.add("10m 2 3 MEOW");
        current.rows.add("0.0%");
        current.rows.add("10m");
        assertEquals(1, WaypointRenderer.retainDistinctSequenceLabels(labels, 0, 3, positions));
        assertSame(current, labels.getFirst());
        assertEquals(java.util.List.of("10m 2 3 MEOW", "0.0%", "10m"), current.rows);

        var distinct = new WaypointRenderer.LabelCandidate();
        distinct.group = group; distinct.waypoint = com.babbur.waypointer.core.Waypoint.at(-35, 199, -123);
        distinct.index = 2;
        labels.set(1, distinct);
        assertEquals(2, WaypointRenderer.retainDistinctSequenceLabels(labels, 0, 2, positions));
        distinct.waypoint = point.withPreciseSixteenths(point.preciseX() + 1, point.preciseY(), point.preciseZ());
        assertEquals(2, WaypointRenderer.retainDistinctSequenceLabels(labels, 0, 2, positions));

        var otherRoute = new WaypointRenderer.LabelCandidate();
        otherRoute.group = com.babbur.waypointer.core.WaypointGroup.create("Other", "dwarven_mines");
        otherRoute.waypoint = point;
        labels.set(2, otherRoute);
        assertEquals(3, WaypointRenderer.retainDistinctSequenceLabels(labels, 0, 3, positions));

        var farther = new WaypointRenderer.LabelCandidate();
        farther.group = group; farther.waypoint = point; farther.index = 4;
        var nearer = new WaypointRenderer.LabelCandidate();
        nearer.group = group; nearer.waypoint = point; nearer.index = 2;
        labels.set(0, farther); labels.set(1, nearer);
        assertEquals(1, WaypointRenderer.retainDistinctSequenceLabels(labels, 0, 2, positions));
        assertSame(nearer, labels.getFirst());
    }

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
