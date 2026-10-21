package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalShareCodecTest {

    @Test
    void jsonOverTheImportBudgetIsNotRetriedByTheDungeonParser() {
        String bomb = "{\"Arrow-Trap-1\":[{\"locations\":[],\"padding\":["
                + "0,".repeat(BoundedJson.MAX_SCALAR_VALUES) + "0]}]}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(bomb));
        assertTrue(failure.getMessage().contains("too many values"));
        for (Throwable suppressed : failure.getSuppressed()) {
            assertTrue(suppressed.getMessage().contains("too many values"), suppressed.getMessage());
        }
    }

    @Test
    void dispatchesNormalWaypointRoutes() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(12, 64, -8));

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(
                UniversalShareCodec.encodeWaypoints(List.of(group), WaypointCodec.Options.FULL_FIDELITY));

        UniversalShareCodec.Waypoints routes =
                assertInstanceOf(UniversalShareCodec.Waypoints.class, decoded);
        assertEquals(UniversalShareCodec.Type.WAYPOINTS, routes.type());
        assertEquals(1, routes.result().groups().size());
        assertEquals(group.name(), routes.result().groups().get(0).name());
    }

    @Test
    void dispatchesRouteLibraryWaypointWrappers() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(12, 64, -8));
        RouteLibraryMetadata metadata = new RouteLibraryMetadata(
                List.of(new RouteLibraryMetadata.ManualColorsEntry(0, List.of(0x123456))),
                List.of());

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(
                UniversalShareCodec.encodeWaypoints(
                        List.of(group.exportSnapshot()),
                        WaypointCodec.Options.FULL_FIDELITY,
                        metadata));

        UniversalShareCodec.Waypoints routes =
                assertInstanceOf(UniversalShareCodec.Waypoints.class, decoded);
        assertEquals(List.of(0x123456),
                routes.result().groups().getFirst().manualColorSnapshot(),
                "manual colours supplied as export metadata arrive on the route");
    }

    @Test
    void dispatchesFencedConfigCodes() {
        WaypointerConfig config = new WaypointerConfig();
        config.setShowTracer(false);

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode("```text\n"
                + UniversalShareCodec.encodeConfig(config) + "\n```");

        UniversalShareCodec.Configuration configuration =
                assertInstanceOf(UniversalShareCodec.Configuration.class, decoded);
        assertEquals(UniversalShareCodec.Type.CONFIG, configuration.type());
        assertTrue(!configuration.config().showTracer());
    }

    @Test
    void dispatchesDungeonRoutes() {
        WaypointGroup route = WaypointGroup.create("Crypt A", "crypt-a");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.add(new Waypoint(4, 68, 9, "Chest", 0xAA5500,
                Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET, 0.0));

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(
                UniversalShareCodec.encodeDungeon(List.of(route)));

        UniversalShareCodec.DungeonRoutes dungeon =
                assertInstanceOf(UniversalShareCodec.DungeonRoutes.class, decoded);
        assertEquals(UniversalShareCodec.Type.DUNGEON, dungeon.type());
        assertEquals(1, dungeon.result().groups().size());
        assertEquals(route.waypoints(), dungeon.result().groups().getFirst().waypoints());
    }

    @Test
    void odinDungeonWaypointsImportAsRoomRoutesWithTheirColors() {
        String odin = """
                {
                  "Entrance": [
                    {
                      "blockPos": {"x": 15, "y": 71, "z": 14},
                      "color": "#55FF55FF",
                      "filled": false,
                      "depth": false,
                      "aabb": {"minX": 0.0, "minY": 0.0, "minZ": 0.0, "maxX": 1.0, "maxY": 1.0, "maxZ": 1.0}
                    }
                  ]
                }
                """;

        UniversalShareCodec.DungeonRoutes dungeon = assertInstanceOf(
                UniversalShareCodec.DungeonRoutes.class, UniversalShareCodec.decode(odin));

        WaypointGroup room = dungeon.result().groups().getFirst();
        assertEquals(WaypointGroup.RouteKind.DUNGEON, room.routeKind());
        assertEquals("entrance", room.zoneId());
        Waypoint waypoint = room.get(0);
        assertEquals(List.of(15, 71, 14), List.of(waypoint.x(), waypoint.y(), waypoint.z()));
        assertEquals(0x55FF55, waypoint.color());
    }

    @Test
    void compressedOdinRoomPacksAlsoImportAsRoomRoutes() {
        WaypointGroup altar = WaypointGroup.create("Altar", "altar");
        altar.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        altar.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        altar.add(new Waypoint(19, 83, 43, "Chest", 0x00FF00,
                Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET, 0.0));
        String odinCode = WaypointExportCodec.encode(List.of(altar),
                WaypointCodec.Options.FULL_FIDELITY, WaypointExportCodec.Target.ODIN);

        UniversalShareCodec.DungeonRoutes dungeon = assertInstanceOf(
                UniversalShareCodec.DungeonRoutes.class, UniversalShareCodec.decode(odinCode));

        Waypoint chest = dungeon.result().groups().getFirst().get(0);
        assertEquals(List.of(19, 83, 43), List.of(chest.x(), chest.y(), chest.z()));
        assertEquals(0x00FF00, chest.color());
    }

    @Test
    void islandWaypointsWithBlockPositionsStayIslandRoutes() {
        String notRooms = """
                {"Hub": [{"blockPos": {"x": -3, "y": 70, "z": -60}, "color": "#FF0000FF"}]}
                """;

        assertInstanceOf(UniversalShareCodec.Waypoints.class, UniversalShareCodec.decode(notRooms));
    }

    @Test
    void rejectsOversizedPayloadsBeforeDispatchingToFeatureCodecs() {
        String body = "A".repeat(WaypointImporter.MAX_TEXT_PAYLOAD_CHARS);

        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(WaypointerConfigCodec.MAGIC + body));
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(DungeonRoomShareCodec.MAGIC + body));
    }
}
