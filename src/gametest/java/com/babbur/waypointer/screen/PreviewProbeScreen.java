package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class PreviewProbeScreen extends Screen {
    private final CatalogRoutePreview preview;

    public PreviewProbeScreen(WaypointGroup route) {
        super(Component.literal("preview probe"));
        this.preview = CatalogRoutePreview.of(List.of(route));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        preview.render(g, font, 40, 40, 40 + 300, 40 + 220, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
