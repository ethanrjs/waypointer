package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;

public final class DungeonRoomZoneBridge {

    private static volatile String debugLine = "not installed";
    private static volatile DebugSnapshot debugSnapshot = DebugSnapshot.notInstalled();

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
        publishDebugSnapshot("installed", "initial listener registration");
        Zone currentZone = manager.currentZone();
        if (currentZone != null) onZoneChanged(currentZone);
        DungeonRoom currentRoom = tracker.currentRoom();
        if (currentRoom != null) onRoomChanged(currentRoom);
    }

    private void onZoneChanged(Zone zone) {
        if (applyingRoomZone) {
            debugLine = "applied room zone=" + describeZone(zone)
                    + ", lastBroad=" + describeZone(lastDungeonZone);
            publishDebugSnapshot("zone callback ignored", "reentrant room-zone apply");
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
            publishDebugSnapshot("remembered broad dungeon", describeZone(zone));
            return;
        }
        if (!isRoomZone(zone)) {
            lastDungeonZone = null;
            debugLine = "left dungeon context with zone=" + describeZone(zone);
            publishDebugSnapshot("left dungeon context", describeZone(zone));
            return;
        }
        debugLine = "saw room zone=" + describeZone(zone)
                + ", lastBroad=" + describeZone(lastDungeonZone);
        publishDebugSnapshot("saw room zone", describeZone(zone));
    }

    private void onRoomChanged(DungeonRoom room) {
        if (!tracker.inDungeon()) {
            debugLine = "room changed outside dungeon: " + describeRoom(room);
            publishDebugSnapshot("room outside dungeon", describeRoom(room));
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
            publishDebugSnapshot("restored broad dungeon", describeRoom(room));
            return;
        }
        debugLine = "room unmatched, current=" + describeZone(manager.currentZone())
                + ", lastBroad=" + describeZone(lastDungeonZone);
        publishDebugSnapshot("room unmatched", describeRoom(room));
    }

    private void applyRoomZone(DungeonRoom room, String reason) {
        Zone roomZone = new Zone(room.roomId(), room.displayName());
        if (roomZone.equals(manager.currentZone())) {
            debugLine = "kept room zone=" + describeZone(roomZone)
                    + ", reason=" + reason;
            publishDebugSnapshot("kept room zone", reason);
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
        publishDebugSnapshot("applied room zone", reason);
    }

    static boolean isBroadDungeonZone(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        return id.equals("dungeon") || id.startsWith("dungeon_f") || id.startsWith("dungeon_m");
    }

    static boolean isRoomZone(Zone zone) {
        return zone != null && DungeonRoomData.definition(zone.id()) != null;
    }

    public static String debugLine() {
        return debugLine;
    }

    public static DebugSnapshot debugSnapshot() {
        return debugSnapshot;
    }

    private void publishDebugSnapshot(String action, String reason) {
        debugSnapshot = new DebugSnapshot(
                true,
                debugLine,
                describeZone(manager.currentZone()),
                describeZone(lastDungeonZone),
                applyingRoomZone,
                action,
                reason);
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

    public static final class DebugSnapshot {
        public final boolean installed;
        public final String line;
        public final String currentZone;
        public final String lastBroadZone;
        public final boolean applyingRoomZone;
        public final String lastAction;
        public final String lastReason;

        private static DebugSnapshot notInstalled() {
            return new DebugSnapshot(
                    false,
                    "not installed",
                    "(none)",
                    "(none)",
                    false,
                    "not installed",
                    "(none)");
        }

        private DebugSnapshot(boolean installed,
                              String line,
                              String currentZone,
                              String lastBroadZone,
                              boolean applyingRoomZone,
                              String lastAction,
                              String lastReason) {
            this.installed = installed;
            this.line = normalize(line);
            this.currentZone = normalize(currentZone);
            this.lastBroadZone = normalize(lastBroadZone);
            this.applyingRoomZone = applyingRoomZone;
            this.lastAction = normalize(lastAction);
            this.lastReason = normalize(lastReason);
        }

        private static String normalize(String value) {
            if (value == null) return "(none)";
            String trimmed = value.trim();
            return trimmed.isEmpty() ? "(none)" : trimmed;
        }
    }
}
