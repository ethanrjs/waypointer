package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.Waypoint;

/**
 * Where a structure-relative route sits in the world: the world block its stored origin maps to,
 * and how many quarter turns (clockwise seen from above) the structure is rotated. Points turn about
 * the centre of the pivot block, so block centres stay block centres.
 */
public record StructurePlacement(CrystalHollowsPosition pivot, int quarterTurns) {

    private static final int S = Waypoint.PRECISE_SCALE;

    public StructurePlacement {
        quarterTurns = Math.floorMod(quarterTurns, 4);
    }

    public static StructurePlacement at(CrystalHollowsPosition pivot) {
        return pivot == null ? null : new StructurePlacement(pivot, 0);
    }

    public Waypoint toWorld(Waypoint local) {
        int[] world = toWorldPrecise(local.preciseX(), local.preciseY(), local.preciseZ());
        return local.withPreciseSixteenths(world[0], world[1], world[2]);
    }

    public Waypoint toLocal(Waypoint world) {
        int[] local = toLocalPrecise(world.preciseX(), world.preciseY(), world.preciseZ());
        return world.withPreciseSixteenths(local[0], local[1], local[2]);
    }

    public int[] toWorldPrecise(int x, int y, int z) {
        int[] turned = turnPoint(x, z, quarterTurns);
        return new int[] { turned[0] + pivot.x() * S, y + pivot.y() * S, turned[1] + pivot.z() * S };
    }

    public int[] toLocalPrecise(int x, int y, int z) {
        int[] turned = turnPoint(x - pivot.x() * S, z - pivot.z() * S, 4 - quarterTurns);
        return new int[] { turned[0], y - pivot.y() * S, turned[1] };
    }

    /** World block for a block offset from the pivot. */
    public CrystalHollowsPosition blockToWorld(int dx, int dy, int dz) {
        int[] turned = turnBlock(dx, dz, quarterTurns);
        return new CrystalHollowsPosition(pivot.x() + turned[0], pivot.y() + dy, pivot.z() + turned[1]);
    }

    static int[] turnPoint(int x, int z, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new int[] { S - z, x };
            case 2 -> new int[] { S - x, S - z };
            case 3 -> new int[] { z, S - x };
            default -> new int[] { x, z };
        };
    }

    static int[] turnBlock(int dx, int dz, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new int[] { -dz, dx };
            case 2 -> new int[] { -dx, -dz };
            case 3 -> new int[] { dz, -dx };
            default -> new int[] { dx, dz };
        };
    }
}
