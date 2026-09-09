package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.WaypointRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointInspectorSampleTest {
    @Test void routeDistanceUsesPointCenterInsteadOfCustomShapeCenter() {
        var config = new WaypointerConfig();
        config.setHideWaypointsNearPlayer(false);
        config.setRouteMaxRenderDistance(10);
        var sample = sample(config, Waypoint.at(0, 0, 0), true, new Vec3(.5, .5, 5.5));
        assertEquals(new Vec3(0, 0, -5), sample.pointPosition());
        assertTrue(sample.visible(), "point is five blocks away although visual shape center is ten");
        config.setRouteMaxRenderDistance(4);
        assertFalse(sample.visible());
    }

    @Test void hiddenCurrentMarkerDoesNotChangeWorldEligibilityForCurrentBeam() {
        var config = new WaypointerConfig(); config.setHideWaypointsNearPlayer(false);
        var sample = sample(config, Waypoint.at(0, 0, 0), false, new Vec3(.5, .5, .5));
        assertFalse(sample.visible());
        assertTrue(sample.worldVisible());
    }

    @Test void pearlTargetsAndDisabledPointsNeverEmitWorldGeometry() {
        var config = new WaypointerConfig(); config.setHideWaypointsNearPlayer(false);
        for (int flag : new int[]{Waypoint.FLAG_DUNGEON_PEARL_TARGET, Waypoint.FLAG_DISABLED}) {
            assertFalse(sample(config, Waypoint.at(0, 0, 0).withFlags(flag), true,
                    new Vec3(.5, .5, .5)).worldVisible());
        }
    }

    private static WaypointInspectorSample sample(WaypointerConfig config, Waypoint point,
                                                    boolean visible, Vec3 shapeCenter) {
        var group = WaypointGroup.create("Preview", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE); group.add(point);
        return new WaypointInspectorSample(config, group, point, new AABB(-.5, -.5, -.5, .5, .5, .5),
                WaypointRenderer.State.CURRENT, 0xFFFFFF, new WaypointInspectorProjection(10, 0, 0, 70, 100),
                0, null, shapeCenter, -64, 320, Vec3.ZERO, 0, visible);
    }
}
