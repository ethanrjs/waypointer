package com.babbur.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointCodecGoldenVectorGeneratorTest {

    @Test
    void generatedFixtureIsSelfConsistent() throws Exception {
        String generated = WaypointCodecGoldenVectorGenerator.generateJson();
        JsonObject root = JsonParser.parseString(generated).getAsJsonObject();

        assertEquals("waypointer-native-codec-golden-vectors",
                root.get("format").getAsString());
        assertEquals(1, root.get("schema").getAsInt());
        assertEquals(WaypointCodec.MAGIC, root.get("wireMagic").getAsString());

        JsonObject dictionaries = root.getAsJsonObject("dictionaries");
        assertDictionary(dictionaries.getAsJsonObject("legacyV1ToV8"),
                CodecDictionary.BYTES,
                WaypointCodecGoldenVectorGenerator.LEGACY_DICTIONARY_SHA256);
        assertDictionary(dictionaries.getAsJsonObject("v9"),
                V9CodecDictionary.BYTES,
                V9CodecDictionary.EXPECTED_SHA256);

        Set<Integer> versions = new HashSet<>();
        Set<Integer> v9Kinds = new HashSet<>();
        Set<String> ids = new HashSet<>();
        JsonArray vectors = root.getAsJsonArray("vectors");
        assertEquals(12, vectors.size());
        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            String id = vector.get("id").getAsString();
            assertTrue(ids.add(id), "duplicate vector id " + id);

            int version = vector.get("wireVersion").getAsInt();
            versions.add(version);
            String code = vector.get("code").getAsString();
            DecodeDebug debug = WaypointCodec.debugDecode(code);
            assertEquals(version, debug.version(), id);
            assertEquals(vector.getAsJsonObject("decoded"),
                    WaypointCodecGoldenVectorGenerator.decodedSemantics(code), id);
            assertTrue(vector.get("compressedHex").getAsString().matches("[0-9a-f]+"), id);
            assertTrue(vector.get("bodyHex").getAsString().matches("[0-9a-f]+"), id);

            JsonElement kind = vector.get("v9ContentKind");
            if (version == 9) {
                assertTrue(kind.isJsonPrimitive(), id);
                int expectedKind = kind.getAsInt();
                v9Kinds.add(expectedKind);
                assertEquals(expectedKind, WaypointCodec.v9ContentKind(debug.headerByte()), id);
                assertTrue(vector.get("crc32Hex").getAsString().matches("[0-9a-f]{8}"), id);
            } else {
                assertTrue(kind.isJsonNull(), id);
                if (version == 8) {
                    assertTrue(vector.get("crc32Hex").getAsString().matches("[0-9a-f]{8}"), id);
                } else {
                    assertTrue(vector.get("crc32Hex").isJsonNull(), id);
                }
            }
        }

        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), versions);
        assertEquals(Set.of(
                WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE,
                WaypointCodec.V9_CONTENT_KIND_COMPACT_ROUTE,
                WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE,
                WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META), v9Kinds);

        WaypointCodecGoldenVectorGenerator.write(
                Path.of("build", "generated-test-resources",
                        "waypointer-native-golden-vectors.json"), generated);
    }

    @Test
    void committedFixtureMatchesGeneratorExactly() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                WaypointCodecGoldenVectorGenerator.FIXTURE_RESOURCE)) {
            assertNotNull(input, "missing committed native codec golden fixture");
            String committed = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(committed, WaypointCodecGoldenVectorGenerator.generateJson());
        }
    }

    private static void assertDictionary(JsonObject actual, byte[] bytes, String expectedHash)
            throws Exception {
        assertEquals(bytes.length, actual.get("bytes").getAsInt());
        assertEquals(expectedHash, actual.get("sha256").getAsString());
        assertEquals(expectedHash, WaypointCodecGoldenVectorGenerator.sha256(bytes));
    }
}
