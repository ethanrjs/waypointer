package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.V10ConfigBodyCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V10ConfigCodecTest {

    @Test
    void universalWriterUsesKindThreeAndRoundTripsDefaultLightHeavyAndEdges() throws Exception {
        for (NamedConfig fixture : fixtures()) {
            String code = UniversalShareCodec.encodeConfig(fixture.config());
            assertTrue(code.startsWith("WP:"), fixture.name());
            V10Transport.CheckedFrame frame = V10Transport.probe(
                    code.substring(WaypointCodec.MAGIC.length()));
            assertEquals(V10ConfigBodyCodec.CONTENT_KIND, frame.contentKind(), fixture.name());
            UniversalShareCodec.Configuration decoded = assertInstanceOf(
                    UniversalShareCodec.Configuration.class, UniversalShareCodec.decode(code));
            assertSameShareableConfig(fixture.config(), decoded.config());
            assertEquals(code, UniversalShareCodec.encodeConfig(decoded.config()), fixture.name());
        }
        assertEquals(V10Transport.MODE_DIRECT,
                probeMode(UniversalShareCodec.encodeConfig(new WaypointerConfig())));
        assertEquals(V10Transport.MODE_DEFLATE,
                probeMode(UniversalShareCodec.encodeConfig(heavyConfig())));
    }

    static int probeMode(String code) throws IOException {
        return V10Transport.probe(code.substring(WaypointCodec.MAGIC.length())).mode();
    }

    @Test
    void matchesCrossLanguageGoldenVectors() throws Exception {
        JsonArray vectors;
        try (InputStreamReader reader = new InputStreamReader(
                V10ConfigCodecTest.class.getResourceAsStream(
                        "/fixtures/waypointer-v10-config-golden-vectors.json"),
                StandardCharsets.UTF_8)) {
            vectors = JsonParser.parseReader(reader).getAsJsonArray();
        }
        assertEquals(fixtures().size(), vectors.size());
        for (int index = 0; index < vectors.size(); index++) {
            NamedConfig fixture = fixtures().get(index);
            JsonObject vector = vectors.get(index).getAsJsonObject();
            String wire = UniversalShareCodec.encodeConfig(fixture.config());
            V10Transport.CheckedFrame frame = V10Transport.probe(
                    wire.substring(WaypointCodec.MAGIC.length()));
            byte[] semantic = frame.semantic();
            String direct = codeFor(V10Transport.MODE_DIRECT,
                    V10Transport.seal(V10Transport.MODE_DIRECT, semantic));
            DeflateCandidate defaultDeflate = deflateCandidate(semantic,
                    Deflater.DEFAULT_STRATEGY);
            DeflateCandidate filteredDeflate = deflateCandidate(semantic,
                    Deflater.FILTERED);
            DeflateCandidate bestDeflate = defaultDeflate.compareTo(filteredDeflate) <= 0
                    ? defaultDeflate : filteredDeflate;
            assertEquals(fixture.name(), vector.get("id").getAsString());
            assertEquals(vector.get("wire").getAsString(), wire, fixture.name());
            assertEquals(vector.get("v10Chars").getAsInt(), wire.length(), fixture.name());
            assertEquals(vector.get("semanticBytes").getAsInt(), semantic.length, fixture.name());
            assertEquals(vector.get("v10AChars").getAsInt(), direct.length(), fixture.name());
            assertEquals(vector.get("aWire").getAsString(), direct, fixture.name());
            assertEquals(vector.get("v10BCompressedBytes").getAsInt(),
                    bestDeflate.compressedBytes(), fixture.name());
            assertEquals(vector.get("v10BPayloadBytes").getAsInt(),
                    bestDeflate.payload().length, fixture.name());
            assertEquals(vector.get("v10BChars").getAsInt(),
                    bestDeflate.wire().length(), fixture.name());
            assertEquals(vector.get("bWire").getAsString(), bestDeflate.wire(), fixture.name());
            assertEquals(vector.get("currentWpcChars").getAsInt(),
                    WaypointerConfigCodec.encode(fixture.config()).length(), fixture.name());
            assertEquals(vector.get("mode").getAsString(),
                    frame.mode() == V10Transport.MODE_DIRECT ? "A" : "B", fixture.name());
            assertEquals(vector.get("semanticHex").getAsString(),
                    HexFormat.of().formatHex(frame.semantic()), fixture.name());
            assertSameShareableConfig(fixture.config(), decodedConfig(vector.get("wire").getAsString()));
        }
    }

    @Test
    void acceptsNonwinningModesAndAlternateValidRawDeflate() throws Exception {
        WaypointerConfig heavy = heavyConfig();
        byte[] heavySemantic = V10ConfigBodyCodec.encode(heavy);
        String nonwinningA = codeFor(V10Transport.MODE_DIRECT,
                V10Transport.seal(V10Transport.MODE_DIRECT, heavySemantic));
        assertSameShareableConfig(heavy, decodedConfig(nonwinningA));

        WaypointerConfig defaults = new WaypointerConfig();
        byte[] defaultSemantic = V10ConfigBodyCodec.encode(defaults);
        String nonwinningB = codeFor(V10Transport.MODE_DEFLATE,
                alternateDeflatePayload(defaultSemantic, 1));
        assertSameShareableConfig(defaults, decodedConfig(nonwinningB));

        String alternateLevelOne = codeFor(V10Transport.MODE_DEFLATE,
                alternateDeflatePayload(heavySemantic, 1));
        assertSameShareableConfig(heavy, decodedConfig(alternateLevelOne));
    }

    @Test
    void skipsBoundedUnknownFieldsButRejectsNoncanonicalKnownFields() throws Exception {
        byte[] unknown = semanticWithField(100, new byte[] {1, 2, 3});
        assertSameShareableConfig(new WaypointerConfig(), decodedConfig(directCode(unknown)));

        // Tag 2 defaults to true and therefore must be omitted.
        byte[] explicitDefault = semanticWithField(2, new byte[] {1});
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(explicitDefault)));

        // Tag 30 is a shortest unsigned integer. 0x80 0x00 is redundant.
        byte[] nonShortestInteger = semanticWithField(30, new byte[] {(byte) 0x80, 0});
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(nonShortestInteger)));

        // Tag 4 is RGB and must use exactly three bytes.
        byte[] shortRgb = semanticWithField(4, new byte[] {1});
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(shortRgb)));

        // Tag 37: count=1, length=1, malformed UTF-8 byte.
        byte[] malformedUtf8 = semanticWithField(37,
                new byte[] {1, 1, (byte) 0xFF});
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(malformedUtf8)));

        WaypointerConfig malformedJavaText = new WaypointerConfig();
        malformedJavaText.addChatCoordSenderBlacklist("\uD800");
        assertThrows(IllegalStateException.class,
                () -> UniversalShareCodec.encodeConfig(malformedJavaText));
    }

    @Test
    void enforcesCanonicalTokensExactEofAndResourceBounds() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(new byte[] {0x3A, 0})));
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(
                        new byte[] {0x3A, (byte) 0x80, 0})));

        ByteArrayOutputStream tooMany = new ByteArrayOutputStream();
        tooMany.write(V10ConfigBodyCodec.SEMANTIC_HEADER);
        for (int tag = 1_000; tag < 1_257; tag++) {
            writeUVarint(tooMany, ((long) (tag == 1_000 ? 1_000 : 1) << 2));
            tooMany.write(0);
        }
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(tooMany.toByteArray())));

        ByteArrayOutputStream tooLong = new ByteArrayOutputStream();
        tooLong.write(V10ConfigBodyCodec.SEMANTIC_HEADER);
        writeUVarint(tooLong, (100L << 2) | 3);
        writeUVarint(tooLong, V10ConfigBodyCodec.MAX_FIELD_BYTES + 1L);
        tooLong.writeBytes(new byte[V10ConfigBodyCodec.MAX_FIELD_BYTES + 1]);
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(tooLong.toByteArray())));

        byte[] tooManyStrings = {(byte) 0x81, 0x02}; // shortest uvarint for 257
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(
                        semanticWithField(37, tooManyStrings))));

        byte[] tooLongString = new byte[2 + V10ConfigBodyCodec.MAX_STRING_BYTES + 1];
        tooLongString[0] = 1;
        tooLongString[1] = (byte) (V10ConfigBodyCodec.MAX_STRING_BYTES + 1);
        Arrays.fill(tooLongString, 2, tooLongString.length, (byte) 'x');
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(
                        semanticWithField(37, tooLongString))));

        byte[] oversizedBody = new byte[V10ConfigBodyCodec.MAX_BODY_BYTES + 1];
        oversizedBody[0] = V10ConfigBodyCodec.SEMANTIC_HEADER;
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(oversizedBody)));
    }

    @Test
    void committedKindThreeFailuresAndTypedMismatchesAreTerminal() throws Exception {
        byte[] invalidKindThree = {0x3A, 0};
        IllegalArgumentException committed = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(directCode(invalidKindThree)));
        assertTrue(committed.getMessage().contains("v10 config"));

        WaypointGroup route = WaypointGroup.create("", "unknown");
        route.add(Waypoint.at(1, 2, 3));
        String kindTwo = WaypointCodec.encode(List.of(route), WaypointCodec.Options.BARE_COORDINATES);
        IOException mismatch = assertThrows(IOException.class, () -> V10ConfigCodec.decode(kindTwo));
        assertTrue(mismatch.getMessage().contains("kind 3"));

        String config = UniversalShareCodec.encodeConfig(new WaypointerConfig());
        IllegalArgumentException routeOnly = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(config));
        assertTrue(routeOnly.getMessage().contains("v10="));
        assertFalse(routeOnly.getMessage().contains("; v9="));
    }

    @Test
    void legacyWpcVersionsOneThroughFiveRemainImportOnlyCompatible() throws Exception {
        for (int version = 1; version <= 5; version++) {
            String wpc = legacyWpc((byte) version, (byte) 0);
            UniversalShareCodec.Configuration decoded = assertInstanceOf(
                    UniversalShareCodec.Configuration.class, UniversalShareCodec.decode(wpc));
            assertEquals(version <= 4, decoded.config().exportIncludeNames(), "WPC v" + version);
        }
        String current = WaypointerConfigCodec.encode(lightConfig());
        assertTrue(current.startsWith("WPC:"));
        assertSameShareableConfig(lightConfig(), decodedConfig(current));
        assertTrue(UniversalShareCodec.encodeConfig(lightConfig()).startsWith("WP:"));
    }

    static List<NamedConfig> fixtures() {
        return List.of(new NamedConfig("default", new WaypointerConfig()),
                new NamedConfig("light", lightConfig()),
                new NamedConfig("heavy", heavyConfig()),
                new NamedConfig("edges", edgeConfig()));
    }

    private static WaypointerConfig lightConfig() {
        WaypointerConfig config = new WaypointerConfig();
        config.setDefaultReachRadius(5.5);
        config.setTracerColor(0x12ABEF);
        config.setTracerOpacity(0.72);
        config.setTracerThickness(2.25);
        config.setShowRouteProgress(true);
        config.setShowRouteLines(true);
        config.setRouteLineColor(0x55CC88);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.CURRENT);
        config.setChatCoordDetection(false);
        config.addChatCoordSenderBlacklist("PartyBot");
        config.addChatCoordSenderBlacklist("Düngéon");
        config.setTempDefaultDurationSec(90);
        return config;
    }

    private static WaypointerConfig heavyConfig() {
        WaypointerConfig config = new WaypointerConfig();
        config.disableAllSettings();
        // Keep the pre-Crystal-Hollows golden fixture stable; those fields have a dedicated
        // all-nine-tags round-trip test in WaypointerConfigTest.
        config.setCrystalHollowsEnabled(true);
        config.setCrystalHollowsStructureWaypoints(true);
        config.setCrystalHollowsShowRoughMarkers(true);
        config.setCrystalHollowsEntityDetection(true);
        config.setCrystalHollowsChatDetection(true);
        config.setCrystalHollowsWishingCompassSolver(true);
        config.setCrystalHollowsCompassRays(true);
        config.setCrystalHollowsAnnounceDetections(true);
        config.setDefaultReachRadius(8.75);
        config.setDefaultWaypointColor(0x123456);
        config.setTracerColor(0xFEDCBA);
        config.setTracerOpacity(0.41);
        config.setTracerThickness(7.25);
        config.setWaypointOutlineThickness(2.5);
        config.setWaypointMarkerScale(1.75);
        config.setWaypointOutlineOpacity(0.66);
        config.setWaypointOutlineColor(0xABCDEF);
        config.setLabelScale(1.4);
        config.setHideWaypointsNearRadius(12.25);
        config.setHideWaypointLabelsNearRadius(9.5);
        config.setRouteLineColor(0xCC4400);
        config.setDungeonEntryPathColor(0x22EE77);
        config.setMaxWaypointLabels(7);
        config.setMaxStaticWaypointRenderDistance(197.5);
        config.setLabelHeightOffset(-1.25);
        config.setBoxStyle(WaypointerConfig.BoxStyle.PAINT);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        config.setImportedRouteColorMode(WaypointGroup.GradientMode.AUTO);
        config.setImportedRouteDefaultColor(0x334455);
        config.setSequencePreviousWaypointCount(11);
        config.setSequenceNextWaypointCount(13);
        config.setTempDefaultMode(Waypoint.TEMP_TIME);
        config.setTempDefaultDurationSec(43_210);
        config.setShowRouteProgress(true);
        config.setShowRouteLines(true);
        config.setUseEtherwarpHeight(true);
        config.setShowDungeonEntryPathToFirstWaypoint(true);
        config.setShowDungeonEntryPathToFollowingWaypoints(true);
        config.setBeaconBeamExtendsBelowWaypoint(true);
        config.setEtherwarpAlignmentSound(WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE);
        for (int index = 0; index < 20; index++) {
            config.addChatCoordSenderBlacklist("Blocked" + index);
        }
        return config;
    }

    private static WaypointerConfig edgeConfig() {
        WaypointerConfig config = new WaypointerConfig();
        config.setDefaultReachRadius(Double.POSITIVE_INFINITY);
        config.setTracerOpacity(-0.0);
        config.setMaxWaypointLabels(Integer.MAX_VALUE);
        config.setSequencePreviousWaypointCount(Integer.MAX_VALUE);
        config.setSequenceNextWaypointCount(Integer.MAX_VALUE);
        config.setTempDefaultDurationSec(Integer.MAX_VALUE);
        config.setDefaultWaypointColor(0xFFABCDEF);
        config.addChatCoordSenderBlacklist("雪だるま");
        return config;
    }

    private static WaypointerConfig decodedConfig(String code) {
        return assertInstanceOf(UniversalShareCodec.Configuration.class,
                UniversalShareCodec.decode(code)).config();
    }

    private static void assertSameShareableConfig(WaypointerConfig expected,
                                                   WaypointerConfig actual) {
        assertEquals(WaypointerConfigCodec.encode(expected), WaypointerConfigCodec.encode(actual));
    }

    private static byte[] semanticWithField(int tag, byte[] value) {
        ByteArrayOutputStream semantic = new ByteArrayOutputStream();
        semantic.write(V10ConfigBodyCodec.SEMANTIC_HEADER);
        int lengthClass = value.length == 1 ? 0 : value.length == 3 ? 1 : value.length == 8 ? 2 : 3;
        writeUVarint(semantic, ((long) tag << 2) | lengthClass);
        if (lengthClass == 3) writeUVarint(semantic, value.length);
        semantic.writeBytes(value);
        return semantic.toByteArray();
    }

    private static String directCode(byte[] semantic) {
        return codeFor(V10Transport.MODE_DIRECT,
                V10Transport.seal(V10Transport.MODE_DIRECT, semantic));
    }

    private static String codeFor(int mode, byte[] payload) {
        return WaypointCodec.MAGIC + V10Transport.encode(mode, payload);
    }

    private static byte[] rawDeflate(byte[] input, int level) {
        Deflater deflater = new Deflater(level, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return output.toByteArray();
    }

    private static DeflateCandidate deflateCandidate(byte[] input, int strategy)
            throws IOException {
        byte[] payload = V10Transport.deflateAndSeal(input, strategy);
        return new DeflateCandidate(codeFor(V10Transport.MODE_DEFLATE, payload),
                payload.length - 1 - V10Transport.CHECKSUM_BYTES, payload);
    }

    private static byte[] alternateDeflatePayload(byte[] semantic, int level) throws IOException {
        return V10Transport.sealCompressed(semantic,
                rawDeflate(Arrays.copyOfRange(semantic, 1, semantic.length), level));
    }

    private static String legacyWpc(byte... raw) throws IOException {
        return WaypointerConfigCodec.MAGIC
                + AsciiStreamCodec.encode(rawDeflate(raw, Deflater.BEST_COMPRESSION));
    }

    private static void writeUVarint(ByteArrayOutputStream output, long value) {
        do {
            int next = (int) value & 0x7F;
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    record NamedConfig(String name, WaypointerConfig config) {}

    private record DeflateCandidate(String wire, int compressedBytes, byte[] payload)
            implements Comparable<DeflateCandidate> {
        @Override
        public int compareTo(DeflateCandidate other) {
            int compared = Integer.compare(wire.length(), other.wire.length());
            if (compared != 0) return compared;
            compared = Integer.compare(payload.length, other.payload.length);
            if (compared != 0) return compared;
            for (int index = 0; index < payload.length; index++) {
                compared = Integer.compare(payload[index] & 0xFF,
                        other.payload[index] & 0xFF);
                if (compared != 0) return compared;
            }
            return 0;
        }
    }
}
