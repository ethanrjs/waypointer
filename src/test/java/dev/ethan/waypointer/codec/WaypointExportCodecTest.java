package dev.ethan.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility tests for third-party export targets. These assert the JSON
 * shapes Waypointer promises to emit, then feed them back through the importer
 * so export support cannot drift away from import support unnoticed.
 */
class WaypointExportCodecTest {

    private static final WaypointCodec.Options FULL_EXTERNAL =
            WaypointCodec.Options.builder()
                    .includeNames(true)
                    .includeColors(true)
                    .includeRadii(true)
                    .includeWaypointFlags(true)
                    .includeGroupMeta(true)
                    .build();

    @Test
    void skyblocker_export_round_trips_through_skyblocker_importer() {
        WaypointGroup group = sampleGroup("Park Route", "the_park");
        String encoded = WaypointExportCodec.encode(List.of(group), FULL_EXTERNAL,
                WaypointExportCodec.Target.SKYBLOCKER);

        assertTrue(encoded.startsWith(WaypointImporter.SKYBLOCKER_V1_PREFIX));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(encoded);
        WaypointGroup imported = result.groups().get(0);
        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        assertEquals("the_park", imported.zoneId());
        assertEquals("start", imported.get(0).name());
    }

    @Test
    void skytils_export_round_trips_through_skytils_importer() {
        WaypointGroup group = sampleGroup("Galatea Route", "galatea");
        String encoded = WaypointExportCodec.encode(List.of(group), FULL_EXTERNAL,
                WaypointExportCodec.Target.SKYTILS);

        WaypointImporter.ImportResult result = WaypointImporter.importAny(encoded);
        WaypointGroup imported = result.groups().get(0);
        assertEquals(WaypointImporter.Source.SKYTILS, result.source());
        assertEquals("galatea", imported.zoneId());
        assertEquals("start", imported.get(0).name());
    }

    @Test
    void skyhanni_export_emits_flat_route_json() {
        WaypointGroup group = sampleGroup("Ignored Group Name", "hub");
        String encoded = WaypointExportCodec.encode(List.of(group), FULL_EXTERNAL,
                WaypointExportCodec.Target.SKYHANNI);

        JsonArray root = JsonParser.parseString(encoded).getAsJsonArray();
        JsonObject first = root.get(0).getAsJsonObject();
        assertEquals(1, first.get("x").getAsInt());
        assertEquals("start", first.getAsJsonObject("options").get("name").getAsString());
        assertEquals(1.0, first.get("r").getAsDouble(), 0.0001);
        assertEquals(0.0, first.get("g").getAsDouble(), 0.0001);

        WaypointImporter.ImportResult result = WaypointImporter.importAny(encoded);
        assertEquals(WaypointImporter.Source.SKYHANNI, result.source());
        assertEquals(2, result.groups().get(0).size());
    }

    @Test
    void skyhanni_export_uses_step_numbers_when_names_are_disabled() {
        WaypointGroup group = sampleGroup("Route", "hub");
        WaypointCodec.Options opts = WaypointCodec.Options.builder()
                .includeNames(false)
                .includeColors(false)
                .build();

        String encoded = WaypointExportCodec.encode(List.of(group), opts,
                WaypointExportCodec.Target.SKYHANNI);

        JsonArray root = JsonParser.parseString(encoded).getAsJsonArray();
        JsonObject first = root.get(0).getAsJsonObject();
        assertEquals(1, first
                .getAsJsonObject("options").get("name").getAsInt());
        assertEquals(2, root.get(1).getAsJsonObject()
                .getAsJsonObject("options").get("name").getAsInt());
        assertFalse(first.has("r"));
        assertFalse(first.has("g"));
        assertFalse(first.has("b"));
    }

    @Test
    void third_party_normalized_color_channels_are_short_but_precise() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 64, 2, "colored", 0x4F38E0, 0, 0.0));
        WaypointCodec.Options opts = WaypointCodec.Options.builder()
                .includeNames(true)
                .includeColors(true)
                .build();

        String skyhanni = WaypointExportCodec.encode(List.of(group), opts,
                WaypointExportCodec.Target.SKYHANNI);
        assertTrue(skyhanni.contains("\"r\":0.31"));
        assertTrue(skyhanni.contains("\"g\":0.22"));
        assertTrue(skyhanni.contains("\"b\":0.878"));
        assertFalse(skyhanni.contains("0.30980392156862746"));
        JsonObject first = JsonParser.parseString(skyhanni).getAsJsonArray()
                .get(0).getAsJsonObject();
        assertEquals(0x4F38E0, WaypointImporter.coleweightRgb(
                first.get("r").getAsDouble(),
                first.get("g").getAsDouble(),
                first.get("b").getAsDouble()));

        String skyblocker = WaypointExportCodec.encode(List.of(group), opts,
                WaypointExportCodec.Target.SKYBLOCKER);
        assertTrue(WaypointImporter.importAny(skyblocker).groups().get(0).size() > 0);
    }

    private static WaypointGroup sampleGroup(String name, String zoneId) {
        WaypointGroup group = WaypointGroup.create(name, zoneId);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 64, 2, "start", 0xFF0000, 0, 4.5));
        group.add(new Waypoint(3, 65, 4, "end", 0x0000FF, Waypoint.FLAG_HIDE_NAME, 0.0));
        return group;
    }
}
