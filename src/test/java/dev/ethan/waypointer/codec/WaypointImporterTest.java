package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WaypointImporterTest {

    @Test
    void boundsUnsafeLooseJsonReachRadii() {
        String json = "{\"name\":\"unsafe\",\"waypoints\":["
                + "{\"x\":0,\"y\":64,\"z\":0,\"r\":1e309},"
                + "{\"x\":1,\"y\":64,\"z\":0,\"r\":1000000}]}";

        WaypointGroup group = WaypointImporter.importAny(json).groups().get(0);

        assertEquals(0.0, group.get(0).customRadius());
        assertEquals(Waypoint.MAX_REACH_RADIUS, group.get(1).customRadius());
    }

    @Test
    void delegates_to_native_codec_for_wptr1_payload() {
        WaypointGroup g = WaypointGroup.create("Gold", "dungeon_f7");
        g.add(Waypoint.at(1, 2, 3));
        String native_ = WaypointCodec.encode(List.of(g));
        WaypointImporter.ImportResult result = WaypointImporter.importAny(native_);
        assertEquals(WaypointImporter.Source.WAYPOINTER, result.source());
        assertEquals(1, result.groups().size());
        // Bare exports without a builder-supplied label produce an empty
        // string, which lets the import success path skip the "Label:" line
        // without having to special-case null.
        assertEquals("", result.label());
    }

    @Test
    void surfaces_label_from_native_codec_payload() {
        WaypointGroup g = WaypointGroup.create("Gold", "dungeon_f7");
        g.add(Waypoint.at(1, 2, 3));
        String labelled = WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.builder().label("F7 dragon path").build());
        WaypointImporter.ImportResult result = WaypointImporter.importAny(labelled);
        assertEquals(WaypointImporter.Source.WAYPOINTER, result.source());
        assertEquals("F7 dragon path", result.label());
    }

    @Test
    void unwraps_markdown_code_block_around_native_payload() {
        WaypointGroup g = WaypointGroup.create("Gold", "dungeon_f7");
        g.add(Waypoint.at(1, 2, 3));
        String native_ = WaypointCodec.encode(List.of(g));

        WaypointImporter.ImportResult result = WaypointImporter.importAny("```\n" + native_ + "\n```");

        assertEquals(WaypointImporter.Source.WAYPOINTER, result.source());
        assertEquals(1, result.groups().size());
        assertEquals("dungeon_f7", result.groups().get(0).zoneId());
    }

    @Test
    void unwraps_language_tagged_markdown_code_block_around_native_payload() {
        WaypointGroup g = WaypointGroup.create("Gold", "dungeon_f7");
        g.add(Waypoint.at(1, 2, 3));
        String native_ = WaypointCodec.encode(List.of(g));

        WaypointImporter.ImportResult result = WaypointImporter.importAny("```text\n" + native_ + "\n```");

        assertEquals(WaypointImporter.Source.WAYPOINTER, result.source());
        assertEquals(1, result.groups().size());
    }

    @Test
    void non_waypointer_sources_default_label_to_empty_string() {
        // JSON / Skyblocker / Skytils paths don't carry a Waypointer label, so
        // ImportResult.label() must be an empty string (never null) for them
        // -- the chat output uses isEmpty() to decide whether to render the
        // "Label:" line and would NPE on a null fall-through.
        String json = "{\"name\":\"x\",\"island\":\"hub\",\"waypoints\":[{\"x\":0,\"y\":0,\"z\":0}]}";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertNotNull(result.label());
        assertEquals("", result.label());
    }

    @Test
    void sanitizes_group_and_waypoint_names_from_json_imports() {
        String groupName = "\u00A7cBad\nGroup";
        String waypointName = "\u00A7aBad\tPoint";
        String json = "{\"name\":\"\\u00A7cBad\\nGroup\",\"island\":\"hub\",\"waypoints\":["
                + "{\"name\":\"\\u00A7aBad\\tPoint\",\"x\":0,\"y\":70,\"z\":0}"
                + "]}";

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup group = result.groups().get(0);

        assertEquals(WaypointCodec.Options.sanitizeLabel(groupName), group.name());
        assertEquals(WaypointCodec.Options.sanitizeLabel(waypointName), group.get(0).name());
        assertFalse(group.name().contains("\u00A7"));
        assertFalse(group.get(0).name().contains("\u00A7"));
    }

    @Test
    void parses_skytils_style_object() {
        String json = "{\"name\":\"Fetchur\",\"island\":\"mining-hub\",\"waypoints\":["
                + "{\"name\":\"stop1\",\"x\":1,\"y\":70,\"z\":2,\"color\":\"0.5:ff:10:20:30\"},"
                + "{\"x\":5,\"y\":70,\"z\":10}"
                + "]}";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(WaypointImporter.Source.SKYTILS, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals("Fetchur", g.name());
        assertEquals("mining_hub", g.zoneId()); // dashes normalized
        assertEquals(2, g.size());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode());
        assertEquals(0x102030, g.get(0).color());
    }

    @Test
    void malformed_skytils_colon_color_falls_back_to_default_color() {
        String json = "{\"name\":\"Fetchur\",\"island\":\"mining-hub\",\"waypoints\":["
                + "{\"name\":\"bad\",\"x\":1,\"y\":70,\"z\":2,\"color\":\"0.5:ff:zz:20:30\"},"
                + "{\"name\":\"short\",\"x\":5,\"y\":70,\"z\":10,\"color\":\"0.5:ff:10\"}"
                + "]}";

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup g = result.groups().get(0);

        assertEquals(2, g.size());
        assertEquals(Waypoint.DEFAULT_COLOR, g.get(0).color());
        assertEquals(Waypoint.DEFAULT_COLOR, g.get(1).color());
    }

    @Test
    void malformed_hex_color_falls_back_to_default_color() {
        String json = "{\"name\":\"Fetchur\",\"island\":\"mining-hub\",\"waypoints\":["
                + "{\"name\":\"bad\",\"x\":1,\"y\":70,\"z\":2,\"color\":\"#nothex\"}"
                + "]}";

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(Waypoint.DEFAULT_COLOR, result.groups().get(0).get(0).color());
    }

    @Test
    void parses_skytils_base64_categories_numeric_route_as_sequence() {
        WaypointImporter.ImportResult result = WaypointImporter.importAny(SKYTILS_TP_POINTS_SAMPLE);

        assertEquals(WaypointImporter.Source.SKYTILS, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals("TP Points", g.name());
        assertEquals("galatea", g.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode());
        assertEquals(10, g.size());
        assertEquals("-618,88,24", coordKey(g.get(0)));
        assertEquals("-642,96,-22", coordKey(g.get(9)));
    }

    @Test
    void parses_skytils_base64_categories_named_points_as_sequence() {
        WaypointImporter.ImportResult result = WaypointImporter.importAny(SKYTILS_POLE_POINTS_SAMPLE);

        assertEquals(WaypointImporter.Source.SKYTILS, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals("Pole Points", g.name());
        assertEquals("galatea", g.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode());
        assertEquals(3, g.size());
        assertEquals("Pole Point 1", g.get(0).name());
        assertEquals("-641,92,-20", coordKey(g.get(0)));
        assertEquals("-671,92,-11", coordKey(g.get(2)));
    }

    @Test
    void parses_skyblocker_legacy_map_of_islands() {
        String json = "{"
                + "\"dwarven_mines\":[{\"x\":1,\"y\":2,\"z\":3,\"name\":\"a\",\"r\":255,\"g\":128,\"b\":64}],"
                + "\"the_end\":[{\"x\":10,\"y\":20,\"z\":30}]"
                + "}";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        assertEquals(2, result.groups().size());
        WaypointGroup mines = result.groups().stream()
                .filter(g -> g.zoneId().equals("dwarven_mines")).findFirst().orElseThrow();
        assertEquals(0xFF8040, mines.get(0).color());
    }

    @Test
    void parses_soopy_style_top_level_waypoint_array() {
        String json = "[{\"x\":0,\"y\":1,\"z\":2,\"name\":\"p\"},{\"x\":3,\"y\":4,\"z\":5}]";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals(2, g.size());
    }

    @Test
    void parses_array_of_groups() {
        String json = "[{\"name\":\"A\",\"island\":\"hub\",\"waypoints\":[{\"x\":0,\"y\":0,\"z\":0}]},"
                + "{\"name\":\"B\",\"zone\":\"hub\",\"points\":[{\"x\":1,\"y\":1,\"z\":1}]}]";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(2, result.groups().size());
        assertEquals("A", result.groups().get(0).name());
        assertEquals("B", result.groups().get(1).name());
    }

    @Test
    void unprefixed_base64_gzipped_json_preserves_inner_json_source() throws Exception {
        String json = "[{\"name\":\"Glacite\",\"island\":\"glacite\",\"waypoints\":[{\"x\":1,\"y\":2,\"z\":3}]}]";
        String packed = Base64.getEncoder().encodeToString(gzip(json));
        WaypointImporter.ImportResult result = WaypointImporter.importAny(packed);
        assertEquals(WaypointImporter.Source.JSON, result.source());
        assertEquals(1, result.groups().size());
        assertEquals("Glacite", result.groups().get(0).name());
    }

    @Test
    void explicit_skyblocker_prefix_forces_skyblocker_source() throws Exception {
        String json = "[{\"name\":\"Glacite\",\"island\":\"glacite\",\"waypoints\":[{\"x\":1,\"y\":2,\"z\":3}]}]";
        String packed = WaypointImporter.SKYBLOCKER_V1_PREFIX
                + Base64.getEncoder().encodeToString(gzip(json));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(packed);

        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        assertEquals(1, result.groups().size());
    }

    @Test
    void rejects_garbage() {
        assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny("not-json-and-not-base64-gzip"));
    }

    @Test
    void parses_skyhanni_flat_array_with_options_name() {
        // SkyHanni uses the same flat route schema as legacy Coleweight:
        // float 0-1 r/g/b, options.name holds the step index.
        String json = "["
                + "{\"x\":100,\"y\":64,\"z\":200,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":1}},"
                + "{\"x\":110,\"y\":64,\"z\":210,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":2}},"
                + "{\"x\":120,\"y\":64,\"z\":220,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":3}}"
                + "]";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(WaypointImporter.Source.SKYHANNI, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals("Imported Route", g.name());
        assertEquals(3, g.size());
        assertEquals("1", g.get(0).name());
        assertEquals(100, g.get(0).x());
        assertEquals(220, g.get(2).z());
        // SkyHanni payloads carry no zone info, so parse time must leave the
        // group tagged UNKNOWN -- the command-layer retarget step is what
        // snaps it to the player's current zone. If the parser ever picks
        // a default zone on its own, the retarget step becomes a no-op and
        // this test (plus the call-site retarget test below) would flag it.
        assertEquals(dev.ethan.waypointer.core.Zone.UNKNOWN.id(), g.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode());
        // AUTO gradient rewrites colors on insert; what matters is the group picked AUTO
        // (so users can see route direction), not the specific post-gradient color.
        assertEquals(WaypointGroup.GradientMode.AUTO, g.gradientMode());
    }

    @Test
    void parses_current_skyhanni_wrapper_and_string_step_numbers() {
        String json = "{\"waypoints\":["
                + "{\"x\":30,\"y\":70,\"z\":40,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":\"2\"}},"
                + "{\"x\":10,\"y\":68,\"z\":20,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":\"1\"}}"
                + "]}";

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(WaypointImporter.Source.SKYHANNI, result.source());
        WaypointGroup group = result.groups().get(0);
        assertEquals(2, group.size());
        assertEquals("1", group.get(0).name());
        assertEquals(10, group.get(0).x());
        assertEquals("2", group.get(1).name());
        assertTrue(group.enabled());
    }

    @Test
    void parses_soopy_options_array_and_skips_null_coordinate_placeholders() {
        String json = "["
                + "{\"x\":null,\"y\":null,\"z\":null,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":1}},"
                + "{\"x\":20,\"y\":70,\"z\":40,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":2}}"
                + "]";

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(WaypointImporter.Source.SOOPY, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals(1, g.size());
        assertEquals("2", g.get(0).name());
        assertEquals(20, g.get(0).x());
        assertEquals(70, g.get(0).y());
        assertEquals(40, g.get(0).z());
    }

    @Test
    void rejects_soopy_options_array_with_only_null_coordinates_cleanly() {
        String json = "[{\"x\":null,\"y\":null,\"z\":null,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":1}}]";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny(json));

        assertTrue(ex.getMessage().contains("no waypoints"));
    }

    @Test
    void retargeting_unknown_groups_to_current_zone_preserves_zoned_groups() {
        // The retarget rule is "UNKNOWN becomes current, everything else is
        // left alone". Exercising both halves in one test keeps the contract
        // documented in a single place -- silent retargeting of a real zone
        // would destroy sender intent on Waypointer codec imports.
        WaypointGroup fromColeweight = WaypointGroup.create("cw",
                dev.ethan.waypointer.core.Zone.UNKNOWN.id());
        WaypointGroup fromOtherZone  = WaypointGroup.create("zoned", "dungeon_f7");

        java.util.List<WaypointGroup> groups = new java.util.ArrayList<>();
        groups.add(fromColeweight);
        groups.add(fromOtherZone);

        dev.ethan.waypointer.core.Zone target = dev.ethan.waypointer.core.Zone.fromId("the_park");
        int retargeted = 0;
        for (WaypointGroup g : groups) {
            if (dev.ethan.waypointer.core.Zone.UNKNOWN.id().equals(g.zoneId())) {
                g.setZoneId(target.id());
                retargeted++;
            }
        }
        assertEquals(1, retargeted);
        assertEquals("the_park", fromColeweight.zoneId());
        assertEquals("dungeon_f7", fromOtherZone.zoneId());
    }

    @Test
    void sorts_coleweight_by_numeric_option_name_regardless_of_array_order() {
        // JSON arrays list steps 3, 1, 2 -- importer should sort back to 1, 2, 3.
        String json = "["
                + "{\"x\":30,\"y\":64,\"z\":0,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":3}},"
                + "{\"x\":10,\"y\":64,\"z\":0,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":1}},"
                + "{\"x\":20,\"y\":64,\"z\":0,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":2}}"
                + "]";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup g = result.groups().get(0);
        assertEquals(10, g.get(0).x());
        assertEquals(20, g.get(1).x());
        assertEquals(30, g.get(2).x());
    }

    @Test
    void keeps_coleweight_array_order_when_names_are_non_numeric() {
        String json = "["
                + "{\"x\":1,\"y\":0,\"z\":0,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":\"start\"}},"
                + "{\"x\":2,\"y\":0,\"z\":0,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":\"mid\"}},"
                + "{\"x\":3,\"y\":0,\"z\":0,\"r\":0,\"g\":1,\"b\":0,\"options\":{\"name\":\"end\"}}"
                + "]";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup g = result.groups().get(0);
        assertEquals("start", g.get(0).name());
        assertEquals("mid",   g.get(1).name());
        assertEquals("end",   g.get(2).name());
    }

    @Test
    void converts_coleweight_float_rgb_to_byte_channels() {
        // The coleweight importer stamps AUTO gradient on the group, which overwrites
        // per-point colors on add(), so we exercise the pure color conversion directly.
        assertEquals(0xFF8000, WaypointImporter.coleweightRgb(1.0, 0.5, 0.0));
        assertEquals(0x00FF00, WaypointImporter.coleweightRgb(0.0, 1.0, 0.0));
        assertEquals(0x000000, WaypointImporter.coleweightRgb(0.0, 0.0, 0.0));
        assertEquals(0xFFFFFF, WaypointImporter.coleweightRgb(1.0, 1.0, 1.0));
        // Out-of-range channels clamp to [0, 255].
        assertEquals(0xFF0000, WaypointImporter.coleweightRgb(2.5, -1.0, 0.0));
    }

    @Test
    void does_not_misclassify_skyblocker_byte_rgb_as_coleweight() {
        // Skyblocker uses r/g/b but no `options` key -- must still route to the legacy
        // loose-waypoint path so byte colors aren't mangled as [0,1] floats.
        String json = "[{\"x\":1,\"y\":2,\"z\":3,\"r\":255,\"g\":128,\"b\":64,\"name\":\"a\"}]";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(WaypointImporter.Source.JSON, result.source());
        assertEquals(0xFF8040, result.groups().get(0).get(0).color());
    }

    // --- Skyblocker V1 format ---------------------------------------------------------------

    /**
     * Real payload copied from the Skyblocker mod's share-string UI. Locks the
     * on-wire format so changes to the importer don't silently regress
     * compatibility with shared routes already circulating in chat screenshots
     * and reddit threads.
     * 
     * Thanks Amelia for the sample.
     */
    private static final String SKYBLOCKER_V1_SAMPLE =
            "[Skyblocker-Waypoint-Data-V1]H4sIAAAAAAAA/9WSTQuDIBjH730K8dwiW3tp1x1222EMdogOUrIEU+mFiLHvPmvZdIzRNUH0" +
            "eX4P/v3rEzsAPNQEALa4k4Ly+tpJAg8A5vSeMzVr6L4LOC4GcCYtOJWikRrQimGe9SjrVBFNNdBHVorFQ0qrDTgVTJRHUUjBiV3UD9/z" +
            "XSNEdqjoFCUfADGTOVYn+d7GyFa5aFh2ITwjpYJ12RCDSvGtjZAl7YdmGGx/KuvnuY2mAYIjfLpL8r5Clth+Z90EzfMeLNR7ZGpFVhc" +
            "E+3nW18u0jsI/vz6z48PJ+rAmTr9LXnQ2MJFkBAAA";

    @Test
    void parses_skyblocker_v1_prefixed_real_payload() {
        WaypointImporter.ImportResult result = WaypointImporter.importAny(SKYBLOCKER_V1_SAMPLE);

        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        assertEquals(1, result.groups().size());

        WaypointGroup g = result.groups().get(0);
        assertEquals("New Group", g.name());
        // "dynamic" is Skyblocker's Private Island id -- we alias it so imports
        // don't strand the user on an unrecognized zone.
        assertEquals("private_island", g.zoneId());
        assertEquals(4, g.size());

        // Skyblocker V1 uses pos:[x,y,z] and every point in the sample carries a
        // numbered name. Validate both so regressions in array-coord parsing or
        // name handling fail loudly.
        assertEquals(11, g.get(0).x());
        assertEquals(104, g.get(0).y());
        assertEquals(26, g.get(0).z());
        assertEquals("Waypoint 1", g.get(0).name());
        assertEquals("Waypoint 4", g.get(3).name());
    }

    @Test
    void parses_skyblocker_v1_synthetic_payload_with_pos_and_colorComponents() throws Exception {
        // Synthetic payload with a known island alias + colorComponents so we can
        // assert specific color bytes (the real sample paints everything [0,1,0]
        // green, which is also the default, making that a weak test signal).
        String json = "[{"
                + "\"name\":\"Route\","
                + "\"island\":\"foraging_1\","
                + "\"waypoints\":["
                + "{\"pos\":[10,64,20],\"name\":\"A\",\"colorComponents\":[1.0,0.5,0.0]},"
                + "{\"pos\":[30,64,40],\"name\":\"B\",\"colorComponents\":[0.0,0.0,1.0]}"
                + "]}]";
        String packed = WaypointImporter.SKYBLOCKER_V1_PREFIX
                + Base64.getEncoder().encodeToString(gzip(json));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(packed);

        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        WaypointGroup g = result.groups().get(0);
        assertEquals("Route", g.name());
        assertEquals("the_park", g.zoneId());    // foraging_1 -> the_park alias
        assertEquals(2, g.size());
        assertEquals(10, g.get(0).x());
        assertEquals("A", g.get(0).name());
        // Skyblocker renders all points in unordered groups; MANUAL preserves explicit colors.
        assertEquals(WaypointGroup.LoadMode.STATIC, g.loadMode());
        assertEquals(WaypointGroup.GradientMode.MANUAL, g.gradientMode());
        assertEquals(0xFF8000, g.get(0).color());
        assertEquals(0x0000FF, g.get(1).color());
    }

    @Test
    void skyblocker_ordered_groups_preserve_explicit_colors() throws Exception {
        // The upstream codec carries both order and per-point colors. Sequence mode
        // represents the order without overwriting the sender's colors.
        String json = "[{"
                + "\"name\":\"Path\","
                + "\"island\":\"hub\","
                + "\"ordered\":true,"
                + "\"waypoints\":["
                + "{\"pos\":[1,64,1],\"colorComponents\":[0,1,0]},"
                + "{\"pos\":[2,64,2],\"colorComponents\":[0,1,0]},"
                + "{\"pos\":[3,64,3],\"colorComponents\":[0,1,0]}"
                + "]}]";
        String packed = WaypointImporter.SKYBLOCKER_V1_PREFIX
                + Base64.getEncoder().encodeToString(gzip(json));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(packed);
        WaypointGroup g = result.groups().get(0);
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode());
        assertEquals(WaypointGroup.GradientMode.MANUAL, g.gradientMode());
        assertEquals(0x00FF00, g.get(0).color());
        assertEquals(3, g.size());
    }

    @Test
    void skyblocker_island_aliases_map_hypixel_ids_to_waypointer_zones() {
        // Spot-check every alias that re-routes a Hypixel internal id onto a
        // Waypointer display id. The full table lives in the importer; this
        // test is the canary that catches accidental removals or renames.
        assertEquals("private_island",    WaypointImporter.normalizeZone("dynamic"));
        assertEquals("the_farming_isles", WaypointImporter.normalizeZone("farming_1"));
        assertEquals("the_park",          WaypointImporter.normalizeZone("foraging_1"));
        assertEquals("spiders_den",       WaypointImporter.normalizeZone("combat_1"));
        assertEquals("the_end",           WaypointImporter.normalizeZone("combat_3"));
        assertEquals("gold_mine",         WaypointImporter.normalizeZone("mining_1"));
        assertEquals("deep_caverns",      WaypointImporter.normalizeZone("mining_2"));
        assertEquals("dwarven_mines",     WaypointImporter.normalizeZone("mining_3"));
        // "dungeon" has no safe one-to-one alias (Waypointer zones are per-floor),
        // so we map it to UNKNOWN and let the import command's retarget step
        // snap it to whatever floor the player is currently in.
        assertEquals(dev.ethan.waypointer.core.Zone.UNKNOWN.id(),
                WaypointImporter.normalizeZone("dungeon"));
        // Unknown ids are passed through unchanged -- lowercasing/dash-to-underscore
        // only, no invented aliases.
        assertEquals("some_future_island",
                WaypointImporter.normalizeZone("Some-Future-Island"));
    }

    @Test
    void skyblocker_v1_body_without_base64_errors_usefully() {
        // Garbage body after a valid prefix should produce an error message that
        // names the prefix we matched -- otherwise the user sees the generic
        // "unrecognized waypoint payload" and has no idea which parser failed.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny("[Skyblocker-Waypoint-Data-V1]!!!not-base64!!!"));
        assertTrue(ex.getMessage().contains("Skyblocker"),
                "error should mention Skyblocker; got: " + ex.getMessage());
    }

    @Test
    void skyblocker_legacy_ordered_map_parses_as_per_route_groups() {
        // Skyblocker's pre-V1 ordered format: {groupName: {name, enabled, waypoints}}
        // Verify the map key becomes the group name, the per-point coords land
        // correctly, and we pick AUTO gradient for the route-direction hint.
        String json = "{"
                + "\"Dragon Path\":{"
                + "  \"name\":\"Dragon Path\","
                + "  \"enabled\":true,"
                + "  \"waypoints\":["
                + "    {\"x\":100,\"y\":64,\"z\":100},"
                + "    {\"x\":110,\"y\":64,\"z\":110}"
                + "  ]"
                + "}}";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(WaypointImporter.Source.SKYBLOCKER, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup g = result.groups().get(0);
        assertEquals("Dragon Path", g.name());
        assertEquals(2, g.size());
        assertEquals(WaypointGroup.GradientMode.AUTO, g.gradientMode());
    }

    private static final String SKYTILS_TP_POINTS_SAMPLE =
            "eyJjYXRlZ29yaWVzIjpbeyJuYW1lIjoiVFAgUG9pbnRzIiwiaXNsYW5kIjoiZm9yYWdpbmdfMiIsIndheXBvaW50cyI6W3si"
                    + "bmFtZSI6IjEiLCJjb2xvciI6MjEzMDc3MTcxMiwiZW5hYmxlZCI6dHJ1ZSwieCI6LTYxOCwieSI6ODgsInoiOjI0fSx7"
                    + "Im5hbWUiOiIyIiwiY29sb3IiOjIxMzA3NzE3MTIsImVuYWJsZWQiOnRydWUsIngiOi02MTAsInkiOjkzLCJ6IjozMX0s"
                    + "eyJuYW1lIjoiMyIsImNvbG9yIjoyMTMwNzcxNzEyLCJlbmFibGVkIjp0cnVlLCJ4IjotNjE1LCJ5Ijo5NCwieiI6NDV9"
                    + "LHsibmFtZSI6IjQiLCJjb2xvciI6MjEzMDc3MTcxMiwiZW5hYmxlZCI6dHJ1ZSwieCI6LTYxMywieSI6MTAwLCJ6Ijo2"
                    + "M30seyJuYW1lIjoiNSIsImNvbG9yIjoyMTMwNzcxNzEyLCJlbmFibGVkIjp0cnVlLCJ4IjotNjI4LCJ5IjoxMDIsInoi"
                    + "Ojc2fSx7Im5hbWUiOiI2IiwiY29sb3IiOjIxMzA3NzE3MTIsImVuYWJsZWQiOnRydWUsIngiOi02NTAsInkiOjEwMSwi"
                    + "eiI6NTN9LHsibmFtZSI6IjciLCJjb2xvciI6MjEzMDc3MTcxMiwiZW5hYmxlZCI6dHJ1ZSwieCI6LTY3MCwieSI6OTYs"
                    + "InoiOjMwfSx7Im5hbWUiOiI4IiwiY29sb3IiOjIxMzA3NzE3MTIsImVuYWJsZWQiOnRydWUsIngiOi02NzEsInkiOjk2"
                    + "LCJ6IjotMTB9LHsibmFtZSI6IjkiLCJjb2xvciI6MjEzMDc3MTcxMiwiZW5hYmxlZCI6dHJ1ZSwieCI6LTY3MiwieSI6"
                    + "OTQsInoiOi0zMn0seyJuYW1lIjoiMTAiLCJjb2xvciI6MjEzMDc3MTcxMiwiZW5hYmxlZCI6dHJ1ZSwieCI6LTY0Miwi"
                    + "eSI6OTYsInoiOi0yMn1dfV19";

    private static final String SKYTILS_POLE_POINTS_SAMPLE =
            "eyJjYXRlZ29yaWVzIjpbeyJuYW1lIjoiUG9sZSBQb2ludHMiLCJpc2xhbmQiOiJmb3JhZ2luZ18yIiwid2F5cG9pbnRz"
                    + "IjpbeyJuYW1lIjoiUG9sZSBQb2ludCAxIiwiY29sb3IiOjIxMzA3NzE3MTIsImVuYWJsZWQiOnRydWUsIngiOi02NDEs"
                    + "InkiOjkyLCJ6IjotMjB9LHsibmFtZSI6IlBvbGUgUG9pbnQgMiIsImNvbG9yIjoyMTMwNzcxNzEyLCJlbmFibGVkIjp0"
                    + "cnVlLCJ4IjotNjcxLCJ5Ijo5MSwieiI6Mjl9LHsibmFtZSI6IlBvbGUgUG9pbnQgMyIsImNvbG9yIjoyMTMwNzcxNzEy"
                    + "LCJlbmFibGVkIjp0cnVlLCJ4IjotNjcxLCJ5Ijo5MiwieiI6LTExfV19XX0=";

    private static String coordKey(Waypoint w) {
        return w.x() + "," + w.y() + "," + w.z();
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes());
        }
        return out.toByteArray();
    }
}
