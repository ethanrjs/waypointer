package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.CatalogCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogV11Test {
    private static final String API_ROOT = "https://catalog.example/api/";

    @Test
    void routesV9CarriesInFullStayV9SoOlderVersionsCanInstallThem() {
        CatalogProtocol.PreparedCatalogPayload prepared = CatalogProtocol.prepareCatalogPayload(List.of(route()));

        assertFalse(CatalogCodec.isV11(prepared.payload()));
        assertDoesNotThrow(() -> WaypointCodec.decodeCanonicalV9(prepared.payload()));
        assertEquals(9, CatalogProtocol.inspectPayload(prepared.payload()).codecVersion());
    }

    @Test
    void aRouteV9WouldLoseSomethingFromPublishesAsV11AndInstallsWhole() {
        WaypointGroup route = route();
        route.setIcon("minecraft:diamond_pickaxe");
        CatalogProtocol.PreparedCatalogPayload prepared = CatalogProtocol.prepareCatalogPayload(List.of(route));

        assertTrue(CatalogCodec.isV11(prepared.payload()));
        CatalogProtocol.PayloadManifest manifest = CatalogProtocol.inspectPayload(prepared.payload());
        assertEquals(CatalogCodec.V11_CODEC_VERSION, manifest.codecVersion());
        assertEquals("crystal_hollows", manifest.zoneId());
        assertTrue(CatalogProtocol.supportsCodecVersion(CatalogCodec.V11_CODEC_VERSION));

        CatalogRouteSummary summary = new CatalogRouteSummary(
                "Abcdefghijklmnopqrstuv", "Route", "", "Tester", "", false, "public",
                "crystal_hollows", "Crystal Hollows", 3, 1, CatalogCodec.V11_CODEC_VERSION, 1,
                0, "", "", "/r/Abcdefghijklmnopqrstuv");
        ActiveGroupManager manager = new ActiveGroupManager();
        assertEquals(CatalogInstallState.Action.INSTALL,
                CatalogInstallState.inspect(API_ROOT, summary, manager.allGroups()).action());
        CatalogRouteInstaller.install(manager, API_ROOT, new CatalogRouteDetails(summary, prepared.payload()));

        WaypointGroup installed = manager.allGroups().iterator().next();
        assertEquals("minecraft:diamond_pickaxe", installed.icon());
        assertEquals(3, installed.size());
        assertEquals(CatalogInstallState.Action.INSTALLED,
                CatalogInstallState.inspect(API_ROOT, summary, manager.allGroups()).action());
    }

    private static WaypointGroup route() {
        WaypointGroup group = WaypointGroup.create("Gemstones", "crystal_hollows");
        group.add(new Waypoint(10, 70, -4, "Start", 0x44AA66, 0, 0.0));
        group.add(new Waypoint(14, 71, -2, "", 0x44AA66, 0, 0.0));
        group.add(new Waypoint(20, 69, 3, "End", 0x44AA66, 0, 0.0));
        return group;
    }
}
