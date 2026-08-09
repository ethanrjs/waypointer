package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

public final class RoutePreviewPipAdapter
        extends PictureInPictureRenderer<RoutePreviewRenderState> {

    private RoutePreviewPipAdapter() {
        super();
    }

    public static void install() {
        PictureInPictureRendererRegistry.register(context -> new RoutePreviewPipAdapter());
    }

    @Override
    public Class<RoutePreviewRenderState> getRenderStateClass() {
        return RoutePreviewRenderState.class;
    }

    @Override
    public void prepare(RoutePreviewRenderState state, GuiRenderState guiState,
                        FeatureRenderDispatcher features, int guiScale) {
        try {
            super.prepare(state, guiState, features, guiScale);
        } catch (RuntimeException allocationFailure) {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            state.availability().markUnavailable();
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
    protected void renderToTexture(RoutePreviewRenderState state, PoseStack poseStack,
                                   SubmitNodeCollector collector) {
        poseStack.pushPose();
        try {
            RoutePreviewRenderCore.applyView(state, poseStack);
            int order = 0;
            for (RoutePreviewPassPlan.Pass pass
                    : RoutePreviewPassPlan.orderedPasses(state.selfOcclusion())) {
                submitPass(collector, order++, state, poseStack,
                        RoutePreviewPassPlan.renderType(pass, state),
                        RoutePreviewPassPlan.emitter(pass));
            }
        } catch (RuntimeException error) {
            state.availability().markUnavailable();
            Waypointer.LOGGER.error("Could not submit route preview geometry", error);
        } finally {
            poseStack.popPose();
        }
    }

    private static void submitPass(SubmitNodeCollector collector, int order,
                                   RoutePreviewRenderState state, PoseStack poseStack,
                                   RenderType renderType, RoutePreviewRenderCore.Emitter emitter) {
        collector.order(order).submitCustomGeometry(poseStack, renderType,
                (submittedPose, vertices) -> {
                    PoseStack snapshot = new PoseStack();
                    snapshot.last().set(submittedPose);
                    try {
                        emitter.emit(state, snapshot, vertices);
                    } catch (RuntimeException error) {
                        state.availability().markUnavailable();
                        Waypointer.LOGGER.error("Could not render route preview geometry", error);
                    }
                });
    }

}
