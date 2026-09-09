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

public final class RoutePreviewPipAdapter
        extends PictureInPictureRenderer<RoutePreviewRenderState> {

    private final WaypointInspectorGpuProjection inspectorProjection = new WaypointInspectorGpuProjection();

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
        boolean inspector = state.scene().inspector() != null;
        var previousProjection = RenderSystem.getProjectionMatrixBuffer();
        var previousProjectionType = RenderSystem.getProjectionType();
        var previousGlobals = RenderSystem.getGlobalSettingsUniform();
        var previousColor = RenderSystem.outputColorTextureOverride;
        var previousDepth = RenderSystem.outputDepthTextureOverride;
        if (inspector) RenderSystem.getModelViewStack().pushMatrix().identity();
        try {
            super.prepare(state, guiState, guiScale);
        } catch (RuntimeException allocationFailure) {
            state.availability().markUnavailable();
            Waypointer.LOGGER.error("Could not allocate route preview target", allocationFailure);
        } finally {
            if (inspector) {
                RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
                RenderSystem.setGlobalSettingsUniform(previousGlobals);
                RenderSystem.getModelViewStack().popMatrix();
            }
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
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
        if (state.scene().inspector() != null) {
            inspectorProjection.install(state);
            poseStack = new PoseStack();
        }
        poseStack.pushPose();
        try {
            RoutePreviewRenderCore.applyView(state, poseStack);
            for (RoutePreviewPassPlan.Pass pass
                    : RoutePreviewPassPlan.orderedPasses(state)) {
                for (var submission : RoutePreviewPassPlan.submissions(pass, state)) {
                    submitPass(state, poseStack, submission.type(), submission.emitter());
                }
            }
        } catch (RuntimeException error) {
            state.availability().markUnavailable();
            Waypointer.LOGGER.error("Could not render route preview", error);
        } finally {
            poseStack.popPose();
        }
    }

    @Override public void close() {
        try { super.close(); }
        finally { inspectorProjection.close(); }
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

}
