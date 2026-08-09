package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonWaypointType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Route-editor behavior */
final class GroupEditPolicy {

    static final int SUBWAY_STYLE_ACTION_NONE = 0;
    static final int SUBWAY_STYLE_ACTION_SMALL = 1;
    static final int SUBWAY_STYLE_ACTION_FILLED = 2;
    static final int SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT = 3;

    static final int WAYPOINT_CONTROL_ACTION_NONE = 0;
    static final int WAYPOINT_CONTROL_ACTION_STAND_SKIP = 1;
    static final int WAYPOINT_CONTROL_ACTION_INTERACT_SKIP = 2;
    static final int WAYPOINT_CONTROL_ACTION_MINE_SKIP = 3;
    static final int WAYPOINT_CONTROL_ACTION_DEPTH_CHECK = 4;

    private static final List<String> ROUTE_INFO_LABELS = List.of(
            "Click", "Double-click selected", "Right-click",
            "Shift-left-click", "Shift-right-click", "Color swatch");
    private static final List<String> ROUTE_INFO_DESCRIPTIONS = List.of(
            "Select a waypoint row", "Rename that waypoint", "Set the current waypoint",
            "Move in world", "Toggle subwaypoint", "Edit color; Shift-click unlocks");
    private static final List<String> DUNGEON_ROUTE_INFO_LABELS = List.of(
            "Types", "No trigger", "Stand", "Interact", "Mine", "Skip Ahead");
    private static final List<String> DUNGEON_ROUTE_INFO_DESCRIPTIONS = List.of(
            "Label the selected dungeon waypoint", "Skip when near",
            "Skip when standing for 0.5 seconds", "Right-click to skip",
            "Break to skip", "Waypoints can be skipped");

    private GroupEditPolicy() {}

    static RouteColorMode routeColorMode(WaypointGroup.GradientMode mode, boolean paintActive) {
        if (paintActive) return RouteColorMode.PAINT;
        if (mode == WaypointGroup.GradientMode.AUTO) return RouteColorMode.GRADIENT;
        if (mode == WaypointGroup.GradientMode.STATIC) return RouteColorMode.ONE;
        return RouteColorMode.COLOR;
    }

    static RouteColorMode nextColorMode(RouteColorMode mode) {
        return switch (mode == null ? RouteColorMode.COLOR : mode) {
            case COLOR -> RouteColorMode.GRADIENT;
            case GRADIENT -> RouteColorMode.ONE;
            case ONE -> RouteColorMode.PAINT;
            case PAINT -> RouteColorMode.COLOR;
        };
    }

    static String colorModeName(RouteColorMode mode) {
        String name = (mode == null ? RouteColorMode.COLOR : mode).name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    static String colorModeTooltipKey(RouteColorMode mode) {
        return "waypointer.screen.group_edit.color_mode.tooltip."
                + colorModeName(mode).toLowerCase(Locale.ROOT);
    }

    static String colorModeTooltipFallback(RouteColorMode mode) {
        return switch (mode == null ? RouteColorMode.COLOR : mode) {
            case COLOR -> "Edit each waypoint's color.";
            case GRADIENT -> "Blend waypoint colors from Start to End.";
            case ONE -> "Use one color for the whole route.";
            case PAINT -> "Use painted waypoint faces.";
        };
    }

    static String modeTooltipKey(WaypointGroup.LoadMode mode) {
        return "waypointer.screen.group_edit.mode.tooltip."
                + (mode == WaypointGroup.LoadMode.SEQUENCE ? "sequence" : "static");
    }

    static String modeTooltipFallback(WaypointGroup.LoadMode mode) {
        return mode == WaypointGroup.LoadMode.SEQUENCE
                ? "Go through waypoints one by one."
                : "Show all waypoints.";
    }

    static String skipAheadTooltipText(boolean dungeonRoomGroup) {
        return dungeonRoomGroup
                ? "Entering a later waypoint's radius skips to it,\n"
                        + "even if it uses Stand, Interact, or Mine."
                : "Toggle skipping waypoints for this route.";
    }

    static List<String> routeInfoLabels(boolean dungeonRoomGroup) {
        return appendDungeonInfo(ROUTE_INFO_LABELS, DUNGEON_ROUTE_INFO_LABELS, dungeonRoomGroup);
    }

    static List<String> routeInfoDescriptions(boolean dungeonRoomGroup) {
        return appendDungeonInfo(
                ROUTE_INFO_DESCRIPTIONS, DUNGEON_ROUTE_INFO_DESCRIPTIONS, dungeonRoomGroup);
    }

    static List<ConnectorSegment> connectorSegments(WaypointGroup group) {
        if (group == null || group.size() < 2) return List.of();
        List<ConnectorSegment> segments = new ArrayList<>();
        List<ConnectorSegment> branches = new ArrayList<>();
        int previousMain = -1;
        for (int i = 0; i < group.size(); i++) {
            if (group.isSubwaypoint(i)) continue;
            if (previousMain >= 0) {
                int previousColor = group.get(previousMain).color();
                int nextColor = group.get(i).color();
                segments.add(new ConnectorSegment(false, previousMain, i,
                        previousColor, nextColor));
                int span = i - previousMain;
                for (int child = previousMain + 1; child < i; child++) {
                    double t = (child - previousMain) / (double) span;
                    branches.add(new ConnectorSegment(true, child, child,
                            interpolateRgb(previousColor, nextColor, t),
                            group.get(child).color()));
                }
            }
            previousMain = i;
        }
        int lastIndex = group.size() - 1;
        if (previousMain >= 0 && previousMain < lastIndex) {
            int spineColor = group.get(previousMain).color();
            segments.add(new ConnectorSegment(false, previousMain, lastIndex,
                    spineColor, spineColor));
            for (int child = previousMain + 1; child <= lastIndex; child++) {
                branches.add(new ConnectorSegment(true, child, child,
                        spineColor, group.get(child).color()));
            }
        }
        segments.addAll(branches);
        return List.copyOf(segments);
    }

    static int interpolateRgb(int color1, int color2, double t) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;
        int r = (int) Math.round(r1 + (r2 - r1) * t);
        int green = (int) Math.round(g1 + (g2 - g1) * t);
        int b = (int) Math.round(b1 + (b2 - b1) * t);
        return (r << 16) | (green << 8) | b;
    }

    static boolean isWaypointRowVisuallyActive(WaypointGroup group, int index) {
        if (group == null || index < 0 || index >= group.size()) return false;
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) return true;
        int currentIndex = group.currentIndex();
        int activeParent = group.activeSubwaypointParentIndex();
        if (group.isSubwaypoint(index)) {
            if (index == currentIndex) return true;
            int parent = group.parentMainIndex(index);
            return parent == activeParent || parent == currentIndex;
        }
        return index == currentIndex || index == activeParent;
    }

    static String waypointControlSummary(
            Waypoint waypoint, boolean subwaypoint, boolean dungeonRoomGroup) {
        if (waypoint == null) return "";
        List<String> active = new ArrayList<>();
        if (dungeonRoomGroup) {
            active.addAll(DungeonWaypointType.activeTypes(waypoint).stream()
                    .map(DungeonWaypointType::label)
                    .toList());
        }
        if (subwaypoint && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT)) active.add("Tiny");
        if (subwaypoint && waypoint.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT)) active.add("Filled");
        if (subwaypoint && waypoint.hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED)) {
            active.add("Hide");
        }
        if (dungeonRoomGroup && waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_STAND)) active.add("Stand");
        if (dungeonRoomGroup && waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT)) active.add("Interact");
        if (dungeonRoomGroup && waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_MINE)) active.add("Mine");
        if (waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)) active.add("LOS");
        return String.join(" · ", active);
    }

    static boolean shouldShowWaypointControls(int rowIndex, int selectedIndex) {
        return rowIndex >= 0 && rowIndex == selectedIndex;
    }

    static boolean shouldStartRenameFromRowClick(boolean doubleClick, boolean wasAlreadySelected) {
        return doubleClick && wasAlreadySelected;
    }

    static String swatchGestureTooltipText(boolean shiftDown) {
        return shiftDown ? "Shift-click unlocks locked color" : "Click to edit waypoint color";
    }

    static String dungeonStandSkipTooltipText() {
        return "Stand to skip";
    }

    static String dungeonInteractSkipTooltipText() {
        return "Interact to skip";
    }

    static String dungeonMineSkipTooltipText() {
        return "Mine to skip";
    }

    static int waypointControlFlagForAction(int action, boolean dungeonRoomGroup) {
        if (action == WAYPOINT_CONTROL_ACTION_STAND_SKIP && dungeonRoomGroup) {
            return Waypoint.FLAG_SKIP_ON_STAND;
        }
        if (action == WAYPOINT_CONTROL_ACTION_INTERACT_SKIP && dungeonRoomGroup) {
            return Waypoint.FLAG_SKIP_ON_INTERACT;
        }
        if (action == WAYPOINT_CONTROL_ACTION_MINE_SKIP && dungeonRoomGroup) {
            return Waypoint.FLAG_SKIP_ON_MINE;
        }
        return action == WAYPOINT_CONTROL_ACTION_DEPTH_CHECK ? Waypoint.FLAG_DEPTH_CHECKED : 0;
    }

    static int subwaypointStyleFlagForAction(int action) {
        return switch (action) {
            case SUBWAY_STYLE_ACTION_SMALL -> Waypoint.FLAG_SMALL_SUBWAYPOINT;
            case SUBWAY_STYLE_ACTION_FILLED -> Waypoint.FLAG_FILLED_SUBWAYPOINT;
            case SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT ->
                    Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED;
            default -> 0;
        };
    }

    static int selectedIndexAfterRemoval(int removedIndex, int sizeAfterRemoval) {
        if (removedIndex < 0 || sizeAfterRemoval <= 0) return -1;
        return Math.min(removedIndex, sizeAfterRemoval - 1);
    }

    static int moveWaypointSelection(WaypointGroup group, int selectedIndex, int delta) {
        if (group == null || selectedIndex < 0 || selectedIndex >= group.size()) {
            return selectedIndex;
        }
        return group.moveBy(selectedIndex, delta);
    }

    static String publishNameChangeIfNeeded(
            ActiveGroupManager manager, WaypointGroup group, String lastPublishedName) {
        String currentName = group.name();
        if (!currentName.equals(lastPublishedName)) manager.fireDataChanged();
        return currentName;
    }

    private static List<String> appendDungeonInfo(
            List<String> base, List<String> dungeon, boolean dungeonRoomGroup) {
        if (!dungeonRoomGroup) return base;
        List<String> result = new ArrayList<>(base);
        result.addAll(dungeon);
        return List.copyOf(result);
    }

    enum RouteColorMode { COLOR, GRADIENT, ONE, PAINT }

    record ConnectorSegment(boolean horizontal, int fromIndex, int toIndex,
                            int color1, int color2) {}
}
