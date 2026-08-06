package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.HappySnowmanSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable, route-local data consumed by the export screen's 3D preview. */
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
    private final float outlineWidth;
    private final int routeLineColor;
    private final double centerX;
    private final double centerY;
    private final double centerZ;

    private RoutePreviewScene(String routeId, String routeName, boolean roomLocal,
                              List<Marker> markers, List<Connector> connectors,
                              WaypointPaint paint, RoutePreviewPaintResource.Entry paintResource,
                              boolean paintUnavailable,
                              WaypointerConfig.BoxStyle boxStyle,
                              float opacity, float outlineWidth, int routeLineColor,
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
        this.outlineWidth = outlineWidth;
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
            if (shapeLevel != null && !shapeLevel.hasChunkAt(
                    new net.minecraft.core.BlockPos(waypoint.x(), waypoint.y(), waypoint.z()))) {
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
        boolean roomLocal = DungeonRoomData.definition(group.zoneId()) != null;

        Map<PositionKey, Integer> duplicateCounts = new HashMap<>();
        for (Waypoint waypoint : group.waypoints()) {
            PositionKey key = new PositionKey(
                    waypoint.preciseX(), waypoint.preciseY(), waypoint.preciseZ());
            duplicateCounts.merge(key, 1, Integer::sum);
        }

        List<Marker> markers = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            Waypoint waypoint = group.get(i);
            AABB worldBox = worldBoxes.get(i);
            Box localBox = new Box(
                    worldBox.minX - pivotX, worldBox.minY - pivotY, worldBox.minZ - pivotZ,
                    worldBox.maxX - pivotX, worldBox.maxY - pivotY, worldBox.maxZ - pivotZ);
            boolean small = waypoint.isSubwaypoint()
                    && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
            PositionKey key = new PositionKey(
                    waypoint.preciseX(), waypoint.preciseY(), waypoint.preciseZ());
            markers.add(new Marker(
                    i,
                    waypoint.name(),
                    group.displayIndexLabel(i),
                    sequenceText(group, i),
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

        WaypointPaint seasonalPaint = HappySnowmanSession.facePaint();
        WaypointPaint paint = seasonalPaint != null
                ? seasonalPaint
                : group.paintEnabled()
                        ? (group.paint() != null ? group.paint() : config.waypointPainterDefaultPaint())
                        : null;
        boolean paintUnavailable = false;
        RoutePreviewPaintResource.Entry paintResource = null;
        try {
            paintResource = RoutePreviewPaintResource.activate(group.id(), paint);
        } catch (RuntimeException decodeOrUploadFailure) {
            if (paint != null) {
                paint = null;
                paintUnavailable = true;
            }
        }
        float opacity = (float) Math.max(0.35, config.beaconOpacity());
        return new RoutePreviewScene(
                group.id(), routeName(group), roomLocal, markers, connectors,
                paint, paintResource, paintUnavailable, config.boxStyle(), opacity,
                (float) config.waypointOutlineThickness(), config.routeLineColor(),
                pivotX, pivotY, pivotZ);
    }

    public static RoutePreviewScene empty() {
        return new RoutePreviewScene("", "", false, List.of(), List.of(), null, null, false,
                WaypointerConfig.BoxStyle.OUTLINED, 0.35f, 1.0f, 0x00FF00,
                0.0, 0.0, 0.0);
    }

    private static String routeName(WaypointGroup group) {
        String name = group.name() == null ? "" : group.name().trim();
        return name.isEmpty() ? group.zoneId() : name;
    }

    private static String sequenceText(WaypointGroup group, int index) {
        if (group.loadMode() != WaypointGroup.LoadMode.SEQUENCE) return "";
        if (!group.isSubwaypoint(index)) {
            return "Step " + group.mainOrdinal(index) + " of " + group.mainWaypointCount();
        }
        int parent = group.parentMainIndex(index);
        int parentOrdinal = parent >= 0 ? group.mainOrdinal(parent) : 0;
        return parentOrdinal > 0
                ? "Substep " + group.childOrdinal(index) + " of step " + parentOrdinal
                : "Substep " + group.childOrdinal(index);
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
    public float outlineWidth() { return outlineWidth; }
    public int routeLineColor() { return routeLineColor; }
    public double centerX() { return centerX; }
    public double centerY() { return centerY; }
    public double centerZ() { return centerZ; }
    public boolean simplified() { return markers.size() > SIMPLIFIED_THRESHOLD; }
}
