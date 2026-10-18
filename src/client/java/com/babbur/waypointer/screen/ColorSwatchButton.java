package com.babbur.waypointer.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

public final class ColorSwatchButton extends AbstractButton {

    private static final int BORDER_IDLE    = 0xFF000000;
    private static final int BORDER_HOVER   = 0xFFFFFFFF;
    private static final int BORDER_FOCUS   = 0xFFFFD35A;
    private static final int INNER_SHADOW   = 0x66000000;
    private static final int INNER_HIGHLIGHT = 0x33FFFFFF;
    private static final int DISABLED_OVERLAY = 0xAA000000;

    private static final int WIDE_ENOUGH_FOR_LABEL = 40;
    private static final int CHIP = 12;

    private final Runnable onPress;
    private final String caption;
    private int rgb;

    public ColorSwatchButton(int x, int y, int w, int h, String caption, int rgb, Runnable onPress) {
        super(x, y, w, h, Component.literal(caption));
        this.caption = caption;
        this.rgb = rgb & 0xFFFFFF;
        this.onPress = onPress;
    }

    public void setColor(int rgb) {
        this.rgb = rgb & 0xFFFFFF;
    }

    public int getColor() {
        return rgb;
    }

    public void extractOverPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        extractWidgetRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onPress.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x1 = getX();
        int y1 = getY();
        boolean highlighted = active && isHoveredOrFocused();
        if (getWidth() < WIDE_ENOUGH_FOR_LABEL) {
            renderPlainSwatch(g, x1, y1, x1 + getWidth(), y1 + getHeight());
            return;
        }
        GuiTokens.drawControlFrame(g, x1, y1, getWidth(), getHeight(), active, highlighted, isFocused());
        int chip = Math.min(CHIP, getHeight() - 6);
        var font = Minecraft.getInstance().font;
        String label = caption == null || caption.isBlank() ? hex(rgb) : caption;
        String clipped = font.plainSubstrByWidth(label, Math.max(0, getWidth() - 8 - chip - 4));
        int groupW = chip + 4 + font.width(clipped);
        int chipX = Math.max(x1 + 4, x1 + (getWidth() - groupW) / 2);
        int chipY = y1 + (getHeight() - chip) / 2;
        g.fill(chipX, chipY, chipX + chip, chipY + chip, active ? 0xFF000000 : 0xFF3A3F45);
        g.fill(chipX + 1, chipY + 1, chipX + chip - 1, chipY + chip - 1, 0xFF000000 | rgb);
        if (!active) g.fill(chipX + 1, chipY + 1, chipX + chip - 1, chipY + chip - 1, DISABLED_OVERLAY);
        g.text(font, clipped, chipX + chip + 4, GuiTokens.opticalTextY(y1, getHeight()),
                active ? GuiTokens.TEXT : GuiTokens.TEXT_MUTED, false);
    }

    private void renderPlainSwatch(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int border = active && isFocused()
                ? BORDER_FOCUS
                : active && isHoveredOrFocused() ? BORDER_HOVER : BORDER_IDLE;
        g.fill(x1, y1, x2, y2, border);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF000000 | rgb);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, INNER_HIGHLIGHT);
        g.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, INNER_SHADOW);
        if (!active) g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, DISABLED_OVERLAY);
    }

    static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }


    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
