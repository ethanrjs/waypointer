package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.WaypointRenderer;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Five real, spaced sample positions; changing the inspected role advances the sequence around point three. */
final class WaypointInspectorSequence {
    static final int EDITED_INDEX = 2;
    static final int STATIC_TAB = 3;
    static final int TAB_COUNT = 4;
    static final List<Vec3> POSITIONS = List.of(new Vec3(-12, 0, 2), new Vec3(-6, 0, 2),
            Vec3.ZERO, new Vec3(6, 0, 2), new Vec3(12, 0, 2));

    private WaypointInspectorSequence() {}
    static int currentIndex(int roleTab) { return switch (roleTab) { case 1 -> 1; case 2 -> 3; default -> 2; }; }
    static WaypointRenderer.State state(int index, int current) {
        return index < current ? WaypointRenderer.State.COMPLETED
                : index == current ? WaypointRenderer.State.CURRENT : WaypointRenderer.State.UPCOMING;
    }
    static int initialTab(WaypointGroup original) { return original != null && original.loadMode() == WaypointGroup.LoadMode.STATIC ? STATIC_TAB : 0; }
    static int currentIndex(Waypoint edited, int roleTab) {
        return edited.isSubwaypoint() && roleTab == 1 ? 0 : currentIndex(roleTab);
    }
    static WaypointRenderer.State state(WaypointGroup group, int index) {
        return WaypointRenderer.stateFor(group, index, group.currentIndex());
    }
    static WaypointerConfig configFor(int index, WaypointerConfig edited, WaypointerConfig context) {
        return index == EDITED_INDEX || context == null ? edited : context;
    }
    static WaypointGroup group(Waypoint edited, WaypointGroup original, int roleTab) {
        var route = new WaypointGroup("appearance-sequence", original == null ? "Route" : original.name(), "");
        route.setLoadMode(roleTab == STATIC_TAB ? WaypointGroup.LoadMode.STATIC : WaypointGroup.LoadMode.SEQUENCE);
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int i = 0; i < POSITIONS.size(); i++) {
            Vec3 p = POSITIONS.get(i);
            route.add(i == EDITED_INDEX ? edited : new Waypoint(edited.x() + (int) p.x, edited.y() + (int) p.y, edited.z() + (int) p.z,
                    i == EDITED_INDEX ? edited.name() : "#" + (i + 1), edited.color(), 0, 0));
        }
        route.setCurrentIndex(currentIndex(edited, roleTab));
        return route;
    }
    static boolean[] visible(WaypointGroup group, WaypointerConfig config) {
        boolean[] visible = new boolean[POSITIONS.size()];
        group.forEachVisibleIndex(config.sequenceVisibility(), config.keepSubwaypointsVisibleUntilNextWaypoint(), i -> visible[i] = true);
        return visible;
    }
    static double horizontalCenter(WaypointInspectorProjection camera, boolean[] visible) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < POSITIONS.size(); i++) if (visible[i]) {
            Vec3 p = POSITIONS.get(i);
            var projected = camera.project(p.x, p.y, p.z);
            if (projected.depth() <= .05) continue;
            min = Math.min(min, projected.x()); max = Math.max(max, projected.x());
        }
        return Double.isFinite(min) ? (min + max) * .5 : 0;
    }
}
