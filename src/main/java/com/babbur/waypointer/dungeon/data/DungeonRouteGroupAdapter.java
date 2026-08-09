package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonHighlight;
import com.babbur.waypointer.dungeon.DungeonHighlightStyle;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.DungeonWaypointTrigger;

import java.util.ArrayList;
import java.util.List;

/** Converts legacy dungeon room entries into standard Waypointer routes. */
final class DungeonRouteGroupAdapter {

    private static final int SUPPORT_WAYPOINT_COLOR = 0xFFB300;

    private DungeonRouteGroupAdapter() {}

    static WaypointGroup fromWaypoints(String roomId, String name,
                                       List<DungeonWaypoint> dungeonWaypoints) {
        String normalizedRoomId = DungeonRoomCatalogEntry.normalizeId(roomId);
        if (normalizedRoomId.isBlank()) {
            throw new IllegalArgumentException("dungeon route has no room id");
        }
        WaypointGroup group = WaypointGroup.create(
                name == null || name.isBlank() ? "Dungeon Route" : name,
                normalizedRoomId);
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setSkipAheadEnabled(false);

        List<Waypoint> waypoints = new ArrayList<>();
        List<Waypoint> leadingSupport = new ArrayList<>();
        boolean hasProgressWaypoint = false;
        int currentStage = Integer.MIN_VALUE;
        boolean stageHasMain = false;
        for (DungeonWaypoint dungeonWaypoint : dungeonWaypoints == null
                ? List.<DungeonWaypoint>of() : dungeonWaypoints) {
            if (dungeonWaypoint.secretIndex() <= 0) {
                Waypoint support = new Waypoint(
                        dungeonWaypoint.x(), dungeonWaypoint.y(), dungeonWaypoint.z(),
                        dungeonWaypoint.name(),
                        dungeonWaypoint.hasOwnColor()
                                ? dungeonWaypoint.color() : SUPPORT_WAYPOINT_COLOR,
                        Waypoint.FLAG_SUBWAYPOINT, 0.0);
                if (hasProgressWaypoint) waypoints.add(support);
                else leadingSupport.add(support);
                continue;
            }

            if (dungeonWaypoint.secretIndex() != currentStage) {
                currentStage = dungeonWaypoint.secretIndex();
                stageHasMain = false;
            }
            int flags = flagsForTrigger(dungeonWaypoint.trigger());
            if (stageHasMain) flags |= Waypoint.FLAG_SUBWAYPOINT;
            if (dungeonWaypoint.completesSecret()) flags |= Waypoint.FLAG_DUNGEON_SECRET;
            if (usesLineOfSight(dungeonWaypoint.trigger())) flags |= Waypoint.FLAG_DEPTH_CHECKED;
            int color = actionColor(dungeonWaypoint);
            waypoints.add(new Waypoint(
                    dungeonWaypoint.x(), dungeonWaypoint.y(), dungeonWaypoint.z(),
                    dungeonWaypoint.name(), color, flags, 0.0));
            if (!hasProgressWaypoint) {
                hasProgressWaypoint = true;
                waypoints.addAll(leadingSupport);
                leadingSupport.clear();
            }
            stageHasMain = true;

            for (DungeonHighlight highlight : dungeonWaypoint.highlights()) {
                int highlightFlags = Waypoint.FLAG_SUBWAYPOINT | highlightFlags(highlight.style());
                if (dungeonWaypoint.trigger() == DungeonWaypointTrigger.THROW_PEARL) {
                    highlightFlags |= Waypoint.FLAG_DUNGEON_PEARL_TARGET
                            | Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME;
                } else if (dungeonWaypoint.trigger() == DungeonWaypointTrigger.BREAK_BLOCKS
                        || dungeonWaypoint.trigger() == DungeonWaypointTrigger.DUNGEONBREAKER) {
                    highlightFlags |= flagsForTrigger(dungeonWaypoint.trigger());
                    if (usesLineOfSight(dungeonWaypoint.trigger())) {
                        highlightFlags |= Waypoint.FLAG_DEPTH_CHECKED;
                    }
                }
                waypoints.add(new Waypoint(
                        highlight.x(), highlight.y(), highlight.z(), "",
                        highlight.hasOwnColor() ? highlight.color() : color,
                        highlightFlags, 0.0));
            }
        }

        if (!hasProgressWaypoint) {
            group.setLoadMode(WaypointGroup.LoadMode.STATIC);
            for (Waypoint support : leadingSupport) waypoints.add(support.withSubwaypoint(false));
        }
        group.addAll(waypoints);
        return group;
    }

    private static int actionColor(DungeonWaypoint waypoint) {
        if (waypoint.hasOwnColor()) return waypoint.color();
        return switch (waypoint.trigger()) {
            case INTERACT_BLOCK, FLIP_LEVER -> DungeonSecretCategory.LEVER.defaultColor;
            case OPEN_CHEST, CHAT_MESSAGE, ANY_SECRET -> DungeonSecretCategory.CHEST.defaultColor;
            case USE_SUPERBOOM -> DungeonSecretCategory.SUPERBOOM.defaultColor;
            case PICKUP_ITEM -> DungeonSecretCategory.ITEM.defaultColor;
            case KILL_BAT -> DungeonSecretCategory.BAT.defaultColor;
            case BREAK_BLOCKS -> DungeonSecretCategory.STONK.defaultColor;
            case DUNGEONBREAKER -> DungeonSecretCategory.DUNGEONBREAKER.defaultColor;
            case ETHERWARP -> DungeonSecretCategory.ETHERWARP.defaultColor;
            case THROW_PEARL -> DungeonSecretCategory.PEARL.defaultColor;
            case MANUAL -> waypoint.category().defaultColor;
        };
    }

    private static int flagsForTrigger(DungeonWaypointTrigger trigger) {
        if (trigger == null) return Waypoint.FLAG_SKIP_ON_STAND;
        return switch (trigger) {
            case DUNGEONBREAKER -> Waypoint.FLAG_SKIP_ON_MINE
                    | Waypoint.FLAG_DUNGEON_DUNGEONBREAKER;
            case BREAK_BLOCKS -> Waypoint.FLAG_SKIP_ON_MINE;
            case USE_SUPERBOOM -> Waypoint.FLAG_SKIP_ON_INTERACT
                    | Waypoint.FLAG_DUNGEON_SUPERBOOM;
            case ETHERWARP -> Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP;
            case THROW_PEARL -> Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_PEARL;
            case PICKUP_ITEM -> Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ITEM;
            case KILL_BAT -> Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_BAT;
            case INTERACT_BLOCK, FLIP_LEVER, OPEN_CHEST, ANY_SECRET ->
                    Waypoint.FLAG_SKIP_ON_INTERACT;
            case CHAT_MESSAGE, MANUAL -> Waypoint.FLAG_SKIP_ON_STAND;
        };
    }

    private static boolean usesLineOfSight(DungeonWaypointTrigger trigger) {
        return trigger == DungeonWaypointTrigger.ETHERWARP
                || trigger == DungeonWaypointTrigger.DUNGEONBREAKER;
    }

    private static int highlightFlags(DungeonHighlightStyle style) {
        return style == DungeonHighlightStyle.FILLED
                || style == DungeonHighlightStyle.OUTLINE_FILLED
                ? Waypoint.FLAG_FILLED_SUBWAYPOINT : 0;
    }
}
