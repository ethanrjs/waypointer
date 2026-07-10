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
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode(),
                "secrets navigate one at a time");
        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(3, group.size(),
                "secret + its highlight + the support marker should all render");

        Waypoint secret = group.get(0);
        assertEquals(93, secret.x());
        assertEquals(70, secret.y());
        assertEquals(204, secret.z());
        assertFalse(secret.hasName(), "normal route labels will fall back to index labels");
        assertEquals(DungeonRoomRouteSync.SECRET_WAYPOINT_COLOR, secret.color(),
                "every progress secret shares the uniform route color");
        assertTrue(secret.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));
        assertFalse(secret.hasFlag(Waypoint.FLAG_SKIP_ON_STAND));

        Waypoint highlight = group.get(1);
        assertEquals(92, highlight.x());
        assertEquals(71, highlight.y());
        assertEquals(205, highlight.z());
        assertTrue(highlight.isSubwaypoint());
        assertTrue(highlight.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
        assertEquals(DungeonRoomRouteSync.SUPPORT_WAYPOINT_COLOR, highlight.color(),
                "support markers share the uniform subwaypoint color");

        Waypoint marker = group.get(2);
        assertTrue(marker.isSubwaypoint(),
                "non-progress records render as persistent markers outside route progression");
        assertFalse(marker.hasFlag(Waypoint.FLAG_SKIP_ON_STAND));
        assertFalse(marker.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));
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
    void foundSecretsDropOutOfTheMirroredGroupAndCompletedRoomsHideIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, new DungeonConfig());
        sync.install();

        DungeonRoom room = room("progress-room", "Progress Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("progress-room", "Progress Room", room);
        DungeonRoomData.addWaypoint(definition.id(),
                DungeonWaypoint.plain("first", DungeonSecretCategory.CHEST, 4, 70, 7, ""));
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "second", 2, DungeonSecretCategory.LEVER, 8, 70, 9, "", List.of()));
        tracker.setCurrentRoom(room);

        String groupId = DungeonRoomRouteSync.generatedGroupId("progress-room");
        assertEquals(2, manager.get(groupId).size());

        session.markFound(room, 1);
        assertEquals(1, manager.get(groupId).size(),
                "found secrets should disappear from the mirrored group");

        session.markFound(room, 2);
        assertNull(manager.get(groupId),
                "a fully completed room should drop its route group");

        session.resetRoom(room);
        assertEquals(2, manager.get(groupId).size(),
                "resetting the room should bring the route back");
    }

    @Test
    void markRoomCompleteHidesTheGroupLikeAGreenCheckmarkWould() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, new DungeonConfig());
        sync.install();

        DungeonRoom room = room("check-room", "Check Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("check-room", "Check Room", room);
        DungeonRoomData.addWaypoint(definition.id(),
                DungeonWaypoint.plain("only", DungeonSecretCategory.CHEST, 4, 70, 7, ""));
        tracker.setCurrentRoom(room);
        assertNotNull(manager.get(DungeonRoomRouteSync.generatedGroupId("check-room")));

        session.markRoomComplete(room);

        assertTrue(session.isRoomComplete(room));
        assertNull(manager.get(DungeonRoomRouteSync.generatedGroupId("check-room")));
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
    void userRoomRouteGroupProjectsIntoTheGeneratedMirrorInsteadOfTheSecrets() {
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
        assertEquals("Dungeon Secrets -- Sync Room",
                manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room")).name());

        WaypointGroup userRoute = WaypointGroup.create("User Route", "sync-room");
        userRoute.add(Waypoint.at(4, 70, 7));
        manager.add(userRoute);

        WaypointGroup mirror = manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room"));
        assertNotNull(mirror, "the user route projects into the generated mirror");
        assertEquals("User Route", mirror.name());
        assertTrue(mirror.runtimeOnly());
        // Same room-local coordinates as the definition secret, so the same
        // world projection (NE room, corner 100/200).
        assertEquals(93, mirror.get(0).x());
        assertEquals(70, mirror.get(0).y());
        assertEquals(204, mirror.get(0).z());
    }

    @Test
    void disabledUserRouteHidesTheMirrorWithoutResurrectingSecrets() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        DungeonRoom room = room("sync-room", "Sync Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("sync-room", "Sync Room", room);
        DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                "secret", DungeonSecretCategory.CHEST, 4, 70, 7, ""));
        tracker.setCurrentRoom(room);

        WaypointGroup userRoute = WaypointGroup.create("User Route", "sync-room");
        userRoute.add(Waypoint.at(1, 70, 1));
        userRoute.setEnabled(false);
        manager.add(userRoute);

        assertNull(manager.get(DungeonRoomRouteSync.generatedGroupId("sync-room")),
                "a hidden user route hides the room outright rather than falling back to secrets");
    }

    @Test
    void mirrorRebuildsKeepTheCurrentWaypoint() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        DungeonRoom room = room("sync-room", "Sync Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("sync-room", "Sync Room", room);
        DungeonRoomData.addWaypoint(definition.id(),
                DungeonWaypoint.plain("first", DungeonSecretCategory.CHEST, 4, 70, 7, ""));
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "second", 2, DungeonSecretCategory.LEVER, 8, 70, 9, "", List.of()));
        tracker.setCurrentRoom(room);

        String groupId = DungeonRoomRouteSync.generatedGroupId("sync-room");
        manager.get(groupId).setCurrentIndex(1);

        // Any unrelated data change rebuilds the mirror; progress must survive.
        manager.fireDataChanged();

        assertEquals(1, manager.get(groupId).currentIndex(),
                "rebuilding the mirror must not snap the route back to waypoint #1");
    }

    @Test
    void editableRouteFromDefinitionKeepsRoomLocalCoordinatesAndUniformColors() {
        DungeonRoom room = room("convert-room", "Convert Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("convert-room", "Convert Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "secret", 1, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 7, "Secret 1",
                List.of(new DungeonHighlight(5, 71, 8, DungeonHighlightStyle.OUTLINE, 0x123456))));
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "marker", 0, DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.MANUAL,
                9, 70, 9, "support", List.of()));

        WaypointGroup route = DungeonRoomRouteSync.editableRouteFromDefinition(definition);

        assertEquals("convert-room", route.zoneId());
        assertFalse(route.runtimeOnly(), "converted routes persist like any user route");
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, route.loadMode());
        assertEquals(3, route.size());

        Waypoint secret = route.get(0);
        assertEquals(4, secret.x(), "coordinates stay room-local; the sync mirror projects them");
        assertEquals(70, secret.y());
        assertEquals(7, secret.z());
        assertEquals(DungeonRoomRouteSync.SECRET_WAYPOINT_COLOR, secret.color());
        assertTrue(secret.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT));

        Waypoint highlight = route.get(1);
        assertTrue(highlight.isSubwaypoint());
        assertEquals(DungeonRoomRouteSync.SUPPORT_WAYPOINT_COLOR, highlight.color());

        Waypoint marker = route.get(2);
        assertTrue(marker.isSubwaypoint());
        assertEquals(DungeonRoomRouteSync.SUPPORT_WAYPOINT_COLOR, marker.color());
    }

    @Test
    void writeThroughHelpersDescribeTheRoomState() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonRoom room = room("helper-room", "Helper Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("helper-room", "Helper Room", room);
        DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                "secret", DungeonSecretCategory.CHEST, 4, 70, 7, ""));

        // Secrets installed, no user route: in-world edits must be refused
        // until the user converts the secrets into their own route.
        assertNull(DungeonRoomRouteSync.storedRouteForRoom(manager, "helper-room"));
        assertTrue(DungeonRoomRouteSync.secretsRequireConversion(manager, "helper-room"));

        WaypointGroup stored = WaypointGroup.create("User Route", "helper-room");
        stored.add(Waypoint.at(1, 70, 1));
        manager.add(stored);

        assertEquals(stored, DungeonRoomRouteSync.storedRouteForRoom(manager, "helper-room"));
        assertFalse(DungeonRoomRouteSync.secretsRequireConversion(manager, "helper-room"));

        WaypointGroup mirror = new WaypointGroup(
                DungeonRoomRouteSync.generatedGroupId("helper-room"), "User Route", "helper-room");
        mirror.setRuntimeOnly(true);
        assertEquals(stored, DungeonRoomRouteSync.storedSourceForMirror(manager, mirror));
        assertEquals(stored, DungeonRoomRouteSync.durableEditTarget(manager, mirror));
        assertEquals(stored, DungeonRoomRouteSync.durableEditTarget(manager, stored));
        assertNull(DungeonRoomRouteSync.storedSourceForMirror(manager, stored),
                "only generated mirrors have a stored source");

        manager.remove(stored.id());
        assertNull(DungeonRoomRouteSync.durableEditTarget(manager, mirror),
                "downloaded definition-only mirrors must require explicit conversion");
    }

    @Test
    void storedDungeonRoomGroupsNeverSurfaceAsActiveGroups() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonRoom room = room("surface-room", "Surface Room");
        DungeonRoomData.defineRoom("surface-room", "Surface Room", room);

        WaypointGroup stored = WaypointGroup.create("User Route", "surface-room");
        stored.add(Waypoint.at(4, 70, 7));
        manager.add(stored);
        manager.onZoneChanged(new dev.ethan.waypointer.core.Zone("surface-room", "Surface Room"));

        assertTrue(manager.activeGroups().isEmpty(),
                "room-local stored groups only act through the projected mirror");
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
