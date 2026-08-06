package com.babbur.waypointer.screen.preview;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** One access-widened GUI submission boundary; no per-frame reflection. */
public final class RoutePreviewGuiBridge {

    private RoutePreviewGuiBridge() {}

    public static void submit(GuiGraphicsExtractor graphics, RoutePreviewRenderState state) {
        try {
            graphics.guiRenderState.addPicturesInPictureState(state);
        } catch (RuntimeException allocationOrSubmissionFailure) {
            RoutePreviewAvailability.markUnavailable();
        }
    }
}
