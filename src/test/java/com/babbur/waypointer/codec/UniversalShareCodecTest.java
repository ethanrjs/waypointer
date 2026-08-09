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
    void rejectsOversizedPayloadsBeforeDispatchingToFeatureCodecs() {
        String body = "A".repeat(WaypointImporter.MAX_TEXT_PAYLOAD_CHARS);

        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(WaypointerConfigCodec.MAGIC + body));
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(DungeonRoomShareCodec.MAGIC + body));
    }
}
