package com.babbur.waypointer.location;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SidebarTextsTest {

    @Test
    void stripsVanillaAndHypixelFormattingPairsFromVisibleText() {
        String stripped = SidebarTexts.buildStrippedText(List.of(
                "§aVisible §lName",
                "Dwarven B§uase Camp",
                "TU§xNG§u_1",
                "trailing§"
        ));

        assertEquals("Visible Name\nDwarven Base Camp\nTUNG_1\ntrailing", stripped);
        assertFalse(stripped.contains("§"));
    }

    @Test
    void hiddenOwnerTokensDoNotBlockTungstenMineshaftActivation() {
        String sidebar = SidebarTexts.buildStrippedText(List.of(
                "§6⏣ Glacite Mineshafts",
                "07/23/26 m87§yD TU§xNG§u_1"
        ));
        Zone detected = Zone.refineIfDwarvenMinesContext(Zone.fromId("mineshaft"), sidebar);
        WaypointGroup route = new WaypointGroup(
                "tungsten-route", "Tungsten Route", "mineshaft_tungsten");
        route.add(Waypoint.at(1, 64, 1));
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.add(route);

        manager.onZoneChanged(detected);

        assertEquals("mineshaft_tungsten", detected.id());
        assertEquals(List.of(route), manager.activeGroups());
    }
}
