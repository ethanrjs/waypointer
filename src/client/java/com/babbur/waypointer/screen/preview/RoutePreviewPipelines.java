package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Preview-only pipelines. They do not alter the live world renderer's depth behavior. */
public final class RoutePreviewPipelines {

    private static RenderType depthOnly;
    private static RenderType surfaces;
    private static RenderType connectors;
    private static RenderType outlines;

    private RoutePreviewPipelines() {}

    public static RenderType depthOnly() {
        if (depthOnly == null) {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(id("route_preview_depth"))
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withColorTargetState(RoutePreviewPipelineCompat.noColorWrites())
                    .withCull(false)
                    .build();
            depthOnly = RenderType.create("waypointer_route_preview_depth",
                    RenderSetup.builder(pipeline).createRenderSetup());
        }
        return depthOnly;
    }

    public static RenderType surfaces() {
        if (surfaces == null) {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(id("route_preview_surfaces"))
                    .withDepthStencilState(readOnlyDepth())
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false)
                    .build();
            surfaces = RenderType.create("waypointer_route_preview_surfaces",
                    RenderSetup.builder(pipeline).createRenderSetup());
        }
        return surfaces;
    }

    public static RenderType painted(RoutePreviewScene scene) {
        if (scene.paintResource() == null) return surfaces();
        return scene.paintResource().renderType();
    }

    public static RenderType connectors() {
        if (connectors == null) {
            connectors = buildRibbons("route_preview_connectors", readOnlyDepth());
        }
        return connectors;
    }

    public static RenderType outlines() {
        if (outlines == null) {
            outlines = buildRibbons(
                    "route_preview_outlines", RoutePreviewPipelineCompat.outlineDepthState());
        }
        return outlines;
    }

    private static RenderType buildRibbons(String path, DepthStencilState depthState) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(id(path))
                .withDepthStencilState(depthState)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
        return RenderType.create("waypointer_" + path,
                RenderSetup.builder(pipeline).createRenderSetup());
    }

    private static DepthStencilState readOnlyDepth() {
        return new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, path);
    }
}
