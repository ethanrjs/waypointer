package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomRouteSyncTest {

    private DungeonRoomRouteSync sync;

    @BeforeEach
    @AfterEach
    void clearRuntimeData() {
        if (sync != null) {
            sync.uninstall();
            sync = null;
        }
        DungeonRoomData.clearAllCustom();
    }

    @Test
    void syncCreatesRuntimeRouteGroupFromRoomLocalDungeonWaypoints() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        DungeonRoom room = room("sync-room", "Sync Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("sync-room", "Sync Room", room);
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "secret",
                1,
                DungeonSecretCategory.CHEST,
                DungeonWaypointTrigger.OPEN_CHEST,
                4,
                70,
                7,
                "",
                List.of(new DungeonHighlight(
                        5,
                        71,
                        8,
                        DungeonHighlightStyle.OUTLINE_FILLED,
                        0x123456))));
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "support",
                0,
                DungeonSecretCategory.DEFAULT,
                DungeonWaypointTrigger.MANUAL,
                9,
                70,
                9,
                "support",
                List.of()));

        tracker.setCurrentRoom(room);

        WaypointGroup group = manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room"));
        assertNotNull(group);
        assertTrue(group.runtimeOnly());
        assertEquals("sync-room", group.zoneId());
        assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(2, group.size(), "support/non-progress records should not render as route points");

        Waypoint secret = group.get(0);
        assertEquals(93, secret.x());
        assertEquals(70, secret.y());
        assertEquals(204, secret.z());
        assertFalse(secret.hasName(), "normal route labels will fall back to index labels");
        assertEquals(DungeonSecretCategory.CHEST.defaultColor, secret.color());
        assertTrue(secret.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));
        assertFalse(secret.hasFlag(Waypoint.FLAG_SKIP_ON_STAND));

        Waypoint highlight = group.get(1);
        assertEquals(92, highlight.x());
        assertEquals(71, highlight.y());
        assertEquals(205, highlight.z());
        assertTrue(highlight.isSubwaypoint());
        assertTrue(highlight.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
        assertEquals(0x123456, highlight.color());
    }

    @Test
    void manualDungeonWaypointsDefaultToStandSkip() {
        DungeonRoom room = room("manual-room", "Manual Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("manual-room", "Manual Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "secret",
                1,
                DungeonSecretCategory.DEFAULT,
                DungeonWaypointTrigger.MANUAL,
                4,
                70,
                7,
                "",
                List.of()));

        WaypointGroup group = DungeonRoomRouteSync.routeGroupForRoom(room, definition);

        assertTrue(group.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_STAND));
        assertFalse(group.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));
    }

    @Test
    void bundledDungeonDefinitionsDoNotCreateRuntimeRouteGroups() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        assertTrue(DungeonRoomData.definition("altar").waypoints().isEmpty());

        tracker.setCurrentRoom(room("altar", "Altar"));

        assertNull(manager.get(DungeonRoomRouteSync.generatedGroupId("altar")));
    }

    @Test
    void userRoomRouteGroupTakesPrecedenceOverGeneratedDungeonGroup() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        DungeonRoom room = room("sync-room", "Sync Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("sync-room", "Sync Room", room);
        DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                "secret",
                DungeonSecretCategory.CHEST,
                4,
                70,
                7,
                ""));
        tracker.setCurrentRoom(room);
        assertNotNull(manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room")));

        WaypointGroup userRoute = WaypointGroup.create("User Route", "sync-room");
        userRoute.add(Waypoint.at(1, 2, 3));
        manager.add(userRoute);

        assertNull(manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room")));
        assertEquals(List.of(userRoute), manager.groupsForZone("sync-room"));
    }

    @Test
    void clearingRoomWaypointsStopsGeneratedDungeonRouteFromRespawning() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        DungeonRoom room = room("sync-room", "Sync Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("sync-room", "Sync Room", room);
        DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                "secret",
                DungeonSecretCategory.CHEST,
                4,
                70,
                7,
                ""));
        tracker.setCurrentRoom(room);
        assertNotNull(manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room")));

        DungeonRoomData.clearWaypoints("sync-room");

        assertNull(manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room")));
        assertTrue(DungeonRoomData.definition("sync-room").waypoints().isEmpty());
    }

    private static DungeonRoom room(String id, String name) {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NE,
                100,
                200,
                List.of(DungeonRoom.packSegment(100, 200)),
                id,
                name,
                DungeonDetectionConfidence.CORE_CONFIRMED);
    }
}
