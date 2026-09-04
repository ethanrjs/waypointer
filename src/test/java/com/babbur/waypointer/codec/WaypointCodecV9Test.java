package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointCodecV9Test {

    @Test
    void shipped_dictionary_matches_the_train_only_asset() throws Exception {
        assertEquals(V9CodecDictionary.EXPECTED_BYTES, V9CodecDictionary.BYTES.length);
        assertEquals(V9CodecDictionary.EXPECTED_SHA256,
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(V9CodecDictionary.BYTES)));
    }

    @Test
    void compact_full_route_round_trips_names_colors_and_order() {
        WaypointGroup route = compactRoute(64);

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route));
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(9, debug.version());
        assertEquals(WaypointCodec.V9_CONTENT_KIND_COMPACT_ROUTE,
                WaypointCodec.v9ContentKind(debug.headerByte()));
        assertEquals(-1, debug.groups().get(0).groupFlagsByte());
        assertEquals("COMPACT_V9_RANGE", debug.groups().get(0).coordMode());
        assertEquals(-1, debug.groups().get(0).coordModeOrdinal());
        assertEquals(-1, debug.groups().get(0).coordBlockBytes());
        assertTrue(debug.groups().get(0).bodyBlockBytes() > 0);
        assertEquals(route.name(), decoded.name());
        assertEquals(route.zoneId(), decoded.zoneId());
        assertEquals(route.size(), decoded.size());
        for (int index = 0; index < route.size(); index++) {
            assertEquals(route.get(index).x(), decoded.get(index).x(), "x@" + index);
            assertEquals(route.get(index).y(), decoded.get(index).y(), "y@" + index);
            assertEquals(route.get(index).z(), decoded.get(index).z(), "z@" + index);
            assertEquals(route.get(index).name(), decoded.get(index).name(), "name@" + index);
            assertEquals(route.get(index).color(), decoded.get(index).color(), "color@" + index);
        }
    }

    @Test
    void default_api_preserves_supported_rich_metadata() {
        WaypointGroup route = WaypointGroup.create("rich", "dungeon_f7");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setDefaultRadius(7.5);
        route.add(new Waypoint(4, 70, -8, "named", 0x123456,
                Waypoint.FLAG_DEPTH_CHECKED | Waypoint.FLAG_DISABLED, 4.5));

        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encodeV9ForTest(List.of(route))).get(0);

        assertEquals("named", decoded.get(0).name());
        assertEquals(0x123456, decoded.get(0).color());
        assertEquals(4.5, decoded.get(0).customRadius());
        assertTrue(decoded.get(0).hasFlag(Waypoint.FLAG_DEPTH_CHECKED));
        assertTrue(decoded.get(0).hasFlag(Waypoint.FLAG_DISABLED));
        assertEquals(7.5, decoded.defaultRadius());
    }

    @Test
    void label_only_options_builder_remains_full_fidelity() {
        WaypointGroup route = WaypointGroup.create("builder", "dungeon_f7");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setDefaultRadius(7.25);
        route.add(new Waypoint(4, 70, -8, "named", 0x123456,
                Waypoint.FLAG_DEPTH_CHECKED, 4.54));
        WaypointCodec.Options options = WaypointCodec.Options.builder()
                .label("Label only")
                .build();

        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(
                WaypointCodec.encodeV9ForTest(List.of(route), options));
        WaypointGroup result = decoded.groups().get(0);

        assertTrue(options.includeNames);
        assertTrue(options.includeColors);
        assertTrue(options.includeRadii);
        assertTrue(options.includeWaypointFlags);
        assertTrue(options.includeGroupMeta);
        assertEquals("Label only", decoded.label());
        assertEquals(route.defaultRadius(), result.defaultRadius());
        assertEquals(route.get(0).name(), result.get(0).name());
        assertEquals(route.get(0).color(), result.get(0).color());
        assertEquals(route.get(0).customRadius(), result.get(0).customRadius());
        assertEquals(route.get(0).flags(), result.get(0).flags());
    }

    @Test
    void default_api_preserves_exact_radii_full_flags_and_persistent_group_metadata() {
        WaypointGroup route = WaypointGroup.create("persistent", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setDefaultRadius(3.0005);
        route.setSkipAheadEnabled(false);
        route.setStaticColor(0x123456);
        route.setGradientStartColor(0x010203);
        route.setGradientEndColor(0xA0B0C0);
        route.add(new Waypoint(4, 70, -8, "long-" + "n".repeat(80), 0x123456,
                Integer.MIN_VALUE | Waypoint.FLAG_DEPTH_CHECKED, 4.54));
        route.setGradientMode(WaypointGroup.GradientMode.STATIC);

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route));
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE,
                WaypointCodec.v9ContentKind(debug.headerByte()));
        assertEquals(route.defaultRadius(), debug.groups().get(0).defaultRadius());
        assertEquals(route.get(0).customRadius(),
                debug.groups().get(0).waypoints().get(0).customRadius());
        assertEquals(route.defaultRadius(), decoded.defaultRadius());
        assertEquals(route.skipAheadEnabled(), decoded.skipAheadEnabled());
        assertEquals(route.gradientMode(), decoded.gradientMode());
        assertEquals(route.staticColor(), decoded.staticColor());
        assertEquals(route.gradientStartColor(), decoded.gradientStartColor());
        assertEquals(route.gradientEndColor(), decoded.gradientEndColor());
        assertEquals(route.get(0).name(), decoded.get(0).name());
        assertEquals(route.get(0).flags(), decoded.get(0).flags());
        assertEquals(route.get(0).customRadius(), decoded.get(0).customRadius());
    }

    @Test
    void encode_rejects_routes_above_the_decode_waypoint_limit() {
        WaypointGroup route = WaypointGroup.create("too many", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index <= WaypointImporter.MAX_WAYPOINTS_PER_GROUP; index++) {
            route.add(Waypoint.at(0, 64, 0));
        }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.encodeV9ForTest(List.of(route)));

        assertTrue(error.getMessage().contains("exceeds waypoint limit"));
    }

    @Test
    void encode_rejects_malformed_unicode_in_names_and_zone_ids() {
        WaypointGroup malformedName = WaypointGroup.create("safe", "mining_3");
        malformedName.add(new Waypoint(0, 64, 0, "bad\uD800", Waypoint.DEFAULT_COLOR, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.encodeV9ForTest(List.of(malformedName)));

        WaypointGroup malformedZone = WaypointGroup.create("safe", "bad\uD800");
        malformedZone.add(Waypoint.at(0, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.encodeV9ForTest(List.of(malformedZone)));
    }

    @Test
    void coordinate_only_with_nondefault_skip_metadata_forces_general_fallback() {
        WaypointGroup route = compactRoute(64);
        route.setSkipAheadEnabled(false);

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), WaypointCodec.Options.NO_NAMES);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        assertFalse(decoded.skipAheadEnabled());
        assertEquals(WaypointGroup.GradientMode.MANUAL, decoded.gradientMode());
    }

    @Test
    void omitted_group_metadata_does_not_recolor_explicit_waypoint_colors() {
        WaypointGroup route = WaypointGroup.create("colors", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(new Waypoint(0, 64, 0, "red", 0xFF0000, 0, 0.0));
        route.add(new Waypoint(1, 64, 1, "green", 0x00FF00, 0, 0.0));
        WaypointCodec.Options options = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeGroupMeta(false)
                .build();

        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encodeV9ForTest(List.of(route), options)).get(0);

        assertEquals(WaypointGroup.GradientMode.MANUAL, decoded.gradientMode());
        assertEquals(0xFF0000, decoded.get(0).color());
        assertEquals(0x00FF00, decoded.get(1).color());
    }

    @Test
    void color_export_preserves_the_lock_needed_by_auto_gradient_semantics() {
        WaypointGroup route = WaypointGroup.create("locked color", "mining_3");
        route.add(new Waypoint(0, 64, 0, "locked", 0xFF0000,
                Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_DEPTH_CHECKED, 0.0));
        route.add(Waypoint.at(1, 64, 1));
        WaypointCodec.Options options = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeWaypointFlags(false)
                .build();

        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encodeV9ForTest(List.of(route), options)).get(0);

        assertEquals(WaypointGroup.GradientMode.AUTO, decoded.gradientMode());
        assertEquals(0xFF0000, decoded.get(0).color());
        assertTrue(decoded.get(0).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        assertFalse(decoded.get(0).hasFlag(Waypoint.FLAG_DEPTH_CHECKED));
    }

    @Test
    void noncanonical_and_extreme_numeric_names_fall_back_to_palette_losslessly() {
        WaypointGroup route = WaypointGroup.create("numeric edge names", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        List<String> names = List.of(
                "01", "-0", Long.toString(Long.MAX_VALUE), Long.toString(Long.MIN_VALUE), "1", "2");
        for (int index = 0; index < names.size(); index++) {
            route.add(new Waypoint(index, 64, -index, names.get(index), 0x44AA66, 0, 0.0));
        }

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route));
        assertEquals(WaypointCodec.V9_CONTENT_KIND_COMPACT_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        for (int index = 0; index < names.size(); index++) {
            assertEquals(names.get(index), decoded.get(index).name());
        }
    }

    @Test
    void empty_and_single_point_routes_round_trip() {
        WaypointGroup empty = WaypointGroup.create("empty", "unknown");
        empty.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        WaypointGroup one = WaypointGroup.create("one", "mining_3");
        one.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        one.add(new Waypoint(-100_000_000, 255, 100_000_000,
                "solo", 0xFEDCBA, 0, 0.0));

        WaypointGroup emptyDecoded = WaypointCodec.decode(
                WaypointCodec.encodeV9ForTest(List.of(empty))).get(0);
        WaypointGroup oneDecoded = WaypointCodec.decode(
                WaypointCodec.encodeV9ForTest(List.of(one))).get(0);

        assertTrue(emptyDecoded.isEmpty());
        assertEquals("empty", emptyDecoded.name());
        assertEquals(1, oneDecoded.size());
        assertEquals(-100_000_000, oneDecoded.get(0).x());
        assertEquals(100_000_000, oneDecoded.get(0).z());
        assertEquals("solo", oneDecoded.get(0).name());
        assertEquals(0xFEDCBA, oneDecoded.get(0).color());
    }

    @Test
    void explicit_coordinate_only_uses_trained_kind_two() {
        WaypointGroup route = kindTwoRoute();

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), WaypointCodec.Options.NO_NAMES);
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE,
                WaypointCodec.v9ContentKind(debug.headerByte()));
        assertEquals("", decoded.name());
        assertEquals(route.zoneId(), decoded.zoneId());
        for (int index = 0; index < route.size(); index++) {
            assertEquals(route.get(index).x(), decoded.get(index).x(), "x@" + index);
            assertEquals(route.get(index).y(), decoded.get(index).y(), "y@" + index);
            assertEquals(route.get(index).z(), decoded.get(index).z(), "z@" + index);
            assertEquals("", decoded.get(index).name());
            assertEquals(Waypoint.DEFAULT_COLOR, decoded.get(index).color());
        }
    }

    @Test
    void extreme_coordinates_with_unrepresented_precision_use_distinct_anonymous_fallback() {
        WaypointGroup route = WaypointGroup.create("extreme", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(Waypoint.at(-134_000_000, 64, 0));
        route.add(Waypoint.at(134_000_000, 64, 0)
                .withPreciseSixteenths(134_000_000 * Waypoint.PRECISE_SCALE + 1,
                        64 * Waypoint.PRECISE_SCALE + 8, 8));

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), WaypointCodec.Options.NO_NAMES);
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META,
                WaypointCodec.v9ContentKind(debug.headerByte()));
        assertEquals(-134_000_000, decoded.get(0).x());
        assertEquals(134_000_000, decoded.get(1).x());
    }

    @Test
    void auto_gradient_coordinate_export_uses_metadata_fallback() {
        WaypointGroup route = WaypointGroup.create("auto", "mining_3");
        route.add(Waypoint.at(0, 64, 0));
        route.add(Waypoint.at(1, 64, 1));

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), WaypointCodec.Options.NO_NAMES);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
    }

    @Test
    void custom_group_radius_coordinate_export_uses_kind_five_and_preserves_radius() {
        WaypointGroup route = WaypointGroup.create("radius", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setDefaultRadius(6.5);
        route.add(Waypoint.at(0, 64, 0));
        route.add(Waypoint.at(2, 65, 3));

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), WaypointCodec.Options.NO_NAMES);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        assertEquals(6.5, decoded.defaultRadius());
        assertEquals("", decoded.name());
    }

    @Test
    void anonymous_kind_five_rejects_the_reserved_persistent_metadata_bit() throws Exception {
        WaypointGroup route = WaypointGroup.create("radius", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setDefaultRadius(6.5);
        route.add(Waypoint.at(0, 64, 0));
        route.add(Waypoint.at(2, 65, 3));

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), WaypointCodec.Options.NO_NAMES);
        byte[] body = currentBody(encoded);
        assertEquals(WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META,
                WaypointCodec.v9ContentKind(body[0] & 0xFF));

        int groupFlagsOffset = 1;
        while ((body[groupFlagsOffset++] & 0x80) != 0) {
            // Skip the anonymous zone-reference varint.
        }
        body[groupFlagsOffset] |= (byte) 0x80;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(body)));
        assertTrue(error.getMessage().contains("kind 5 group metadata extension bit is reserved"));
    }

    @Test
    void multi_group_maximum_model_delta_uses_general_kind_zero_losslessly() {
        WaypointGroup extreme = WaypointGroup.create("extreme", "mining_3");
        extreme.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        extreme.add(Waypoint.at(-134_217_728, 64, 0));
        extreme.add(Waypoint.at(134_217_727, 64, 0));
        WaypointGroup second = WaypointGroup.create("second", "foraging_2");
        second.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        second.add(Waypoint.at(1, 2, 3));

        String encoded = WaypointCodec.encodeV9ForTest(
                List.of(extreme, second), WaypointCodec.Options.NO_NAMES);
        List<WaypointGroup> decoded = WaypointCodec.decode(encoded);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        assertEquals(-134_217_728, decoded.get(0).get(0).x());
        assertEquals(134_217_727, decoded.get(0).get(1).x());
        assertEquals(2, decoded.size());
    }

    @Test
    void single_group_maximum_model_delta_with_full_flags_uses_kind_zero_losslessly() {
        WaypointGroup route = WaypointGroup.create("full extreme", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(Waypoint.at(-134_217_728, 64, 0)
                .withFlags(Waypoint.FLAG_DEPTH_CHECKED));
        route.add(Waypoint.at(134_217_727, 64, 0));

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route));
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        assertEquals(-134_217_728, decoded.get(0).x());
        assertEquals(134_217_727, decoded.get(1).x());
        assertTrue(decoded.get(0).hasFlag(Waypoint.FLAG_DEPTH_CHECKED));
    }

    @Test
    void compact_route_preserves_duplicate_coordinates_and_emoji_metadata() {
        WaypointGroup route = WaypointGroup.create("Route 🚇", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < 32; index++) {
            route.add(new Waypoint(42, 70, -9,
                    index % 2 == 0 ? "入口✨" : "出口🧭",
                    index % 2 == 0 ? 0xAABBCC : 0x102030,
                    0,
                    0.0));
        }
        WaypointCodec.Options options = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .label("Emoji 🚇 route")
                .build();

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route), options);
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(encoded);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_COMPACT_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        assertEquals("Emoji 🚇 route", decoded.label());
        assertEquals("Emoji 🚇 route", WaypointCodec.peekLabel(encoded).orElseThrow());
        assertEquals(route.name(), decoded.groups().get(0).name());
        for (int index = 0; index < route.size(); index++) {
            assertEquals(42, decoded.groups().get(0).get(index).x());
            assertEquals(route.get(index).name(), decoded.groups().get(0).get(index).name());
            assertEquals(route.get(index).color(), decoded.groups().get(0).get(index).color());
        }
    }

    @Test
    void subwaypoint_style_and_precision_force_general_full_fidelity_kind() {
        WaypointGroup route = WaypointGroup.create("subway", "dungeon_f7");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(Waypoint.at(0, 70, 0));
        Waypoint child = Waypoint.at(1, 70, 0)
                .withFlags(Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_FILLED_SUBWAYPOINT)
                .withPreciseSixteenths(19, 1132, 11);
        route.add(child);

        String encoded = WaypointCodec.encodeV9ForTest(List.of(route));
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        assertEquals(child.flags(), decoded.get(1).flags());
        assertEquals(child.preciseX(), decoded.get(1).preciseX());
        assertEquals(child.preciseY(), decoded.get(1).preciseY());
        assertEquals(child.preciseZ(), decoded.get(1).preciseZ());
    }

    @Test
    void unknown_v9_content_kind_is_rejected_before_body_parse() throws Exception {
        String encoded = WaypointCodec.encodeV9ForTest(List.of(compactRoute(32)));
        byte[] body = currentBody(encoded);
        body[0] = (byte) ((body[0] & 0x8F) | (7 << 4));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(body)));

        assertTrue(error.getMessage().contains("unsupported v9 content kind 7"));
        assertFalse(WaypointCodec.peekLabel(encodeBody(body)).isPresent());
    }

    @Test
    void compact_decoder_rejects_coordinates_the_waypoint_model_cannot_represent() throws Exception {
        byte[] body = {
                (byte) ((WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE << 4) | 9),
                2, // trained zone token: mining_3
                1, // one waypoint
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01,
                0, 0, // y=0, z=0
                0, 0, // three zero delta widths
                0 // empty range payload
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(body)));

        assertTrue(error.getMessage().contains("outside representable block range"));
    }

    @Test
    void general_decoder_rejects_coordinate_delta_overflow_before_waypoint_construction() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(raw);
        output.writeByte(WaypointCodec.WIRE_VERSION);
        WaypointCodec.writeVarint(output, 1); // pool count
        WaypointCodec.writeVarint(output, 0); // pool[0] = ""
        WaypointCodec.writeVarint(output, 1); // group count
        WaypointCodec.writeVarint(output, 0); // group name
        WaypointCodec.writeVarint(output, 0); // custom zone = pool[0]
        output.writeByte(0b0000_0101); // bodyless + sequence + VECTOR
        WaypointCodec.writeVarint(output, 2);
        WaypointCodec.writeZigzag(output, 134_217_727);
        WaypointCodec.writeZigzag(output, 0);
        WaypointCodec.writeZigzag(output, 0);
        WaypointCodec.writeZigzag(output, 1); // next x would be 134,217,728
        WaypointCodec.writeZigzag(output, 0);
        WaypointCodec.writeZigzag(output, 0);
        output.flush();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(raw.toByteArray())));

        assertTrue(error.getMessage().contains("outside representable block range"));
    }

    @Test
    void general_decoder_rejects_nonzero_fixed_coordinate_padding() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(raw);
        output.writeByte(WaypointCodec.WIRE_VERSION);
        WaypointCodec.writeVarint(output, 1); // pool count
        WaypointCodec.writeVarint(output, 0); // pool[0] = ""
        WaypointCodec.writeVarint(output, 1); // group count
        WaypointCodec.writeVarint(output, 0); // group name
        WaypointCodec.writeVarint(output, 0); // custom zone = pool[0]
        output.writeByte(0b0010_0101); // bodyless + sequence + FIXED_COMPACT
        WaypointCodec.writeVarint(output, 1);
        output.write(new byte[] { 0, 2, 0, 0, 1 }); // (0,0,0), then one nonzero padding bit
        output.flush();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(raw.toByteArray())));

        assertTrue(error.getMessage().contains("padding bits must be zero"));
    }

    @Test
    void general_decoder_rejects_reserved_coordinate_width_bit() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(raw);
        output.writeByte(WaypointCodec.WIRE_VERSION);
        WaypointCodec.writeVarint(output, 1); // pool count
        WaypointCodec.writeVarint(output, 0); // pool[0] = ""
        WaypointCodec.writeVarint(output, 1); // group count
        WaypointCodec.writeVarint(output, 0); // group name
        WaypointCodec.writeVarint(output, 0); // custom zone = pool[0]
        output.writeByte(0b0011_0101); // bodyless + sequence + FIT_COMPACT
        WaypointCodec.writeVarint(output, 0); // empty group
        WaypointCodec.writeZigzag(output, 0);
        WaypointCodec.writeZigzag(output, 0);
        WaypointCodec.writeZigzag(output, 0);
        output.writeShort(0x8000);
        output.flush();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(raw.toByteArray())));

        assertTrue(error.getMessage().contains("coordinate width reserved bit is set"));
    }

    @Test
    void fixed_general_vector_with_unrepresentable_absolute_coordinate_is_rejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WP:h0o)Yj+#j4r[uzR&6RgApr#!!"));

        assertTrue(error.getMessage().contains("outside representable block range"));
    }

    @Test
    void bounded_label_preview_sanitizes_untrusted_v9_label_bytes() throws Exception {
        String hostile = "\u00A7c\n Bad ";
        byte[] label = hostile.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(raw);
        output.writeByte(0x80 | WaypointCodec.WIRE_VERSION); // label + general kind
        WaypointCodec.writeVarint(output, label.length);
        output.write(label);
        WaypointCodec.writeVarint(output, 1); // pool count
        WaypointCodec.writeVarint(output, 0); // pool[0] = ""
        WaypointCodec.writeVarint(output, 0); // group count
        output.flush();
        String encoded = encodeBody(raw.toByteArray());

        assertEquals("c Bad", WaypointCodec.peekLabel(encoded).orElseThrow());
        assertEquals("c Bad", WaypointCodec.decodeFull(encoded).label());
    }

    @Test
    void compact_body_rejects_trailing_and_truncated_range_bytes() throws Exception {
        String encoded = WaypointCodec.encodeV9ForTest(List.of(compactRoute(64)));
        assertEquals(WaypointCodec.V9_CONTENT_KIND_COMPACT_ROUTE,
                WaypointCodec.v9ContentKind(WaypointCodec.debugDecode(encoded).headerByte()));
        byte[] body = currentBody(encoded);

        byte[] withTail = Arrays.copyOf(body, body.length + 1);
        withTail[body.length] = 42;
        IllegalArgumentException trailing = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(withTail)));
        assertTrue(trailing.getMessage().contains("trailing binary body bytes"));

        byte[] truncated = Arrays.copyOf(body, body.length - 1);
        IllegalArgumentException shortPayload = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeBody(truncated)));
        assertTrue(shortPayload.getMessage().contains("truncated compact coordinate payload")
                || shortPayload.getMessage().contains("truncated or non-canonical"));
    }

    @Test
    void catalogEncoderKeepsFullV9Meaning() {
        WaypointGroup route = WaypointGroup.create("Catalog v9", "hub");
        route.setSkipAheadEnabled(false);
        route.setStaticColor(0x123456);
        route.setGradientStartColor(0x010203);
        route.setGradientEndColor(0xA0B0C0);
        route.add(new Waypoint(0, 64, 0, "Exact", 0xABCDEF,
                Integer.MIN_VALUE | Waypoint.FLAG_DISABLED, 1.25));

        String payload = WaypointCodec.encodeCatalog(List.of(route));

        assertEquals(9, WaypointCodec.debugDecode(payload).version());
        WaypointGroup decoded = WaypointCodec.decode(payload).getFirst();
        assertFalse(decoded.skipAheadEnabled());
        assertEquals(0x123456, decoded.staticColor());
        assertEquals(0x010203, decoded.gradientStartColor());
        assertEquals(0xA0B0C0, decoded.gradientEndColor());
        assertEquals(Double.doubleToRawLongBits(1.25),
                Double.doubleToRawLongBits(decoded.get(0).customRadius()));
        assertEquals(Integer.MIN_VALUE | Waypoint.FLAG_DISABLED, decoded.get(0).flags());
    }

    private static WaypointGroup compactRoute(int count) {
        WaypointGroup route = WaypointGroup.create("Route 1", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < count; index++) {
            route.add(new Waypoint(
                    -120 + index * 3,
                    64 + index % 5,
                    300 - index * 2,
                    Integer.toString(index + 1),
                    index % 7 == 0 ? 0x55CCEE : 0x33AA55,
                    0,
                    0.0));
        }
        return route;
    }

    private static WaypointGroup kindTwoRoute() {
        WaypointGroup route = WaypointGroup.create("", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Random random = new Random(0);
        int x = 0;
        int y = 64;
        int z = 0;
        for (int index = 0; index < 32; index++) {
            x += random.nextInt(41) - 20;
            y += random.nextInt(5) - 2;
            z += random.nextInt(41) - 20;
            route.add(Waypoint.at(x, y, z));
        }
        return route;
    }

    private static byte[] currentBody(String encoded) throws Exception {
        String text = encoded.substring(WaypointCodec.MAGIC.length());
        byte[] compressed = AsciiStreamCodec.decode(WaypointCodec.unescapeHypixelEmotes(text));
        byte[] framed = inflate(compressed);
        return Arrays.copyOf(framed, framed.length - Integer.BYTES);
    }

    private static String encodeBody(byte[] body) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(body);
        long value = crc.getValue();
        byte[] framed = Arrays.copyOf(body, body.length + Integer.BYTES);
        framed[body.length] = (byte) (value >>> 24);
        framed[body.length + 1] = (byte) (value >>> 16);
        framed[body.length + 2] = (byte) (value >>> 8);
        framed[body.length + 3] = (byte) value;

        byte[] compressed = deflate(framed);
        return WaypointCodec.MAGIC
                + WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encode(compressed));
    }

    private static byte[] deflate(byte[] raw) throws Exception {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setDictionary(V9CodecDictionary.BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(raw);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws Exception {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            inflater.setDictionary(V9CodecDictionary.BYTES);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) throw new IllegalArgumentException("truncated stream");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
