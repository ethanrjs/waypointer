package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OdinWaypointImporter {

    private static final Map<String, String> ISLANDS = Map.ofEntries(
            Map.entry("SinglePlayer", Zone.PRIVATE_WORLD.id()),
            Map.entry("PrivateIsland", "private_island"),
            Map.entry("Garden", "garden"),
            Map.entry("SpiderDen", "spiders_den"),
            Map.entry("CrimsonIsle", "crimson_isle"),
            Map.entry("TheEnd", "the_end"),
            Map.entry("GoldMine", "gold_mine"),
            Map.entry("DeepCaverns", "deep_caverns"),
            Map.entry("DwarvenMines", "dwarven_mines"),
            Map.entry("CrystalHollows", "crystal_hollows"),
            Map.entry("FarmingIsland", "the_farming_isles"),
            Map.entry("ThePark", "the_park"),
            Map.entry("DungeonHub", "dungeon_hub"),
            Map.entry("Hub", "hub"),
            Map.entry("DarkAuction", "dark_auction"),
            Map.entry("JerryWorkshop", "winter"),
            Map.entry("Mineshaft", "mineshaft_unknown"),
            Map.entry("Rift", "rift"),
            Map.entry("BackwaterBayou", "backwater_bayou"),
            Map.entry("MoongladeMarsh", "galatea"),
            Map.entry("LotusAtoll", "lotus_atoll"),
            Map.entry("TorrhusCanyon", "torrhus_canyon"),
            Map.entry("Safari", "safari"));

    private static final Map<String, String> ISLAND_LABELS = Map.ofEntries(
            Map.entry("SinglePlayer", "Singleplayer"),
            Map.entry("PrivateIsland", "Private Island"),
            Map.entry("SpiderDen", "Spider's Den"),
            Map.entry("CrimsonIsle", "Crimson Isle"),
            Map.entry("TheEnd", "The End"),
            Map.entry("GoldMine", "Gold Mine"),
            Map.entry("DeepCaverns", "Deep Caverns"),
            Map.entry("DwarvenMines", "Dwarven Mines"),
            Map.entry("CrystalHollows", "Crystal Hollows"),
            Map.entry("FarmingIsland", "The Farming Islands"),
            Map.entry("ThePark", "The Park"),
            Map.entry("DungeonHub", "Dungeon Hub"),
            Map.entry("DarkAuction", "Dark Auction"),
            Map.entry("JerryWorkshop", "Jerry's Workshop"),
            Map.entry("Rift", "The Rift"),
            Map.entry("BackwaterBayou", "Backwater Bayou"),
            Map.entry("MoongladeMarsh", "Moonglade Marsh"),
            Map.entry("LotusAtoll", "Lotus Atoll"),
            Map.entry("TorrhusCanyon", "Torrhus Canyon"));

    private OdinWaypointImporter() {}

    static boolean looksLikeExport(JsonArray array) {
        boolean any = false;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) return false;
            JsonObject point = element.getAsJsonObject();
            if (!point.has("area") || !point.get("area").isJsonPrimitive()
                    || !point.has("blockPos") || !point.get("blockPos").isJsonObject()) {
                return false;
            }
            any = true;
        }
        return any;
    }

    static List<WaypointGroup> decode(JsonArray array) {
        Map<String, WaypointGroup> byArea = new LinkedHashMap<>();
        for (JsonElement element : array) {
            JsonObject point = element.getAsJsonObject();
            Waypoint waypoint = waypoint(point);
            if (waypoint == null) continue;
            String area = point.get("area").getAsString().strip();
            WaypointGroup group = byArea.computeIfAbsent(area, key -> {
                WaypointGroup created = WaypointGroup.create(routeName(key), zone(key));
                created.setLoadMode(WaypointGroup.LoadMode.STATIC);
                created.setGradientMode(WaypointGroup.GradientMode.MANUAL);
                return created;
            });
            group.add(waypoint);
        }
        List<WaypointGroup> groups = new ArrayList<>();
        for (WaypointGroup group : byArea.values()) {
            if (!group.isEmpty()) groups.add(group);
        }
        return groups;
    }

    private static Waypoint waypoint(JsonObject point) {
        double[] from = vector(point.get("blockPos"));
        if (from == null) return null;
        double[] to = point.has("endPos") ? vector(point.get("endPos")) : null;
        if (to == null) to = from;
        int x = (int) Math.floor((Math.min(from[0], to[0]) + Math.max(from[0], to[0])) / 2.0);
        int y = (int) Math.floor(Math.min(from[1], to[1]));
        int z = (int) Math.floor((Math.min(from[2], to[2]) + Math.max(from[2], to[2])) / 2.0);
        if (!Waypoint.isRepresentableBlockCoordinate(x) || !Waypoint.isRepresentableBlockCoordinate(y)
                || !Waypoint.isRepresentableBlockCoordinate(z)) {
            return null;
        }
        String label = string(point, "label").replace('§', '&');
        int color = color(string(point, "color"));
        double radius = 0.0;
        if (point.has("radius") && point.get("radius").isJsonPrimitive()) {
            try {
                double r = point.get("radius").getAsDouble();
                if (r > 0) radius = Math.max(Waypoint.MIN_REACH_RADIUS, Math.min(Waypoint.MAX_REACH_RADIUS, r));
            } catch (RuntimeException ignored) {

            }
        }
        boolean enabled = !point.has("enabled") || !point.get("enabled").isJsonPrimitive()
                || point.get("enabled").getAsBoolean();
        return new Waypoint(x, y, z, WaypointCodec.Options.sanitizeLabel(label), color,
                enabled ? 0 : Waypoint.FLAG_DISABLED, radius);
    }

    private static double[] vector(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        try {
            double x = o.get("x").getAsDouble();
            double y = o.get("y").getAsDouble();
            double z = o.get("z").getAsDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            return new double[]{x, y, z};
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static int color(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() != 8 && digits.length() != 6) return Waypoint.DEFAULT_COLOR;
        try {
            return Integer.parseInt(digits.substring(0, 6), 16);
        } catch (NumberFormatException invalid) {
            return Waypoint.DEFAULT_COLOR;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static String zone(String area) {
        if (area.startsWith("ISLAND_")) {
            String zone = ISLANDS.get(area.substring(7));
            return zone == null ? Zone.UNKNOWN.id() : zone;
        }
        if (area.startsWith("KUUDRA_")) return "kuudra";
        int floor = floor(area);
        return floor > 0 ? "dungeon_f" + floor : Zone.UNKNOWN.id();
    }

    private static int floor(String area) {
        String digits = area.startsWith("FLOOR_") ? area.substring(6)
                : area.startsWith("F") && area.indexOf('_') > 1 ? area.substring(1, area.indexOf('_')) : "";
        try {
            int floor = Integer.parseInt(digits);
            return floor >= 1 && floor <= 7 ? floor : -1;
        } catch (NumberFormatException notAFloor) {
            return -1;
        }
    }

    static String routeName(String area) {
        if (area.startsWith("ISLAND_")) {
            String key = area.substring(7);
            return ISLAND_LABELS.getOrDefault(key, key);
        }
        if (area.startsWith("KUUDRA_")) return "Kuudra " + area.substring(7);
        if (area.startsWith("FLOOR_")) {
            String n = area.substring(6);
            return "F" + n + " / M" + n + " Boss";
        }
        int floor = floor(area);
        if (floor > 0) {
            String phase = area.substring(area.indexOf('_') + 1).toLowerCase(Locale.ROOT);
            return "F" + floor + " " + Character.toUpperCase(phase.charAt(0)) + phase.substring(1);
        }
        return area.isEmpty() ? "Odin waypoints" : area;
    }
}
