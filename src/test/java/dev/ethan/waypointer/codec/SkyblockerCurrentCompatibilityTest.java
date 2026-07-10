package dev.ethan.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyblockerCurrentCompatibilityTest {

    @Test
    void importsCurrentUpstreamFixtureWithoutReorderingOrRecoloring() throws Exception {
        String json;
        try (var input = getClass().getResourceAsStream("/fixtures/skyblocker-current-waypoints.json")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var compressed = new java.io.ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        String share = WaypointImporter.SKYBLOCKER_V1_PREFIX
                + Base64.getEncoder().encodeToString(compressed.toByteArray());

        WaypointImporter.ImportResult result = WaypointImporter.importAny(share);
        WaypointGroup group = result.groups().getFirst();

        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        assertEquals("Current Skyblocker Route", group.name());
        assertEquals("the_park", group.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(List.of(30, 10), group.waypoints().stream().map(Waypoint::x).toList());
        assertEquals(List.of("First", "Second"), group.waypoints().stream().map(Waypoint::name).toList());
        assertEquals(List.of(0xFF8000, 0x0000FF), group.waypoints().stream().map(Waypoint::color).toList());
        assertTrue(group.get(0).hasFlag(Waypoint.FLAG_THROUGH_WALL));
        assertTrue(group.get(1).hasFlag(Waypoint.FLAG_HIDE_BEACON));
        assertTrue(group.get(1).hasFlag(Waypoint.FLAG_HIDE_NAME));
    }

    @Test
    void exportsExactCurrentSkyblockerWrapperAndRequiredSchema() throws Exception {
        WaypointGroup group = WaypointGroup.create("Schema Route", "the_park");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(new Waypoint(3, 70, 4, "Visible", 0x4F38E0,
                Waypoint.FLAG_THROUGH_WALL, 0.0));
        group.add(new Waypoint(7, 71, 8, "Disabled", 0x102030,
                Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME, 0.0));

        String share = WaypointExportCodec.encode(List.of(group),
                WaypointCodec.Options.builder().includeNames(true).includeColors(true).build(),
                WaypointExportCodec.Target.SKYBLOCKER);

        assertTrue(share.startsWith("[Skyblocker-Waypoint-Data-V1]"));
        assertFalse(share.substring(WaypointImporter.SKYBLOCKER_V1_PREFIX.length()).startsWith(":"));
        byte[] packed = Base64.getDecoder().decode(
                share.substring(WaypointImporter.SKYBLOCKER_V1_PREFIX.length()));
        String json;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonArray root = JsonParser.parseString(json).getAsJsonArray();
        JsonObject exportedGroup = root.get(0).getAsJsonObject();
        assertEquals("Schema Route", exportedGroup.get("name").getAsString());
        assertEquals("foraging_1", exportedGroup.get("island").getAsString());
        assertTrue(exportedGroup.get("ordered").getAsBoolean());
        assertTrue(exportedGroup.get("renderThroughWalls").getAsBoolean());

        JsonObject visible = exportedGroup.getAsJsonArray("waypoints").get(0).getAsJsonObject();
        assertEquals(List.of(3, 70, 4), visible.getAsJsonArray("pos").asList().stream()
                .map(element -> element.getAsInt()).toList());
        assertEquals("Visible", visible.get("name").getAsString());
        assertEquals(3, visible.getAsJsonArray("colorComponents").size());
        assertEquals(0.5, visible.get("alpha").getAsDouble());
        assertTrue(visible.get("shouldRender").getAsBoolean());
        assertFalse(exportedGroup.getAsJsonArray("waypoints").get(1).getAsJsonObject()
                .get("shouldRender").getAsBoolean());
    }

    @Test
    void currentSkyblockerRoundTripPreservesRepresentableFields() {
        WaypointGroup original = WaypointGroup.create("Round Trip", "hub");
        original.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        original.setLoadMode(WaypointGroup.LoadMode.STATIC);
        original.add(new Waypoint(9, 80, 7, "A", 0xABCDEF, 0, 0.0));
        original.add(new Waypoint(6, 75, 5, "B", 0x123456,
                Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME, 0.0));

        String share = WaypointExportCodec.encode(List.of(original),
                WaypointCodec.Options.builder().includeNames(true).includeColors(true).build(),
                WaypointExportCodec.Target.SKYBLOCKER);
        WaypointGroup imported = WaypointImporter.importAny(share).groups().getFirst();

        assertEquals("Round Trip", imported.name());
        assertEquals("hub", imported.zoneId());
        assertEquals(WaypointGroup.LoadMode.STATIC, imported.loadMode());
        assertEquals(List.of(9, 6), imported.waypoints().stream().map(Waypoint::x).toList());
        assertEquals(List.of("A", "B"), imported.waypoints().stream().map(Waypoint::name).toList());
        assertEquals(List.of(0xABCDEF, 0x123456), imported.waypoints().stream().map(Waypoint::color).toList());
        assertTrue(imported.get(1).hasFlag(Waypoint.FLAG_HIDE_BEACON));
        assertTrue(imported.get(1).hasFlag(Waypoint.FLAG_HIDE_NAME));
    }
}
