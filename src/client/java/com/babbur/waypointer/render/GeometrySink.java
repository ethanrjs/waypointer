package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.Objects;

/** Destination for world-overlay geometry. */
@FunctionalInterface
public interface GeometrySink {

    boolean submit(RenderType renderType, RenderSubmission.Geometry geometry);

    /** Marks geometry that must be rebuilt each frame. */
    default void dynamic(Runnable body) {
        body.run();
    }

    /** Whether static geometry callbacks must run this frame. */
    default boolean staticGeometryNeeded() {
        return true;
    }

    /** Whether static geometry must remain valid as the camera turns in place. */
    default boolean retainsStaticGeometry() {
        return false;
    }

    /** Returns the immediate-mode sink. */
    static GeometrySink legacy(LevelRenderContext context, PoseStack poseStack) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(poseStack, "poseStack");
        return (renderType, geometry) ->
                RenderSubmission.submit(context, poseStack, renderType, geometry);
    }
}
