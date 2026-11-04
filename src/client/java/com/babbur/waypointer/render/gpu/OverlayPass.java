package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        private final DrawStream staticStream;
        private final DrawStream dynamicStream;

        MeshSet(MeshBucket bucket) {
            String name = bucket.kind() + (bucket.depthTested() ? "/depth" : "/through");
            this.statics = new GpuMesh(name + "/static", bucket.topology(), bucket.sorted());
            this.dynamics = new GpuMesh(name + "/dynamic", bucket.topology(), bucket.sorted());
            this.staticStream = new DrawStream(bucket, statics);
            this.dynamicStream = new DrawStream(bucket, dynamics);
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

    private static final class DrawStream implements DrawSchedule.Stream {
        final MeshBucket bucket;
        final GpuMesh mesh;

        DrawStream(MeshBucket bucket, GpuMesh mesh) {
            this.bucket = bucket;
            this.mesh = mesh;
        }

        @Override
        public int primitiveCount() {
            return mesh.primitiveCount();
        }

        @Override
        public double primitiveDistance(int index) {
            return mesh.primitiveDistance(index);
        }

        @Override
        public int bandOrder() {
            return bucket.bandOrder();
        }
    }

    private GpuBuffer noFogBuffer;
    private final Matrix4f scratchView = new Matrix4f();
    private final Vector3f scratchOffset = new Vector3f();
    private final List<Map.Entry<MeshBucket, MeshSet>> ordered = new ArrayList<>();
    private final Map<Identifier, AbstractTexture> textures = new HashMap<>();
    private final List<DrawStream> streams = new ArrayList<>();
    private int lastDrawCalls;

    public int lastDrawCalls() {
        return lastDrawCalls;
    }

    public void draw(OverlayCompositing compositing, FrameView view, SceneKey key,
                    Map<MeshBucket, MeshSet> meshes, OverlayPipelines pipelines,
                    GpuTextureView depthOverride) {
        Objects.requireNonNull(compositing, "compositing");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(meshes, "meshes");
        Objects.requireNonNull(pipelines, "pipelines");

        textures.clear();
        ordered.clear();
        streams.clear();
        lastDrawCalls = 0;
        for (Map.Entry<MeshBucket, MeshSet> entry : meshes.entrySet()) {
            if (!entry.getValue().isEmpty()) ordered.add(entry);
        }
        if (ordered.isEmpty()) return;
        ordered.sort(Comparator
                .comparingInt((Map.Entry<MeshBucket, MeshSet> entry) -> entry.getKey().drawOrder())
                .thenComparing(entry -> entry.getKey().texture() == null
                        ? "" : entry.getKey().texture().toString()));

        double localCameraX = view.cameraX() - key.originX();
        double localCameraY = view.cameraY() - key.originY();
        double localCameraZ = view.cameraZ() - key.originZ();
        CommandEncoder encoder = GpuMeshCompat.createCommandEncoder();
        for (Map.Entry<MeshBucket, MeshSet> entry : ordered) {
            MeshSet set = entry.getValue();
            set.dynamics.prepareIndices(
                    encoder, localCameraX, localCameraY, localCameraZ);
            set.statics.prepareIndices(
                    encoder, localCameraX, localCameraY, localCameraZ);
            if (!set.dynamics.isEmpty()) streams.add(set.dynamicStream);
            if (!set.statics.isEmpty()) streams.add(set.staticStream);
        }

        for (Map.Entry<MeshBucket, MeshSet> entry : ordered) {
            Identifier textureId = entry.getKey().texture();
            if (textureId != null) {
                textures.computeIfAbsent(textureId, RenderPassTextureBinder::resolve);
            }
        }

        GpuBufferSlice projection = view.projection();
        GpuBufferSlice fog = compositing == OverlayCompositing.POST_WORLD
                ? noFog()
                : OverlayPassCompat.currentFog();
        modelViewFor(scratchView, view.view(), key,
                view.cameraX(), view.cameraY(), view.cameraZ());
        scratchOffset.zero();
        GpuBufferSlice transforms = OverlayPassCompat.writeTransform(scratchView, scratchOffset);

        GpuTextureView color = OverlayPassCompat.mainColorView();
        GpuTextureView depth = depthOverride != null ? depthOverride : OverlayPassCompat.mainDepthView();
        int[] drawCalls = {0};
        CommandEncoder drawEncoder = GpuMeshCompat.createCommandEncoder();
        try (RenderPass pass = OverlayPassCompat.beginPass(drawEncoder, () -> "Waypointer overlay", color, depth)) {
            DrawSchedule.plan(streams, (stream, start, end) -> {
                MeshBucket bucket = stream.bucket;
                OverlayPipelines.Slot slot = pipelines.slot(bucket);
                OverlayPassCompat.setPipeline(pass, slot.forCompositing(compositing));
                OverlayPassCompat.bindUniforms(pass, transforms, projection, fog);
                if (bucket.texture() != null
                        && !OverlayPassCompat.bindTexture(pass, "Sampler0", textures.get(bucket.texture()))) {
                    return;
                }
                int indices = bucket.topology().indicesPerPrimitive();
                stream.mesh.draw(pass, start * indices, (end - start) * indices);
                drawCalls[0]++;
            });
        }
        GpuMeshCompat.submit(drawEncoder);
        lastDrawCalls = drawCalls[0];
    }

    static Matrix4f modelViewFor(Matrix4f target, Matrix4fc view, SceneKey key,
                                 double cameraX, double cameraY, double cameraZ) {
        return target.set(view).translate(
                (float) (key.originX() - cameraX),
                (float) (key.originY() - cameraY),
                (float) (key.originZ() - cameraZ));
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
        textures.clear();
        streams.clear();
        lastDrawCalls = 0;
    }
}
