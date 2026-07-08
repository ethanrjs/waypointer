package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OdinDungeonWaypointImportTest {

    @Test
    void imports_odin_dungeon_waypoint_config_as_room_scoped_static_groups() {
        String json = """
                {
                  "Entrance": [],
                  "Altar": [
                    {
                      "blockPos": {
                        "field_11175": 19,
                        "field_11174": 83,
                        "field_11173": 43
                      },
                      "color": "#00FF00FF",
                      "filled": false,
                      "depth": false,
                      "title": "Secret Chest"
                    },
                    {
                      "blockPos": {
                        "field_11175": 51,
                        "field_11174": 87,
                        "field_11173": 50
                      },
                      "color": "#FF0000FF",
                      "filled": false,
                      "depth": false,
                      "title": "Enter text"
                    }
                  ]
                }
                """;

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(WaypointImporter.Source.ODIN, result.source());
        assertEquals(1, result.groups().size());

        WaypointGroup group = result.groups().get(0);
        assertEquals("Altar", group.name());
        assertEquals("altar", group.zoneId());
        assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(2, group.size());

        Waypoint first = group.get(0);
        assertEquals(19, first.x());
        assertEquals(83, first.y());
        assertEquals(43, first.z());
        assertEquals("Secret Chest", first.name());
        assertEquals(0x00FF00, first.color());

        Waypoint second = group.get(1);
        assertEquals(0xFF0000, second.color());
        assertEquals("", second.name());
    }

    @Test
    void odin_import_uses_normalized_zone_id_for_unknown_future_rooms() {
        String json = """
                {
                  "Future Room": [
                    {
                      "blockPos": {
                        "field_11175": 1,
                        "field_11174": 2,
                        "field_11173": 3
                      },
                      "color": "#0000FFFF"
                    }
                  ]
                }
                """;

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup group = result.groups().get(0);

        assertEquals(WaypointImporter.Source.ODIN, result.source());
        assertEquals("Future Room", group.name());
        assertEquals("future-room", group.zoneId());
        assertEquals(1, group.size());
        assertEquals(0x0000FF, group.get(0).color());
    }

    @Test
    void odin_import_sanitizes_unknown_room_and_waypoint_names() {
        String json = """
                {
                  "\\u00A7cFuture\\n Room": [
                    {
                      "blockPos": {
                        "field_11175": 1,
                        "field_11174": 2,
                        "field_11173": 3
                      },
                      "title": "\\u00A7aSecret\\nChest"
                    }
                  ]
                }
                """;

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup group = result.groups().get(0);

        assertEquals(WaypointCodec.Options.sanitizeLabel("\u00A7cFuture\n Room"),
                group.name());
        assertEquals(WaypointCodec.Options.sanitizeLabel("\u00A7aSecret\nChest"),
                group.get(0).name());
    }
}
