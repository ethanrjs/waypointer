package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "crypt-a", "Crypt A", DungeonRoomType.ROOM, DungeonRoomShape.ONE_BY_ONE,
                List.of(), List.of(), List.of(DungeonWaypoint.plain(
                        "crypt-a:1", DungeonSecretCategory.CHEST, 4, 68, 9, "Chest")));

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(
                UniversalShareCodec.encodeDungeon(List.of(definition)));

        UniversalShareCodec.DungeonRoutes dungeon =
                assertInstanceOf(UniversalShareCodec.DungeonRoutes.class, decoded);
        assertEquals(UniversalShareCodec.Type.DUNGEON, dungeon.type());
        assertEquals(1, dungeon.result().definitions().size());
        assertEquals(definition, dungeon.result().definitions().get(0));
    }
}
