package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsProjection;
import com.babbur.waypointer.crystal.CrystalHollowsStructureFolder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceTargetTest {

    @Test
    void darkColorsAreLiftedSoTheNameStaysReadable() {
        assertEquals(0xFF5555, AppearanceTarget.readable(0xFF5555));
        int lifted = AppearanceTarget.readable(0x101060);
        assertTrue((lifted & 0xFF) > 0x60 && ((lifted >> 16) & 0xFF) > 0x10);
    }

    @Test
    void switcherOffersZonesWithRoutesOrLooksAfterTheFamilies() {
        List<String> keys = AppearanceTarget.switcherZoneKeys(Zone.fromId("dwarven_mines"),
                List.of("hub", "private_world", "dwarven_mines", "mineshaft_topaz", "hub"),
                List.of("the_park"));
        assertEquals(List.of("dwarven_mines", "dungeon", "mineshaft", "crystal_hollows"), keys.subList(0, 4));
        assertTrue(keys.contains("hub"));
        assertTrue(keys.contains("the_park"));
        assertEquals(keys.size(), keys.stream().distinct().count(), "each zone is offered once");
        assertTrue(!keys.contains("private_world") && !keys.contains("mineshaft_topaz"));
    }

    @Test
    void structureRouteLooksAreKeptWhenTheStructureIsSeenAgain() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup seen = structureRoute();
        manager.add(seen);

        WaypointerConfig draft = new WaypointerConfig();
        draft.setLabelScale(2.0);
        AppearanceTarget.route(manager, config, seen, () -> { }).scope().capture(draft, "labelScale");

        WaypointGroup seenAgain = structureRoute();
        manager.add(seenAgain);
        CrystalHollowsProjection.refreshStyles(manager, config);
        assertEquals("2.0", seenAgain.styleOverride().values().get("labelScale"));
    }

    private static WaypointGroup structureRoute() {
        WaypointGroup group = new WaypointGroup(CrystalHollowsStructureFolder.GROUP_PREFIX + "mines_of_divan",
                "Mines of Divan", CrystalHollowsStructureFolder.ZONE_ID);
        group.setRuntimeOnly(true);
        group.add(Waypoint.at(500, 100, 500));
        return group;
    }
}
