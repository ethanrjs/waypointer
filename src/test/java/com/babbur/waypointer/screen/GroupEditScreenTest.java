package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupEditScreenTest {

    @Test
    void parseCoordinateInputAcceptsTrimmedWholeNumbers() {
        assertEquals(64, GroupEditGeometry.parseCoordinate("64"));
        assertEquals(-12, GroupEditGeometry.parseCoordinate("  -12  "));
        assertEquals(12, GroupEditGeometry.parseCoordinate("+12"));
        assertEquals(123, GroupEditGeometry.parseCoordinate("\u0661\u0662\u0663"));
        assertEquals(-123, GroupEditGeometry.parseCoordinate("\u200e\u2212\u06f1\u06f2\u06f3"));
    }

    @Test
    void parseCoordinateInputRejectsBlankOrNonIntegerValues() {
        assertNull(GroupEditGeometry.parseCoordinate(null));
        assertNull(GroupEditGeometry.parseCoordinate(""));
        assertNull(GroupEditGeometry.parseCoordinate("   "));
        assertNull(GroupEditGeometry.parseCoordinate("-"));
        assertNull(GroupEditGeometry.parseCoordinate("1 2"));
        assertNull(GroupEditGeometry.parseCoordinate("12.5"));
        assertNull(GroupEditGeometry.parseCoordinate("north"));
    }

    @Test
    void coordinateErrorMessageNamesAxisAndFailureReason() {
        assertEquals("X coordinate is required.",
                GroupEditGeometry.coordinateError(0, " "));
        assertEquals("Y coordinate must be a whole number.",
                GroupEditGeometry.coordinateError(1, "12.5"));
        assertEquals("Z coordinate must be a whole number.",
                GroupEditGeometry.coordinateError(2, "-"));
    }

    @Test
    void coordinateScrollStepsOnceInTheWheelDirectionAndClampsAtIntegerBounds() {
        assertEquals(13, GroupEditGeometry.coordinateAfterScroll(12, 0.25));
        assertEquals(11, GroupEditGeometry.coordinateAfterScroll(12, -4.0));
        assertEquals(-11, GroupEditGeometry.coordinateAfterScroll(-12, 1.0));
        assertEquals(-13, GroupEditGeometry.coordinateAfterScroll(-12, -1.0));
        assertEquals(12, GroupEditGeometry.coordinateAfterScroll(12, 0.0));
        assertEquals(Integer.MAX_VALUE,
                GroupEditGeometry.coordinateAfterScroll(Integer.MAX_VALUE, 1.0));
        assertEquals(Integer.MIN_VALUE,
                GroupEditGeometry.coordinateAfterScroll(Integer.MIN_VALUE, -1.0));
    }

    @Test
    void waypointListUsesEqualInsetsAndRowsWithoutGaps() {
        assertEquals(2, GroupEditGeometry.ROUTE_LIST_INSET);
        assertEquals(GuiTokens.ROW_H, GroupEditGeometry.waypointRowPitch());
        assertEquals(4, GroupEditGeometry.routeListMaxScroll(5,
                GuiTokens.ROW_H * 5));
        assertEquals(0, GroupEditGeometry.routeListMaxScroll(5,
                GuiTokens.ROW_H * 5 + 4));
    }

    @Test
    void routeScrollbarDragMapsAndClampsToTheListRange() {
        assertEquals(0, GroupEditGeometry.routeScrollOffsetForPointer(
                10, 0, 10, 110, 20, 160));
        assertEquals(80, GroupEditGeometry.routeScrollOffsetForPointer(
                50, 0, 10, 110, 20, 160));
        assertEquals(160, GroupEditGeometry.routeScrollOffsetForPointer(
                500, 0, 10, 110, 20, 160));
        assertEquals(0, GroupEditGeometry.routeScrollOffsetForPointer(
                -500, 0, 10, 110, 20, 160));
    }

    @Test
    void waypointRowTextWidthReservesControlAndMetadataSpace() {
        int textLeft = 220;
        int rowRight = 560;
        int metadataWidth = 48;

        int normalWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, false, false, true, 0);
        int withMetadataWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, false, false, true, metadataWidth);
        int dungeonWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, false, true, true, 0);
        int subwaypointWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, true, false, true, 0);
        int dungeonSubwaypointWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, true, true, true, 0);
        int unselectedDungeonSubwaypointWidth = GroupEditScreen.waypointRowTextWidth(
                textLeft, rowRight, true, true, false, 0);

        assertEquals(normalWidth - metadataWidth - GAP, withMetadataWidth);
        assertTrue(dungeonWidth < normalWidth);
        assertTrue(subwaypointWidth < normalWidth);
        assertTrue(dungeonSubwaypointWidth < subwaypointWidth);
        assertTrue(unselectedDungeonSubwaypointWidth > normalWidth,
                "unselected rows should reclaim the repeated control columns");
        assertEquals(GroupEditScreen.waypointControlButtonX(rowRight, 4),
                GroupEditScreen.dungeonTypeButtonX(rowRight));
    }

    @Test
    void dungeonTypePickerUsesFourByTwoGridAndFlipsAboveNearTheBottom() {
        GroupEditScreen.DungeonTypePickerBounds below = GroupEditScreen.dungeonTypePickerBounds(
                500, 80, 180, 560, 40, 260);
        GroupEditScreen.DungeonTypePickerBounds above = GroupEditScreen.dungeonTypePickerBounds(
                500, 220, 180, 560, 40, 260);

        assertTrue(below.top() > 80);
        assertTrue(above.bottom() < 220);
        assertEquals(8, DungeonWaypointType.values().length);
        assertEquals(0, GroupEditScreen.dungeonTypePickerCell(below, 0).typeIndex());
        assertEquals(3, GroupEditScreen.dungeonTypePickerCell(below, 3).typeIndex());
        assertEquals(4, GroupEditScreen.dungeonTypePickerCell(below, 4).typeIndex());
        assertEquals(7, GroupEditScreen.dungeonTypePickerCell(below, 7).typeIndex());
        assertNull(GroupEditScreen.dungeonTypePickerCell(below, 8));
    }

    @Test
    void dungeonTypePickerHoverUsesOnlyTheTypeName() {
        assertEquals("Etherwarp",
                GroupEditScreen.dungeonTypePickerTooltip(DungeonWaypointType.ETHERWARP));
        assertFalse(GroupEditScreen.dungeonTypePickerTooltip(DungeonWaypointType.SECRET)
                .contains("selected"));
    }

    @Test
    void waypointRowTextWidthClampsWhenReservedSpaceConsumesTheRow() {
        assertEquals(0, GroupEditScreen.waypointRowTextWidth(
                400, 420, true, true, true, 120));
    }

    @Test
    void waypointControlsUseProgressiveDisclosureForTheSelectedRow() {
        assertTrue(GroupEditPolicy.shouldShowWaypointControls(3, 3));
        assertFalse(GroupEditPolicy.shouldShowWaypointControls(2, 3));
        assertFalse(GroupEditPolicy.shouldShowWaypointControls(-1, -1));
    }

    @Test
    void idleWaypointSummaryKeepsActiveStateScannableWithoutButtonFrames() {
        Waypoint waypoint = Waypoint.at(0, 70, 0).withFlags(
                Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_FILLED_SUBWAYPOINT
                        | Waypoint.FLAG_SKIP_ON_INTERACT
                        | Waypoint.FLAG_DEPTH_CHECKED);

        assertEquals("Tiny · Filled · Interact · LOS",
                GroupEditPolicy.waypointControlSummary(waypoint, true, true));
        assertEquals("LOS",
                GroupEditPolicy.waypointControlSummary(waypoint, false, false));
        assertEquals("", GroupEditPolicy.waypointControlSummary(null, true, true));

        Waypoint dungeonItem = Waypoint.at(0, 70, 0).withFlags(
                Waypoint.FLAG_DUNGEON_SECRET
                        | Waypoint.FLAG_DUNGEON_ITEM
                        | Waypoint.FLAG_SKIP_ON_STAND);
        assertEquals("Secret · Item · Stand",
                GroupEditPolicy.waypointControlSummary(dungeonItem, false, true));
    }

    @Test
    void connectorSegmentsKeepSubwaypointColorsOutOfTheMainSpine() {
        int cyan = 0x31CFE8;
        int orange = 0xFFB000;
        int blue = 0x3150E0;
        WaypointGroup group = WaypointGroup.create("Route", "dungeon_f7");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(0, 70, 0).withColor(cyan));
        group.add(Waypoint.at(1, 70, 0).withColor(orange));
        group.add(Waypoint.at(2, 70, 0).withColor(orange));
        group.add(Waypoint.at(3, 70, 0).withColor(blue));
        group.add(Waypoint.at(4, 70, 0).withColor(orange));
        group.add(Waypoint.at(5, 70, 0).withColor(orange));
        assertTrue(group.toggleSubwaypoint(1));
        assertTrue(group.toggleSubwaypoint(2));
        assertTrue(group.toggleSubwaypoint(4));
        assertTrue(group.toggleSubwaypoint(5));

        assertEquals(List.of(
                new GroupEditPolicy.ConnectorSegment(false, 0, 3, cyan, blue),
                new GroupEditPolicy.ConnectorSegment(false, 3, 5, blue, blue),
                new GroupEditPolicy.ConnectorSegment(true, 1, 1, 0x31A5E5, orange),
                new GroupEditPolicy.ConnectorSegment(true, 2, 2, 0x317AE3, orange),
                new GroupEditPolicy.ConnectorSegment(true, 4, 4, blue, orange),
                new GroupEditPolicy.ConnectorSegment(true, 5, 5, blue, orange)
        ), GroupEditPolicy.connectorSegments(group));
    }

    @Test
    void labelEditorWidthNeverOverlapsReservedRightColumn() {
        assertEquals(72, GroupEditGeometry.labelEditorWidth(208, 280));
        assertEquals(0, GroupEditGeometry.labelEditorWidth(300, 280));
    }

    @Test
    void shortScreenGeometryReservesWrappedFooterAndScrollsSidebarOverflow() {
        assertEquals(190, GroupEditGeometry.contentBottom(262, 56),
                "a two-row footer at GUI scale 5 must be outside the content panel");
        assertEquals(116, GroupEditGeometry.maxSidebarScroll(236, 120));
        assertEquals(0, GroupEditGeometry.maxSidebarScroll(120, 236));
        assertEquals(236, GroupEditGeometry.maxSidebarScroll(236, 0));
    }

    @Test
    void keyboardFocusScrollsSidebarControlFullyIntoView() {
        assertEquals(116, GroupEditGeometry.sidebarScrollOffsetToReveal(
                0, 278, 20, 62, 182, 116));
        assertEquals(0, GroupEditGeometry.sidebarScrollOffsetToReveal(
                116, 62, 20, 62, 182, 116));
        assertEquals(50, GroupEditGeometry.sidebarScrollOffsetToReveal(
                50, 150, 20, 62, 182, 116));
    }

    @Test
    void sameSelectedWaypointDoubleClickStartsRenameDecision() {
        assertTrue(GroupEditPolicy.shouldStartRenameFromRowClick(true, true));
        assertFalse(GroupEditPolicy.shouldStartRenameFromRowClick(false, true));
        assertFalse(GroupEditPolicy.shouldStartRenameFromRowClick(true, false));
    }

    @Test
    void swatchGestureTooltipTextExplainsColorActions() {
        assertEquals("Click to edit waypoint color",
                GroupEditPolicy.swatchGestureTooltipText(false));
        assertEquals("Shift-click unlocks locked color",
                GroupEditPolicy.swatchGestureTooltipText(true));
    }

    @Test
    void waypointRowVisualStateKeepsHeldSubwaypointParentAndChildrenActive() {
        WaypointGroup group = routeWithSubwaypoints();
        group.advancePast(0);

        assertTrue(GroupEditPolicy.isWaypointRowVisuallyActive(group, 0),
                "the reached parent should stay highlighted during subwaypoint visual hold");
        assertTrue(GroupEditPolicy.isWaypointRowVisuallyActive(group, 1),
                "the first child under the held parent should stay highlighted");
        assertTrue(GroupEditPolicy.isWaypointRowVisuallyActive(group, 2),
                "the second child under the held parent should stay highlighted");
        assertTrue(GroupEditPolicy.isWaypointRowVisuallyActive(group, 3),
                "the next main waypoint should remain highlighted as the actual route target");
        assertFalse(GroupEditPolicy.isWaypointRowVisuallyActive(group, -1));
    }

    @Test
    void waypointRowVisualStateTreatsExactSubwaypointTargetAsActive() {
        WaypointGroup group = routeWithSubwaypoints();
        group.setCurrentTargetIndex(2);

        assertEquals(2, group.currentIndex());
        assertEquals(-1, group.activeSubwaypointParentIndex(),
                "exact child targets do not expose a visual hold until the route advances to the next main");
        assertFalse(GroupEditPolicy.isWaypointRowVisuallyActive(group, 0),
                "the parent is completed, not active, for an exact child target");
        assertFalse(GroupEditPolicy.isWaypointRowVisuallyActive(group, 1),
                "sibling children are completed, not active, for an exact child target");
        assertTrue(GroupEditPolicy.isWaypointRowVisuallyActive(group, 2),
                "the exact child target should be active in the editor");
        assertFalse(GroupEditPolicy.isWaypointRowVisuallyActive(group, 3),
                "the next main is not active until the child chain advances past it");
    }

    @Test
    void dungeonSkipTooltipTextUsesConciseToggleLabels() {
        assertEquals("Stand to skip",
                GroupEditPolicy.dungeonStandSkipTooltipText());
        assertEquals("Interact to skip",
                GroupEditPolicy.dungeonInteractSkipTooltipText());
        assertEquals("Mine to skip",
                GroupEditPolicy.dungeonMineSkipTooltipText());
    }

    @Test
    void dungeonRouteInfoExplainsTriggerAndRadiusSkipBehaviors() {
        assertEquals(7, GroupEditPolicy.routeInfoLabels(false).size());
        assertEquals(13, GroupEditPolicy.routeInfoLabels(true).size());
        assertTrue(GroupEditPolicy.routeInfoLabels(true).containsAll(
                java.util.List.of("Types", "No trigger", "Stand", "Interact", "Mine", "Skip Ahead")));
        assertEquals(java.util.List.of(
                "Select a waypoint row",
                "Rename that waypoint",
                "Open the waypoint menu",
                "Enable or disable the waypoint",
                "Move in world",
                "Toggle subwaypoint",
                "Edit color; Shift-click unlocks"
        ), GroupEditPolicy.routeInfoDescriptions(false));
        assertTrue(GroupEditPolicy.routeInfoDescriptions(true).containsAll(java.util.List.of(
                "Skip when near",
                "Skip when standing for 0.5 seconds",
                "Right-click to skip",
                "Break to skip",
                "Waypoints can be skipped")));
        assertEquals("Entering a later waypoint's radius skips to it,\n"
                        + "even if it uses Stand, Interact, or Mine.",
                GroupEditPolicy.skipAheadTooltipText(true));
    }

    @Test
    void waypointControlFlagsKeepDungeonSkipActionsDungeonOnly() {
        assertEquals(Waypoint.FLAG_SKIP_ON_STAND,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_STAND_SKIP, true));
        assertEquals(Waypoint.FLAG_SKIP_ON_INTERACT,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_INTERACT_SKIP, true));
        assertEquals(Waypoint.FLAG_SKIP_ON_MINE,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_MINE_SKIP, true));
        assertEquals(0,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_STAND_SKIP, false));
        assertEquals(0,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_INTERACT_SKIP, false));
        assertEquals(0,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_MINE_SKIP, false));
        assertEquals(Waypoint.FLAG_DEPTH_CHECKED,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_DEPTH_CHECK, false));
        assertEquals(0,
                GroupEditPolicy.waypointControlFlagForAction(
                        GroupEditPolicy.WAYPOINT_CONTROL_ACTION_NONE, true));
    }

    @Test
    void subwaypointStyleActionsMapToIndependentWaypointFlags() {
        assertEquals(Waypoint.FLAG_SMALL_SUBWAYPOINT,
                GroupEditPolicy.subwaypointStyleFlagForAction(
                        GroupEditPolicy.SUBWAY_STYLE_ACTION_SMALL));
        assertEquals(Waypoint.FLAG_FILLED_SUBWAYPOINT,
                GroupEditPolicy.subwaypointStyleFlagForAction(
                        GroupEditPolicy.SUBWAY_STYLE_ACTION_FILLED));
        assertEquals(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                GroupEditPolicy.subwaypointStyleFlagForAction(
                        GroupEditPolicy.SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT));
        assertEquals(0,
                GroupEditPolicy.subwaypointStyleFlagForAction(
                        GroupEditPolicy.SUBWAY_STYLE_ACTION_NONE));
    }

    @Test
    void waypointControlActionsMatchTheSevenIconAtlasCells() {
        assertEquals(0, GroupEditScreen.subwaypointStyleIconIndex(
                GroupEditPolicy.SUBWAY_STYLE_ACTION_SMALL));
        assertEquals(1, GroupEditScreen.subwaypointStyleIconIndex(
                GroupEditPolicy.SUBWAY_STYLE_ACTION_FILLED));
        assertEquals(2, GroupEditScreen.subwaypointStyleIconIndex(
                GroupEditPolicy.SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT));
        assertEquals(3, GroupEditScreen.waypointControlIconIndex(
                GroupEditPolicy.WAYPOINT_CONTROL_ACTION_STAND_SKIP));
        assertEquals(4, GroupEditScreen.waypointControlIconIndex(
                GroupEditPolicy.WAYPOINT_CONTROL_ACTION_INTERACT_SKIP));
        assertEquals(5, GroupEditScreen.waypointControlIconIndex(
                GroupEditPolicy.WAYPOINT_CONTROL_ACTION_MINE_SKIP));
        assertEquals(6, GroupEditScreen.waypointControlIconIndex(
                GroupEditPolicy.WAYPOINT_CONTROL_ACTION_DEPTH_CHECK));
    }

    @Test
    void waypointControlButtonsUseOneConsistentPitch() {
        int rightmostX = GroupEditScreen.waypointControlButtonX(500, 0);
        for (int indexFromRight = 1; indexFromRight < 7; indexFromRight++) {
            assertEquals(24, GroupEditScreen.waypointControlButtonX(500, indexFromRight - 1)
                    - GroupEditScreen.waypointControlButtonX(500, indexFromRight));
        }
        assertEquals(472, rightmostX, "the right edge keeps its existing 8px row inset");
    }

    @Test
    void colorModeCycleAndLabelsMatchEditorControls() {
        assertEquals("Color", GroupEditPolicy.colorModeName(GroupEditPolicy.RouteColorMode.COLOR));
        assertEquals("Gradient", GroupEditPolicy.colorModeName(GroupEditPolicy.RouteColorMode.GRADIENT));
        assertEquals("One", GroupEditPolicy.colorModeName(GroupEditPolicy.RouteColorMode.ONE));
        assertEquals("Paint", GroupEditPolicy.colorModeName(GroupEditPolicy.RouteColorMode.PAINT));

        assertEquals(GroupEditPolicy.RouteColorMode.GRADIENT,
                GroupEditPolicy.nextColorMode(GroupEditPolicy.RouteColorMode.COLOR));
        assertEquals(GroupEditPolicy.RouteColorMode.ONE,
                GroupEditPolicy.nextColorMode(GroupEditPolicy.RouteColorMode.GRADIENT));
        assertEquals(GroupEditPolicy.RouteColorMode.PAINT,
                GroupEditPolicy.nextColorMode(GroupEditPolicy.RouteColorMode.ONE));
        assertEquals(GroupEditPolicy.RouteColorMode.COLOR,
                GroupEditPolicy.nextColorMode(GroupEditPolicy.RouteColorMode.PAINT));

        assertEquals(GroupEditPolicy.RouteColorMode.COLOR,
                GroupEditPolicy.routeColorMode(WaypointGroup.GradientMode.MANUAL, false));
        assertEquals(GroupEditPolicy.RouteColorMode.PAINT,
                GroupEditPolicy.routeColorMode(WaypointGroup.GradientMode.AUTO, true));
    }

    @Test
    void routeModeTooltipsDescribeOnlyTheSelectedOption() {
        assertEquals("waypointer.screen.group_edit.color_mode.tooltip.color",
                GroupEditPolicy.colorModeTooltipKey(GroupEditPolicy.RouteColorMode.COLOR));
        assertEquals("Edit each waypoint's color.",
                GroupEditPolicy.colorModeTooltipFallback(GroupEditPolicy.RouteColorMode.COLOR));
        assertEquals("waypointer.screen.group_edit.color_mode.tooltip.gradient",
                GroupEditPolicy.colorModeTooltipKey(GroupEditPolicy.RouteColorMode.GRADIENT));
        assertEquals("Blend waypoint colors from Start to End.",
                GroupEditPolicy.colorModeTooltipFallback(GroupEditPolicy.RouteColorMode.GRADIENT));
        assertEquals("waypointer.screen.group_edit.color_mode.tooltip.one",
                GroupEditPolicy.colorModeTooltipKey(GroupEditPolicy.RouteColorMode.ONE));
        assertEquals("Use one color for the whole route.",
                GroupEditPolicy.colorModeTooltipFallback(GroupEditPolicy.RouteColorMode.ONE));
        assertEquals("waypointer.screen.group_edit.color_mode.tooltip.paint",
                GroupEditPolicy.colorModeTooltipKey(GroupEditPolicy.RouteColorMode.PAINT));
        assertEquals("Use painted waypoint faces.",
                GroupEditPolicy.colorModeTooltipFallback(GroupEditPolicy.RouteColorMode.PAINT));

        assertEquals("waypointer.screen.group_edit.mode.tooltip.static",
                GroupEditPolicy.modeTooltipKey(WaypointGroup.LoadMode.STATIC));
        assertEquals("Show all waypoints.",
                GroupEditPolicy.modeTooltipFallback(WaypointGroup.LoadMode.STATIC));
        assertEquals("waypointer.screen.group_edit.mode.tooltip.sequence",
                GroupEditPolicy.modeTooltipKey(WaypointGroup.LoadMode.SEQUENCE));
        assertEquals("Go through waypoints one by one.",
                GroupEditPolicy.modeTooltipFallback(WaypointGroup.LoadMode.SEQUENCE));
    }

    @Test
    void selectedIndexAfterRemovalKeepsAVisibleNeighborSelected() {
        assertEquals(1, GroupEditPolicy.selectedIndexAfterRemoval(1, 2),
                "removing a middle row should select the next row now occupying that index");
        assertEquals(1, GroupEditPolicy.selectedIndexAfterRemoval(2, 2),
                "removing the last row should select the new last row");
        assertEquals(-1, GroupEditPolicy.selectedIndexAfterRemoval(0, 0),
                "removing the only row should leave no selection");
        assertEquals(-1, GroupEditPolicy.selectedIndexAfterRemoval(-1, 3),
                "invalid removal indexes should not invent a selection");
    }

    @Test
    void moveWaypointSelectionUsesTheGroupHierarchyRules() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Waypoint first = Waypoint.at(0, 70, 0);
        Waypoint child = Waypoint.at(1, 70, 0);
        Waypoint second = Waypoint.at(2, 70, 0);
        group.add(first);
        group.add(child);
        group.add(second);
        assertTrue(group.toggleSubwaypoint(1));
        child = group.get(1);

        int movedTo = GroupEditPolicy.moveWaypointSelection(group, 0, 1);

        assertEquals(1, movedTo);
        assertSame(first, group.get(1));
        assertSame(child, group.get(2),
                "moving a main waypoint must keep its subwaypoint attached");
        assertEquals(1, GroupEditPolicy.moveWaypointSelection(group, movedTo, 1),
                "a blocked move must keep the selected row unchanged");
        assertEquals(-1, GroupEditPolicy.moveWaypointSelection(group, -1, 1));
    }

    @Test
    void routeNameChangePublishesOnceWhenEditorIsRemoved() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("Original", "hub");
        manager.add(group);
        AtomicInteger dataChanges = new AtomicInteger();
        manager.addDataListener(dataChanges::incrementAndGet);
        String publishedName = group.name();

        publishedName = GroupEditPolicy.publishNameChangeIfNeeded(manager, group, publishedName);
        assertEquals(0, dataChanges.get(), "closing without a rename should not schedule a save");

        group.setName("Renamed");
        publishedName = GroupEditPolicy.publishNameChangeIfNeeded(manager, group, publishedName);
        GroupEditPolicy.publishNameChangeIfNeeded(manager, group, publishedName);

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
