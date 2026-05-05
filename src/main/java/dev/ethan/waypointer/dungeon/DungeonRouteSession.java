package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.dungeon.data.DungeonRoomData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-run progress for dungeon secret routes.
 *
 * <p>The catalog is persistent, but found/current state is deliberately not:
 * Catacombs rooms are regenerated every run and a saved "found" bit from the
 * previous run would hide useful waypoints in the next one.
 */
public final class DungeonRouteSession {

    public enum Status { FOUND, CURRENT, UPCOMING }

    private static final int NO_CURRENT_SECRET = 0;

    private final Map<String, RoomProgress> progressByRoom = new HashMap<>();

    public void resetAll() {
        progressByRoom.clear();
    }

    public void resetRoom(DungeonRoom room) {
        String key = routeKey(room);
        if (!key.isEmpty()) progressByRoom.remove(key);
        if (room != null && room.hasRoomId()) {
            progressByRoom.remove(room.identityKey());
        }
    }

    public void markFound(DungeonRoom room, int secretIndex) {
        if (room == null || secretIndex <= 0) return;
        RoomProgress progress = progressFor(room);
        progress.foundSecretIndices.add(secretIndex);
        if (secretIndex == progress.currentSecretIndex) {
            progress.currentSecretIndex = nextUnfoundSecret(room, progress);
        }
    }

    public void advance(DungeonRoom room) {
        if (room == null) return;
        RoomProgress progress = progressFor(room);
        if (progress.currentSecretIndex == NO_CURRENT_SECRET) return;

        progress.foundSecretIndices.add(progress.currentSecretIndex);
        progress.currentSecretIndex = nextUnfoundSecret(room, progress);
    }

    public int currentSecretIndex(DungeonRoom room) {
        return progressFor(room).currentSecretIndex;
    }

    public Status status(DungeonRoom room, DungeonWaypoint waypoint) {
        RoomProgress progress = progressFor(room);
        int secretIndex = waypoint.secretIndex();
        if (secretIndex <= 0) return Status.UPCOMING;
        if (progress.foundSecretIndices.contains(secretIndex)) return Status.FOUND;
        if (secretIndex == progress.currentSecretIndex) return Status.CURRENT;
        return Status.UPCOMING;
    }

    private RoomProgress progressFor(DungeonRoom room) {
        String key = routeKey(room);
        RoomProgress migrated = migrateProgress(room, key);
        if (migrated != null) return migrated;

        return progressByRoom.computeIfAbsent(key, ignored -> {
            RoomProgress progress = new RoomProgress();
            progress.currentSecretIndex = firstSecretIndex(room);
            return progress;
        });
    }

    private RoomProgress migrateProgress(DungeonRoom room, String key) {
        if (room == null || !room.hasRoomId()) return null;

        RoomProgress existing = progressByRoom.get(key);
        if (existing != null) return existing;

        RoomProgress migrated = progressByRoom.remove(room.identityKey());
        if (migrated == null) return null;

        progressByRoom.put(key, migrated);
        return migrated;
    }

    private static int firstSecretIndex(DungeonRoom room) {
        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);
        int min = Integer.MAX_VALUE;
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint.secretIndex() > 0 && waypoint.secretIndex() < min) {
                min = waypoint.secretIndex();
            }
        }
        return min == Integer.MAX_VALUE ? NO_CURRENT_SECRET : min;
    }

    private static int nextUnfoundSecret(DungeonRoom room, RoomProgress progress) {
        if (progress.currentSecretIndex == NO_CURRENT_SECRET) return NO_CURRENT_SECRET;

        int next = Integer.MAX_VALUE;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            int index = waypoint.secretIndex();
            if (index > 0
                    && index > progress.currentSecretIndex
                    && !progress.foundSecretIndices.contains(index)
                    && index < next) {
                next = index;
            }
        }
        return next == Integer.MAX_VALUE ? NO_CURRENT_SECRET : next;
    }

    private static String routeKey(DungeonRoom room) {
        if (room == null) return "";
        return room.hasRoomId() ? room.roomId() : room.identityKey();
    }

    private static final class RoomProgress {
        private int currentSecretIndex = NO_CURRENT_SECRET;
        private final Set<Integer> foundSecretIndices = new HashSet<>();
    }
}
