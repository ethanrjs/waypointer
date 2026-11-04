package com.babbur.waypointer.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;

final class CatalogFilterChip extends AbstractButton {
    static final int HEIGHT = 14;
    private static final int PAD = 6;
    private static final int ICON_W = 8;

    private final boolean on;
    private final int color;
    private final Icons.Icon icon;
    private final Runnable onToggle;

    CatalogFilterChip(int x, int y, Component label, boolean on, int color, Icons.Icon icon, Runnable onToggle) {
        super(x, y, width(Minecraft.getInstance().font, label, icon), HEIGHT, label);
        this.on = on;
        this.color = color;
        this.icon = icon;
        this.onToggle = onToggle;
    }

    static int width(Font font, Component label, Icons.Icon icon) {
        return PAD * 2 + font.width(label) + (icon == null ? 0 : ICON_W + 4);
    }

    boolean on() {
        return on;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onToggle.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + getWidth();
        int y2 = y1 + getHeight();
        int edge = on ? color : isHoveredOrFocused() ? 0x60FFFFFF : BORDER;
        g.fill(x1 + 1, y1, x2 - 1, y2, on ? (color & 0x00FFFFFF) | 0x40000000 : isHoveredOrFocused() ? HOVER : 0);
        g.fill(x1 + 1, y1, x2 - 1, y1 + 1, edge);
        g.fill(x1 + 1, y2 - 1, x2 - 1, y2, edge);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, edge);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, edge);
        Font font = Minecraft.getInstance().font;
        int textX = x1 + PAD;
        int tint = on ? TEXT : TEXT_DIM;
        if (icon != null) {
            Icons.drawAt(g, icon, textX + 4, y1 + 7, on ? TEXT : color);
            textX += ICON_W + 4;
        }
        g.text(font, getMessage(), textX, y1 + 3, tint, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
