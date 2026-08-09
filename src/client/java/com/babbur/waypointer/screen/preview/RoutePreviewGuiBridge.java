package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class RoutePreviewGuiBridge {

    private RoutePreviewGuiBridge() {}

    public static void submit(GuiGraphicsExtractor graphics, RoutePreviewRenderState state) {
        try {
            graphics.guiRenderState.addPicturesInPictureState(state);
        } catch (RuntimeException allocationOrSubmissionFailure) {
            state.availability().markUnavailable();
            Waypointer.LOGGER.error("Could not submit route preview GUI state",
                    allocationOrSubmissionFailure);
        }
    }
}
