package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link WaypointCodec#debugDecode(String)}. The debug API is the only
 * production surface that exposes wire-level bytes (export flags, group flags,
 * coord mode, per-waypoint flag bytes), so a regression here would silently
 * misreport what the decoder is actually doing in the /wp debug inspector.
 *
 * Tests here are paired: each assertion checks both that the decoded domain
 * object matches the source (parity with {@code decode()}) and that the matching
 * raw wire field reflects what the encoder put on the wire.
 */
class WaypointCodecDebugTest {

    @Test
    void debug_groups_match_normal_decode() {
        WaypointGroup g = sampleGroup();
        String encoded = WaypointCodec.encode(List.of(g));

        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        List<WaypointGroup> plain = WaypointCodec.decode(encoded);

        assertEquals(plain.size(), debug.decodedGroups().size(),
                "debug must return same number of groups as decode");
        assertEquals(plain.get(0).name(), debug.decodedGroups().get(0).name());
        assertEquals(plain.get(0).size(), debug.decodedGroups().get(0).size());
    }

    @Test
    void input_and_payload_char_counts_are_consistent() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup()));
        DecodeDebug d = WaypointCodec.debugDecode(encoded);

        assertEquals(encoded.length(), d.inputChars());
        assertEquals(WaypointCodec.MAGIC, d.magic());
        assertEquals(encoded.length() - WaypointCodec.MAGIC.length(), d.payloadChars());
        assertTrue(d.rawBodyBytes() > 0, "raw body must be non-empty");
        assertTrue(d.compressedBytes() > 0, "compressed must be non-empty");
    }

    @Test
    void header_byte_sets_version_and_names_flag_when_names_included() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup()), WaypointCodec.Options.WITH_NAMES);
        DecodeDebug d = WaypointCodec.debugDecode(encoded);

        // Low nibble must carry the current wire version; bit 4 carries the
        // names flag; with no label we expect bit 5 also clear; bits 6-7 are
        // reserved and must remain zero so a future schema bump can claim them
        // without ambiguity.
        assertEquals(WaypointCodec.WIRE_VERSION, d.version(),
                "encoder must stamp the current wire version");
        assertEquals(0, d.headerByte() & 0b1100_0000,
                "reserved high bits must stay 0");
        assertTrue(d.includesNames());
        assertFalse(d.hasLabel(),     "no label set on this export -- bit 5 must be 0");
        assertFalse(d.reservedBit6(), "reserved bit 6 must never be set by the current encoder");
        assertFalse(d.reservedBit7(), "reserved bit 7 must never be set by the current encoder");
    }

    @Test
    void header_byte_carries_only_version_when_names_excluded() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup()), WaypointCodec.Options.NO_NAMES);
        DecodeDebug d = WaypointCodec.debugDecode(encoded);

        // NO_NAMES with no label: only the version nibble should be set, everything else zero.
        assertEquals(WaypointCodec.WIRE_VERSION, d.version());
        assertEquals(0, d.headerByte() & 0b1111_0000,
                "NO_NAMES + no label must clear every flag bit in the high nibble");
        assertFalse(d.includesNames());
        assertFalse(d.hasLabel());
        assertFalse(d.reservedBit6());
        assertFalse(d.reservedBit7());
    }

    @Test
    void label_round_trips_through_header_bit_and_decoded_field() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup()),
                WaypointCodec.Options.builder().label("Boss path -- F7").build());
        DecodeDebug d = WaypointCodec.debugDecode(encoded);

        assertTrue(d.hasLabel(), "label-bearing export must set header bit 5");
        assertEquals("Boss path -- F7", d.label(), "decoded label must match input verbatim");
        assertEquals("Boss path -- F7", WaypointCodec.peekLabel(encoded).orElse(null),
                "peekLabel must return the same string without a full decode");
    }

    @Test
    void empty_label_clears_header_bit() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup()),
                WaypointCodec.Options.builder().label("").build());
        DecodeDebug d = WaypointCodec.debugDecode(encoded);
        assertFalse(d.hasLabel(), "empty label must not flip bit 5");
        assertEquals("", d.label());
        assertTrue(WaypointCodec.peekLabel(encoded).isEmpty());
    }

    @Test
    void group_flags_byte_decodes_into_labeled_fields() {
        WaypointGroup g = WaypointGroup.create("custom", "zone");
        g.add(Waypoint.at(10, 70, 20));
        g.setDefaultRadius(5.5);                                 // customRadius
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);          // loadSequence
        g.setGradientMode(WaypointGroup.GradientMode.AUTO);      // gradientAuto
        g.setEnabled(true);                                      // enabled

        String encoded = WaypointCodec.encode(List.of(g));
        DecodeDebug.GroupDebug gd = WaypointCodec.debugDecode(encoded).groups().get(0);

        assertTrue(gd.enabled());
        assertTrue(gd.gradientAuto());
        assertTrue(gd.loadSequence());
        assertTrue(gd.customRadius());
        assertEquals(5.5, gd.defaultRadius(), 0.001);
        int mode = (gd.groupFlagsByte() >>> 4) & 0b11;
        assertEquals(gd.coordModeOrdinal(), mode,
                "group flags byte's coord-mode nibble must match reported ordinal");
    }

    @Test
    void coord_mode_debug_round_trips_in_bounds_yoyo_routes() {
        // Yo-yo pattern inside the FIXED_COMPACT bit range. The coord-mode
        // contest ranks candidates by post-DEFLATE size, and on a 2-value
        // alternating pattern all three bit-packed candidates deflate to
        // essentially the same size as ABSOLUTE_VARINT (the dictionary + RLE
        // kills the difference). So AUTO may legitimately pick any of them
        // -- what the debug layer must guarantee is that whichever it picks
        // round-trips the exact coord stream and surfaces a valid coord-mode
        // name.
        WaypointGroup g = WaypointGroup.create("yoyo", "zone");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL); // keep colors clean for flag tests elsewhere
        for (int i = 0; i < 20; i++) {
            int sign = (i % 2 == 0) ? 1 : -1;
            g.add(new Waypoint(sign * 1500, 70, sign * 1800, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        }
        String encoded = WaypointCodec.encode(List.of(g));
        DecodeDebug.GroupDebug gd = WaypointCodec.debugDecode(encoded).groups().get(0);

        assertTrue(gd.coordModeOrdinal() >= 0 && gd.coordModeOrdinal() <= 3,
                "coord-mode ordinal must be a valid wire value, got " + gd.coordModeOrdinal());
        assertEquals(20, gd.pointCount(), "debug view must report the correct point count");
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void coord_mode_falls_back_when_out_of_fixed_bounds() {
        WaypointGroup g = WaypointGroup.create("far", "zone");
        g.add(Waypoint.at(0, 70, 0));
        g.add(Waypoint.at(1_000_000, 70, 1_000_000));

        DecodeDebug.GroupDebug gd = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(g)))
                .groups().get(0);

        assertNotEquals("FIXED_COMPACT", gd.coordMode(),
                "FIXED_COMPACT must be ineligible when x,z exceed [-2048,+2047]");
    }

    @Test
    void coord_and_body_block_bytes_are_measured_separately() {
        WaypointGroup g = WaypointGroup.create("blocks", "zone");
        for (int i = 0; i < 12; i++) {
            g.add(new Waypoint(i * 3, 70, i * 2, "pt" + i,
                    Waypoint.DEFAULT_COLOR, 0, 0.0));
        }
        DecodeDebug.GroupDebug gd = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(g)))
                .groups().get(0);

        assertTrue(gd.coordBlockBytes() > 0, "coord block must be non-empty");
        assertTrue(gd.bodyBlockBytes()  > 0, "body block must be non-empty");
        // Sum can't exceed the whole raw body.
        DecodeDebug full = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(g)));
        assertTrue(gd.coordBlockBytes() + gd.bodyBlockBytes() < full.rawBodyBytes());
    }

    @Test
    void waypoint_flag_byte_reflects_per_point_fields() {
        WaypointGroup g = WaypointGroup.create("flags", "zone");
        // MANUAL gradient keeps the DEFAULT_COLOR we pass in; the default AUTO mode
        // would recolor every waypoint on add, flipping the HAS_COLOR bit on.
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(Waypoint.at(0, 70, 0)); // bare
        g.add(new Waypoint(10, 70, 10, "named", Waypoint.DEFAULT_COLOR, 0, 0.0)); // name only
        g.add(new Waypoint(20, 70, 20, "",      0xFF5577, 0, 0.0));               // color only
        g.add(new Waypoint(30, 70, 30, "",      Waypoint.DEFAULT_COLOR, 0, 7.5)); // radius only
        g.add(new Waypoint(40, 70, 40, "",      Waypoint.DEFAULT_COLOR,
                Waypoint.FLAG_HIDE_BEACON, 0.0));                                 // extended flags only

        List<DecodeDebug.WaypointDebug> wps = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(g)))
                .groups().get(0).waypoints();

        assertEquals(0, wps.get(0).wpFlagsByte(), "bare waypoint has zero flag byte");

        assertTrue(wps.get(1).hasName());
        assertFalse(wps.get(1).hasColor());
        assertFalse(wps.get(1).hasRadius());
        assertFalse(wps.get(1).extended());

        assertFalse(wps.get(2).hasName());
        assertTrue(wps.get(2).hasColor());

        assertTrue(wps.get(3).hasRadius());
        assertEquals(7.5, wps.get(3).customRadius(), 0.001);

        assertTrue(wps.get(4).extended());
        assertEquals(Waypoint.FLAG_HIDE_BEACON, wps.get(4).extendedFlags());
    }

    @Test
    void string_pool_starts_with_empty_string() {
        DecodeDebug d = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(sampleGroup())));
        assertFalse(d.stringPool().isEmpty(), "pool should be non-empty");
        assertEquals("", d.stringPool().get(0), "pool slot 0 reserved for empty string");
    }

    @Test
    void decode_nanos_is_positive() {
        DecodeDebug d = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(sampleGroup())));
        assertTrue(d.decodeNanos() > 0, "decodeNanos must record real elapsed time, got " + d.decodeNanos());
    }

    @Test
    void multiple_groups_appear_in_wire_order() {
        WaypointGroup a = WaypointGroup.create("alpha", "z");  a.add(Waypoint.at(0, 70, 0));
        WaypointGroup b = WaypointGroup.create("bravo", "z");  b.add(Waypoint.at(1, 70, 1));
        WaypointGroup c = WaypointGroup.create("charlie", "z"); c.add(Waypoint.at(2, 70, 2));

        DecodeDebug d = WaypointCodec.debugDecode(WaypointCodec.encode(List.of(a, b, c)));
        assertEquals(3, d.groups().size());
        assertEquals("alpha",   d.groups().get(0).name());
        assertEquals("bravo",   d.groups().get(1).name());
        assertEquals("charlie", d.groups().get(2).name());
        assertEquals(0, d.groups().get(0).index());
        assertEquals(2, d.groups().get(2).index());
    }

    @Test
    void rejects_non_codec_input() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.debugDecode("hello world"));
        assertTrue(e.getMessage().contains(WaypointCodec.MAGIC),
                "error should mention the expected magic prefix");
    }

    @Test
    void rejects_null_input() {
        assertThrows(IllegalArgumentException.class, () -> WaypointCodec.debugDecode(null));
    }

    @Test
    void rejects_truncated_payload() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup()));
        // Lop off half the body; the trimmed string is still magic-prefixed but cannot inflate.
        String truncated = encoded.substring(0, WaypointCodec.MAGIC.length()
                + (encoded.length() - WaypointCodec.MAGIC.length()) / 2);
        assertThrows(IllegalArgumentException.class, () -> WaypointCodec.debugDecode(truncated));
    }

    private static WaypointGroup sampleGroup() {
        WaypointGroup g = WaypointGroup.create("run", "dungeon_f7");
        g.add(new Waypoint(10,  70, -20, "start", 0x40E0D0, 0, 0.0));
        g.add(new Waypoint(15,  71, -18, "",      0xFF5577,
                Waypoint.FLAG_LOCKED_COLOR, 5.0));
        g.add(new Waypoint(30,  68,   5, "boss",  0xFFDD00,
                Waypoint.FLAG_HIDE_BEACON,  0.0));
        return g;
    }
}
