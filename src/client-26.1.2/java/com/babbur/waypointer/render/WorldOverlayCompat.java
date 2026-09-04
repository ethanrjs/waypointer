package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.Objects;
import java.util.function.Consumer;

public final class WorldOverlayCompat {
    private WorldOverlayCompat() {}

    public static void register(Consumer<LevelRenderContext> callback) {
        Objects.requireNonNull(callback, "callback");
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(callback::accept);
    }

    public static boolean submit(LevelRenderContext context, PoseStack poseStack,
                                 RenderType renderType, RenderSubmission.Geometry geometry) {
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        VertexConsumer vertices = buffers.getBuffer(renderType);
        try {
            geometry.emit(vertices, poseStack);
        } finally {
            buffers.endBatch(renderType);
        }
        return true;
    }

}
