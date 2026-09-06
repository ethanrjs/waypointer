package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pipelines and legacy RenderType mappings for retained geometry. */
public final class OverlayPipelines {

    private record PipelineKey(MeshBucket.Kind kind, boolean depthTested, boolean smooth) {
        static PipelineKey from(MeshBucket bucket) {
            return new PipelineKey(bucket.kind(), bucket.depthTested(),
                    bucket.kind() == MeshBucket.Kind.LINES && WaypointerRenderPipelines.antialiasing());
        }
    }

    public record Slot(RenderPipeline inWorld, RenderPipeline postWorld, MeshLayout layout) {
        public RenderPipeline forCompositing(OverlayCompositing compositing) {
            return compositing == OverlayCompositing.POST_WORLD ? postWorld : inWorld;
        }
    }

    private static final DepthStencilState NO_DEPTH_TEST =
            new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    private static final DepthStencilState DEPTH_TESTED =
            new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false);

    private final Map<RenderType, MeshBucket> bucketsByRenderType = new IdentityHashMap<>();
    private final Map<PipelineKey, Slot> slots = new HashMap<>();

    private OverlayPipelines() {}

    public static OverlayPipelines create() {
        OverlayPipelines pipelines = new OverlayPipelines();
        for (boolean smooth : new boolean[]{false, true}) {
            pipelines.map(WaypointerRenderPipelines.linesThroughWalls(smooth),
                    MeshBucket.untextured(MeshBucket.Kind.LINES, false));
            pipelines.map(WaypointerRenderPipelines.linesDepthTested(smooth),
                    MeshBucket.untextured(MeshBucket.Kind.LINES, true));
        }
        pipelines.map(com.babbur.waypointer.render.WaypointerRenderPipelines.quadsThroughWalls(),
                MeshBucket.untextured(MeshBucket.Kind.QUADS, false));
        pipelines.map(com.babbur.waypointer.render.WaypointerRenderPipelines.quadsDepthTested(),
                MeshBucket.untextured(MeshBucket.Kind.QUADS, true));
        Identifier beam = net.minecraft.client.renderer.blockentity.BeaconRenderer.BEAM_LOCATION;
        pipelines.map(com.babbur.waypointer.render.WaypointerRenderPipelines.beaconBeamThroughWalls(),
                MeshBucket.textured(MeshBucket.Kind.BEAM, false, beam));
        pipelines.map(com.babbur.waypointer.render.WaypointerRenderPipelines.beaconBeamDepthTested(),
                MeshBucket.textured(MeshBucket.Kind.BEAM, true, beam));
        return pipelines;
    }

    public void registerPainted(Identifier texture, RenderType throughWalls, RenderType depthTested) {
        Objects.requireNonNull(texture, "texture");
        map(throughWalls, MeshBucket.textured(MeshBucket.Kind.PAINTED, false, texture));
        map(depthTested, MeshBucket.textured(MeshBucket.Kind.PAINTED, true, texture));
    }

    public List<MeshBucket> unregisterPainted(RenderType throughWalls, RenderType depthTested) {
        List<MeshBucket> removed = new ArrayList<>(2);
        removePainted(throughWalls, removed);
        removePainted(depthTested, removed);
        return List.copyOf(removed);
    }

    private void removePainted(RenderType renderType, List<MeshBucket> removed) {
        MeshBucket bucket = bucketsByRenderType.remove(renderType);
        if (bucket == null) return;
        removed.add(bucket);
    }

    public MeshBucket bucketFor(RenderType renderType) {
        return renderType == null ? null : bucketsByRenderType.get(renderType);
    }

    public Slot slot(MeshBucket bucket) {
        Objects.requireNonNull(bucket, "bucket");
        return slots.computeIfAbsent(PipelineKey.from(bucket), OverlayPipelines::buildSlot);
    }

    public boolean assignIrisPrograms(IrisBridge iris) {
        if (iris == null) return false;
        boolean assignedAll = true;
        for (Map.Entry<PipelineKey, Slot> entry : slots.entrySet()) {
            assignedAll &= iris.assign(
                    entry.getValue().inWorld(), irisProgramFor(entry.getKey().kind()));
        }
        return assignedAll;
    }

    static IrisBridge.Program irisProgramFor(MeshBucket.Kind kind) {
        return switch (kind) {
            case LINES -> IrisBridge.Program.LINES;
            case QUADS -> IrisBridge.Program.BASIC;
            case BEAM, PAINTED -> IrisBridge.Program.BEACON_BEAM;
        };
    }

    private void map(RenderType renderType, MeshBucket bucket) {
        if (renderType == null) return;
        bucketsByRenderType.put(renderType, bucket);
    }

    private static Slot buildSlot(PipelineKey key) {
        String suffix = (key.depthTested() ? "depth" : "through") + (key.smooth() ? "_smooth" : "");
        RenderPipeline inWorld = build(key, "gpu/" + key.kind().name().toLowerCase(java.util.Locale.ROOT) + "_" + suffix);
        RenderPipeline postWorld = build(key, "gpu_post/" + key.kind().name().toLowerCase(java.util.Locale.ROOT) + "_" + suffix);
        MeshLayout layout = VertexLayoutCompat.describe(inWorld);
        return new Slot(inWorld, postWorld, layout);
    }

    private static RenderPipeline build(PipelineKey key, String path) {
        DepthStencilState depth = key.depthTested() ? DEPTH_TESTED : NO_DEPTH_TEST;
        Identifier location = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, path);
        return switch (key.kind()) {
            case LINES -> WaypointerRenderPipelines.buildLinesPipeline(location, depth, key.smooth());
            case QUADS -> RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(location)
                    .withDepthStencilState(depth)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false)
                    .build();
            case BEAM -> RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                    .withLocation(location)
                    .withDepthStencilState(depth)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .build();
            case PAINTED -> RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                    .withLocation(location)
                    .withDepthStencilState(depth)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(true)
                    .build();
        };
    }
}
