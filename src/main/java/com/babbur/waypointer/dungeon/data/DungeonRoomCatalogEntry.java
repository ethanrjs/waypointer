package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        if (isNormalized(id)) return id;
        String lower = id.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean dash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                if (dash && !out.isEmpty()) out.append('-');
                dash = false;
                out.append(c);
            } else {
                dash = true;
            }
        }
        return out.toString();
    }

    private static boolean isNormalized(String id) {
        if (id.isEmpty() || id.charAt(0) == '-' || id.charAt(id.length() - 1) == '-') return id.isEmpty();
        char previous = 0;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean word = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
            if (!word && (c != '-' || previous == '-')) return false;
            previous = c;
        }
        return true;
    }

    private static int normalizeCount(int count) {
        return count < 0 ? UNKNOWN_COUNT : count;
    }
}
