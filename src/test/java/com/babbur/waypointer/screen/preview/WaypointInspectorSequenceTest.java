package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.render.WaypointRenderer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointInspectorSequenceTest {
    @Test void activeSubwaypointAndItsParentUseTheLiveCurrentState() {
        var point = Waypoint.at(10, 70, 10).withFlags(Waypoint.FLAG_SUBWAYPOINT);
        var group = WaypointInspectorSequence.group(point, null, 0);
        assertEquals(WaypointRenderer.State.CURRENT, WaypointInspectorSequence.state(group, WaypointInspectorSequence.EDITED_INDEX));
        assertEquals(WaypointRenderer.State.CURRENT, WaypointInspectorSequence.state(group, group.parentMainIndex(WaypointInspectorSequence.EDITED_INDEX)));
    }
    @Test void contextualCoordinatesStayInTheEditedWaypointsWorld() {
        var group = WaypointInspectorSequence.group(Waypoint.at(10, 70, 10), null, 0);
        assertEquals(4, group.get(1).x()); assertEquals(70, group.get(1).y()); assertEquals(12, group.get(1).z());
        assertEquals(16, group.get(3).x()); assertEquals(70, group.get(3).y()); assertEquals(12, group.get(3).z());
    }
    @Test void everyRoleHasRealPreviousCurrentNextAndASubstantialCurrentTracer() {
        WaypointerConfig config = new WaypointerConfig();
        for (int tab = 0; tab < 3; tab++) {
            var group = WaypointInspectorSequence.group(Waypoint.at(0, 0, 0), null, tab);
            int current = WaypointInspectorSequence.currentIndex(tab);
            assertEquals(current, group.currentIndex());
            assertEquals(WaypointRenderer.State.CURRENT, WaypointInspectorSequence.state(current, current));
            assertEquals(WaypointRenderer.State.COMPLETED, WaypointInspectorSequence.state(current - 1, current));
            assertEquals(WaypointRenderer.State.UPCOMING, WaypointInspectorSequence.state(current + 1, current));
            boolean[] visible = WaypointInspectorSequence.visible(group, config);
            assertTrue(visible[current - 1]); assertTrue(visible[current]); assertTrue(visible[current + 1]);
            assertTrue(visible[WaypointInspectorSequence.EDITED_INDEX]);
            for (int i = 1; i < 5; i++) assertTrue(WaypointInspectorSequence.POSITIONS.get(i)
                    .distanceTo(WaypointInspectorSequence.POSITIONS.get(i - 1)) >= 6);
            var base = new WaypointInspectorProjection(1.8, 0, Math.toRadians(-6), 70, 75);
            var camera = new WaypointInspectorProjection(1.8, 0, Math.toRadians(-6), 70, 75,
                    58.95 - WaypointInspectorSequence.horizontalCenter(base, visible), 7.5);
            var target = WaypointInspectorSequence.POSITIONS.get(current);
            var projected = camera.project(target.x, target.y, target.z);
            assertTrue(Math.hypot(projected.x(), projected.y()) >= 20, "current tracer remains visible on every role tab");
            for (int i = 0; i < 5; i++) if (visible[i]) {
                var point = WaypointInspectorSequence.POSITIONS.get(i);
                assertTrue(Math.abs(camera.project(point.x, point.y, point.z).x()) < 180, "context fits compact viewport");
                if (i > 0 && visible[i - 1]) {
                    var previous = WaypointInspectorSequence.POSITIONS.get(i - 1);
                    assertTrue(Math.abs(camera.project(point.x, point.y, point.z).x()
                            - camera.project(previous.x, previous.y, previous.z).x()) >= 30,
                            "adjacent context labels remain separated in the compact camera");
                }
            }
        }
    }
    @Test void staticPreviewKeepsAllEnabledPointsCurrentAndPreservesEditedShape() {
        var source = com.babbur.waypointer.core.WaypointGroup.create("Static", "hub");
        source.setLoadMode(com.babbur.waypointer.core.WaypointGroup.LoadMode.STATIC);
        assertEquals(WaypointInspectorSequence.STATIC_TAB, WaypointInspectorSequence.initialTab(source));
        assertEquals(0, WaypointInspectorSequence.initialTab(null));
        var point = Waypoint.at(10, 70, 10).withFlags(Waypoint.FLAG_DISABLED | Waypoint.FLAG_SMALL_SUBWAYPOINT);
        var sample = WaypointInspectorSequence.group(point, source, WaypointInspectorSequence.STATIC_TAB);
        assertEquals(com.babbur.waypointer.core.WaypointGroup.LoadMode.STATIC, sample.loadMode());
        assertEquals(point, sample.get(WaypointInspectorSequence.EDITED_INDEX));
        boolean[] visible = WaypointInspectorSequence.visible(sample, new WaypointerConfig());
        for (int i = 0; i < sample.size(); i++) {
            assertEquals(WaypointRenderer.State.CURRENT, WaypointInspectorSequence.state(sample, i));
            assertEquals(i != WaypointInspectorSequence.EDITED_INDEX, visible[i]);
        }
    }
    @Test void pointDraftOnlyChangesRepresentativeAppearance() {
        var draft = new WaypointerConfig(); draft.setLabelOpacity(.25);
        var inherited = new WaypointerConfig(); inherited.setLabelOpacity(.75);
        for (int i = 0; i < 5; i++) {
            assertSame(i == WaypointInspectorSequence.EDITED_INDEX ? draft : inherited,
                    WaypointInspectorSequence.configFor(i, draft, inherited));
            assertSame(draft, WaypointInspectorSequence.configFor(i, draft, null));
        }
    }

}
