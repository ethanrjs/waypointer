package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

final class OdinWaypointExporter {

    private static final Map<String, String> AREA_BY_ZONE = new HashMap<>();
    private static final List<String> KUUDRA_TIERS = List.of(
            "KUUDRA_T1", "KUUDRA_T2", "KUUDRA_T3", "KUUDRA_T4", "KUUDRA_T5");
    private static final List<String> F6_PHASES = List.of(
            "F6_TERRACOTTAS", "F6_GIANTS", "F6_CLEARED");
    private static final List<String> F7_PHASES = List.of(
            "F7_MAXOR", "F7_STORM", "F7_TERMINALS", "F7_GOLDOR", "F7_NECRON", "F7_CLEARED");
    private static final double MAX_RADIUS = 10.0;

    static {
        OdinWaypointImporter.ISLANDS.forEach((island, zone) -> AREA_BY_ZONE.put(zone, "ISLAND_" + island));
    }

    private OdinWaypointExporter() {}

    static String encode(List<WaypointGroup> groups, WaypointCodec.Options opts) {
        if (groups.isEmpty()) throw new IllegalArgumentException("Nothing to export");
        long rooms = groups.stream().filter(OdinWaypointExporter::isRoom).count();
        if (rooms == groups.size()) return roomPack(groups, opts);
        if (rooms > 0) {
            throw new IllegalArgumentException(
                    "Odin keeps dungeon room waypoints apart from island waypoints; export them separately");
        }
        return areaWaypoints(groups, opts);
    }

    static boolean isRoom(WaypointGroup group) {
        return group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                || DungeonRoomData.entry(group.zoneId()) != null;
    }

    private static String roomPack(List<WaypointGroup> groups, WaypointCodec.Options opts) {
        Map<String, JsonArray> rooms = new LinkedHashMap<>();
        for (WaypointGroup group : groups) {
            JsonArray points = rooms.computeIfAbsent(roomName(group), key -> new JsonArray());
            for (Waypoint waypoint : group.waypoints()) {
                JsonObject point = new JsonObject();
                JsonObject pos = new JsonObject();
                pos.addProperty("x", waypoint.x());
                pos.addProperty("y", waypoint.y());
                pos.addProperty("z", waypoint.z());
                point.add("blockPos", pos);
                point.addProperty("color", color(waypoint, opts));
                point.addProperty("filled", false);
                point.addProperty("depth", false);
                JsonObject box = new JsonObject();
                box.addProperty("minX", 0.0);
                box.addProperty("minY", 0.0);
                box.addProperty("minZ", 0.0);
                box.addProperty("maxX", 1.0);
                box.addProperty("maxY", 1.0);
                box.addProperty("maxZ", 1.0);
                point.add("aabb", box);
                String title = label(waypoint, opts);
                if (!title.isEmpty()) point.addProperty("title", title);
                String type = roomWaypointType(waypoint);
                if (type != null) point.addProperty("type", type);
                points.add(point);
            }
        }
        JsonObject root = new JsonObject();
        rooms.forEach(root::add);
        return Base64.getEncoder().encodeToString(gzip(root.toString()));
    }

    private static String roomName(WaypointGroup group) {
        DungeonRoomCatalogEntry room = DungeonRoomData.entry(group.zoneId());
        if (room != null) return room.displayName();
        String name = group.name().strip();
        return name.isEmpty() ? group.zoneId() : name;
    }

    static String roomWaypointType(Waypoint waypoint) {
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_ETHERWARP)) return "ETHERWARP";
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_SECRET) || waypoint.hasFlag(Waypoint.FLAG_DUNGEON_ITEM)
                || waypoint.hasFlag(Waypoint.FLAG_DUNGEON_BAT)) {
            return "SECRET";
        }
        return null;
    }

    private static String areaWaypoints(List<WaypointGroup> groups, WaypointCodec.Options opts) {
        JsonArray out = new JsonArray();
        for (WaypointGroup group : groups) {
            List<String> areas = areas(group.zoneId());
            if (areas.isEmpty()) {
                throw new IllegalArgumentException("Odin does not recognize the "
                        + Zone.fromId(group.zoneId()).displayName() + " zone; use Waypointer export instead");
            }
            boolean numbered = group.loadMode() == WaypointGroup.LoadMode.SEQUENCE;
            for (String area : areas) {
                int step = 0;
                for (Waypoint waypoint : group.waypoints()) {
                    step++;
                    String label = label(waypoint, opts);
                    if (label.isEmpty() && numbered) label = Integer.toString(step);
                    out.add(areaWaypoint(area, waypoint, label, opts));
                }
            }
        }
        return out.toString();
    }

    private static JsonObject areaWaypoint(String area, Waypoint waypoint, String label,
                                           WaypointCodec.Options opts) {
        JsonObject point = new JsonObject();
        point.addProperty("area", area);
        boolean radius = opts.includeRadii && waypoint.customRadius() > 0;
        point.add("blockPos", vector(waypoint.x(), waypoint.y(), waypoint.z()));
        point.add("endPos", radius ? vector(waypoint.x(), waypoint.y(), waypoint.z())
                : vector(waypoint.x() + 1, waypoint.y() + 1, waypoint.z() + 1));
        point.addProperty("label", label);
        point.addProperty("color", color(waypoint, opts));
        point.addProperty("trigger", "VISUAL");
        if (radius) point.addProperty("radius", (float) Math.min(MAX_RADIUS, waypoint.customRadius()));
        point.addProperty("enabled", !waypoint.isDisabled());
        return point;
    }

    static List<String> areas(String zoneId) {
        String zone = Zone.canonicalId(zoneId);
        String island = AREA_BY_ZONE.get(zone);
        if (island != null) return List.of(island);
        if (zone.startsWith("mineshaft")) return List.of("ISLAND_Mineshaft");
        if (zone.equals("kuudra")) return KUUDRA_TIERS;
        if (zone.length() == "dungeon_f1".length() && (zone.startsWith("dungeon_f") || zone.startsWith("dungeon_m"))) {
            int floor = zone.charAt(zone.length() - 1) - '0';
            if (floor >= 1 && floor <= 5) return List.of("FLOOR_" + floor);
            if (floor == 6) return F6_PHASES;
            if (floor == 7) return F7_PHASES;
        }
        return List.of();
    }

    private static JsonObject vector(int x, int y, int z) {
        JsonObject vector = new JsonObject();
        vector.addProperty("x", (double) x);
        vector.addProperty("y", (double) y);
        vector.addProperty("z", (double) z);
        return vector;
    }

    private static String label(Waypoint waypoint, WaypointCodec.Options opts) {
        if (!opts.includeNames || !waypoint.hasName()) return "";
        return waypoint.name().replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§");
    }

    private static String color(Waypoint waypoint, WaypointCodec.Options opts) {
        int rgb = opts.includeColors ? waypoint.color() & 0xFFFFFF : Waypoint.DEFAULT_COLOR & 0xFFFFFF;
        return String.format(Locale.ROOT, "#%06XFF", rgb);
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Odin export failed", e);
        }
        return out.toByteArray();
    }
}
