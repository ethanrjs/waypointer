package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in exact V10 selector benchmark against the immutable external corpus. */
class V10LockedCorpusTest {

    @Test
    @EnabledIfSystemProperty(named = "v10.corpus", matches = ".+")
    void lockedCorpusMatchesCrcRiceQuotientPortfolio() throws Exception {
        Path corpus = Path.of(System.getProperty("v10.corpus"));
        long totalWire = 0;
        Map<String, Long> splitWire = new LinkedHashMap<>();
        splitWire.put("train", 0L);
        splitWire.put("validation", 0L);
        splitWire.put("test", 0L);
        Map<String, Integer> modes = new LinkedHashMap<>();
        modes.put("rice", 0);
        modes.put("quotient", 0);
        modes.put("deflate", 0);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int routes = 0;
        int points = 0;

        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                JsonArray coordinates = record.getAsJsonArray("coordinates");
                WaypointGroup group = WaypointGroup.create("", "unknown");
                group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
                for (var element : coordinates) {
                    JsonArray point = element.getAsJsonArray();
                    group.add(Waypoint.at(point.get(0).getAsInt(),
                            point.get(1).getAsInt(), point.get(2).getAsInt()));
                }
                String code = WaypointCodec.encode(
                        List.of(group), WaypointCodec.Options.BARE_COORDINATES);
                WaypointGroup decoded = WaypointCodec.decode(code).getFirst();
                assertEquals(coordinates.size(), decoded.size());
                for (int index = 0; index < decoded.size(); index++) {
                    JsonArray expected = coordinates.get(index).getAsJsonArray();
                    assertEquals(expected.get(0).getAsInt(), decoded.get(index).x());
                    assertEquals(expected.get(1).getAsInt(), decoded.get(index).y());
                    assertEquals(expected.get(2).getAsInt(), decoded.get(index).z());
                }
                String transport = code.substring(WaypointCodec.MAGIC.length());
                V10Transport.CheckedFrame frame = V10Transport.probe(transport);
                String mode;
                if (frame.mode() == V10Transport.MODE_DEFLATE) mode = "deflate";
                else mode = V10BareEntropyCodec.descriptor(frame.semantic())
                        == V10BareEntropyCodec.DirectDescriptor.QUOTIENT
                        ? "quotient" : "rice";
                modes.put(mode, modes.get(mode) + 1);
                byte[] wire = code.getBytes(StandardCharsets.US_ASCII);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(wire.length).array());
                digest.update(wire);
                totalWire += wire.length;
                splitWire.compute(record.get("split").getAsString(),
                        (ignored, value) -> value + wire.length);
                routes++;
                points += coordinates.size();
            }
        }

        assertEquals(302, routes);
        assertEquals(31_418, points);
        assertEquals(76_699, totalWire);
        assertEquals(Map.of("train", 60_891L, "validation", 8_420L, "test", 7_388L),
                splitWire);
        assertEquals(Map.of("rice", 275, "quotient", 21, "deflate", 6), modes);
        String wireDigest = HexFormat.of().formatHex(digest.digest());
        assertEquals("19ee966ed93d36e96fce91adeb6c02bf19a41bb1bc2c23696f84fdf3ec47c935",
                wireDigest);
        System.out.printf("V10 wire=%d splits=%s modes=%s digest=%s%n",
                totalWire, splitWire, modes, wireDigest);
    }
}
