package dev.ethan.waypointer.dungeon.data;

import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonRoomType;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stable, authored description of a dungeon room and its route data.
 *
 * <p>{@code DungeonRoom} describes a live physical instance; this class is the
 * persistent catalog entry that can be matched to many physical instances over
 * time. Waypoints and fingerprints are room-local, never world-local.
 */
public record DungeonRoomDefinition(
        String id,
        String displayName,
        DungeonRoomType type,
        DungeonRoomShape shape,
        List<Integer> coreHashes,
        List<DungeonRoomFingerprint> fingerprints,
        List<DungeonWaypoint> waypoints) {

    public DungeonRoomDefinition {
        id = normalizeId(id);
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        type = type == null ? DungeonRoomType.ROOM : type;
        shape = shape == null ? DungeonRoomShape.UNKNOWN : shape;
        coreHashes = coreHashes == null ? List.of() : List.copyOf(coreHashes);
        fingerprints = fingerprints == null ? List.of() : List.copyOf(fingerprints);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    public DungeonRoomDefinition(String id, String displayName, DungeonRoomType type,
                                 DungeonRoomShape shape,
                                 List<DungeonRoomFingerprint> fingerprints,
                                 List<DungeonWaypoint> waypoints) {
        this(id, displayName, type, shape, List.of(), fingerprints, waypoints);
    }

    public DungeonRoomDefinition withDisplayName(String name) {
        return new DungeonRoomDefinition(id, name, type, shape, coreHashes, fingerprints, waypoints);
    }

    public DungeonRoomDefinition withCoreHashes(List<Integer> next) {
        return new DungeonRoomDefinition(id, displayName, type, shape, next, fingerprints, waypoints);
    }

    public DungeonRoomDefinition withFingerprints(List<DungeonRoomFingerprint> next) {
        return new DungeonRoomDefinition(id, displayName, type, shape, coreHashes, next, waypoints);
    }

    public DungeonRoomDefinition withWaypoints(List<DungeonWaypoint> next) {
        return new DungeonRoomDefinition(id, displayName, type, shape, coreHashes, fingerprints, next);
    }

    public boolean hasCoreHashes() {
        return !coreHashes.isEmpty();
    }

    public boolean hasFingerprints() {
        return !fingerprints.isEmpty();
    }

    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id;
    }
}
