package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CrystalHollowsPosition;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.babbur.waypointer.crystal.CrystalHollowsStructureZones;
import com.babbur.waypointer.crystal.FairyGrottoLayout;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrottoLayoutShareTest {

    @Test
    void theGrottoLayoutTravelsInShareCodes() {
        WaypointGroup route = grottoRoute();
        for (int names = 0; names < 2; names++) {
            WaypointCodec.Options options = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                    .includeNames(names == 0).build();
            String code = UniversalShareCodec.encodeWaypoints(List.of(route), options);
            List<WaypointGroup> decoded = WaypointCodec.decode(code);
            assertEquals(route.grottoLayout(), decoded.getFirst().grottoLayout(), code);
            assertEquals(route.zoneId(), decoded.getFirst().zoneId());
        }
    }

    @Test
    void theSavedRouteFormKeepsTheLayoutToo() {
        WaypointGroup route = grottoRoute();
        String code = WaypointExportCodec.encode(List.of(route), WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.WAYPOINTER, null);
        assertEquals(route.grottoLayout(), WaypointCodec.decode(code).getFirst().grottoLayout());
    }

    @Test
    void droppingTheZoneDropsTheLayout() {
        WaypointCodec.Options noZone = WaypointCodec.Options.FULL_FIDELITY.toBuilder().includeZone(false).build();
        assertTrue(V11Codec.project(List.of(grottoRoute()), noZone).getFirst().grottoLayout().isEmpty());
    }

    @Test
    void invalidLayoutTextIsDropped() {
        WaypointGroup route = grottoRoute();
        route.setGrottoLayout("1.not base64!");
        assertTrue(route.grottoLayout().isEmpty());
        route.setGrottoLayout("2." + grottoRoute().grottoLayout().substring(2));
        assertTrue(route.grottoLayout().isEmpty());
    }

    private static WaypointGroup grottoRoute() {
        Random random = new Random(7);
        CrystalHollowsPosition pivot = new CrystalHollowsPosition(300, 100, 300);
        Set<Long> glass = new HashSet<>();
        while (glass.size() < 400) {
            glass.add(FairyGrottoLayout.pack(300 + random.nextInt(61) - 30, 100 + random.nextInt(21) - 10,
                    300 + random.nextInt(61) - 30));
        }
        WaypointGroup route = WaypointGroup.create("Jasper",
                CrystalHollowsStructureZones.zoneId(CrystalHollowsStructure.FAIRY_GROTTO));
        route.setGrottoLayout(FairyGrottoLayout.capture(pivot, glass).toText());
        route.add(new Waypoint(1, 0, 2, "1", 0xFF00FF, 0, 0.0));
        route.add(new Waypoint(-4, 2, 6, "2", 0xFF00FF, 0, 0.0));
        route.add(new Waypoint(8, -1, -3, "3", 0xFF00FF, 0, 0.0));
        return route;
    }
}
