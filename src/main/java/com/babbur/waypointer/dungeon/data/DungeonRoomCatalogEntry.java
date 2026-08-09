package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable room identity metadata used by Catacombs room detection. */
public record DungeonRoomCatalogEntry(
        String id,
        String displayName,
        DungeonRoomType type,
        DungeonRoomShape shape,
        List<Integer> coreHashes,
        List<DungeonRoomFingerprint> fingerprints,
        int secretCount,
        int cryptCount,
        int trappedChestCount) {

    public static final int UNKNOWN_COUNT = -1;

    public DungeonRoomCatalogEntry {
        id = normalizeId(id);
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        type = type == null ? DungeonRoomType.ROOM : type;
        shape = shape == null ? DungeonRoomShape.UNKNOWN : shape;
        coreHashes = coreHashes == null ? List.of() : List.copyOf(coreHashes);
        fingerprints = fingerprints == null ? List.of() : List.copyOf(fingerprints);
        secretCount = normalizeCount(secretCount);
        cryptCount = normalizeCount(cryptCount);
        trappedChestCount = normalizeCount(trappedChestCount);
    }

    public boolean hasCoreHashes() { return !coreHashes.isEmpty(); }
    public boolean hasFingerprints() { return !fingerprints.isEmpty(); }
    public boolean hasSecretCount() { return secretCount >= 0; }
    public boolean hasCryptCount() { return cryptCount >= 0; }
    public boolean hasTrappedChestCount() { return trappedChestCount >= 0; }

    public static String normalizeId(String id) {
        if (id == null) return "";
        return id.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static int normalizeCount(int count) {
        return count < 0 ? UNKNOWN_COUNT : count;
    }
}
