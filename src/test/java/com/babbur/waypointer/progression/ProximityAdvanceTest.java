package com.babbur.waypointer.progression;

import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused on the progression edge cases that are easy to regress:
 * skipping ahead, staying put when out of range, and not walking off the end.
 */
class ProximityAdvanceTest {

    private static final String DUNGEON_TRIGGER_ROOM_ID = "progress-trigger-room";

    @BeforeEach
    @AfterEach
    void clearCustomDungeonRooms() {
        DungeonRoomData.clearAllCustom();
    }

    private static WaypointGroup line() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(2.0);
        g.add(Waypoint.at(0, 0, 0));
        g.add(Waypoint.at(10, 0, 0));
        g.add(Waypoint.at(20, 0, 0));
        g.add(Waypoint.at(30, 0, 0));
        return g;
    }

    private static WaypointGroup dungeonTriggerGroup() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                0,
                0,
                List.of(DungeonRoom.packSegment(0, 0)));
        var definition = DungeonRoomData.defineRoom(
                DUNGEON_TRIGGER_ROOM_ID,
                "Progress Trigger Room",
                room);
        WaypointGroup group = WaypointGroup.create("trigger", definition.id());
        group.setDefaultRadius(0.5);
        return group;
    }

    @Test
    void standSkipFlagRequiresHalfSecondOnDungeonWaypointBlock() {
        WaypointGroup group = dungeonTriggerGroup();
        group.add(Waypoint.at(0, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_STAND));

        assertFalse(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false, 1_000L));
        assertFalse(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false, 1_499L));
        assertTrue(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false, 1_500L));

        assertTrue(group.isComplete());
    }

    @Test
    void standSkipFlagIgnoresDungeonWaypointRadius() {
        WaypointGroup group = dungeonTriggerGroup();
        group.setDefaultRadius(10.0);
        group.add(Waypoint.at(0, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_STAND));

        assertFalse(ProximityTracker.advanceIfReached(group, 5.0, 0.5, 0.5,
                false, true, false, 1_000L));

        assertEquals(0, group.currentIndex());
    }

    @Test
    void standSkipInPassableSpaceFallsBackToRadius() {
        WaypointGroup group = dungeonTriggerGroup();
        group.setDefaultRadius(2.0);
        Waypoint waypoint = Waypoint.at(0, 2, 0).withFlags(Waypoint.FLAG_SKIP_ON_STAND);
        group.add(waypoint);

        assertTrue(ProximityTracker.isStandSkipReached(group, 0, waypoint,
                0.5, 1.0, 0.5, 1_000L, false));

        assertEquals(-1, group.standSkipHoldIndex());
    }

    @Test
    void standSkipHoldResetsAfterLeavingDungeonWaypointBlock() {
        WaypointGroup group = dungeonTriggerGroup();
        group.add(Waypoint.at(0, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_STAND));

        assertFalse(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false, 1_000L));
        assertFalse(ProximityTracker.advanceIfReached(group, 4.0, 1.0, 4.0,
                false, true, false, 1_600L));
        assertFalse(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false, 1_700L));
        assertTrue(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false, 2_200L));
    }

    @Test
    void standSkipFlagDoesNotAffectNormalRoutes() {
        WaypointGroup group = WaypointGroup.create("normal", "hub");
        group.setDefaultRadius(0.5);
        group.add(Waypoint.at(0, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_STAND));

        assertFalse(ProximityTracker.advanceIfReached(group, 0.1, 1.0, 0.1,
                false, true, false));

        assertEquals(0, group.currentIndex());
    }

    @Test
    void interactSkipFlagAdvancesDungeonWaypointForClickedBlock() {
        WaypointGroup group = dungeonTriggerGroup();
        group.add(Waypoint.at(4, 2, 6).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));

        assertTrue(ProximityTracker.advanceIfInteractedWithBlock(group, 4, 2, 6, false));

        assertTrue(group.isComplete());
    }

    @Test
    void interactSkipFlagIgnoresDungeonWaypointRadius() {
        WaypointGroup group = dungeonTriggerGroup();
        group.setDefaultRadius(10.0);
        group.add(Waypoint.at(0, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));

        assertFalse(ProximityTracker.advanceIfReached(group, 0.5, 0.5, 0.5,
                false, true, false, 1_000L));

        assertEquals(0, group.currentIndex());
    }

    @Test
    void interactSkipFlagIgnoresDifferentBlocks() {
        WaypointGroup group = dungeonTriggerGroup();
        group.add(Waypoint.at(4, 2, 6).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));

        assertFalse(ProximityTracker.advanceIfInteractedWithBlock(group, 5, 2, 6, false));

        assertEquals(0, group.currentIndex());
    }

    @Test
    void mineSkipAdvancesOnlyAfterObservedDungeonBlockBecomesAir() {
        WaypointGroup group = dungeonTriggerGroup();
        group.setDefaultRadius(10.0);
        group.add(Waypoint.at(4, 2, 6).withFlags(Waypoint.FLAG_SKIP_ON_MINE));
        Set<ProximityTracker.MineTarget> observed = new HashSet<>();

        assertFalse(ProximityTracker.updateMinedWaypointProgress(
                group, (x, y, z) -> ProximityTracker.MINE_BLOCK_AIR,
                observed, new HashSet<>(), false, false, 1_000L, false));
        assertFalse(ProximityTracker.advanceIfReached(group, 4.5, 2.5, 6.5,
                false, true, false, 1_000L));
        assertFalse(ProximityTracker.updateMinedWaypointProgress(
                group, (x, y, z) -> ProximityTracker.MINE_BLOCK_PRESENT,
                observed, new HashSet<>(), false, false, 1_100L, false));
        assertTrue(ProximityTracker.updateMinedWaypointProgress(
                group, (x, y, z) -> ProximityTracker.MINE_BLOCK_AIR,
                observed, new HashSet<>(), false, false, 1_200L, false));

        assertTrue(group.isComplete());
    }

    @Test
    void deferredInteractionAdvancesARebuiltRuntimeGroup() {
        WaypointGroup original = dungeonTriggerGroup();
        original.add(Waypoint.at(4, 2, 6).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.add(original);

        WaypointGroup rebuilt = new WaypointGroup(
                original.id(), original.name(), original.zoneId());
        rebuilt.add(Waypoint.at(4, 2, 6).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));
        manager.add(rebuilt);

        assertTrue(ProximityTracker.advanceDeferredInteraction(
                manager, original.id(), 0, 4, 2, 6, false));
        assertTrue(rebuilt.isComplete());
        assertFalse(original.isComplete());
    }

    @Test
    void out_of_range_does_not_advance() {
        WaypointGroup g = line();
        assertFalse(advanceIfReached(g, 5.0, 0.0, 0.0));
        assertEquals(0, g.currentIndex());
    }

    @Test
    void reaching_current_advances_one() {
        WaypointGroup g = line();
        assertTrue(advanceIfReached(g, 0.5, 0.5, 0.5));
        assertEquals(1, g.currentIndex());
    }

    @Test
    void reaching_a_future_waypoint_skips_ahead() {
        WaypointGroup g = line();
        assertTrue(advanceIfReached(g, 20.5, 0.5, 0.5));
        assertEquals(3, g.currentIndex()); // jumped from 0 straight past index 2
    }

    @Test
    void reaching_last_marks_complete() {
        WaypointGroup g = line();
        assertTrue(advanceIfReached(g, 30.5, 0.5, 0.5));
        assertTrue(g.isComplete());
    }

    @Test
    void reaching_last_loops_to_start_when_restart_enabled() {
        WaypointGroup g = line();
        assertTrue(ProximityTracker.advanceIfReached(g, 30.5, 0.5, 0.5, true));
        assertEquals(0, g.currentIndex());
        assertFalse(g.isComplete());
    }

    @Test
    void oneWaypointLoopRearmsOnlyAfterLeavingItsReachRadius() {
        WaypointGroup group = WaypointGroup.create("single", "test_zone");
        group.setDefaultRadius(2.0);
        group.add(Waypoint.at(0, 0, 0));

        assertTrue(ProximityTracker.advanceIfReached(group, 0.5, 0.5, 0.5, true));
        assertEquals(0, group.currentIndex());
        assertTrue(group.isProximitySuppressed(0));

        assertFalse(ProximityTracker.advanceIfReached(group, 0.5, 0.5, 0.5, true));
        assertFalse(ProximityTracker.advanceIfReached(group, 5.0, 0.5, 0.5, true));
        assertTrue(ProximityTracker.advanceIfReached(group, 0.5, 0.5, 0.5, true));
    }

    @Test
    void dungeonRoomRouteDoesNotLoopWhenRestartEnabled() {
        WaypointGroup group = dungeonTriggerGroup();
        group.add(Waypoint.at(0, 0, 0));

        assertTrue(ProximityTracker.advanceIfReached(group, 0.5, 0.5, 0.5, true));

        assertTrue(group.isComplete());
        assertEquals(group.size(), group.currentIndex());
    }

    @Test
    void reaching_last_parentWithSubwaypoints_loopsTracerButKeepsVisualHold() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(3);

        assertTrue(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, true));

        assertEquals(0, g.currentIndex(), "tracer target should wrap back to waypoint 1");
        assertEquals(2, g.activeSubwaypointParentIndex(),
                "last waypoint and its subwaypoints should stay visible after the wrap");
    }

    @Test
    void oneWaypointHideAfterParentSubwaypointReappearsAfterLeavingParentRange() {
        WaypointGroup group = WaypointGroup.create("single", "test_zone");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setDefaultRadius(2.0);
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(1, 0, 0)
                .withFlags(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED));
        group.toggleSubwaypoint(1);

        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(group));

        assertTrue(ProximityTracker.updateGroupProgress(group, 0.5, 0.5, 0.5,
                true, true, false, false));
        assertEquals(0, group.currentIndex());
        assertEquals(0, group.activeSubwaypointParentIndex());
        assertArrayEquals(new int[] { 0 }, visibleIndices(group));

        assertTrue(ProximityTracker.updateGroupProgress(group, 8.0, 0.5, 0.5,
                true, true, false, false));
        assertEquals(0, group.currentIndex());
        assertEquals(-1, group.activeSubwaypointParentIndex());
        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(group));
    }

    @Test
    void ignores_waypoints_before_current() {
        WaypointGroup g = line();
        g.setCurrentIndex(2);
        assertFalse(advanceIfReached(g, 0.5, 0.5, 0.5)); // index 0 should not count
        assertEquals(2, g.currentIndex());
    }

    @Test
    void respects_custom_radius_on_waypoint() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(1.0);
        g.add(Waypoint.at(0, 0, 0).withRadius(10.0));
        assertTrue(advanceIfReached(g, 7.0, 0.0, 0.0));
        assertTrue(g.isComplete());
    }

    @Test
    void skip_ahead_disabled_only_advances_current() {
        // With proximity skip-ahead off, standing next to a far-future waypoint
        // must NOT jump progression. The only legal advance is when the player is
        // within range of the waypoint they're actually on (index 0 here).
        WaypointGroup g = line();
        assertFalse(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(0, g.currentIndex());

        // Walking up to the current waypoint still advances by one, same as the
        // enabled case -- disabling skip-ahead must not also disable normal
        // progression.
        assertTrue(ProximityTracker.advanceIfReached(g, 0.5, 0.5, 0.5, false, false));
        assertEquals(1, g.currentIndex());
    }

    @Test
    void routeSkipAheadOffOverridesGlobalSkipAheadOn() {
        WaypointGroup g = line();
        g.setSkipAheadEnabled(false);

        assertFalse(ProximityTracker.updateGroupProgress(g, 20.5, 0.5, 0.5,
                false, true, false, false));
        assertEquals(0, g.currentIndex());

        assertTrue(ProximityTracker.updateGroupProgress(g, 0.5, 0.5, 0.5,
                false, true, false, false));
        assertEquals(1, g.currentIndex());
    }

    @Test
    void skipAheadDoesNotJumpToFutureInteractTriggerWaypoint() {
        WaypointGroup group = dungeonTriggerGroup();
        group.setDefaultRadius(2.0);
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(10, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));

        assertFalse(ProximityTracker.advanceIfReached(group, 10.5, 0.5, 0.5,
                false, true, false));
        assertEquals(0, group.currentIndex());

        assertTrue(ProximityTracker.advanceIfInteractedWithBlock(group, 10, 0, 0, false));
        assertTrue(group.isComplete());
    }

    @Test
    void skipAheadDoesNotJumpToFutureStandTriggerWaypoint() {
        WaypointGroup group = dungeonTriggerGroup();
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(10, 0, 0).withFlags(Waypoint.FLAG_SKIP_ON_STAND));

        assertFalse(ProximityTracker.advanceIfReached(group, 10.1, 1.0, 0.1,
                false, true, false));
        assertEquals(0, group.currentIndex());

        group.setCurrentIndex(1);
        assertFalse(ProximityTracker.advanceIfReached(group, 10.1, 1.0, 0.1,
                false, true, false, 1_000L));
        assertTrue(ProximityTracker.advanceIfReached(group, 10.1, 1.0, 0.1,
                false, true, false, 1_500L));
        assertTrue(group.isComplete());
    }

    @Test
    void skipAheadVisibleOnlyDoesNotJumpToHiddenFutureWaypoint() {
        WaypointGroup g = line();

        assertFalse(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5,
                false, true, true));

        assertEquals(0, g.currentIndex());
    }

    @Test
    void skipAheadVisibleOnlyCanAdvanceToVisibleNextWaypoint() {
        WaypointGroup g = line();

        assertTrue(ProximityTracker.advanceIfReached(g, 10.5, 0.5, 0.5,
                false, true, true));

        assertEquals(2, g.currentIndex());
    }

    @Test
    void explicitSubwaypointTargetAdvancesWhenReached() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);
        g.setCurrentTargetIndex(1);

        assertEquals(1, g.currentIndex());
        assertTrue(ProximityTracker.advanceIfReached(g, 10.5, 0.5, 0.5,
                false, true, true));

        assertEquals(2, g.currentIndex());
    }

    @Test
    void subwaypoints_doNotAdvanceRouteProgress() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertFalse(advanceIfReached(g, 10.5, 0.5, 0.5));

        assertEquals(0, g.currentIndex());
    }

    @Test
    void reaching_parentWithSubwaypoints_advancesTracerTargetButLeavesVisualHold() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertTrue(advanceIfReached(g, 0.5, 0.5, 0.5));
        assertEquals(2, g.currentIndex());
        assertEquals(0, g.activeSubwaypointParentIndex());

        assertTrue(advanceIfReached(g, 20.5, 0.5, 0.5));
        assertEquals(3, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void strictProgression_parentWithSubwaypoints_advancesTracerTarget() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertTrue(ProximityTracker.advanceIfReached(g, 0.5, 0.5, 0.5, false, false));
        assertEquals(2, g.currentIndex());
        assertEquals(0, g.activeSubwaypointParentIndex());

        assertTrue(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(3, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void skipAhead_ignoresSubwaypointsButStillFindsFutureMainWaypoints() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertTrue(advanceIfReached(g, 20.5, 0.5, 0.5));

        assertEquals(3, g.currentIndex());
    }

    @Test
    void staticReachTracking_ignoresSubwaypoints() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.toggleSubwaypoint(1);

        assertFalse(ProximityTracker.markReachedStaticWaypoints(g, 10.5, 0.5, 0.5));

        assertFalse(g.isStaticWaypointReached(1));
        assertEquals(0, g.currentIndex());
    }

    @Test
    void reach_mode_temp_is_removed_on_advance() {
        // TEMP_UNTIL_REACHED waypoints should vanish the moment the proximity
        // tracker advances past them -- anything else would leave a stale temp
        // entry hanging around the list after its lifecycle ended.
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(2.0);
        g.add(Waypoint.at(0, 0, 0).withTemp(Waypoint.TEMP_UNTIL_REACHED, 0L));
        g.add(Waypoint.at(10, 0, 0));

        assertTrue(advanceIfReached(g, 0.5, 0.5, 0.5));
        assertEquals(1, g.size()); // temp was removed
        // After the temp was removed, currentIndex was originally advanced to 1
        // but the list shrank; the remaining waypoint should now be index 0.
        assertEquals(Waypoint.TEMP_NONE, g.get(0).tempMode());
    }

    @Test
    void freshly_focused_waypoint_is_not_immediately_advanced() {
        WaypointGroup g = line();
        g.focusNewWaypoint(2);

        assertFalse(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(2, g.currentIndex());

        assertFalse(ProximityTracker.advanceIfReached(g, 25.0, 0.5, 0.5, false, false));
        assertEquals(2, g.currentIndex());

        assertTrue(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(3, g.currentIndex());
    }

    @Test
    void static_reach_tracking_hides_visited_waypoints_without_advancing_route() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));

        assertEquals(0, g.currentIndex());
        assertFalse(g.isStaticWaypointReached(0));
        assertTrue(g.isStaticWaypointReached(2));
    }

    @Test
    void static_progress_update_does_not_run_sequence_advancement() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        ProximityTracker.updateGroupProgress(g, 20.5, 0.5, 0.5,
                false, true, true);

        assertEquals(0, g.currentIndex());
        assertTrue(g.isStaticWaypointReached(2));
    }

    @Test
    void freshly_focused_static_waypoint_is_not_hidden_until_player_leaves_and_returns() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.focusNewWaypoint(2);

        assertFalse(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));
        assertFalse(g.isStaticWaypointReached(2));

        assertFalse(ProximityTracker.markReachedStaticWaypoints(g, 25.0, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));
        assertTrue(g.isStaticWaypointReached(2));
    }

    @Test
    void static_reach_tracking_resets_after_every_waypoint_is_reached() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 0.5, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 10.5, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 30.5, 0.5, 0.5));

        for (int i = 0; i < g.size(); i++) {
            assertFalse(g.isStaticWaypointReached(i));
        }
    }

    @Test
    void static_cycle_reset_does_not_remark_later_indices_same_tick() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.setDefaultRadius(6.0);
        g.add(Waypoint.at(0, 0, 0));
        g.add(Waypoint.at(10, 0, 0));
        g.add(Waypoint.at(20, 0, 0));
        g.add(Waypoint.at(30, 0, 0));
        g.add(Waypoint.at(40, 0, 0));

        g.markStaticWaypointReached(0);
        g.markStaticWaypointReached(1);
        g.markStaticWaypointReached(2);

        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 35.5, 0.5, 0.5));

        for (int i = 0; i < g.size(); i++) {
            assertFalse(g.isStaticWaypointReached(i), "index " + i);
        }
    }

    @Test
    void completionFeedbackMatchesRequestedFormatAndRemovalCommand() {
        WaypointGroup group = new WaypointGroup("route-id", "route name", "hub");
        WaypointGroup.RouteCompletion completion = new WaypointGroup.RouteCompletion(
                573_000L, true, 2, true);

        Component message = ProximityTracker.routeCompletionMessage(group.name(), completion);
        Component remove = ProximityTracker.removeRecordMessage(group, completion);

        assertEquals("Route \"route name\" cleared in 9m 33s (New Best!) [2 skips]",
                message.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW),
                message.getStyle().getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE),
                message.getSiblings().get(0).getStyle().getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                message.getSiblings().get(1).getStyle().getColor());
        assertEquals("[REMOVE RECORD]", remove.getString());
        ClickEvent.RunCommand click = assertInstanceOf(
                ClickEvent.RunCommand.class, remove.getStyle().getClickEvent());
        assertEquals("/wp removerecord cm91dGUtaWQ 573000", click.command());
    }

    @Test
    void completionFeedbackOmitsSkipCountWhenSkippingIsDisabled() {
        WaypointGroup.RouteCompletion completion = new WaypointGroup.RouteCompletion(
                9_000L, false, 0, false);

        assertEquals("Route \"strict\" cleared in 0m 9s",
                ProximityTracker.routeCompletionMessage("strict", completion).getString());
    }

    @Test
    void worldJoinInvalidatesTimerEvenWhenProgressResetIsDisabled() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = line();
        manager.add(group);
        group.advancePastTimed(0, true, 1_000L);

        WorldJoinProgressReset.resetForWorldJoin(manager, false);
        group.advancePastTimed(3, true, 574_000L);

        assertNull(group.consumeRouteCompletion());
        assertEquals(-1L, group.bestTimeMillis());
        assertTrue(group.isComplete(), "the opt-out still preserves route progress");
    }

    @Test
    void routeTimesOffStillAdvancesButNeverRecordsAndCancelsAnActiveRun() {
        WaypointGroup disabled = line();

        assertTrue(ProximityTracker.advanceIfReached(disabled, 0.5, 0.5, 0.5,
                false, false, false, 1_000L, false));
        assertTrue(ProximityTracker.advanceIfReached(disabled, 30.5, 0.5, 0.5,
                false, true, false, 574_000L, false));
        assertTrue(disabled.isComplete());
        assertNull(disabled.consumeRouteCompletion());
        assertEquals(-1L, disabled.bestTimeMillis());

        WaypointGroup cancelled = line();
        assertTrue(ProximityTracker.advanceIfReached(cancelled, 0.5, 0.5, 0.5,
                false, false, false, 1_000L, true));
        assertTrue(ProximityTracker.advanceIfReached(cancelled, 30.5, 0.5, 0.5,
                false, true, false, 574_000L, false));
        assertNull(cancelled.consumeRouteCompletion());
        assertEquals(-1L, cancelled.bestTimeMillis());
    }

    private static int[] visibleIndices(WaypointGroup group) {
        List<Integer> indices = new ArrayList<>();
        group.forEachVisibleIndex(indices::add);
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean advanceIfReached(WaypointGroup g, double px, double py, double pz) {
        return ProximityTracker.advanceIfReached(g, px, py, pz);
    }
}
