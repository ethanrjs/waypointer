package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.HappySnowmanSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RoutePreviewScene {

    public static final int SIMPLIFIED_THRESHOLD = 1_000;

    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) {
        public double centerX() { return (minX + maxX) * 0.5; }
        public double centerY() { return (minY + maxY) * 0.5; }
        public double centerZ() { return (minZ + maxZ) * 0.5; }
        public double width() { return maxX - minX; }
        public double height() { return maxY - minY; }
        public double depth() { return maxZ - minZ; }
        public Box scaled(double scale) {
            double safeScale = Double.isFinite(scale) ? Math.clamp(scale, 0.25, 3.0) : 1.0;
            double cx = centerX(), cy = centerY(), cz = centerZ();
            return new Box(
                    cx + (minX - cx) * safeScale,
                    cy + (minY - cy) * safeScale,
                    cz + (minZ - cz) * safeScale,
                    cx + (maxX - cx) * safeScale,
                    cy + (maxY - cy) * safeScale,
                    cz + (maxZ - cz) * safeScale);
        }
    }

    public record Marker(int sourceIndex, String name, String displayIndex,
                         String sequenceText, String coordinateText,
                         int color, int flags, boolean subwaypoint,
                         boolean small, int duplicateCount, Box box) {
        public String displayName() {
            return name == null || name.isBlank() ? "Waypoint #" + (sourceIndex + 1) : name;
        }
    }

    public record Connector(double x1, double y1, double z1,
                            double x2, double y2, double z2) {}

    private record PositionKey(int x, int y, int z) {}

    private final String routeId;
    private final String routeName;
    private final boolean roomLocal;
    private final List<Marker> markers;
    private final List<Connector> connectors;
    private final WaypointPaint paint;
    private final RoutePreviewPaintResource.Entry paintResource;
    private final boolean paintUnavailable;
    private final WaypointerConfig.BoxStyle boxStyle;
    private final float opacity;
    private final float outlineOpacity;
    private final float outlineWidth;
    private final boolean outlineMatchesWaypointColor;
    private final int outlineColor;
    private final int routeLineColor;
    private final double centerX;
    private final double centerY;
    private final double centerZ;

    private RoutePreviewScene(String routeId, String routeName, boolean roomLocal,
                              List<Marker> markers, List<Connector> connectors,
                              WaypointPaint paint, RoutePreviewPaintResource.Entry paintResource,
                              boolean paintUnavailable,
                              WaypointerConfig.BoxStyle boxStyle,
                              float opacity, float outlineOpacity, float outlineWidth,
                              boolean outlineMatchesWaypointColor, int outlineColor,
                              int routeLineColor,
                              double centerX, double centerY, double centerZ) {
        this.routeId = routeId;
        this.routeName = routeName;
        this.roomLocal = roomLocal;
        this.markers = List.copyOf(markers);
        this.connectors = List.copyOf(connectors);
        this.paint = paint;
        this.paintResource = paintResource;
        this.paintUnavailable = paintUnavailable;
        this.boxStyle = boxStyle;
        this.opacity = opacity;
        this.outlineOpacity = outlineOpacity;
        this.outlineWidth = outlineWidth;
        this.outlineMatchesWaypointColor = outlineMatchesWaypointColor;
        this.outlineColor = outlineColor;
        this.routeLineColor = routeLineColor;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
    }

    public static RoutePreviewScene build(WaypointGroup group, WaypointerConfig config,
                                          ClientLevel loadedLevel) {
        if (group == null) return empty();

        List<AABB> worldBoxes = new ArrayList<>(group.size());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (Waypoint waypoint : group.waypoints()) {
            ClientLevel shapeLevel = loadedLevel;
            if (shapeLevel != null
                    && !shapeLevel.hasChunk(waypoint.x() >> 4, waypoint.z() >> 4)) {
                shapeLevel = null;
            }
            AABB box = WaypointRenderer.waypointBoxBounds(shapeLevel, waypoint);
            worldBoxes.add(box);
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }

        double pivotX = worldBoxes.isEmpty() ? 0.0 : (minX + maxX) * 0.5;
        double pivotY = worldBoxes.isEmpty() ? 0.0 : (minY + maxY) * 0.5;
        double pivotZ = worldBoxes.isEmpty() ? 0.0 : (minZ + maxZ) * 0.5;
        boolean roomLocal = group.routeKind() == WaypointGroup.RouteKind.DUNGEON;

        Map<PositionKey, Integer> duplicateCounts = new HashMap<>();
        for (Waypoint waypoint : group.waypoints()) {
            PositionKey key = new PositionKey(
                    waypoint.preciseX(), waypoint.preciseY(), waypoint.preciseZ());
            duplicateCounts.merge(key, 1, Integer::sum);
        }

        List<Marker> markers = new ArrayList<>(group.size());
        int mainCount = group.mainWaypointCount();
        int mainOrdinal = 0;
        int childOrdinal = 0;
        for (int i = 0; i < group.size(); i++) {
            Waypoint waypoint = group.get(i);
            if (waypoint.isSubwaypoint()) {
                if (mainOrdinal > 0) childOrdinal++;
            } else {
                mainOrdinal++;
                childOrdinal = 0;
            }
            AABB worldBox = worldBoxes.get(i);
            Box localBox = new Box(
                    worldBox.minX - pivotX, worldBox.minY - pivotY, worldBox.minZ - pivotZ,
                    worldBox.maxX - pivotX, worldBox.maxY - pivotY, worldBox.maxZ - pivotZ)
                    .scaled(config.waypointMarkerScale());
            boolean small = waypoint.isSubwaypoint()
                    && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
            PositionKey key = new PositionKey(
                    waypoint.preciseX(), waypoint.preciseY(), waypoint.preciseZ());
            markers.add(new Marker(
                    i,
                    waypoint.name(),
                    mainOrdinal == 0 ? "#" + (i + 1)
                            : "#" + mainOrdinal + (waypoint.isSubwaypoint() ? "." + childOrdinal : ""),
                    sequenceText(group.loadMode(), waypoint.isSubwaypoint(),
                            mainOrdinal, childOrdinal, mainCount),
                    coordinateText(waypoint, roomLocal),
                    waypoint.color() & 0xFFFFFF,
                    waypoint.flags(),
                    waypoint.isSubwaypoint(),
                    small,
                    duplicateCounts.getOrDefault(key, 1),
                    localBox));
        }

        List<Connector> connectors = new ArrayList<>();
        Waypoint previousMain = null;
        for (Waypoint waypoint : group.waypoints()) {
            if (waypoint.isSubwaypoint()) continue;
            if (previousMain != null) {
                connectors.add(new Connector(
                        previousMain.centerX() - pivotX,
                        previousMain.centerY() - pivotY,
                        previousMain.centerZ() - pivotZ,
                        waypoint.centerX() - pivotX,
                        waypoint.centerY() - pivotY,
                        waypoint.centerZ() - pivotZ));
            }
            previousMain = waypoint;
        }

        WaypointPaint paint = effectivePaint(group, config);
        float opacity = (float) Math.clamp(config.beaconOpacity(), 0.0, 1.0);
        return new RoutePreviewScene(
                group.id(), routeName(group), roomLocal, markers, connectors,
                paint, null, false, config.boxStyle(), opacity,
                (float) config.waypointOutlineOpacity(),
                (float) config.waypointOutlineThickness(),
                config.matchWaypointOutlineToWaypointColor(), config.waypointOutlineColor(),
                config.routeLineColor(),
                pivotX, pivotY, pivotZ);
    }

    public static RoutePreviewScene empty() {
        return new RoutePreviewScene("", "", false, List.of(), List.of(), null, null, false,
                WaypointerConfig.BoxStyle.OUTLINED, 0.35f, 1.0f, 1.0f,
                true, 0x00FF00, 0x00FF00,
                0.0, 0.0, 0.0);
    }

    RoutePreviewScene preparePaintResource(RoutePreviewPaintResource resources) {
        if (resources == null) return this;
        if (paint == null || markers.isEmpty() || simplified()) {
            resources.activate(routeId, null);
            return this;
        }
        try {
            RoutePreviewPaintResource.Entry resource = resources.activate(routeId, paint);
            return copyWithPaint(paint, resource, false);
        } catch (RuntimeException decodeOrUploadFailure) {
            Waypointer.LOGGER.error("Could not prepare route preview paint for {}",
                    routeId, decodeOrUploadFailure);
            return copyWithPaint(null, null, true);
        }
    }

    private RoutePreviewScene copyWithPaint(WaypointPaint nextPaint,
                                            RoutePreviewPaintResource.Entry nextResource,
                                            boolean unavailable) {
        return new RoutePreviewScene(
                routeId, routeName, roomLocal, markers, connectors,
                nextPaint, nextResource, unavailable, boxStyle,
                opacity, outlineOpacity, outlineWidth, outlineMatchesWaypointColor,
                outlineColor, routeLineColor, centerX, centerY, centerZ);
    }

    private static String routeName(WaypointGroup group) {
        String name = group.name() == null ? "" : group.name().trim();
        return name.isEmpty() ? group.zoneId() : name;
    }

    private static String sequenceText(WaypointGroup.LoadMode loadMode, boolean subwaypoint,
                                       int mainOrdinal, int childOrdinal, int mainCount) {
        if (loadMode != WaypointGroup.LoadMode.SEQUENCE) return "";
        if (!subwaypoint) {
            return "Step " + mainOrdinal + " of " + mainCount;
        }
        return mainOrdinal > 0
                ? "Substep " + childOrdinal + " of step " + mainOrdinal
                : "Substep " + childOrdinal;
    }

    private static String coordinateText(Waypoint waypoint, boolean roomLocal) {
        boolean precise = waypoint.hasCustomPrecisePosition()
                || waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
        String x = precise ? format(waypoint.preciseX() / 16.0) : Integer.toString(waypoint.x());
        String y = precise ? format(waypoint.preciseY() / 16.0) : Integer.toString(waypoint.y());
        String z = precise ? format(waypoint.preciseZ() / 16.0) : Integer.toString(waypoint.z());
        return (roomLocal ? "Room-local " : "") + "(" + x + ", " + y + ", " + z + ")";
    }

    static String format(double value) {
        if (value == 0.0) return "0";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static WaypointPaint effectivePaint(WaypointGroup group, WaypointerConfig config) {
        if (group == null || config == null || !group.paintEnabled()) return null;
        WaypointPaint seasonalPaint = HappySnowmanSession.facePaint();
        return seasonalPaint != null
                ? seasonalPaint
                : group.paint() != null ? group.paint() : config.waypointPainterDefaultPaint();
    }

    public String routeId() { return routeId; }
    public String routeName() { return routeName; }
    public boolean roomLocal() { return roomLocal; }
    public List<Marker> markers() { return markers; }
    public List<Connector> connectors() { return connectors; }
    public WaypointPaint paint() { return paint; }
    RoutePreviewPaintResource.Entry paintResource() { return paintResource; }
    public boolean paintUnavailable() { return paintUnavailable; }
    public WaypointerConfig.BoxStyle boxStyle() { return boxStyle; }
    public float opacity() { return opacity; }
    public float outlineOpacity() { return outlineOpacity; }
    public float outlineWidth() { return outlineWidth; }
    public boolean outlineMatchesWaypointColor() { return outlineMatchesWaypointColor; }
    public int outlineColor() { return outlineColor; }
    public int routeLineColor() { return routeLineColor; }
    public double centerX() { return centerX; }
    public double centerY() { return centerY; }
    public double centerZ() { return centerZ; }
    public boolean simplified() { return markers.size() > SIMPLIFIED_THRESHOLD; }
}
