package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional semantic V10 round-trip check against an external route corpus. */
class V10ExternalCorpusRoundTripTest {

    @Test
    @EnabledIfSystemProperty(named = "v10.corpus", matches = ".+")
    void everyRoutePreservesItsOrderedCoordinates() throws Exception {
        Path corpus = Path.of(System.getProperty("v10.corpus"));
        int routes = 0;
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
                    group.add(Waypoint.at(
                            point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()));
                }

                List<WaypointGroup> decoded = WaypointCodec.decode(
                        WaypointCodec.encode(List.of(group), WaypointCodec.Options.BARE_COORDINATES));
                assertEquals(1, decoded.size());
                assertEquals(coordinates.size(), decoded.getFirst().size());
                for (int index = 0; index < coordinates.size(); index++) {
                    JsonArray expected = coordinates.get(index).getAsJsonArray();
                    assertEquals(expected.get(0).getAsInt(), decoded.getFirst().get(index).x());
                    assertEquals(expected.get(1).getAsInt(), decoded.getFirst().get(index).y());
                    assertEquals(expected.get(2).getAsInt(), decoded.getFirst().get(index).z());
                }
                routes++;
            }
        }
        assertTrue(routes > 0, "external corpus is empty");
    }
}
