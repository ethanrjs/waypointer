package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertEquals(0x123456, highlight.color(),
                "explicit highlight colors remain authoritative");

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
    void dungeonWaypointsAutomaticallyColorizeByActionType() {
        DungeonRoom room = room("color-room", "Color Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "color-room", "Color Room", room);
        DungeonWaypointTrigger[] triggers = {
                DungeonWaypointTrigger.ETHERWARP,
                DungeonWaypointTrigger.BREAK_BLOCKS,
                DungeonWaypointTrigger.INTERACT_BLOCK,
                DungeonWaypointTrigger.USE_SUPERBOOM,
                DungeonWaypointTrigger.PICKUP_ITEM,
                DungeonWaypointTrigger.KILL_BAT,
                DungeonWaypointTrigger.THROW_PEARL,
                DungeonWaypointTrigger.OPEN_CHEST
        };
        int[] expectedColors = {
                DungeonSecretCategory.ETHERWARP.defaultColor,
                DungeonSecretCategory.STONK.defaultColor,
                DungeonSecretCategory.LEVER.defaultColor,
                DungeonSecretCategory.SUPERBOOM.defaultColor,
                DungeonSecretCategory.ITEM.defaultColor,
                DungeonSecretCategory.BAT.defaultColor,
                DungeonSecretCategory.PEARL.defaultColor,
                DungeonRoomRouteSync.SECRET_WAYPOINT_COLOR
        };
        for (int i = 0; i < triggers.length; i++) {
            definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                    "action-" + i, i + 1, DungeonSecretCategory.DEFAULT, triggers[i],
                    i, 70, i, "", List.of(DungeonHighlight.outline(i, 71, i))));
        }

        WaypointGroup group = DungeonRoomRouteSync.routeGroupForRoom(room, definition);

        for (int i = 0; i < triggers.length; i++) {
            Waypoint action = group.get(i * 2);
            Waypoint inheritedHighlight = group.get(i * 2 + 1);
            assertEquals(expectedColors[i], action.color(), triggers[i] + " action color");
            assertEquals(expectedColors[i], inheritedHighlight.color(),
                    triggers[i] + " highlight should inherit its action color");
        }
    }

    @Test
    void automaticDungeonColorsComeFromDungeonConfig() {
        DungeonRoom room = room("configured-color-room", "Configured Color Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "configured-color-room", "Configured Color Room", room);
        DungeonWaypointTrigger[] triggers = {
                DungeonWaypointTrigger.OPEN_CHEST,
                DungeonWaypointTrigger.ETHERWARP,
                DungeonWaypointTrigger.BREAK_BLOCKS,
                DungeonWaypointTrigger.INTERACT_BLOCK,
                DungeonWaypointTrigger.USE_SUPERBOOM,
                DungeonWaypointTrigger.PICKUP_ITEM,
                DungeonWaypointTrigger.KILL_BAT,
                DungeonWaypointTrigger.DUNGEONBREAKER,
                DungeonWaypointTrigger.THROW_PEARL
        };
        int[] colors = {
                0x010101, 0x020202, 0x030303, 0x040404, 0x050505,
                0x060606, 0x070707, 0x080808, 0x090909
        };
        for (int i = 0; i < triggers.length; i++) {
            definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                    "configured-" + i, i + 1, DungeonSecretCategory.DEFAULT, triggers[i],
                    i, 70, i, "", List.of(DungeonHighlight.outline(i, 71, i))));
        }
        DungeonConfig config = new DungeonConfig();
        config.setAutomaticSecretColor(colors[0]);
        config.setAutomaticEtherwarpColor(colors[1]);
        config.setAutomaticBreakBlocksColor(colors[2]);
        config.setAutomaticInteractColor(colors[3]);
        config.setAutomaticSuperboomColor(colors[4]);
        config.setAutomaticItemColor(colors[5]);
        config.setAutomaticBatColor(colors[6]);
        config.setAutomaticDungeonbreakerColor(colors[7]);
        config.setAutomaticPearlColor(colors[8]);

        WaypointGroup group = DungeonRoomRouteSync.routeGroupForRoom(
                room, definition, null, config);

        for (int i = 0; i < triggers.length; i++) {
            assertEquals(colors[i], group.get(i * 2).color(), triggers[i] + " action color");
            assertEquals(colors[i], group.get(i * 2 + 1).color(),
                    triggers[i] + " highlight color");
        }
    }

    @Test
    void explicitDungeonWaypointAndHighlightColorsOverrideAutomaticColors() {
        DungeonRoom room = room("custom-color-room", "Custom Color Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "custom-color-room", "Custom Color Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "custom", 1, DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.ETHERWARP,
                1, 70, 1, "", List.of(new DungeonHighlight(
                        2, 71, 2, DungeonHighlightStyle.OUTLINE, 0x654321)), 0x123456));

        WaypointGroup group = DungeonRoomRouteSync.routeGroupForRoom(room, definition);

        assertEquals(0x123456, group.get(0).color());
        assertEquals(0x654321, group.get(1).color());
    }

    @Test
    void transformedUserRouteCarriesWaypointPaintIntoRuntimeMirror() {
        DungeonRoom room = room("paint-room", "Paint Room");
        WaypointGroup source = WaypointGroup.create("Painted Route", "paint-room");
        source.add(Waypoint.at(1, 70, 2));
        source.setPaint(WaypointPaint.solid(0xBADA55));
        source.setPaintEnabled(false);

        WaypointGroup mirror = DungeonRoomRouteSync.transformedRouteGroupForRoom(
                room, source, null);

        assertEquals(source.paint(), mirror.paint());
        assertFalse(mirror.paintEnabled());
    }

    @Test
    void foundSecretSubwaypointsRemainUntilTheNextSecretIsFound() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, new DungeonConfig());
        sync.install();

        DungeonRoom room = room("progress-room", "Progress Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("progress-room", "Progress Room", room);
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "first", 1, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 7, "", List.of(DungeonHighlight.outline(5, 71, 8))));
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "second", 2, DungeonSecretCategory.LEVER, 8, 70, 9, "", List.of()));
        tracker.setCurrentRoom(room);

        String groupId = DungeonRoomRouteSync.generatedGroupId("progress-room");
        assertEquals(3, manager.get(groupId).size());

        session.markFound(room, 1);
        WaypointGroup held = manager.get(groupId);
        assertEquals(1, held.size(),
                "a completed stage disappears immediately so only the next secret remains");
        assertEquals(-1, held.activeSubwaypointParentIndex());
        assertEquals(0, held.currentIndex());
        assertArrayEquals(new int[] { 0 }, visibleIndices(held));

        session.markFound(room, 2);
        assertNull(manager.get(groupId),
                "a fully completed room should drop its route group");

        session.resetRoom(room);
        assertEquals(3, manager.get(groupId).size(),
                "resetting the room should bring the route back");
    }

    @Test
    void findingAnotherSecretReplacesThePreviousSubwaypointHold() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, new DungeonConfig());
        sync.install();

        DungeonRoom room = room("hold-room", "Hold Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("hold-room", "Hold Room", room);
        for (int secret = 1; secret <= 3; secret++) {
            int x = secret * 4;
            DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                    "secret-" + secret, secret, DungeonSecretCategory.CHEST,
                    DungeonWaypointTrigger.OPEN_CHEST, x, 70, 7, "",
                    List.of(DungeonHighlight.outline(x + 1, 71, 8))));
        }
        tracker.setCurrentRoom(room);
        String groupId = DungeonRoomRouteSync.generatedGroupId("hold-room");

        session.markFound(room, 1);
        WaypointGroup firstHold = manager.get(groupId);
        assertEquals(-1, firstHold.activeSubwaypointParentIndex());
        assertEquals(208, firstHold.get(0).z());

        session.markFound(room, 2);
        WaypointGroup secondHold = manager.get(groupId);
        assertEquals(-1, secondHold.activeSubwaypointParentIndex());
        assertEquals(0, secondHold.currentIndex());
        assertEquals(1, secondHold.mainWaypointCount());
        assertEquals(212, secondHold.get(0).z(),
                "the third secret should become the sole current stage");
    }

    @Test
    void outOfOrderFoundSecretDoesNotReplaceTheCurrentHold() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, new DungeonConfig());
        sync.install();

        DungeonRoom room = room("ordered-hold-room", "Ordered Hold Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "ordered-hold-room", "Ordered Hold Room", room);
        for (int secret = 1; secret <= 3; secret++) {
            int x = secret * 4;
            DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                    "secret-" + secret, secret, DungeonSecretCategory.CHEST,
                    DungeonWaypointTrigger.OPEN_CHEST, x, 70, 7, "",
                    List.of(DungeonHighlight.outline(x + 1, 71, 8))));
        }
        tracker.setCurrentRoom(room);
        String groupId = DungeonRoomRouteSync.generatedGroupId("ordered-hold-room");

        session.markFound(room, 1);
        session.markFound(room, 3);

        WaypointGroup held = manager.get(groupId);
        assertEquals(-1, held.activeSubwaypointParentIndex());
        assertEquals(0, held.currentIndex());
        assertEquals(1, held.mainWaypointCount());
        assertEquals(208, held.get(0).z());
    }

    @Test
    void clearingNextSecretWithoutChildrenDropsThePreviousHold() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, new DungeonConfig());
        sync.install();

        DungeonRoom room = room("drop-hold-room", "Drop Hold Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "drop-hold-room", "Drop Hold Room", room);
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "first", 1, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 7, "", List.of(DungeonHighlight.outline(5, 71, 8))));
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "second", 2, DungeonSecretCategory.LEVER, 8, 70, 9, "", List.of()));
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "third", 3, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                12, 70, 11, "", List.of()));
        tracker.setCurrentRoom(room);
        String groupId = DungeonRoomRouteSync.generatedGroupId("drop-hold-room");

        session.markFound(room, 1);
        assertEquals(-1, manager.get(groupId).activeSubwaypointParentIndex());

        session.markFound(room, 2);
        WaypointGroup remaining = manager.get(groupId);
        assertEquals(-1, remaining.activeSubwaypointParentIndex());
        assertEquals(1, remaining.size());
        assertEquals(212, remaining.get(0).z());
    }

    @Test
    void mirrorRebuildCarriesActiveSubwaypointParentHold() {
        WaypointGroup previous = new WaypointGroup("old", "Route", "dungeon_f7");
        previous.add(Waypoint.at(0, 70, 0));
        previous.add(Waypoint.at(1, 70, 0).withSubwaypoint(true));
        previous.add(Waypoint.at(8, 70, 0));
        previous.advancePast(0);

        WaypointGroup rebuilt = new WaypointGroup("new", "Route", "dungeon_f7");
        rebuilt.add(Waypoint.at(0, 70, 0));
        rebuilt.add(Waypoint.at(1, 70, 0).withSubwaypoint(true));
        rebuilt.add(Waypoint.at(8, 70, 0));

        DungeonRoomRouteSync.carryOverProgress(previous, rebuilt);

        assertEquals(0, rebuilt.activeSubwaypointParentIndex());
        assertEquals(2, rebuilt.currentIndex());
        assertArrayEquals(new int[] { 0, 1, 2 }, visibleIndices(rebuilt));
    }

    @Test
    void mirrorRebuildCarriesTheCorrectDuplicateCoordinateParent() {
        WaypointGroup previous = duplicateCoordinateRoute("old");
        previous.setCurrentIndex(2);
        previous.advancePast(2);

        WaypointGroup rebuilt = duplicateCoordinateRoute("new");
        DungeonRoomRouteSync.carryOverProgress(previous, rebuilt);

        assertEquals(2, rebuilt.activeSubwaypointParentIndex());
        assertEquals(4, rebuilt.currentIndex());
    }

    @Test
    void mirrorRebuildKeepsCompletedUserRouteComplete() {
        WaypointGroup previous = duplicateCoordinateRoute("old");
        previous.advancePast(previous.lastMainIndex());
        assertTrue(previous.isComplete());

        WaypointGroup rebuilt = duplicateCoordinateRoute("new");
        DungeonRoomRouteSync.carryOverProgress(previous, rebuilt);

        assertTrue(rebuilt.isComplete());
    }

    @Test
    void completedRoomKeepsFinalSubwaypointsWhenHidingIsDisabled() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        config.setHideCompletedRooms(false);
        DungeonStateTracker tracker = new DungeonStateTracker(manager, config);
        DungeonRouteSession session = new DungeonRouteSession();
        sync = new DungeonRoomRouteSync(manager, tracker, session, config);
        sync.install();

        DungeonRoom room = room("visible-complete-room", "Visible Complete Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "visible-complete-room", "Visible Complete Room", room);
        DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "only", 1, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 7, "", List.of(DungeonHighlight.outline(5, 71, 8))));
        tracker.setCurrentRoom(room);
        String groupId = DungeonRoomRouteSync.generatedGroupId("visible-complete-room");

        session.markFound(room, 1);

        WaypointGroup completed = manager.get(groupId);
        assertNotNull(completed);
        assertTrue(completed.isComplete());
        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(completed));

        manager.fireDataChanged();

        WaypointGroup rebuilt = manager.get(groupId);
        assertNotNull(rebuilt);
        assertTrue(rebuilt.isComplete());
        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(rebuilt));
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
    void dungeonMasterSwitchRemovesAndRestoresTheRuntimeMirror() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, config);
        sync = new DungeonRoomRouteSync(manager, tracker, new DungeonRouteSession(), config);
        sync.install();

        DungeonRoom room = room("switch-room", "Switch Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("switch-room", "Switch Room", room);
        DungeonRoomData.addWaypoint(definition.id(),
                DungeonWaypoint.plain("only", DungeonSecretCategory.CHEST, 4, 70, 7, ""));
        tracker.setCurrentRoom(room);
        String generatedId = DungeonRoomRouteSync.generatedGroupId("switch-room");
        assertNotNull(manager.get(generatedId));

        config.setEnabled(false);

        assertNull(tracker.currentRoom(), "disabled dungeon consumers must not see a stale room");
        assertNull(manager.get(generatedId), "disabling must remove the rendered runtime mirror");

        config.setEnabled(true);

        assertEquals(room, tracker.currentRoom());
        assertNotNull(manager.get(generatedId), "re-enabling should restore the current room route");
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
    void manualStoredProgressChangesUpdateTheRenderedMirrorAcrossRebuilds() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        DungeonRoom room = room("manual-progress-room", "Manual Progress Room");
        DungeonRoomData.defineRoom("manual-progress-room", "Manual Progress Room", room);

        WaypointGroup stored = WaypointGroup.create("User Route", "manual-progress-room");
        stored.add(Waypoint.at(1, 70, 1));
        stored.add(Waypoint.at(2, 70, 2));
        stored.add(Waypoint.at(3, 70, 3));
        manager.add(stored);
        tracker.setCurrentRoom(room);

        String mirrorId = DungeonRoomRouteSync.generatedGroupId("manual-progress-room");
        WaypointGroup mirror = manager.get(mirrorId);
        mirror.setCurrentIndex(1);

        DungeonRoomRouteSync.setManualCurrentIndex(manager, stored, 2);
        manager.fireDataChanged();

        assertEquals(2, stored.currentIndex());
        assertEquals(2, manager.get(mirrorId).currentIndex(),
                "a manual selection must replace stale runtime progress");
        assertArrayEquals(new int[] { 2 }, visibleIndices(manager.get(mirrorId)));

        DungeonRoomRouteSync.resetManualProgress(manager, stored);
        manager.fireDataChanged();

        assertEquals(0, stored.currentIndex());
        assertEquals(0, manager.get(mirrorId).currentIndex(),
                "reset must restore the rendered route to its first waypoint");
        assertArrayEquals(new int[] { 0 }, visibleIndices(manager.get(mirrorId)));
    }

    @Test
    void definitionRouteVisibilitySurvivesRuntimeMirrorRebuilds() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, config);
        sync = new DungeonRoomRouteSync(manager, tracker, new DungeonRouteSession(), config);
        sync.install();
        DungeonRoom room = room("toggle-definition-room", "Toggle Definition Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "toggle-definition-room", "Toggle Definition Room", room);
        DungeonRoomData.addWaypoint(definition.id(),
                DungeonWaypoint.plain("secret", DungeonSecretCategory.CHEST, 1, 70, 1, ""));
        tracker.setCurrentRoom(room);

        String mirrorId = DungeonRoomRouteSync.generatedGroupId(definition.id());
        WaypointGroup mirror = manager.get(mirrorId);
        assertTrue(mirror.enabled());

        DungeonRoomRouteSync.setRouteEnabled(manager, config, mirror, false);
        manager.fireDataChanged();

        assertFalse(config.roomRouteEnabled(definition.id()));
        assertNotNull(manager.get(mirrorId));
        assertFalse(manager.get(mirrorId).enabled(),
                "a hidden definition mirror must not resurrect enabled during sync");

        DungeonRoomRouteSync.setRouteEnabled(manager, config, manager.get(mirrorId), true);
        manager.fireDataChanged();

        assertTrue(config.roomRouteEnabled(definition.id()));
        assertTrue(manager.get(mirrorId).enabled());
    }

    @Test
    void storedRouteVisibilityControlsItsRuntimeMirror() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, config);
        sync = new DungeonRoomRouteSync(manager, tracker, new DungeonRouteSession(), config);
        sync.install();
        DungeonRoom room = room("toggle-stored-room", "Toggle Stored Room");
        DungeonRoomData.defineRoom("toggle-stored-room", "Toggle Stored Room", room);
        WaypointGroup stored = WaypointGroup.create("User Route", "toggle-stored-room");
        stored.add(Waypoint.at(1, 70, 1));
        manager.add(stored);
        tracker.setCurrentRoom(room);

        String mirrorId = DungeonRoomRouteSync.generatedGroupId("toggle-stored-room");
        assertNotNull(manager.get(mirrorId));

        DungeonRoomRouteSync.setRouteEnabled(manager, config, stored, false);
        manager.fireDataChanged();

        assertFalse(stored.enabled());
        assertNull(manager.get(mirrorId), "a hidden stored route removes its runtime projection");

        DungeonRoomRouteSync.setRouteEnabled(manager, config, stored, true);
        manager.fireDataChanged();

        assertTrue(stored.enabled());
        assertTrue(manager.get(mirrorId).enabled());
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
        assertEquals(0x123456, highlight.color());

        Waypoint marker = route.get(2);
        assertTrue(marker.isSubwaypoint());
        assertEquals(DungeonRoomRouteSync.SUPPORT_WAYPOINT_COLOR, marker.color());
    }

    @Test
    void editableRoutePreservesExplicitWaypointColors() {
        DungeonRoom room = room("colored-convert-room", "Colored Convert Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("colored-convert-room", "Colored Convert Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "secret", 1, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 7, "Chest", List.of(), 0x123456));
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "marker", 0, DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.MANUAL,
                9, 70, 9, "", List.of(), 0x654321));

        WaypointGroup route = DungeonRoomRouteSync.editableRouteFromDefinition(definition);

        assertEquals(0x123456, route.get(0).color());
        assertEquals(0x654321, route.get(1).color());
    }

    @Test
    void oneSecretStageAdvancesThroughEachRecordedActionIndividually() {
        DungeonRoom room = room("stage-room", "Stage Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("stage-room", "Stage Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "TP", 1, DungeonSecretCategory.ETHERWARP, DungeonWaypointTrigger.ETHERWARP,
                1, 70, 1, "TP", List.of()));
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "", 1, DungeonSecretCategory.DUNGEONBREAKER,
                DungeonWaypointTrigger.DUNGEONBREAKER,
                2, 70, 2, "", List.of()));
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "Item", 1, DungeonSecretCategory.ITEM, DungeonWaypointTrigger.PICKUP_ITEM,
                3, 70, 3, "Item", List.of()));
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "Chest", 2, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 4, "Chest", List.of()));

        WaypointGroup route = DungeonRoomRouteSync.editableRouteFromDefinition(definition);
        route.setVisibleMainSteps(2);

        assertFalse(route.get(0).isSubwaypoint());
        assertTrue(route.get(1).isSubwaypoint());
        assertTrue(route.get(2).isSubwaypoint());
        assertFalse(route.get(3).isSubwaypoint());
        assertArrayEquals(new int[] { 0, 3 }, visibleIndices(route));

        route.advancePast(0);
        assertEquals(1, route.currentIndex());
        assertArrayEquals(new int[] { 1, 3 }, visibleIndices(route));
        route.advancePast(1);
        assertEquals(2, route.currentIndex());
        route.advancePast(2);
        assertEquals(3, route.currentIndex());
    }

    @Test
    void routeStartDistanceUsesProjectedRoomCoordinates() {
        DungeonRoom room = room("variant-room", "Variant Room");
        WaypointGroup near = WaypointGroup.create("Near", room.roomId());
        near.add(Waypoint.at(2, 70, 3));
        WaypointGroup far = WaypointGroup.create("Far", room.roomId());
        far.add(Waypoint.at(20, 70, 30));
        Waypoint projectedNear = DungeonRoomWaypointPlacement.toActualWaypoint(room, near.get(0));
        Vec3 player = new Vec3(projectedNear.centerX(), projectedNear.centerY(), projectedNear.centerZ());

        assertEquals(0.0, DungeonRoomRouteSync.startDistanceSq(room, near, player));
        assertTrue(DungeonRoomRouteSync.startDistanceSq(room, far, player) > 0.0);
    }

    @Test
    void runtimeMirrorResolvesItsExactSelectedRouteVariant() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = WaypointGroup.create("Route 1", "variant-source-room");
        first.add(Waypoint.at(1, 70, 1));
        WaypointGroup second = WaypointGroup.create("Route 2", "variant-source-room");
        second.add(Waypoint.at(2, 70, 2));
        manager.add(first);
        manager.add(second);
        WaypointGroup mirror = new WaypointGroup(
                DungeonRoomRouteSync.generatedGroupId("variant-source-room"),
                "Route 2", "variant-source-room");
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(second.id());

        assertEquals(second, DungeonRoomRouteSync.storedSourceForMirror(manager, mirror));
    }

    @Test
    void installingDefinitionsCreatesEnabledRoutesAndDisablesExistingDungeonRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonRoom room = room("import-room", "Import Room");
        DungeonRoomDefinition definition =
                DungeonRoomData.defineRoom("import-room", "Import Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                "secret", DungeonSecretCategory.CHEST, 4, 70, 7, "Secret"));

        WaypointGroup temporary = WaypointGroup.create("Temporary", definition.id());
        temporary.setTemp(true);
        temporary.add(Waypoint.at(20, 90, 20));
        manager.add(temporary);
        WaypointGroup existing = WaypointGroup.create("Existing", definition.id());
        existing.add(Waypoint.at(1, 70, 1));
        manager.add(existing);
        WaypointGroup unrelated = WaypointGroup.create("Hub", "hub");
        unrelated.add(Waypoint.at(2, 70, 2));
        manager.add(unrelated);

        List<WaypointGroup> installed = DungeonRoomRouteSync.installEditableRoutes(
                manager, config, List.of(definition));

        assertEquals(1, installed.size());
        WaypointGroup imported = installed.get(0);
        assertTrue(temporary.enabled());
        assertFalse(existing.enabled());
        assertTrue(imported.enabled());
        assertTrue(unrelated.enabled());
        assertEquals(imported,
                DungeonRoomRouteSync.storedRouteForRoom(manager, definition.id()));
        assertFalse(config.roomRouteEnabled(definition.id()));
    }

    @Test
    void importingHidesDungeonRoutesForRoomsTheImportDoesNotCover() {
        // Installing a new route set supersedes the old one everywhere, not just
        // in the rooms that happen to overlap -- otherwise the previous pack
        // keeps drawing in every room the new pack is missing.
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonRoomDefinition untouched = DungeonRoomData.defineRoom(
                "supersede-old-room", "Supersede Old Room",
                room("supersede-old-room", "Supersede Old Room"));
        untouched = DungeonRoomData.addWaypoint(untouched.id(), DungeonWaypoint.plain(
                "old-secret", DungeonSecretCategory.CHEST, 5, 70, 5, "Chest"));
        WaypointGroup oldRoute = WaypointGroup.create("Old pack route", untouched.id());
        oldRoute.add(Waypoint.at(5, 70, 5));
        manager.add(oldRoute);

        WaypointGroup hubRoute = WaypointGroup.create("Hub", "hub");
        hubRoute.add(Waypoint.at(2, 70, 2));
        manager.add(hubRoute);

        DungeonRoomDefinition incoming = DungeonRoomData.defineRoom(
                "supersede-new-room", "Supersede New Room",
                room("supersede-new-room", "Supersede New Room"));
        incoming = incoming.withWaypoints(List.of(DungeonWaypoint.plain(
                "new-secret", DungeonSecretCategory.CHEST, 9, 70, 9, "Chest")));

        List<WaypointGroup> installed = DungeonRoomRouteSync.installEditableRoutes(
                manager, config, List.of(incoming));

        assertEquals(1, installed.size());
        assertTrue(installed.get(0).enabled(), "the freshly imported route stays on");
        assertFalse(oldRoute.enabled(), "a route for an uncovered room is hidden too");
        assertTrue(hubRoute.enabled(), "non-dungeon routes are untouched");
    }

    @Test
    void installingMultipleVariantsKeepsEveryRouteForTheRoom() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonRoom room = room("variant-install-room", "Variant Install Room");
        DungeonRoomDefinition base =
                DungeonRoomData.defineRoom("variant-install-room", "Variant Install Room", room);
        DungeonRoomDefinition first = base.withDisplayName("Variant Install Room, route 1")
                .withWaypoints(List.of(DungeonWaypoint.plain(
                        "first", DungeonSecretCategory.CHEST, 1, 70, 1, "Chest")));
        DungeonRoomDefinition second = base.withDisplayName("Variant Install Room, route 2")
                .withWaypoints(List.of(DungeonWaypoint.plain(
                        "second", DungeonSecretCategory.CHEST, 10, 70, 10, "Chest")));

        List<WaypointGroup> installed = DungeonRoomRouteSync.installEditableRoutes(
                manager, config, List.of(first, second));

        assertEquals(2, installed.size());
        assertTrue(installed.get(0).enabled());
        assertTrue(installed.get(1).enabled());
        assertEquals(2, manager.groupsForZone(base.id()).stream()
                .filter(group -> !group.runtimeOnly() && !group.temp())
                .count());
    }

    @Test
    void installingMissingDefinitionsMigratesOnlyDefinitionOnlyRooms() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonConfig config = new DungeonConfig();
        DungeonRoomDefinition migrated = DungeonRoomData.defineRoom(
                "migrated-room", "Migrated Room", room("migrated-room", "Migrated Room"));
        migrated = DungeonRoomData.addWaypoint(migrated.id(), DungeonWaypoint.plain(
                "migrated-secret", DungeonSecretCategory.CHEST, 1, 70, 1, "Chest"));
        DungeonRoomDefinition retained = DungeonRoomData.defineRoom(
                "retained-room", "Retained Room", room("retained-room", "Retained Room"));
        retained = DungeonRoomData.addWaypoint(retained.id(), DungeonWaypoint.plain(
                "retained-secret", DungeonSecretCategory.CHEST, 2, 70, 2, "Chest"));
        WaypointGroup existing = WaypointGroup.create("Existing", retained.id());
        existing.add(Waypoint.at(2, 70, 2));
        manager.add(existing);

        List<WaypointGroup> migratedRoutes = DungeonRoomRouteSync.installMissingEditableRoutes(
                manager, config, List.of(migrated, retained));

        assertEquals(1, migratedRoutes.size());
        assertEquals(migrated.id(), migratedRoutes.get(0).zoneId());
        assertTrue(existing.enabled(), "unrelated stored dungeon routes stay active");
        assertEquals(existing, DungeonRoomRouteSync.storedRouteForRoom(manager, retained.id()));
    }

    @Test
    void leadingSupportMarkersNeverBecomeSequenceProgress() {
        DungeonRoom room = room("leading-support-room", "Leading Support Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "leading-support-room", "Leading Support Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "marker", 0, DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.MANUAL,
                2, 70, 2, "support", List.of()));
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "secret", 1, DungeonSecretCategory.CHEST, DungeonWaypointTrigger.OPEN_CHEST,
                4, 70, 7, "Secret 1", List.of()));

        WaypointGroup route = DungeonRoomRouteSync.editableRouteFromDefinition(definition);

        assertEquals(WaypointGroup.LoadMode.SEQUENCE, route.loadMode());
        assertEquals("Secret 1", route.get(0).name());
        assertFalse(route.get(0).isSubwaypoint());
        assertEquals("support", route.get(1).name());
        assertTrue(route.get(1).isSubwaypoint());
        assertEquals(1, route.mainWaypointCount());
    }

    @Test
    void supportOnlyDefinitionsConvertToStaticMarkers() {
        DungeonRoom room = room("support-only-room", "Support Only Room");
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                "support-only-room", "Support Only Room", room);
        definition = DungeonRoomData.addWaypoint(definition.id(), new DungeonWaypoint(
                "marker", 0, DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.MANUAL,
                2, 70, 2, "support", List.of()));

        WaypointGroup route = DungeonRoomRouteSync.editableRouteFromDefinition(definition);

        assertEquals(WaypointGroup.LoadMode.STATIC, route.loadMode());
        assertEquals(1, route.size());
        assertFalse(route.get(0).isSubwaypoint());
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
        manager.onZoneChanged(new com.babbur.waypointer.core.Zone("surface-room", "Surface Room"));

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

    private static WaypointGroup duplicateCoordinateRoute(String id) {
        WaypointGroup group = new WaypointGroup(id, "Route", "dungeon_f7");
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(1, 70, 0).withSubwaypoint(true));
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(2, 70, 0).withSubwaypoint(true));
        group.add(Waypoint.at(10, 70, 0));
        return group;
    }

    private static int[] visibleIndices(WaypointGroup group) {
        List<Integer> indices = new ArrayList<>();
        group.forEachVisibleIndex(indices::add);
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }
}
