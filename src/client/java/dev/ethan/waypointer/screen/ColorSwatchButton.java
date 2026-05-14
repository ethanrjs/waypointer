package dev.ethan.waypointer.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * Square-ish button whose primary visual IS the colour it represents -- the
 * fill spans the full button, with centered text so the control still reads
 * as an action rather than a passive chip.
 *
 * <p>Replaces the previous text-only Button that read e.g. {@code "Start #F6FBFC"}.
 * Players picking a gradient want to see the actual colour alongside the label;
 * a hex string is an abstraction they have to parse.
 *
 * <p>Hover and focus are signalled with a brighter outline ring. The caption
 * auto-flips between dark and light to stay legible on both ends of the
 * luminance spectrum.
 */
public final class ColorSwatchButton extends AbstractButton {

    private static final int BORDER_IDLE    = 0xFF000000;
    private static final int BORDER_HOVER   = 0xFFFFFFFF;
    private static final int BORDER_FOCUS   = 0xFFFFD35A;
    private static final int INNER_SHADOW   = 0x66000000;
    private static final int INNER_HIGHLIGHT = 0x33FFFFFF;
    private static final int DISABLED_OVERLAY = 0xAA000000;

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

    @Override
    public void onPress(InputWithModifiers input) {
        onPress.run();
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + getWidth();
        int y2 = y1 + getHeight();

        int border = active && isFocused()
                ? BORDER_FOCUS
                : active && isHoveredOrFocused() ? BORDER_HOVER : BORDER_IDLE;

        g.fill(x1, y1, x2, y2, border);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF000000 | rgb);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, INNER_HIGHLIGHT);
        g.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, INNER_SHADOW);
        if (!active) {
            g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, DISABLED_OVERLAY);
        }

        // Caption with a dark 1px drop shadow offset by (+1, +1). Drop shadow is
        // enough contrast for the bright-swatch case without needing to pick a
        // fully inverted text colour per swatch.
        int textColor = !active ? 0xFF9A9A9A : isLightColor(rgb) ? 0xFF101216 : 0xFFE6E9EC;
        int shadow = textColor == 0xFF101216 ? 0x40FFFFFF : 0x80000000;
        var font = Minecraft.getInstance().font;
        String clipped = font.plainSubstrByWidth(caption, getWidth() - 8);
        int textX = x1 + (getWidth() - font.width(clipped)) / 2;
        int textY = y1 + (getHeight() - 8) / 2;
        g.drawString(font, clipped, textX + 1, textY + 1, shadow, false);
        g.drawString(font, clipped, textX, textY, textColor, false);
    }

    /** Cheap perceptual-luminance threshold to flip caption colour for contrast. */
    private static boolean isLightColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >>  8) & 0xFF;
        int b =  rgb        & 0xFF;
        // Rec. 601 luma; good enough for "should my text be black or white?"
        int luma = (r * 299 + g * 587 + b * 114) / 1000;
        return luma > 150;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
