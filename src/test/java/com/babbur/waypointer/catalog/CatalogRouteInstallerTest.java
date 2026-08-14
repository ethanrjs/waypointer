package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRouteInstallerTest {

    @Test
    void decodesCurrentWpPayloadWithoutMutatingLocalRoutes() {
        WaypointGroup group = WaypointGroup.create("Museum route", "hub");
        group.add(new Waypoint(12, 70, -4, "Start", 0x44AA66, 0, 0.0));
        String payload = WaypointCodec.encode(
                List.of(group), WaypointCodec.Options.FULL_FIDELITY);
        CatalogRouteDetails details = new CatalogRouteDetails(summary(1, 1), payload);

        List<WaypointGroup> decoded = CatalogRouteInstaller.decodeForPreview(details);

        assertEquals(1, decoded.size());
        assertEquals("Museum route", decoded.getFirst().name());
        assertEquals(1, decoded.getFirst().size());
        assertEquals("Start", decoded.getFirst().waypoints().getFirst().name());
    }

    @Test
    void rejectsPayloadWhenCatalogCountsDoNotMatch() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(0, 64, 0, "Point", 0xFFFFFF, 0, 0.0));
        String payload = WaypointCodec.encode(
                List.of(group), WaypointCodec.Options.FULL_FIDELITY);

        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.decodeForPreview(
                         new CatalogRouteDetails(summary(2, 1), payload)));
    }

    @Test
    void rejectsLegacyNoncanonicalAndMislabeledCatalogPayloads() {
        WaypointGroup group = route("hub", 1);
        String v9 = WaypointCodec.encodeCatalog(List.of(group));
        String v8 = WaypointCodec.encodeCatalogV8IfLossless(List.of(group));

        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(1, 1, "hub", 8), v9)));
        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(1, 1), v8)));
        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(1, 1), v9 + " ")));
    }

    @Test
    void derivesAndChecksEmbeddedCatalogZones() {
        WaypointGroup hub = route("hub", 1);
        WaypointGroup park = route("the_park", 2);
        String payload = WaypointCodec.encodeCatalog(List.of(hub, park));

        CatalogRouteInstaller.PreparedRoute prepared = CatalogRouteInstaller.prepare(
                new CatalogRouteDetails(summary(3, 2, "multiple", 9), payload));
        assertEquals(2, prepared.previewGroups().size());

        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(3, 2, "hub", 9), payload)));
    }

    @Test
    void enforcesCatalogSpecificGroupLimit() {
        List<WaypointGroup> groups = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> route("hub", 1))
                .toList();
        String payload = WaypointCodec.encodeCatalog(groups);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(65, 65), payload)));
        assertTrue(failure.getMessage().contains("install limits"));
    }

    private static WaypointGroup route(String zoneId, int waypointCount) {
        WaypointGroup group = WaypointGroup.create("Route", zoneId);
        for (int index = 0; index < waypointCount; index++) {
            group.add(new Waypoint(index, 64, index, "Point " + index,
                    0xFFFFFF, 0, 0.0));
        }
        return group;
    }

    private static CatalogRouteSummary summary(int waypointCount, int groupCount) {
        return summary(waypointCount, groupCount, "hub", 9);
    }

    private static CatalogRouteSummary summary(
            int waypointCount, int groupCount, String zoneId, int codecVersion) {
        return new CatalogRouteSummary(
                "Abcdefghijklmnopqrstuv", "Route", "", "Tester", "", false,
                "unlisted", zoneId, "Hub", waypointCount, groupCount, codecVersion, 1,
                0, "", "", "/routes/Abcdefghijklmnopqrstuv");
    }
}
