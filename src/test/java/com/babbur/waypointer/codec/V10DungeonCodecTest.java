package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.V10DungeonBodyCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V10DungeonCodecTest {

    @Test
    void nearLimitDirectFrameSurvivesOversizedOptionalDeflateCandidates() throws Exception {
        byte[] semantic = new byte[V10Transport.MAX_FRAME_BYTES - V10Transport.CHECKSUM_BYTES];
        new Random(0x6E656172436170L).nextBytes(semantic);
        semantic[0] = (byte) V10DungeonBodyCodec.SEMANTIC_HEADER;

        assertThrows(V10ProfileLimitException.class, () ->
                V10Transport.deflated(semantic, Deflater.DEFAULT_STRATEGY));
        assertThrows(V10ProfileLimitException.class, () ->
                V10Transport.deflated(semantic, Deflater.FILTERED));

        V10Transport.Outbound selected = V10DungeonCodec.selectCandidate(semantic);

        assertEquals(V10Transport.MODE_DIRECT, selected.mode());
        assertEquals(V10Transport.MAX_FRAME_BYTES, selected.payload().length);
        assertArrayEquals(semantic,
                V10Transport.unseal(V10Transport.MODE_DIRECT, selected.payload()));
    }

    @Test
    void externalCrcCanMakeAnOtherwiseBoundedDeflateCandidateIneligible() throws Exception {
        byte[] semantic = V10FrameBoundary.semanticWhoseDeflateOnlyFitsWithoutChecksum(
                (byte) V10DungeonBodyCodec.SEMANTIC_HEADER, 0x10C0FFEEL);

        assertThrows(V10ProfileLimitException.class, () ->
                V10Transport.deflateAndSeal(semantic, Deflater.DEFAULT_STRATEGY));

        V10Transport.Outbound selected = V10DungeonCodec.selectCandidate(semantic);
        assertEquals(V10Transport.MODE_DIRECT, selected.mode());
        assertEquals(semantic.length + V10Transport.CHECKSUM_BYTES, selected.payload().length);
        assertArrayEquals(semantic,
                V10Transport.unseal(V10Transport.MODE_DIRECT, selected.payload()));
    }

    @Test
    void goldenVectorIsStable() throws Exception {
        WaypointGroup route = dungeonRoute("crypt-a", "Crypt");
        route.setLoadMode(WaypointGroup.LoadMode.STATIC);
        route.setSkipAheadEnabled(false);
        route.add(new Waypoint(1, 70, -2, "Chest", 0x123456,
                Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_SKIP_ON_INTERACT, 2.5)
                .withPreciseSixteenths(23, 1128, -25));
        byte[] semantic = V10DungeonBodyCodec.encode(List.of(route));
        String wire = V10DungeonCodec.encode(List.of(route));

        assertArrayEquals(HexFormat.of().parseHex(
                "4a00012c0763727970742d61054372797074000100028c01031f054368657374"
                        + "12345680284004000000000000010001"), semantic);
        assertEquals("WP:_+N)mhQtw@Q[Le;F25E5N)lgj+^4bZalY<tJRQM'C#p6zQjD[;z3#!iD%",
                wire);
        assertEquals(V10Transport.MODE_DEFLATE, V10Transport.decode(
                wire.substring(WaypointCodec.MAGIC.length())).mode());
        assertGroupsEqual(List.of(route), V10DungeonCodec.decode(wire).routes());
    }

    @Test
    void exactWpdSchema2ProjectionPreservesAllFlattenedSemantics() throws Exception {
        List<WaypointGroup> source = edgeRoutes();
        List<WaypointGroup> expected = DungeonRoomShareCodec.decode(
                DungeonRoomShareCodec.encode(source)).routes();

        String encoded = V10DungeonCodec.encode(source);
        V10DungeonBodyCodec.Decoded decoded = V10DungeonCodec.decode(encoded);

        assertTrue(encoded.startsWith("WP:"));
        V10Transport.CheckedFrame frame = V10Transport.probe(
                encoded.substring(WaypointCodec.MAGIC.length()));
        assertEquals(V10DungeonBodyCodec.CONTENT_KIND, frame.contentKind());
        assertEquals(V10DungeonBodyCodec.SEMANTIC_HEADER, frame.header());
        assertEquals(V10DungeonBodyCodec.SUBTYPE_FLATTENED_WPD_SCHEMA_2, decoded.subtype());
        assertGroupsEqual(expected, decoded.routes());
        assertEquals(encoded, V10DungeonCodec.encode(decoded.routes()));
    }

    @Test
    void universalWriterAndDispatcherUseKind4WhileLegacyApiStaysWpd() {
        List<WaypointGroup> source = edgeRoutes();
        String universal = UniversalShareCodec.encodeDungeon(source);
        String legacy = DungeonRoomShareCodec.encode(source);

        assertTrue(universal.startsWith("WP:"));
        assertTrue(legacy.startsWith("WPD:."));
        UniversalShareCodec.DungeonRoutes result = assertInstanceOf(
                UniversalShareCodec.DungeonRoutes.class,
                UniversalShareCodec.decode("```text\n" + universal + "\n```"));
        assertEquals(source.stream().mapToInt(WaypointGroup::size).sum(),
                result.result().waypointCount());
        assertEquals(com.babbur.waypointer.dungeon.data.DungeonRouteImporter.Format.WAYPOINTER,
                result.result().format());
        assertGroupsEqual(DungeonRoomShareCodec.decode(legacy).routes(), result.result().groups());
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.decode(universal));
    }

    @Test
    void debugInspectorDecodesKind4DungeonExports() {
        List<WaypointGroup> source = edgeRoutes();
        String encoded = UniversalShareCodec.encodeDungeon(source);

        DecodeDebug debug = WaypointCodec.debugDecode(encoded);

        assertEquals(10, debug.version());
        assertEquals(V10DungeonBodyCodec.CONTENT_KIND,
                WaypointCodec.v9ContentKind(debug.headerByte()));
        assertTrue(debug.groups().stream()
                .allMatch(group -> group.coordMode().startsWith("V10_DUNGEON_")));
        assertGroupsEqual(DungeonRoomShareCodec.decode(
                DungeonRoomShareCodec.encode(source)).routes(), debug.decodedGroups());
    }

    @Test
    void acceptsAlternateStandardsValidRawDeflateAndNormalizesOnWrite() throws Exception {
        String canonical = V10DungeonCodec.encode(edgeRoutes());
        V10Transport.CheckedFrame checked = V10Transport.probe(
                canonical.substring(WaypointCodec.MAGIC.length()));
        byte[] alternate = rawDeflateAndSeal(checked.semantic(), Deflater.BEST_SPEED);
        String imported = WaypointCodec.MAGIC
                + V10Transport.encode(V10Transport.MODE_DEFLATE, alternate);

        V10DungeonBodyCodec.Decoded decoded = V10DungeonCodec.decode(imported);

        assertGroupsEqual(DungeonRoomShareCodec.decode(
                DungeonRoomShareCodec.encode(edgeRoutes())).routes(), decoded.routes());
        assertEquals(canonical, V10DungeonCodec.encode(decoded.routes()));
    }

    @Test
    void sharedTransportRejectsBadCrcAndIncompleteDeflateForKind4() throws Exception {
        byte[] semantic = V10DungeonBodyCodec.encode(edgeRoutes());
        byte[] badCrc = V10Transport.seal(V10Transport.MODE_DIRECT, semantic);
        badCrc[badCrc.length - 1] ^= 1;
        String crcCode = WaypointCodec.MAGIC
                + V10Transport.encode(V10Transport.MODE_DIRECT, badCrc);
        assertTrue(assertThrows(IOException.class, () -> V10DungeonCodec.decode(crcCode))
                .getMessage().contains("CRC-16"));

        byte[] compressed = rawDeflateAndSeal(semantic, Deflater.BEST_SPEED);
        byte[] badDeflateCrc = compressed.clone();
        badDeflateCrc[badDeflateCrc.length - 1] ^= 1;
        String badDeflateCrcCode = WaypointCodec.MAGIC
                + V10Transport.encode(V10Transport.MODE_DEFLATE, badDeflateCrc);
        assertTrue(assertThrows(IOException.class,
                () -> V10DungeonCodec.decode(badDeflateCrcCode))
                .getMessage().contains("CRC-16"));

        // payload = header, compressed bytes, checksum; the last compressed byte
        // therefore sits at index compressedLength.
        int compressedLength = compressed.length - 1 - V10Transport.CHECKSUM_BYTES;
        byte[] badCompressedTail = compressed.clone();
        badCompressedTail[compressedLength] ^= 1;
        String badCompressedTailCode = WaypointCodec.MAGIC
                + V10Transport.encode(V10Transport.MODE_DEFLATE, badCompressedTail);
        assertThrows(IOException.class, () -> V10DungeonCodec.decode(badCompressedTailCode));

        byte[] trailing = new byte[compressed.length + 1];
        System.arraycopy(compressed, 0, trailing, 0, 1 + compressedLength);
        System.arraycopy(compressed, 1 + compressedLength, trailing, 2 + compressedLength,
                V10Transport.CHECKSUM_BYTES);
        String trailingCode = WaypointCodec.MAGIC
                + V10Transport.encode(V10Transport.MODE_DEFLATE, trailing);
        assertTrue(assertThrows(IOException.class, () -> V10DungeonCodec.decode(trailingCode))
                .getMessage().contains("trailing v10 compressed bytes"));

        byte[] truncated = new byte[compressed.length - 1];
        System.arraycopy(compressed, 0, truncated, 0, compressedLength);
        System.arraycopy(compressed, 1 + compressedLength, truncated, compressedLength,
                V10Transport.CHECKSUM_BYTES);
        String truncatedCode = WaypointCodec.MAGIC
                + V10Transport.encode(V10Transport.MODE_DEFLATE, truncated);
        IOException truncatedFailure = assertThrows(IOException.class,
                () -> V10DungeonCodec.decode(truncatedCode));
        assertTrue(truncatedFailure.getMessage().contains("truncated v10 deflate")
                || truncatedFailure.getMessage().contains("malformed v10 deflate"));
    }

    @Test
    void routeOnlyAndCrossKindApisRejectTypedMismatch() throws Exception {
        String dungeon = V10DungeonCodec.encode(edgeRoutes());
        IllegalArgumentException routeOnly = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decodeFull(dungeon));
        assertTrue(routeOnly.getMessage().contains("kind 4"));
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(dungeon));

        String config = UniversalShareCodec.encodeConfig(new WaypointerConfig());
        IOException configMismatch = assertThrows(IOException.class,
                () -> V10DungeonCodec.decode(config));
        assertTrue(configMismatch.getMessage().contains("kind 3"));

        WaypointGroup bare = WaypointGroup.create("", "unknown");
        bare.add(Waypoint.at(1, 2, 3));
        String bareCode = WaypointCodec.encode(List.of(bare), WaypointCodec.Options.BARE_COORDINATES);
        IOException routeMismatch = assertThrows(IOException.class,
                () -> V10DungeonCodec.decode(bareCode));
        assertTrue(routeMismatch.getMessage().contains("kind 2"));
    }

    @Test
    void committedMalformedAndUnsupportedV10NeverFallBack() {
        String unsupportedSubtype = codeForSemantic(new byte[] {
                (byte) V10DungeonBodyCodec.SEMANTIC_HEADER, 1, 1, 1, 0
        });
        IllegalArgumentException subtypeFailure = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(unsupportedSubtype));
        assertTrue(subtypeFailure.getMessage().contains("committed v10 kind 4"));
        assertTrue(subtypeFailure.getMessage().contains("subtype"));
        assertTrue(Arrays.stream(subtypeFailure.getSuppressed()).noneMatch(
                failure -> failure.getMessage() != null && failure.getMessage().contains("WPD")));

        String nonCanonicalSubtype = codeForSemantic(new byte[] {
                (byte) V10DungeonBodyCodec.SEMANTIC_HEADER, (byte) 0x80, 0, 1, 1, 0
        });
        IllegalArgumentException varintFailure = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(nonCanonicalSubtype));
        assertTrue(varintFailure.getMessage().contains("committed v10 kind 4"));
        assertTrue(varintFailure.getMessage().contains("non-canonical"));

        // Kind 1 is the last unassigned kind; kind 7 became the labeled general route.
        String unsupportedKind = codeForSemantic(new byte[] {(byte) 0x1A});
        IllegalArgumentException kindFailure = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(unsupportedKind));
        assertTrue(kindFailure.getMessage().contains("committed v10 kind 1"));
        assertTrue(kindFailure.getMessage().contains("unsupported committed v10 share kind 1"));
    }

    @Test
    void bodyParserRequiresChildLengthsCanonicalValuesAndExactEof() throws Exception {
        byte[] good = V10DungeonBodyCodec.encode(edgeRoutes());

        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThrows(IOException.class, () -> V10DungeonBodyCodec.decode(trailing));

        byte[] truncated = Arrays.copyOf(good, good.length - 1);
        assertThrows(IOException.class, () -> V10DungeonBodyCodec.decode(truncated));

        byte[] overlongRouteCount = new byte[] {
                (byte) V10DungeonBodyCodec.SEMANTIC_HEADER, 0, (byte) 0x81, 0, 1, 0
        };
        assertTrue(assertThrows(IOException.class,
                () -> V10DungeonBodyCodec.decode(overlongRouteCount))
                .getMessage().contains("non-canonical"));

        byte[] missingChild = new byte[] {
                (byte) V10DungeonBodyCodec.SEMANTIC_HEADER, 0, 1, 10, 0
        };
        assertTrue(assertThrows(IOException.class,
                () -> V10DungeonBodyCodec.decode(missingChild))
                .getMessage().contains("truncated"));
    }

    @Test
    void encodeLimitsRoutesPointsAndStringsBeforeWriting() {
        List<WaypointGroup> tooManyRoutes = new ArrayList<>();
        for (int index = 0; index <= V10DungeonBodyCodec.MAX_ROUTES; index++) {
            WaypointGroup route = dungeonRoute("room-" + index, "r");
            route.add(Waypoint.at(index, 64, 0));
            tooManyRoutes.add(route);
        }
        assertThrows(IllegalArgumentException.class,
                () -> V10DungeonBodyCodec.encode(tooManyRoutes));

        WaypointGroup tooManyPoints = dungeonRoute("room", "r");
        for (int index = 0; index <= V10DungeonBodyCodec.MAX_WAYPOINTS_PER_ROUTE; index++) {
            tooManyPoints.add(Waypoint.at(index, 64, 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> V10DungeonBodyCodec.encode(List.of(tooManyPoints)));

        List<WaypointGroup> tooManyTotal = new ArrayList<>();
        int remaining = V10DungeonBodyCodec.MAX_TOTAL_WAYPOINTS + 1;
        for (int routeIndex = 0; remaining > 0; routeIndex++) {
            WaypointGroup route = dungeonRoute("total-" + routeIndex, "r");
            int count = Math.min(V10DungeonBodyCodec.MAX_WAYPOINTS_PER_ROUTE, remaining);
            for (int point = 0; point < count; point++) {
                route.add(Waypoint.at(point, 64, routeIndex));
            }
            remaining -= count;
            tooManyTotal.add(route);
        }
        assertThrows(IllegalArgumentException.class,
                () -> V10DungeonBodyCodec.encode(tooManyTotal));

        WaypointGroup longName = dungeonRoute("room", "x".repeat(
                V10DungeonBodyCodec.MAX_STRING_BYTES + 1));
        longName.add(Waypoint.at(0, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> V10DungeonBodyCodec.encode(List.of(longName)));

        List<WaypointGroup> tooManyStringBytes = new ArrayList<>();
        int namedPoints = V10DungeonBodyCodec.MAX_TOTAL_STRING_BYTES
                / V10DungeonBodyCodec.MAX_STRING_BYTES + 1;
        for (int routeIndex = 0; namedPoints > 0; routeIndex++) {
            WaypointGroup route = dungeonRoute("strings-" + routeIndex, "r");
            int count = Math.min(V10DungeonBodyCodec.MAX_WAYPOINTS_PER_ROUTE, namedPoints);
            for (int point = 0; point < count; point++) {
                route.add(Waypoint.at(point, 64, routeIndex)
                        .withName("x".repeat(V10DungeonBodyCodec.MAX_STRING_BYTES)));
            }
            namedPoints -= count;
            tooManyStringBytes.add(route);
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> V10DungeonBodyCodec.encode(tooManyStringBytes))
                .getMessage().contains("aggregate UTF-8"));
    }

    @Test
    void rejectsUnsafeDisplayNamesOnEncodeAndImport() throws Exception {
        for (String invalid : List.of("\u00A7cRed", "line\nbreak", "tab\tbreak")) {
            WaypointGroup invalidRoute = dungeonRoute("crypt-a", invalid);
            invalidRoute.add(Waypoint.at(0, 64, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> V10DungeonBodyCodec.encode(List.of(invalidRoute)), invalid);

            WaypointGroup invalidWaypoint = dungeonRoute("crypt-a", "Route");
            invalidWaypoint.add(Waypoint.at(0, 64, 0).withName(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> V10DungeonBodyCodec.encode(List.of(invalidWaypoint)), invalid);

            String sourceName = "x".repeat(invalid.getBytes(StandardCharsets.UTF_8).length);
            WaypointGroup routeNameSource = dungeonRoute("crypt-a", sourceName);
            routeNameSource.add(Waypoint.at(0, 64, 0));
            assertThrows(IOException.class,
                    () -> V10DungeonBodyCodec.decode(replaceUtf8(
                            V10DungeonBodyCodec.encode(List.of(routeNameSource)),
                            sourceName, invalid)), invalid);

            WaypointGroup waypointNameSource = dungeonRoute("crypt-a", "Route");
            waypointNameSource.add(Waypoint.at(0, 64, 0).withName(sourceName));
            assertThrows(IOException.class,
                    () -> V10DungeonBodyCodec.decode(replaceUtf8(
                            V10DungeonBodyCodec.encode(List.of(waypointNameSource)),
                            sourceName, invalid)), invalid);
        }
    }

    @Test
    void acceptsUnicodeDisplayNames() throws Exception {
        WaypointGroup route = dungeonRoute("crypt-a", "房间 ✨");
        route.add(Waypoint.at(0, 64, 0).withName("宝箱 🧭"));

        List<WaypointGroup> decoded = V10DungeonBodyCodec.decode(
                V10DungeonBodyCodec.encode(List.of(route))).routes();

        assertEquals("房间 ✨", decoded.get(0).name());
        assertEquals("宝箱 🧭", decoded.get(0).get(0).name());
    }

    @Test
    void deterministicRandomDifferentialAndMutationFuzz() throws Exception {
        Random random = new Random(0xD00D_10L);
        for (int trial = 0; trial < 300; trial++) {
            List<WaypointGroup> source = randomRoutes(random);
            String encoded = V10DungeonCodec.encode(source);
            List<WaypointGroup> expected = DungeonRoomShareCodec.decode(
                    DungeonRoomShareCodec.encode(source)).routes();
            assertGroupsEqual(expected, V10DungeonCodec.decode(encoded).routes());
            assertEquals(encoded, V10DungeonCodec.encode(V10DungeonCodec.decode(encoded).routes()));
        }

        byte[] semantic = V10DungeonBodyCodec.encode(edgeRoutes());
        int rejected = 0;
        for (int trial = 0; trial < 1_000; trial++) {
            byte[] mutated = semantic.clone();
            int index = random.nextInt(mutated.length);
            mutated[index] ^= (byte) (1 << random.nextInt(8));
            try {
                V10DungeonBodyCodec.decode(mutated);
            } catch (IOException | IllegalArgumentException expected) {
                rejected++;
            }
        }
        assertTrue(rejected >= 300, "structural parser rejected " + rejected + "/1000 mutations");

        byte[] sealed = V10Transport.seal(V10Transport.MODE_DIRECT, semantic);
        for (int trial = 0; trial < 1_000; trial++) {
            byte[] mutated = sealed.clone();
            int index = random.nextInt(mutated.length);
            mutated[index] ^= (byte) (1 << random.nextInt(8));
            assertThrows(IOException.class,
                    () -> V10Transport.unseal(V10Transport.MODE_DIRECT, mutated));
        }
    }

    private static List<WaypointGroup> edgeRoutes() {
        WaypointGroup first = dungeonRoute("  CRYPT A!! ", "Crypt ✨");
        first.setLoadMode(WaypointGroup.LoadMode.STATIC);
        first.setDefaultRadius(Waypoint.MIN_REACH_RADIUS);
        first.setSkipAheadEnabled(false);
        first.add(new Waypoint(-20, 0, 30, "Chest", Waypoint.DEFAULT_COLOR,
                0, 0.0, Waypoint.TEMP_TIME, 123L,
                -20 * 16, 0 * 16 + 15, 30 * 16 + 8));
        first.add(new Waypoint(-19, 1, 29, "Lever", 0x123456,
                Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET
                        | Integer.MIN_VALUE,
                0.125).withPreciseSixteenths(-19 * 16 + 15, 1 * 16, 29 * 16 + 7));
        first.add(new Waypoint(-18, 1, 28, "Odd color", 0xFF123456,
                0xFFFF_FFFF, Waypoint.MAX_REACH_RADIUS));

        WaypointGroup second = dungeonRoute("boss-room", "Boss");
        second.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        second.setDefaultRadius(Waypoint.MAX_REACH_RADIUS);
        second.setSkipAheadEnabled(true);
        second.add(Waypoint.at(Waypoint.MIN_BLOCK_COORDINATE, 64,
                Waypoint.MAX_BLOCK_COORDINATE));
        second.add(new Waypoint(Waypoint.MAX_BLOCK_COORDINATE, -64,
                Waypoint.MIN_BLOCK_COORDINATE, "End", 0, Waypoint.FLAG_DISABLED, 1.0));
        return List.of(first, second);
    }

    private static List<WaypointGroup> randomRoutes(Random random) {
        int routeCount = 1 + random.nextInt(4);
        List<WaypointGroup> routes = new ArrayList<>();
        for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
            WaypointGroup route = dungeonRoute("Room " + routeIndex, "Route " + routeIndex);
            route.setLoadMode(random.nextBoolean()
                    ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
            route.setSkipAheadEnabled(random.nextBoolean());
            route.setDefaultRadius(random.nextBoolean() ? Waypoint.DEFAULT_REACH_RADIUS
                    : 0.5 + random.nextInt(100) / 2.0);
            int x = random.nextInt(257) - 128;
            int y = random.nextInt(385) - 64;
            int z = random.nextInt(257) - 128;
            int points = 1 + random.nextInt(32);
            for (int point = 0; point < points; point++) {
                x += random.nextInt(15) - 7;
                y += random.nextInt(3) - 1;
                z += random.nextInt(15) - 7;
                int color = random.nextInt(5) == 0 ? Waypoint.DEFAULT_COLOR : random.nextInt(1 << 24);
                int flags = random.nextInt();
                double radius = random.nextInt(4) == 0 ? 0.0 : random.nextInt(401) / 4.0;
                Waypoint waypoint = new Waypoint(x, y, z,
                        random.nextBoolean() ? "" : "P" + point, color, flags, radius);
                if (random.nextBoolean()) {
                    waypoint = waypoint.withPreciseSixteenths(
                            x * 16 + random.nextInt(16),
                            y * 16 + random.nextInt(16),
                            z * 16 + random.nextInt(16));
                }
                route.add(waypoint);
            }
            routes.add(route);
        }
        return routes;
    }

    private static WaypointGroup dungeonRoute(String room, String name) {
        WaypointGroup route = WaypointGroup.create(name, room);
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        return route;
    }

    private static String codeForSemantic(byte[] semantic) {
        return WaypointCodec.MAGIC + V10Transport.encode(V10Transport.MODE_DIRECT,
                V10Transport.seal(V10Transport.MODE_DIRECT, semantic));
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

    private static byte[] rawDeflateAndSeal(byte[] semantic, int level) throws IOException {
        return V10Transport.sealCompressed(semantic,
                rawDeflate(Arrays.copyOfRange(semantic, 1, semantic.length), level));
    }

    private static byte[] replaceUtf8(byte[] input, String source, String replacement) {
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
        assertEquals(sourceBytes.length, replacementBytes.length);
        byte[] result = input.clone();
        int offset = -1;
        search:
        for (int candidate = 0; candidate <= result.length - sourceBytes.length; candidate++) {
            for (int index = 0; index < sourceBytes.length; index++) {
                if (result[candidate + index] != sourceBytes[index]) continue search;
            }
            offset = candidate;
            break;
        }
        assertTrue(offset >= 0, "source name not found in semantic body");
        System.arraycopy(replacementBytes, 0, result, offset, replacementBytes.length);
        return result;
    }

    private static void assertGroupsEqual(List<WaypointGroup> expected,
                                          List<WaypointGroup> actual) {
        assertEquals(expected.size(), actual.size());
        for (int group = 0; group < expected.size(); group++) {
            WaypointGroup left = expected.get(group);
            WaypointGroup right = actual.get(group);
            assertEquals(left.zoneId(), right.zoneId(), "zone@" + group);
            assertEquals(left.name(), right.name(), "name@" + group);
            assertEquals(left.routeKind(), right.routeKind(), "kind@" + group);
            assertEquals(left.gradientMode(), right.gradientMode(), "gradient@" + group);
            assertEquals(left.loadMode(), right.loadMode(), "load@" + group);
            assertEquals(left.defaultRadius(), right.defaultRadius(), "radius@" + group);
            assertEquals(left.skipAheadEnabled(), right.skipAheadEnabled(), "skip@" + group);
            assertEquals(left.waypoints(), right.waypoints(), "waypoints@" + group);
        }
    }
}
