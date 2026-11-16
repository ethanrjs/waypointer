package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;

import java.util.Objects;
import java.util.function.Consumer;

public final class WorldOverlayCompat {
    private static final int WORLD_OVERLAY_ORDER = Integer.MAX_VALUE;

    private WorldOverlayCompat() {}

    public static void register(Consumer<LevelRenderContext> callback) {
        Objects.requireNonNull(callback, "callback");
        LevelRenderEvents.COLLECT_SUBMITS.register(callback::accept);
    }

    public static boolean submit(LevelRenderContext context, PoseStack poseStack,
                                 RenderType renderType, RenderSubmission.Geometry geometry) {
        SubmitNodeCollection nodes = (SubmitNodeCollection)
                context.submitNodeCollector().order(WORLD_OVERLAY_ORDER);
        nodes.afterTerrain.submit(new CustomFeatureRenderer.Submit(
                poseStack.last().copy(), renderType, (submittedPose, vertices) -> {
                    PoseStack snapshot = new PoseStack();
                    snapshot.last().set(submittedPose);
                    geometry.emit(vertices, snapshot);
                }));
        return true;
    }

    /** World-space text, like a nametag: {@code pose} already faces the camera at the right scale. */
    public static void submitText(LevelRenderContext context, PoseStack pose, float x, float y,
                                  FormattedCharSequence text, int color, int background, boolean seeThrough) {
        context.submitNodeCollector().order(WORLD_OVERLAY_ORDER).submitText(pose, x, y, text, false,
                seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                FULL_BRIGHT, color, background, 0);
    }

    /** Nothing to flush: submitted text is drawn with the rest of the frame. */
    public static void flushText(LevelRenderContext context) {
    }

    private static final int FULL_BRIGHT = 0x00F000F0;
}
