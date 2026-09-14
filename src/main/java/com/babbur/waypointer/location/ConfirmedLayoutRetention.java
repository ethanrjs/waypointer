package com.babbur.waypointer.location;

import com.babbur.waypointer.core.Zone;

/**
 * Retains a confirmed Mineshaft layout or Catacombs floor during partial sidebar updates.
 * A different marker replaces it; world changes and location packets clear it before refinement.
 */
public final class ConfirmedLayoutRetention {

    private ConfirmedLayoutRetention() {}

    public static Zone retain(Zone previous, Zone next, String sidebarText) {
        if (previous == null || next == null || previous.equals(next)) return next;
        String previousId = previous.id();
        String nextId = next.id();
        if (Zone.isSpecificMineshaftZone(previousId) && "mineshaft_unknown".equals(nextId)
                && Zone.tryResolveMineshaftTypeFromSidebarBlob(sidebarText) == null) {
            return previous;
        }
        if (isCatacombsFloor(previousId) && isBroadCatacombs(nextId)
                && CatacombsFloorRefiner.tryResolveFromSidebarBlob(sidebarText) == null) {
            return previous;
        }
        return next;
    }

    /** The packet zone, or the sidebar's bare area name, before a floor marker refines it. */
    private static boolean isBroadCatacombs(String id) {
        return "dungeon".equals(id) || "the_catacombs".equals(id);
    }

    static boolean isCatacombsFloor(String id) {
        return id != null && (id.startsWith("dungeon_f") || id.startsWith("dungeon_m"));
    }
}
