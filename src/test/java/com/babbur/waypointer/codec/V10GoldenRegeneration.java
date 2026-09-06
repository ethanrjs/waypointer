package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.V10ConfigBodyCodec;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.Deflater;

/** Regenerates wire fixtures with {@code -Pv10.regen=<repo root>}. Kind 2 preserves recorded
 * coordinates; kind 3 uses {@link V10ConfigCodecTest#fixtures()}. Review every wire change. */
class V10GoldenRegeneration {

    static final String KIND2_PROFILE =
            "header-mode+crc16-outside+rice+quotient<=1024;golomb-reserved";
    static final String KIND2_FIXTURE = "/fixtures/waypointer-v10-next-no-golomb-goldens.json";
    static final String CONFIG_FIXTURE = "/fixtures/waypointer-v10-config-golden-vectors.json";

    @Test
    @EnabledIfSystemProperty(named = "v10.regen", matches = ".+")
    void regenerate() throws Exception {
        Path root = Path.of(System.getProperty("v10.regen"));
        Path fixtures = root.resolve("src/test/resources/fixtures");

        JsonObject kind2 = readJson(KIND2_FIXTURE).getAsJsonObject();
        JsonArray vectors = kind2.getAsJsonArray("vectors");
        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            int mode = vector.get("mode").getAsString().equals("B")
                    ? V10Transport.MODE_DEFLATE : V10Transport.MODE_DIRECT;
            byte[] semantic = HexFormat.of().parseHex(vector.get("semanticHex").getAsString());
            WaypointGroup route = V10BareRouteCodec.decode(
                    new V10Transport.CheckedFrame(mode, semantic));
            String wire = WaypointCodec.MAGIC + V10BareRouteCodec.encode(route);
            String transport = wire.substring(WaypointCodec.MAGIC.length());
            V10Transport.Frame physical = V10Transport.decode(transport);
            V10Transport.CheckedFrame checked = V10Transport.probe(transport);
            String newMode = checked.mode() == V10Transport.MODE_DEFLATE ? "B"
                    : V10BareEntropyCodec.descriptor(checked.semantic())
                            .name().toLowerCase(Locale.ROOT);
            vector.addProperty("mode", newMode);
            vector.addProperty("modePayloadHex", HexFormat.of().formatHex(physical.payload()));
            vector.addProperty("semanticHex", HexFormat.of().formatHex(checked.semantic()));
            vector.addProperty("wire", wire);
        }
        JsonObject rebuilt = new JsonObject();
        rebuilt.addProperty("profile", KIND2_PROFILE);
        rebuilt.addProperty("selectedWireSha256", selectedWireSha256(vectors));
        rebuilt.add("vectors", vectors);
        Files.writeString(fixtures.resolve("waypointer-v10-next-no-golomb-goldens.json"),
                new GsonBuilder().create().toJson(rebuilt), StandardCharsets.UTF_8);

        JsonArray config = new JsonArray();
        for (V10ConfigCodecTest.NamedConfig fixture : V10ConfigCodecTest.fixtures()) {
            byte[] semantic = V10ConfigBodyCodec.encode(fixture.config());
            String wire = UniversalShareCodec.encodeConfig(fixture.config());
            byte[] direct = V10Transport.seal(V10Transport.MODE_DIRECT, semantic);
            String aWire = WaypointCodec.MAGIC + V10Transport.encode(direct);
            byte[] defaultDeflate = V10Transport.deflateAndSeal(semantic, Deflater.DEFAULT_STRATEGY);
            byte[] filteredDeflate = V10Transport.deflateAndSeal(semantic, Deflater.FILTERED);
            V10Transport.Outbound best = new V10Transport.Outbound(
                    V10Transport.MODE_DEFLATE, defaultDeflate);
            V10Transport.Outbound filtered = new V10Transport.Outbound(
                    V10Transport.MODE_DEFLATE, filteredDeflate);
            if (filtered.compareTo(best) < 0) best = filtered;
            String bWire = WaypointCodec.MAGIC + best.transport();
            JsonObject vector = new JsonObject();
            vector.addProperty("id", fixture.name());
            vector.addProperty("currentWpcChars",
                    WaypointerConfigCodec.encode(fixture.config()).length());
            vector.addProperty("semanticBytes", semantic.length);
            vector.addProperty("v10AChars", aWire.length());
            vector.addProperty("v10BCompressedBytes",
                    best.payload().length - 1 - V10Transport.CHECKSUM_BYTES);
            vector.addProperty("v10BPayloadBytes", best.payload().length);
            vector.addProperty("v10BChars", bWire.length());
            vector.addProperty("v10Chars", wire.length());
            vector.addProperty("mode", wire.equals(aWire) ? "A" : "B");
            vector.addProperty("semanticHex", HexFormat.of().formatHex(semantic));
            vector.addProperty("aWire", aWire);
            vector.addProperty("bWire", bWire);
            vector.addProperty("wire", wire);
            config.add(vector);
        }
        Files.writeString(fixtures.resolve("waypointer-v10-config-golden-vectors.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(config),
                StandardCharsets.UTF_8);
    }

    /** SHA-256 over each vector's wire, prefixed by its big-endian four-byte length. */
    static String selectedWireSha256(JsonArray vectors) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (JsonElement element : vectors) {
            byte[] wire = element.getAsJsonObject().get("wire").getAsString()
                    .getBytes(StandardCharsets.US_ASCII);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(wire.length).array());
            digest.update(wire);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static JsonElement readJson(String resource) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                V10GoldenRegeneration.class.getResourceAsStream(resource),
                StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }
}
