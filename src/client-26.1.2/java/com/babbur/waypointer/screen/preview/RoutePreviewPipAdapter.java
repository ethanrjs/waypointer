package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/** Minecraft 26.1.2 immediate-buffer adapter for the route preview. */
public final class RoutePreviewPipAdapter
        extends PictureInPictureRenderer<RoutePreviewRenderState> {

    private RoutePreviewPipAdapter(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    public static void install() {
        PictureInPictureRendererRegistry.register(
                context -> new RoutePreviewPipAdapter(context.bufferSource()));
    }

    @Override
    public Class<RoutePreviewRenderState> getRenderStateClass() {
        return RoutePreviewRenderState.class;
    }

    @Override
    public void prepare(RoutePreviewRenderState state, GuiRenderState guiState, int guiScale) {
        try {
            super.prepare(state, guiState, guiScale);
        } catch (RuntimeException allocationFailure) {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            RoutePreviewAvailability.markUnavailable();
            Waypointer.LOGGER.error("Could not allocate route preview target", allocationFailure);
        }
    }

    @Override
    protected String getTextureLabel() {
        return "Waypointer route preview";
    }

    @Override
    protected float getTranslateY(int textureHeight, int guiScale) {
        return textureHeight * 0.5f;
    }

    @Override
    protected void renderToTexture(RoutePreviewRenderState state, PoseStack poseStack) {
        poseStack.pushPose();
        try {
            RoutePreviewRenderCore.applyView(state, poseStack);
            submitPass(state, poseStack, RoutePreviewPipelines.depthOnly(),
                    RoutePreviewRenderCore::emitDepth);
            submitPass(state, poseStack, surfaceType(state),
                    RoutePreviewRenderCore::emitSurfaces);
            submitPass(state, poseStack, RoutePreviewPipelines.surfaces(),
                    RoutePreviewRenderCore::emitPaintHover);
            submitPass(state, poseStack, RoutePreviewPipelines.connectors(),
                    RoutePreviewRenderCore::emitConnectors);
            submitPass(state, poseStack, RoutePreviewPipelines.outlines(),
                    RoutePreviewRenderCore::emitOutlines);
        } catch (RuntimeException error) {
            RoutePreviewAvailability.markUnavailable();
            Waypointer.LOGGER.error("Could not render route preview", error);
        } finally {
            poseStack.popPose();
        }
    }

    private void submitPass(RoutePreviewRenderState state, PoseStack poseStack,
                            RenderType renderType, RoutePreviewRenderCore.Emitter emitter) {
        VertexConsumer vertices = bufferSource.getBuffer(renderType);
        try {
            emitter.emit(state, poseStack, vertices);
        } finally {
            bufferSource.endBatch(renderType);
        }
    }

    private static RenderType surfaceType(RoutePreviewRenderState state) {
        RoutePreviewScene scene = state.scene();
        return scene.paint() != null && !scene.simplified()
                ? RoutePreviewPipelines.painted(scene)
                : RoutePreviewPipelines.surfaces();
    }
}
