package com.babbur.waypointer.codec;

import com.babbur.waypointer.chat.CodecScanner;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointCodecV10BareRoutePackTest {

    @Test
    void product_all_off_export_round_trips_exact_group_boundaries_order_and_coordinates()
            throws Exception {
        WaypointGroup first = WaypointGroup.create("ignored\u0000first", "mining_3");
        first.add(new Waypoint(11, 64, -5, "discarded waypoint name",
                0x123456, Waypoint.FLAG_DEPTH_CHECKED, 4.5));
        first.add(Waypoint.at(13, 63, -4));
        first.add(Waypoint.at(13, 63, -4));
        first.setLoadMode(WaypointGroup.LoadMode.STATIC);

        WaypointGroup second = route("ignored second", new int[][] {});
        WaypointGroup third = route("ignored third", new int[][] {
                {-300, -64, 900}, {-299, -63, 897}
        });

        String encoded = WaypointCodec.encode(
                List.of(first, second, third), WaypointCodec.Options.BARE_COORDINATES);
        V10Transport.CheckedFrame frame = V10Transport.probe(
                encoded.substring(WaypointCodec.MAGIC.length()));
        List<WaypointGroup> decoded = WaypointCodec.decode(encoded);
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);

        assertEquals(V10BareRoutePackCodec.SEMANTIC_HEADER, frame.header());
        assertEquals(V10BareRoutePackCodec.CONTENT_KIND, frame.contentKind());
        // header, subtype, route-count, child-length, then the headerless kind-2
        // body begins directly with this route's point count (3), not 0x2A.
        assertEquals(3, frame.semantic()[4] & 0xFF);
        assertEquals(10, debug.version());
        assertEquals(3, debug.groups().size());
        assertEquals(3, decoded.size());
        assertCoordinates(decoded.get(0), new int[][] {
                {11, 64, -5}, {13, 63, -4}, {13, 63, -4}
        });
        assertCoordinates(decoded.get(1), new int[][] {});
        assertCoordinates(decoded.get(2), new int[][] {
                {-300, -64, 900}, {-299, -63, 897}
        });
        for (WaypointGroup group : decoded) {
            assertEquals("", group.name());
            assertEquals("unknown", group.zoneId());
            assertEquals(WaypointGroup.RouteKind.REGULAR, group.routeKind());
            assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
            for (Waypoint waypoint : group.waypoints()) {
                assertEquals("", waypoint.name());
                assertEquals(Waypoint.DEFAULT_COLOR, waypoint.color());
                assertEquals(0, waypoint.flags());
            }
        }
        assertTrue(debug.groups().stream().allMatch(group ->
                group.coordMode().startsWith("V10_BARE_PACK_")));
        assertTrue(WaypointCodec.isValidCodec(encoded));
        assertTrue(CodecScanner.scan("routes: " + encoded).getFirst().valid());
    }

    @Test
    void single_bare_remains_kind2_multi_bare_is_kind6_and_catalog_stays_v9()
            throws Exception {
        WaypointGroup first = route("first", new int[][] {{1, 70, 2}, {2, 70, 3}});
        WaypointGroup second = route("second", new int[][] {{20, 80, -2}, {22, 80, -4}});
        List<WaypointGroup> routes = List.of(first, second);

        String singleProduct = WaypointCodec.encode(
                List.of(first), WaypointCodec.Options.BARE_COORDINATES);
        assertEquals(2, V10Transport.probe(singleProduct.substring(
                WaypointCodec.MAGIC.length())).contentKind());

        assertV10Kind(6, WaypointCodec.encode(routes, WaypointCodec.Options.BARE_COORDINATES));
        assertV10Kind(6, WaypointExportCodec.encode(
                routes,
                WaypointCodec.Options.BARE_COORDINATES,
                WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty()));
        assertV10Kind(6, UniversalShareCodec.encodeWaypoints(
                routes, WaypointCodec.Options.BARE_COORDINATES,
                RouteLibraryMetadata.empty()));
        assertV9(WaypointCodec.encodeCatalog(routes));
        assertV10Kind(0, WaypointExportCodec.encode(
                routes,
                WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty()));

        RouteLibraryMetadata richMetadata = new RouteLibraryMetadata(
                List.of(new RouteLibraryMetadata.ManualColorsEntry(0, List.of(0x123456, 0x654321))),
                List.of());
        String rich = WaypointExportCodec.encode(
                routes,
                WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.WAYPOINTER,
                richMetadata);
        assertFalse(rich.startsWith(RouteLibraryCodec.MAGIC));
        assertV10Kind(V10RouteLibraryCodec.CONTENT_KIND, rich);
        assertTrue(V10RouteLibraryCodec.isLibrarySemantic(V10Transport.probe(
                rich.substring(WaypointCodec.MAGIC.length())).semantic()));

        WaypointGroup dungeon = route("dungeon", new int[][] {{9, 65, 9}});
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        assertBareRejected(() -> WaypointCodec.encode(
                List.of(dungeon), WaypointCodec.Options.BARE_COORDINATES));
        assertBareRejected(() -> WaypointCodec.encode(
                List.of(first, dungeon), WaypointCodec.Options.BARE_COORDINATES));
        assertBareRejected(() -> WaypointCodec.encode(
                List.of(first, dungeon), WaypointCodec.Options.BARE_COORDINATES));
        assertBareRejected(() -> UniversalShareCodec.encodeWaypoints(
                List.of(first, dungeon), WaypointCodec.Options.BARE_COORDINATES));
    }

    @Test
    void all_off_regular_selector_and_product_facades_use_kind6_without_marking_builder_bare()
            throws Exception {
        List<WaypointGroup> routes = List.of(
                route("a", new int[][] {{1, 2, 3}}),
                route("b", new int[][] {{4, 5, 6}}));

        WaypointCodec.Options allOff = WaypointCodec.Options.builder()
                .includeNames(false)
                .includeColors(false)
                .includeRadii(false)
                .includeWaypointFlags(false)
                .includeGroupMeta(false)
                .includeZone(false)
                .build();
        assertFalse(allOff.isBareCoordinateProjection());
        assertV10Kind(6, WaypointCodec.encode(routes, allOff));

        String exportScreenPath = WaypointExportCodec.encode(
                routes,
                allOff,
                WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty());
        String commandPath = UniversalShareCodec.encodeWaypoints(
                routes,
                allOff,
                RouteLibraryMetadata.empty());

        assertEquals(6, V10Transport.probe(exportScreenPath.substring(
                WaypointCodec.MAGIC.length())).contentKind());
        assertEquals(6, V10Transport.probe(commandPath.substring(
                WaypointCodec.MAGIC.length())).contentKind());
        assertEquals(2, ((UniversalShareCodec.Waypoints)
                UniversalShareCodec.decode(commandPath)).result().groups().size());

        WaypointCodec.Options almostBare = WaypointCodec.Options.BARE_COORDINATES
                .toBuilder().includeZone(true).build();
        assertFalse(almostBare.isBareCoordinateProjection());
        assertV10Kind(0, WaypointExportCodec.encode(
                routes, almostBare, WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty()));

        WaypointCodec.Options labeledAllOff = WaypointCodec.Options.BARE_COORDINATES
                .toBuilder().label("route pack").build();
        assertFalse(labeledAllOff.isBareCoordinateProjection());
        assertV10Kind(V10GeneralRouteCodec.LABELED_CONTENT_KIND,
                WaypointCodec.encode(routes, labeledAllOff));
    }

    @Test
    void committed_kind6_rejects_noncanonical_truncated_and_malformed_children_terminally()
            throws Exception {
        // Subtype 1 is the route library; a bare subtype byte is a truncated library.
        assertTerminalFailure(new byte[] {0x6A, 0x01},
                "truncated v10 route library");
        // Subtype 2 is the catalog reference; a bare subtype byte is a truncated one.
        assertTerminalFailure(new byte[] {0x6A, 0x02},
                "truncated v10 catalog reference");
        assertTerminalFailure(new byte[] {0x6A, 0x03},
                "unsupported v10 bare-pack subtype");
        assertTerminalFailure(new byte[] {0x6A, 0x04},
                "unsupported v10 bare-pack subtype");
        assertTerminalFailure(new byte[] {0x6A, 0x00, (byte) 0x82, 0x00},
                "non-canonical v10 bare-pack uvarint");
        assertTerminalFailure(new byte[] {0x6A, 0x00, 0x01},
                "route count is below");
        assertTerminalFailure(new byte[] {0x6A, 0x00, (byte) 0x81, 0x02},
                "uvarint exceeds field limit");
        assertTerminalFailure(new byte[] {0x6A, 0x00, 0x02, 0x00},
                "child 0 is empty");
        assertTerminalFailure(new byte[] {0x6A, 0x00, 0x02, 0x03, 0x2A},
                "truncated v10 bare-pack child");
        assertTerminalFailure(new byte[] {
                0x6A, 0x00, 0x02,
                0x02, 0x3A, 0x00,
                0x01, 0x00
        },
                "truncated v10 semantic body");

        List<WaypointGroup> routes = List.of(
                route("a", new int[][] {{1, 2, 3}, {2, 2, 4}}),
                route("b", new int[][] {{5, 6, 7}, {7, 6, 8}}));
        byte[] canonical = V10BareRoutePackCodec.encodeSemantic(
                routes, V10Transport.MODE_DIRECT);
        assertTerminalFailure(Arrays.copyOf(canonical, canonical.length + 1),
                "trailing v10 bare-pack bytes");

        byte[] wrongModeChildren = V10BareRoutePackCodec.encodeSemantic(
                routes, V10Transport.MODE_DEFLATE);
        assertTerminalFailure(wrongModeChildren, "truncated v10 Rice stream");
    }

    @Test
    void aggregate_waypoint_limit_is_checked_before_decoding_the_over_limit_child()
            throws Exception {
        WaypointGroup maxRoute = WaypointGroup.create("ignored", "unknown");
        int pointsPerRoute = (V10BareRoutePackCodec.MAX_TOTAL_WAYPOINTS / 3) + 1;
        for (int index = 0; index < pointsPerRoute; index++) {
            maxRoute.add(Waypoint.at(0, 64, 0));
        }
        byte[] kind2 = V10BareRouteCodec.encodeRiceSemantic(
                V10BareRouteCodec.coordinatesOf(maxRoute));
        byte[] child = Arrays.copyOfRange(kind2, 1, kind2.length);
        ByteArrayOutputStream semantic = new ByteArrayOutputStream();
        semantic.write(0x6A);
        semantic.write(0);
        semantic.write(3);
        for (int index = 0; index < 3; index++) {
            writeUVarint(semantic, child.length);
            semantic.writeBytes(child);
        }

        assertTerminalFailure(semantic.toByteArray(), "total waypoint count exceeds 50000");
    }

    @Test
    void universal_dispatch_commits_known_kind6_without_legacy_repair_or_dungeon_fallback() {
        byte[] malformed = {0x6A, 0x00, 0x02, 0x00};
        String code = committedCode(malformed);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> UniversalShareCodec.decode(code));
        assertTrue(failure.getMessage().startsWith(
                        "share decode failed: committed v10 kind 6="),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("repair also failed"), failure.getMessage());
        assertFalse(failure.getMessage().contains("dungeon"), failure.getMessage());
    }

    @Test
    void deflate_mode_keeps_crc_outside_the_raw_stream() throws Exception {
        List<WaypointGroup> routes = new ArrayList<>();
        for (int routeIndex = 0; routeIndex < 16; routeIndex++) {
            WaypointGroup group = WaypointGroup.create("ignored " + routeIndex, "unknown");
            for (int pointIndex = 0; pointIndex < 50; pointIndex++) {
                group.add(Waypoint.at(pointIndex, 64, -pointIndex));
            }
            routes.add(group);
        }

        String code = WaypointCodec.encode(
                routes, WaypointCodec.Options.BARE_COORDINATES);
        V10Transport.Frame physical = V10Transport.decode(
                code.substring(WaypointCodec.MAGIC.length()));
        V10Transport.CheckedFrame checked = V10Transport.probe(
                code.substring(WaypointCodec.MAGIC.length()));

        assertEquals(V10Transport.MODE_DEFLATE, physical.mode());
        byte[] payload = physical.payload();
        byte[] compressedOnly = Arrays.copyOfRange(
                payload, 1, payload.length - V10Transport.CHECKSUM_BYTES);
        byte[] semantic = checked.semantic();
        assertArrayEquals(Arrays.copyOfRange(semantic, 1, semantic.length),
                V10Transport.inflate(compressedOnly));
        assertThrows(IOException.class,
                () -> V10Transport.inflate(Arrays.copyOfRange(payload, 1, payload.length)),
                "the checksum bytes must not be accepted as compressed input");

        byte[] alternate = V10Transport.deflateAndSeal(
                checked.semantic(), Deflater.BEST_SPEED);
        String alternateCode = WaypointCodec.MAGIC + V10Transport.encode(
                V10Transport.MODE_DEFLATE, alternate);
        assertEquals(16, WaypointCodec.decode(alternateCode).size());
    }

    @Test
    void direct_children_select_one_canonical_rice_or_quotient_body_and_enforce_cap()
            throws Exception {
        JsonArray fixture;
        try (var stream = WaypointCodecV10BareRoutePackTest.class.getResourceAsStream(
                "/fixtures/v10-equal-text-tie-coordinates.json")) {
            if (stream == null) throw new AssertionError("missing quotient fixture");
            fixture = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        int[][] coordinates = new int[fixture.size()][3];
        WaypointGroup quotientRoute = WaypointGroup.create("ignored", "unknown");
        for (int index = 0; index < fixture.size(); index++) {
            JsonArray point = fixture.get(index).getAsJsonArray();
            for (int axis = 0; axis < 3; axis++) {
                coordinates[index][axis] = point.get(axis).getAsInt();
            }
            quotientRoute.add(Waypoint.at(
                    coordinates[index][0], coordinates[index][1], coordinates[index][2]));
        }
        WaypointGroup empty = WaypointGroup.create("ignored empty", "unknown");
        byte[] canonical = V10BareRoutePackCodec.encodeSemantic(
                List.of(quotientRoute, empty), V10Transport.MODE_DIRECT);
        int[] cursor = {3};
        int firstLength = readUVarint(canonical, cursor);
        byte[] firstSemantic = restoreKind2Header(Arrays.copyOfRange(
                canonical, cursor[0], cursor[0] + firstLength));
        assertEquals(V10BareEntropyCodec.DirectDescriptor.QUOTIENT,
                V10BareEntropyCodec.descriptor(firstSemantic));
        assertEquals(2, V10BareRoutePackCodec.decode(new V10Transport.CheckedFrame(
                V10Transport.MODE_DIRECT, canonical)).size());

        byte[] rice = V10BareRouteCodec.encodeRiceSemantic(coordinates);
        byte[] emptySemantic = V10BareRouteCodec.encodeRiceSemantic(
                V10BareRouteCodec.coordinatesOf(empty));
        ByteArrayOutputStream nonCanonicalRicePack = new ByteArrayOutputStream();
        nonCanonicalRicePack.write(0x6A);
        nonCanonicalRicePack.write(0);
        nonCanonicalRicePack.write(2);
        writeHeaderlessChild(nonCanonicalRicePack, rice);
        writeHeaderlessChild(nonCanonicalRicePack, emptySemantic);
        assertTerminalFailure(nonCanonicalRicePack.toByteArray(),
                "non-canonical v10 bare-pack coordinate child");

        int[][] overCap = new int[V10BareEntropyCodec.MAX_QUOTIENT_WAYPOINTS + 1][3];
        ByteArrayOutputStream overCapSemantic = V10BareRouteCodec.semanticPrefix(overCap);
        V10BareRouteCodec.RiceBitWriter marker = new V10BareRouteCodec.RiceBitWriter();
        marker.writeBits(3, 2);
        marker.writeBits(7, 3);
        marker.writeBits(1, 5);
        overCapSemantic.writeBytes(marker.finish());
        ByteArrayOutputStream overCapPack = new ByteArrayOutputStream();
        overCapPack.write(0x6A);
        overCapPack.write(0);
        overCapPack.write(2);
        writeHeaderlessChild(overCapPack, overCapSemantic.toByteArray());
        writeHeaderlessChild(overCapPack, emptySemantic);
        assertTerminalFailure(overCapPack.toByteArray(), "1024-waypoint limit");
    }

    private static WaypointGroup route(String name, int[][] coordinates) {
        WaypointGroup group = WaypointGroup.create(name, "mining_3");
        for (int[] coordinate : coordinates) {
            group.add(Waypoint.at(coordinate[0], coordinate[1], coordinate[2]));
        }
        return group;
    }

    private static void assertCoordinates(WaypointGroup group, int[][] expected) {
        assertEquals(expected.length, group.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index][0], group.get(index).x());
            assertEquals(expected[index][1], group.get(index).y());
            assertEquals(expected[index][2], group.get(index).z());
        }
    }

    private static void assertV9(String encoded) {
        assertEquals(9, WaypointCodec.debugDecode(encoded).version(), encoded);
    }

    private static void assertV10Kind(int expectedKind, String encoded) throws Exception {
        V10Transport.CheckedFrame frame = V10Transport.probe(
                encoded.substring(WaypointCodec.MAGIC.length()));
        assertEquals(expectedKind, frame.contentKind(), encoded);
    }

    private static void assertBareRejected(Runnable encoder) {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class, encoder::run);
        assertTrue(rejected.getMessage().contains("coordinate-only export requires"),
                rejected.getMessage());
    }

    private static void assertTerminalFailure(byte[] semantic, String expected) {
        String code = committedCode(semantic);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> WaypointCodec.decode(code));
        assertTrue(failure.getMessage().contains("codec decode failed: v10="), failure.getMessage());
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertFalse(failure.getMessage().contains("; v9="), failure.getMessage());
        assertFalse(failure.getMessage().contains("; v8="), failure.getMessage());
    }

    private static String committedCode(byte[] semantic) {
        return WaypointCodec.MAGIC + V10Transport.encode(
                V10Transport.MODE_DIRECT,
                V10Transport.seal(V10Transport.MODE_DIRECT, semantic));
    }

    private static void writeUVarint(ByteArrayOutputStream output, int value) {
        do {
            int next = value & 0x7F;
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static int readUVarint(byte[] input, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (true) {
            int next = input[cursor[0]++] & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
    }

    private static byte[] restoreKind2Header(byte[] body) {
        byte[] semantic = new byte[body.length + 1];
        semantic[0] = 0x2A;
        System.arraycopy(body, 0, semantic, 1, body.length);
        return semantic;
    }

    private static void writeHeaderlessChild(ByteArrayOutputStream pack, byte[] semantic) {
        int bodyLength = semantic.length - 1;
        writeUVarint(pack, bodyLength);
        pack.write(semantic, 1, bodyLength);
    }
}
