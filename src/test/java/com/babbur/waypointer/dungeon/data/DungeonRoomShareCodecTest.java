package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomShareCodecTest {

    private static final String CATALOG_JSON = """
            {"schema":2,"routes":[{"room":"room-a","name":"","loadMode":"SEQUENCE",
            "defaultRadius":0.5,"skipAhead":false,"waypoints":[
            {"x":-1,"y":2,"z":3,"px":-1,"py":32,"pz":63,
            "color":10531008,"flags":-2147483648,"name":"First","radius":0.25},
            {"x":1,"y":2,"z":3,"px":16,"py":47,"pz":48,"color":0,"flags":81}]}]}
            """;

    @Test
    void catalogPreservesPrecisePointsChildStylesAndRouteSettings() throws Exception {
        DungeonRoomShareCodec.Decoded decoded = DungeonRoomShareCodec.decodeCatalog(catalogPayload(CATALOG_JSON));
        WaypointGroup route = decoded.routes().getFirst();
        assertEquals(2, DungeonRoomShareCodec.CATALOG_CODEC_VERSION);
        assertEquals(2, decoded.waypointCount());
        assertEquals("", route.name());
        assertEquals(0.5, route.defaultRadius());
        assertEquals(WaypointGroup.GradientMode.MANUAL, route.gradientMode());
        assertEquals(-1, route.get(0).preciseX());
        assertEquals(-1, route.get(0).x());
        assertEquals(Integer.MIN_VALUE, route.get(0).flags());
        assertEquals(0.25, route.get(0).customRadius());
        assertEquals(81, route.get(1).flags());
        assertTrue(route.get(1).isSubwaypoint());
        assertEquals(route.waypoints(), DungeonRoomShareCodec.decodeCatalog(
                DungeonRoomShareCodec.encodeCatalog(decoded.routes())).routes().getFirst().waypoints());
        assertEquals("\uD83D\uDE00", DungeonRoomShareCodec.decodeCatalog(catalogPayload(
                CATALOG_JSON.replace("First", "\\uD83D\\uDE00"))).routes().getFirst().get(0).name());
    }

    @Test
    void catalogRejectsCoercionUnknownFieldsAndLossyValues() throws Exception {
        List<String> invalid = List.of(
                CATALOG_JSON.replace("\"schema\":2", "\"schema\":2,\"schema\":2"),
                CATALOG_JSON.replace("\"schema\":2", "\"schema\":2,\"extra\":0"),
                CATALOG_JSON.replace("\"schema\":2", "\"schema\":\"2\""),
                CATALOG_JSON.replace("\"schema\":2", "\"schema\":2.0"),
                CATALOG_JSON.replace("\"schema\":2", "\"schema\":3"),
                CATALOG_JSON.replace("room-a", "Room A"),
                CATALOG_JSON.replace("room-a", "mineshaft"),
                CATALOG_JSON.replace("\"name\":\"\"", "\"name\":null"),
                CATALOG_JSON.replace("\"name\":\"\"", "\"name\":\"\\uD800\""),
                CATALOG_JSON.replace("First", "\\uDC00"),
                CATALOG_JSON.replace("\"name\":\"\"", "\"name\":\"" + "a".repeat(1025) + "\""),
                CATALOG_JSON.replace("SEQUENCE", "sequence"),
                CATALOG_JSON.replace("\"defaultRadius\":0.5", "\"defaultRadius\":0.49"),
                CATALOG_JSON.replace("\"defaultRadius\":0.5", "\"defaultRadius\":1e400"),
                CATALOG_JSON.replace("\"skipAhead\":false", "\"skipAhead\":\"false\""),
                CATALOG_JSON.replace("\"skipAhead\":false", "\"skipAhead\":false,\"enabled\":true"),
                CATALOG_JSON.replace("\"x\":-1", "\"x\":\"-1\""),
                CATALOG_JSON.replace("\"x\":-1", "\"x\":-1.1"),
                CATALOG_JSON.replace("\"x\":-1", "\"x\":-1.0"),
                CATALOG_JSON.replace("\"py\":32", "\"py\":32e0"),
                CATALOG_JSON.replace("\"px\":-1", "\"px\":0"),
                CATALOG_JSON.replace("\"px\":-1", "\"px\":2147483648"),
                CATALOG_JSON.replace("\"color\":10531008", "\"color\":-1"),
                CATALOG_JSON.replace("\"color\":10531008", "\"color\":16777216"),
                CATALOG_JSON.replace("\"flags\":-2147483648", "\"flags\":2147483648"),
                CATALOG_JSON.replace("\"flags\":-2147483648", "\"flags\":1e-400"),
                CATALOG_JSON.replace("\"flags\":-2147483648", "\"flags\":16"),
                CATALOG_JSON.replace("\"radius\":0.25", "\"radius\":0"),
                CATALOG_JSON.replace("\"radius\":0.25", "\"radius\":101"),
                CATALOG_JSON.replace("\"radius\":0.25", "\"radius\":0.25,\"extra\":true"),
                CATALOG_JSON + "{}",
                "[".repeat(9) + "0" + "]".repeat(9));
        for (String json : invalid) {
            String payload = catalogPayload(json);
            assertThrows(IllegalArgumentException.class,
                    () -> DungeonRoomShareCodec.decodeCatalog(payload), json);
        }
    }

    @Test
    void catalogRejectsLegacyWrappersAndMalformedOrOversizedTransport() throws Exception {
        String payload = catalogPayload(CATALOG_JSON);
        for (String invalid : List.of(" " + payload, payload + "\n", "```\n" + payload + "\n```",
                "WPD:" + gzipBase64(CATALOG_JSON), "WPD:." + "!".repeat(256 * 1024))) {
            assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.decodeCatalog(invalid));
        }
        String malformedUtf8 = catalogPayload(new byte[] {(byte) 0xC3, 0x28});
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.decodeCatalog(malformedUtf8));
        String oversizedJson = catalogPayload(" ".repeat(8 * 1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.decodeCatalog(oversizedJson));
        byte[] compressed = AsciiStreamCodec.decode(payload.substring(5));
        String trailing = "WPD:." + AsciiStreamCodec.encode(Arrays.copyOf(compressed, compressed.length + 1));
        String truncated = "WPD:." + AsciiStreamCodec.encode(Arrays.copyOf(compressed, compressed.length - 1));
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.decodeCatalog(trailing));
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.decodeCatalog(truncated));
        assertEquals(2, DungeonRoomShareCodec.decode("```\n" + payload + "\n```").waypointCount());
    }

    @Test
    void catalogEnforcesRouteAndWaypointCountsInBothDirections() throws Exception {
        JsonObject document = JsonParser.parseString(CATALOG_JSON).getAsJsonObject();
        JsonObject oneRoute = document.getAsJsonArray("routes").get(0).getAsJsonObject();
        JsonArray empty = new JsonArray();
        document.add("routes", empty);
        assertThrows(IllegalArgumentException.class,
                () -> DungeonRoomShareCodec.decodeCatalog(catalogPayload(document.toString())));
        for (int count : List.of(257, 21)) {
            JsonArray routes = new JsonArray();
            for (int i = 0; i < count; i++) {
                JsonObject route = oneRoute.deepCopy();
                if (count == 21) {
                    JsonArray points = new JsonArray();
                    for (int j = 0; j < 512; j++) points.add(oneRoute.getAsJsonArray("waypoints").get(0).deepCopy());
                    route.add("waypoints", points);
                }
                routes.add(route);
            }
            document.add("routes", routes);
            assertThrows(IllegalArgumentException.class,
                    () -> DungeonRoomShareCodec.decodeCatalog(catalogPayload(document.toString())));
        }
        for (int count : List.of(0, 513)) {
            JsonObject route = oneRoute.deepCopy();
            JsonArray points = new JsonArray();
            for (int i = 0; i < count; i++) points.add(oneRoute.getAsJsonArray("waypoints").get(0).deepCopy());
            route.add("waypoints", points);
            JsonArray routes = new JsonArray();
            routes.add(route);
            document.add("routes", routes);
            assertThrows(IllegalArgumentException.class,
                    () -> DungeonRoomShareCodec.decodeCatalog(catalogPayload(document.toString())));
        }
        WaypointGroup valid = route("room-a", "Route");
        valid.add(Waypoint.at(1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> DungeonRoomShareCodec.encodeCatalog(List.of(valid, route("room-b", "Empty"))));
        valid.setName("a".repeat(1025));
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomShareCodec.encodeCatalog(List.of(valid)));
    }

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

    private static String catalogPayload(String json) throws Exception {
        return catalogPayload(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String catalogPayload(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(out)) {
            compressed.write(bytes);
        }
        return "WPD:." + AsciiStreamCodec.encode(out.toByteArray());
    }
}
