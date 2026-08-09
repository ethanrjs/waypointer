package com.babbur.waypointer.dungeon;

/** Coarse room type read from the dungeon map color. */
public enum DungeonRoomType {
    ENTRANCE(30),
    ROOM(63),
    PUZZLE(66),
    TRAP(62),
    MINIBOSS(74),
    FAIRY(82),
    BLOOD(18),
    UNKNOWN(85);

    public final byte packedColor;

    DungeonRoomType(int packedColor) {
        this.packedColor = (byte) packedColor;
    }

    public static DungeonRoomType fromMapColor(byte color) {
        for (DungeonRoomType t : values()) if (t.packedColor == color) return t;
        return null;
    }
}
