package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.render.TracerRenderer;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Uses live tracer gates and projection with the inspector's controlled camera. */
final class WaypointInspectorTracer {
    private WaypointInspectorTracer() {}

    static WaypointInspectorSample target(List<WaypointInspectorSample> samples, WaypointGroup source,
                                           WaypointerConfig config, DungeonConfig dungeon) {
        return target(samples, source, config, dungeon, samples.getFirst().playerPosition());
    }

    static WaypointInspectorSample target(List<WaypointInspectorSample> samples, WaypointGroup source,
                                           WaypointerConfig config, DungeonConfig dungeon, Vec3 player) {
        WaypointGroup preview = samples.getFirst().group();
        boolean staticRoute = preview.loadMode() == WaypointGroup.LoadMode.STATIC;
        if (!staticRoute || source.temp() || source.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
            if (CompassMarkerState.navigationStopped(source.current())) return null;
            var current = samples.get(preview.currentIndex());
            return eligible(current, source, dungeon, staticRoute, player) ? current : null;
        }
        WaypointInspectorSample nearest = null;
        double closest = Double.POSITIVE_INFINITY;
        for (var sample : samples) {
            if (!eligible(sample, source, dungeon, true, player)) continue;
            double distance = player.distanceTo(sample.pointPosition());
            if (distance < closest) { closest = distance; nearest = sample; }
        }
        return nearest;
    }

    static boolean eligible(WaypointInspectorSample sample, WaypointGroup source,
                            DungeonConfig dungeon, boolean staticRoute) {
        return eligible(sample, source, dungeon, staticRoute, sample.playerPosition());
    }

    private static boolean eligible(WaypointInspectorSample sample, WaypointGroup source,
                                    DungeonConfig dungeon, boolean staticRoute, Vec3 player) {
        var config = sample.config();
        double distance = player.distanceTo(sample.pointPosition());
        return Double.isFinite(distance) && !CompassMarkerState.navigationStopped(sample.waypoint())
                && TracerRenderer.tracersEnabled(source, config, dungeon)
                && (!staticRoute || !config.hideTracerOnStaticRoutes())
                && (!config.hideWaypointsNearPlayer() || distance > config.hideWaypointsNearRadius())
                && distance >= config.routeMinRenderDistance()
                && (config.routeMaxRenderDistance() <= 0 || distance <= config.routeMaxRenderDistance())
                && (!staticRoute || source.temp() || source.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    || config.maxStaticWaypointRenderDistance() <= 0 || distance <= config.maxStaticWaypointRenderDistance());
    }

    static boolean project(WaypointInspectorProjection camera, Vec3 position,
                           int width, int height, double[] out) {
        var view = camera.view(position.x, position.y, position.z);
        if (view.depth() <= 0) {
            double distance = Math.sqrt(view.x() * view.x() + view.y() * view.y() + view.depth() * view.depth());
            TracerRenderer.projectOffscreenDirection(view.x(), -view.y(), distance, width, height, out);
            return true;
        }
        var point = camera.project(position.x, position.y, position.z);
        boolean offscreen = !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                || Math.abs(point.x()) > width / 2.0 || Math.abs(point.y()) > height / 2.0;
        if (offscreen) TracerRenderer.projectDirectionToEdge(point.x(), point.y(), width, height, out);
        else { out[0] = width / 2.0 + point.x(); out[1] = height / 2.0 + point.y(); }
        return offscreen;
    }
}
