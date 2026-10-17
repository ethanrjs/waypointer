package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.RenderHelpers;
import net.minecraft.client.Minecraft;
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

    /** Path width in GUI pixels. */
    private static final double LINE_WIDTH = 1.25;

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

    /**
     * From the catalog's light preview. When the server thinned the points, editor numbers can't
     * be rebuilt, so hovering shows only positions.
     */
    static CatalogRoutePreview of(com.babbur.waypointer.catalog.CatalogRoutePreviewData data) {
        List<List<Point>> paths = new ArrayList<>();
        boolean roomPack = false;
        for (var group : data.groups()) {
            roomPack |= group.dungeon();
            List<Point> path = new ArrayList<>(group.points().size());
            int main = 0;
            int child = 0;
            for (var point : group.points()) {
                if (point.subwaypoint() && main > 0) child++;
                else {
                    main++;
                    child = 0;
                }
                String label = data.truncated() ? "" : "#" + main + (child > 0 ? "." + child : "");
                path.add(new Point(point.x(), point.y(), point.z(), 0xFF000000 | point.rgb(),
                        point.subwaypoint(), label, ""));
            }
            if (!path.isEmpty()) paths.add(List.copyOf(path));
        }
        return new CatalogRoutePreview(List.copyOf(paths), roomPack && data.groups().size() > 1, data.groups().size());
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

        // Smooth lines and round points at sub-pixel positions, so the map stays sharp at any GUI scale.
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        Point hovered = null;
        double hoveredDistance = 26;
        double hoveredX = 0, hoveredY = 0;
        g.enableScissor(x1 + 1, y1 + 1, x2 - 1, y2 - 1);
        for (List<Point> path : paths) {
            double px = 0, py = 0;
            int previousColor = 0;
            for (int i = 0; i < path.size(); i++) {
                Point point = path.get(i);
                double sx = centerX + (point.x() - midX) * scale;
                double sy = centerY + (point.z() - midZ) * scale;
                if (i > 0) {
                    RenderHelpers.drawScreenLine(g, px, py, sx, sy, (previousColor & 0xFFFFFF) | 0xB0000000,
                            LINE_WIDTH * guiScale, true);
                }
                px = sx;
                py = sy;
                previousColor = point.color();
            }
            for (int i = path.size() - 1; i >= 0; i--) {
                Point point = path.get(i);
                double sx = centerX + (point.x() - midX) * scale;
                double sy = centerY + (point.z() - midZ) * scale;
                double dx = mouseX - sx;
                double dy = mouseY - sy;
                if (dx * dx + dy * dy < hoveredDistance) {
                    hoveredDistance = dx * dx + dy * dy;
                    hovered = point;
                    hoveredX = sx;
                    hoveredY = sy;
                }
                dot(g, sx, sy, point, i == 0, guiScale);
            }
        }
        if (hovered != null) {
            square(g, hoveredX, hoveredY, 7, TEXT, guiScale);
            square(g, hoveredX, hoveredY, 5, hovered.color(), guiScale);
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

    /** A waypoint: a colored square in a dark frame, a white frame for the start, smaller for subwaypoints. */
    private static void dot(GuiGraphicsExtractor g, double x, double y, Point point, boolean first, double guiScale) {
        if (point.subwaypoint()) {
            square(g, x, y, 3, 0xE0101216, guiScale);
            square(g, x, y, 1.5, point.color(), guiScale);
            return;
        }
        square(g, x, y, 5, first ? TEXT : 0xE0101216, guiScale);
        square(g, x, y, 3, point.color(), guiScale);
    }

    /** A square of {@code side} GUI pixels centred on (x, y), snapped to whole screen pixels so it stays crisp. */
    private static void square(GuiGraphicsExtractor g, double x, double y, double side, int argb, double guiScale) {
        int pixels = Math.max(1, (int) Math.round(side * guiScale));
        double left = Math.round(x * guiScale - pixels / 2.0) / guiScale;
        double top = Math.round(y * guiScale - pixels / 2.0) / guiScale;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) left, (float) top);
        pose.scale((float) (1 / guiScale), (float) (1 / guiScale));
        g.fill(0, 0, pixels, pixels, argb);
        pose.popMatrix();
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

    /** Tooltip lines for a waypoint on the map. */
    static List<Component> tooltip(Point point) {
        List<Component> lines = new ArrayList<>();
        String heading = (point.label() + "  " + (point.name() == null ? "" : point.name())).trim();
        if (!heading.isEmpty()) lines.add(Component.literal(heading));
        lines.add(Component.literal(point.x() + ", " + point.y() + ", " + point.z()).withStyle(
                heading.isEmpty() ? net.minecraft.ChatFormatting.WHITE : net.minecraft.ChatFormatting.GRAY));
        return lines;
    }
}
