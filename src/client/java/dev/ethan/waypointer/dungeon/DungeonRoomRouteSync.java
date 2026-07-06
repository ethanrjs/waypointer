package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mirrors authored dungeon-room secrets into normal runtime route groups.
 *
 * <p>Dungeon room data is stored in canonical room-local coordinates, while the
 * existing waypoint renderer/progression pipeline expects world coordinates.
 * This adapter does the one translation step when a physical room is detected,
 * then leaves rendering, labels, and progress to the ordinary route system.
 */
public final class DungeonRoomRouteSync {

    static final String GENERATED_GROUP_ID_PREFIX = "dungeon:auto:";

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private final Consumer<DungeonRoom> roomListener = room -> syncCurrentRoom();
    private final Consumer<Zone> zoneListener = this::onZoneChanged;
    private final Runnable syncListener = this::syncCurrentRoom;
    private boolean syncing;

    public DungeonRoomRouteSync(ActiveGroupManager manager, DungeonStateTracker tracker) {
        this.manager = manager;
        this.tracker = tracker;
    }

    public void install() {
        tracker.addRoomListener(roomListener);
        manager.addZoneListener(zoneListener);
        manager.addDataListener(syncListener);
        DungeonRoomData.addChangeListener(syncListener);
        syncCurrentRoom();
    }

    public void uninstall() {
        tracker.removeRoomListener(roomListener);
        manager.removeZoneListener(zoneListener);
        manager.removeDataListener(syncListener);
        DungeonRoomData.removeChangeListener(syncListener);
    }

    private void onZoneChanged(Zone zone) {
        if (!DungeonRoomZoneBridge.isBroadDungeonZone(zone)
                && !DungeonRoomZoneBridge.isRoomZone(zone)) {
            removeGeneratedGroups();
            return;
        }
        syncCurrentRoom();
    }

    private void syncCurrentRoom() {
        if (syncing) return;
        DungeonRoom room = tracker.currentRoom();
        if (room == null || !room.hasRoomId()) return;

        syncing = true;
        try {
            syncRoom(room);
        } finally {
            syncing = false;
        }
    }

    private void syncRoom(DungeonRoom room) {
        String roomId = room.roomId();
        String generatedId = generatedGroupId(roomId);
        DungeonRoomDefinition definition = DungeonRoomData.customDefinition(roomId);
        if (definition == null
                || definition.waypoints().isEmpty()
                || hasUserRouteGroup(roomId)) {
            removeGeneratedGroup(generatedId);
            return;
        }

        manager.add(routeGroupForRoom(room, definition));
    }

    private boolean hasUserRouteGroup(String roomId) {
        for (WaypointGroup group : manager.groupsForZone(roomId)) {
            if (!group.runtimeOnly()) return true;
        }
        return false;
    }

    private void removeGeneratedGroups() {
        List<String> ids = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (isGeneratedGroup(group)) ids.add(group.id());
        }
        for (String id : ids) {
            manager.remove(id);
        }
    }

    private void removeGeneratedGroup(String id) {
        WaypointGroup existing = manager.get(id);
        if (isGeneratedGroup(existing)) {
            manager.remove(id);
        }
    }

    static WaypointGroup routeGroupForRoom(DungeonRoom room, DungeonRoomDefinition definition) {
        WaypointGroup group = new WaypointGroup(
                generatedGroupId(definition.id()),
                "Dungeon Secrets -- " + definition.displayName(),
                definition.id());
        group.setRuntimeOnly(true);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);

        List<Waypoint> waypoints = new ArrayList<>();
        for (DungeonWaypoint dungeonWaypoint : definition.waypoints()) {
            addWaypoint(room, dungeonWaypoint, waypoints);
        }
        group.addAll(waypoints);
        return group;
    }

    private static void addWaypoint(DungeonRoom room, DungeonWaypoint dungeonWaypoint,
                                    List<Waypoint> out) {
        if (dungeonWaypoint.secretIndex() <= 0) return;

        int[] actual = DungeonMapMath.relativeToActual(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                dungeonWaypoint.x(),
                dungeonWaypoint.y(),
                dungeonWaypoint.z());
        out.add(new Waypoint(
                actual[0],
                actual[1],
                actual[2],
                dungeonWaypoint.name(),
                dungeonWaypoint.color(),
                DungeonWaypointSkipRules.flagsForTriggerAt(
                        dungeonWaypoint.trigger(), actual[0], actual[1], actual[2]),
                0.0));

        for (DungeonHighlight highlight : dungeonWaypoint.highlights()) {
            int[] highlightActual = DungeonMapMath.relativeToActual(
                    room.direction(),
                    room.physicalCornerX(),
                    room.physicalCornerZ(),
                    highlight.x(),
                    highlight.y(),
                    highlight.z());
            int color = highlight.hasOwnColor() ? highlight.color() : dungeonWaypoint.color();
            out.add(new Waypoint(
                    highlightActual[0],
                    highlightActual[1],
                    highlightActual[2],
                    "",
                    color,
                    Waypoint.FLAG_SUBWAYPOINT | highlightFlags(highlight.style()),
                    0.0));
        }
    }

    private static int highlightFlags(DungeonHighlightStyle style) {
        return style == DungeonHighlightStyle.FILLED
                || style == DungeonHighlightStyle.OUTLINE_FILLED
                ? Waypoint.FLAG_FILLED_SUBWAYPOINT
                : 0;
    }

    public static boolean isGeneratedGroup(WaypointGroup group) {
        return group != null
                && group.runtimeOnly()
                && group.id().startsWith(GENERATED_GROUP_ID_PREFIX);
    }

    public static String generatedGroupId(String roomId) {
        return GENERATED_GROUP_ID_PREFIX + DungeonRoomDefinition.normalizeId(roomId);
    }
}
