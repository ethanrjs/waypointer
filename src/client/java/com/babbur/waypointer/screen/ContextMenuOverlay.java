package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.ROW_H;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

/**
 * Shared right-click / menu-key popup. One instance per screen; at most one open.
 * Every item shows its gesture accelerator as a right-aligned hint, so the menu
 * doubles as the discoverable reference for the hidden gestures it mirrors.
 */
final class ContextMenuOverlay {

    record Item(Component label, String hint, boolean enabled, Runnable action) {
        static Item of(Component label, String hint, Runnable action) {
            return new Item(label, hint, true, action);
        }

        static Item of(Component label, Runnable action) {
            return new Item(label, null, true, action);
        }

        static Item disabled(Component label, String hint) {
            return new Item(label, hint, false, null);
        }
    }

    private static final int MIN_W = 130;
    private static final int MAX_W = 250;

    private List<Item> items = List.of();
    private boolean open;
    private int x;
    private int y;
    private int width;
    private int height;
    private int cursor = -1;

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
        items = List.of();
        cursor = -1;
    }

    /** Opens beside a pointer position, flipped above when it would clip the bottom. */
    void openAt(Font font, List<Item> menuItems, double anchorX, double anchorY,
                int minX, int minY, int maxRight, int maxBottom) {
        openMeasured(menuItems, measureWidest(font, menuItems),
                anchorX, anchorY, minX, minY, maxRight, maxBottom);
    }

    void openMeasured(List<Item> menuItems, int widestItemWidth,
                      double anchorX, double anchorY,
                      int minX, int minY, int maxRight, int maxBottom) {
        if (menuItems == null || menuItems.isEmpty()) return;
        items = List.copyOf(menuItems);
        width = menuWidth(widestItemWidth);
        height = menuHeight(items.size());
        x = clampedX(anchorX, minX, maxRight, width);
        y = openY(anchorY, height, minY, maxBottom);
        cursor = -1;
        open = true;
    }

    /** Opens with its bottom edge sitting on {@code anchorBottomY} (footer menus). */
    void openAbove(Font font, List<Item> menuItems, int anchorX, int anchorBottomY,
                   int minX, int minY, int maxRight) {
        openAboveMeasured(menuItems, measureWidest(font, menuItems),
                anchorX, anchorBottomY, minX, minY, maxRight);
    }

    void openAboveMeasured(List<Item> menuItems, int widestItemWidth,
                           int anchorX, int anchorBottomY,
                           int minX, int minY, int maxRight) {
        if (menuItems == null || menuItems.isEmpty()) return;
        items = List.copyOf(menuItems);
        width = menuWidth(widestItemWidth);
        height = menuHeight(items.size());
        x = clampedX(anchorX, minX, maxRight, width);
        y = Math.max(minY, anchorBottomY - height);
        cursor = -1;
        open = true;
    }

    private static int measureWidest(Font font, List<Item> menuItems) {
        int widest = 0;
        if (menuItems == null) return widest;
        for (Item item : menuItems) {
            int w = font.width(item.label());
            if (item.hint() != null) w += GAP_SECTION + font.width(item.hint());
            widest = Math.max(widest, w);
        }
        return widest;
    }

    static int menuWidth(int widestItemWidth) {
        return Math.max(MIN_W, Math.min(MAX_W, widestItemWidth + GAP * 2));
    }

    static int menuHeight(int itemCount) {
        return itemCount * ROW_H + 2;
    }

    static int clampedX(double anchorX, int minX, int maxRight, int width) {
        return (int) Math.max(minX, Math.min(anchorX, maxRight - width));
    }

    /** Prefers opening below the anchor; flips above when it would clip the bottom. */
    static int openY(double anchorY, int height, int minY, int maxBottom) {
        int below = (int) anchorY;
        return below + height <= maxBottom
                ? Math.max(minY, below)
                : Math.max(minY, below - height);
    }

    void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        if (!open) return;
        GuiTokens.drawTooltipPanel(g, x, y, x + width, y + height);
        int hovered = itemIndexAt(mouseX, mouseY);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int rowY = y + 1 + i * ROW_H;
            boolean isCursor = i == cursor;
            boolean isHovered = i == hovered && item.enabled();
            if (isCursor) {
                g.fill(x + 1, rowY, x + width - 1, rowY + ROW_H, SELECTED);
                g.fill(x + 1, rowY, x + 3, rowY + ROW_H, ACCENT);
            } else if (isHovered) {
                g.fill(x + 1, rowY, x + width - 1, rowY + ROW_H, HOVER);
            }
            if (isHovered) g.requestCursor(CursorTypes.POINTING_HAND);

            int labelColor = !item.enabled() ? TEXT_MUTED
                    : isHovered || isCursor ? TEXT : TEXT_DIM;
            int hintX = x + width - GAP;
            if (item.hint() != null) {
                int hintW = font.width(item.hint());
                g.text(font, item.hint(), hintX - hintW, rowY + 7, TEXT_MUTED, false);
                hintX -= hintW + GAP;
            }
            int labelMaxW = Math.max(12, hintX - (x + GAP));
            g.text(font, font.plainSubstrByWidth(item.label().getString(), labelMaxW),
                    x + GAP, rowY + 7, labelColor, false);
        }
    }

    /** Consumes every click while open; outside clicks just dismiss. */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (!open) return false;
        int index = itemIndexAt(mouseX, mouseY);
        if (index < 0) {
            close();
            return true;
        }
        Item item = items.get(index);
        if (!item.enabled()) return true;
        Runnable action = item.action();
        close();
        action.run();
        return true;
    }

    boolean keyPressed(int keyCode) {
        if (!open) return false;
        switch (keyCode) {
            case GLFW_KEY_ESCAPE -> {
                close();
                return true;
            }
            case GLFW_KEY_UP -> {
                moveCursor(-1);
                return true;
            }
            case GLFW_KEY_DOWN -> {
                moveCursor(1);
                return true;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER, GLFW_KEY_SPACE -> {
                if (cursor >= 0 && cursor < items.size() && items.get(cursor).enabled()) {
                    Runnable action = items.get(cursor).action();
                    close();
                    action.run();
                } else {
                    close();
                }
                return true;
            }
            default -> {
                close();
                return false;
            }
        }
    }

    private void moveCursor(int delta) {
        if (items.isEmpty()) return;
        int next = cursor;
        for (int step = 0; step < items.size(); step++) {
            next = next < 0
                    ? (delta > 0 ? 0 : items.size() - 1)
                    : Math.floorMod(next + delta, items.size());
            if (items.get(next).enabled()) {
                cursor = next;
                return;
            }
        }
    }

    private int itemIndexAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + width) return -1;
        int index = (int) ((mouseY - y - 1) / ROW_H);
        return mouseY >= y + 1 && index >= 0 && index < items.size() ? index : -1;
    }
}
