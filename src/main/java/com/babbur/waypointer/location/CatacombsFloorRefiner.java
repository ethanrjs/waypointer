package com.babbur.waypointer.location;

import com.babbur.waypointer.core.Zone;

import java.util.Locale;

final class CatacombsFloorRefiner {

    private static final String CATACOMBS_TOKEN = "CATACOMBS";
    private static final String[] FLOOR_MARKERS = {
            "F1", "F2", "F3", "F4", "F5", "F6", "F7",
            "M1", "M2", "M3", "M4", "M5", "M6", "M7"
    };

    private CatacombsFloorRefiner() {}

    static Zone refine(Zone packetZone, String sidebarText) {
        if (!isCatacombsPacketZone(packetZone)) return packetZone;
        Zone floorZone = tryResolveFromSidebarBlob(sidebarText);
        return floorZone == null ? packetZone : floorZone;
    }

    static Zone tryResolveFromSidebarBlob(String sidebarText) {
        if (sidebarText == null || sidebarText.isBlank()) return null;
        String upper = sidebarText.toUpperCase(Locale.ROOT);
        if (!upper.contains(CATACOMBS_TOKEN)) return null;
        for (String marker : FLOOR_MARKERS) {
            if (upper.contains("(" + marker + ")")) return zoneForMarker(marker);
        }
        return null;
    }

    static boolean shouldPoll(Zone packetZone) {
        return isCatacombsPacketZone(packetZone);
    }

    private static boolean isCatacombsPacketZone(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        return "dungeon".equals(id)
                || id.startsWith("dungeon_f")
                || id.startsWith("dungeon_m");
    }

    private static Zone zoneForMarker(String marker) {
        return Zone.fromId("dungeon_" + marker.toLowerCase(Locale.ROOT));
    }
}
