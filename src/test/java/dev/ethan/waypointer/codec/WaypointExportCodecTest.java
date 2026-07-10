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
    void skyhanni_export_emits_current_wrapped_route_json() {
        WaypointGroup group = sampleGroup("Ignored Group Name", "hub");
        String encoded = WaypointExportCodec.encode(List.of(group), FULL_EXTERNAL,
                WaypointExportCodec.Target.SKYHANNI);

        JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
        JsonArray waypoints = root.getAsJsonArray("waypoints");
        JsonObject first = waypoints.get(0).getAsJsonObject();
        assertEquals(1, first.get("x").getAsInt());
        assertEquals("1", first.getAsJsonObject("options").get("name").getAsString());
        assertEquals(0.0, first.get("r").getAsDouble(), 0.0001);
        assertEquals(1.0, first.get("g").getAsDouble(), 0.0001);
        assertEquals(0.0, first.get("b").getAsDouble(), 0.0001);
        assertFalse(root.has("enabled"));
        assertFalse(first.has("enabled"));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(encoded);
        assertEquals(WaypointImporter.Source.SKYHANNI, result.source());
        assertEquals(2, result.groups().get(0).size());
        assertTrue(result.groups().get(0).enabled());
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

        JsonArray waypoints = JsonParser.parseString(encoded).getAsJsonObject()
                .getAsJsonArray("waypoints");
        JsonObject first = waypoints.get(0).getAsJsonObject();
        assertEquals("1", first
                .getAsJsonObject("options").get("name").getAsString());
        assertEquals("2", waypoints.get(1).getAsJsonObject()
                .getAsJsonObject("options").get("name").getAsString());
        assertTrue(first.has("r"));
        assertTrue(first.has("g"));
        assertTrue(first.has("b"));
    }

    @Test
    void skyblocker_normalized_color_channels_are_short_but_precise() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 64, 2, "colored", 0x4F38E0, 0, 0.0));
        WaypointCodec.Options opts = WaypointCodec.Options.builder()
                .includeNames(true)
                .includeColors(true)
                .build();

        String skyblocker = WaypointExportCodec.encode(List.of(group), opts,
                WaypointExportCodec.Target.SKYBLOCKER);
        assertTrue(WaypointImporter.importAny(skyblocker).groups().get(0).size() > 0);
    }

    @Test
    void third_party_targets_report_name_capabilities() {
        assertTrue(WaypointExportCodec.Target.WAYPOINTER.supportsNames());
        assertTrue(WaypointExportCodec.Target.SKYBLOCKER.supportsNames());
        assertTrue(WaypointExportCodec.Target.SKYTILS.supportsNames());
        assertFalse(WaypointExportCodec.Target.SKYHANNI.supportsNames());
        assertFalse(WaypointExportCodec.Target.SKYHANNI.supportsColors());
    }

    private static WaypointGroup sampleGroup(String name, String zoneId) {
        WaypointGroup group = WaypointGroup.create(name, zoneId);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 64, 2, "start", 0xFF0000, 0, 4.5));
        group.add(new Waypoint(3, 65, 4, "end", 0x0000FF, Waypoint.FLAG_HIDE_NAME, 0.0));
        return group;
    }
}
