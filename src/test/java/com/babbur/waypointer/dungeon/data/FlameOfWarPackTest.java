package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlameOfWarPackTest {

    private static final String ANDESITE = """
            {"Andesite-2":[
              {"secret":{"type":"item","location":[1,69,28]},"locations":[[2,69,20]],
               "etherwarps":[[2,69,24]],"mines":[[3,70,25],[3,71,25]],"interacts":[],"tnts":[],
               "enderpearls":[],"enderpearlangles":[]},
              {"secret":{"type":"interact","location":[6,69,22]},"locations":[],
               "etherwarps":[],"mines":[],"interacts":[],"tnts":[[3,71,20]],
               "enderpearls":[],"enderpearlangles":[]},
              {"secret":{"type":"interact","location":[9,69,9]},"locations":[],
               "etherwarps":[],"mines":[],"interacts":[],"tnts":[],
               "enderpearls":[],"enderpearlangles":[]},
              {"secret":{"type":"exit","location":[12,69,27]},"locations":[],
               "etherwarps":[],"mines":[],"interacts":[],"tnts":[],
               "enderpearls":[],"enderpearlangles":[]}]}
            """;

    private static final Map<String, List<FlameOfWarPack.Secret>> SECRETS = Map.of("andesite-2", List.of(
            new FlameOfWarPack.Secret(6, 69, 22, "wither"),
            new FlameOfWarPack.Secret(1, 69, 28, "item")));

    @Test
    void everyKindGetsOneLookAndTheRightRules() {
        WaypointGroup route = build().routes().getFirst();

        assertEquals("FlameOfWar", route.name());
        assertEquals(WaypointGroup.RouteKind.DUNGEON, route.routeKind());
        Waypoint breaker = only(route, Waypoint.FLAG_DUNGEON_DUNGEONBREAKER).getFirst();
        assertTrue(breaker.hasFlag(Waypoint.FLAG_SKIP_ON_MINE));
        assertTrue(route.waypoints().stream().noneMatch(w -> w.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)),
                "nothing in the pack is line-of-sight checked");
        assertEquals(DungeonSecretCategory.DUNGEONBREAKER.defaultColor, breaker.color());
        assertEquals(2, only(route, Waypoint.FLAG_DUNGEON_DUNGEONBREAKER).size());
        assertTrue(route.waypoints().stream().filter(w -> w.hasFlag(Waypoint.FLAG_SKIP_ON_MINE))
                .allMatch(w -> w.hasFlag(Waypoint.FLAG_DUNGEON_DUNGEONBREAKER)));

        Waypoint warp = only(route, Waypoint.FLAG_DUNGEON_ETHERWARP).getFirst();
        assertEquals("", warp.name());
        assertEquals(DungeonSecretCategory.ETHERWARP.defaultColor, warp.color());

        Waypoint item = only(route, Waypoint.FLAG_DUNGEON_ITEM).getFirst();
        assertEquals("Item", item.name());
        assertTrue(item.hasFlag(Waypoint.FLAG_DUNGEON_SECRET), "items must still count as secrets");

        assertEquals("Wither Essence", at(route, 6, 69, 22).name());
        assertEquals(DungeonSecretCategory.WITHER.defaultColor, at(route, 6, 69, 22).color());
        assertEquals("Secret", at(route, 9, 69, 9).name());
        Waypoint exit = at(route, 12, 69, 27);
        assertEquals("Exit", exit.name());
        assertFalse(exit.hasFlag(Waypoint.FLAG_DUNGEON_SECRET));
    }

    @Test
    void unmatchedSecretsAreReportedForReview() {
        List<String> report = build().report();

        assertTrue(report.stream().anyMatch(line -> line.contains("CHECK Andesite-2: secret at 9 69 9")));
    }

    @Test
    void itemsMoveToWhereTheSecretTableHasThem() {
        String beams = """
                {"Beams-2":[
                  {"secret":{"type":"item","location":[8,88,23]},"locations":[],
                   "etherwarps":[[4,84,18]],"mines":[],"interacts":[],"tnts":[],
                   "enderpearls":[],"enderpearlangles":[]}]}
                """;
        FlameOfWarPack.Pack pack = FlameOfWarPack.build(JsonParser.parseString(beams).getAsJsonObject(),
                Map.of("beams-2", List.of(new FlameOfWarPack.Secret(9, 92, 25, "item"))), Map.of(), true);

        Waypoint item = only(pack.routes().getFirst(), Waypoint.FLAG_DUNGEON_ITEM).getFirst();
        assertEquals(List.of(9, 92, 25), List.of(item.x(), item.y(), item.z()));
        assertTrue(pack.report().stream().anyMatch(line -> line.contains("MOVED Beams-2: item 8 88 23 -> 9 92 25")));

        FlameOfWarPack.Pack farOff = FlameOfWarPack.build(JsonParser.parseString(beams).getAsJsonObject(),
                Map.of("beams-2", List.of(new FlameOfWarPack.Secret(30, 92, 25, "item"))), Map.of(), true);
        Waypoint kept = only(farOff.routes().getFirst(), Waypoint.FLAG_DUNGEON_ITEM).getFirst();
        assertEquals(List.of(8, 88, 23), List.of(kept.x(), kept.y(), kept.z()));

        FlameOfWarPack.Pack asRecorded = FlameOfWarPack.build(JsonParser.parseString(beams).getAsJsonObject(),
                Map.of("beams-2", List.of(new FlameOfWarPack.Secret(9, 92, 25, "item"))), Map.of());
        Waypoint unchanged = only(asRecorded.routes().getFirst(), Waypoint.FLAG_DUNGEON_ITEM).getFirst();
        assertEquals(List.of(8, 88, 23), List.of(unchanged.x(), unchanged.y(), unchanged.z()),
                "by default the pack keeps SecretRoutes' positions");
    }

    @Test
    void packSurvivesTheShareCodec() {
        List<WaypointGroup> routes = build().routes();

        List<WaypointGroup> decoded = DungeonRoomShareCodec.decode(DungeonRoomShareCodec.encode(routes)).routes();

        assertEquals(routes.getFirst().waypoints(), decoded.getFirst().waypoints());
        assertEquals("FlameOfWar", decoded.getFirst().name());
    }

    @Test
    @EnabledIfSystemProperty(named = "fow.routes", matches = ".+")
    void writePack() throws Exception {
        JsonObject routes = JsonParser.parseString(
                Files.readString(Path.of(System.getProperty("fow.routes")))).getAsJsonObject();
        FlameOfWarPack.Pack pack = FlameOfWarPack.build(routes,
                FlameOfWarPack.readSecretTable(Path.of(System.getProperty("fow.secrets"))),
                FlameOfWarPack.catalogSecretCounts(), Boolean.getBoolean("fow.snap"));
        Path out = Files.createDirectories(Path.of(System.getProperty("fow.out")));
        String share = com.babbur.waypointer.codec.UniversalShareCodec.encodeDungeon(pack.routes());
        List<WaypointGroup> decoded = ((com.babbur.waypointer.codec.UniversalShareCodec.DungeonRoutes)
                com.babbur.waypointer.codec.UniversalShareCodec.decode(share)).result().groups();
        assertEquals(pack.routes().size(), decoded.size());
        assertTrue(decoded.stream().flatMap(route -> route.waypoints().stream())
                .noneMatch(w -> w.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)), "pack must not use line-of-sight");
        Files.writeString(out.resolve("flameofwar-routes.txt"), share);
        Files.writeString(out.resolve("flameofwar-routes.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(FlameOfWarPack.reviewJson(pack.routes())));
        Files.write(out.resolve("flameofwar-report.txt"), pack.report());
        pack.report().stream().limit(3).forEach(System.out::println);
    }

    private static FlameOfWarPack.Pack build() {
        return FlameOfWarPack.build(JsonParser.parseString(ANDESITE).getAsJsonObject(), SECRETS, Map.of());
    }

    private static List<Waypoint> only(WaypointGroup route, int flag) {
        return route.waypoints().stream().filter(w -> w.hasFlag(flag)).toList();
    }

    private static Waypoint at(WaypointGroup route, int x, int y, int z) {
        return route.waypoints().stream()
                .filter(w -> w.x() == x && w.y() == y && w.z() == z && !w.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT))
                .findFirst().orElseThrow();
    }
}
