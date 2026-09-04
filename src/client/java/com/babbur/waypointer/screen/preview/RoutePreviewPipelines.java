package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

public final class RoutePreviewPipelines {

    private static RenderType depthOnly;
    private static RenderType surfaces;
    private static RenderType surfacesNoDepth;
    private static RenderType connectors;
    private static RenderType outlines;
    private static RenderType outlinesNoDepth;

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

    public static RenderType surfacesNoDepth() {
        if (surfacesNoDepth == null) {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(id("waypoint_preview_surfaces"))
                    .withDepthStencilState(noDepthTest())
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false)
                    .build();
            surfacesNoDepth = RenderType.create("waypointer_waypoint_preview_surfaces",
                    RenderSetup.builder(pipeline).createRenderSetup());
        }
        return surfacesNoDepth;
    }

    public static RenderType painted(RoutePreviewScene scene, boolean depthTested) {
        if (scene.paintResource() == null) {
            return depthTested ? surfaces() : surfacesNoDepth();
        }
        return depthTested
                ? scene.paintResource().depthTested()
                : scene.paintResource().throughWalls();
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

    public static RenderType outlinesNoDepth() {
        if (outlinesNoDepth == null) {
            outlinesNoDepth = buildRibbons("waypoint_preview_outlines", noDepthTest());
        }
        return outlinesNoDepth;
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

    private static DepthStencilState noDepthTest() {
        return new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, path);
    }
}
