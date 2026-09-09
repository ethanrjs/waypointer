package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.WaypointSkipFade;
import net.minecraft.client.Minecraft;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.babbur.waypointer.render.RenderHelpers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable rendering inputs captured from the draft, never resolved against the saved override again. */
record WaypointInspectorSample(WaypointerConfig config, WaypointGroup group, Waypoint waypoint,
                               AABB bounds, WaypointRenderer.State role, int color,
                               WaypointInspectorProjection camera, float animationTime,
                               RoutePreviewPaintResource.Entry paint, Vec3 worldCenter, int minBuildY, int maxBuildY,
                               Vec3 position, int index, boolean sequenceVisible) {
    float alpha() {
        float normal = WaypointRenderer.styleStateAlpha(config, group, role);
        var fade = WaypointSkipFade.get(group);
        return fade == null ? normal : fade.alpha(index, normal, sequenceVisible);
    }
    Vec3 pointPosition() { return position.add(waypoint.centerX() - worldCenter.x,
            waypoint.centerY() - worldCenter.y, waypoint.centerZ() - worldCenter.z); }
    Vec3 playerPosition() {
        var player = Minecraft.getInstance().player;
        return cameraPosition().add(0, -(player == null ? 1.62 : player.getEyeHeight()), 0);
    }
    Vec3 cameraPosition() {
        return new Vec3(camera.distance() * Math.sin(camera.yaw()) * Math.cos(camera.pitch()),
                -camera.distance() * Math.sin(camera.pitch()),
                -camera.distance() * Math.cos(camera.yaw()) * Math.cos(camera.pitch()));
    }
    double distanceTo(Vec3 location) { return cameraPosition().distanceTo(location); }
    private boolean rolePaintFallback() {
        return paint != null && config.colorSequenceWaypointsByRole()
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE;
    }
    private boolean painted() { return paint != null && !rolePaintFallback(); }

    boolean visible() {
        return visible(true);
    }
    boolean labelVisible() { return visible(false); }
    private boolean visible(boolean staticDistance) {
        var fade = WaypointSkipFade.get(group);
        return (sequenceVisible || config.showCurrentSequenceWaypoint() && fade != null && fade.isOutgoing(index)) && worldVisible(staticDistance);
    }

    boolean worldVisible() { return worldVisible(true); }
    private boolean worldVisible(boolean staticDistance) {
        double d = distanceTo(pointPosition());
        var fade = WaypointSkipFade.get(group);
        return group.isWaypointEnabled(index)
                && !waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)
                && !WaypointRenderer.shouldForceHideReachedWaypoint(index, group.currentIndex(), waypoint)
                && (group.loadMode() != WaypointGroup.LoadMode.STATIC || !config.hideReachedStaticWaypointsUntilCycleComplete()
                    || !group.isStaticWaypointReached(index) || CompassMarkerState.navigationStopped(waypoint))
                && (!config.hideWaypointsNearPlayer() || config.hideWaypointsNearRadius() <= 0 || playerPosition().distanceTo(pointPosition()) > config.hideWaypointsNearRadius())
                && (config.routeMinRenderDistance() <= 0 || d >= config.routeMinRenderDistance())
                && (config.routeMaxRenderDistance() <= 0 || d <= config.routeMaxRenderDistance())
                && (!staticDistance || group.loadMode() != WaypointGroup.LoadMode.STATIC || config.maxStaticWaypointRenderDistance() <= 0 || d <= config.maxStaticWaypointRenderDistance())
                && (group.loadMode() != WaypointGroup.LoadMode.SEQUENCE || role != WaypointRenderer.State.COMPLETED || config.showCompleted()
                    || fade != null && fade.isOutgoing(index));
    }

    RenderType renderType(RoutePreviewPassPlan.Pass pass) {
        boolean depth = waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);
        return switch (pass) {
            case CONNECTORS, OUTLINES -> depth ? WaypointerRenderPipelines.linesDepthTested(config.renderAntialiasing())
                    : WaypointerRenderPipelines.linesThroughWalls(config.renderAntialiasing());
            case PAINT_HOVER -> config.useBeaconBeamTextures()
                    ? depth ? WaypointerRenderPipelines.beaconBeamDepthTested() : WaypointerRenderPipelines.beaconBeamThroughWalls()
                    : depth ? WaypointerRenderPipelines.quadsDepthTested() : WaypointerRenderPipelines.quadsThroughWalls();
            case SURFACE -> !painted() ? depth ? WaypointerRenderPipelines.quadsDepthTested() : WaypointerRenderPipelines.quadsThroughWalls()
                    : depth ? paint.depthTested() : paint.throughWalls();
            case DEPTH -> RoutePreviewPipelines.depthOnly();
        };
    }

    void emitMarker(PoseStack pose, VertexConsumer target, boolean outline) {
        if (!visible()) return;
        VertexConsumer vertices = new WaypointInspectorVertices(target, pose.last(), camera);
        PoseStack identity = new PoseStack();
        identity.translate(position.x, position.y, position.z);
        if (!outline && painted()) {
            // Same atlas, padding and alpha as the live painted marker; GUI has a controlled background.
            RenderHelpers.emitTexturedBox(vertices, identity,
                    (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                    (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ,
                    alpha() * (float) config.beaconOpacity(),
                    cameraPosition().x - position.x, cameraPosition().y - position.y, cameraPosition().z - position.z,
                    RoutePreviewPaintResource.ATLAS_WIDTH, RoutePreviewPaintResource.ATLAS_HEIGHT,
                    RoutePreviewPaintResource.PADDING);
            return;
        }
        var style = config.effectiveBoxStyle();
        if (outline && style != WaypointerConfig.BoxStyle.OUTLINED && style != WaypointerConfig.BoxStyle.FILLED_OUTLINED) return;
        if (!outline && !rolePaintFallback() && style == WaypointerConfig.BoxStyle.OUTLINED && !WaypointRenderer.isFilledSubwaypoint(waypoint)) return;
        WaypointRenderer.emitStyledMarker(vertices, identity,
                new double[]{bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ},
                color, alpha(), config, outline);
    }

    void emitBeam(PoseStack pose, VertexConsumer target) {
        var mode = config.beaconBeamMode();
        var fade = WaypointSkipFade.get(group);
        boolean outgoing = fade != null && fade.isOutgoing(index);
        boolean normallyVisible = mode == WaypointerConfig.BeaconBeamMode.CURRENT
                ? index == WaypointRenderer.currentBeamIndex(group) && (!outgoing || config.showCompleted()) : sequenceVisible;
        if (!worldVisible() || mode == WaypointerConfig.BeaconBeamMode.OFF || !normallyVisible && !outgoing) return;
        float beamAlpha = fade == null ? WaypointRenderer.styleStateAlpha(config, group, role)
                : fade.beamAlpha(index, WaypointRenderer.styleStateAlpha(config, group, role), normallyVisible);
        float radius = WaypointRenderer.beaconTextureRadiusScale(waypoint, worldCenter.add(cameraPosition().subtract(position)), false);
        float x = (float) (waypoint.centerX() - worldCenter.x), z = (float) (waypoint.centerZ() - worldCenter.z);
        float worldStart = config.beaconBeamExtendsBelowWaypoint() ? minBuildY : waypoint.y();
        float low = (float) (worldStart - worldCenter.y);
        float high = (float) (WaypointRenderer.beaconBeamTop(waypoint.y(), worldStart, maxBuildY) - worldCenter.y);
        if (high <= low) return;
        PoseStack identity = new PoseStack();
        identity.translate(position.x, position.y, position.z);
        WaypointRenderer.emitStyledBeacon(new WaypointInspectorVertices(target, pose.last(), camera), identity,
                x, z, low, high, color, beamAlpha * (float) config.beaconOpacity(), animationTime, radius, config);
    }
}
