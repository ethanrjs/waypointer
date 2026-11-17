package com.babbur.waypointer.crystal;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

public final class CrystalHollowsStructureRoutes {

    private static volatile Function<CrystalHollowsStructure, CrystalHollowsPosition> pivotResolver =
            CrystalHollowsStructureRoutes::livePivot;

    private CrystalHollowsStructureRoutes() {}

    public static boolean isStructureRoute(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly()
                && group.routeKind() == WaypointGroup.RouteKind.REGULAR
                && CrystalHollowsStructureZones.isStructureZone(group.zoneId());
    }

    public static CrystalHollowsStructure structureFor(WaypointGroup group) {
        return isStructureRoute(group) ? CrystalHollowsStructureZones.structureFor(group.zoneId()) : null;
    }

    public static CrystalHollowsPosition pivotFor(WaypointGroup group) {
        StructurePlacement placement = placementFor(group);
        return placement == null ? null : placement.pivot();
    }

    /** Where the route's structure is in this lobby, for drawing it; null while unknown. */
    public static StructurePlacement placementFor(WaypointGroup group) {
        CrystalHollowsStructure structure = structureFor(group);
        if (structure == null) return null;
        if (structure == CrystalHollowsStructure.FAIRY_GROTTO) return FairyGrottoRoutes.placementFor(group);
        return StructurePlacement.at(pivotResolver.apply(structure));
    }

    /** Like {@link #placementFor}, but a Fairy Grotto route may record or find its grotto now. */
    public static StructurePlacement placementForEdit(WaypointGroup group) {
        CrystalHollowsStructure structure = structureFor(group);
        if (structure == CrystalHollowsStructure.FAIRY_GROTTO) return FairyGrottoRoutes.placementForEdit(group);
        return placementFor(group);
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

    static void overridePivotResolver(Function<CrystalHollowsStructure, CrystalHollowsPosition> resolver) {
        pivotResolver = resolver == null ? CrystalHollowsStructureRoutes::livePivot : resolver;
    }

    public static Component unavailableMessage(WaypointGroup group) {
        if (structureFor(group) != CrystalHollowsStructure.FAIRY_GROTTO) return unavailableMessage();
        if (FairyGrottoRoutes.missingLayout(group)) {
            return Component.translatableWithFallback("waypointer.crystal.edit.grotto_layout_missing",
                    "This route has no grotto layout, so it can't be placed. A Fairy Grotto route records its grotto when its first waypoint is added inside one.");
        }
        return Component.translatableWithFallback("waypointer.crystal.edit.requires_grotto",
                "Stand inside this route's Fairy Grotto to add or move waypoints.");
    }

    public static Component unavailableMessage() {
        return Component.translatableWithFallback("waypointer.crystal.edit.requires_structure",
                "Locate this Crystal Hollows structure to use world positions. You can still add and edit NPC-relative coordinates in the route editor.");
    }
}
