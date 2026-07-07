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
 *
 * <p>The count fields describe what Hypixel put in the room ({@code secrets}
 * as tracked by the tab-list counter, crypts, trapped chests);
 * {@link #UNKNOWN_COUNT} means the catalog doesn't know. They come from the
 * bundled Odin data and power "found X of Y" style displays -- they are not
 * required to match the number of authored waypoints.
 */
public record DungeonRoomDefinition(
        String id,
        String displayName,
        DungeonRoomType type,
        DungeonRoomShape shape,
        List<Integer> coreHashes,
        List<DungeonRoomFingerprint> fingerprints,
        List<DungeonWaypoint> waypoints,
        int secretCount,
        int cryptCount,
        int trappedChestCount) {

    public static final int UNKNOWN_COUNT = -1;

    public DungeonRoomDefinition {
        id = normalizeId(id);
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        type = type == null ? DungeonRoomType.ROOM : type;
        shape = shape == null ? DungeonRoomShape.UNKNOWN : shape;
        coreHashes = coreHashes == null ? List.of() : List.copyOf(coreHashes);
        fingerprints = fingerprints == null ? List.of() : List.copyOf(fingerprints);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        secretCount = normalizeCount(secretCount);
        cryptCount = normalizeCount(cryptCount);
        trappedChestCount = normalizeCount(trappedChestCount);
    }

    public DungeonRoomDefinition(String id, String displayName, DungeonRoomType type,
                                 DungeonRoomShape shape,
                                 List<Integer> coreHashes,
                                 List<DungeonRoomFingerprint> fingerprints,
                                 List<DungeonWaypoint> waypoints) {
        this(id, displayName, type, shape, coreHashes, fingerprints, waypoints,
                UNKNOWN_COUNT, UNKNOWN_COUNT, UNKNOWN_COUNT);
    }

    public DungeonRoomDefinition(String id, String displayName, DungeonRoomType type,
                                 DungeonRoomShape shape,
                                 List<DungeonRoomFingerprint> fingerprints,
                                 List<DungeonWaypoint> waypoints) {
        this(id, displayName, type, shape, List.of(), fingerprints, waypoints);
    }

    public DungeonRoomDefinition withDisplayName(String name) {
        return new DungeonRoomDefinition(id, name, type, shape, coreHashes, fingerprints,
                waypoints, secretCount, cryptCount, trappedChestCount);
    }

    public DungeonRoomDefinition withCoreHashes(List<Integer> next) {
        return new DungeonRoomDefinition(id, displayName, type, shape, next, fingerprints,
                waypoints, secretCount, cryptCount, trappedChestCount);
    }

    public DungeonRoomDefinition withFingerprints(List<DungeonRoomFingerprint> next) {
        return new DungeonRoomDefinition(id, displayName, type, shape, coreHashes, next,
                waypoints, secretCount, cryptCount, trappedChestCount);
    }

    public DungeonRoomDefinition withWaypoints(List<DungeonWaypoint> next) {
        return new DungeonRoomDefinition(id, displayName, type, shape, coreHashes, fingerprints,
                next, secretCount, cryptCount, trappedChestCount);
    }

    public DungeonRoomDefinition withCounts(int secrets, int crypts, int trappedChests) {
        return new DungeonRoomDefinition(id, displayName, type, shape, coreHashes, fingerprints,
                waypoints, secrets, crypts, trappedChests);
    }

    public boolean hasCoreHashes() {
        return !coreHashes.isEmpty();
    }

    public boolean hasFingerprints() {
        return !fingerprints.isEmpty();
    }

    public boolean hasSecretCount() {
        return secretCount != UNKNOWN_COUNT;
    }

    public boolean hasCryptCount() {
        return cryptCount != UNKNOWN_COUNT;
    }

    public boolean hasTrappedChestCount() {
        return trappedChestCount != UNKNOWN_COUNT;
    }

    private static int normalizeCount(int count) {
        return count < 0 ? UNKNOWN_COUNT : count;
    }

    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id;
    }
}
