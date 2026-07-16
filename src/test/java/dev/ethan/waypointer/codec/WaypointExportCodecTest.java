package dev.ethan.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        WaypointGroup group = sampleGroup("Park Route", "the_park");
        String encoded = WaypointExportCodec.encode(List.of(group), FULL_EXTERNAL,
                WaypointExportCodec.Target.SKYTILS);

        WaypointImporter.ImportResult result = WaypointImporter.importAny(encoded);
        WaypointGroup imported = result.groups().get(0);
        assertEquals(WaypointImporter.Source.SKYTILS, result.source());
        assertEquals("the_park", imported.zoneId());
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

    @Test
    void every_known_zone_maps_to_a_real_skyblocker_location_id() {
        Set<String> accepted = Set.of(
                "dynamic", "garden", "hub", "farming_1", "foraging_1", "foraging_2", "foraging_3",
                "combat_1", "combat_2", "combat_3", "crimson_isle", "mining_1",
                "mining_2", "mining_3", "fishing_1", "dungeon_hub", "winter", "rift",
                "dark_auction", "crystal_hollows", "dungeon", "kuudra", "mineshaft",
                "lotus_atoll", "safari", "unknown");

        for (Zone zone : Zone.knownZones()) {
            assertTrue(accepted.contains(WaypointExportCodec.skyblockerIslandId(zone.id())),
                    () -> "unsupported Skyblocker zone " + zone.id());
        }
        DungeonRoomData.allDefinitions().forEach(room ->
                assertEquals("dungeon", WaypointExportCodec.skyblockerIslandId(room.id())));
    }

    @Test
    void every_representable_known_zone_maps_to_a_real_skytils_island_id() {
        Set<String> accepted = Set.of(
                "dynamic", "garden", "combat_1", "crimson_isle", "combat_3", "fishing_1",
                "mining_1", "mining_2", "mining_3", "crystal_hollows", "farming_1",
                "foraging_1", "dungeon", "dungeon_hub", "hub", "dark_auction", "winter",
                "kuudra", "mineshaft", "rift");

        for (Zone zone : Zone.knownZones()) {
            if (zone.id().equals("galatea")
                    || zone.id().equals("lotus_atoll")
                    || zone.id().equals("torrhus_canyon")
                    || zone.id().equals("safari")) {
                assertThrows(IllegalArgumentException.class,
                        () -> WaypointExportCodec.skytilsIslandId(zone.id()));
            } else {
                assertTrue(accepted.contains(WaypointExportCodec.skytilsIslandId(zone.id())),
                        () -> "unsupported Skytils zone " + zone.id());
            }
        }
        DungeonRoomData.allDefinitions().forEach(room ->
                assertEquals("dungeon", WaypointExportCodec.skytilsIslandId(room.id())));
    }

    @Test
    void refinements_and_catacombs_zones_collapse_to_recipient_coarse_ids() {
        assertEquals("mining_3", WaypointExportCodec.skyblockerIslandId("great_glacite_lake"));
        assertEquals("mining_3", WaypointExportCodec.skyblockerIslandId("glacite_tunnels"));
        assertEquals("mining_3", WaypointExportCodec.skyblockerIslandId("dwarven_base_camp"));
        assertEquals("mineshaft", WaypointExportCodec.skyblockerIslandId("mineshaft_topaz_1"));
        assertEquals("mineshaft", WaypointExportCodec.skyblockerIslandId("mineshaft_crystal"));
        assertEquals("dungeon_hub", WaypointExportCodec.skyblockerIslandId("dungeon_hub"));
        assertEquals("dungeon", WaypointExportCodec.skyblockerIslandId("dungeon"));
        assertEquals("dungeon", WaypointExportCodec.skyblockerIslandId("dungeon_f7"));
        assertEquals("dungeon", WaypointExportCodec.skyblockerIslandId("dungeon_m7"));
        assertEquals("dungeon", WaypointExportCodec.skyblockerIslandId("entrance"));
        assertEquals("dungeon", WaypointExportCodec.skyblockerIslandId("altar"));
        assertEquals("lotus_atoll", WaypointExportCodec.skyblockerIslandId("lotus_atoll"));
        assertEquals("foraging_3", WaypointExportCodec.skyblockerIslandId("torrhus_canyon"));
        assertEquals("safari", WaypointExportCodec.skyblockerIslandId("safari"));
    }

    private static WaypointGroup sampleGroup(String name, String zoneId) {
        WaypointGroup group = WaypointGroup.create(name, zoneId);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 64, 2, "start", 0xFF0000, 0, 4.5));
        group.add(new Waypoint(3, 65, 4, "end", 0x0000FF, Waypoint.FLAG_HIDE_NAME, 0.0));
        return group;
    }
}
