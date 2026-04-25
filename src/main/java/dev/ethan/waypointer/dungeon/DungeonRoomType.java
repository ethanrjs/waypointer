package dev.ethan.waypointer.dungeon;

/**
 * Coarse classification for a dungeon room derived from its color on the
 * Hypixel-supplied dungeon map item.
 *
 * <p>Not the same as {@link DungeonRoomShape}: a room's TYPE is "puzzle" or
 * "regular room", whereas its SHAPE is "1x2" or "L-shape". Type comes for
 * free from a single map-color lookup; shape requires flood-filling
 * same-colored segments.
 *
 * <p>Color constants taken from Skyblocker's
 * {@code DungeonMapUtils.getRoomType} (LGPL-3.0); values originate in the
 * vanilla {@code MapColor} table and are stable across MC versions.
 */
public enum DungeonRoomType {
    ENTRANCE(30),
    ROOM(63),
    PUZZLE(66),
    TRAP(62),
    MINIBOSS(74),
    FAIRY(82),
    BLOOD(18),
    UNKNOWN(85);

    /** Packed map-color byte, as stored in {@code MapItemSavedData.colors}. */
    public final byte packedColor;

    DungeonRoomType(int packedColor) {
        this.packedColor = (byte) packedColor;
    }

    public static DungeonRoomType fromMapColor(byte color) {
        for (DungeonRoomType t : values()) if (t.packedColor == color) return t;
        return null;
    }
}
