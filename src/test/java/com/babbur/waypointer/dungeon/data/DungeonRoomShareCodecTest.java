package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomShareCodecTest {

    @Test
    void roundTripsPersistedDungeonGroups() {
        WaypointGroup route = route("crypt-a", "Crypt A");
        route.add(new Waypoint(16, 70, 16, "Chest", 0x123456,
                Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET, 2.5)
                .withPreciseSixteenths(264, 1128, 260));
        DungeonRoomShareCodec.Decoded decoded = DungeonRoomShareCodec.decode(
                "```text\n" + DungeonRoomShareCodec.encode(List.of(route)) + "\n```");

        assertEquals(1, decoded.routes().size());
        WaypointGroup copy = decoded.routes().getFirst();
        assertEquals(WaypointGroup.RouteKind.DUNGEON, copy.routeKind());
        assertEquals(route.zoneId(), copy.zoneId());
        assertEquals(route.name(), copy.name());
        assertEquals(route.waypoints(), copy.waypoints());
    }

    @Test
    void rejectsRegularOrEmptyRoutes() {
        WaypointGroup regular = WaypointGroup.create("Regular", "hub");
        regular.add(Waypoint.at(1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> DungeonRoomShareCodec.encode(List.of(regular)));

        assertThrows(IllegalArgumentException.class,
                () -> DungeonRoomShareCodec.encode(List.of(route("empty", "Empty"))));
    }

    @Test
    void decodesLegacyDefinitionPayloadIntoDungeonGroup() throws Exception {
        String json = """
                {"schema":1,"rooms":[{"id":"legacy","name":"Legacy","type":"ROOM",
                "shape":"ONE_BY_ONE","waypoints":[{"id":"secret-1","secretIndex":1,
                "category":"chest","trigger":"OPEN_CHEST","x":1,"y":2,"z":3,
                "name":"Chest","highlights":[]}]}]}
                """;

        String legacy = DungeonRoomShareCodec.MAGIC + gzipBase64(json);
        DungeonRoomShareCodec.Decoded decoded = DungeonRoomShareCodec.decode(legacy);

        assertEquals(1, decoded.routes().size());
        assertEquals("legacy", decoded.routes().getFirst().zoneId());
        assertTrue(decoded.routes().getFirst().get(0).hasFlag(Waypoint.FLAG_DUNGEON_SECRET));
    }

    @Test
    void legacyActivationFieldIsIgnored() throws Exception {
        String payload = DungeonRoomShareCodec.MAGIC + gzipBase64(currentJson(
                ",\"activation\":{\"match\":\"ALL\",\"rules\":[{\"type\":\"UNKNOWN\"}]}"));

        WaypointGroup decoded = DungeonRoomShareCodec.decode(payload).routes().getFirst();

        assertEquals("Route", decoded.name());
    }

    private static WaypointGroup route(String room, String name) {
        WaypointGroup route = WaypointGroup.create(name, room);
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setSkipAheadEnabled(false);
        return route;
    }

    private static String currentJson(String activationField) {
        return "{\"schema\":2,\"routes\":[{\"room\":\"room-a\","
                + "\"name\":\"Route\",\"loadMode\":\"SEQUENCE\","
                + "\"waypoints\":[{\"x\":1,\"y\":2,\"z\":3}]"
                + activationField + "}]}";
    }

    private static String gzipBase64(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
