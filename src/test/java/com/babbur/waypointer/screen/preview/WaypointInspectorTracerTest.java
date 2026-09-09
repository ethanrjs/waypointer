package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.WaypointRenderer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WaypointInspectorTracerTest {
    @Test void rearTargetsUseLiveBottomFallbackAndFrontTargetsRetainPan() {
        var camera = new WaypointInspectorProjection(8, 0, 0, 90, 200, 25, 10);
        double[] result = new double[2];
        assertTrue(WaypointInspectorTracer.project(camera, new Vec3(0, 0, -9), 320, 200, result));
        assertEquals(160, result[0]); assertEquals(200, result[1]);
        assertTrue(WaypointInspectorTracer.project(camera, new Vec3(.000001, 0, -9), 320, 200, result));
        assertEquals(160, result[0]); assertEquals(200, result[1]);
        assertFalse(WaypointInspectorTracer.project(camera, Vec3.ZERO, 320, 200, result));
        assertEquals(185, result[0]); assertEquals(110, result[1]);
        assertTrue(WaypointInspectorTracer.project(camera, new Vec3(100, 0, 0), 320, 200, result));
        assertEquals(320, result[0]);
    }

    @Test void staticTracerChoosesNearestEligiblePointAndHonorsSuppression() {
        var config = new WaypointerConfig();
        config.setShowTracer(true); config.setHideTracerOnStaticRoutes(false);
        var group = new WaypointGroup("preview", "Route", "");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.add(Waypoint.at(0, 0, 0)); group.add(Waypoint.at(0, 0, 20));
        var near = sample(config, group, 0, Vec3.ZERO);
        var far = sample(config, group, 1, new Vec3(0, 0, 20));
        assertSame(near, WaypointInspectorTracer.target(List.of(far, near), group, config, null, new Vec3(0, -1.62, -8)));
        // Camera is eight blocks away, but the player feet are farther from the logical waypoint center.
        config.setRouteMinRenderDistance(8.1);
        assertSame(near, WaypointInspectorTracer.target(List.of(far, near), group, config, null, new Vec3(0, -1.62, -8)));
        config.setRouteMinRenderDistance(10);
        assertSame(far, WaypointInspectorTracer.target(List.of(far, near), group, config, null, new Vec3(0, -1.62, -8)));
        config.setHideTracerOnStaticRoutes(true);
        assertNull(WaypointInspectorTracer.target(List.of(far, near), group, config, null, new Vec3(0, -1.62, -8)));
    }

    private static WaypointInspectorSample sample(WaypointerConfig config, WaypointGroup group, int index, Vec3 position) {
        return new WaypointInspectorSample(config, group, group.get(index), null, WaypointRenderer.State.CURRENT,
                0xFFFFFF, new WaypointInspectorProjection(8, 0, 0, 90, 200), 0, null,
                new Vec3(group.get(index).centerX(), group.get(index).centerY(), group.get(index).centerZ()),
                -64, 320, position, index, true);
    }
}
