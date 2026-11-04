package com.babbur.waypointer.dungeon;

public final class DungeonBossArea {

    private DungeonBossArea() {}

    public static int floorNumber(String zoneId) {
        if (zoneId == null || zoneId.length() != 10) return 0;
        if (!zoneId.startsWith("dungeon_f") && !zoneId.startsWith("dungeon_m")) return 0;
        char digit = zoneId.charAt(9);
        return digit >= '1' && digit <= '7' ? digit - '0' : 0;
    }

    public static boolean contains(int floor, double x, double z) {
        return switch (floor) {
            case 1 -> x > -71 && z > -39;
            case 2, 3, 4 -> x > -39 && z > -39;
            case 5, 6 -> x > -39 && z > -7;
            default -> x > -7 && z > -7;
        };
    }
}
