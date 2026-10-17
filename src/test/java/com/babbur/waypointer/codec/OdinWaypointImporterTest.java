package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OdinWaypointImporterTest {

    /** As Odin 0.3.6's "Export" writes it: Gson, pretty printed, Vec3 as x/y/z objects. */
    private static final String EXPORT = """
            [
              {
                "area": "ISLAND_DwarvenMines",
                "blockPos": {
                  "x": 12.0,
                  "y": 140.0,
                  "z": -48.0
                },
                "endPos": {
                  "x": 12.0,
                  "y": 140.0,
                  "z": -48.0
                },
                "label": "§bStart",
                "color": "#55FFFFFF",
                "trigger": "VISUAL",
                "enabled": true
              },
              {
                "area": "F7_STORM",
                "blockPos": {"x": 70.0, "y": 220.0, "z": 30.0},
                "endPos": {"x": 74.0, "y": 224.0, "z": 36.0},
                "label": "Crusher",
                "color": "#FF5555FF",
                "trigger": "PROXIMITY",
                "command": "pc crusher",
                "radius": 2.5,
                "enabled": false
              },
              {
                "area": "ISLAND_DwarvenMines",
                "blockPos": {"x": -3.5, "y": 120.0, "z": 9.0},
                "endPos": {"x": -3.5, "y": 120.0, "z": 9.0},
                "label": "",
                "color": "#FFAA00FF",
                "trigger": "CLICK",
                "enabled": true
              },
              {
                "area": "KUUDRA_T5",
                "blockPos": {"x": -101.0, "y": 78.0, "z": -105.0},
                "endPos": {"x": -101.0, "y": 78.0, "z": -105.0},
                "label": "Pre",
                "color": "#55FF55FF",
                "trigger": "VISUAL",
                "enabled": true
              },
              {
                "area": "FLOOR_3",
                "blockPos": {"x": 1.0, "y": 70.0, "z": 1.0},
                "endPos": {"x": 1.0, "y": 70.0, "z": 1.0},
                "label": "Lever",
                "color": "#FFFFFFFF",
                "trigger": "VISUAL",
                "enabled": true
              }
            ]
            """;

    @Test
    void readsOdinsWaypointsModuleExport() {
        WaypointImporter.ImportResult result = WaypointImporter.importAny(EXPORT);
        assertEquals(WaypointImporter.Source.ODIN, result.source());
        List<WaypointGroup> groups = result.groups();
        assertEquals(List.of("Dwarven Mines", "F7 Storm", "Kuudra T5", "F3 / M3 Boss"),
                groups.stream().map(WaypointGroup::name).toList());
        assertEquals(List.of("dwarven_mines", "dungeon_f7", "kuudra", "dungeon_f3"),
                groups.stream().map(WaypointGroup::zoneId).toList());

        WaypointGroup mines = groups.getFirst();
        assertEquals(WaypointGroup.LoadMode.STATIC, mines.loadMode(), "Odin shows every point at once");
        assertEquals(2, mines.size());
        Waypoint start = mines.get(0);
        assertEquals(List.of(12, 140, -48), List.of(start.x(), start.y(), start.z()));
        assertEquals("&bStart", start.name());
        assertEquals(0x55FFFF, start.color() & 0xFFFFFF);
        Waypoint unnamed = mines.get(1);
        assertEquals(-4, unnamed.x(), "fractional positions round down to their block");

        Waypoint crusher = groups.get(1).get(0);
        assertEquals(List.of(72, 220, 33), List.of(crusher.x(), crusher.y(), crusher.z()),
                "an area becomes its middle, on its lowest layer");
        assertEquals(2.5, crusher.customRadius(), 1e-9);
        assertTrue(crusher.isDisabled());
    }

    @Test
    void otherArraysAreNotMistakenForOdin() {
        WaypointImporter.ImportResult loose = WaypointImporter.importAny(
                "[{\"x\":1,\"y\":2,\"z\":3,\"name\":\"a\"}]");
        assertNotEquals(WaypointImporter.Source.ODIN, loose.source());
    }
}
