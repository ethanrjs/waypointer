package com.babbur.waypointer.codec;

import com.babbur.waypointer.chat.CodecScanner;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointCodecV10BareRouteTest {

    @Test
    void direct_mode_matches_locked_contextual91_goldens() {
        // Header-mode envelope: no selector character, CRC-16 trailer.
        assertEquals(String.join("\n",
                        "WP:M!8D%",
                        "WP:<$!!#~%S#",
                        "WP:v)i\"&\"g42F0#(#"),
                String.join("\n",
                        encodeBare(List.of()),
                        encodeBare(List.of(point(0, 0, 0))),
                        encodeBare(List.of(
                                point(10, 64, -20),
                                point(10, 64, -20),
                                point(11, 63, -18)))));
    }

    @Test
    void explicit_bare_projection_strips_every_field_but_ordered_block_coordinates() {
        WaypointGroup source = WaypointGroup.create("named route", "mining_3");
        source.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        source.setLoadMode(WaypointGroup.LoadMode.STATIC);
        source.setDefaultRadius(8.5);
        source.setSkipAheadEnabled(false);
        source.add(new Waypoint(4, 70, -8, "start", 0x123456,
                Waypoint.FLAG_DEPTH_CHECKED, 4.5));
        source.add(new Waypoint(0, 0, 0, "precise child", 0xABCDEF,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT,
                6.25, Waypoint.TEMP_TIME, 123_456L,
                11 * 16 + 1, 70 * 16 + 2, -20 * 16 + 3));

        String encoded = WaypointCodec.encode(
                List.of(source), WaypointCodec.Options.BARE_COORDINATES);
        V10Transport.CheckedFrame checked = assertDoesNotThrowProbe(encoded);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);

        assertEquals(0x2A, checked.header());
        assertEquals(2, checked.contentKind());
        assertEquals(10, debug.version());
        assertEquals(0x2A, debug.headerByte());
        assertTrue(debug.textEncoding().contains("V10"));
        assertEquals(checked.mode() == V10Transport.MODE_DIRECT
                        ? "V10_RICE" : "V10_DELTA_DEFLATE",
                debug.groups().getFirst().coordMode());
        assertTrue(WaypointCodec.peekLabel(encoded).isEmpty());
        assertTrue(WaypointCodec.isValidCodec(encoded));
        assertTrue(CodecScanner.scan("route: " + encoded).getFirst().valid());
        assertThrows(IllegalArgumentException.class, () -> WaypointCodec.decodeCanonicalV9(encoded));
        assertEquals("", decoded.name());
        assertEquals("unknown", decoded.zoneId());
        assertEquals(WaypointGroup.GradientMode.MANUAL, decoded.gradientMode());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, decoded.loadMode());
        assertEquals(Waypoint.DEFAULT_REACH_RADIUS, decoded.defaultRadius());
        assertTrue(decoded.skipAheadEnabled());
        assertEquals(2, decoded.size());
        assertPoint(decoded.get(0), 4, 70, -8);
        assertPoint(decoded.get(1), 11, 70, -20);
        for (Waypoint waypoint : decoded.waypoints()) {
            assertEquals("", waypoint.name());
            assertEquals(Waypoint.DEFAULT_COLOR, waypoint.color());
            assertEquals(0, waypoint.flags());
            assertEquals(0.0, waypoint.customRadius());
            assertEquals(Waypoint.TEMP_NONE, waypoint.tempMode());
            assertFalse(waypoint.hasCustomPrecisePosition());
        }

        // The ordinary/default writer uses the lossless V10 general body, so
        // explicit coordinate projection cannot weaken it.
        String rich = WaypointCodec.encode(List.of(source));
        assertEquals(V10GeneralRouteCodec.CONTENT_KIND,
                assertDoesNotThrowProbe(rich).contentKind());
        assertEquals(10, WaypointCodec.debugDecode(rich).version());
        WaypointGroup richDecoded = WaypointCodec.decode(rich).get(0);
        assertEquals("named route", richDecoded.name());
        assertEquals("mining_3", richDecoded.zoneId());
        assertEquals("precise child", richDecoded.get(1).name());
        assertEquals(0xABCDEF, richDecoded.get(1).color());
        assertTrue(richDecoded.get(1).isSubwaypoint());
        assertEquals(6.25, richDecoded.get(1).customRadius());
        assertEquals(source.get(1).preciseX(), richDecoded.get(1).preciseX());
        assertEquals(source.get(1).preciseY(), richDecoded.get(1).preciseY());
        assertEquals(source.get(1).preciseZ(), richDecoded.get(1).preciseZ());
    }

    @Test
    void explicit_bare_projection_ignores_unsafe_names_that_are_not_on_the_wire() {
        List<String> discardedNames = List.of(
                "ignored\u0000control",
                "x".repeat(WaypointCodec.MAX_ROUTE_DISPLAY_NAME_BYTES + 1),
                String.valueOf((char) 0xD800));

        for (String discardedName : discardedNames) {
            WaypointGroup source = WaypointGroup.create(discardedName, "mining_3");
            source.add(new Waypoint(7, 80, -11, discardedName,
                    Waypoint.DEFAULT_COLOR, 0, 0.0));

            String encoded = WaypointCodec.encode(
                    List.of(source), WaypointCodec.Options.BARE_COORDINATES);
            String inferredAllOff = WaypointCodec.encode(List.of(source),
                    WaypointCodec.Options.builder()
                            .includeNames(false)
                            .includeColors(false)
                            .includeRadii(false)
                            .includeWaypointFlags(false)
                            .includeGroupMeta(false)
                            .includeZone(false)
                            .build());
            WaypointGroup decoded = WaypointCodec.decode(encoded).getFirst();

            assertEquals(encoded, inferredAllOff);
            assertEquals(10, WaypointCodec.debugDecode(encoded).version());
            assertPoint(decoded.get(0), 7, 80, -11);
            assertEquals("", decoded.name());
            assertEquals("", decoded.get(0).name());
            assertThrows(IllegalArgumentException.class,
                    () -> WaypointCodec.encode(List.of(source),
                            WaypointCodec.Options.FULL_FIDELITY));
        }
    }

    @Test
    void deflate_mode_round_trips_semantically_without_recompression_validation() throws Exception {
        List<int[]> points = new ArrayList<>();
        for (int index = 0; index < 200; index++) points.add(point(index, 64, -index));
        String encoded = encodeBare(points);

        assertEquals(V10Transport.MODE_DEFLATE, V10Transport.decode(
                encoded.substring(WaypointCodec.MAGIC.length())).mode());
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        assertEquals(points.size(), decoded.size());
        for (int index = 0; index < points.size(); index++) {
            assertPoint(decoded.get(index), points.get(index)[0], points.get(index)[1], points.get(index)[2]);
        }

        V10Transport.Frame frame = V10Transport.decode(
                encoded.substring(WaypointCodec.MAGIC.length()));
        byte[] semantic = V10Transport.inflateAndVerify(frame.payload());
        byte[] alternateDeflate = externalCrcDeflatePayload(semantic, Deflater.BEST_SPEED);
        assertFalse(Arrays.equals(frame.payload(), alternateDeflate));
        String alternateCode = WaypointCodec.MAGIC + V10Transport.encode(
                V10Transport.MODE_DEFLATE, alternateDeflate);
        WaypointGroup alternateDecoded = WaypointCodec.decode(alternateCode).get(0);
        assertEquals(points.size(), alternateDecoded.size());
        for (int index = 0; index < points.size(); index++) {
            assertPoint(alternateDecoded.get(index),
                    points.get(index)[0], points.get(index)[1], points.get(index)[2]);
        }

        byte[] withTrailingCompressedByte = insertBeforeCrc(frame.payload(), (byte) 0);
        String malformed = V10Transport.encode(frame.mode(), withTrailingCompressedByte);
        IOException failure = assertThrows(IOException.class,
                () -> V10BareRouteCodec.decode(malformed));
        assertTrue(failure.getMessage().contains("trailing v10 compressed bytes"));
    }

    @Test
    void quotient_descriptor_wins_the_former_equal_text_tie_fixture() throws Exception {
        JsonArray coordinates;
        try (var stream = WaypointCodecV10BareRouteTest.class.getResourceAsStream(
                "/fixtures/v10-equal-text-tie-coordinates.json")) {
            if (stream == null) throw new AssertionError("missing equal-text tie fixture");
            coordinates = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonArray();
        }
        List<int[]> points = new ArrayList<>(coordinates.size());
        for (JsonElement element : coordinates) {
            JsonArray point = element.getAsJsonArray();
            points.add(point(point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()));
        }

        String encoded = encodeBare(points);
        V10Transport.Frame frame = V10Transport.decode(
                encoded.substring(WaypointCodec.MAGIC.length()));

        assertEquals(163, points.size());
        assertEquals(V10Transport.MODE_DIRECT, frame.mode(),
                "quotient direct mode must win this locked route");
        byte[] semantic = V10Transport.unseal(V10Transport.MODE_DIRECT, frame.payload());
        assertEquals(V10BareEntropyCodec.DirectDescriptor.QUOTIENT,
                V10BareEntropyCodec.descriptor(semantic));
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        assertEquals("V10_QUOTIENT", debug.groups().getFirst().coordMode());
        assertTrue(debug.textEncoding().contains("V10_QUOTIENT"));
        // Locked final text length, payload length, and payload digest.
        assertEquals("348 chars, 281 payload bytes, sha256 e2d5f286d94cea1c185821bc608099db1e3cabed1992351edd37b7968154fa41",
                encoded.length() + " chars, " + frame.payload().length + " payload bytes, sha256 "
                        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(frame.payload())));
    }

    @Test
    void contextual_base91_escape_is_exact_and_canonical() throws Exception {
        String plain = "<3<~o/o~ordinary~digit";
        String escaped = V10Transport.escapeContextual(plain);
        assertEquals("<~3<~~o~/o~~ordinary~digit", escaped);
        assertEquals(plain, V10Transport.unescapeContextual(escaped));

        // '<3' consists of legal base-91 digits, but it is not the canonical
        // chat spelling because the contextual marker is missing.
        IOException failure = assertThrows(IOException.class,
                () -> V10Transport.decode("<3"));
        assertTrue(failure.getMessage().contains("non-canonical"));

        byte[] arbitrary = {0x0A, 0, 1, 2, 3, 4, 5};
        String transport = V10Transport.encode(V10Transport.MODE_DIRECT, arbitrary);
        assertTrue(Arrays.equals(arbitrary, V10Transport.decode(transport).payload()));
    }

    @Test
    void crc_varints_padding_and_rice_work_are_strict() throws Exception {
        String good = encodeBare(List.of(
                point(10, 64, -20), point(10, 64, -20), point(11, 63, -18)));
        V10Transport.Frame frame = V10Transport.decode(
                good.substring(WaypointCodec.MAGIC.length()));
        byte[] corrupted = frame.payload();
        corrupted[corrupted.length - 1] ^= 1;
        IOException crcFailure = assertThrows(IOException.class,
                () -> V10BareRouteCodec.decode(V10Transport.encode(frame.mode(), corrupted)));
        assertTrue(crcFailure.getMessage().contains("CRC-16"));

        assertTerminalCommittedV10Failure(new byte[] {
                0x2A, (byte) 0x80, 0x00
        }, "non-canonical v10 uvarint");

        // Canonical two-identical-point Rice body is
        // 2a02000000ffffff03. One extra zero byte is redundant padding.
        assertTerminalCommittedV10Failure(new byte[] {
                0x2A, 0x02, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x03, 0x00
        }, "redundant v10 Rice padding byte");

        // count=2, first=(0,0,0), extended k=(0,0,0), then more than
        // 90*(count-1) unary zeros. The count-derived work budget must fire
        // before a broad frame-length scan can consume the body.
        byte[] unaryAttack = new byte[5 + 13];
        unaryAttack[0] = 0x2A;
        unaryAttack[1] = 0x02;
        unaryAttack[5] = 0x03;
        assertTerminalCommittedV10Failure(
                unaryAttack, "v10 Rice unary work exceeds count-derived limit");
    }

    @Test
    void valid_v10_header_and_crc_commit_before_kind_body_dispatch() {
        assertTerminalCommittedV10Failure(new byte[] {0x3A, 0x00},
                "unsupported v10 content kind 3");
    }

    @Test
    void legacy_v9_payloads_beginning_with_a_or_b_fall_back_after_failed_probe() throws Exception {
        List<String> codes;
        try (var stream = WaypointCodecV10BareRouteTest.class.getResourceAsStream(
                "/fixtures/v9-ab-prefix-fallback-codes.txt")) {
            if (stream == null) throw new AssertionError("missing V9 A/B fallback fixture");
            codes = new java.io.BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList();
        }

        assertEquals(List.of('A', 'B'), codes.stream()
                .map(code -> code.charAt(WaypointCodec.MAGIC.length())).toList());
        for (String code : codes) {
            List<WaypointGroup> decoded = WaypointCodec.decode(code);
            assertEquals(1, decoded.size());
            assertEquals(21, decoded.get(0).size());
        }
    }

    private static void assertTerminalCommittedV10Failure(byte[] semantic, String expected) {
        String code = WaypointCodec.MAGIC + V10Transport.encode(
                V10Transport.MODE_DIRECT,
                V10Transport.seal(V10Transport.MODE_DIRECT, semantic));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(code));
        assertTrue(failure.getMessage().contains("codec decode failed: v10="));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertFalse(failure.getMessage().contains("; v9="), failure.getMessage());
        assertFalse(failure.getMessage().contains("; v8="), failure.getMessage());
    }

    private static V10Transport.CheckedFrame assertDoesNotThrowProbe(String code) {
        try {
            return V10Transport.probe(code.substring(WaypointCodec.MAGIC.length()));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String encodeBare(List<int[]> points) {
        WaypointGroup group = WaypointGroup.create("ignored", "mining_3");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int[] point : points) group.add(Waypoint.at(point[0], point[1], point[2]));
        return WaypointCodec.encode(List.of(group), WaypointCodec.Options.BARE_COORDINATES);
    }

    private static int[] point(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    private static byte[] rawDeflate(byte[] input, int level) throws IOException {
        Deflater deflater = new Deflater(level, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(input);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private static byte[] externalCrcDeflatePayload(byte[] semantic, int level)
            throws IOException {
        return V10Transport.sealCompressed(semantic,
                rawDeflate(Arrays.copyOfRange(semantic, 1, semantic.length), level));
    }

    private static byte[] insertBeforeCrc(byte[] payload, byte value) {
        int contentEnd = payload.length - V10Transport.CHECKSUM_BYTES;
        byte[] inserted = new byte[payload.length + 1];
        System.arraycopy(payload, 0, inserted, 0, contentEnd);
        inserted[contentEnd] = value;
        System.arraycopy(payload, contentEnd, inserted, contentEnd + 1,
                V10Transport.CHECKSUM_BYTES);
        return inserted;
    }

    private static void assertPoint(Waypoint waypoint, int x, int y, int z) {
        assertEquals(x, waypoint.x());
        assertEquals(y, waypoint.y());
        assertEquals(z, waypoint.z());
    }
}
