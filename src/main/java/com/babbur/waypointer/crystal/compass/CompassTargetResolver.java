package com.babbur.waypointer.crystal.compass;

import com.babbur.waypointer.crystal.CrystalHollowsGeometry;
import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Resolves a triangulated point against the server's Wishing Compass target rules. */
public final class CompassTargetResolver {

    private CompassTargetResolver() {}

    public static EnumSet<WishingCompassTarget> candidates(
            Map<Crystal, CrystalState> crystals, boolean hasJungleKey, boolean hasKingsScent) {
        EnumSet<WishingCompassTarget> result = EnumSet.of(WishingCompassTarget.CRYSTAL_NUCLEUS);
        if (needsCrystal(crystals, Crystal.JADE)) result.add(WishingCompassTarget.MINES_OF_DIVAN);
        if (needsCrystal(crystals, Crystal.AMBER)) {
            result.add(hasKingsScent
                    ? WishingCompassTarget.GOBLIN_QUEEN
                    : WishingCompassTarget.GOBLIN_KING);
        }
        if (needsCrystal(crystals, Crystal.TOPAZ)) result.add(WishingCompassTarget.BAL);
        if (needsCrystal(crystals, Crystal.AMETHYST)) {
            result.add(hasJungleKey
                    ? WishingCompassTarget.JUNGLE_TEMPLE
                    : WishingCompassTarget.ODAWA);
        }
        if (needsCrystal(crystals, Crystal.SAPPHIRE)) result.add(WishingCompassTarget.PRECURSOR_CITY);
        return result;
    }

    public static EnumSet<WishingCompassTarget> resolve(
            Vec3d solution,
            CrystalHollowsZone usedZone,
            Map<Crystal, CrystalState> crystals,
            Set<WishingCompassTarget> candidates) {
        if (CrystalHollowsGeometry.insideNucleus(solution.x(), solution.y(), solution.z())) {
            return EnumSet.of(WishingCompassTarget.CRYSTAL_NUCLEUS);
        }
        EnumSet<WishingCompassTarget> result = candidates.isEmpty()
                ? EnumSet.noneOf(WishingCompassTarget.class)
                : EnumSet.copyOf(candidates);
        result.remove(WishingCompassTarget.CRYSTAL_NUCLEUS);
        result.removeIf(target -> impossible(target, solution));
        if (result.contains(WishingCompassTarget.JUNGLE_TEMPLE)
                && result.contains(WishingCompassTarget.BAL)
                && usedZone == CrystalHollowsZone.JUNGLE
                && needsCrystal(crystals, Crystal.AMETHYST)) {
            result.remove(WishingCompassTarget.BAL);
        }
        return result;
    }

    private static boolean needsCrystal(Map<Crystal, CrystalState> crystals, Crystal crystal) {
        CrystalState state = crystals == null ? null : crystals.get(crystal);
        return state == null || state == CrystalState.MISSING;
    }

    private static boolean impossible(WishingCompassTarget target, Vec3d point) {
        double x = point.x();
        double y = point.y();
        double z = point.z();
        return switch (target) {
            case BAL -> y > 75;
            case GOBLIN_KING -> y < 82 || y > 168 || x > 572 || z < 456;
            case GOBLIN_QUEEN -> y < 125 || y > 140 || x > 621 || z < 404;
            case JUNGLE_TEMPLE -> y < 72 || y > 81 || x > 621 || z > 621;
            case ODAWA -> y < 73 || y > 155 || x > 566 || z > 567;
            case PRECURSOR_CITY -> y < 121 || y > 130 || x < 405 || z < 405;
            case MINES_OF_DIVAN -> y < 97 || y > 102 || x < 404 || z > 621;
            case CRYSTAL_NUCLEUS -> true;
        };
    }
}
