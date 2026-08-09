package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;

import java.util.ArrayList;
import java.util.List;

public final class DungeonRoomRouteProjection {

    static final String GENERATED_GROUP_ID_PREFIX = "dungeon:auto:";

    private DungeonRoomRouteProjection() {}

    static WaypointGroup transformedRouteGroupForRoom(DungeonRoom room, WaypointGroup source) {
        return transformedRouteGroupForRoom(room, source, 0);
    }

    static WaypointGroup transformedRouteGroupForRoom(DungeonRoom room, WaypointGroup source,
                                                      int visibleSecretStages) {
        WaypointGroup group = new WaypointGroup(
                generatedGroupId(source.zoneId()), source.name(), source.zoneId());
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setRuntimeOnly(true);
        group.setRuntimeSourceGroupId(source.id());
        group.setVisibleMainSteps(visibleSecretStages);
        group.setLoadMode(source.loadMode());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setDefaultRadius(source.defaultRadius());
        group.setSkipAheadEnabled(source.skipAheadEnabled());
        group.setPaint(source.paint());
        group.setPaintEnabled(source.paintEnabled());

        List<Waypoint> waypoints = new ArrayList<>(source.size());
        for (Waypoint stored : source.waypoints()) {
            waypoints.add(DungeonRoomWaypointPlacement.toActualWaypoint(room, stored));
        }
        group.addAll(waypoints);
        group.setCurrentIndex(source.currentIndex());
        return group;
    }

    public static boolean isGeneratedGroup(WaypointGroup group) {
        return group != null
                && group.runtimeOnly()
                && group.id().startsWith(GENERATED_GROUP_ID_PREFIX);
    }

    public static String generatedGroupId(String roomId) {
        return GENERATED_GROUP_ID_PREFIX + DungeonRoomCatalogEntry.normalizeId(roomId);
    }
}
