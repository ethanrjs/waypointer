package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class SkysoftWaypointExporterTest {
    @Test
    void exportsExistingRouteSettingsThroughPublicTargetAndSnapshot() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
        config.putNamedStyle("Beam", WaypointStyle.EMPTY.with("beaconBeamMode", "ALL_VISIBLE"));
        WaypointGroup group = WaypointGroup.create("Route", "private_island");
        group.setStylePresetId("Beam");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setEnabled(false);
        group.setSkipAheadEnabled(false);
        group.setDefaultRadius(4);
        group.setStyleOverride(WaypointStyle.EMPTY.with("boxStyle", "FILLED_OUTLINED")
                .with("showRouteLines", false)
                .with("defaultWaypointColor", 0x123456));
        group.add(new Waypoint(0, 0, 0, "Precise", 0xABCDEF, Waypoint.FLAG_DISABLED, 2,
                Waypoint.TEMP_NONE, 0, -15, 1603, 55)
                .withStyleOverride(WaypointStyle.EMPTY.with("boxStyle", "OUTLINED")
                        .with("waypointMarkerScale", 0.25).with("beaconBeamMode", "OFF")
                        .with("defaultWaypointColor", 0xFEDCBA)));
        group.add(new Waypoint(5, 7, 9, "Hidden beam", 0xFF0000, Waypoint.FLAG_HIDE_BEACON, 0));
        String share = WaypointExportCodec.encode(List.of(group.exportSnapshot()),
                WaypointCodec.Options.builder().build(), WaypointExportCodec.Target.SKYSOFT,
                RouteLibraryMetadata.empty(), config.exportSnapshot());
        JsonObject json = library(share).getAsJsonArray("groups").get(0).getAsJsonObject();
        assertFalse(json.has("id"));
        assertEquals("PRIVATE_ISLAND", json.get("island").getAsString());
        assertEquals("ISLAND", json.get("scope").getAsString());
        assertFalse(json.get("enabled").getAsBoolean());
        assertTrue(json.get("route").getAsBoolean());
        assertTrue(json.get("loop").getAsBoolean());
        assertTrue(json.get("filled").getAsBoolean());
        assertFalse(json.get("skipAhead").getAsBoolean());
        assertFalse(json.get("lines").getAsBoolean());
        assertEquals(4, json.get("radius").getAsDouble());
        assertEquals("[\"BLOCK\",\"BEACON\"]", json.get("styles").toString());
        JsonObject first = json.getAsJsonArray("points").get(0).getAsJsonObject();
        JsonObject second = json.getAsJsonArray("points").get(1).getAsJsonObject();
        assertFalse(first.has("id"));
        assertFalse(second.has("id"));
        assertEquals(-1.4375, first.get("x").getAsDouble());
        assertEquals(99.6875, first.get("y").getAsDouble());
        assertEquals(2.9375, first.get("z").getAsDouble());
        assertFalse(first.get("enabled").getAsBoolean());
        assertEquals(0xFEDCBA, first.get("color").getAsInt());
        assertEquals(2, first.get("radius").getAsDouble());
        assertEquals("[\"MARKER\"]", first.get("styles").toString());
        assertEquals(0x123456, second.get("color").getAsInt());
        assertEquals("[\"BLOCK\"]", second.get("styles").toString());
        assertFalse(second.has("radius"));
        var restored = WaypointImporter.importAny(share).groups().getFirst();
        assertEquals(group.get(0).preciseX(), restored.get(0).preciseX());
        assertEquals(group.get(0).preciseY(), restored.get(0).preciseY());
        assertEquals(group.get(0).preciseZ(), restored.get(0).preciseZ());
        assertFalse(restored.enabled());
        assertTrue(restored.get(0).isDisabled());
        assertEquals(0xFEDCBA, restored.get(0).color());
    }

    @Test
    void reexportsActualSkysoftPrivateIslandFixtureWithAll23Points() throws Exception {
        String original = Files.readString(Path.of("src/test/resources/fixtures/skysoft-private-island-share.txt")).strip();
        var imported = WaypointImporter.importAny(original).groups();
        JsonObject source = library(original).getAsJsonArray("groups").get(0).getAsJsonObject();
        JsonObject result = library(SkysoftWaypointExporter.encode(imported, new WaypointerConfig()))
                .getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals(23, result.getAsJsonArray("points").size());
        for (String key : List.of("name", "island", "route", "loop", "enabled", "skipAhead", "styles", "filled", "lines"))
            assertEquals(source.get(key), result.get(key), key);
        for (int i = 0; i < 23; i++) {
            JsonObject a = source.getAsJsonArray("points").get(i).getAsJsonObject();
            JsonObject b = result.getAsJsonArray("points").get(i).getAsJsonObject();
            for (String axis : List.of("x", "y", "z")) assertEquals(a.get(axis).getAsDouble(), b.get(axis).getAsDouble());
            assertEquals(imported.getFirst().get(i).color(), b.get("color").getAsInt());
        }
    }

    @Test
    void rejectsUnsupportedContextsAndSkysoftValidationViolations() {
        WaypointerConfig config = new WaypointerConfig();
        for (String zone : List.of("unknown", "private_world", "dark_auction", "dungeon_f7", "mineshaft_crystal")) {
            var group = WaypointGroup.create("Route", zone);
            group.add(new Waypoint(1, 2, 3, "", 0xFFFFFF, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> SkysoftWaypointExporter.encode(List.of(group), config), zone);
        }
        var group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(1, 2, 3, "Too small", 0xFFFFFF, 0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> SkysoftWaypointExporter.encode(List.of(group), config));
        var invalidName = WaypointGroup.create("x".repeat(81), "hub");
        invalidName.add(new Waypoint(1, 2, 3, "", 0xFFFFFF, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SkysoftWaypointExporter.encode(List.of(invalidName), config));
        assertThrows(IllegalArgumentException.class, () -> SkysoftWaypointExporter.encode(Collections.nCopies(257, invalidName), config));
        assertThrows(IllegalArgumentException.class, () -> SkysoftWaypointExporter.encode(List.of(), config));
    }

    @Test
    void inclusionOptionsDropOptionalDataAndKeepRequiredIslandWithCompactStableShares() throws Exception {
        var group = WaypointGroup.create("44 point route", "hub");
        group.setEnabled(false);
        group.setSkipAheadEnabled(true);
        group.setDefaultRadius(7);
        for (int i = 0; i < 44; i++)
            group.add(new Waypoint(i * 3, 98, i * -2, "Point " + i, 0x123456 + i, 0, 2));
        var options = WaypointCodec.Options.builder().includeNames(false).includeColors(false)
                .includeRadii(false).includeGroupMeta(false).includeZone(false).build();
        String share = WaypointExportCodec.encode(List.of(group), options, WaypointExportCodec.Target.SKYSOFT);
        assertEquals(share, WaypointExportCodec.encode(List.of(group), options, WaypointExportCodec.Target.SKYSOFT));
        assertTrue(share.length() < 1000, "44 simple points should not carry random UUID overhead: " + share.length());
        JsonObject json = library(share).getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals("HUB", json.get("island").getAsString());
        assertFalse(json.has("radius"));
        assertFalse(json.has("color"));
        assertTrue(json.get("enabled").getAsBoolean());
        assertFalse(json.get("skipAhead").getAsBoolean());
        assertFalse(json.get("loop").getAsBoolean());
        for (var element : json.getAsJsonArray("points")) {
            JsonObject point = element.getAsJsonObject();
            assertEquals("", point.get("name").getAsString());
            assertFalse(point.has("color"));
            assertFalse(point.has("radius"));
            assertFalse(point.has("id"));
            assertFalse(point.has("styles"));
        }
        assertEquals(44, WaypointImporter.importAny(share).groups().getFirst().size());
    }

    private static JsonObject library(String share) throws Exception {
        assertTrue(share.matches("SKYSOFT:[A-Za-z0-9_-]+"));
        byte[] compressed = Base64.getUrlDecoder().decode(share.substring("SKYSOFT:".length()));
        JsonObject envelope;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            envelope = JsonParser.parseString(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals(1, envelope.get("formatVersion").getAsInt());
        assertEquals(2, envelope.get("schemaVersion").getAsInt());
        assertEquals("waypoints", envelope.get("type").getAsString());
        assertTrue(envelope.get("payload").getAsJsonPrimitive().isString());
        JsonObject library = JsonParser.parseString(envelope.get("payload").getAsString()).getAsJsonObject();
        assertEquals(2, library.get("version").getAsInt());
        return library;
    }
}
