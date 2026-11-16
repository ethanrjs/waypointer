package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;

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

    /** World-space text, like a nametag: {@code pose} already faces the camera at the right scale. */
    public static void submitText(LevelRenderContext context, PoseStack pose, float x, float y,
                                  FormattedCharSequence text, int color, int background, boolean seeThrough) {
        Minecraft.getInstance().font.drawInBatch(text, x, y, color, false, pose.last().pose(),
                context.bufferSource(), seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                background, FULL_BRIGHT);
    }

    /** Draws the text batched by {@link #submitText} this pass. */
    public static void flushText(LevelRenderContext context) {
        context.bufferSource().endBatch();
    }

    private static final int FULL_BRIGHT = 0x00F000F0;
}
