package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

final class FlameOfWarPack {

    static final String ROUTE_NAME = "FlameOfWar";

    enum Kind {
        PATH, PEARL_TARGET, ETHERWARP, DUNGEONBREAKER, SUPERBOOM, PEARL,
        CHEST, WITHER_ESSENCE, REDSTONE_KEY, SECRET, ITEM, BAT, LEVER, EXIT, PRINCE, OTHER
    }

    record Secret(int x, int y, int z, String category) {}

    record Pack(List<WaypointGroup> routes, List<String> report) {}

    static final int STRUCTURE = Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_STEP_ANY_ORDER
            | Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_FILLED_SUBWAYPOINT
            | Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME;

    static final int PATH_COLOR = 0x8AA7BA;
    static final double ITEM_SNAP_BLOCKS = 8.0;
    static final double BAT_SNAP_BLOCKS = 10.0;

    private FlameOfWarPack() {}

    static Pack build(JsonObject fowRoutes, Map<String, List<Secret>> secretsByRoom,
                      Map<String, Integer> catalogSecretCounts) {
        return build(fowRoutes, secretsByRoom, catalogSecretCounts, false);
    }

    static Pack build(JsonObject fowRoutes, Map<String, List<Secret>> secretsByRoom,
                      Map<String, Integer> catalogSecretCounts, boolean snapToSecretTable) {
        List<WaypointGroup> routes = new ArrayList<>();
        List<String> report = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        Map<Kind, Integer> totals = new EnumMap<>(Kind.class);
        Map<String, Integer> routesPerRoom = new HashMap<>();

        for (Map.Entry<String, JsonElement> entry : fowRoutes.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("#") || key.equalsIgnoreCase("version")) continue;
            JsonObject single = new JsonObject();
            single.add(key, entry.getValue());
            DungeonRouteImporter.Result imported = DungeonRouteImporter.parse(single.toString());
            if (!imported.unmatchedRooms().isEmpty()) {
                report.add("SKIPPED " + key + ": no Waypointer room matches it");
                continue;
            }
            String roomKey = key.split(":")[0].toLowerCase(Locale.ROOT);
            List<Secret> secrets = secretsByRoom.getOrDefault(roomKey, List.of());
            for (WaypointGroup source : imported.groups()) {
                if (source.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
                    report.add("SKIPPED " + key + ": boss route (" + source.name() + "), not a room");
                    continue;
                }
                int routeNumber = routesPerRoom.merge(source.zoneId(), 1, Integer::sum);
                String name = routeNumber == 1 ? ROUTE_NAME : ROUTE_NAME + " " + routeNumber;
                Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
                WaypointGroup route = rebuild(source, name, secrets, counts, key, report, snapToSecretTable);
                routes.add(route);
                counts.forEach((kind, count) -> totals.merge(kind, count, Integer::sum));

                int secretSteps = (int) route.waypoints().stream()
                        .filter(w -> w.hasFlag(Waypoint.FLAG_DUNGEON_SECRET)).count();
                Integer roomSecrets = catalogSecretCounts.get(source.zoneId());
                String coverage = roomSecrets == null ? secretSteps + " secrets"
                        : secretSteps + "/" + roomSecrets + " secrets";
                if (roomSecrets != null && secretSteps < roomSecrets) {
                    report.add("NOTE " + key + ": route collects " + secretSteps + " of the room's "
                            + roomSecrets + " secrets");
                }
                summary.add(String.format(Locale.ROOT, "%-24s %-14s %-13s %s",
                        key, name, coverage, describe(counts)));
            }
        }

        List<String> out = new ArrayList<>();
        out.add("FlameOfWar dungeon pack: " + routes.size() + " room routes");
        out.add("Totals: " + describe(totals));
        out.add("");
        out.add("Things to look at:");
        out.addAll(report.isEmpty() ? List.of("  none") : report.stream().map(line -> "  " + line).toList());
        out.add("");
        out.add("Rooms:");
        out.addAll(summary.stream().map(line -> "  " + line).toList());
        return new Pack(List.copyOf(routes), out);
    }

    private static WaypointGroup rebuild(WaypointGroup source, String name, List<Secret> secrets,
                                         Map<Kind, Integer> counts, String key, List<String> report,
                                         boolean snapToSecretTable) {
        WaypointGroup route = WaypointGroup.create(name, source.zoneId());
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setLoadMode(source.loadMode());
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setSkipAheadEnabled(false);
        List<Waypoint> waypoints = new ArrayList<>();
        for (Waypoint imported : source.waypoints()) {
            Waypoint waypoint = imported;
            Kind kind = kindOf(waypoint, secrets);
            counts.merge(kind, 1, Integer::sum);
            if (snapToSecretTable && (kind == Kind.ITEM || kind == Kind.BAT)) {
                Waypoint moved = snapToSecretTable(waypoint, kind, secrets);
                if (moved != waypoint) {
                    report.add("MOVED " + key + ": " + kind.name().toLowerCase(Locale.ROOT) + " "
                            + waypoint.x() + " " + waypoint.y() + " " + waypoint.z() + " -> "
                            + moved.x() + " " + moved.y() + " " + moved.z());
                } else if (secrets.stream().noneMatch(s -> sameSpot(s, imported))) {
                    report.add("CHECK " + key + ": " + kind.name().toLowerCase(Locale.ROOT) + " at "
                            + waypoint.x() + " " + waypoint.y() + " " + waypoint.z()
                            + " has no nearby match in the room secret table");
                }
                waypoint = moved;
            }
            if (kind == Kind.SECRET) {
                report.add("CHECK " + key + ": secret at " + waypoint.x() + " " + waypoint.y() + " "
                        + waypoint.z() + " is not in the room secret table; labelled \"Secret\"");
            } else if (kind == Kind.OTHER) {
                report.add("CHECK " + key + ": unrecognised step \"" + waypoint.name() + "\" at "
                        + waypoint.x() + " " + waypoint.y() + " " + waypoint.z() + "; left as imported");
            }
            waypoints.add(normalize(waypoint, kind));
        }
        route.addAll(waypoints);
        return route;
    }

    static Waypoint snapToSecretTable(Waypoint waypoint, Kind kind, List<Secret> secrets) {
        String category = kind == Kind.BAT ? "bat" : "item";
        double limit = kind == Kind.BAT ? BAT_SNAP_BLOCKS : ITEM_SNAP_BLOCKS;
        Secret best = null;
        double bestSq = limit * limit;
        for (Secret secret : secrets) {
            if (!secret.category().equals(category)) continue;
            double dx = secret.x() - waypoint.x();
            double dy = secret.y() - waypoint.y();
            double dz = secret.z() - waypoint.z();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq <= bestSq) {
                best = secret;
                bestSq = distanceSq;
            }
        }
        if (best == null || sameSpot(best, waypoint)) return waypoint;
        return new Waypoint(best.x(), best.y(), best.z(), waypoint.name(), waypoint.color(),
                waypoint.flags(), waypoint.customRadius());
    }

    private static boolean sameSpot(Secret secret, Waypoint waypoint) {
        return secret.x() == waypoint.x() && secret.y() == waypoint.y() && secret.z() == waypoint.z();
    }

    static Kind kindOf(Waypoint waypoint, List<Secret> secrets) {
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)) return Kind.PEARL_TARGET;
        boolean skips = waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_STAND)
                || waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT)
                || waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_MINE);
        if (!skips && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT)) return Kind.PATH;
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_ETHERWARP)) return Kind.ETHERWARP;
        if (waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_MINE)) return Kind.DUNGEONBREAKER;
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_SUPERBOOM)) return Kind.SUPERBOOM;
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL)) return Kind.PEARL;
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_ITEM)) return Kind.ITEM;
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_BAT)) return Kind.BAT;
        String name = waypoint.name() == null ? "" : waypoint.name();
        if (name.equals("Exit")) return Kind.EXIT;
        if (name.equals("Prince")) return Kind.PRINCE;
        if (name.equals("Lever") && !waypoint.hasFlag(Waypoint.FLAG_DUNGEON_SECRET)) return Kind.LEVER;
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_SECRET)
                && waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT)) {
            for (Secret secret : secrets) {
                if (secret.x() != waypoint.x() || secret.y() != waypoint.y() || secret.z() != waypoint.z()) continue;
                return switch (secret.category()) {
                    case "chest" -> Kind.CHEST;
                    case "wither" -> Kind.WITHER_ESSENCE;
                    case "key" -> Kind.REDSTONE_KEY;
                    default -> Kind.SECRET;
                };
            }
            return Kind.SECRET;
        }
        return Kind.OTHER;
    }

    static Waypoint normalize(Waypoint waypoint, Kind kind) {
        int structure = waypoint.flags() & STRUCTURE;
        boolean sub = (structure & Waypoint.FLAG_SUBWAYPOINT) != 0;
        return switch (kind) {
            case PATH -> styled(waypoint, "", PATH_COLOR, Waypoint.FLAG_SUBWAYPOINT
                    | Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_FILLED_SUBWAYPOINT
                    | Waypoint.FLAG_HIDE_NAME | Waypoint.FLAG_HIDE_BEACON);
            case PEARL_TARGET -> styled(waypoint, "", DungeonSecretCategory.PEARL.defaultColor,
                    Waypoint.FLAG_SUBWAYPOINT | (structure & Waypoint.FLAG_FILLED_SUBWAYPOINT)
                            | Waypoint.FLAG_DUNGEON_PEARL_TARGET
                            | Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME);
            case ETHERWARP -> action(waypoint, "", DungeonSecretCategory.ETHERWARP, sub,
                    Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP);
            case DUNGEONBREAKER -> action(waypoint, "", DungeonSecretCategory.DUNGEONBREAKER, sub,
                    Waypoint.FLAG_SKIP_ON_MINE | Waypoint.FLAG_DUNGEON_DUNGEONBREAKER
                            | (sub ? Waypoint.FLAG_STEP_ANY_ORDER : 0));
            case SUPERBOOM -> action(waypoint, "", DungeonSecretCategory.SUPERBOOM, sub,
                    Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SUPERBOOM);
            case PEARL -> action(waypoint, "", DungeonSecretCategory.PEARL, sub,
                    Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_PEARL);
            case CHEST -> action(waypoint, "Chest", DungeonSecretCategory.CHEST, sub,
                    Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET);
            case WITHER_ESSENCE -> action(waypoint, "Wither Essence", DungeonSecretCategory.WITHER, sub,
                    Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET);
            case REDSTONE_KEY -> action(waypoint, "Redstone Key", DungeonSecretCategory.REDSTONE_KEY, sub,
                    Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET);
            case SECRET -> action(waypoint, "Secret", DungeonSecretCategory.CHEST, sub,
                    Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET);
            case ITEM -> action(waypoint, "Item", DungeonSecretCategory.ITEM, sub,
                    Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ITEM | Waypoint.FLAG_DUNGEON_SECRET);
            case BAT -> action(waypoint, "Bat", DungeonSecretCategory.BAT, sub,
                    Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_BAT | Waypoint.FLAG_DUNGEON_SECRET);
            case LEVER -> action(waypoint, "Lever", DungeonSecretCategory.LEVER, sub,
                    Waypoint.FLAG_SKIP_ON_INTERACT);
            case EXIT -> action(waypoint, "Exit", DungeonSecretCategory.ENTRANCE, sub,
                    Waypoint.FLAG_SKIP_ON_STAND);
            case PRINCE -> action(waypoint, "Prince", DungeonSecretCategory.PRINCE, sub,
                    Waypoint.FLAG_SKIP_ON_STAND);
            case OTHER -> waypoint;
        };
    }

    private static Waypoint action(Waypoint waypoint, String name, DungeonSecretCategory category,
                                   boolean sub, int flags) {
        return styled(waypoint, name, category.defaultColor, (sub ? Waypoint.FLAG_SUBWAYPOINT : 0) | flags);
    }

    private static Waypoint styled(Waypoint waypoint, String name, int color, int flags) {
        return new Waypoint(waypoint.x(), waypoint.y(), waypoint.z(), name, color, flags, 0.0);
    }

    private static String describe(Map<Kind, Integer> counts) {
        StringBuilder out = new StringBuilder();
        for (Kind kind : Kind.values()) {
            Integer count = counts.get(kind);
            if (count == null || kind == Kind.PATH) continue;
            if (!out.isEmpty()) out.append(", ");
            out.append(count).append(' ').append(kind.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        return out.toString();
    }

    static JsonArray reviewJson(List<WaypointGroup> routes) {
        JsonArray out = new JsonArray();
        for (WaypointGroup route : routes) {
            JsonObject json = new JsonObject();
            json.addProperty("room", route.zoneId());
            json.addProperty("name", route.name());
            JsonArray steps = new JsonArray();
            int pathDots = 0;
            for (Waypoint waypoint : route.waypoints()) {
                Kind kind = kindOf(waypoint, List.of());
                if (kind == Kind.PATH) {
                    pathDots++;
                    continue;
                }
                JsonObject step = new JsonObject();
                step.addProperty("kind", waypoint.hasName() ? waypoint.name() : kind.name().toLowerCase(Locale.ROOT));
                step.addProperty("at", waypoint.x() + " " + waypoint.y() + " " + waypoint.z());
                step.addProperty("color", String.format(Locale.ROOT, "#%06X", waypoint.color() & 0xFFFFFF));
                if (waypoint.isSubwaypoint()) step.addProperty("sub", true);
                if (waypoint.hasFlag(Waypoint.FLAG_STEP_ANY_ORDER)) step.addProperty("anyOrder", true);
                steps.add(step);
            }
            json.addProperty("pathDots", pathDots);
            json.add("waypoints", steps);
            out.add(json);
        }
        return out;
    }

    static Map<String, List<Secret>> readSecretTable(Path catacombsDir) throws IOException {
        Map<String, List<Secret>> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(catacombsDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject room = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!room.has("secrets")) continue;
                List<Secret> secrets = new ArrayList<>();
                for (JsonElement element : room.getAsJsonArray("secrets")) {
                    JsonObject secret = element.getAsJsonObject();
                    secrets.add(new Secret(secret.get("x").getAsInt(), secret.get("y").getAsInt(),
                            secret.get("z").getAsInt(), secret.get("category").getAsString()));
                }
                String name = file.getFileName().toString();
                out.put(name.substring(0, name.length() - ".json".length()).toLowerCase(Locale.ROOT), secrets);
            }
        }
        return out;
    }

    static Map<String, Integer> catalogSecretCounts() {
        Map<String, Integer> out = new HashMap<>();
        try (var in = FlameOfWarPack.class.getResourceAsStream(
                "/assets/waypointer/dungeons/catacombs/rooms.json")) {
            JsonArray rooms = JsonParser.parseString(new String(in.readAllBytes()))
                    .getAsJsonObject().getAsJsonArray("rooms");
            for (JsonElement element : rooms) {
                JsonObject room = element.getAsJsonObject();
                out.put(DungeonRoomCatalogEntry.normalizeId(room.get("id").getAsString()),
                        room.get("secrets").getAsInt());
            }
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalStateException("room catalog is unreadable", unreadable);
        }
        return out;
    }
}
