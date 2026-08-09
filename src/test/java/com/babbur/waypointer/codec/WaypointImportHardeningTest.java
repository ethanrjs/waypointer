package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointImportHardeningTest {

    private static String largeJsonRoute(int count) {
        StringBuilder json = new StringBuilder(count * 24 + 64);
        json.append("{\"name\":\"huge\",\"waypoints\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) json.append(',');
            json.append("{\"x\":").append(i).append(",\"y\":64,\"z\":0}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String spacedCodec(String codec) {
        String body = codec.substring(WaypointCodec.MAGIC.length());
        StringBuilder out = new StringBuilder(codec.length() + codec.length() / 4);
        out.append(WaypointCodec.MAGIC);
        for (int i = 0; i < body.length(); i++) {
            out.append(body.charAt(i));
            if (i % 5 == 4) out.append(' ');
            if (i % 17 == 16) out.append('\n');
        }
        return out.toString();
    }

    @Test
    void rejectsImportsOverPerGroupWaypointLimit() {
        String payload = largeJsonRoute(WaypointImporter.MAX_WAYPOINTS_PER_GROUP + 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny(payload));

        assertTrue(ex.getMessage().contains("too many waypoints"));
    }

    @Test
    void repairsJsonEmbeddedInChatText() {
        String payload = "Player123: {waves} take this route "
                + "{\"name\":\"chat\",\"waypoints\":[{\"x\":7,\"y\":64,\"z\":9}]} ty";

        WaypointImporter.ImportResult result = WaypointImporter.importAny(payload);

        assertEquals(1, result.groups().size());
        assertEquals("chat", result.groups().get(0).name());
        assertEquals(7, result.groups().get(0).get(0).x());
    }

    @Test
    void repairsWhitespaceInsertedIntoNativeCodec() {
        WaypointGroup group = WaypointGroup.create("native", "hub");
        group.add(Waypoint.at(3, 64, 5));
        String codec = WaypointCodec.encode(List.of(group));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(spacedCodec(codec));

        assertEquals(WaypointImporter.Source.WAYPOINTER, result.source());
        assertEquals(1, result.groups().size());
        assertEquals(3, result.groups().get(0).get(0).x());
    }

    @Test
    void repairsNativeCodecEmbeddedInChatTextWithoutGuessingEverySuffix() {
        WaypointGroup group = WaypointGroup.create("native", "hub");
        group.add(Waypoint.at(3, 64, 5));
        String codec = WaypointCodec.encode(List.of(group));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(
                "Player123: use " + codec + " thanks");

        assertEquals(WaypointImporter.Source.WAYPOINTER, result.source());
        assertEquals(3, result.groups().get(0).get(0).x());
    }

    @Test
    void rejectsDeeplyUnbalancedJsonInLinearTime() {
        String payload = "{".repeat(100_000);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThrows(IllegalArgumentException.class,
                        () -> WaypointImporter.importAny(payload)));
    }

    @Test
    void malformedJsonIsNormalizedToAnImportFailure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny("{\"waypoints\":["));

        assertTrue(error.getMessage().contains("malformed waypoint payload"));
    }
}
