package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrystalHollowsTabListTest {

    @Test
    void parsesCrystalWidgetStates() {
        Map<Crystal, CrystalState> states = CrystalHollowsTabList.parseCrystalStates(List.of(
                "§6Amber: ✖ Not Found",
                "§5Amethyst ✔ Not Placed",
                "§aJade: ✔ Placed"));
        assertEquals(CrystalState.MISSING, states.get(Crystal.AMBER));
        assertEquals(CrystalState.COLLECTED, states.get(Crystal.AMETHYST));
        assertEquals(CrystalState.PLACED, states.get(Crystal.JADE));
    }

    @Test
    void detectsKingsScentAnywhereInTab() {
        assertTrue(CrystalHollowsTabList.hasKingsScent(List.of("§6King's Scent §f12:34")));
        assertFalse(CrystalHollowsTabList.hasKingsScent(List.of("§7No active effects")));
        assertFalse(CrystalHollowsTabList.hasKingsScent(null));
    }

    @Test
    void tabWidgetOverridesChatOnlyWhileItIsPresent() {
        Map<Crystal, CrystalState> chat = Map.of(
                Crystal.JADE, CrystalState.COLLECTED,
                Crystal.AMBER, CrystalState.MISSING);
        Map<Crystal, CrystalState> tab = Map.of(
                Crystal.JADE, CrystalState.PLACED);

        assertEquals(tab, CrystalHollowsTabList.preferredStates(tab, chat));
        assertEquals(chat, CrystalHollowsTabList.preferredStates(Map.of(), chat));
        assertTrue(CrystalHollowsTabList.preferredStates(null, null).isEmpty());
    }
}
