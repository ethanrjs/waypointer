package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRouteImporterTest {


    private static final String SECRET_ROUTES_JSON = """
            {
              "#copyright": "example",
              "Version": 7,
              "Arrow-Trap-1": [
                {
                  "locations": [[14, 69, 4], [19, 69, 4]],
                  "etherwarps": [[10, 75, 3]],
                  "mines": [],
                  "interacts": [],
                  "tnts": [[4, 69, 13]],
                  "secret": { "type": "bat", "location": [26, 77, 10] }
                },
                {
                  "locations": [[3, 69, 5]],
                  "etherwarps": [],
                  "mines": [],
                  "interacts": [],
                  "tnts": [],
                  "secret": { "type": "item", "location": [3, 72, 19] }
                }
              ],
              "Arrow-Trap-1:1": [
                {
                  "locations": [[1, 69, 1]],
                  "secret": { "type": "interact", "location": [2, 69, 2] }
                }
              ],
              "Definitely-Not-A-Room-9": [
                {
                  "locations": [[1, 69, 1]],
                  "secret": { "type": "interact", "location": [2, 69, 2] }
                }
              ]
            }
            """;

    @Test
    void importsSecretRoutesStagesAsOrderedActionsAndPreservesVariants() {
        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(SECRET_ROUTES_JSON);

        assertEquals(DungeonRouteImporter.Format.SECRET_ROUTES, result.format());
        assertEquals(2, result.groups().size());
        assertEquals(9, result.waypointCount());
        assertEquals(0, result.skippedVariants());
        assertEquals(List.of("Definitely-Not-A-Room-9"), result.unmatchedRooms());

        WaypointGroup room = result.groups().get(0);
        assertEquals("arrow-trap", room.zoneId(), "DRM name suffix should map onto the catalog id");
        assertEquals(WaypointGroup.RouteKind.DUNGEON, room.routeKind());

        assertEquals("Arrow Trap, route 1", room.name());
        Waypoint etherwarp = room.waypoints().get(0);
        assertTrue(etherwarp.hasFlag(Waypoint.FLAG_DUNGEON_ETHERWARP));
        assertEquals("TP", etherwarp.name());

        Waypoint tnt = room.waypoints().get(1);
        assertTrue(tnt.hasFlag(Waypoint.FLAG_DUNGEON_SUPERBOOM));
        assertTrue(tnt.isSubwaypoint());

        Waypoint bat = room.waypoints().get(2);
        assertTrue(bat.hasFlag(Waypoint.FLAG_DUNGEON_BAT));
        assertEquals(26, bat.x());
        assertEquals(77, bat.y());
        assertEquals(10, bat.z());

        Waypoint item = room.waypoints().get(5);
        assertTrue(item.hasFlag(Waypoint.FLAG_DUNGEON_ITEM));
        assertTrue(item.hasFlag(Waypoint.FLAG_DUNGEON_SECRET));

        assertEquals("Arrow Trap, route 2", result.groups().get(1).name());
    }

    private static final String BOSS_JSON = """
            {
              "f7boss": [
                {
                  "locations": [[73, 221, 16], [69, 221, 41], [69, 205, 41], [59, 167, 50]],
                  "etherwarps": [],
                  "mines": [[69, 220, 41], [19, 168, 72], [19, 167, 72]],
                  "bonzo_staffs": [[53, 168, 56]]
                }
              ]
            }
            """;

    @Test
    void secretRoutesBossRoutesBecomeFloorRoutesInWorldCoordinates() {
        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(BOSS_JSON);
        assertEquals(List.of(), result.unmatchedRooms());
        WaypointGroup boss = result.groups().getFirst();
        assertEquals("dungeon_f7", boss.zoneId());
        assertEquals(WaypointGroup.RouteKind.REGULAR, boss.routeKind());
        assertEquals("F7 Boss, route 1", boss.name());
        assertTrue(boss.usesDungeonSteps());
        assertEquals(69, boss.get(0).x());
        assertEquals(220, boss.get(0).y());
        assertTrue(boss.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_MINE));

        List<String> names = new ArrayList<>();
        for (Waypoint waypoint : boss.waypoints()) names.add(waypoint.name());
        assertTrue(names.contains("Bonzo"));
        assertFalse(names.contains("Chest"), "a boss step has no made-up secret");
        Waypoint end = boss.waypoints().stream().filter(w -> w.name().equals("Exit"))
                .findFirst().orElseThrow();
        assertEquals(59, end.x());
        assertTrue(end.hasFlag(Waypoint.FLAG_SKIP_ON_STAND));
    }

    @Test
    void secretRoutesBossNamesMapToTheirFloor() {
        assertEquals("dungeon_f7", DungeonRouteImporter.secretRoutesBossFloor("f7boss"));
        assertEquals("dungeon_f6", DungeonRouteImporter.secretRoutesBossFloor("F6-Boss"));
        assertEquals("dungeon_f7", DungeonRouteImporter.secretRoutesBossFloor("m7boss"));
        assertNull(DungeonRouteImporter.secretRoutesBossFloor("f8boss"));
        assertNull(DungeonRouteImporter.secretRoutesBossFloor("Arrow-Trap-1"));
    }

    @Test
    void secretRoutesMineBlocksCanBeDugInAnyOrder() {
        WaypointGroup room = DungeonRouteImporter.parse("""
                {
                  "Arrow-Trap-1": [
                    {
                      "locations": [],
                      "etherwarps": [[10, 75, 3]],
                      "mines": [[11, 70, 3], [11, 71, 3]],
                      "secret": { "type": "interact", "location": [12, 70, 3] }
                    }
                  ]
                }
                """).groups().getFirst();
        assertFalse(room.get(0).hasFlag(Waypoint.FLAG_STEP_ANY_ORDER));
        assertTrue(room.get(1).hasFlag(Waypoint.FLAG_SKIP_ON_MINE));
        assertTrue(room.isAnyOrderStepMember(1));
        assertTrue(room.isAnyOrderStepMember(2));
        assertFalse(room.isAnyOrderStepMember(3), "the secret stays in order");
        room.advancePast(0);
        assertEquals(3, room.currentIndex());
    }

    @Test
    void secretRoutesKeepEditablePathGuidesWithoutAddingCompletionSteps() {
        WaypointGroup room = DungeonRouteImporter.parse(SECRET_ROUTES_JSON).groups().getFirst();
        assertEquals(2, room.mainWaypointCount());
        Waypoint guide = room.get(3);
        assertEquals(14, guide.x());
        assertEquals(19, room.get(4).x());
        assertEquals(0, room.parentMainIndex(3));
        assertEquals(5, room.parentMainIndex(6));
        assertTrue(guide.isSubwaypoint());
        assertTrue(guide.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT));
        assertTrue(guide.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
        assertEquals(0, guide.flags() & (Waypoint.DUNGEON_COMPLETION_FLAGS | Waypoint.FLAG_DUNGEON_SECRET));
        room.advancePast(0);
        room.advancePast(1);
        room.advancePast(2);
        assertEquals(5, room.currentIndex(), "passive guides must not block the next secret stage");
        room.set(3, guide.withColor(0x123456));
        WaypointGroup saved = DungeonRoomShareCodec.decode(
                DungeonRoomShareCodec.encode(List.of(room))).routes().getFirst();
        assertEquals(room.waypoints(), saved.waypoints());
    }

    @Test
    void currentPackExitsAreWalkingStepsRatherThanSecrets() {
        for (String type : List.of("exit", "exitroute")) {
            Waypoint exit = DungeonRouteImporter.parse("{\"Arrow-Trap-1\":[{\"secret\":{\"type\":\""
                    + type + "\",\"location\":[1,70,1]}}]}")
                    .groups().getFirst().get(0);
            assertEquals("Exit", exit.name());
            assertTrue(exit.hasFlag(Waypoint.FLAG_SKIP_ON_STAND));
            assertEquals(0, exit.flags() & (Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET));
        }
    }

    @Test
    void navigationStagesDoNotCreateCollectiblesOrDummyOriginTargets() {
        WaypointGroup route = DungeonRouteImporter.parse("""
                {"Arrow-Trap-1":[
                  {"locations":[[25,80,9],[25,69,9]],"etherwarps":[[25,68,9]],
                   "secret":{"type":"dummy","location":[0,0,0]}},
                  {"locations":[[10,70,10]],"secret":{"type":"dummy","location":[0,0,0]}},
                  {"secret":{"type":"prince","location":[12,70,12]}}
                ]}
                """).groups().getFirst();

        assertEquals(3, route.mainWaypointCount());
        assertEquals(6, route.size());
        for (Waypoint point : route.waypoints()) {
            assertFalse(point.hasFlag(Waypoint.FLAG_DUNGEON_SECRET));
            assertFalse(point.x() == 0 && point.y() == 0 && point.z() == 0);
        }
        assertEquals("Move", route.get(3).name());
        assertEquals(10, route.get(3).x());
        assertEquals("Prince", route.get(5).name());
        assertEquals(0xFFC0CB, route.get(5).color());
        assertTrue(route.get(5).hasFlag(Waypoint.FLAG_SKIP_ON_STAND));
    }

    @Test
    void importedSecretRoutesUseBundledCatalogRoomIdentity() {
        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(SECRET_ROUTES_JSON);
        assertEquals("arrow-trap", result.groups().getFirst().zoneId());
        assertTrue(DungeonRoomData.entry("arrow-trap").hasCoreHashes());
    }

    @Test
    void importsPinnedSecretRoutesNamesWhoseUpstreamCoreHashesMatchCatalogRooms() {
        String json = """
                {
                  "Banners-1": [{"secret":{"type":"interact","location":[1,70,1]}}],
                  "Double-Stair-3": [{"secret":{"type":"item","location":[2,71,2]}}],
                  "Redstone-Skull-3": [{"secret":{"type":"interact","location":[3,72,3]}}]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(List.of("banners", "staircase", "redstone-crypt"),
                result.groups().stream().map(WaypointGroup::zoneId).toList());
        assertEquals(3, result.waypointCount());
        assertTrue(result.unmatchedRooms().isEmpty());
    }

    @Test
    void mapsKnownSecretRoutesNameCollisionsByCoreHash() {
        String json = """
                {
                  "Crypts-1": [{"secret":{"type":"interact","location":[1,70,1]}}],
                  "Draw-Bridge-6": [{"secret":{"type":"interact","location":[2,70,2]}}],
                  "Rail-Track-9": [{"secret":{"type":"interact","location":[3,70,3]}}],
                  "Lava-Skulls-3": [{"secret":{"type":"interact","location":[4,70,4]}}],
                  "Waterfall-2": [{"secret":{"type":"interact","location":[5,70,5]}}]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(List.of("criss-cross", "pirate", "rails", "ritual", "small-waterfall"),
                result.groups().stream().map(WaypointGroup::zoneId).toList());
        assertTrue(result.unmatchedRooms().isEmpty());
    }

    @Test
    void preservesUnambiguousSuffixFreeNamesUsingPinnedSourceCores() {
        String json = """
                {
                  "Arrow-Trap": [{"secret":{"type":"interact","location":[1,70,1]}}],
                  "Crypts": [{"secret":{"type":"interact","location":[2,70,2]}}],
                  "Draw-Bridge": [{"secret":{"type":"interact","location":[3,70,3]}}],
                  "Rail-Track": [{"secret":{"type":"interact","location":[4,70,4]}}],
                  "Lava-Skulls": [{"secret":{"type":"interact","location":[5,70,5]}}],
                  "Silvers-Sword": [{"secret":{"type":"interact","location":[6,70,6]}}]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(List.of("arrow-trap", "criss-cross", "pirate", "rails", "ritual",
                        "silver-sword"),
                result.groups().stream().map(WaypointGroup::zoneId).toList());
        assertTrue(result.unmatchedRooms().isEmpty());
    }

    @Test
    void doesNotConflateAmbiguousOrRetiredSecretRoutesNames() {
        String json = """
                {
                  "Waterfall": [{"secret":{"type":"interact","location":[1,70,1]}}],
                  "Four-Banner-1": [{"secret":{"type":"interact","location":[2,70,2]}}]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertTrue(result.groups().isEmpty());
        assertEquals(List.of("Waterfall", "Four-Banner-1"), result.unmatchedRooms());
    }

    @Test
    void mapsPreviouslyUnresolvedSecretRoutesRoomKindsAndVariants() {
        String json = """
                {
                  "Blaze-Room-1-Low": [{"secret":{"type":"interact","location":[1,70,1]}}],
                  "Lava-Pool-3": [{"secret":{"type":"interact","location":[2,70,2]}}],
                  "Mini-Rail-Track-3:2": [{"secret":{"type":"interact","location":[3,70,3]}}],
                  "Trap-Very-Hard-3": [{"secret":{"type":"interact","location":[4,70,4]}}],
                  "Boxes-Room": [{"secret":{"type":"interact","location":[5,70,5]}}],
                  "Entrance Room": [{"secret":{"type":"interact","location":[6,70,6]}}],
                  "Fairy Room": [{"secret":{"type":"interact","location":[7,70,7]}}],
                  "Blood Room": [{"secret":{"type":"interact","location":[8,70,8]}}]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(List.of("higher-blaze", "lava-pit", "rail-track", "new-trap",
                        "boulder", "entrance", "fairy", "blood"),
                result.groups().stream().map(WaypointGroup::zoneId).toList());
        assertEquals("Rail Track, route 3", result.groups().get(2).name());
        assertTrue(result.unmatchedRooms().isEmpty());
    }

    @Test
    void pinnedSecretRoutesMappingUniquelyJoinsEveryUpstreamRoom() throws Exception {
        try (InputStream stream = Objects.requireNonNull(DungeonRouteImporterTest.class
                .getResourceAsStream(
                        "/assets/waypointer/dungeons/catacombs/secret-routes-room-cores.json"));
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals("a6a88c831206df5c16ec49d416c9d7e6b5a533e0",
                    root.getAsJsonObject("source").get("commit").getAsString());
            JsonObject sourceRooms = root.getAsJsonObject("rooms");
            assertEquals(140, sourceRooms.size());

            for (var sourceRoom : sourceRooms.entrySet()) {
                List<Integer> coreHashes = new ArrayList<>();
                for (JsonElement core : sourceRoom.getValue().getAsJsonArray()) {
                    coreHashes.add(core.getAsInt());
                }
                assertNotNull(DungeonRouteImporter.matchUniqueCoreRoom(
                                DungeonRoomData.allEntries(), coreHashes),
                        () -> sourceRoom.getKey() + " must identify exactly one catalog room");
            }
        }
    }

    @Test
    void refusesAmbiguousSecretRoutesCoreHashes() {
        DungeonRoomCatalogEntry first = roomWithCores("first", 101, 202);
        DungeonRoomCatalogEntry second = roomWithCores("second", 202, 303);

        assertNull(DungeonRouteImporter.matchUniqueCoreRoom(
                List.of(first, second), List.of(202)));
        assertEquals(first, DungeonRouteImporter.matchUniqueCoreRoom(
                List.of(first, second), List.of(101)));
    }

    private static DungeonRoomCatalogEntry roomWithCores(String id, Integer... coreHashes) {
        return new DungeonRoomCatalogEntry(id, id, DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE, List.of(coreHashes), List.of(), 0, 0, 0);
    }


    private static final String ODIN_PACK_JSON = """
            {
              "Altar": [
                {
                  "blockPos": {"x": 10, "y": 70, "z": 12},
                  "color": "#00FF00FF",
                  "filled": false,
                  "depth": false,
                  "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1},
                  "title": "lever here",
                  "type": "SECRET"
                },
                {
                  "blockPos": {"x": 15, "y": 82, "z": 3},
                  "color": "#FFAA00FF",
                  "filled": true,
                  "depth": false,
                  "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1},
                  "type": "ETHERWARP"
                },
                {
                  "x": 4, "y": 70, "z": 4,
                  "color": "#112233FF",
                  "filled": false,
                  "depth": true,
                  "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1},
                  "secret": true
                },
                {
                  "blockPos": {"x": 7, "y": 71, "z": 8},
                  "filled": false,
                  "depth": false,
                  "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1},
                  "title": "watch the trap"
                }
              ],
              "Not A Real Odin Room": [
                {
                  "blockPos": {"x": 1, "y": 70, "z": 1},
                  "filled": false,
                  "depth": false,
                  "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1}
                }
              ]
            }
            """;

    @Test
    void importsOdinPackWaypointsWithTypesTitlesAndColors() {
        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(ODIN_PACK_JSON);

        assertEquals(DungeonRouteImporter.Format.ODIN_PACK, result.format());
        assertEquals(List.of("Not A Real Odin Room"), result.unmatchedRooms());
        assertEquals(1, result.groups().size());
        assertEquals(4, result.waypointCount());

        WaypointGroup altar = result.groups().get(0);
        assertEquals("altar", altar.zoneId());

        Waypoint secret = altar.waypoints().get(0);
        assertTrue(secret.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));
        assertEquals("lever here", secret.name());
        assertEquals(0x00FF00, secret.color(), "current Odin #RRGGBBAA should keep its RGB");
        assertEquals(10, secret.x());

        Waypoint ether = altar.waypoints().get(1);
        assertTrue(ether.hasFlag(Waypoint.FLAG_DUNGEON_ETHERWARP));
        assertFalse(ether.hasFlag(Waypoint.FLAG_DEPTH_CHECKED),
                "an explicit Odin depth setting must not be replaced by type defaults");

        Waypoint legacySecret = altar.waypoints().get(2);
        assertTrue(legacySecret.hasFlag(Waypoint.FLAG_DUNGEON_SECRET),
                "legacy 'secret: true' waypoints with flat x/y/z should still import");
        assertEquals(0x112233, legacySecret.color());
        assertTrue(legacySecret.hasFlag(Waypoint.FLAG_DEPTH_CHECKED));

        Waypoint marker = altar.waypoints().get(3);
        assertTrue(marker.isSubwaypoint(), "untyped waypoints import as persistent markers");
        assertEquals("watch the trap", marker.name());
    }

    @Test
    void keepsOdinLeadingMarkerStylesAndLegacyCoordinatesInEditableRoutes() {
        String json = """
                {"Altar": [
                  {"blockPos":{"field_11175":2,"field_11174":71,"field_11173":3},
                   "title":"\\u00A7aUseful\\nmarker", "color":"#12AB3480", "filled":true, "depth":true},
                  {"blockPos":{"x":4,"y":72,"z":5}, "type":"SECRET", "title":"Enter text"},
                  {"blockPos":{"x":6,"y":73,"z":7}, "title":"Enter text", "filled":false}
                ]}
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);
        WaypointGroup route = result.groups().getFirst();
        assertEquals(3, result.waypointCount());
        assertEquals(WaypointGroup.RouteKind.DUNGEON, route.routeKind());
        assertEquals(WaypointGroup.GradientMode.MANUAL, route.gradientMode());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, route.loadMode());
        assertFalse(route.runtimeOnly());
        assertFalse(route.temp());
        assertEquals("Secret 1", route.get(0).name());
        Waypoint marker = route.get(1);
        assertTrue(marker.isSubwaypoint());
        assertTrue(marker.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
        assertTrue(marker.hasFlag(Waypoint.FLAG_DEPTH_CHECKED));
        assertEquals(2, marker.x());
        assertEquals(71, marker.y());
        assertEquals(3, marker.z());
        assertEquals(0x12AB34, marker.color());
        assertEquals(com.babbur.waypointer.codec.WaypointCodec.Options.sanitizeLabel(
                "\u00A7aUseful\nmarker"), marker.name());
        assertEquals("", route.get(2).name());

        route.set(1, marker.withName("My marker").withColor(0xABCDE0));
        WaypointGroup saved = DungeonRoomShareCodec.decode(
                DungeonRoomShareCodec.encode(List.of(route))).routes().getFirst();
        assertEquals(route.waypoints(), saved.waypoints());
    }

    @Test
    void reportsOnlyWaypointsInReturnedGroupsIncludingPearlTargets() {
        String secretRoutes = """
                {"Arrow-Trap-1":[{
                  "locations":[[1,70,1],[2,70,2]], "enderpearls":[[1,70,1]],
                  "secret":{"type":"interact","location":[3,70,3]}
                }]}
                """;
        DungeonRouteImporter.Result pearl = DungeonRouteImporter.parse(secretRoutes);
        assertEquals(5, pearl.waypointCount());
        assertEquals(5, pearl.groups().getFirst().size());
        assertTrue(pearl.groups().getFirst().get(1).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET));

        String odinAliases = """
                {
                  "Altar":[{"blockPos":{"x":1,"y":70,"z":1},"type":"SECRET"}],
                  "altar":[{"blockPos":{"x":2,"y":70,"z":2},"type":"SECRET"}]
                }
                """;
        DungeonRouteImporter.Result aliases = DungeonRouteImporter.parse(odinAliases);
        assertEquals(1, aliases.groups().size());
        assertEquals(1, aliases.waypointCount());
        assertEquals(2, aliases.groups().getFirst().get(0).x());
    }

    @Test
    void keepsOdinRoomNameAliasesIndependentFromSecretRoutesMapping() {
        String json = """
                {
                  "Crypts-1": [
                    {
                      "blockPos": {"x": 1, "y": 70, "z": 1},
                      "filled": false,
                      "type": "SECRET"
                    }
                  ]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(DungeonRouteImporter.Format.ODIN_PACK, result.format());
        assertEquals("crypt", result.groups().getFirst().zoneId());
    }

    @Test
    void keepsPreviouslyAcceptedOpaqueLeadingAlphaColors() {
        String legacy = ODIN_PACK_JSON.replace("#00FF00FF", "#FF00FF00");

        Waypoint secret = DungeonRouteImporter.parse(legacy)
                .groups().get(0).waypoints().get(0);

        assertEquals(0x00FF00, secret.color());
    }

    @Test
    void acceptsOdinShareStringsAsBase64Gzip() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(ODIN_PACK_JSON.getBytes(StandardCharsets.UTF_8));
        }
        String shared = Base64.getEncoder().encodeToString(compressed.toByteArray());

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(shared);

        assertEquals(DungeonRouteImporter.Format.ODIN_PACK, result.format());
        assertEquals(4, result.waypointCount());
    }


    @Test
    void importsWaypointerNativeRoomJson() {
        String json = """
                {
                  "schema": 1,
                  "rooms": [
                    {
                      "id": "native-room",
                      "name": "Native Room",
                      "type": "ROOM",
                      "shape": "ONE_BY_ONE",
                      "waypoints": [
                        {"id": "s1", "secretIndex": 1, "category": "chest",
                         "x": 5, "y": 70, "z": 5, "highlights": []}
                      ]
                    }
                  ]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(DungeonRouteImporter.Format.WAYPOINTER, result.format());
        assertEquals(1, result.waypointCount());
        assertEquals("native-room", result.groups().get(0).zoneId());
    }

    @Test
    void rejectsUnrecognizedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> DungeonRouteImporter.parse("hello"));
        assertThrows(IllegalArgumentException.class, () -> DungeonRouteImporter.parse("{\"a\": 1}"));
        assertThrows(IllegalArgumentException.class, () -> DungeonRouteImporter.parse("  "));
    }

    @Test
    void normalizesMalformedNestedRouteDataToAnImportFailure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DungeonRouteImporter.parse("{\"Altar\":[{\"blockPos\":[]}] }"));

        assertTrue(error.getMessage().contains("route import payload is malformed"));
    }

    @Test
    void discardsPositionsOutsideRoomLocalRange() {
        String json = """
                {
                  "Altar": [
                    {
                      "blockPos": {"x": 9999, "y": 70, "z": 12},
                      "filled": false, "depth": false,
                      "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1},
                      "type": "SECRET"
                    }
                  ]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(0, result.waypointCount(),
                "world-coordinate data must not import as room-local");
    }

    @Test
    void discardsMinimumIntegerRoomLocalCoordinates() {
        String json = """
                {
                  "Altar": [
                    {
                      "blockPos": {"x": -2147483648, "y": 70, "z": 12},
                      "filled": false, "depth": false,
                      "aabb": {"minX": 0, "minY": 0, "minZ": 0, "maxX": 1, "maxY": 1, "maxZ": 1},
                      "type": "SECRET"
                    }
                  ]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(0, result.waypointCount(),
                "Integer.MIN_VALUE must not pass the room-local absolute bound");
    }
}
