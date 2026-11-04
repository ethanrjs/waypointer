package com.babbur.waypointer.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

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
        boolean highlighted = active && isHoveredOrFocused();
        int color = !active ? GuiTokens.TEXT_MUTED : highlighted ? GuiTokens.ACCENT : GuiTokens.TEXT;
        g.fill(getX(), getY(), getX() + width, getY() + height,
                highlighted ? GuiTokens.HOVER : GuiTokens.SURFACE_SUBTLE);
        Icons.drawCentered(g, redo ? Icons.Icon.REDO : Icons.Icon.UNDO,
                getX(), getY(), width, height, color);
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
