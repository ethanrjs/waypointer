package com.babbur.waypointer.dungeon;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** A room footprint made from one or more 32x32-block map segments. */
public enum DungeonRoomShape {
    ONE_BY_ONE,
    ONE_BY_TWO,
    ONE_BY_THREE,
    ONE_BY_FOUR,
    TWO_BY_TWO,
    L_SHAPE,
    UNKNOWN;

    public static DungeonRoomShape classify(int segmentCount, int spanX, int spanZ) {
        if (segmentCount <= 1) return ONE_BY_ONE;
        if (segmentCount == 2) return ONE_BY_TWO;
        if (segmentCount == 3) {
            return (spanX == 1 || spanZ == 1) ? ONE_BY_THREE : L_SHAPE;
        }
        if (segmentCount == 4) {
            if (spanX == 1 || spanZ == 1) return ONE_BY_FOUR;
            if (spanX == 2 && spanZ == 2) return TWO_BY_TWO;
            return L_SHAPE;
        }
        return UNKNOWN;
    }

    public static DungeonRoomShape classifySegments(Collection<Long> segments) {
        if (segments == null) return classify(0, 0, 0);
        Set<Long> distinctSegments = new HashSet<>();
        Set<Integer> distinctX = new HashSet<>();
        Set<Integer> distinctZ = new HashSet<>();
        for (Long packed : segments) {
            if (packed == null || !distinctSegments.add(packed)) continue;
            distinctX.add(DungeonRoom.segmentX(packed));
            distinctZ.add(DungeonRoom.segmentZ(packed));
        }
        return classify(distinctSegments.size(), distinctX.size(), distinctZ.size());
    }
}
