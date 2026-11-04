package com.babbur.waypointer.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.babbur.waypointer.config.WaypointerConfig;

public final class WaypointLabelAppearance {
    private WaypointLabelAppearance() {}

    public static boolean focused(double x, double y, double centerX, double centerY) {
        return Math.abs(x - centerX) <= 16 && Math.abs(y - centerY) <= 16;
    }

    public static boolean showDetail(WaypointerConfig config, double distanceSquared, boolean focused) {
        return !config.labelDetailOnDemand() || focused
                || distanceSquared <= config.labelDetailDistance() * config.labelDetailDistance();
    }

    public static int priority(boolean current, boolean focused) {
        return (current ? 0 : 2) + (focused ? 0 : 1);
    }

    public static boolean nearScreen(double x, double y, int width, int height) {
        int margin = WaypointWorldRenderer.SCREEN_CULL_MARGIN;
        return x >= -margin && x <= width + margin && y >= -margin && y <= height + margin;
    }

    public static double labelWidth(int textWidth, boolean hasRows, boolean icon, float scale) {
        return Math.max(hasRows ? textWidth + 2 * WaypointWorldRenderer.BACKDROP_PAD_X : 0, icon ? 9 : 0) * scale;
    }

    public static boolean overlaps(double left, double top, double right, double bottom,
                                   double otherLeft, double otherTop, double otherRight, double otherBottom) {
        return left < otherRight && right > otherLeft && top < otherBottom && bottom > otherTop;
    }

    public static void drawIcon(GuiGraphicsExtractor g, String icon, double cx, double cy, float scale, int argb) {
        if (icon == null || "NONE".equals(icon)) return;
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        g.pose().scale(scale, scale);
        for (int y = -4; y <= 4; y++) {
            for (int x = -4; x <= 4; x++) {
                if (iconPixel(icon, x, y)) g.fill(x, y, x + 1, y + 1, argb);
            }
        }
        g.pose().popMatrix();
    }

    public static boolean iconPixel(String icon, int x, int y) {
        if (icon.startsWith(WaypointerConfig.CUSTOM_ICON_PREFIX)) {
            int size = WaypointerConfig.CUSTOM_ICON_SIZE, half = size / 2;
            int cell = WaypointerConfig.CUSTOM_ICON_PREFIX.length() + (y + half) * size + (x + half);
            return cell < icon.length() && icon.charAt(cell) == '1';
        }
        return switch (icon) {
            case "DIAMOND" -> Math.abs(x) + Math.abs(y) == 4;
            case "CIRCLE" -> x * x + y * y >= 10 && x * x + y * y <= 20;
            case "SQUARE" -> Math.abs(x) == 4 || Math.abs(y) == 4;
            case "STAR" -> x == 0 || y == 0 || Math.abs(x) == Math.abs(y) && Math.abs(x) <= 2;
            default -> false;
        };
    }
}
