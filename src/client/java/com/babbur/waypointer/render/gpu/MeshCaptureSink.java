package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.render.GeometrySink;
import com.babbur.waypointer.render.RenderSubmission;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Captures geometry into static and dynamic CPU mesh builders. */
public final class MeshCaptureSink implements GeometrySink {

    public static final class Pair {
        public final MeshBuilder statics;
        public final MeshBuilder dynamics;

        Pair(MeshLayout layout, MeshTopology topology) {
            this.statics = new MeshBuilder(layout, topology);
            this.dynamics = new MeshBuilder(layout, topology);
        }
    }

    private final OverlayPipelines pipelines;
    private final PoseStack poseStack;
    private final Map<MeshBucket, Pair> buckets = new LinkedHashMap<>();
    private boolean captureStatic = true;
    private boolean inDynamicScope;
    private int unknownRenderTypes;

    public MeshCaptureSink(OverlayPipelines pipelines, PoseStack poseStack) {
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.poseStack = Objects.requireNonNull(poseStack, "poseStack");
    }

    /** Starts a full or dynamic-only capture. */
    public void beginCapture(boolean fullRebuild) {
        captureStatic = fullRebuild;
        inDynamicScope = false;
        unknownRenderTypes = 0;
        for (Pair pair : buckets.values()) {
            pair.dynamics.reset();
            if (fullRebuild) pair.statics.reset();
        }
    }

    public Map<MeshBucket, Pair> buckets() {
        return buckets;
    }

    public int unknownRenderTypes() {
        return unknownRenderTypes;
    }

    public void removeBucket(MeshBucket bucket) {
        Pair pair = buckets.remove(bucket);
        if (pair == null) return;
        pair.statics.close();
        pair.dynamics.close();
    }

    @Override
    public boolean submit(RenderType renderType, RenderSubmission.Geometry geometry) {
        MeshBucket bucket = pipelines.bucketFor(renderType);
        if (bucket == null || geometry == null) {
            unknownRenderTypes++;
            return false;
        }
        Pair pair = buckets.computeIfAbsent(bucket, this::newPair);
        geometry.emit(new Switching(pair), poseStack);
        return true;
    }

    @Override
    public void dynamic(Runnable body) {
        boolean previous = inDynamicScope;
        inDynamicScope = true;
        try {
            body.run();
        } finally {
            inDynamicScope = previous;
        }
    }

    @Override
    public boolean staticGeometryNeeded() {
        return captureStatic;
    }

    @Override
    public boolean retainsStaticGeometry() {
        return true;
    }

    public void close() {
        for (Pair pair : buckets.values()) {
            pair.statics.close();
            pair.dynamics.close();
        }
        buckets.clear();
    }

    private Pair newPair(MeshBucket bucket) {
        OverlayPipelines.Slot slot = pipelines.slot(bucket);
        return new Pair(slot.layout(), bucket.topology());
    }

    private final class Switching implements VertexConsumer {
        private final Pair pair;

        Switching(Pair pair) {
            this.pair = pair;
        }

        private VertexConsumer target() {
            if (inDynamicScope) return pair.dynamics;
            return captureStatic ? pair.statics : DISCARD;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { target().addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { target().setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setColor(int argb) { target().setColor(argb); return this; }
        @Override public VertexConsumer setUv(float u, float v) { target().setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { target().setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { target().setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { target().setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { target().setLineWidth(width); return this; }
    }

    private static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    };
}
