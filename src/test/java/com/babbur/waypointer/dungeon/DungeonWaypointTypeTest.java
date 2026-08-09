package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonWaypointTypeTest {

    @Test
    void exposesStableEightTypeAndWaypointTypeButtonIconContract() {
        assertEquals(List.of(
                        "Secret", "Etherwarp", "Dungeonbreaker", "Superboom",
                        "Pearl", "Pearl target", "Item", "Bat"),
                List.of(DungeonWaypointType.values()).stream()
                        .map(DungeonWaypointType::label)
                        .toList());
        for (int i = 0; i < DungeonWaypointType.values().length; i++) {
            assertEquals(i, DungeonWaypointType.values()[i].iconIndex());
        }
        assertEquals(12, DungeonWaypointType.ICON_SIZE);
        assertEquals(8, DungeonWaypointType.WAYPOINT_TYPE_ICON_INDEX);
        assertEquals(108, DungeonWaypointType.ICON_ATLAS_WIDTH);
    }

    @Test
    void iconAtlasMatchesTheNineCellContract() throws Exception {
        var image = ImageIO.read(Path.of(
                "src/main/resources/assets/waypointer/textures/gui/dungeon_waypoint_types.png")
                .toFile());

        assertEquals(DungeonWaypointType.ICON_ATLAS_WIDTH, image.getWidth());
        assertEquals(DungeonWaypointType.ICON_SIZE, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
    }

    @Test
    void manualSelectionKeepsOnlyOneTypeAndPreservesOtherWaypointFlags() {
        Waypoint original = Waypoint.at(1, 70, 2).withFlags(
                Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SKIP_ON_INTERACT
                        | Waypoint.FLAG_DUNGEON_SECRET
                        | Waypoint.FLAG_DUNGEON_ITEM);

        Waypoint withBat = DungeonWaypointType.BAT.selectExclusive(original);

        assertTrue(withBat.hasFlag(Waypoint.FLAG_SUBWAYPOINT));
        assertTrue(withBat.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));
        assertFalse(withBat.hasFlag(Waypoint.FLAG_DUNGEON_SECRET));
        assertFalse(withBat.hasFlag(Waypoint.FLAG_DUNGEON_ITEM));
        assertTrue(withBat.hasFlag(Waypoint.FLAG_DUNGEON_BAT));
        assertFalse(DungeonWaypointType.BAT.selectExclusive(withBat)
                .hasFlag(Waypoint.FLAG_DUNGEON_BAT));
        assertNull(DungeonWaypointType.BAT.selectExclusive(null));
        assertEquals(Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_BAT,
                DungeonWaypointType.BAT.applyExclusive(
                        Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET));
    }

    @Test
    void firstTypeUsesStableIconOrderAndHandlesNoType() {
        assertEquals(DungeonWaypointType.SECRET,
                DungeonWaypointType.firstType(
                        Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_DUNGEON_BAT));
        assertEquals(DungeonWaypointType.PEARL_TARGET,
                DungeonWaypointType.firstType(Waypoint.FLAG_DUNGEON_PEARL_TARGET));
        assertNull(DungeonWaypointType.firstType(Waypoint.FLAG_SKIP_ON_STAND));
    }

    @Test
    void summaryKeepsCombinedImportedLabelsVisible() {
        Waypoint waypoint = Waypoint.at(0, 0, 0).withFlags(
                Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_DUNGEON_ITEM);

        assertEquals(List.of(DungeonWaypointType.SECRET, DungeonWaypointType.ITEM),
                DungeonWaypointType.activeTypes(waypoint));
        assertEquals("Secret · Item", DungeonWaypointType.activeSummary(waypoint));
    }

    @Test
    void nullAndUnlabeledWaypointsHaveNoTypes() {
        assertEquals(List.of(), DungeonWaypointType.activeTypes(null));
        assertEquals("", DungeonWaypointType.activeSummary(null));
        assertFalse(DungeonWaypointType.SECRET.isSet(null));
        assertFalse(DungeonWaypointType.SECRET.isSet(Waypoint.at(0, 0, 0)));
    }
}
