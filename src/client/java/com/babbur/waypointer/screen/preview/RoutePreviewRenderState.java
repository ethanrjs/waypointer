package com.babbur.waypointer.screen.preview;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** Immutable GUI-extraction state for one route-preview frame. */
public record RoutePreviewRenderState(
        RoutePreviewScene scene,
        float yawRadians,
        int hoveredWaypointIndex,
        int x0,
        int y0,
        int x1,
        int y1,
        float scale,
        int guiScale,
        ScreenRectangle scissorArea,
        ScreenRectangle bounds
) implements PictureInPictureRenderState {

    public static RoutePreviewRenderState create(
            RoutePreviewScene scene,
            float yawRadians,
            int hoveredWaypointIndex,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            ScreenRectangle scissorArea) {
        return create(scene, yawRadians, hoveredWaypointIndex,
                x0, y0, x1, y1, scale, 1, scissorArea);
    }

    public static RoutePreviewRenderState create(
            RoutePreviewScene scene,
            float yawRadians,
            int hoveredWaypointIndex,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            int guiScale,
            ScreenRectangle scissorArea) {
        return new RoutePreviewRenderState(
                scene, yawRadians, hoveredWaypointIndex,
                x0, y0, x1, y1, scale, Math.max(1, guiScale), scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}
