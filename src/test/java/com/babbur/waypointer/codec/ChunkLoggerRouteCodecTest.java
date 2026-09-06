package com.babbur.waypointer.codec;

import com.google.gson.JsonObject;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLoggerRouteCodecTest {

    @Test
    void importsCoordinateFixtureAsMainWaypointsAndCoalChildren() throws IOException {
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(fixture());

        assertEquals(WaypointImporter.Source.CHUNKLOGGER, imported.source());
        WaypointGroup group = imported.groups().getFirst();
        assertEquals(List.of("10,64,-2", "11,62,1", "-118,191,-2", "-4,70,8", "0,0,0", "0,0,0", "9,9,9"),
                coordinates(group));
        assertFalse(group.get(0).isSubwaypoint());
        assertTrue(group.get(1).isSubwaypoint());
        assertTrue(group.get(2).isSubwaypoint());
        assertFalse(group.get(3).isSubwaypoint());
        assertFalse(group.get(4).isSubwaypoint());
        assertTrue(group.get(5).isSubwaypoint(), "a zero offset is a real coal block");
        assertFalse(group.get(6).isSubwaypoint());
    }

    @Test
    void acceptsWaypointsObjectWrapper() {
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(
                "{\"waypoints\":[{\"x\":1,\"y\":2,\"z\":3,\"coal\":\"\"}]}" );

        assertEquals(WaypointImporter.Source.CHUNKLOGGER, imported.source());
        assertEquals(List.of("1,2,3"), coordinates(imported.groups().getFirst()));
    }

    @Test
    void coalUsesStandardBase64SignedTriplesAndIgnoresTrailingBytes() {
        JsonObject source = new JsonObject();
        source.addProperty("coal", "Af4DgH8AYw=="); // [1,-2,3], [-128,127,0], trailing 99

        List<ChunkLoggerRouteCodec.RelativeOffset> decoded = ChunkLoggerRouteCodec.decodeCoal(source);
        assertEquals(List.of(
                new ChunkLoggerRouteCodec.RelativeOffset(1, -2, 3),
                new ChunkLoggerRouteCodec.RelativeOffset(-128, 127, 0)), decoded);
        source.addProperty("coal", "%%%");
        assertTrue(ChunkLoggerRouteCodec.decodeCoal(source).isEmpty());
        source.addProperty("coal", "");
        assertTrue(ChunkLoggerRouteCodec.decodeCoal(source).isEmpty());
    }

    @Test
    void acceptsScalarStatisticsWithoutPersistingThem() {
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(
                "[{\"x\":1,\"y\":2,\"z\":3,\"blocks\":4,\"xzSpan\":5,\"ySpan\":6}]" );
        assertEquals(WaypointImporter.Source.CHUNKLOGGER, imported.source());
        assertEquals(List.of("1,2,3"), coordinates(imported.groups().getFirst()));
    }

    @Test
    void chunkLoggerMarkerWinsOverSkyHanniExtras() {
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(
                "[{\"x\":1,\"y\":2,\"z\":3,\"coal\":\"AAAA\",\"options\":{\"name\":\"ignored\"}}]" );

        assertEquals(WaypointImporter.Source.CHUNKLOGGER, imported.source());
        assertEquals(List.of("1,2,3", "1,2,3"), coordinates(imported.groups().getFirst()));
        assertTrue(imported.groups().getFirst().get(1).isSubwaypoint());
    }

    @Test
    void rejectsCoalChildOutsideWaypointerCoordinateRange() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny("[{\"x\":134217727,\"y\":0,\"z\":0,\"coal\":\"AQAA\"}]"));
        assertTrue(failure.getMessage().contains("outside Waypointer's coordinate range"));
    }

    private static String fixture() throws IOException {
        try (var stream = ChunkLoggerRouteCodecTest.class.getResourceAsStream(
                "/fixtures/chunklogger-routeskipper-coordinate-route.json")) {
            assertTrue(stream != null, "missing ChunkLogger fixture");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> coordinates(WaypointGroup group) {
        return group.waypoints().stream()
                .map(waypoint -> waypoint.x() + "," + waypoint.y() + "," + waypoint.z())
                .toList();
    }
}
