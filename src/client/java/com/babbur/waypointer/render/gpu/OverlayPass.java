package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Draws retained mesh buckets through one render pass. */
public final class OverlayPass implements AutoCloseable {

    public record FrameView(GpuBufferSlice projection, Matrix4f view,
                            double cameraX, double cameraY, double cameraZ) {
        public FrameView {
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(view, "view");
        }
    }

    public static final class MeshSet implements AutoCloseable {
        public final GpuMesh statics;
        public final GpuMesh dynamics;

        MeshSet(MeshBucket bucket) {
            String name = bucket.kind() + (bucket.depthTested() ? "/depth" : "/through");
            this.statics = new GpuMesh(name + "/static", bucket.topology(), bucket.sorted());
            this.dynamics = new GpuMesh(name + "/dynamic", bucket.topology(), bucket.sorted());
        }

        public static MeshSet create(MeshBucket bucket) {
            return new MeshSet(bucket);
        }

        boolean isEmpty() {
            return statics.isEmpty() && dynamics.isEmpty();
        }

        @Override
        public void close() {
            statics.close();
            dynamics.close();
        }
    }

    private GpuBuffer noFogBuffer;
    private final Matrix4f scratchView = new Matrix4f();
    private final Vector3f scratchOffset = new Vector3f();
    private final List<Map.Entry<MeshBucket, MeshSet>> ordered = new ArrayList<>();

    public int draw(OverlayCompositing compositing, FrameView view, SceneKey key,
                    Map<MeshBucket, MeshSet> meshes, OverlayPipelines pipelines,
                    GpuTextureView depthOverride, OverlayFrameStats stats) {
        Objects.requireNonNull(compositing, "compositing");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(meshes, "meshes");
        Objects.requireNonNull(pipelines, "pipelines");

        ordered.clear();
        for (Map.Entry<MeshBucket, MeshSet> entry : meshes.entrySet()) {
            if (!entry.getValue().isEmpty()) ordered.add(entry);
        }
        if (ordered.isEmpty()) return 0;
        ordered.sort(Comparator
                .comparingInt((Map.Entry<MeshBucket, MeshSet> entry) -> entry.getKey().drawOrder())
                .thenComparing(entry -> entry.getKey().texture() == null
                        ? "" : entry.getKey().texture().toString()));

        double localCameraX = view.cameraX() - key.originX();
        double localCameraY = view.cameraY() - key.originY();
        double localCameraZ = view.cameraZ() - key.originZ();
        long prepareStarted = System.nanoTime();
        int indexBytes = 0;
        CommandEncoder encoder = GpuMeshCompat.createCommandEncoder();
        for (Map.Entry<MeshBucket, MeshSet> entry : ordered) {
            MeshSet set = entry.getValue();
            indexBytes += set.dynamics.prepareIndices(
                    encoder, localCameraX, localCameraY, localCameraZ);
            indexBytes += set.statics.prepareIndices(
                    encoder, localCameraX, localCameraY, localCameraZ);
        }
        if (stats != null) {
            stats.recordUpload(System.nanoTime() - prepareStarted, indexBytes, 0, 0);
        }

        GpuBufferSlice projection = view.projection();
        GpuBufferSlice fog = compositing == OverlayCompositing.POST_WORLD
                ? noFog()
                : OverlayPassCompat.currentFog();
        scratchView.set(view.view());
        scratchOffset.set(
                (float) (key.originX() - view.cameraX()),
                (float) (key.originY() - view.cameraY()),
                (float) (key.originZ() - view.cameraZ()));
        GpuBufferSlice transforms = OverlayPassCompat.writeTransform(scratchView, scratchOffset);

        GpuTextureView color = OverlayPassCompat.mainColorView();
        GpuTextureView depth = depthOverride != null ? depthOverride : OverlayPassCompat.mainDepthView();
        int drawCalls = 0;
        long started = System.nanoTime();
        try (RenderPass pass = OverlayPassCompat.beginPass(() -> "Waypointer overlay", color, depth)) {
            for (Map.Entry<MeshBucket, MeshSet> entry : ordered) {
                MeshBucket bucket = entry.getKey();
                OverlayPipelines.Slot slot = pipelines.slot(bucket);
                pass.setPipeline(slot.forCompositing(compositing));
                OverlayPassCompat.bindUniforms(pass, transforms, projection, fog);
                if (bucket.texture() != null) {
                    if (!OverlayPassCompat.bindTexture(pass, "Sampler0", bucket.texture())) continue;
                }
                MeshSet set = entry.getValue();
                if (!set.dynamics.isEmpty()) {
                    set.dynamics.draw(pass);
                    drawCalls++;
                }
                if (!set.statics.isEmpty()) {
                    set.statics.draw(pass);
                    drawCalls++;
                }
            }
        }
        if (stats != null) stats.recordDraw(System.nanoTime() - started, ordered.size(), drawCalls);
        return drawCalls;
    }

    private GpuBufferSlice noFog() {
        if (noFogBuffer == null) {
            noFogBuffer = OverlayPassCompat.createNoFogBuffer(() -> "Waypointer overlay no-fog");
        }
        return noFogBuffer == null ? OverlayPassCompat.currentFog() : noFogBuffer.slice();
    }

    @Override
    public void close() {
        if (noFogBuffer != null) {
            noFogBuffer.close();
            noFogBuffer = null;
        }
        ordered.clear();
    }
}
