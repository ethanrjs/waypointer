package com.babbur.waypointer.codec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.config.WaypointerConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkysoftWaypointImporterTest {
    @Test
    void importsDisplayOverridesAndPersistsThemLocally(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var group = WaypointImporter.importAny("""
                {"version":2,"groups":[{"route":true,"lines":false,"filled":true,
                "styles":["BLOCK","BEACON"],"points":[
                {"x":1,"y":2,"z":3},
                {"x":2,"y":2,"z":3,"styles":["MARKER"]},
                {"x":3,"y":2,"z":3,"styles":[]},
                {"x":4,"y":2,"z":3,"styles":["BEACON"]}]}]}
                """).groups().getFirst();
        var manager = new ActiveGroupManager();
        manager.add(group);
        var storage = new Storage(tempDir.resolve("waypoints.json"));
        storage.save(manager);
        var reloaded = new ActiveGroupManager();
        storage.load(reloaded);
        var restored = reloaded.allGroupsList().getFirst();
        assertEquals(group.styleOverride(), restored.styleOverride());
        for (int i = 0; i < group.size(); i++) assertEquals(group.get(i), restored.get(i));
        var base = new WaypointerConfig();
        var block = WaypointStyles.resolve(base, restored, restored.get(0));
        assertFalse(block.showRouteLines());
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED, block.boxStyle());
        assertEquals(1.0, block.waypointMarkerScale());
        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, block.beaconBeamMode());
        assertEquals(48.0 / 255.0, block.beaconOpacity());
        var marker = WaypointStyles.resolve(base, restored, restored.get(1));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED, marker.boxStyle());
        assertEquals(0.25, marker.waypointMarkerScale());
        assertEquals(1.0, marker.waypointOutlineOpacity());
        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, marker.beaconBeamMode());
        var hidden = WaypointStyles.resolve(base, restored, restored.get(2));
        assertEquals(0.0, hidden.waypointOutlineOpacity());
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED, hidden.boxStyle());
        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, hidden.beaconBeamMode());
        var beacon = WaypointStyles.resolve(base, restored, restored.get(3));
        assertEquals(0.0, beacon.waypointOutlineOpacity());
        assertEquals(1.0, beacon.beaconOpacity());
        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, beacon.beaconBeamMode());
    }

    @Test
    void importsActualPrivateIslandShareWithoutChangingAnyCoordinateOrOrder() throws Exception {
        String share = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/resources/fixtures/skysoft-private-island-share.txt")).strip();
        int[][] coordinates = {
                {11, 30}, {10, 36}, {8, 43}, {-1, 48}, {-10, 50}, {-15, 47},
                {-17, 43}, {-21, 40}, {-23, 34}, {-24, 30}, {-24, 26}, {-23, 22},
                {-20, 18}, {-17, 17}, {-14, 17}, {-10, 17}, {-5, 18}, {-3, 17},
                {0, 18}, {3, 19}, {4, 19}, {8, 23}, {10, 24}
        };
        for (String input : List.of(share, "```yaml\n" + share + "\n```")) {
            var result = ((UniversalShareCodec.Waypoints) UniversalShareCodec.decode(input)).result();
            assertEquals(WaypointImporter.Source.SKYSOFT, result.source());
            assertEquals(1, result.groups().size());
            var group = result.groups().getFirst();
            assertEquals("test", group.name());
            assertEquals("private_island", group.zoneId());
            assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
            assertTrue(group.enabled());
            assertFalse(group.skipAheadEnabled());
            assertEquals(3.0, group.defaultRadius());
            assertEquals(coordinates.length, group.size());
            var restored = WaypointImporter.importAny(WaypointCodec.encode(result.groups(),
                    WaypointCodec.Options.FULL_FIDELITY)).groups().getFirst();
            for (int i = 0; i < coordinates.length; i++) {
                var point = group.get(i);
                assertEquals(coordinates[i][0], point.x(), "x at " + i);
                assertEquals(98, point.y(), "y at " + i);
                assertEquals(coordinates[i][1], point.z(), "z at " + i);
                assertEquals(coordinates[i][0] + 0.5, point.centerX());
                assertEquals(98.5, point.centerY());
                assertEquals(coordinates[i][1] + 0.5, point.centerZ());
                assertEquals("", point.name());
                assertFalse(point.hasFlag(Waypoint.FLAG_DISABLED));
                assertEquals(point, restored.get(i), "native export point at " + i);
            }
            assertEquals(0x1A87C4, group.get(0).color());
            assertEquals(0x2BB1FB, group.get(group.size() - 1).color());
        }
    }

    @Test
    void decodesCurrentShareEnvelopeAndPreservesRouteFields() {
        String payload = "{\"version\":2,\"groups\":[{"
                + "\"name\":\"Skysoft route\",\"island\":\"THE_FARMING_ISLANDS\","
                + "\"route\":true,\"skipAhead\":false,\"radius\":4.5,\"color\":4535040,"
                + "\"styles\":[\"MARKER\"],\"points\":[{\"name\":\"Start\",\"x\":10.0,\"y\":70.0,\"z\":-4.0,\"color\":16711935,\"radius\":2.0},{\"name\":\"Off\",\"x\":11.25,\"y\":71.5,\"z\":-3,\"enabled\":false}]}]}";
        var result = WaypointImporter.importAny(envelope(2, payload));
        assertEquals(WaypointImporter.Source.SKYSOFT, result.source());
        WaypointGroup group = result.groups().getFirst();
        assertEquals("the_farming_isles", group.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
        assertEquals(4.5, group.defaultRadius());
        assertEquals(2.0, group.get(0).customRadius());
        assertEquals(0xFF00FF, group.get(0).color());
        assertEquals(4535040, group.get(1).color());
        assertFalse(group.skipAheadEnabled());
        assertTrue(group.get(1).hasFlag(Waypoint.FLAG_DISABLED));
        assertEquals(11, group.get(1).x());
        assertEquals(11.75, group.get(1).preciseX() / 16.0, 0.0001);
    }

    @Test
    void preservesDefaultGradientSignedColorsAndIslandMappings() {
        for (var island : java.util.Map.of("JERRYS_WORKSHOP", "winter", "DUNGEONS", "unknown",
                "GLACITE_TUNNELS", "dwarven_mines", "THE_RIFT", "rift",
                "GLACITE_MINESHAFTS", "mineshaft_unknown").entrySet()) {
            String json = """
                    {"version":2,"groups":[{"island":"%s","points":[
                    {"x":1,"y":2,"z":3},{"x":2,"y":3,"z":4},
                    {"x":3,"y":4,"z":5,"color":-65536}]}]}
                    """.formatted(island.getKey());
            var group = WaypointImporter.importAny(json).groups().getFirst();
            assertEquals(island.getValue(), group.zoneId());
            assertFalse(group.skipAheadEnabled());
            assertEquals(0x1A87C4, group.get(0).color());
            assertEquals(0x229CDF, group.get(1).color());
            assertEquals(0xFF0000, group.get(2).color());
        }
    }

    @Test
    void rejectsUnsupportedEnvelopesAndInvalidLibrariesThroughPublicImporter() {
        String valid = """
                {"version":2,"groups":[{"island":"HUB","points":[{"x":1,"y":2,"z":3}]}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(envelope(3, valid)));
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(envelope(0, valid)));
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny("SKYSOFT:invalid"));
        for (String invalid : List.of(valid.replace("\"version\":2", "\"version\":3"),
                valid.replace("\"x\":1", "\"x\":1e309"),
                valid.replace("\"x\":1", "\"x\":30000001"),
                valid.replace("\"x\":1,", ""),
                valid.replace("\"x\":1", "\"x\":\"1\""),
                valid.replace("\"island\":\"HUB\"", "\"radius\":-1"))) {
            assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(invalid), invalid);
            assertThrows(IllegalArgumentException.class, () -> UniversalShareCodec.decode(invalid), invalid);
        }
        JsonObject wrongType = new JsonObject();
        wrongType.addProperty("formatVersion", 1);
        wrongType.addProperty("type", "settings");
        wrongType.addProperty("schemaVersion", 2);
        wrongType.addProperty("payload", valid);
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(compress(wrongType.toString())));
        wrongType.addProperty("type", "waypoints");
        wrongType.addProperty("formatVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(compress(wrongType.toString())));
    }

    @Test
    void decodesSchemaOneRawLibraryAndLooseJson() {
        JsonObject root = JsonParser.parseString("{\"version\":1,\"groups\":[{"
                + "\"name\":\"Old\",\"island\":\"hub\",\"style\":\"BEACON\","
                + "\"points\":[{\"name\":\"A\",\"x\":1,\"y\":2,\"z\":3}]}]}").getAsJsonObject();
        assertTrue(SkysoftWaypointImporter.looksLikeLibrary(root));
        var group = SkysoftWaypointImporter.decodeJson(root).groups().getFirst();
        assertEquals(1, group.size());
        var style = WaypointStyles.resolve(new WaypointerConfig(), group, group.get(0));
        assertEquals(1.0, style.waypointOutlineOpacity());
        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, style.beaconBeamMode());
    }

    @Test
    void rejectsBadEnvelopeAndCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> SkysoftWaypointImporter.decodeShare(envelope(1, "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> SkysoftWaypointImporter.decodeJson(JsonParser.parseString(
                        "{\"version\":2,\"groups\":[{\"island\":\"hub\",\"points\":[{\"x\":1e309,\"y\":2,\"z\":3}]}]}").getAsJsonObject()));
    }

    @Test
    void rejectsMalformedDisplayStyles() {
        for (String styles : List.of("[\"UNKNOWN\"]", "[1]", "[\"BLOCK\",\"BLOCK\"]", "null")) {
            String json = "{\"version\":2,\"groups\":[{\"styles\":" + styles
                    + ",\"points\":[{\"x\":1,\"y\":2,\"z\":3}]}]}";
            assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(json));
        }
    }

    private static String envelope(int schema, String payload) {
        JsonObject json = new JsonObject();
        json.addProperty("formatVersion", 1);
        json.addProperty("type", "waypoints");
        json.addProperty("schemaVersion", schema);
        json.addProperty("payload", payload);
        return compress(json.toString());
    }

    private static String compress(String json) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return "SKYSOFT:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

}
