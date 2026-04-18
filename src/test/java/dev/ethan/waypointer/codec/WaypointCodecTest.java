package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip safety net for the import/export format. A regression here silently
 * corrupts every shared waypoint chain, so we lean on fuzzing over hand-crafted cases.
 */
class WaypointCodecTest {

    @Test
    void magic_prefix_is_emitted() {
        String s = WaypointCodec.encode(List.of(sampleGroup("A", "hub")));
        assertTrue(s.startsWith(WaypointCodec.MAGIC), "missing " + WaypointCodec.MAGIC + " prefix: " + s);
    }

    @Test
    void round_trip_single_group() {
        WaypointGroup before = sampleGroup("Gold Run", "dungeon_f7");
        List<WaypointGroup> decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(before)));
        assertEquals(1, decoded.size());
        assertGroupsEqual(before, decoded.get(0));
    }

    @Test
    void round_trip_multiple_groups_preserves_order() {
        WaypointGroup a = sampleGroup("A", "dwarven_mines");
        WaypointGroup b = sampleGroup("B", "crystal_hollows");
        b.setDefaultRadius(7.5);
        b.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        // currentIndex is intentionally NOT part of the export contract, so we
        // don't exercise it here. The decoded b will start at index 0 regardless.

        List<WaypointGroup> decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(a, b)));
        assertEquals(2, decoded.size());
        assertGroupsEqual(a, decoded.get(0));
        assertGroupsEqual(b, decoded.get(1));
    }

    @Test
    void rejects_missing_magic() {
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("not-a-waypointer-export"));
    }

    @Test
    void rejects_garbled_payload() {
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(WaypointCodec.MAGIC + "ZZZZZZZ"));
    }

    @Test
    void rejects_legacy_prefixes() {
        // Early prototypes tried WPTR1:, WP2:, and WP3: as the magic. None of them
        // shipped. The current format uses a version-in-header scheme so the magic
        // itself is just WP:; anything else must be rejected outright -- no silent
        // fallback, no misleading "unsupported version" error from deep inside
        // the decoder.
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WPTR1:AAAABBBBCCCCDDDD"));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WP2:AAAABBBBCCCCDDDD"));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WP3:AAAABBBBCCCCDDDD"));
    }

    @Test
    void empty_group_list_round_trips() {
        List<WaypointGroup> decoded = WaypointCodec.decode(WaypointCodec.encode(List.of()));
        assertTrue(decoded.isEmpty());
    }

    @Test
    void fuzz_random_routes_round_trip() {
        // Progress (currentIndex) and enabled state are deliberately NOT exported,
        // so we leave both at the WaypointGroup defaults on the source side.
        // Exercising them here would just prove the decoder overrides them back
        // to their defaults, which is already covered by the dedicated test.
        Random r = new Random(0xC0FFEE);
        for (int trial = 0; trial < 50; trial++) {
            List<WaypointGroup> groups = new ArrayList<>();
            int nGroups = 1 + r.nextInt(4);
            for (int gi = 0; gi < nGroups; gi++) {
                WaypointGroup g = WaypointGroup.create("group-" + trial + "-" + gi, "zone-" + r.nextInt(6));
                g.setDefaultRadius(1.0 + r.nextInt(20));
                g.setGradientMode(r.nextBoolean() ? WaypointGroup.GradientMode.AUTO
                        : WaypointGroup.GradientMode.MANUAL);

                int nPts = r.nextInt(30);
                for (int i = 0; i < nPts; i++) {
                    int x = r.nextInt(2000) - 1000;
                    int y = r.nextInt(320);
                    int z = r.nextInt(2000) - 1000;
                    String name = r.nextBoolean() ? "p" + i : "";
                    int color = r.nextInt(0xFFFFFF);
                    int flags = r.nextInt(0x10);
                    double radius = r.nextBoolean() ? 0 : (0.5 + r.nextInt(50));
                    g.add(new Waypoint(x, y, z, name, color, flags, radius));
                }
                groups.add(g);
            }

            String encoded = WaypointCodec.encode(groups);
            List<WaypointGroup> decoded = WaypointCodec.decode(encoded);
            assertEquals(groups.size(), decoded.size(), "trial " + trial + " group count");
            for (int i = 0; i < groups.size(); i++) {
                assertGroupsEqual(groups.get(i), decoded.get(i));
            }
        }
    }

    @Test
    void packed_payload_is_small() {
        WaypointGroup g = WaypointGroup.create("Gold Run", "dungeon_f7");
        for (int i = 0; i < 40; i++) g.add(Waypoint.at(i * 3, 70, i * 2));
        String s = WaypointCodec.encode(List.of(g));
        // JSON for the same route weighs multiple kilobytes; the CJK-packed form
        // should beat the old Z85 codec by 2x+ on the same fixture.
        assertTrue(s.length() < 150, "expected packed export < 150 chars, got " + s.length() + ": " + s);
    }

    @Test
    void fifty_named_waypoints_fit_in_chat_limit() {
        // The whole point of the CJK densification: a 50-waypoint named route
        // must fit in a single 255-char Minecraft chat message. Guard that
        // headline capability with a direct test.
        WaypointGroup g = WaypointGroup.create("Big Run", "dungeon_f7");
        for (int i = 0; i < 50; i++) {
            g.add(new Waypoint(100 + i * 3, 70 + (i % 5), 200 + i * 2,
                    "pt" + i, Waypoint.DEFAULT_COLOR, 0, 0));
        }
        String s = WaypointCodec.encode(List.of(g));
        assertTrue(s.length() <= 255,
                "50 named waypoints must fit in 255 chars; got " + s.length() + ": " + s);
    }

    @Test
    void body_chars_are_all_in_cjk_alphabet_range() {
        // Every non-magic character must land in [U+4E00, U+4E00 + 16384) so
        // chat paste, MC's chat validator, and the Unifont fallback all accept it.
        WaypointGroup g = sampleGroup("range check", "dungeon_f7");
        String s = WaypointCodec.encode(List.of(g));
        String body = s.substring(WaypointCodec.MAGIC.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            assertTrue(c >= 0x4E00 && c < 0x4E00 + 16384,
                    "body char at " + i + " out of CJK alphabet range: U+"
                            + Integer.toHexString(c).toUpperCase());
        }
    }

    @Test
    void packed_export_is_chat_paste_safe() {
        // The whole point of the codec string is that it survives being retyped/pasted
        // into a Minecraft chat box, which collapses runs of spaces. A leaked space
        // character here would silently truncate shared routes.
        WaypointGroup g = sampleGroup("space test", "zone");
        String s = WaypointCodec.encode(List.of(g));
        assertFalse(s.contains(" "), "codec output must contain zero whitespace; got: " + s);
        assertFalse(s.contains("\t"), "codec output must contain no tabs");
        assertFalse(s.contains("\n"), "codec output must contain no newlines");
    }

    @Test
    void round_trip_preserves_load_mode() {
        WaypointGroup g = sampleGroup("seq", "zone");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, decoded.loadMode(),
                "sequence load-mode must survive export/import so shared routes keep their behavior");
    }

    @Test
    void round_trip_preserves_default_radius() {
        WaypointGroup g = sampleGroup("wide radius", "zone");
        g.setDefaultRadius(12.5);
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals(12.5, decoded.defaultRadius(), 1e-6);
    }

    @Test
    void no_names_export_omits_names_and_still_decodes_geometry() {
        // When sharing a "clean" route you don't want teammate nicknames / dev labels
        // leaking. NO_NAMES strips names but must still round-trip coords.
        WaypointGroup g = WaypointGroup.create("Secret Route", "dungeon_f7");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(new Waypoint(10, 70, 20, "alpha-personal",    0xAABBCC, 0, 0));
        g.add(new Waypoint(11, 71, 22, "bravo-personal",    0xDDEEFF, 0, 0));
        g.add(new Waypoint(12, 72, 24, "charlie-personal",  0x001122, 0, 0));

        String stripped = WaypointCodec.encode(List.of(g), WaypointCodec.Options.NO_NAMES);
        // Name strings themselves shouldn't be in the payload. We can't see through
        // deflate+CJK directly, but we can confirm the output is smaller than a names
        // export, and that the decoded waypoints come back nameless.
        String withNames = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES);
        assertTrue(stripped.length() < withNames.length(),
                "NO_NAMES export should be shorter than WITH_NAMES; stripped="
                        + stripped.length() + " named=" + withNames.length());

        WaypointGroup decoded = WaypointCodec.decode(stripped).get(0);
        assertEquals(3, decoded.size());
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
            assertEquals("", decoded.get(i).name(), "name must be stripped at index " + i);
        }
    }

    @Test
    void exports_always_reset_session_state_on_import() {
        // Exports are for sharing a route, not a session. The codec drops
        // per-group enabled state and currentIndex unconditionally so imports
        // always start fresh (enabled = true, index = 0), regardless of what
        // the sender's group looked like when they hit Export.
        WaypointGroup g = sampleGroup("Boss Route", "dungeon_f7");
        g.setCurrentIndex(2);
        g.setEnabled(false);

        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals(0, decoded.currentIndex(),
                "exports must reset progress so recipients don't inherit the sender's position");
        assertTrue(decoded.enabled(),
                "exports default enabled=true so a shared route activates on import");
    }

    @Test
    void round_trip_preserves_every_shareable_metadata_field() {
        // Every field that defines the ROUTE (not the session) must survive a
        // round trip. Enabled-state and currentIndex are explicitly not in this
        // set -- see exports_always_reset_session_state_on_import.
        WaypointGroup g = WaypointGroup.create("F7 Terminals", "dungeon_f7");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(6.0);
        g.add(new Waypoint(100, 64, 200, "T1", 0x11AACC, Waypoint.FLAG_LOCKED_COLOR, 0));
        g.add(new Waypoint(110, 64, 210, "T2", 0x22BBCD,
                Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON, 4.5));
        g.add(new Waypoint(120, 64, 220, "",   0x33CCDE, 0, 0));

        WaypointGroup d = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals("F7 Terminals", d.name());
        assertEquals("dungeon_f7", d.zoneId());
        assertEquals(WaypointGroup.GradientMode.MANUAL, d.gradientMode());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, d.loadMode());
        assertEquals(6.0, d.defaultRadius(), 1e-6);
        assertTrue(d.enabled(), "import always activates the group");
        assertEquals(0, d.currentIndex(), "import always starts fresh");
        assertEquals(3, d.size());

        assertEquals("T1", d.get(0).name());
        assertEquals(0x11AACC, d.get(0).color());
        assertEquals(Waypoint.FLAG_LOCKED_COLOR, d.get(0).flags());

        assertEquals("T2", d.get(1).name());
        assertEquals(4.5, d.get(1).customRadius(), 1e-6);
        assertEquals(Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON, d.get(1).flags());

        assertEquals("", d.get(2).name());
    }

    @Test
    void isCodecString_recognizes_current_magic() {
        assertTrue(WaypointCodec.isCodecString(WaypointCodec.MAGIC + "abcd0"));
        assertTrue(WaypointCodec.isCodecString("   " + WaypointCodec.MAGIC + "xyz0   "));
        assertFalse(WaypointCodec.isCodecString("random text WP no colon"));
        // Historical prefix shapes must not accidentally qualify -- none of them
        // ever shipped, but they all survived early prototyping and might crop up
        // in other people's notes or blog posts. Version handling now lives in the
        // header byte, not the magic, so there is exactly one valid prefix.
        assertFalse(WaypointCodec.isCodecString("WPTR1:abc"));
        assertFalse(WaypointCodec.isCodecString("WP2:abc"));
        assertFalse(WaypointCodec.isCodecString("WP3:abc"));
        assertFalse(WaypointCodec.isCodecString(null));
        assertFalse(WaypointCodec.isCodecString(""));
    }

    // --- granular Options toggles ----------------------------------------------------------

    @Test
    void include_colors_false_strips_per_waypoint_colors_to_default() {
        // Sender opts out of colors; recipient must see DEFAULT_COLOR everywhere
        // even though the source had distinct values per point. This is the
        // "share a route, recolor it on my end" workflow.
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(new Waypoint(0, 70, 0, "", 0xFF0000, 0, 0));
        g.add(new Waypoint(5, 70, 5, "", 0x00FF00, 0, 0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeColors(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(Waypoint.DEFAULT_COLOR, decoded.get(i).color(),
                    "color must reset to default at index " + i);
        }
    }

    @Test
    void include_radii_false_strips_custom_per_waypoint_radius() {
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 8.5));
        g.add(new Waypoint(1, 70, 1, "", Waypoint.DEFAULT_COLOR, 0, 12.0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeRadii(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(0.0, decoded.get(i).customRadius(), 1e-6,
                    "custom radius must clear at index " + i);
        }
    }

    @Test
    void include_waypoint_flags_false_strips_per_waypoint_flag_bits() {
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR,
                Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON, 0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeWaypointFlags(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        assertEquals(0, decoded.get(0).flags(),
                "flag bits must reset to 0 when includeWaypointFlags is off");
    }

    @Test
    void include_group_meta_false_strips_gradient_load_mode_and_default_radius() {
        // Group metadata stripping should make the recipient see plain
        // defaults: AUTO gradient, STATIC load order, default 3.0 radius.
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(7.5);
        g.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeGroupMeta(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        assertEquals(WaypointGroup.GradientMode.AUTO, decoded.gradientMode(),
                "gradient must default to AUTO when group meta is dropped");
        assertEquals(WaypointGroup.LoadMode.STATIC, decoded.loadMode(),
                "load mode must default to STATIC when group meta is dropped");
        assertEquals(3.0, decoded.defaultRadius(), 1e-6,
                "default radius must reset to 3.0 when group meta is dropped");
    }

    @Test
    void granular_strip_produces_smaller_payload_than_full_export() {
        // The whole point of the toggles: stripping optional fields must yield
        // a byte-smaller payload. If a future encoder regression silently
        // included the data anyway, this would catch it.
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(8.0);
        for (int i = 0; i < 10; i++) {
            g.add(new Waypoint(i, 70 + i, i * 2, "p" + i,
                    0xAA0000 | i, Waypoint.FLAG_LOCKED_COLOR, 4.5));
        }

        int full = WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.builder()
                        .includeNames(true).includeColors(true).includeRadii(true)
                        .includeWaypointFlags(true).includeGroupMeta(true).build()).length();
        int stripped = WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.builder()
                        .includeNames(false).includeColors(false).includeRadii(false)
                        .includeWaypointFlags(false).includeGroupMeta(false).build()).length();
        assertTrue(stripped < full,
                "all-toggles-off must beat all-toggles-on; stripped=" + stripped + " full=" + full);
    }

    @Test
    void label_sanitization_strips_section_signs_and_caps_visible_chars() {
        // sanitizeLabel must drop section signs (formatting injection) and
        // truncate to MAX_LABEL_CHARS so an encoded payload never carries a
        // hostile or oversized title. Both rules are enforced at the Options
        // boundary so neither encoder nor decoder has to re-validate.
        String hostile = "\u00A7c" + "Pwned " + "\u00A74".repeat(5);
        String sanitized = WaypointCodec.Options.sanitizeLabel(hostile);
        assertFalse(sanitized.contains("\u00A7"),
                "section signs must be stripped, got: " + sanitized);

        String giant = "x".repeat(1000);
        String capped = WaypointCodec.Options.sanitizeLabel(giant);
        assertTrue(capped.length() <= WaypointCodec.Options.MAX_LABEL_CHARS,
                "label must be capped at MAX_LABEL_CHARS; got " + capped.length());
    }

    // --- coord packing mode selection ---------------------------------------------------------

    @Test
    void auto_mode_beats_absolute_on_yoyo_route_inside_fixed_bounds() {
        // Yo-yo path entirely within the FIXED_COMPACT window ([-2048, +2047]
        // on x/z). Delta mode exacts big jumps; absolute-varint spends ~2 bytes
        // per coord; fixed-compact spends only 33 bits per waypoint. Fixed
        // should win outright, and AUTO must pick it.
        WaypointGroup g = WaypointGroup.create("yoyo", "z");
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(2000, 100, 2000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));

        int vector   = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_VECTOR).length();
        int absolute = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_ABSOLUTE).length();
        int fixed    = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_FIXED).length();
        int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();

        assertTrue(auto <= vector && auto <= absolute && auto <= fixed,
                "AUTO must pick the smallest; auto=" + auto + " vector=" + vector
                        + " absolute=" + absolute + " fixed=" + fixed);
    }

    @Test
    void auto_mode_no_worse_than_either_when_coords_exceed_fixed_bounds() {
        // Yo-yo shape far enough out that FIXED_COMPACT is ineligible; AUTO must
        // still pick the smallest of the two remaining modes. Not asserting the
        // specific ordering of absolute vs vector because deflate+dictionary can
        // blur the raw-byte ranking when coord streams compress differently.
        WaypointGroup g = WaypointGroup.create("yoyo_far", "z");
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(1_000_000, 100, 1_000_000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));

        int vector   = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_VECTOR).length();
        int absolute = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_ABSOLUTE).length();
        int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();

        assertTrue(auto <= vector && auto <= absolute,
                "AUTO must not be worse than either forced mode; auto=" + auto
                        + " vector=" + vector + " absolute=" + absolute);
    }

    @Test
    void auto_mode_picks_vector_when_waypoints_cluster_far_from_origin() {
        // Dense cluster at far coords: each step is tiny but the absolute coord is
        // 4 bytes of varint. Vector (delta) mode should win here. FIXED_COMPACT
        // is out of range at these magnitudes, so AUTO falls back to vector.
        WaypointGroup g = WaypointGroup.create("cluster", "z");
        int base = 2_000_000;
        for (int i = 0; i < 20; i++) {
            g.add(new Waypoint(base + i, 80, base + i * 2, "", Waypoint.DEFAULT_COLOR, 0, 0));
        }

        int vector   = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_VECTOR).length();
        int absolute = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_ABSOLUTE).length();
        int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();

        assertTrue(vector < absolute,
                "clustered route should be smaller under vector packing; vector=" + vector
                        + " absolute=" + absolute);
        assertTrue(auto <= vector,
                "AUTO must not be worse than vector; auto=" + auto + " vector=" + vector);
    }

    @Test
    void fixed_compact_round_trips_coords_exactly() {
        // FIXED_COMPACT packs coords with bit-level precision; any bit-width math
        // error would silently corrupt the decoded values. Explicit per-axis
        // checks across the full supported range.
        WaypointGroup g = WaypointGroup.create("edges", "z");
        g.add(new Waypoint(-2048, -64, -2048, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 2047, 447,  2047, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(    0,   0,     0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 1234,  75,  -456, "", Waypoint.DEFAULT_COLOR, 0, 0));

        String encoded = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_FIXED);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        assertEquals(g.size(), decoded.size());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void fixed_compact_rejects_out_of_bounds_when_forced() {
        // If FIXED_COMPACT is forced on a group whose coords overflow the bit
        // widths, we must throw rather than silently truncate.
        WaypointGroup g = WaypointGroup.create("oob", "z");
        g.add(new Waypoint(100_000, 80, 100_000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                        WaypointCodec.PackingMode.FORCE_FIXED));
    }

    @Test
    void all_forced_modes_round_trip_identically() {
        // Packing mode is purely a byte-count optimization -- every mode must
        // decode to the same logical group.
        WaypointGroup g = sampleGroup("A", "z");
        WaypointGroup fromVector = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                        WaypointCodec.PackingMode.FORCE_VECTOR)).get(0);
        WaypointGroup fromAbsolute = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                        WaypointCodec.PackingMode.FORCE_ABSOLUTE)).get(0);
        WaypointGroup fromFixed = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                        WaypointCodec.PackingMode.FORCE_FIXED)).get(0);
        assertGroupsEqual(g, fromVector);
        assertGroupsEqual(g, fromAbsolute);
        assertGroupsEqual(g, fromFixed);
    }

    @Test
    void auto_picks_minimum_on_every_fuzz_case() {
        // Strongest safety guarantee: AUTO must never be larger than any eligible
        // forced mode on any input. If it ever is, the selection logic is broken.
        Random r = new Random(0xF00DF00DL);
        for (int trial = 0; trial < 20; trial++) {
            WaypointGroup g = WaypointGroup.create("t" + trial, "z");
            int n = 2 + r.nextInt(15);
            boolean fixedEligible = true;
            for (int i = 0; i < n; i++) {
                // Mix of origin-adjacent and far coords to stress both modes.
                int scale = r.nextBoolean() ? 1 : 1_000_000;
                int x = (r.nextInt(2000) - 1000) * scale;
                int z = (r.nextInt(2000) - 1000) * scale;
                int y = r.nextInt(320);
                if (x < -2048 || x > 2047 || z < -2048 || z > 2047) fixedEligible = false;
                g.add(new Waypoint(x, y, z, "", Waypoint.DEFAULT_COLOR, 0, 0));
            }

            int vector   = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                    WaypointCodec.PackingMode.FORCE_VECTOR).length();
            int absolute = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                    WaypointCodec.PackingMode.FORCE_ABSOLUTE).length();
            int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();
            assertTrue(auto <= vector && auto <= absolute,
                    "trial " + trial + ": auto=" + auto + " vector=" + vector + " absolute=" + absolute);
            if (fixedEligible) {
                int fixed = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                        WaypointCodec.PackingMode.FORCE_FIXED).length();
                assertTrue(auto <= fixed,
                        "trial " + trial + ": auto=" + auto + " fixed=" + fixed);
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static WaypointGroup sampleGroup(String name, String zone) {
        WaypointGroup g = WaypointGroup.create(name, zone);
        g.setDefaultRadius(4.0);
        g.add(new Waypoint(10, 70, -20, "start", 0x40E0D0, 0, 0.0));
        g.add(new Waypoint(15, 71, -18, "", 0xFF5577, Waypoint.FLAG_LOCKED_COLOR, 5.0));
        g.add(new Waypoint(30, 68, 5, "boss", 0xFFDD00, Waypoint.FLAG_HIDE_BEACON, 0.0));
        return g;
    }

    private static void assertGroupsEqual(WaypointGroup expected, WaypointGroup actual) {
        assertEquals(expected.name(), actual.name(), "name");
        assertEquals(expected.zoneId(), actual.zoneId(), "zoneId");
        // enabled and currentIndex are never written by the encoder (see
        // exports_always_reset_session_state_on_import). This helper only stays
        // valid when callers leave them at the WaypointGroup defaults; tests
        // that deliberately mutate either field must not route through here.
        assertTrue(actual.enabled(), "decoded groups always import enabled");
        assertEquals(0, actual.currentIndex(), "decoded groups always start at index 0");
        assertEquals(expected.defaultRadius(), actual.defaultRadius(), 1e-6, "defaultRadius");
        assertEquals(expected.gradientMode(), actual.gradientMode(), "gradientMode");
        assertEquals(expected.size(), actual.size(), "size");
        // Waypoint comparison: compare position/flags/radius strictly; compare color
        // loosely when the source was AUTO because encode/decode re-applies the gradient.
        boolean looseColor = expected.gradientMode() == WaypointGroup.GradientMode.AUTO;
        for (int i = 0; i < expected.size(); i++) {
            Waypoint e = expected.get(i);
            Waypoint a = actual.get(i);
            assertEquals(e.x(), a.x(), "x@" + i);
            assertEquals(e.y(), a.y(), "y@" + i);
            assertEquals(e.z(), a.z(), "z@" + i);
            assertEquals(e.name(), a.name(), "name@" + i);
            assertEquals(e.flags(), a.flags(), "flags@" + i);
            assertEquals(e.customRadius(), a.customRadius(), 1e-6, "customRadius@" + i);
            if (!looseColor || e.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
                assertEquals(e.color(), a.color(), "color@" + i);
            }
        }
    }
}
