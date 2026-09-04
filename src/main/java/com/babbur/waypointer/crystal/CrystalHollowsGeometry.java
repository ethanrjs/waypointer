package com.babbur.waypointer.crystal;

import java.util.List;

/** Authoritative Crystal Hollows bounds and fixed locations. */
public final class CrystalHollowsGeometry {

    public static final CrystalHollowsPosition NUCLEUS_TARGET =
            new CrystalHollowsPosition(513, 106, 551);
    public static final double NUCLEUS_CENTRE_X = 513.5;
    public static final double NUCLEUS_CENTRE_Y = 107.0;
    public static final double NUCLEUS_CENTRE_Z = 513.5;

    public record Entrance(String id, String displayName, CrystalHollowsPosition position) {}

    public static final List<Entrance> NUCLEUS_ENTRANCES = List.of(
            new Entrance("jungle", "Jungle", new CrystalHollowsPosition(461, 121, 461)),
            new Entrance("mithril_deposits", "Mithril Deposits", new CrystalHollowsPosition(565, 121, 462)),
            new Entrance("precursor_remnants", "Precursor Remnants", new CrystalHollowsPosition(566, 122, 564)),
            new Entrance("goblin_holdout", "Goblin Holdout", new CrystalHollowsPosition(462, 122, 564)),
            new Entrance("magma_ne", "Magma Fields NE", new CrystalHollowsPosition(557, 65, 474)),
            new Entrance("magma_se", "Magma Fields SE", new CrystalHollowsPosition(560, 57, 554)),
            new Entrance("magma_sw", "Magma Fields SW", new CrystalHollowsPosition(469, 63, 541)),
            new Entrance("magma_nw", "Magma Fields NW", new CrystalHollowsPosition(471, 61, 499)));

    private static final Box HOLLOWS = new Box(201, 30, 201, 824, 189, 824);
    private static final Box NUCLEUS = new Box(462, 63, 461, 564, 181, 565);
    private static final Box JUNGLE = new Box(201, 63, 201, 513, 189, 513);
    private static final Box MITHRIL = new Box(512, 63, 201, 824, 189, 513);
    private static final Box GOBLIN = new Box(201, 63, 512, 513, 189, 824);
    private static final Box PRECURSOR = new Box(512, 63, 512, 824, 189, 824);
    private static final Box MAGMA = new Box(201, 30, 201, 824, 64, 824);

    private CrystalHollowsGeometry() {}

    public static boolean insideHollows(double x, double y, double z) {
        return HOLLOWS.contains(x, y, z);
    }

    public static boolean insideNucleus(double x, double y, double z) {
        return NUCLEUS.contains(x, y, z);
    }

    public static CrystalHollowsZone zoneAt(double x, double y, double z) {
        if (!insideHollows(x, y, z)) return null;
        if (NUCLEUS.contains(x, y, z)) return CrystalHollowsZone.CRYSTAL_NUCLEUS;
        if (JUNGLE.contains(x, y, z)) return CrystalHollowsZone.JUNGLE;
        if (MITHRIL.contains(x, y, z)) return CrystalHollowsZone.MITHRIL_DEPOSITS;
        if (GOBLIN.contains(x, y, z)) return CrystalHollowsZone.GOBLIN_HOLDOUT;
        if (PRECURSOR.contains(x, y, z)) return CrystalHollowsZone.PRECURSOR_REMNANTS;
        if (MAGMA.contains(x, y, z)) return CrystalHollowsZone.MAGMA_FIELDS;
        return null;
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX
                    && y >= minY && y < maxY
                    && z >= minZ && z < maxZ;
        }
    }
}
