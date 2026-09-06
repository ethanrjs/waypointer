package com.babbur.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** RouteSkipper bridge: coal offsets become subwaypoints; span statistics are lossy. */
final class ChunkLoggerRouteCodec {

    record RelativeOffset(int x, int y, int z) {
        RelativeOffset {
            if (!isSignedByte(x) || !isSignedByte(y) || !isSignedByte(z)) {
                throw new IllegalArgumentException("ChunkLogger coal offset is outside signed-byte range");
            }
        }
    }

    private ChunkLoggerRouteCodec() {}

    static boolean looksLikeRoute(JsonElement root) {
        JsonArray route;
        if (root.isJsonArray()) {
            route = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (!object.has("waypoints") || !object.get("waypoints").isJsonArray()) return false;
            route = object.getAsJsonArray("waypoints");
        } else {
            return false;
        }

        boolean hasCoordinateWaypoint = false;
        boolean hasChunkLoggerSpecificField = false;
        for (JsonElement element : route) {
            if (!element.isJsonObject()) continue;
            JsonObject waypoint = element.getAsJsonObject();
            if (!waypoint.has("x") || !waypoint.has("y") || !waypoint.has("z")) continue;
            hasCoordinateWaypoint = true;
            hasChunkLoggerSpecificField |= waypoint.has("coal") || waypoint.has("blocks")
                    || waypoint.has("xzSpan") || waypoint.has("ySpan");
        }
        // Plain coordinate JSON is valid generic JSON too. Only claim it as
        // RouteSkipper when its distinctive optional fields make that certain.
        return hasCoordinateWaypoint && hasChunkLoggerSpecificField;
    }

    static WaypointGroup decode(JsonElement root, String defaultRouteName) {
        WaypointGroup group = WaypointGroup.create(defaultRouteName, "unknown");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);

        List<Waypoint> waypoints = new ArrayList<>();
        for (JsonElement element : routeArray(root)) {
            if (!element.isJsonObject()) continue;
            JsonObject source = element.getAsJsonObject();
            int[] position = coordinates(source);
            if (position == null) continue;
            Waypoint parent = new Waypoint(position[0], position[1], position[2],
                    "", Waypoint.DEFAULT_COLOR, 0, 0.0);
            waypoints.add(parent);
            for (RelativeOffset offset : decodeCoal(source)) {
                waypoints.add(coalChild(parent, offset));
            }
        }
        group.addAll(waypoints);
        return group;
    }

    /** Standard Base64 signed-byte triples; malformed/empty fields are empty shapes. */
    static List<RelativeOffset> decodeCoal(JsonObject source) {
        if (!source.has("coal") || !source.get("coal").isJsonPrimitive()
                || !source.getAsJsonPrimitive("coal").isString()) {
            return List.of();
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(source.get("coal").getAsString());
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
        if (decoded.length < 3) return List.of();

        List<RelativeOffset> offsets = new ArrayList<>(decoded.length / 3);
        for (int index = 0; index + 2 < decoded.length; index += 3) {
            offsets.add(new RelativeOffset(decoded[index], decoded[index + 1], decoded[index + 2]));
        }
        return offsets;
    }

    private static Waypoint coalChild(Waypoint parent, RelativeOffset offset) {
        long x = (long) parent.x() + offset.x();
        long y = (long) parent.y() + offset.y();
        long z = (long) parent.z() + offset.z();
        if (x < Waypoint.MIN_BLOCK_COORDINATE || x > Waypoint.MAX_BLOCK_COORDINATE
                || y < Waypoint.MIN_BLOCK_COORDINATE || y > Waypoint.MAX_BLOCK_COORDINATE
                || z < Waypoint.MIN_BLOCK_COORDINATE || z > Waypoint.MAX_BLOCK_COORDINATE) {
            throw new IllegalArgumentException("ChunkLogger coal block is outside Waypointer's coordinate range");
        }
        return new Waypoint((int) x, (int) y, (int) z, "", Waypoint.DEFAULT_COLOR,
                Waypoint.FLAG_SUBWAYPOINT, 0.0);
    }

    private static JsonArray routeArray(JsonElement root) {
        return root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().getAsJsonArray("waypoints");
    }

    private static int[] coordinates(JsonObject source) {
        if (!(source.has("x") && source.has("y") && source.has("z"))) return null;
        try {
            return new int[]{coordinate(source.get("x")), coordinate(source.get("y")), coordinate(source.get("z"))};
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static int coordinate(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("waypoint coordinate must be a number");
        }
        int coordinate = value.getAsBigDecimal().intValueExact();
        if (!Waypoint.isRepresentableBlockCoordinate(coordinate)) {
            throw new IllegalArgumentException("waypoint coordinate is outside the precise range");
        }
        return coordinate;
    }

    private static boolean isSignedByte(long value) {
        return value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
    }
}
