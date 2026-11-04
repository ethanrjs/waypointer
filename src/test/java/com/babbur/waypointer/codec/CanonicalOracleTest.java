package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

                String code = line.strip();
                List<WaypointGroup> groups;
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
