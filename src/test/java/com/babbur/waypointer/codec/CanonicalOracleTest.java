package com.babbur.waypointer.codec;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Developer oracle for the catalog's canonical wire-v9 payloads, for checking other encoders
 * (the website's converter, the catalog worker) against the mod byte for byte. Runs only when
 * asked:
 *
 * <pre>WAYPOINTER_CANONICAL_IN=routes.txt WAYPOINTER_CANONICAL_OUT=canonical.json \
 *   ./gradlew test --tests '*CanonicalOracleTest'</pre>
 *
 * The input has one route code per line (any format Waypointer imports). The output lists, per
 * line, the input and the exact payload the mod's catalog check accepts for the same content.
 */
@EnabledIfEnvironmentVariable(named = "WAYPOINTER_CANONICAL_IN", matches = ".+")
class CanonicalOracleTest {
    @Test
    void writeCanonicalPayloads() throws Exception {
        Path in = Path.of(System.getenv("WAYPOINTER_CANONICAL_IN"));
        String outName = System.getenv("WAYPOINTER_CANONICAL_OUT");
        Path out = Path.of(outName == null || outName.isBlank() ? in + ".canonical.json" : outName);
        JsonArray results = new JsonArray();
        for (String line : Files.readAllLines(in)) {
            if (line.isBlank()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("input", line.strip());
            try {
                // Waypointer codes decode as they are, as the catalog check does; other formats
                // go through the importer the converter mirrors.
                String code = line.strip();
                java.util.List<com.babbur.waypointer.core.WaypointGroup> groups;
                String label = "";
                if (code.startsWith(WaypointCodec.MAGIC)) {
                    WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(code);
                    groups = decoded.groups();
                    label = decoded.label();
                } else {
                    groups = WaypointImporter.importAny(code).groups();
                }
                String canonical = WaypointCodec.encodeV9ForTest(groups,
                        WaypointCodec.Options.FULL_FIDELITY.toBuilder().label(label).build());
                WaypointCodec.decodeCanonicalV9(canonical);
                entry.addProperty("canonical", canonical);
                entry.addProperty("sameAsInput", canonical.equals(line.strip()));
            } catch (RuntimeException failure) {
                entry.addProperty("error", failure.toString());
            }
            results.add(entry);
        }
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(results));
        System.out.println("Wrote " + results.size() + " canonical payloads to " + out);
    }
}
