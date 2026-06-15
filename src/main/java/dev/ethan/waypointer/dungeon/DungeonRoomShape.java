package dev.ethan.waypointer.dungeon;

/**
 * The footprint of a dungeon room, derived from how many 32x32-block segments
 * share its map color and how those segments are arranged in space.
 *
 * <p>Shape is meaningful primarily for {@link DungeonRoomType#ROOM}. Room
 * identity comes from core hashes and authored definitions; shape is retained
 * as geometry metadata and as a safe fallback for generic rooms.
 */
public enum DungeonRoomShape {
    ONE_BY_ONE,
    ONE_BY_TWO,
    ONE_BY_THREE,
    ONE_BY_FOUR,
    TWO_BY_TWO,
    L_SHAPE,
    UNKNOWN;

    /**
     * Classify a set of room segments. Only counts and span dimensions matter --
     * the actual world location of the segments is irrelevant for shape.
     *
     * @param segmentCount number of distinct 32x32 segments in the room
     * @param spanX        number of distinct segment X coordinates
     * @param spanZ        number of distinct segment Z coordinates
     */
    public static DungeonRoomShape classify(int segmentCount, int spanX, int spanZ) {
        if (segmentCount <= 1) return ONE_BY_ONE;
        if (segmentCount == 2) return ONE_BY_TWO;
        if (segmentCount == 3) {
            // 3-in-a-line vs L. A straight line has either spanX==1 or spanZ==1.
            return (spanX == 1 || spanZ == 1) ? ONE_BY_THREE : L_SHAPE;
        }
        if (segmentCount == 4) {
            if (spanX == 1 || spanZ == 1) return ONE_BY_FOUR;
            if (spanX == 2 && spanZ == 2) return TWO_BY_TWO;
            return L_SHAPE;
        }
        return UNKNOWN;
    }
}
