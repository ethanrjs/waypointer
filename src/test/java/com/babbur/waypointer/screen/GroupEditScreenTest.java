package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupEditScreenTest {

    @Test
    void parseCoordinateInputAcceptsTrimmedWholeNumbers() {
        assertEquals(64, GroupEditScreen.parseCoordinateInput("64"));
        assertEquals(-12, GroupEditScreen.parseCoordinateInput("  -12  "));
    }

    @Test
    void parseCoordinateInputRejectsBlankOrNonIntegerValues() {
        assertNull(GroupEditScreen.parseCoordinateInput(null));
        assertNull(GroupEditScreen.parseCoordinateInput(""));
        assertNull(GroupEditScreen.parseCoordinateInput("   "));
        assertNull(GroupEditScreen.parseCoordinateInput("-"));
        assertNull(GroupEditScreen.parseCoordinateInput("12.5"));
        assertNull(GroupEditScreen.parseCoordinateInput("north"));
    }

    @Test
    void coordinateErrorMessageNamesAxisAndFailureReason() {
        assertEquals("X coordinate is required.",
                GroupEditScreen.coordinateErrorMessage(0, " "));
        assertEquals("Y coordinate must be a whole number.",
                GroupEditScreen.coordinateErrorMessage(1, "12.5"));
        assertEquals("Z coordinate must be a whole number.",
                GroupEditScreen.coordinateErrorMessage(2, "-"));
    }

    @Test
    void coordinateScrollStepsOnceInTheWheelDirectionAndClampsAtIntegerBounds() {
        assertEquals(13, GroupEditScreen.coordinateAfterScroll(12, 0.25));
        assertEquals(11, GroupEditScreen.coordinateAfterScroll(12, -4.0));
        assertEquals(-11, GroupEditScreen.coordinateAfterScroll(-12, 1.0));
        assertEquals(-13, GroupEditScreen.coordinateAfterScroll(-12, -1.0));
        assertEquals(12, GroupEditScreen.coordinateAfterScroll(12, 0.0));
        assertEquals(Integer.MAX_VALUE,
                GroupEditScreen.coordinateAfterScroll(Integer.MAX_VALUE, 1.0));
        assertEquals(Integer.MIN_VALUE,
                GroupEditScreen.coordinateAfterScroll(Integer.MIN_VALUE, -1.0));
    }

    @Test
    void waypointRowTextWidthReservesControlAndMetadataSpace() {
        int textLeft = 220;
        int rowRight = 560;
        int metadataWidth = 48;

        int normalWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, false, false, 0);
        int withMetadataWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, false, false, metadataWidth);
        int dungeonWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, false, true, 0);
        int subwaypointWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, true, false, 0);
        int dungeonSubwaypointWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, true, true, 0);

        assertEquals(normalWidth - metadataWidth - GAP, withMetadataWidth);
        assertTrue(dungeonWidth < normalWidth);
        assertTrue(subwaypointWidth < normalWidth);
        assertTrue(dungeonSubwaypointWidth < subwaypointWidth);
    }

    @Test
    void waypointRowTextWidthClampsWhenReservedSpaceConsumesTheRow() {
        assertEquals(0, GroupEditScreen.waypointRowTextWidth(
                400, 420, true, true, 120));
    }

    @Test
    void labelEditorWidthNeverOverlapsReservedRightColumn() {
        assertEquals(72, GroupEditScreen.labelEditorWidth(208, 280));
        assertEquals(0, GroupEditScreen.labelEditorWidth(300, 280));
    }

    @Test
    void shortScreenGeometryReservesWrappedFooterAndScrollsSidebarOverflow() {
        assertEquals(190, GroupEditScreen.contentBottom(262, 56),
                "a two-row footer at GUI scale 5 must be outside the content panel");
        assertEquals(116, GroupEditScreen.maxSidebarScroll(236, 120));
        assertEquals(0, GroupEditScreen.maxSidebarScroll(120, 236));
        assertEquals(236, GroupEditScreen.maxSidebarScroll(236, 0));
    }

    @Test
    void keyboardFocusScrollsSidebarControlFullyIntoView() {
        assertEquals(116, GroupEditScreen.sidebarScrollOffsetToReveal(
                0, 278, 20, 62, 182, 116));
        assertEquals(0, GroupEditScreen.sidebarScrollOffsetToReveal(
                116, 62, 20, 62, 182, 116));
        assertEquals(50, GroupEditScreen.sidebarScrollOffsetToReveal(
                50, 150, 20, 62, 182, 116));
    }

    @Test
    void sameSelectedWaypointDoubleClickStartsRenameDecision() {
        assertTrue(GroupEditScreen.shouldStartRenameFromRowClick(true, true));
        assertFalse(GroupEditScreen.shouldStartRenameFromRowClick(false, true));
        assertFalse(GroupEditScreen.shouldStartRenameFromRowClick(true, false));
    }

    @Test
    void swatchGestureTooltipTextExplainsColorActions() {
        assertEquals("Click to edit waypoint color",
                GroupEditScreen.swatchGestureTooltipText(false));
        assertEquals("Shift-click unlocks locked color",
                GroupEditScreen.swatchGestureTooltipText(true));
    }

    @Test
    void waypointRowVisualStateKeepsHeldSubwaypointParentAndChildrenActive() {
        WaypointGroup group = routeWithSubwaypoints();
        group.advancePast(0);

        assertTrue(GroupEditScreen.isWaypointRowVisuallyActive(group, 0),
                "the reached parent should stay highlighted during subwaypoint visual hold");
        assertTrue(GroupEditScreen.isWaypointRowVisuallyActive(group, 1),
                "the first child under the held parent should stay highlighted");
        assertTrue(GroupEditScreen.isWaypointRowVisuallyActive(group, 2),
                "the second child under the held parent should stay highlighted");
        assertTrue(GroupEditScreen.isWaypointRowVisuallyActive(group, 3),
                "the next main waypoint should remain highlighted as the actual route target");
        assertFalse(GroupEditScreen.isWaypointRowVisuallyActive(group, -1));
    }

    @Test
    void waypointRowVisualStateTreatsExactSubwaypointTargetAsActive() {
        WaypointGroup group = routeWithSubwaypoints();
        group.setCurrentTargetIndex(2);

        assertEquals(2, group.currentIndex());
        assertEquals(-1, group.activeSubwaypointParentIndex(),
                "exact child targets do not expose a visual hold until the route advances to the next main");
        assertFalse(GroupEditScreen.isWaypointRowVisuallyActive(group, 0),
                "the parent is completed, not active, for an exact child target");
        assertFalse(GroupEditScreen.isWaypointRowVisuallyActive(group, 1),
                "sibling children are completed, not active, for an exact child target");
        assertTrue(GroupEditScreen.isWaypointRowVisuallyActive(group, 2),
                "the exact child target should be active in the editor");
        assertFalse(GroupEditScreen.isWaypointRowVisuallyActive(group, 3),
                "the next main is not active until the child chain advances past it");
    }

    @Test
    void dungeonSkipTooltipTextNamesRouteProgressionScope() {
        assertEquals("Dungeons: Stand to skip",
                GroupEditScreen.dungeonStandSkipTooltipText());
        assertEquals("Dungeons: Interact to skip",
                GroupEditScreen.dungeonInteractSkipTooltipText());
        assertEquals("Dungeons: Mine to skip",
                GroupEditScreen.dungeonMineSkipTooltipText());
    }

    @Test
    void waypointControlFlagsKeepDungeonSkipActionsDungeonOnly() {
        assertEquals(Waypoint.FLAG_SKIP_ON_STAND,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_STAND_SKIP, true));
        assertEquals(Waypoint.FLAG_SKIP_ON_INTERACT,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_INTERACT_SKIP, true));
        assertEquals(Waypoint.FLAG_SKIP_ON_MINE,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_MINE_SKIP, true));
        assertEquals(0,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_STAND_SKIP, false));
        assertEquals(0,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_INTERACT_SKIP, false));
        assertEquals(0,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_MINE_SKIP, false));
        assertEquals(Waypoint.FLAG_DEPTH_CHECKED,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_DEPTH_CHECK, false));
        assertEquals(0,
                GroupEditScreen.waypointControlFlagForAction(
                        GroupEditScreen.WAYPOINT_CONTROL_ACTION_NONE, true));
    }

    @Test
    void subwaypointStyleActionsMapToIndependentWaypointFlags() {
        assertEquals(Waypoint.FLAG_SMALL_SUBWAYPOINT,
                GroupEditScreen.subwaypointStyleFlagForAction(
                        GroupEditScreen.SUBWAY_STYLE_ACTION_SMALL));
        assertEquals(Waypoint.FLAG_FILLED_SUBWAYPOINT,
                GroupEditScreen.subwaypointStyleFlagForAction(
                        GroupEditScreen.SUBWAY_STYLE_ACTION_FILLED));
        assertEquals(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                GroupEditScreen.subwaypointStyleFlagForAction(
                        GroupEditScreen.SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT));
        assertEquals(0,
                GroupEditScreen.subwaypointStyleFlagForAction(
                        GroupEditScreen.SUBWAY_STYLE_ACTION_NONE));
    }

    @Test
    void colorModeCycleAndLabelsMatchEditorControls() {
        assertEquals("Color", GroupEditScreen.colorModeName(GroupEditScreen.RouteColorMode.COLOR));
        assertEquals("Gradient", GroupEditScreen.colorModeName(GroupEditScreen.RouteColorMode.GRADIENT));
        assertEquals("One", GroupEditScreen.colorModeName(GroupEditScreen.RouteColorMode.ONE));
        assertEquals("Paint", GroupEditScreen.colorModeName(GroupEditScreen.RouteColorMode.PAINT));

        assertEquals(GroupEditScreen.RouteColorMode.GRADIENT,
                GroupEditScreen.nextColorMode(GroupEditScreen.RouteColorMode.COLOR));
        assertEquals(GroupEditScreen.RouteColorMode.ONE,
                GroupEditScreen.nextColorMode(GroupEditScreen.RouteColorMode.GRADIENT));
        assertEquals(GroupEditScreen.RouteColorMode.PAINT,
                GroupEditScreen.nextColorMode(GroupEditScreen.RouteColorMode.ONE));
        assertEquals(GroupEditScreen.RouteColorMode.COLOR,
                GroupEditScreen.nextColorMode(GroupEditScreen.RouteColorMode.PAINT));

        assertEquals(GroupEditScreen.RouteColorMode.COLOR,
                GroupEditScreen.routeColorMode(WaypointGroup.GradientMode.MANUAL, false));
        assertEquals(GroupEditScreen.RouteColorMode.PAINT,
                GroupEditScreen.routeColorMode(WaypointGroup.GradientMode.AUTO, true));
    }

    @Test
    void selectedIndexAfterRemovalKeepsAVisibleNeighborSelected() {
        assertEquals(1, GroupEditScreen.selectedIndexAfterRemoval(1, 2),
                "removing a middle row should select the next row now occupying that index");
        assertEquals(1, GroupEditScreen.selectedIndexAfterRemoval(2, 2),
                "removing the last row should select the new last row");
        assertEquals(-1, GroupEditScreen.selectedIndexAfterRemoval(0, 0),
                "removing the only row should leave no selection");
        assertEquals(-1, GroupEditScreen.selectedIndexAfterRemoval(-1, 3),
                "invalid removal indexes should not invent a selection");
    }

    @Test
    void routeNameChangePublishesOnceWhenEditorIsRemoved() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("Original", "hub");
        manager.add(group);
        AtomicInteger dataChanges = new AtomicInteger();
        manager.addDataListener(dataChanges::incrementAndGet);
        String publishedName = group.name();

        publishedName = GroupEditScreen.publishNameChangeIfNeeded(manager, group, publishedName);
        assertEquals(0, dataChanges.get(), "closing without a rename should not schedule a save");

        group.setName("Renamed");
        publishedName = GroupEditScreen.publishNameChangeIfNeeded(manager, group, publishedName);
        GroupEditScreen.publishNameChangeIfNeeded(manager, group, publishedName);

        assertEquals(1, dataChanges.get(),
                "a completed rename should publish once instead of once per keystroke");
    }

    private static WaypointGroup routeWithSubwaypoints() {
        WaypointGroup group = WaypointGroup.create("Subway Route", "dungeon_f7");
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(1, 70, 0));
        group.add(Waypoint.at(2, 70, 0));
        group.add(Waypoint.at(3, 70, 0));
        assertTrue(group.toggleSubwaypoint(1));
        assertTrue(group.toggleSubwaypoint(2));
        assertTrue(group.isSubwaypoint(1));
        assertTrue(group.isSubwaypoint(2));
        assertEquals(0, group.parentMainIndex(1));
        assertEquals(0, group.parentMainIndex(2));
        return group;
    }
}
