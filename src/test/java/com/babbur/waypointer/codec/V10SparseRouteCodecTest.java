package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class V10SparseRouteCodecTest {

    private static final WaypointCodec.Options DEFAULT_SPARSE = WaypointCodec.Options.builder()
            .includeNames(false)
            .includeColors(false)
            .includeRadii(false)
            .includeWaypointFlags(false)
            .includeGroupMeta(false)
            .includeZone(false)
            .build();

    private static final WaypointCodec.Options FULL_SPARSE = DEFAULT_SPARSE.toBuilder()
            .includeWaypointFlags(true)
            .build();

    @Test
    void python_locked_tiny_golden_is_byte_identical() throws Exception {
        assertFalse(DEFAULT_SPARSE.isBareCoordinateProjection());
        WaypointGroup route = WaypointGroup.create("", "unknown");
        route.add(Waypoint.at(0, 70, 0));
        route.add(Waypoint.at(1, 70, 0)
                .withFlags(Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_FILLED_SUBWAYPOINT)
                .withPreciseSixteenths(22, 1130, 0));

        String code = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, DEFAULT_SPARSE);
        assertEquals("WP:~!Y9Kl$QC!K)$!N{P9KP)", code);
        WaypointGroup decoded = WaypointCodec.decode(code).getFirst();
        assertRouteFields(route, decoded, true);
    }

    @Test
    void crc_outside_split_deflate_golden_is_byte_identical() throws Exception {
        WaypointGroup route = WaypointGroup.create("", "unknown");
        for (int index = 0; index < 96; index++) {
            int flags = index > 0 && index % 4 == 0
                    ? Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT
                    : index % 7 == 0 ? Waypoint.FLAG_DUNGEON_SECRET : 0;
            int offsetX = index % 6 == 0 ? 9 : 8;
            int offsetY = index % 6 == 0 ? 7 : 8;
            route.add(Waypoint.at(index, 70, index % 5 - 2)
                    .withFlags(flags)
                    .withPreciseSixteenths(
                            index * 16 + offsetX,
                            70 * 16 + offsetY,
                            (index % 5 - 2) * 16 + 8));
        }

        String code = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, FULL_SPARSE);
        assertEquals("WP:Cr:4IPM8>Fh'OXYQ'9=3:eP(J-TgvVx!<bh?n#hSX>@AfI{ajT:fK[/1P*%!rl&", code);
        assertRouteFields(route, WaypointCodec.decode(code).getFirst(), true);
    }

    @Test
    void legacy_unified_golden_remains_readable_and_new_selection_is_no_larger() throws Exception {
        WaypointGroup route = WaypointGroup.create("", "unknown");
        for (int index = 0; index < 11; index++) {
            int flags = index == 4
                    ? Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT
                    : index == 7 ? Waypoint.FLAG_DUNGEON_SECRET : 0;
            route.add(Waypoint.at(index, 70, index % 3).withFlags(flags));
        }

        String code = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, FULL_SPARSE);
        String legacy = "WP:p+OXOhwFd5LPR%JE*\"^)a\"SE?A'!Wv%";
        assertTrue(code.length() <= legacy.length());
        V10Transport.CheckedFrame frame = V10Transport.probe(
                legacy.substring(WaypointCodec.MAGIC.length()));
        assertEquals(0, frame.semantic()[1]);
        assertRouteFields(route, WaypointCodec.decode(legacy).getFirst(), true);
        assertRouteFields(route, WaypointCodec.decode(code).getFirst(), true);
    }

    @Test
    void alternate_standards_conforming_raw_deflate_is_accepted() throws Exception {
        WaypointGroup route = sparseFixture();
        String canonical = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, FULL_SPARSE);
        V10Transport.CheckedFrame frame = V10Transport.probe(
                canonical.substring(WaypointCodec.MAGIC.length()));
        String alternate = WaypointCodec.MAGIC + V10Transport.encode(
                V10Transport.MODE_DEFLATE,
                V10Transport.deflateAndSeal(frame.semantic(), Deflater.HUFFMAN_ONLY));

        assertNotEquals(canonical, alternate);
        assertRouteFields(route, WaypointCodec.decode(alternate).getFirst(), true);
    }

    @Test
    void default_projection_keeps_only_persistent_structure_and_sub_precision() {
        WaypointGroup route = WaypointGroup.create("", "unknown");
        route.add(Waypoint.at(0, 70, 0)
                .withFlags(Waypoint.FLAG_SKIP_ON_STAND
                        | Waypoint.FLAG_DUNGEON_SECRET
                        | Waypoint.FLAG_DISABLED)
                .withPreciseSixteenths(1, 1121, 1));
        route.add(Waypoint.at(1, 70, 0)
                .withFlags(Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED
                        | Waypoint.FLAG_SKIP_ON_MINE
                        | Waypoint.FLAG_DUNGEON_BAT)
                .withPreciseSixteenths(17, 1122, 3));

        String code = WaypointCodec.MAGIC
                + assertDoesNotThrow(() -> V10SparseRouteCodec.encode(route, DEFAULT_SPARSE));
        assertEquals(V10SparseRouteCodec.CONTENT_KIND, assertDoesNotThrow(
                () -> V10Transport.probe(
                        code.substring(WaypointCodec.MAGIC.length()))).contentKind());
        WaypointGroup decoded = WaypointCodec.decode(code).getFirst();
        assertEquals(Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_DISABLED,
                decoded.get(0).flags());
        assertFalse(decoded.get(0).hasCustomPrecisePosition());
        assertEquals(Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED
                        | Waypoint.FLAG_DUNGEON_BAT,
                decoded.get(1).flags());
        assertEquals(17, decoded.get(1).preciseX());
        assertEquals(1122, decoded.get(1).preciseY());
        assertEquals(3, decoded.get(1).preciseZ());
    }

    @Test
    void full_sparse_round_trips_unsigned_flags_and_ordinary_precision() throws Exception {
        WaypointGroup route = sparseFixture();
        String code = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, FULL_SPARSE);
        V10Transport.CheckedFrame frame = V10Transport.probe(
                code.substring(WaypointCodec.MAGIC.length()));
        assertEquals(V10SparseRouteCodec.CONTENT_KIND, frame.contentKind());
        assertRouteFields(route, WaypointCodec.decode(code).getFirst(), true);
    }

    @Test
    void quotient_direct_body_competes_for_sparse_route() throws Exception {
        JsonArray fixture;
        try (var stream = V10SparseRouteCodecTest.class.getResourceAsStream(
                "/fixtures/v10-equal-text-tie-coordinates.json")) {
            if (stream == null) throw new AssertionError("missing quotient fixture");
            fixture = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        WaypointGroup route = WaypointGroup.create("", "unknown");
        for (int index = 0; index < fixture.size(); index++) {
            JsonArray point = fixture.get(index).getAsJsonArray();
            Waypoint waypoint = Waypoint.at(
                    point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt());
            if (index == 1) waypoint = waypoint.withFlags(Waypoint.FLAG_DUNGEON_SECRET);
            route.add(waypoint);
        }

        String code = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, FULL_SPARSE);
        V10Transport.CheckedFrame frame = V10Transport.probe(
                code.substring(WaypointCodec.MAGIC.length()));
        assertEquals(V10SparseRouteCodec.CONTENT_KIND, frame.contentKind());
        assertEquals(V10Transport.MODE_DIRECT, frame.mode());
        byte[] coordinateBody = directCoordinateBody(frame.semantic(), route.size());
        byte[] coordinateSemantic = new byte[coordinateBody.length + 1];
        coordinateSemantic[0] = (byte) V10BareRouteCodec.SEMANTIC_HEADER;
        System.arraycopy(coordinateBody, 0, coordinateSemantic, 1, coordinateBody.length);
        assertEquals(V10BareEntropyCodec.DirectDescriptor.QUOTIENT,
                V10BareEntropyCodec.descriptor(coordinateSemantic));
        assertRouteFields(route, WaypointCodec.decode(code).getFirst(), false);
    }

    @Test
    void explicit_bare_coordinates_never_compete_with_semantics_retaining_kind5() throws Exception {
        WaypointGroup route = sparseFixture();
        String code = WaypointCodec.encode(List.of(route), WaypointCodec.Options.BARE_COORDINATES);
        V10Transport.CheckedFrame frame = V10Transport.probe(
                code.substring(WaypointCodec.MAGIC.length()));
        assertEquals(2, frame.contentKind());
        WaypointGroup decoded = WaypointCodec.decode(code).getFirst();
        for (Waypoint waypoint : decoded.waypoints()) {
            assertEquals(0, waypoint.flags());
            assertFalse(waypoint.hasCustomPrecisePosition());
        }
    }

    @Test
    void universal_dispatch_reports_kind5_as_waypoints() throws Exception {
        WaypointGroup route = sparseFixture();
        String code = WaypointCodec.MAGIC + V10SparseRouteCodec.encode(route, FULL_SPARSE);
        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(code);
        UniversalShareCodec.Waypoints waypoints =
                assertInstanceOf(UniversalShareCodec.Waypoints.class, decoded);
        assertEquals(UniversalShareCodec.Type.WAYPOINTS, waypoints.type());
        assertRouteFields(route, waypoints.result().groups().getFirst(), true);
    }

    @Test
    void kind5_body_rejects_noncanonical_selectors_padding_and_first_subwaypoint() {
        assertCommittedKind5Failure(
                new byte[]{0x5A, (byte) 0x80, 0x00},
                "non-canonical v10 kind-5 uvarint");

        byte[] coordinateBody = V10BareRouteCodec.encodeCoordinateBody(
                new int[][]{{0, 70, 0}, {1, 70, 0}}, V10Transport.MODE_DIRECT);

        ByteArrayOutputStream reserved = splitPrefix(coordinateBody, 0x40);
        assertCommittedKind5Failure(reserved.toByteArray(), "reserved");

        ByteArrayOutputStream paddedPresence = splitPrefix(coordinateBody, 0x01);
        paddedPresence.write(0x82); // point 1 plus a non-zero bit outside count=2
        assertCommittedKind5Failure(
                paddedPresence.toByteArray(), "non-zero subwaypoint presence padding");

        ByteArrayOutputStream firstSubwaypoint = splitPrefix(coordinateBody, 0x02);
        firstSubwaypoint.write(1); // one ordinal
        firstSubwaypoint.write(0); // gap 0 selects waypoint 0
        assertCommittedKind5Failure(
                firstSubwaypoint.toByteArray(), "first waypoint cannot be a subwaypoint");

        // Unified selector 0; one exception at index 0 whose entire metadata
        // bit record is zero is an alternate spelling of no exception.
        assertCommittedKind5Failure(
                new byte[]{0x5A, 0, 1, 0, 0, 1, 0, 0, 0},
                "no-op unified exception is non-canonical");
    }

    private static ByteArrayOutputStream splitPrefix(byte[] coordinateBody, int sideHeader) {
        ByteArrayOutputStream semantic = new ByteArrayOutputStream();
        semantic.write(0x5A);
        semantic.write(coordinateBody.length);
        semantic.writeBytes(coordinateBody);
        semantic.write(sideHeader);
        return semantic;
    }

    private static byte[] directCoordinateBody(byte[] semantic, int pointCount) throws IOException {
        int[] cursor = {1};
        long selector = readUVarint(semantic, cursor);
        if (selector > 1) {
            int end = cursor[0] + Math.toIntExact(selector);
            if (end > semantic.length) throw new IOException("truncated sparse coordinate body");
            byte[] body = Arrays.copyOfRange(semantic, cursor[0], end);
            if (V10BareRouteCodec.decodeCoordinateBody(body, V10Transport.MODE_DIRECT).length
                    != pointCount) {
                throw new IOException("sparse coordinate count mismatch");
            }
            return body;
        }
        for (int start = cursor[0]; start < semantic.length; start++) {
            byte[] body = Arrays.copyOfRange(semantic, start, semantic.length);
            try {
                if (V10BareRouteCodec.decodeCoordinateBody(body, V10Transport.MODE_DIRECT).length
                        == pointCount) return body;
            } catch (IOException | IllegalArgumentException ignored) {
                // Unified metadata occupies the prefix; try the next boundary.
            }
        }
        throw new IOException("sparse coordinate body was not found");
    }

    private static long readUVarint(byte[] data, int[] cursor) throws IOException {
        long result = 0;
        for (int index = 0; index < 4; index++) {
            if (cursor[0] >= data.length) throw new IOException("truncated sparse selector");
            int next = data[cursor[0]++] & 0xFF;
            result |= (long) (next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) return result;
        }
        throw new IOException("sparse selector is too long");
    }

    private static void assertCommittedKind5Failure(byte[] semantic, String expectedMessage) {
        String code = WaypointCodec.MAGIC + V10Transport.encode(
                V10Transport.MODE_DIRECT,
                V10Transport.seal(V10Transport.MODE_DIRECT, semantic));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> WaypointCodec.decode(code));
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
    }

    private static WaypointGroup sparseFixture() {
        WaypointGroup route = WaypointGroup.create("", "unknown");
        route.add(Waypoint.at(-134_217_728, 70, 134_217_727)
                .withFlags(0x8000_00E0)
                .withPreciseSixteenths(Integer.MIN_VALUE, 1123, Integer.MAX_VALUE));
        route.add(Waypoint.at(5, -2, 7)
                .withFlags(Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_FILLED_SUBWAYPOINT
                        | Waypoint.FLAG_DUNGEON_ITEM
                        | 0x4000_0000)
                .withPreciseSixteenths(81, -31, 127));
        route.add(Waypoint.at(6, -2, 9).withFlags(0xFFFF_FFFF));
        return route;
    }

    private static void assertRouteFields(
            WaypointGroup expected, WaypointGroup actual, boolean precise) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            Waypoint left = expected.get(index);
            Waypoint right = actual.get(index);
            assertEquals(left.x(), right.x(), "x " + index);
            assertEquals(left.y(), right.y(), "y " + index);
            assertEquals(left.z(), right.z(), "z " + index);
            assertEquals(left.flags(), right.flags(), "flags " + index);
            if (precise) {
                assertEquals(left.preciseX(), right.preciseX(), "precise x " + index);
                assertEquals(left.preciseY(), right.preciseY(), "precise y " + index);
                assertEquals(left.preciseZ(), right.preciseZ(), "precise z " + index);
            }
        }
    }
}
