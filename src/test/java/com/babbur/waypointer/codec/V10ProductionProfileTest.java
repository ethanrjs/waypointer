package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locked cross-language and hostile-input gates for the V10 profile. */
class V10ProductionProfileTest {

    private static final String FIXTURE =
            "/fixtures/waypointer-v10-next-no-golomb-goldens.json";

    @Test
    void matchesAllSelectedPythonQuotientAndDeflateGoldens() throws Exception {
        JsonObject fixture = fixture();
        assertEquals("crc-outside+rice+quotient<=1024;golomb-reserved",
                fixture.get("profile").getAsString());
        JsonObject oracle = fixture.getAsJsonObject("oracle");
        assertEquals("1b17d17a2f8e0871b98da8d974a317dbd186153ab83dd081eb1a86429bc5f058",
                oracle.get("candidateSha256").getAsString());
        assertEquals("19ee966ed93d36e96fce91adeb6c02bf19a41bb1bc2c23696f84fdf3ec47c935",
                oracle.get("selectedWireSha256").getAsString());

        int quotient = 0;
        int deflate = 0;
        int maximumQuotientPoints = 0;
        for (var element : fixture.getAsJsonArray("vectors")) {
            JsonObject vector = element.getAsJsonObject();
            String expectedWire = vector.get("wire").getAsString();
            String transport = expectedWire.substring(WaypointCodec.MAGIC.length());
            V10Transport.Frame physical = V10Transport.decode(transport);
            V10Transport.CheckedFrame checked = V10Transport.probe(transport);
            assertArrayEquals(HexFormat.of().parseHex(vector.get("modePayloadHex").getAsString()),
                    physical.payload(), vector.get("routeId").getAsString());
            assertArrayEquals(HexFormat.of().parseHex(vector.get("semanticHex").getAsString()),
                    checked.semantic(), vector.get("routeId").getAsString());
            WaypointGroup decoded = V10BareRouteCodec.decode(checked);
            assertEquals(vector.get("points").getAsInt(), decoded.size());
            assertEquals(vector.get("coordinateSha256").getAsString(), coordinateHash(decoded));
            // A decoded semantic route must select the same canonical Java wire.
            assertEquals(expectedWire, WaypointCodec.MAGIC + V10BareRouteCodec.encode(decoded));

            if (vector.get("mode").getAsString().equals("quotient")) {
                quotient++;
                maximumQuotientPoints = Math.max(maximumQuotientPoints, decoded.size());
                assertEquals(V10Transport.MODE_DIRECT, checked.mode());
                assertEquals(V10BareEntropyCodec.DirectDescriptor.QUOTIENT,
                        V10BareEntropyCodec.descriptor(checked.semantic()));
                assertCoordinates(vector.getAsJsonArray("coordinates"), decoded);
            } else {
                deflate++;
                assertEquals(V10Transport.MODE_DEFLATE, checked.mode());
            }
        }
        assertEquals(21, quotient);
        assertEquals(6, deflate);
        assertEquals(514, maximumQuotientPoints);
    }

    @Test
    void quotientCapPrecedesCombinatorialWorkAndGolombMarkerIsReserved() throws Exception {
        int[][] overCap = new int[V10BareEntropyCodec.MAX_QUOTIENT_WAYPOINTS + 1][3];
        byte[] overCapSemantic = semanticWithMarker(overCap, 1);
        assertEquals(V10BareEntropyCodec.DirectDescriptor.QUOTIENT,
                V10BareEntropyCodec.descriptor(overCapSemantic));
        IOException cap = assertThrows(IOException.class,
                () -> V10BareRouteCodec.decode(new V10Transport.CheckedFrame(
                        V10Transport.MODE_DIRECT, overCapSemantic)));
        assertTrue(cap.getMessage().contains("1024-waypoint limit"), cap.getMessage());

        byte[] reserved = semanticWithMarker(new int[2][3], 0);
        assertEquals(V10BareEntropyCodec.DirectDescriptor.RESERVED_GOLOMB,
                V10BareEntropyCodec.descriptor(reserved));
        IOException rejected = assertThrows(IOException.class,
                () -> V10BareRouteCodec.decode(new V10Transport.CheckedFrame(
                        V10Transport.MODE_DIRECT, reserved)));
        assertTrue(rejected.getMessage().contains("reserved v10 Golomb descriptor"));

        ByteArrayOutputStream invalidParameter = V10BareRouteCodec.semanticPrefix(new int[2][3]);
        V10BareRouteCodec.RiceBitWriter parameterBits =
                new V10BareRouteCodec.RiceBitWriter();
        parameterBits.writeBits(3, 2);
        parameterBits.writeBits(7, 3);
        parameterBits.writeBits(1, 5);
        parameterBits.writeBits(7, 3);
        parameterBits.writeBits(29, 5);
        parameterBits.writeBits(7, 3);
        parameterBits.writeBits(31, 5);
        parameterBits.writeBits(7, 3);
        parameterBits.writeBits(31, 5);
        invalidParameter.writeBytes(parameterBits.finish());
        IOException k29 = assertThrows(IOException.class,
                () -> V10BareRouteCodec.decode(new V10Transport.CheckedFrame(
                        V10Transport.MODE_DIRECT, invalidParameter.toByteArray())));
        assertTrue(k29.getMessage().contains("non-canonical v10 quotient Rice parameter"));
    }

    @Test
    void crcOutsideBoundaryAlternateDeflateBombAndMutationCampaign() throws Exception {
        JsonObject bVector = fixture().getAsJsonArray("vectors").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(vector -> vector.get("mode").getAsString().equals("B"))
                .findFirst().orElseThrow();
        byte[] payload = HexFormat.of().parseHex(bVector.get("modePayloadHex").getAsString());
        byte[] semantic = HexFormat.of().parseHex(bVector.get("semanticHex").getAsString());
        byte[] alternate = alternateDeflatePayload(semantic, Deflater.BEST_SPEED);
        assertFalse(Arrays.equals(payload, alternate));
        assertArrayEquals(semantic, V10Transport.inflateAndVerify(alternate));

        int compressedLength = payload.length - Integer.BYTES;
        byte[] lastDeflate = payload.clone();
        lastDeflate[compressedLength - 1] ^= 1;
        assertBRejected(lastDeflate);
        byte[] lastCrc = payload.clone();
        lastCrc[lastCrc.length - 1] ^= 1;
        assertBRejected(lastCrc);
        assertBRejected(insertBeforeCrc(payload, (byte) 0));
        assertBRejected(Arrays.copyOf(payload, payload.length + 1));
        byte[] concatenated = new byte[compressedLength * 2 + Integer.BYTES];
        System.arraycopy(payload, 0, concatenated, 0, compressedLength);
        System.arraycopy(payload, 0, concatenated, compressedLength, compressedLength);
        System.arraycopy(payload, compressedLength, concatenated,
                compressedLength * 2, Integer.BYTES);
        assertBRejected(concatenated);

        byte[] bomb = new byte[V10Transport.MAX_FRAME_BYTES + 1];
        bomb[0] = V10BareRouteCodec.SEMANTIC_HEADER;
        IOException oversized = assertThrows(IOException.class,
                () -> V10Transport.inflateAndVerify(
                        alternateDeflatePayload(bomb, Deflater.BEST_COMPRESSION)));
        assertTrue(oversized.getMessage().contains("inflated payload exceeds"),
                oversized.getMessage());

        // Deterministic 20k hostile variants across the DEFLATE/trailer boundary.
        for (int index = 0; index < 20_000; index++) {
            byte[] mutation = payload.clone();
            int position = Math.floorMod(index * 2_654_435_761L + 17, mutation.length);
            mutation[position] ^= (byte) (1 << (index & 7));
            assertBRejected(mutation);
        }
    }

    private static byte[] semanticWithMarker(int[][] coordinates, int marker) {
        ByteArrayOutputStream output = V10BareRouteCodec.semanticPrefix(coordinates);
        V10BareRouteCodec.RiceBitWriter bits = new V10BareRouteCodec.RiceBitWriter();
        bits.writeBits(3, 2);
        bits.writeBits(7, 3);
        bits.writeBits(marker, 5);
        output.writeBytes(bits.finish());
        return output.toByteArray();
    }

    private static byte[] alternateDeflatePayload(byte[] semantic, int level)
            throws IOException {
        Deflater deflater = new Deflater(level, true);
        deflater.setInput(semantic);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        byte[] payload = Arrays.copyOf(compressed.toByteArray(),
                compressed.size() + Integer.BYTES);
        CRC32 checksum = new CRC32();
        checksum.update(V10Transport.MODE_DEFLATE);
        checksum.update(semantic);
        long value = checksum.getValue();
        payload[compressed.size()] = (byte) (value >>> 24);
        payload[compressed.size() + 1] = (byte) (value >>> 16);
        payload[compressed.size() + 2] = (byte) (value >>> 8);
        payload[compressed.size() + 3] = (byte) value;
        return payload;
    }

    private static byte[] insertBeforeCrc(byte[] payload, byte value) {
        int compressedLength = payload.length - Integer.BYTES;
        byte[] inserted = new byte[payload.length + 1];
        System.arraycopy(payload, 0, inserted, 0, compressedLength);
        inserted[compressedLength] = value;
        System.arraycopy(payload, compressedLength, inserted, compressedLength + 1,
                Integer.BYTES);
        return inserted;
    }

    private static void assertBRejected(byte[] payload) {
        assertThrows(IOException.class, () -> V10Transport.probe(
                V10Transport.encode(V10Transport.MODE_DEFLATE, payload)));
    }

    private static void assertCoordinates(JsonArray expected, WaypointGroup actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            JsonArray point = expected.get(index).getAsJsonArray();
            assertEquals(point.get(0).getAsInt(), actual.get(index).x());
            assertEquals(point.get(1).getAsInt(), actual.get(index).y());
            assertEquals(point.get(2).getAsInt(), actual.get(index).z());
        }
    }

    private static String coordinateHash(WaypointGroup group) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer point = ByteBuffer.allocate(3 * Integer.BYTES);
        for (Waypoint waypoint : group.waypoints()) {
            point.clear();
            point.putInt(waypoint.x()).putInt(waypoint.y()).putInt(waypoint.z());
            digest.update(point.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static JsonObject fixture() throws IOException {
        var stream = V10ProductionProfileTest.class.getResourceAsStream(FIXTURE);
        if (stream == null) throw new IOException("missing " + FIXTURE);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
