package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonBossAreaTest {

    @Test
    void floorNumbersComeFromFloorZones() {
        assertEquals(7, DungeonBossArea.floorNumber("dungeon_f7"));
        assertEquals(3, DungeonBossArea.floorNumber("dungeon_m3"));
        assertEquals(0, DungeonBossArea.floorNumber("dungeon"));
        assertEquals(0, DungeonBossArea.floorNumber("arrow-trap"));
        assertEquals(0, DungeonBossArea.floorNumber(null));
    }

    @Test
    void theRoomGridIsNeverTheBoss() {
        for (int floor = 0; floor <= 7; floor++) {
            assertFalse(DungeonBossArea.contains(floor, -185, -185), "floor " + floor);
        }
        assertTrue(DungeonBossArea.contains(7, 73, 16));
        assertFalse(DungeonBossArea.contains(7, -20, 16));
        assertTrue(DungeonBossArea.contains(1, -60, -30));
        assertTrue(DungeonBossArea.contains(5, -30, 0));
        assertFalse(DungeonBossArea.contains(5, -30, -20));
    }

    @Test
    void bossRoutesOnlyShowInTheBoss() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup boss = WaypointGroup.create("F7 Boss", "dungeon_f7");
        boss.add(new Waypoint(69, 220, 41, "", Waypoint.DEFAULT_COLOR, Waypoint.FLAG_SKIP_ON_MINE, 0.0));
        WaypointGroup handMade = WaypointGroup.create("Notes", "dungeon_f7");
        handMade.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        manager.add(boss);
        manager.add(handMade);
        manager.onZoneChanged(Zone.fromId("dungeon_m7"));

        assertEquals(List.of(handMade), manager.activeGroups());
        manager.setInDungeonBoss(true);
        assertEquals(List.of(boss, handMade), manager.activeGroups());
        manager.setInDungeonBoss(false);
        assertEquals(List.of(handMade), manager.activeGroups());
    }
}
