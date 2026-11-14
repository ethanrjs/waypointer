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

    /** Whether a static scope (a route, or a route's connector lines) is being rebuilt this frame. */
    default boolean staticGeometryNeeded(Object scope) {
        return staticGeometryNeeded();
    }

    /** Runs {@code body}, which emits one scope's static geometry, so a retained sink can keep it apart. */
    default void staticGroup(Object scope, Runnable body) {
        body.run();
    }

    static GeometrySink legacy(LevelRenderContext context, PoseStack poseStack) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(poseStack, "poseStack");
        return (renderType, geometry) ->
                RenderSubmission.submit(context, poseStack, renderType, geometry);
    }
}
