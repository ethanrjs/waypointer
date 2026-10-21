package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareSizeBenchmarkTest {

    private record Set(String name, List<List<WaypointGroup>> shares) {}

    @Test
    @EnabledIfSystemProperty(named = "share.corpus", matches = ".+")
    void benchmark() throws Exception {
        List<Set> sets = new ArrayList<>();
        List<WaypointGroup> rooms = DungeonRoomShareCodec.decode(
                Files.readString(Path.of(System.getProperty("share.dungeons")))).routes();
        sets.add(new Set("dungeon rooms (each)", rooms.stream().map(List::of).toList()));
        sets.add(new Set("dungeon pack (110 rooms)", List.of(rooms)));
        List<WaypointGroup> corpus = loadCorpus(Path.of(System.getProperty("share.corpus")));
        sets.add(new Set("corpus routes (each)", corpus.stream().map(List::of).toList()));

        System.out.println(String.format(Locale.ROOT, "%-26s %10s %10s %10s %10s %8s",
                "set", "v10", "v11 json", "v11 cmp", "best", "cmp/v10"));
        for (Set set : sets) {
            V11Coder.costs = new java.util.TreeMap<>();
            long v10 = 0, json = 0, compact = 0, best = 0;
            List<Double> ratios = new ArrayList<>();
            for (List<WaypointGroup> share : set.shares()) {
                boolean dungeon = share.getFirst().routeKind() == WaypointGroup.RouteKind.DUNGEON;
                List<WaypointGroup> projected = V11Codec.project(share, WaypointCodec.Options.FULL_FIDELITY);
                String expected = V11Codec.fingerprint(projected, "", List.of());
                int v10Length = dungeon ? V10DungeonCodec.encode(share).length()
                        : RouteLibraryCodec.encode(share, WaypointCodec.Options.FULL_FIDELITY,
                        RouteLibraryMetadata.capture(null, share)).length();
                int jsonLength = (WaypointCodec.MAGIC + V11Codec.frame(
                        V11Codec.encodeJsonBody(projected, "", List.of()))).length();
                byte[] body = V11CompactCodec.encode(projected, "", List.of());
                int compactLength = jsonLength;
                if (body != null) {
                    compactLength = (WaypointCodec.MAGIC + V11Codec.frame(body)).length();
                    assertEquals(expected, V11Codec.fingerprint(V11CompactCodec.decode(body)),
                            "compact v11 changed " + share.getFirst().name());
                }
                int bestLength = (dungeon ? UniversalShareCodec.encodeDungeon(share)
                        : UniversalShareCodec.encodeWaypoints(share, WaypointCodec.Options.FULL_FIDELITY,
                        RouteLibraryMetadata.capture(null, share))).length();
                v10 += v10Length;
                json += jsonLength;
                compact += compactLength;
                best += bestLength;
                ratios.add((double) compactLength / v10Length);
            }
            java.util.Map<String, Double> costs = V11Coder.costs;
            V11Coder.costs = null;
            double totalBits = costs.values().stream().mapToDouble(Double::doubleValue).sum();
            StringBuilder parts = new StringBuilder();
            costs.forEach((part, cost) -> parts.append(String.format(Locale.ROOT, " %s=%.1f%%", part, 100 * cost / totalBits)));
            System.out.println("    parts:" + parts);
            double[] sorted = ratios.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            System.out.println(String.format(Locale.ROOT, "%-26s %10d %10d %10d %10d %8.3f  (median %.3f, worst %.3f)",
                    set.name(), v10, json, compact, best, (double) compact / v10,
                    sorted[sorted.length / 2], sorted[sorted.length - 1]));
        }
    }

    static List<WaypointGroup> loadCorpus(Path file) throws Exception {
        JsonArray routes = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
        List<WaypointGroup> out = new ArrayList<>();
        for (JsonElement element : routes) {
            JsonObject route = element.getAsJsonObject();
            WaypointImporter.ImportResult imported =
                    WaypointImporter.importAny(route.getAsJsonArray("waypoints").toString());
            for (WaypointGroup group : imported.groups()) {
                group.setName(route.get("name").getAsString());
                String island = route.get("island").getAsString();
                group.setZoneId(island.equals("unknown") ? Zone.UNKNOWN.id() : island);
                out.add(group);
            }
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }
}
