package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Opt-in length report: V9 versus V10 over real saved route stores.
 *
 * <p>Run with {@code -Pv10.routes=<dir of *waypoints.json>} (optionally
 * {@code -Pv10.report=<out.json>} for per-route rows) and {@code -i} so the
 * table prints. Every {@code *waypoints.json} in the directory is loaded
 * through {@link Storage}; identical routes across profiles are counted once.
 * The report is the shared yardstick for codec length work: run it before and
 * after a change and compare the totals and the per-route JSON.
 */
class V10LengthReport {

    private static final WaypointCodec.Options ALL_OFF_NOT_BARE = WaypointCodec.Options.builder()
            .includeNames(false).includeColors(false).includeRadii(false)
            .includeWaypointFlags(false).includeGroupMeta(false).includeZone(false)
            .build();

    @Test
    @EnabledIfSystemProperty(named = "v10.routes", matches = ".+")
    void report() throws Exception {
        Path dir = Path.of(System.getProperty("v10.routes"));
        List<WaypointGroup> routes = loadRoutes(dir);
        List<Row> rows = new ArrayList<>();
        for (WaypointGroup route : routes) rows.add(measure(route));

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%n== V10 length report: %d unique routes from %s%n", rows.size(), dir));
        summarize(out, "FULL_FIDELITY (kind 0 vs V9)", rows, r -> r.v9Full, r -> r.v10Full);
        summarize(out, "NO_NAMES (V9 vs V10)", rows, r -> r.v9NoNames, r -> r.v10NoNames);
        summarize(out, "COORDS ONLY (V9 all-off vs V10 bare, regular routes)", rows,
                r -> r.v9Bare, r -> r.v10Bare);
        distribution(out, "V10 full-fidelity kind/mode", rows, r -> r.v10FullShape);
        distribution(out, "V10 bare kind/mode", rows, r -> r.v10BareShape);
        dungeonAndConfig(out, dir);
        System.out.println(out);

        String reportPath = System.getProperty("v10.report");
        if (reportPath != null && !reportPath.isBlank()) {
            JsonArray json = new JsonArray();
            for (Row row : rows) json.add(row.toJson());
            Files.writeString(Path.of(reportPath),
                    new GsonBuilder().create().toJson(json), StandardCharsets.UTF_8);
        }
    }

    private static List<WaypointGroup> loadRoutes(Path dir) throws IOException {
        Map<String, WaypointGroup> unique = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith("waypoints.json"))
                    .sorted().toList();
        }
        for (Path file : files) {
            ActiveGroupManager manager = new ActiveGroupManager();
            new Storage(file).load(manager);
            for (WaypointGroup group : manager.allGroupsList()) {
                if (group.isEmpty()) continue;
                unique.putIfAbsent(fingerprint(group), group.exportSnapshot());
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String fingerprint(WaypointGroup group) {
        StringBuilder key = new StringBuilder(group.zoneId()).append('|').append(group.name()).append('|');
        for (Waypoint waypoint : group.waypoints()) {
            key.append(waypoint.x()).append(',').append(waypoint.y()).append(',').append(waypoint.z())
                    .append(',').append(waypoint.name()).append(',').append(waypoint.color())
                    .append(',').append(waypoint.flags()).append(';');
        }
        return key.toString();
    }

    private static Row measure(WaypointGroup route) {
        List<WaypointGroup> groups = List.of(route);
        Row row = new Row();
        row.name = route.name();
        row.zone = route.zoneId();
        row.kind = route.routeKind().name();
        row.points = route.size();
        row.named = (int) route.waypoints().stream().filter(Waypoint::hasName).count();

        row.v9Full = length(() -> WaypointCodec.encodeV9ForTest(groups, WaypointCodec.Options.FULL_FIDELITY));
        String full = attempt(() -> WaypointCodec.encode(groups, WaypointCodec.Options.FULL_FIDELITY));
        row.v10Full = full == null ? -1 : full.length();
        row.v10FullShape = shape(full);

        row.v9NoNames = length(() -> WaypointCodec.encodeV9ForTest(groups, WaypointCodec.Options.NO_NAMES));
        String noNames = attempt(() -> WaypointCodec.encode(groups, WaypointCodec.Options.NO_NAMES));
        row.v10NoNames = noNames == null ? -1 : noNames.length();

        if (route.routeKind() == WaypointGroup.RouteKind.REGULAR) {
            row.v9Bare = length(() -> WaypointCodec.encodeV9ForTest(groups, ALL_OFF_NOT_BARE));
            String bare = attempt(() -> WaypointCodec.encode(groups, WaypointCodec.Options.BARE_COORDINATES));
            row.v10Bare = bare == null ? -1 : bare.length();
            row.v10BareShape = shape(bare);
        }
        return row;
    }

    private static String shape(String code) {
        if (code == null) return "error";
        try {
            String transport = code.substring(WaypointCodec.MAGIC.length());
            if (!V10Transport.hasModeSelector(transport)) return "v9-fallback";
            V10Transport.CheckedFrame frame = V10Transport.probe(transport);
            String mode = frame.mode() == V10Transport.MODE_DIRECT ? "A" : "B";
            String detail = "";
            if (frame.contentKind() == 2 && frame.mode() == V10Transport.MODE_DIRECT) {
                detail = "/" + V10BareEntropyCodec.descriptor(frame.semantic()).name().toLowerCase(Locale.ROOT);
            }
            if (frame.contentKind() == 6) {
                detail = V10RouteLibraryCodec.isLibrarySemantic(frame.semantic()) ? "/library" : "/pack";
            }
            return "kind" + frame.contentKind() + detail + "-" + mode;
        } catch (Exception failure) {
            return "v9-fallback";
        }
    }

    private static void summarize(StringBuilder out, String title, List<Row> rows,
                                  Function<Row, Integer> before, Function<Row, Integer> after) {
        long beforeTotal = 0;
        long afterTotal = 0;
        int counted = 0;
        int worse = 0;
        int better = 0;
        double worstRatio = 0;
        String worstName = "";
        long chatBefore = 0;
        long chatAfter = 0;
        for (Row row : rows) {
            int b = before.apply(row);
            int a = after.apply(row);
            if (b <= 0 || a <= 0) continue;
            counted++;
            beforeTotal += b;
            afterTotal += a;
            if (a > b) worse++;
            if (a < b) better++;
            if (b <= 256) chatBefore++;
            if (a <= 256) chatAfter++;
            double ratio = (double) a / b;
            if (ratio > worstRatio) {
                worstRatio = ratio;
                worstName = row.name + " (" + row.points + " pts, " + b + " -> " + a + ")";
            }
        }
        if (counted == 0) {
            out.append(String.format(Locale.ROOT, "-- %s: no routes%n", title));
            return;
        }
        out.append(String.format(Locale.ROOT,
                "-- %s%n   routes=%d  before=%d chars  after=%d chars  delta=%+.2f%%  mean %.1f -> %.1f%n"
                        + "   shorter=%d  longer=%d  fit-in-chat(<=256): %d -> %d  worst regression: %s x%.2f%n",
                title, counted, beforeTotal, afterTotal,
                100.0 * (afterTotal - beforeTotal) / beforeTotal,
                (double) beforeTotal / counted, (double) afterTotal / counted,
                better, worse, chatBefore, chatAfter, worstName, worstRatio));
    }

    private static void distribution(StringBuilder out, String title, List<Row> rows,
                                     Function<Row, String> shape) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Row row : rows) {
            String value = shape.apply(row);
            if (value == null) continue;
            counts.merge(value, 1, Integer::sum);
        }
        out.append("-- ").append(title).append(": ").append(counts).append('\n');
    }

    private static void dungeonAndConfig(StringBuilder out, Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith("waypoints.json"))
                    .sorted().toList();
        }
        long wpd = 0;
        long v10 = 0;
        int collections = 0;
        for (Path file : files) {
            ActiveGroupManager manager = new ActiveGroupManager();
            new Storage(file).load(manager);
            List<WaypointGroup> dungeon = manager.allGroupsList().stream()
                    .filter(g -> g.routeKind() == WaypointGroup.RouteKind.DUNGEON && !g.isEmpty())
                    .map(WaypointGroup::exportSnapshot)
                    .toList();
            if (dungeon.isEmpty()) continue;
            String legacy = attempt(() -> DungeonRoomShareCodec.encode(dungeon));
            String universal = attempt(() -> UniversalShareCodec.encodeDungeon(dungeon));
            if (legacy == null || universal == null) continue;
            collections++;
            wpd += legacy.length();
            v10 += universal.length();
            out.append(String.format(Locale.ROOT, "-- dungeon collection %s: %d routes  WPD=%d  V10=%d  (%+.2f%%)%n",
                    file.getFileName(), dungeon.size(), legacy.length(), universal.length(),
                    100.0 * (universal.length() - legacy.length()) / legacy.length()));
        }
        if (collections > 0) {
            out.append(String.format(Locale.ROOT, "-- dungeon total: WPD=%d  V10=%d  (%+.2f%%)%n",
                    wpd, v10, 100.0 * (v10 - wpd) / wpd));
        }

        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig tweaked = new WaypointerConfig();
        tweaked.setDefaultReachRadius(4.5);
        tweaked.setBeaconOpacity(0.35);
        tweaked.setChatCoordDetection(!defaults.chatCoordDetection());
        tweaked.setShowContributorBadges(!defaults.showContributorBadges());
        for (Map.Entry<String, WaypointerConfig> entry : Map.of(
                "default config", defaults, "tweaked config", tweaked).entrySet()) {
            String legacy = WaypointerConfigCodec.encode(entry.getValue());
            String universal = UniversalShareCodec.encodeConfig(entry.getValue());
            out.append(String.format(Locale.ROOT, "-- %s: WPC=%d  V10=%d  (%+.2f%%)%n",
                    entry.getKey(), legacy.length(), universal.length(),
                    100.0 * (universal.length() - legacy.length()) / legacy.length()));
        }
    }

    private interface Encode {
        String run() throws Exception;
    }

    private static String attempt(Encode encode) {
        try {
            return encode.run();
        } catch (Exception failure) {
            return null;
        }
    }

    private static int length(Encode encode) {
        String code = attempt(encode);
        return code == null ? -1 : code.length();
    }

    private static final class Row {
        String name;
        String zone;
        String kind;
        int points;
        int named;
        int v9Full = -1;
        int v10Full = -1;
        String v10FullShape;
        int v9NoNames = -1;
        int v10NoNames = -1;
        int v9Bare = -1;
        int v10Bare = -1;
        String v10BareShape;

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("zone", zone);
            json.addProperty("kind", kind);
            json.addProperty("points", points);
            json.addProperty("named", named);
            json.addProperty("v9Full", v9Full);
            json.addProperty("v10Full", v10Full);
            json.addProperty("v10FullShape", v10FullShape);
            json.addProperty("v9NoNames", v9NoNames);
            json.addProperty("v10NoNames", v10NoNames);
            json.addProperty("v9Bare", v9Bare);
            json.addProperty("v10Bare", v10Bare);
            json.addProperty("v10BareShape", v10BareShape);
            return json;
        }
    }
}
