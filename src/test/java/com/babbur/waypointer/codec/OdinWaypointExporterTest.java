package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OdinWaypointExporterTest {

    @Test
    void dungeonRoomsBecomeAnOdinWaypointPack() throws Exception {
        WaypointGroup altar = room("altar");
        altar.add(new Waypoint(19, 83, 43, "&aSecret Chest", 0x00FF00,
                Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET, 0.0));
        altar.add(new Waypoint(5, 70, 6, "", 0x9C2EFF,
                Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP, 0.0));
        WaypointGroup second = room("altar");
        second.add(new Waypoint(7, 71, 8, "", 0x6EE7B7, Waypoint.FLAG_DUNGEON_DUNGEONBREAKER, 0.0));

        String code = WaypointExportCodec.encode(List.of(altar, second),
                WaypointCodec.Options.FULL_FIDELITY, WaypointExportCodec.Target.ODIN);

        JsonObject pack = JsonParser.parseString(WaypointImporter.decodeBase64Gzip(code)).getAsJsonObject();
        assertEquals(List.of("Altar"), List.copyOf(pack.keySet()), "routes for one room share Odin's room entry");
        JsonArray points = pack.getAsJsonArray("Altar");
        assertEquals(3, points.size());
        JsonObject chest = points.get(0).getAsJsonObject();
        assertEquals(19, chest.getAsJsonObject("blockPos").get("x").getAsInt());
        assertEquals("#00FF00FF", chest.get("color").getAsString());
        assertEquals("§aSecret Chest", chest.get("title").getAsString());
        assertEquals("SECRET", chest.get("type").getAsString());
        assertFalse(chest.get("filled").getAsBoolean());
        assertEquals(1.0, chest.getAsJsonObject("aabb").get("maxY").getAsDouble());
        assertEquals("ETHERWARP", points.get(1).getAsJsonObject().get("type").getAsString());
        assertFalse(points.get(2).getAsJsonObject().has("type"));

        WaypointImporter.ImportResult imported = WaypointImporter.importAny(code);
        assertEquals(WaypointImporter.Source.ODIN, imported.source());
        WaypointGroup back = imported.groups().getFirst();
        assertEquals("altar", back.zoneId());
        assertEquals(List.of(19, 83, 43), List.of(back.get(0).x(), back.get(0).y(), back.get(0).z()));
        assertEquals(0x00FF00, back.get(0).color());
    }

    @Test
    void islandRoutesBecomeOdinWaypointsAndComeBackUnchanged() {
        WaypointGroup mines = WaypointGroup.create("Mithril", "dwarven_mines");
        mines.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        mines.add(new Waypoint(12, 140, -48, "&bStart", 0x55FFFF, 0, 0.0));
        mines.add(new Waypoint(-4, 120, 9, "", 0xFFAA00, 0, 2.5));
        mines.add(new Waypoint(3, 121, 10, "", 0xFFAA00, Waypoint.FLAG_DISABLED, 0.0));

        String code = WaypointExportCodec.encode(List.of(mines),
                WaypointCodec.Options.FULL_FIDELITY, WaypointExportCodec.Target.ODIN);

        JsonArray points = JsonParser.parseString(code).getAsJsonArray();
        JsonObject start = points.get(0).getAsJsonObject();
        assertEquals("ISLAND_DwarvenMines", start.get("area").getAsString());
        assertEquals("§bStart", start.get("label").getAsString());
        assertEquals("VISUAL", start.get("trigger").getAsString());
        assertEquals(13.0, start.getAsJsonObject("endPos").get("x").getAsDouble(), "outlines its block");
        assertFalse(start.has("radius"));
        JsonObject reach = points.get(1).getAsJsonObject();
        assertEquals("2", reach.get("label").getAsString(), "ordered routes keep their step numbers");
        assertEquals(2.5, reach.get("radius").getAsDouble(), 1e-6);
        assertEquals(-4.0, reach.getAsJsonObject("endPos").get("x").getAsDouble());
        assertFalse(points.get(2).getAsJsonObject().get("enabled").getAsBoolean());

        WaypointImporter.ImportResult imported = WaypointImporter.importAny(code);
        assertEquals(WaypointImporter.Source.ODIN, imported.source());
        WaypointGroup back = imported.groups().getFirst();
        assertEquals("dwarven_mines", back.zoneId());
        for (int i = 0; i < mines.size(); i++) {
            assertEquals(List.of(mines.get(i).x(), mines.get(i).y(), mines.get(i).z()),
                    List.of(back.get(i).x(), back.get(i).y(), back.get(i).z()));
        }
        assertEquals("&bStart", back.get(0).name());
        assertEquals(2.5, back.get(1).customRadius(), 1e-6);
        assertTrue(back.get(2).isDisabled());
    }

    @Test
    void bossAndKuudraRoutesShowForTheWholeFightOrRun() {
        assertEquals(List.of("FLOOR_3"), OdinWaypointExporter.areas("dungeon_m3"));
        assertEquals(6, OdinWaypointExporter.areas("dungeon_f7").size());
        assertEquals("F7_STORM", OdinWaypointExporter.areas("dungeon_f7").get(1));
        assertEquals(5, OdinWaypointExporter.areas("kuudra").size());
        assertEquals(List.of("ISLAND_Mineshaft"), OdinWaypointExporter.areas("mineshaft_topaz_1"));
        assertEquals(List.of("ISLAND_MoongladeMarsh"), OdinWaypointExporter.areas("galatea"));
        assertEquals(List.of(), OdinWaypointExporter.areas("custom_island_name"));

        for (String zone : List.of("hub", "garden", "crystal_hollows", "dungeon_f2", "kuudra", "galatea")) {
            for (String area : OdinWaypointExporter.areas(zone)) {
                assertEquals(zone, OdinWaypointImporter.zone(area), area);
            }
        }
    }

    @Test
    void namesAndColorsFollowTheExportSettings() {
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        hub.add(new Waypoint(1, 70, 1, "Named", 0x123456, 0, 0.0));
        WaypointCodec.Options bare = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeNames(false).includeColors(false).build();

        JsonObject point = JsonParser.parseString(WaypointExportCodec.encode(List.of(hub), bare,
                WaypointExportCodec.Target.ODIN)).getAsJsonArray().get(0).getAsJsonObject();

        assertEquals("1", point.get("label").getAsString(), "ordered routes keep step numbers, not names");
        assertEquals(String.format("#%06XFF", Waypoint.DEFAULT_COLOR & 0xFFFFFF), point.get("color").getAsString());
    }

    @Test
    void roomsAndIslandsCannotShareOneOdinCode() {
        WaypointGroup altar = room("altar");
        altar.add(Waypoint.at(1, 70, 1));
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        hub.add(Waypoint.at(1, 70, 1));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                WaypointExportCodec.encode(List.of(altar, hub), WaypointCodec.Options.FULL_FIDELITY,
                        WaypointExportCodec.Target.ODIN));
        assertTrue(refused.getMessage().contains("separately"));
    }

    private static WaypointGroup room(String id) {
        WaypointGroup room = WaypointGroup.create("FlameOfWar", id);
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        room.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        return room;
    }
}
