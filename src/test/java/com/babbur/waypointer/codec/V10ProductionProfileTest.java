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
import java.util.ArrayList;
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
        assertEquals(V10GoldenRegeneration.KIND2_PROFILE, fixture.get("profile").getAsString());
        assertEquals(fixture.get("selectedWireSha256").getAsString(),
                V10GoldenRegeneration.selectedWireSha256(fixture.getAsJsonArray("vectors")),
                "vector wires must match the recorded selection digest");

        int quotient = 0;
        int rice = 0;
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

            switch (vector.get("mode").getAsString()) {
                case "quotient" -> {
                    quotient++;
                    maximumQuotientPoints = Math.max(maximumQuotientPoints, decoded.size());
                    assertEquals(V10Transport.MODE_DIRECT, checked.mode());
                    assertEquals(V10BareEntropyCodec.DirectDescriptor.QUOTIENT,
                            V10BareEntropyCodec.descriptor(checked.semantic()));
                    assertCoordinates(vector.getAsJsonArray("coordinates"), decoded);
                }
                case "rice" -> {
                    rice++;
                    assertEquals(V10Transport.MODE_DIRECT, checked.mode());
                    assertEquals(V10BareEntropyCodec.DirectDescriptor.RICE,
                            V10BareEntropyCodec.descriptor(checked.semantic()));
                }
                default -> {
                    deflate++;
                    assertEquals(V10Transport.MODE_DEFLATE, checked.mode());
                }
            }
        }
        assertEquals(20, quotient);
        assertEquals(1, rice);
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

        // payload = header byte, compressed bytes, two checksum bytes.
        int compressedLength = payload.length - 1 - V10Transport.CHECKSUM_BYTES;
        byte[] lastDeflate = payload.clone();
        lastDeflate[1 + compressedLength / 2] ^= 1;
        assertBRejected(lastDeflate);
        byte[] lastCrc = payload.clone();
        lastCrc[lastCrc.length - 1] ^= 1;
        assertBRejected(lastCrc);
        byte[] modeFlip = payload.clone();
        modeFlip[0] ^= (byte) V10Transport.HEADER_MODE_BIT;
        assertBRejected(modeFlip);
        assertBRejected(insertBeforeCrc(payload, (byte) 0));
        assertBRejected(Arrays.copyOf(payload, payload.length + 1));
        byte[] concatenated = new byte[1 + compressedLength * 2 + V10Transport.CHECKSUM_BYTES];
        System.arraycopy(payload, 0, concatenated, 0, 1 + compressedLength);
        System.arraycopy(payload, 1, concatenated, 1 + compressedLength, compressedLength);
        System.arraycopy(payload, 1 + compressedLength, concatenated,
                1 + compressedLength * 2, V10Transport.CHECKSUM_BYTES);
        assertBRejected(concatenated);

        // A body one byte past the frame limit must be refused by the bounded
        // inflater before the checksum is even consulted.
        byte[] bombBody = new byte[V10Transport.MAX_FRAME_BYTES + 1];
        byte[] bombCompressed = rawDeflate(bombBody, Deflater.BEST_COMPRESSION);
        byte[] bombPayload = new byte[1 + bombCompressed.length + V10Transport.CHECKSUM_BYTES];
        bombPayload[0] = (byte) (V10BareRouteCodec.SEMANTIC_HEADER | V10Transport.HEADER_MODE_BIT);
        System.arraycopy(bombCompressed, 0, bombPayload, 1, bombCompressed.length);
        IOException oversized = assertThrows(IOException.class,
                () -> V10Transport.inflateAndVerify(bombPayload));
        assertTrue(oversized.getMessage().contains("inflated payload exceeds"),
                oversized.getMessage());

        // Deterministic 20k hostile variants across the header/DEFLATE/trailer
        // boundary. The guarantee is that no single-bit flip yields a different
        // accepted body. A flip inside DEFLATE padding bits leaves the body
        // unchanged and is legitimately accepted; anything else must be refused.
        List<String> silentlyChanged = new ArrayList<>();
        List<String> acceptedOutsidePadding = new ArrayList<>();
        int lastCompressedByte = payload.length - 1 - V10Transport.CHECKSUM_BYTES;
        for (int index = 0; index < 20_000; index++) {
            byte[] mutation = payload.clone();
            int position = Math.floorMod(index * 2_654_435_761L + 17, mutation.length);
            mutation[position] ^= (byte) (1 << (index & 7));
            byte[] accepted;
            try {
                accepted = V10Transport.probe(V10Transport.encode(mutation)).semantic();
            } catch (IOException rejected) {
                continue;
            }
            if (!Arrays.equals(accepted, semantic)) {
                silentlyChanged.add("mutation " + index + " at byte " + position);
            } else if (position != lastCompressedByte) {
                acceptedOutsidePadding.add("mutation " + index + " at byte " + position);
            }
        }
        assertEquals(List.of(), silentlyChanged);
        assertEquals(List.of(), acceptedOutsidePadding,
                "only flips in the final DEFLATE byte's padding bits may be accepted");
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
        return V10Transport.sealCompressed(semantic,
                rawDeflate(Arrays.copyOfRange(semantic, 1, semantic.length), level));
    }

    private static byte[] rawDeflate(byte[] input, int level) {
        Deflater deflater = new Deflater(level, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return compressed.toByteArray();
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

    private static void assertBRejected(byte[] payload) {
        assertThrows(IOException.class, () -> V10Transport.probe(V10Transport.encode(payload)));
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
