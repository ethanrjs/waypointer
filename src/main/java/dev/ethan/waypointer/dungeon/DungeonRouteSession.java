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

    private final Map<String, RoomProgress> progressByRoom = new HashMap<>();

    public void resetAll() {
        progressByRoom.clear();
    }

    public void resetRoom(DungeonRoom room) {
        String key = routeKey(room);
        if (!key.isEmpty()) progressByRoom.remove(key);
    }

    public void markFound(DungeonRoom room, int secretIndex) {
        if (room == null) return;
        RoomProgress progress = progressFor(room);
        progress.foundSecretIndices.add(secretIndex);
        if (secretIndex == progress.currentSecretIndex) {
            progress.currentSecretIndex = nextUnfoundSecret(room, progress);
        }
    }

    public void advance(DungeonRoom room) {
        if (room == null) return;
        RoomProgress progress = progressFor(room);
        progress.foundSecretIndices.add(progress.currentSecretIndex);
        progress.currentSecretIndex = nextUnfoundSecret(room, progress);
    }

    public int currentSecretIndex(DungeonRoom room) {
        return progressFor(room).currentSecretIndex;
    }

    public Status status(DungeonRoom room, DungeonWaypoint waypoint) {
        RoomProgress progress = progressFor(room);
        if (progress.foundSecretIndices.contains(waypoint.secretIndex())) return Status.FOUND;
        if (waypoint.secretIndex() == progress.currentSecretIndex) return Status.CURRENT;
        return Status.UPCOMING;
    }

    private RoomProgress progressFor(DungeonRoom room) {
        String key = routeKey(room);
        return progressByRoom.computeIfAbsent(key, ignored -> {
            RoomProgress progress = new RoomProgress();
            progress.currentSecretIndex = firstSecretIndex(room);
            return progress;
        });
    }

    private static int firstSecretIndex(DungeonRoom room) {
        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);
        int min = Integer.MAX_VALUE;
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint.secretIndex() > 0 && waypoint.secretIndex() < min) {
                min = waypoint.secretIndex();
            }
        }
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    private static int nextUnfoundSecret(DungeonRoom room, RoomProgress progress) {
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
        return next == Integer.MAX_VALUE ? progress.currentSecretIndex + 1 : next;
    }

    private static String routeKey(DungeonRoom room) {
        if (room == null) return "";
        return room.hasRoomId() ? room.roomId() : room.identityKey();
    }

    private static final class RoomProgress {
        private int currentSecretIndex = 1;
        private final Set<Integer> foundSecretIndices = new HashSet<>();
    }
}
