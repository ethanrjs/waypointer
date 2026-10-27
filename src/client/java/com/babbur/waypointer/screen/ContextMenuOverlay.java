package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.ROW_H;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.compat.InputCompat.KEY_DOWN;
import static com.babbur.waypointer.compat.InputCompat.KEY_ENTER;
import static com.babbur.waypointer.compat.InputCompat.KEY_ESCAPE;
import static com.babbur.waypointer.compat.InputCompat.KEY_KP_ENTER;
import static com.babbur.waypointer.compat.InputCompat.KEY_SPACE;
import static com.babbur.waypointer.compat.InputCompat.KEY_UP;

final class ContextMenuOverlay {

    record Item(Component label, String hint, boolean enabled, Runnable action, Icons.Icon icon) {
        Item(Component label, String hint, boolean enabled, Runnable action) {
            this(label, hint, enabled, action, null);
        }

        static Item of(Component label, String hint, Runnable action) {
            return new Item(label, hint, true, action);
        }

        static Item of(Component label, Runnable action) {
            return new Item(label, null, true, action);
        }

        static Item disabled(Component label, String hint) {
            return new Item(label, hint, false, null);
        }

        Item withIcon(Icons.Icon icon) {
            return new Item(label, hint, enabled, action, icon);
        }
    }

    private static final int ICON_COLUMN = Icons.SIZE + 4;

    static String hint(String id) {
        return Component.translatable("waypointer.screen.menu.hint." + id).getString();
    }

    static List<Item> withIcons(List<Item> items, Map<String, Icons.Icon> icons) {
        List<Item> out = new ArrayList<>(items.size());
        for (Item item : items) {
            Icons.Icon icon = item.label().getContents()
                    instanceof TranslatableContents translatable
                    ? icons.get(translatable.getKey()) : null;
            out.add(icon == null || item.icon() != null ? item : item.withIcon(icon));
        }
        return out;
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
    private boolean hintsHidden;

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
        hintsHidden = false;
        items = List.of();
        cursor = -1;
    }

    void openAt(Font font, List<Item> menuItems, double anchorX, double anchorY,
                int minX, int minY, int maxRight, int maxBottom) {
        int widest = fittedWidest(font, menuItems, maxRight - minX);
        openMeasured(menuItems, widest, anchorX, anchorY, minX, minY, maxRight, maxBottom);
        fitWidth(minX, maxRight);
    }

    void openUnder(Font font, List<Item> menuItems, int anchorX, int anchorBottomY, int anchorWidth,
                   int minX, int minY, int maxRight, int maxBottom) {
        openAt(font, menuItems, anchorX, anchorBottomY, minX, minY, maxRight, maxBottom);
        if (!open) return;
        width = Math.min(Math.max(width, anchorWidth), maxRight - minX);
        x = clampedX(anchorX, minX, maxRight, width);
    }

    static int naturalWidth(Font font, List<Item> menuItems) {
        return menuWidth(measureWidest(font, menuItems, true));
    }

    private int fittedWidest(Font font, List<Item> menuItems, int room) {
        int widest = measureWidest(font, menuItems, true);
        boolean hide = menuWidth(widest) > room;
        int result = hide ? measureWidest(font, menuItems, false) : widest;
        hintsHidden = hide;
        return result;
    }

    private void fitWidth(int minX, int maxRight) {
        if (!open || width <= maxRight - minX) return;
        width = Math.max(40, maxRight - minX);
        x = minX;
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

    void openAbove(Font font, List<Item> menuItems, int anchorX, int anchorBottomY,
                   int minX, int minY, int maxRight) {
        int widest = fittedWidest(font, menuItems, maxRight - minX);
        openAboveMeasured(menuItems, widest, anchorX, anchorBottomY, minX, minY, maxRight);
        fitWidth(minX, maxRight);
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

    private static int measureWidest(Font font, List<Item> menuItems, boolean withHints) {
        int widest = 0;
        if (menuItems == null) return widest;
        for (Item item : menuItems) {
            int w = font.width(item.label());
            if (withHints && item.hint() != null) w += GAP_SECTION + font.width(item.hint());
            widest = Math.max(widest, w);
        }
        return widest + (hasIcons(menuItems) ? ICON_COLUMN : 0);
    }

    private static boolean hasIcons(List<Item> menuItems) {
        for (Item item : menuItems) if (item.icon() != null) return true;
        return false;
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

    static int openY(double anchorY, int height, int minY, int maxBottom) {
        return Math.max(minY, Math.min((int) anchorY, maxBottom - height));
    }

    void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        if (!open) return;
        GuiTokens.drawTooltipPanel(g, x, y, x + width, y + height);
        int hovered = itemIndexAt(mouseX, mouseY);
        int labelX = x + GAP + (hasIcons(items) ? ICON_COLUMN : 0);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int rowY = y + 1 + i * ROW_H;
            boolean isCursor = i == cursor;
            boolean isHovered = i == hovered && item.enabled();
            if (isCursor) {
                g.fill(x + 1, rowY, x + width - 1, rowY + ROW_H, SELECTED);
            } else if (isHovered) {
                g.fill(x + 1, rowY, x + width - 1, rowY + ROW_H, HOVER);
            }
            if (isHovered) g.requestCursor(CursorTypes.POINTING_HAND);

            int labelColor = !item.enabled() ? TEXT_MUTED
                    : isHovered || isCursor ? TEXT : TEXT_DIM;
            int hintX = x + width - GAP;
            if (item.hint() != null && !hintsHidden) {
                int hintW = font.width(item.hint());
                g.text(font, item.hint(), hintX - hintW, rowY + 7, TEXT_MUTED, false);
                hintX -= hintW + GAP;
            }
            if (item.icon() != null) {
                int iconColor = !item.enabled() ? TEXT_MUTED : isHovered || isCursor ? ACCENT : TEXT_DIM;
                Icons.draw(g, item.icon(), x + GAP, rowY + (ROW_H - Icons.SIZE) / 2, iconColor);
            }
            int labelMaxW = Math.max(12, hintX - labelX);
            g.text(font, font.plainSubstrByWidth(item.label().getString(), labelMaxW),
                    labelX, rowY + 7, labelColor, false);
        }
    }

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
            case KEY_ESCAPE -> {
                close();
                return true;
            }
            case KEY_UP -> {
                moveCursor(-1);
                return true;
            }
            case KEY_DOWN -> {
                moveCursor(1);
                return true;
            }
            case KEY_ENTER, KEY_KP_ENTER, KEY_SPACE -> {
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
