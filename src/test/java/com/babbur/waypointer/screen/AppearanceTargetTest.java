package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceTargetTest {

    @Test
    void routeMenusEditTheZoneLookTheRouteFollows() {
        assertEquals("crystal_hollows", AppearanceTarget.zoneLookKey("crystal_hollows"));
        assertEquals("dwarven_mines", AppearanceTarget.zoneLookKey("dwarven_mines"));
        assertEquals("mineshaft", AppearanceTarget.zoneLookKey("mineshaft_topaz"));
        assertNull(AppearanceTarget.zoneLookKey("private_world"));
        assertNull(AppearanceTarget.zoneLookKey(""));
        assertNull(AppearanceTarget.zoneLookKey(null));
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
}
