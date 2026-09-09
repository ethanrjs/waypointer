package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GrassColor;
import java.util.List;
import java.util.ArrayList;
import com.babbur.waypointer.core.Waypoint;

record WaypointInspectorScene(List<WaypointInspectorSample> samples, boolean ground) {
    private static final Identifier GRASS_TOP = Identifier.withDefaultNamespace("block/grass_block_top");
    private static RenderType groundRenderType;
    WaypointInspectorProjection camera() { return edited().camera(); }
    WaypointInspectorSample edited() { return samples.get(WaypointInspectorSequence.EDITED_INDEX); }
    List<RoutePreviewPassPlan.Submission> submissions(RoutePreviewPassPlan.Pass pass) {
        if (pass == RoutePreviewPassPlan.Pass.DEPTH) return List.of(new RoutePreviewPassPlan.Submission(
                renderType(pass), (state, pose, vertices) -> emitGround(pose, vertices)));
        List<RoutePreviewPassPlan.Submission> result = new ArrayList<>();
        if (pass == RoutePreviewPassPlan.Pass.CONNECTORS) {
            for (boolean depth : new boolean[]{false, true}) {
                WaypointRenderer.forEachRouteLineSegment(edited().group(), depth,
                        edited().config().sequenceVisibility(), edited().config().keepSubwaypointsVisibleUntilNextWaypoint(),
                        i -> samples.get(i).worldVisible(), (from, to) -> {
                    var a = samples.get(from); var b = samples.get(to);
                    var type = depth ? WaypointerRenderPipelines.linesDepthTested(a.config().renderAntialiasing())
                            : WaypointerRenderPipelines.linesThroughWalls(a.config().renderAntialiasing());
                    result.add(new RoutePreviewPassPlan.Submission(type,
                            (state, pose, vertices) -> emitConnector(a, b, pose, vertices)));
                });
            }
        } else for (var sample : samples) {
            result.add(new RoutePreviewPassPlan.Submission(sample.renderType(pass), (state, pose, vertices) -> {
                if (pass == RoutePreviewPassPlan.Pass.PAINT_HOVER) sample.emitBeam(pose, vertices);
                else sample.emitMarker(pose, vertices, pass == RoutePreviewPassPlan.Pass.OUTLINES);
            }));
        }
        return result;
    }
    RenderType renderType(RoutePreviewPassPlan.Pass pass) {
        if (pass != RoutePreviewPassPlan.Pass.DEPTH) return edited().renderType(pass);
        if (groundRenderType == null) groundRenderType = WaypointerRenderPipelines.paintedQuads(
                Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).location(), false);
        return groundRenderType;
    }
    void emitMarker(PoseStack pose, VertexConsumer vertices, boolean outline) {
        for (var sample : samples) sample.emitMarker(pose, vertices, outline);
    }
    void emitBeam(PoseStack pose, VertexConsumer vertices) { for (var sample : samples) sample.emitBeam(pose, vertices); }
    void emitConnector(PoseStack pose, VertexConsumer vertices) {
        for (int i = 1; i < samples.size(); i++) emitConnector(samples.get(i - 1), samples.get(i), pose, vertices);
    }
    private void emitConnector(WaypointInspectorSample a, WaypointInspectorSample b, PoseStack pose, VertexConsumer vertices) {
        if (!a.config().showRouteLines() || !a.worldVisible() || !b.worldVisible()) return;
        var camera = a.camera();
        var player = Minecraft.getInstance().player;
        float eyeHeight = player == null ? 1.27f : player.getEyeHeight(Pose.CROUCHING);
            var start = WaypointRenderer.routeLineStart(Minecraft.getInstance().level, a.waypoint(), a.config().useEtherwarpHeight(), eyeHeight, new double[6])
                    .subtract(a.worldCenter()).add(a.position());
            var end = b.position();
            var down = new net.minecraft.world.phys.Vec3(-Math.sin(camera.yaw()) * Math.sin(camera.pitch()),
                    -Math.cos(camera.pitch()), Math.cos(camera.yaw()) * Math.sin(camera.pitch()));
            start = WaypointRenderer.clipLineStartOutsideCamera(start, end, a.cameraPosition(), down, .25, .12);
            WaypointRenderer.emitStyledConnector(new WaypointInspectorVertices(vertices, pose.last(), camera),
                    new PoseStack(), start, end, a.config());
    }
    void emitGround(PoseStack pose, VertexConsumer vertices) {
        if (!ground) return;
        var camera = edited().camera();
        var projected = new WaypointInspectorVertices(vertices, pose.last(), camera);
        // Resolve the stitched sprite each frame so resource-pack reloads and animated grass stay current.
        var sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(GRASS_TOP);
        int color = 0xFF000000 | GrassColor.getDefaultColor();
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        for (int z = 18; z >= -8; z--) for (int x = -18; x < 18; x++) {
            projected.addVertex(x, -.51f, z).setColor(color).setUv(u0, v0).setLight(0xF000F0);
            projected.addVertex(x + 1, -.51f, z).setColor(color).setUv(u1, v0).setLight(0xF000F0);
            projected.addVertex(x + 1, -.51f, z + 1).setColor(color).setUv(u1, v1).setLight(0xF000F0);
            projected.addVertex(x, -.51f, z + 1).setColor(color).setUv(u0, v1).setLight(0xF000F0);
        }
    }
}
