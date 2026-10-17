package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;

/**
 * A top-down map of a community route: its waypoints seen from above (north up), joined in route
 * order, in the route's own colors. Dungeon room packs use room-relative coordinates that would
 * overlap, so they get a short summary instead of a map.
 */
final class CatalogRoutePreview {

    record Point(int x, int y, int z, int color, boolean subwaypoint, String label, String name) {}

    private final List<List<Point>> paths;
    private final boolean roomPack;
    private final int roomCount;
    private final int waypointCount;
    private final int minX, maxX, minY, maxY, minZ, maxZ;

    private CatalogRoutePreview(List<List<Point>> paths, boolean roomPack, int roomCount) {
        this.paths = paths;
        this.roomPack = roomPack;
        this.roomCount = roomCount;
        int count = 0;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (List<Point> path : paths) {
            for (Point point : path) {
                count++;
                x0 = Math.min(x0, point.x());
                x1 = Math.max(x1, point.x());
                y0 = Math.min(y0, point.y());
                y1 = Math.max(y1, point.y());
                z0 = Math.min(z0, point.z());
                z1 = Math.max(z1, point.z());
            }
        }
        this.waypointCount = count;
        this.minX = x0;
        this.maxX = x1;
        this.minY = y0;
        this.maxY = y1;
        this.minZ = z0;
        this.maxZ = z1;
    }

    static CatalogRoutePreview of(List<WaypointGroup> groups) {
        List<List<Point>> paths = new ArrayList<>();
        boolean roomPack = false;
        for (WaypointGroup group : groups) {
            roomPack |= group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
            List<Point> path = new ArrayList<>(group.size());
            for (int i = 0; i < group.size(); i++) {
                Waypoint waypoint = group.get(i);
                path.add(new Point(waypoint.x(), waypoint.y(), waypoint.z(),
                        0xFF000000 | waypoint.color(), group.isSubwaypoint(i),
                        group.displayIndexLabel(i), waypoint.name()));
            }
            if (!path.isEmpty()) paths.add(List.copyOf(path));
        }
        return new CatalogRoutePreview(List.copyOf(paths), roomPack && groups.size() > 1, groups.size());
    }

    boolean isEmpty() {
        return waypointCount == 0;
    }

    /** Width and depth of the area the route covers, in blocks. */
    int spanX() { return isEmpty() ? 0 : maxX - minX + 1; }

    int spanZ() { return isEmpty() ? 0 : maxZ - minZ + 1; }

    /**
     * Draws the map into the box and returns the waypoint under the mouse, if any.
     */
    Point render(GuiGraphicsExtractor g, Font font, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, 0x40000000);
        outline(g, x1, y1, x2, y2, BORDER);
        if (roomPack || isEmpty()) {
            Component summary = roomPack
                    ? Component.translatable("waypointer.screen.route_catalog.preview.rooms",
                            roomCount, waypointCount)
                    : Component.translatable("waypointer.screen.route_catalog.preview.empty");
            String text = font.plainSubstrByWidth(summary.getString(), Math.max(10, x2 - x1 - GAP * 2));
            g.text(font, text, (x1 + x2 - font.width(text)) / 2, (y1 + y2) / 2 - 4, TEXT_MUTED, false);
            return null;
        }
        drawGrid(g, x1, y1, x2, y2);

        int pad = 8;
        double spanX = Math.max(1, maxX - minX);
        double spanZ = Math.max(1, maxZ - minZ);
        double scale = Math.min((x2 - x1 - pad * 2) / spanX, (y2 - y1 - pad * 2 - 10) / spanZ);
        scale = Math.min(scale, 6.0);
        double centerX = (x1 + x2) / 2.0;
        double centerY = (y1 + y2 - 10) / 2.0;
        double midX = (minX + maxX) / 2.0;
        double midZ = (minZ + maxZ) / 2.0;

        Point hovered = null;
        int hoveredDistance = 26;
        g.enableScissor(x1 + 1, y1 + 1, x2 - 1, y2 - 1);
        for (List<Point> path : paths) {
            int[] previous = null;
            for (Point point : path) {
                int sx = (int) Math.round(centerX + (point.x() - midX) * scale);
                int sy = (int) Math.round(centerY + (point.z() - midZ) * scale);
                if (previous != null) line(g, previous[0], previous[1], sx, sy, (previous[2] & 0xFFFFFF) | 0x90000000);
                previous = new int[]{sx, sy, point.color()};
            }
            for (int i = path.size() - 1; i >= 0; i--) {
                Point point = path.get(i);
                int sx = (int) Math.round(centerX + (point.x() - midX) * scale);
                int sy = (int) Math.round(centerY + (point.z() - midZ) * scale);
                int dx = mouseX - sx;
                int dy = mouseY - sy;
                if (dx * dx + dy * dy < hoveredDistance) {
                    hoveredDistance = dx * dx + dy * dy;
                    hovered = point;
                }
                dot(g, sx, sy, point, i == 0);
            }
        }
        g.disableScissor();

        String extent = Component.translatable("waypointer.screen.route_catalog.preview.extent",
                spanX(), spanZ()).getString();
        if (maxY > minY) {
            extent += "  ·  " + Component.translatable("waypointer.screen.route_catalog.preview.height",
                    minY, maxY).getString();
        }
        g.text(font, font.plainSubstrByWidth(extent, x2 - x1 - pad), x1 + 4, y2 - 11, TEXT_MUTED, false);
        String north = "N";
        g.text(font, north, x2 - 4 - font.width(north), y1 + 4, TEXT_MUTED, false);
        return hovered;
    }

    private static void dot(GuiGraphicsExtractor g, int x, int y, Point point, boolean first) {
        if (point.subwaypoint()) {
            g.fill(x - 1, y - 1, x + 1, y + 1, 0xE0000000);
            g.fill(x, y, x + 1, y + 1, point.color());
            return;
        }
        int ring = first ? TEXT : 0xE0000000;
        g.fill(x - 2, y - 2, x + 3, y + 3, ring);
        g.fill(x - 1, y - 1, x + 2, y + 2, point.color());
    }

    private static void drawGrid(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int step = 16;
        for (int x = x1 + step; x < x2 - 1; x += step) g.fill(x, y1 + 1, x + 1, y2 - 1, 0x0CFFFFFF);
        for (int y = y1 + step; y < y2 - 1; y += step) g.fill(x1 + 1, y, x2 - 1, y + 1, 0x0CFFFFFF);
    }

    private static void outline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    /** Bresenham line in GUI pixels. */
    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = 0;
        while (guard++ < 4096) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Tooltip lines for a waypoint on the map. */
    static List<Component> tooltip(Point point) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(point.name() == null || point.name().isBlank()
                ? point.label() : point.label() + "  " + point.name()));
        lines.add(Component.literal(point.x() + ", " + point.y() + ", " + point.z()).withStyle(
                net.minecraft.ChatFormatting.GRAY));
        return lines;
    }
}
