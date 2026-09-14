package com.babbur.waypointer.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Icon-only undo/redo control; the accessible name also supplies its tooltip. */
final class HistoryButton extends AbstractButton {
    private final boolean redo;
    private final Runnable action;

    HistoryButton(int x, int y, boolean redo, Runnable action) {
        super(x, y, 22, 20, Component.translatableWithFallback(
                redo ? "waypointer.edit.redo" : "waypointer.edit.undo",
                redo ? "Redo (Ctrl+Y)" : "Undo (Ctrl+Z)"));
        this.redo = redo;
        this.action = action;
        setTooltip(Tooltip.create(getMessage()));
    }

    void extractOverPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        extractWidgetRenderState(g, mouseX, mouseY, partial);
    }

    @Override public void onPress(InputWithModifiers input) { action.run(); }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int color = !active ? GuiTokens.TEXT_MUTED : GuiTokens.TEXT;
        g.fill(getX(), getY(), getX() + width, getY() + height,
                active && isHoveredOrFocused() ? GuiTokens.HOVER : GuiTokens.SURFACE_SUBTLE);
        // Mirrored bent arrow, drawn as pixels rather than a font-dependent symbol.
        for (int i = 0; i < 10; i++) pixel(g, 5 + i, 7, color);
        for (int i = 0; i < 7; i++) pixel(g, 14, 7 + i, color);
        for (int i = 0; i < 6; i++) pixel(g, 9 + i, 13, color);
        for (int i = 0; i < 4; i++) {
            pixel(g, 5 + i, 7 - i, color);
            pixel(g, 5 + i, 7 + i, color);
        }
    }

    private void pixel(GuiGraphicsExtractor g, int x, int y, int color) {
        int px = getX() + (redo ? 20 - x : x);
        g.fill(px, getY() + y, px + 1, getY() + y + 1, color);
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
