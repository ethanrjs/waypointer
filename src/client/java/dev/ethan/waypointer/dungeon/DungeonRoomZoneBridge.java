package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;

public final class DungeonRoomZoneBridge {

    private static volatile String debugLine = "not installed";

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private Zone lastDungeonZone;
    private boolean applyingRoomZone;

    public DungeonRoomZoneBridge(ActiveGroupManager manager, DungeonStateTracker tracker) {
        this.manager = manager;
        this.tracker = tracker;
    }

    public void install() {
        manager.addZoneListener(this::onZoneChanged);
        tracker.addRoomListener(this::onRoomChanged);
        debugLine = "installed, current=" + describeZone(manager.currentZone());
        Zone currentZone = manager.currentZone();
        if (currentZone != null) onZoneChanged(currentZone);
        DungeonRoom currentRoom = tracker.currentRoom();
        if (currentRoom != null) onRoomChanged(currentRoom);
    }

    private void onZoneChanged(Zone zone) {
        if (applyingRoomZone) {
            debugLine = "applied room zone=" + describeZone(zone)
                    + ", lastBroad=" + describeZone(lastDungeonZone);
            return;
        }
        if (isBroadDungeonZone(zone)) {
            lastDungeonZone = zone;
            DungeonRoom room = tracker.currentRoom();
            if (tracker.inDungeon() && room != null && room.hasRoomId()) {
                applyRoomZone(room, "broad dungeon update");
                return;
            }
            debugLine = "saw broad dungeon=" + describeZone(zone)
                    + ", room=" + describeRoom(room);
            return;
        }
        if (!isRoomZone(zone)) {
            lastDungeonZone = null;
            debugLine = "left dungeon context with zone=" + describeZone(zone);
            return;
        }
        debugLine = "saw room zone=" + describeZone(zone)
                + ", lastBroad=" + describeZone(lastDungeonZone);
    }

    private void onRoomChanged(DungeonRoom room) {
        if (!tracker.inDungeon()) {
            debugLine = "room changed outside dungeon: " + describeRoom(room);
            return;
        }
        if (room != null && room.hasRoomId()) {
            applyRoomZone(room, "room tracker update");
            return;
        }
        if (lastDungeonZone != null && !lastDungeonZone.equals(manager.currentZone())) {
            manager.onZoneChanged(lastDungeonZone);
            debugLine = "restored broad dungeon=" + describeZone(lastDungeonZone)
                    + ", room=" + describeRoom(room);
            return;
        }
        debugLine = "room unmatched, current=" + describeZone(manager.currentZone())
                + ", lastBroad=" + describeZone(lastDungeonZone);
    }

    private void applyRoomZone(DungeonRoom room, String reason) {
        Zone roomZone = new Zone(room.roomId(), room.displayName());
        if (roomZone.equals(manager.currentZone())) {
            debugLine = "kept room zone=" + describeZone(roomZone)
                    + ", reason=" + reason;
            return;
        }
        applyingRoomZone = true;
        try {
            manager.onZoneChanged(roomZone);
        } finally {
            applyingRoomZone = false;
        }
        debugLine = "applied room zone=" + describeZone(roomZone)
                + ", reason=" + reason
                + ", lastBroad=" + describeZone(lastDungeonZone);
    }

    static boolean isBroadDungeonZone(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        return id.equals("dungeon") || id.startsWith("dungeon_");
    }

    static boolean isRoomZone(Zone zone) {
        return zone != null && DungeonRoomData.definition(zone.id()) != null;
    }

    public static String debugLine() {
        return debugLine;
    }

    private static String describeZone(Zone zone) {
        if (zone == null) return "(none)";
        return zone.displayName() + " (" + zone.id() + ")";
    }

    private static String describeRoom(DungeonRoom room) {
        if (room == null) return "(none)";
        String roomId = room.hasRoomId() ? room.roomId() : "<unmatched>";
        return room.displayName() + " (" + roomId + ")";
    }
}
