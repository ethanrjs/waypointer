package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class RenderSubmission {

    @FunctionalInterface
    public interface Geometry {
        void emit(VertexConsumer consumer, PoseStack poseStack);
    }

    private RenderSubmission() {}

    static boolean requiredBindingsAvailable() {
        return WorldOverlayCompat.requiredBindingsAvailable();
    }

    public static boolean submit(LevelRenderContext context, PoseStack poseStack,
                                 RenderType renderType, Geometry geometry) {
        if (context == null || poseStack == null || renderType == null || geometry == null) {
            return false;
        }
        return WorldOverlayCompat.submit(context, poseStack, renderType, geometry);
    }
}
