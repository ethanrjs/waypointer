package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPreparedPublishTest {

    @Test
    void preparedAndStrictlyDecodedPayloadsProduceTheSamePublishManifest() {
        WaypointGroup route = route();
        CatalogProtocol.PreparedCatalogPayload prepared =
                CatalogProtocol.prepareCatalogPayload(List.of(route));
        CatalogPublishRequest request = request(prepared.payload(), "hub");
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);

        CatalogProtocol.PublishExpectation trusted =
                CatalogProtocol.validatePreparedPublishRequest(
                        request, identity, prepared);
        CatalogProtocol.PublishExpectation decoded =
                CatalogProtocol.validatePublishRequest(request, identity);

        assertEquals(WaypointCodec.encodeCatalog(List.of(route)),
                prepared.payload());
        assertEquals(decoded, trusted);
    }

    @Test
    void preparedPayloadIsAnImmutableSnapshotOfTheRoute() {
        WaypointGroup route = route();
        CatalogProtocol.PreparedCatalogPayload prepared =
                CatalogProtocol.prepareCatalogPayload(List.of(route));
        route.add(Waypoint.at(30, 75, 40));

        CatalogProtocol.PublishExpectation expectation =
                CatalogProtocol.validatePreparedPublishRequest(
                        request(prepared.payload(), "hub"),
                        PublisherIdentity.generate(Instant.EPOCH), prepared);

        assertEquals(2, expectation.manifest().waypointCount());
        assertEquals(3, route.size());
    }

    @Test
    void multiZoneBundleKeepsEachDestinationAndUsesMultipleZoneManifest() {
        WaypointGroup hub = route();
        WaypointGroup garden = route();
        garden.setZoneId("garden");
        CatalogProtocol.PreparedCatalogPayload prepared =
                CatalogProtocol.prepareCatalogPayload(List.of(hub, garden));
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        CatalogPublishRequest request = request(prepared.payload(), "multiple");

        CatalogProtocol.PublishExpectation expected =
                CatalogProtocol.validatePreparedPublishRequest(request, identity, prepared);

        assertEquals(expected, CatalogProtocol.validatePublishRequest(request, identity));
        assertEquals("multiple", expected.zoneId());
        assertEquals(2, expected.manifest().groupCount());
        assertEquals(4, expected.manifest().waypointCount());
        assertEquals(List.of("hub", "garden"),
                CatalogProtocol.inspectPayload(prepared.payload()).groups().stream()
                        .map(WaypointGroup::zoneId).toList());
        assertThrows(IllegalArgumentException.class,
                () -> CatalogProtocol.validatePreparedPublishRequest(
                        request(prepared.payload(), "hub"), identity, prepared));
    }

    @Test
    void dungeonPackUsesOneListingAndKeepsRoomIdentityAndWaypointStyles() {
        List<WaypointGroup> rooms = IntStream.range(0, 109).mapToObj(index -> {
            WaypointGroup room = route();
            room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            room.setZoneId("room-" + index);
            room.set(0, room.get(0).withFlags(Waypoint.FLAG_DUNGEON_SECRET
                    | Waypoint.FLAG_SKIP_ON_INTERACT).withPreciseSixteenths(19, 1028, 35));
            room.set(1, room.get(1).withFlags(Waypoint.FLAG_SUBWAYPOINT
                    | Waypoint.FLAG_FILLED_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT));
            return room;
        }).toList();
        CatalogProtocol.PreparedCatalogPayload prepared = CatalogProtocol.prepareCatalogPayload(rooms);
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        CatalogPublishRequest request = request(prepared.payload(), "dungeon");
        var expected = CatalogProtocol.validatePreparedPublishRequest(request, identity, prepared);
        var inspected = CatalogProtocol.inspectPayload(prepared.payload());

        assertTrue(prepared.payload().startsWith("WPD:."));
        assertEquals(DungeonRoomShareCodec.CATALOG_CODEC_VERSION, expected.manifest().codecVersion());
        assertEquals("dungeon", expected.zoneId());
        assertEquals(109, expected.manifest().groupCount());
        assertEquals(218, expected.manifest().waypointCount());
        assertEquals(expected, CatalogProtocol.validatePublishRequest(request, identity));
        for (int index = 0; index < rooms.size(); index++) {
            WaypointGroup copy = inspected.groups().get(index);
            assertEquals(WaypointGroup.RouteKind.DUNGEON, copy.routeKind());
            assertEquals(rooms.get(index).zoneId(), copy.zoneId());
            assertEquals(rooms.get(index).waypoints(), copy.waypoints());
        }
        assertThrows(IllegalArgumentException.class, () ->
                CatalogProtocol.prepareCatalogPayload(List.of(rooms.getFirst(), route())));
        assertThrows(IllegalArgumentException.class, () ->
                CatalogProtocol.validatePreparedPublishRequest(
                        request(prepared.payload(), "multiple"), identity, prepared));
    }

    @Test
    void preparedCapabilityCannotValidateDifferentPayloadOrZone() {
        CatalogProtocol.PreparedCatalogPayload prepared =
                CatalogProtocol.prepareCatalogPayload(List.of(route()));
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);

        assertThrows(IllegalArgumentException.class,
                () -> CatalogProtocol.validatePreparedPublishRequest(
                        request(prepared.payload() + " ", "hub"), identity, prepared));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogProtocol.validatePreparedPublishRequest(
                        request(prepared.payload(), "garden"), identity, prepared));
    }

    private static CatalogPublishRequest request(String payload, String zoneId) {
        return new CatalogPublishRequest(
                payload, "Route", "Description",
                CatalogPublishRequest.Visibility.UNLISTED, zoneId, "Tester");
    }

    private static WaypointGroup route() {
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(new Waypoint(1, 64, 2, "Start", 0x44AA66, 1, 2.5));
        route.add(new Waypoint(8, 70, 12, "Finish", 0xCC8844, 0, 0.0));
        route.setDefaultRadius(4.25);
        route.setSkipAheadEnabled(false);
        route.setPaint(new WaypointPaint(
                WaypointPaint.defaultPalette(0x44AA66),
                new byte[WaypointPaint.PIXEL_COUNT]));
        route.setPaintEnabled(false);
        return route;
    }
}
