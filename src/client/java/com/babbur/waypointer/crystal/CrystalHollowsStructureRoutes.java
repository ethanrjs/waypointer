package com.babbur.waypointer.crystal;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

/** Resolves the live pivot that structure-relative routes are placed against. */
public final class CrystalHollowsStructureRoutes {

    private static volatile Function<CrystalHollowsStructure, CrystalHollowsPosition> pivotResolver =
            CrystalHollowsStructureRoutes::livePivot;

    private CrystalHollowsStructureRoutes() {}

    /** A saved, regular route whose zone is a Crystal Hollows structure. */
    public static boolean isStructureRoute(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly()
                && group.routeKind() == WaypointGroup.RouteKind.REGULAR
                && CrystalHollowsStructureZones.isStructureZone(group.zoneId());
    }

    public static CrystalHollowsStructure structureFor(WaypointGroup group) {
        return isStructureRoute(group) ? CrystalHollowsStructureZones.structureFor(group.zoneId()) : null;
    }

    /** The pivot for this route in the current lobby, or {@code null} while it is not located. */
    public static CrystalHollowsPosition pivotFor(WaypointGroup group) {
        CrystalHollowsStructure structure = structureFor(group);
        return structure == null ? null : pivotResolver.apply(structure);
    }

    static CrystalHollowsPosition livePivot(CrystalHollowsStructure structure) {
        CrystalHollowsTracker tracker = WaypointerClient.crystalHollowsTracker();
        if (tracker == null || !tracker.active() || tracker.lobby() == null) return null;
        return CrystalHollowsStructureZones.pivot(tracker.lobby().sightings(), structure, playerPosition());
    }

    private static CrystalHollowsPosition playerPosition() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) return null;
            return new CrystalHollowsPosition((int) Math.floor(client.player.getX()),
                    (int) Math.floor(client.player.getY()), (int) Math.floor(client.player.getZ()));
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Tests replace the live lobby lookup; {@code null} restores it. */
    static void overridePivotResolver(Function<CrystalHollowsStructure, CrystalHollowsPosition> resolver) {
        pivotResolver = resolver == null ? CrystalHollowsStructureRoutes::livePivot : resolver;
    }

    public static Component unavailableMessage() {
        return Component.translatableWithFallback("waypointer.crystal.edit.requires_structure",
                "Locate this Crystal Hollows structure to use world positions. You can still add and edit NPC-relative coordinates in the route editor.");
    }
}
