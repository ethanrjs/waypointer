package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opt-in same-JVM V10 microbenchmark; values are evidence, never CI thresholds. */
class V10ProductionTimingTest {

    @Test
    @EnabledIfSystemProperty(named = "v10.timing", matches = "true")
    void selectedFramesAndTwentyThousandHostileFrames() throws Exception {
        JsonObject fixture;
        try (var stream = V10ProductionTimingTest.class.getResourceAsStream(
                "/fixtures/waypointer-v10-next-no-golomb-goldens.json")) {
            if (stream == null) throw new IOException("missing V10 golden fixture");
            fixture = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> transports = new ArrayList<>();
        List<WaypointGroup> groups = new ArrayList<>();
        byte[] hostileSeed = null;
        for (var element : fixture.getAsJsonArray("vectors")) {
            JsonObject vector = element.getAsJsonObject();
            String transport = vector.get("wire").getAsString()
                    .substring(WaypointCodec.MAGIC.length());
            transports.add(transport);
            groups.add(V10BareRouteCodec.decode(transport));
            if (hostileSeed == null && vector.get("mode").getAsString().equals("B")) {
                hostileSeed = HexFormat.of().parseHex(
                        vector.get("modePayloadHex").getAsString());
            }
        }

        for (int warmup = 0; warmup < 10; warmup++) {
            for (int index = 0; index < groups.size(); index++) {
                V10BareRouteCodec.encode(groups.get(index));
                V10BareRouteCodec.decode(transports.get(index));
            }
        }
        long[] encode = new long[groups.size() * 40];
        long[] decode = new long[encode.length];
        int sample = 0;
        for (int repetition = 0; repetition < 40; repetition++) {
            for (int index = 0; index < groups.size(); index++) {
                long start = System.nanoTime();
                V10BareRouteCodec.encode(groups.get(index));
                encode[sample] = System.nanoTime() - start;
                start = System.nanoTime();
                V10BareRouteCodec.decode(transports.get(index));
                decode[sample] = System.nanoTime() - start;
                sample++;
            }
        }

        long[] hostile = new long[20_000];
        for (int index = 0; index < hostile.length; index++) {
            byte[] mutation = hostileSeed.clone();
            int position = Math.floorMod(index * 2_654_435_761L + 17, mutation.length);
            mutation[position] ^= (byte) (1 << (index & 7));
            String transport = V10Transport.encode(mutation);
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> V10Transport.probe(transport));
            hostile[index] = System.nanoTime() - start;
        }
        System.out.printf("V10 timing selected=%d encode=%s decode=%s hostile20k=%s%n",
                encode.length, summary(encode), summary(decode), summary(hostile));
    }

    private static String summary(long[] nanos) {
        Arrays.sort(nanos);
        return String.format("p50=%.3f,p95=%.3f,max=%.3fms",
                nanos[nanos.length / 2] / 1_000_000.0,
                nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000.0,
                nanos[nanos.length - 1] / 1_000_000.0);
    }
}
