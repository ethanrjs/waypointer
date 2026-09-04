package com.babbur.waypointer.screen.preview;

import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.List;

public final class RoutePreviewPassPlan {

    public enum Pass {
        DEPTH,
        SURFACE,
        PAINT_HOVER,
        CONNECTORS,
        OUTLINES
    }

    private static final List<Pass> WITH_SELF_OCCLUSION = List.of(Pass.values());
    private static final List<Pass> WITHOUT_SELF_OCCLUSION = List.of(
            Pass.SURFACE, Pass.PAINT_HOVER, Pass.CONNECTORS, Pass.OUTLINES);

    private RoutePreviewPassPlan() {}

    public static List<Pass> orderedPasses(boolean selfOcclusion) {
        return selfOcclusion ? WITH_SELF_OCCLUSION : WITHOUT_SELF_OCCLUSION;
    }

    static RenderType renderType(Pass pass, RoutePreviewRenderState state) {
        return switch (pass) {
            case DEPTH -> RoutePreviewPipelines.depthOnly();
            case SURFACE -> surfaceType(state);
            case PAINT_HOVER -> state.selfOcclusion()
                    ? RoutePreviewPipelines.surfaces()
                    : RoutePreviewPipelines.surfacesNoDepth();
            case CONNECTORS -> RoutePreviewPipelines.connectors();
            case OUTLINES -> state.selfOcclusion()
                    ? RoutePreviewPipelines.outlines()
                    : RoutePreviewPipelines.outlinesNoDepth();
        };
    }

    static RoutePreviewRenderCore.Emitter emitter(Pass pass) {
        return switch (pass) {
            case DEPTH -> RoutePreviewRenderCore::emitDepth;
            case SURFACE -> RoutePreviewRenderCore::emitSurfaces;
            case PAINT_HOVER -> RoutePreviewRenderCore::emitPaintHover;
            case CONNECTORS -> RoutePreviewRenderCore::emitConnectors;
            case OUTLINES -> RoutePreviewRenderCore::emitOutlines;
        };
    }

    private static RenderType surfaceType(RoutePreviewRenderState state) {
        RoutePreviewScene scene = state.scene();
        return scene.paint() != null && !scene.simplified()
                ? RoutePreviewPipelines.painted(scene,
                        paintedSurfaceUsesDepthTest(state.selfOcclusion()))
                : state.selfOcclusion()
                        ? RoutePreviewPipelines.surfaces()
                        : RoutePreviewPipelines.surfacesNoDepth();
    }

    static boolean paintedSurfaceUsesDepthTest(boolean selfOcclusion) {
        return selfOcclusion;
    }
}
