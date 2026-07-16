package dev.ethan.waypointer.codec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkytilsCurrentCompatibilityTest {

    @Test
    void imports_current_v1_and_v2_clipboard_wrappers() throws IOException {
        for (String fixture : List.of("skytils-current-v1.txt", "skytils-current-v2.txt")) {
            WaypointImporter.ImportResult result = WaypointImporter.importAny(readFixture(fixture));

            assertEquals(WaypointImporter.Source.SKYTILS, result.source());
            assertEquals(1, result.groups().size());
            WaypointGroup group = result.groups().getFirst();
            assertEquals("Current Skytils", group.name());
            assertEquals("hub", group.zoneId());
            assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
            assertEquals(2, group.size());
            assertEquals("Enabled Blue", group.get(0).name());
            assertEquals(0x0000FF, group.get(0).color());
            assertFalse(group.get(0).hasFlag(Waypoint.FLAG_HIDE_BEACON));
            assertEquals("Disabled Default Red", group.get(1).name());
            assertEquals(0xFF0000, group.get(1).color());
            assertTrue(group.get(1).hasFlag(Waypoint.FLAG_HIDE_BEACON));
            assertTrue(group.get(1).hasFlag(Waypoint.FLAG_HIDE_NAME));
        }
    }

    @Test
    void export_uses_current_v1_schema_and_round_trips_names_colors_and_enabled_state() throws IOException {
        WaypointGroup group = WaypointGroup.create("Skytils Export", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 70, 2, "Blue", 0x0000FF, 0, 0.0));
        group.add(new Waypoint(3, 71, 4, "Hidden", 0xFF0000,
                Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME, 0.0));

        WaypointCodec.Options options = WaypointCodec.Options.builder()
                .includeNames(true)
                .includeColors(true)
                .build();
        String encoded = WaypointExportCodec.encode(List.of(group), options,
                WaypointExportCodec.Target.SKYTILS);

        assertTrue(encoded.startsWith(WaypointImporter.SKYTILS_V1_PREFIX));
        JsonObject category = decodeV1Json(encoded).getAsJsonArray("categories")
                .get(0).getAsJsonObject();
        assertEquals("Skytils Export", category.get("name").getAsString());
        assertEquals("hub", category.get("island").getAsString());
        JsonObject first = category.getAsJsonArray("waypoints").get(0).getAsJsonObject();
        assertEquals("Blue", first.get("name").getAsString());
        assertEquals(0xFF0000FF, first.get("color").getAsInt());
        assertTrue(first.get("enabled").getAsBoolean());
        assertTrue(first.get("addedAt").getAsLong() > 0);
        JsonObject second = category.getAsJsonArray("waypoints").get(1).getAsJsonObject();
        assertFalse(second.get("enabled").getAsBoolean());

        WaypointGroup imported = WaypointImporter.importAny(encoded).groups().getFirst();
        assertEquals(WaypointGroup.LoadMode.STATIC, imported.loadMode());
        assertEquals("Blue", imported.get(0).name());
        assertEquals(0x0000FF, imported.get(0).color());
        assertTrue(imported.get(1).hasFlag(Waypoint.FLAG_HIDE_BEACON));
    }

    private static String readFixture(String name) throws IOException {
        try (var input = SkytilsCurrentCompatibilityTest.class
                .getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IOException("missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static JsonObject decodeV1Json(String encoded) throws IOException {
        String body = encoded.substring(WaypointImporter.SKYTILS_V1_PREFIX.length());
        byte[] compressed = Base64.getDecoder().decode(body);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return JsonParser.parseString(new String(gzip.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
