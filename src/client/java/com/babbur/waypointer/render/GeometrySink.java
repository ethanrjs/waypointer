package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.Objects;

@FunctionalInterface
public interface GeometrySink {

    boolean submit(RenderType renderType, RenderSubmission.Geometry geometry);

    default void dynamic(Runnable body) {
        body.run();
    }

    default boolean staticGeometryNeeded() {
        return true;
    }

    default boolean retainsStaticGeometry() {
        return false;
    }

    static GeometrySink legacy(LevelRenderContext context, PoseStack poseStack) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(poseStack, "poseStack");
        return (renderType, geometry) ->
                RenderSubmission.submit(context, poseStack, renderType, geometry);
    }
}
