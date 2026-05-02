package dev.ethan.waypointer.dungeon.data;

import java.util.Locale;
import java.util.Objects;

/**
 * A room-local block sample used to distinguish same-shape dungeon rooms.
 *
 * <p>Coordinates are authored in the same canonical room frame as
 * {@link dev.ethan.waypointer.dungeon.DungeonWaypoint}. At runtime they are
 * rotated through the current room direction and compared against the world.
 */
public record DungeonRoomFingerprint(int x, int y, int z, String blockId) {

    public DungeonRoomFingerprint {
        Objects.requireNonNull(blockId, "blockId");
        blockId = normalizeBlockId(blockId);
    }

    public static String normalizeBlockId(String id) {
        if (id == null || id.isBlank()) return "minecraft:air";
        String norm = id.trim().toLowerCase(Locale.ROOT);
        return norm.indexOf(':') >= 0 ? norm : "minecraft:" + norm;
    }
}
