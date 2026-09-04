package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        // Catalog V9 intentionally omits V10-only paint metadata. Keeping a
        // painted source here proves preparation remains byte-for-byte equal to
        // the existing catalog encoder rather than introducing another projection.
        route.setPaint(new WaypointPaint(
                WaypointPaint.defaultPalette(0x44AA66),
                new byte[WaypointPaint.PIXEL_COUNT]));
        route.setPaintEnabled(false);
        return route;
    }
}
