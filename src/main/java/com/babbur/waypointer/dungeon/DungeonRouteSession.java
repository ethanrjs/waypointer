package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;

import java.util.ArrayList;
import java.util.Collections;
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

    public enum Status { FOUND, CURRENT, UPCOMING, NON_PROGRESS }

    private static final int NO_CURRENT_SECRET = 0;

    private final Map<String, RoomProgress> progressByRoom = new HashMap<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private String lastResetReason = "(none)";
    private long lastResetAtMillis;

    /**
     * Listener fired after any progress mutation (found, advance, reset,
     * room completion). Lets the route mirror rebuild the visible group so
     * found secrets disappear from the world as they're collected.
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null) changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(changeListeners)) {
            listener.run();
        }
    }

    public void resetAll() {
        progressByRoom.clear();
        lastResetReason = "resetAll";
        lastResetAtMillis = System.currentTimeMillis();
        fireChanged();
    }

    public void resetRoom(DungeonRoom room) {
        for (String key : roomKeys(room)) {
            progressByRoom.remove(key);
        }
        lastResetReason = "resetRoom " + debugRoomKey(room);
        lastResetAtMillis = System.currentTimeMillis();
        if (room == null) return;
        progressByRoom.remove(room.identityKey());
        if (room.hasRoomId()) {
            progressByRoom.remove(room.roomId());
        }
        fireChanged();
    }

    public void markFound(DungeonRoom room, int secretIndex) {
        if (room == null || secretIndex <= 0) return;
        RoomProgress progress = progressFor(room);
        if (!progress.foundSecretIndices.add(secretIndex)) return;
        if (secretIndex == progress.currentSecretIndex) {
            progress.currentSecretIndex = nextUnfoundSecret(room, progress);
        }
        fireChanged();
    }

    public void advance(DungeonRoom room) {
        if (room == null) return;
        RoomProgress progress = progressFor(room);
        if (progress.currentSecretIndex == NO_CURRENT_SECRET) return;

        progress.foundSecretIndices.add(progress.currentSecretIndex);
        progress.currentSecretIndex = nextUnfoundSecret(room, progress);
        fireChanged();
    }

    /**
     * Mark every authored progress secret in the room found. Driven by the
     * dungeon map's green checkmark: Hypixel paints it once a room is cleared
     * with all secrets collected, which is authoritative even when a teammate
     * collected them.
     */
    public void markRoomComplete(DungeonRoom room) {
        if (room == null) return;
        RoomProgress progress = progressFor(room);
        boolean changed = false;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if (waypoint.secretIndex() > 0) {
                changed |= progress.foundSecretIndices.add(waypoint.secretIndex());
            }
        }
        if (progress.currentSecretIndex != NO_CURRENT_SECRET) {
            progress.currentSecretIndex = NO_CURRENT_SECRET;
            changed = true;
        }
        if (changed) fireChanged();
    }

    /** True when the room has authored progress secrets and every one is found. */
    public boolean isRoomComplete(DungeonRoom room) {
        if (room == null) return false;
        RoomProgress progress = peekProgressFor(room);
        boolean sawProgressSecret = false;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            int index = waypoint.secretIndex();
            if (index <= 0) continue;
            sawProgressSecret = true;
            if (!progress.foundSecretIndices.contains(index)) return false;
        }
        return sawProgressSecret;
    }

    public int currentSecretIndex(DungeonRoom room) {
        if (room == null) return NO_CURRENT_SECRET;
        return progressFor(room).currentSecretIndex;
    }

    public Status status(DungeonRoom room, DungeonWaypoint waypoint) {
        if (waypoint == null) return Status.NON_PROGRESS;
        int secretIndex = waypoint.secretIndex();
        if (secretIndex <= 0) return Status.NON_PROGRESS;

        RoomProgress progress = progressFor(room);
        if (progress.foundSecretIndices.contains(secretIndex)) return Status.FOUND;
        if (secretIndex == progress.currentSecretIndex) return Status.CURRENT;
        return Status.UPCOMING;
    }

    public Status peekStatus(DungeonRoom room, DungeonWaypoint waypoint) {
        if (waypoint == null) return Status.NON_PROGRESS;
        int secretIndex = waypoint.secretIndex();
        if (secretIndex <= 0) return Status.NON_PROGRESS;

        RoomProgress progress = peekProgressFor(room);
        if (progress.foundSecretIndices.contains(secretIndex)) return Status.FOUND;
        if (secretIndex == progress.currentSecretIndex) return Status.CURRENT;
        return Status.UPCOMING;
    }

    public DebugSnapshot debugSnapshot(DungeonRoom room) {
        if (room == null) {
            return DebugSnapshot.empty(lastResetReason, lastResetAtMillis, progressByRoom.size());
        }

        List<String> keys = roomKeys(room);
        RoomProgress existingProgress = existingProgressForKeys(keys);
        RoomProgress progress = existingProgress == null
                ? transientProgressFor(room)
                : existingProgress;
        int totalProgressWaypoints = 0;
        int foundCount = 0;
        int currentCount = 0;
        int upcomingCount = 0;
        int nonProgressCount = 0;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            Status status = peekStatusForProgress(progress, waypoint);
            switch (status) {
                case FOUND -> {
                    totalProgressWaypoints++;
                    foundCount++;
                }
                case CURRENT -> {
                    totalProgressWaypoints++;
                    currentCount++;
                }
                case UPCOMING -> {
                    totalProgressWaypoints++;
                    upcomingCount++;
                }
                case NON_PROGRESS -> nonProgressCount++;
            }
        }

        List<Integer> found = new ArrayList<>(progress.foundSecretIndices);
        Collections.sort(found);
        int aliasCount = countExistingAliases(keys);
        return new DebugSnapshot(
                true,
                keys.isEmpty() ? "(none)" : keys.get(0),
                physicalRoomKey(room),
                existingProgress != null,
                progress.currentSecretIndex,
                totalProgressWaypoints,
                foundCount,
                currentCount,
                upcomingCount,
                nonProgressCount,
                found,
                totalProgressWaypoints > 0 && progress.currentSecretIndex == NO_CURRENT_SECRET,
                aliasCount,
                progressByRoom.size(),
                lastResetReason,
                lastResetAtMillis);
    }

    private RoomProgress progressFor(DungeonRoom room) {
        List<String> keys = roomKeys(room);
        if (keys.isEmpty()) return new RoomProgress();

        RoomProgress progress = existingProgressForKeys(keys);
        if (progress == null) {
            progress = new RoomProgress();
            progress.currentSecretIndex = firstSecretIndex(room);
        }
        bindProgressToKeys(keys, progress);
        return progress;
    }

    private RoomProgress peekProgressFor(DungeonRoom room) {
        RoomProgress progress = existingProgressForKeys(roomKeys(room));
        return progress == null ? transientProgressFor(room) : progress;
    }

    private static RoomProgress transientProgressFor(DungeonRoom room) {
        RoomProgress progress = new RoomProgress();
        if (room != null) {
            progress.currentSecretIndex = firstSecretIndex(room);
        }
        return progress;
    }

    private static Status peekStatusForProgress(RoomProgress progress, DungeonWaypoint waypoint) {
        if (progress == null || waypoint == null) return Status.NON_PROGRESS;
        int secretIndex = waypoint.secretIndex();
        if (secretIndex <= 0) return Status.NON_PROGRESS;
        if (progress.foundSecretIndices.contains(secretIndex)) return Status.FOUND;
        if (secretIndex == progress.currentSecretIndex) return Status.CURRENT;
        return Status.UPCOMING;
    }

    private int countExistingAliases(List<String> keys) {
        int aliasCount = 0;
        for (String key : keys) {
            if (progressByRoom.containsKey(key)) {
                aliasCount++;
            }
        }
        return aliasCount;
    }

    private RoomProgress existingProgressForKeys(List<String> keys) {
        for (String key : keys) {
            RoomProgress progress = progressByRoom.get(key);
            if (progress != null) return progress;
        }
        return null;
    }

    private void bindProgressToKeys(List<String> keys, RoomProgress progress) {
        for (String key : keys) {
            progressByRoom.put(key, progress);
        }
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

    private static List<String> roomKeys(DungeonRoom room) {
        if (room == null) return List.of();

        List<String> keys = new ArrayList<>(4);
        if (room.hasRoomId()) {
            keys.add("room-id:" + room.roomId());
        }
        keys.add(physicalRoomKey(room));
        if (room.hasRoomId()) {
            keys.add(room.roomId());
        }
        keys.add(room.identityKey());
        return keys;
    }

    private static String physicalRoomKey(DungeonRoom room) {
        if (room.segments().isEmpty()) {
            return "identity:" + room.identityKey();
        }

        List<Long> segments = new ArrayList<>(room.segments());
        Collections.sort(segments);
        StringBuilder builder = new StringBuilder("segments:");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(segments.get(i));
        }
        return builder.toString();
    }

    private static String debugRoomKey(DungeonRoom room) {
        if (room == null) return "(none)";
        if (room.hasRoomId()) return "room-id:" + room.roomId();
        return physicalRoomKey(room);
    }

    public static final class DebugSnapshot {
        public final boolean roomPresent;
        public final String roomKey;
        public final String physicalKey;
        public final boolean progressInitialized;
        public final int currentSecretIndex;
        public final int totalProgressWaypoints;
        public final int foundCount;
        public final int currentCount;
        public final int upcomingCount;
        public final int nonProgressCount;
        public final List<Integer> foundSecretIndices;
        public final boolean complete;
        public final int aliasCount;
        public final int progressEntryCount;
        public final String lastResetReason;
        public final long lastResetAtMillis;

        private static DebugSnapshot empty(String lastResetReason,
                                           long lastResetAtMillis,
                                           int progressEntryCount) {
            return new DebugSnapshot(
                    false,
                    "(none)",
                    "(none)",
                    false,
                    NO_CURRENT_SECRET,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    false,
                    0,
                    progressEntryCount,
                    lastResetReason,
                    lastResetAtMillis);
        }

        private DebugSnapshot(boolean roomPresent,
                              String roomKey,
                              String physicalKey,
                              boolean progressInitialized,
                              int currentSecretIndex,
                              int totalProgressWaypoints,
                              int foundCount,
                              int currentCount,
                              int upcomingCount,
                              int nonProgressCount,
                              List<Integer> foundSecretIndices,
                              boolean complete,
                              int aliasCount,
                              int progressEntryCount,
                              String lastResetReason,
                              long lastResetAtMillis) {
            this.roomPresent = roomPresent;
            this.roomKey = roomKey == null || roomKey.isBlank() ? "(none)" : roomKey;
            this.physicalKey = physicalKey == null || physicalKey.isBlank() ? "(none)" : physicalKey;
            this.progressInitialized = progressInitialized;
            this.currentSecretIndex = currentSecretIndex;
            this.totalProgressWaypoints = totalProgressWaypoints;
            this.foundCount = foundCount;
            this.currentCount = currentCount;
            this.upcomingCount = upcomingCount;
            this.nonProgressCount = nonProgressCount;
            this.foundSecretIndices = foundSecretIndices == null ? List.of() : List.copyOf(foundSecretIndices);
            this.complete = complete;
            this.aliasCount = aliasCount;
            this.progressEntryCount = progressEntryCount;
            this.lastResetReason = lastResetReason == null || lastResetReason.isBlank()
                    ? "(none)"
                    : lastResetReason;
            this.lastResetAtMillis = lastResetAtMillis;
        }
    }

    private static final class RoomProgress {
        private int currentSecretIndex = NO_CURRENT_SECRET;
        private final Set<Integer> foundSecretIndices = new HashSet<>();
    }
}
