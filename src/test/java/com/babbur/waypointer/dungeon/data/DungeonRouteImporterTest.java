package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.DungeonWaypointTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRouteImporterTest {

    @BeforeEach
    @AfterEach
    void clearCustomRooms() {
        DungeonRoomData.clearAllCustom();
    }

    // ---- SecretRoutes ------------------------------------------------------

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
        assertEquals(2, result.definitions().size());
        assertEquals(5, result.waypointCount());
        assertEquals(0, result.skippedVariants());
        assertEquals(List.of("Definitely-Not-A-Room-9"), result.unmatchedRooms());

        DungeonRoomDefinition room = result.definitions().get(0);
        assertEquals("arrow-trap", room.id(), "DRM name suffix should map onto the catalog id");

        assertEquals("Arrow Trap, route 1", room.displayName());
        DungeonWaypoint etherwarp = room.waypoints().get(0);
        assertEquals(1, etherwarp.secretIndex());
        assertEquals(DungeonWaypointTrigger.ETHERWARP, etherwarp.trigger());
        assertEquals("TP", etherwarp.name());

        DungeonWaypoint tnt = room.waypoints().get(1);
        assertEquals(1, tnt.secretIndex());
        assertEquals(DungeonWaypointTrigger.USE_SUPERBOOM, tnt.trigger());

        DungeonWaypoint bat = room.waypoints().get(2);
        assertEquals(1, bat.secretIndex());
        assertEquals(DungeonSecretCategory.BAT, bat.category());
        assertEquals(DungeonWaypointTrigger.KILL_BAT, bat.trigger());
        assertEquals(26, bat.x());
        assertEquals(77, bat.y());
        assertEquals(10, bat.z());
        assertTrue(bat.highlights().isEmpty());

        DungeonWaypoint item = room.waypoints().get(3);
        assertEquals(2, item.secretIndex());
        assertEquals(DungeonWaypointTrigger.PICKUP_ITEM, item.trigger());

        assertEquals("Arrow Trap, route 2", result.definitions().get(1).displayName());
    }

    @Test
    void importedSecretRoutesRoomsInheritBundledCoreHashes() {
        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(SECRET_ROUTES_JSON);
        assertEquals(1, DungeonRoomData.importCustomDefinitions(result.definitions()));

        DungeonRoomDefinition merged = DungeonRoomData.definition("arrow-trap");
        assertNotNull(merged);
        assertEquals(4, merged.waypoints().size());
        assertTrue(merged.hasCoreHashes(),
                "custom room must keep matching by the bundled core hash after import");
    }

    @Test
    void importsPinnedSecretRoutesNamesWhoseUpstreamCoreHashesMatchCatalogRooms() {
        String json = """
                {
                  "Four-Banner-1": [{"secret":{"type":"interact","location":[1,70,1]}}],
                  "Double-Stair-3": [{"secret":{"type":"item","location":[2,71,2]}}],
                  "Redstone-Skull-3": [{"secret":{"type":"interact","location":[3,72,3]}}]
                }
                """;

        DungeonRouteImporter.Result result = DungeonRouteImporter.parse(json);

        assertEquals(List.of("banners", "staircase", "redstone-crypt"),
                result.definitions().stream().map(DungeonRoomDefinition::id).toList());
        assertEquals(3, result.waypointCount());
        assertTrue(result.unmatchedRooms().isEmpty());
    }

    // ---- Odin packs -----------------------------------------------------------

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
        assertEquals(1, result.definitions().size());
        assertEquals(4, result.waypointCount());

        DungeonRoomDefinition altar = result.definitions().get(0);
        assertEquals("altar", altar.id());

        DungeonWaypoint secret = altar.waypoints().get(0);
        assertEquals(1, secret.secretIndex());
        assertEquals(DungeonWaypointTrigger.ANY_SECRET, secret.trigger());
        assertEquals("lever here", secret.name());
        assertEquals(0x00FF00, secret.color(), "current Odin #RRGGBBAA should keep its RGB");
        assertEquals(10, secret.x());

        DungeonWaypoint ether = altar.waypoints().get(1);
        assertEquals(2, ether.secretIndex());
        assertEquals(DungeonSecretCategory.AOTV, ether.category());
        assertEquals(DungeonWaypointTrigger.ETHERWARP, ether.trigger());

        DungeonWaypoint legacySecret = altar.waypoints().get(2);
        assertEquals(3, legacySecret.secretIndex(),
                "legacy 'secret: true' waypoints with flat x/y/z should still import");
        assertEquals(DungeonWaypointTrigger.ANY_SECRET, legacySecret.trigger());
        assertEquals(0x112233, legacySecret.color());

        DungeonWaypoint marker = altar.waypoints().get(3);
        assertEquals(0, marker.secretIndex(), "untyped waypoints import as persistent markers");
        assertEquals(DungeonWaypointTrigger.MANUAL, marker.trigger());
        assertEquals("watch the trap", marker.name());
    }

    @Test
    void keepsPreviouslyAcceptedOpaqueLeadingAlphaColors() {
        String legacy = ODIN_PACK_JSON.replace("#00FF00FF", "#FF00FF00");

        DungeonWaypoint secret = DungeonRouteImporter.parse(legacy)
                .definitions().get(0).waypoints().get(0);

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

    // ---- native + error paths -------------------------------------------------

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
        assertEquals("native-room", result.definitions().get(0).id());
    }

    @Test
    void rejectsUnrecognizedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> DungeonRouteImporter.parse("hello"));
        assertThrows(IllegalArgumentException.class, () -> DungeonRouteImporter.parse("{\"a\": 1}"));
        assertThrows(IllegalArgumentException.class, () -> DungeonRouteImporter.parse("  "));
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
