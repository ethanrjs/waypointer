package com.babbur.waypointer.screen.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;

/** Adapts the production world emitters to the GUI sample camera. */
final class WaypointInspectorVertices implements VertexConsumer {
    private final VertexConsumer target;
    private final PoseStack.Pose pose;
    private final WaypointInspectorProjection camera;
    private final Vector3f scratch = new Vector3f();

    WaypointInspectorVertices(VertexConsumer target, PoseStack.Pose pose, WaypointInspectorProjection camera) {
        this.target = target;
        this.pose = pose;
        this.camera = camera;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        pose.pose().transformPosition(x, y, z, scratch);
        var p = camera.view(scratch.x, scratch.y, scratch.z);
        target.addVertex((float) p.x(), (float) p.y(), (float) -p.depth());
        return this;
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { target.setColor(r, g, b, a); return this; }
    @Override public VertexConsumer setColor(int argb) { target.setColor(argb); return this; }
    @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
    @Override public VertexConsumer setLineWidth(float width) { target.setLineWidth(width); return this; }
    @Override public VertexConsumer setNormal(float nx, float ny, float nz) {
        pose.normal().transform(nx, ny, nz, scratch);
        var direction = camera.view(scratch.x, scratch.y, scratch.z);
        target.setNormal((float) direction.x(), (float) direction.y(), (float) (camera.distance() - direction.depth()));
        return this;
    }
}
