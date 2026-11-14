package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.render.GeometrySink;
import com.babbur.waypointer.render.RenderSubmission;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Captures one frame's geometry per material bucket. Dynamic geometry is rebuilt every frame.
 * Static geometry is kept per route, so a frame rebuilds only the routes whose scene key changed
 * and a bucket's static mesh is reassembled only when one of its routes' geometry changed.
 */
public final class MeshCaptureSink implements GeometrySink {

    public static final class Pair {
        /** The bucket's static mesh as uploaded: every route's static geometry, in route order. */
        public final MeshBuilder statics;
        public final MeshBuilder dynamics;
        private final MeshLayout layout;
        private final MeshTopology topology;
        /** Static geometry emitted outside any route (rebuilt with the whole scene only). */
        private final MeshBuilder ungrouped;
        private final Map<Object, MeshBuilder> groups = new IdentityHashMap<>();
        private boolean staticsDirty;

        Pair(MeshLayout layout, MeshTopology topology) {
            this.layout = layout;
            this.topology = topology;
            this.statics = new MeshBuilder(layout, topology);
            this.dynamics = new MeshBuilder(layout, topology);
            this.ungrouped = new MeshBuilder(layout, topology);
        }

        private MeshBuilder group(Object group) {
            return groups.computeIfAbsent(group, g -> new MeshBuilder(layout, topology));
        }

        private void close() {
            statics.close();
            dynamics.close();
            ungrouped.close();
            for (MeshBuilder builder : groups.values()) builder.close();
            groups.clear();
        }
    }

    private final OverlayPipelines pipelines;
    private final PoseStack poseStack;
    private final Map<MeshBucket, Pair> buckets = new LinkedHashMap<>();
    private boolean rebuildAll = true;
    private Set<?> rebuildGroups = Set.of();
    private Object currentGroup;
    private boolean inDynamicScope;
    private long groupRebuilds;

    public MeshCaptureSink(OverlayPipelines pipelines, PoseStack poseStack) {
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.poseStack = Objects.requireNonNull(poseStack, "poseStack");
    }

    /** Rebuilds every route's static geometry (or none), dropping routes no longer active. */
    public void beginCapture(boolean fullRebuild) {
        beginCapture(fullRebuild ? null : Set.of(), null);
    }

    /**
     * Starts a frame. {@code rebuild} names the routes whose static geometry is re-emitted
     * ({@code null}: all of them); routes outside {@code live} lose their static geometry
     * ({@code null}: keep every route).
     */
    public void beginCapture(Set<?> rebuild, Collection<?> live) {
        rebuildAll = rebuild == null;
        rebuildGroups = rebuild == null ? Set.of() : rebuild;
        currentGroup = null;
        inDynamicScope = false;
        Set<Object> liveSet = null;
        if (live != null) {
            liveSet = Collections.newSetFromMap(new IdentityHashMap<>());
            liveSet.addAll(live);
        }
        for (Pair pair : buckets.values()) {
            pair.dynamics.reset();
            if (rebuildAll) {
                if (!pair.ungrouped.isEmpty()) pair.staticsDirty = true;
                pair.ungrouped.reset();
            }
            for (Iterator<Map.Entry<Object, MeshBuilder>> it = pair.groups.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Object, MeshBuilder> entry = it.next();
                boolean gone = liveSet != null && !liveSet.contains(entry.getKey());
                if (!gone && !rebuildAll && !rebuildGroups.contains(entry.getKey())) continue;
                if (!entry.getValue().isEmpty()) pair.staticsDirty = true;
                if (gone) {
                    entry.getValue().close();
                    it.remove();
                } else {
                    entry.getValue().reset();
                }
            }
        }
    }

    public Map<MeshBucket, Pair> buckets() {
        return buckets;
    }

    /**
     * Reassembles {@code pair}'s static mesh from its routes in {@code order} when any of them
     * changed since the last upload. Returns whether the static mesh needs uploading.
     */
    public boolean assembleStatics(Pair pair, Collection<?> order) {
        if (!pair.staticsDirty) return false;
        pair.staticsDirty = false;
        pair.statics.reset();
        pair.statics.append(pair.ungrouped);
        for (Object group : order) {
            MeshBuilder builder = pair.groups.get(group);
            if (builder != null) pair.statics.append(builder);
        }
        return true;
    }

    /** Route static-geometry emissions (one per route per pass that rebuilt it), over the sink's lifetime. */
    public long groupRebuilds() {
        return groupRebuilds;
    }

    public void removeBucket(MeshBucket bucket) {
        Pair pair = buckets.remove(bucket);
        if (pair != null) pair.close();
    }

    @Override
    public boolean submit(RenderType renderType, RenderSubmission.Geometry geometry) {
        MeshBucket bucket = pipelines.bucketFor(renderType);
        if (bucket == null || geometry == null) {
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
        return rebuildAll || !rebuildGroups.isEmpty();
    }

    @Override
    public boolean staticGeometryNeeded(Object group) {
        return rebuildAll || rebuildGroups.contains(group);
    }

    @Override
    public void staticGroup(Object group, Runnable body) {
        Object previous = currentGroup;
        currentGroup = group;
        groupRebuilds++;
        try {
            body.run();
        } finally {
            currentGroup = previous;
        }
    }

    @Override
    public boolean retainsStaticGeometry() {
        return true;
    }

    public void close() {
        for (Pair pair : buckets.values()) pair.close();
        buckets.clear();
    }

    private Pair newPair(MeshBucket bucket) {
        OverlayPipelines.Slot slot = pipelines.slot(bucket);
        return new Pair(slot.layout(), bucket.topology());
    }

    private final class Switching implements VertexConsumer {
        private final Pair pair;

        private final Vector3f transformed = new Vector3f();

        Switching(Pair pair) {
            this.pair = pair;
        }

        private VertexConsumer target() {
            if (inDynamicScope) return pair.dynamics;
            Object group = currentGroup;
            if (group == null) {
                if (!rebuildAll) return DISCARD;
                pair.staticsDirty = true;
                return pair.ungrouped;
            }
            if (!rebuildAll && !rebuildGroups.contains(group)) return DISCARD;
            pair.staticsDirty = true;
            return pair.group(group);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { target().addVertex(x, y, z); return this; }
        @Override public VertexConsumer addVertex(Matrix4fc pose, float x, float y, float z) {
            pose.transformPosition(x, y, z, transformed);
            target().addVertex(transformed.x, transformed.y, transformed.z);
            return this;
        }
        @Override public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
            pose.transformNormal(x, y, z, transformed);
            target().setNormal(transformed.x, transformed.y, transformed.z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { target().setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setColor(int argb) { target().setColor(argb); return this; }
        @Override public VertexConsumer setUv(float u, float v) { target().setUv(u, v); return this; }
        public VertexConsumer setUv3(float u, float v) { return this; }
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
        public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    };
}
