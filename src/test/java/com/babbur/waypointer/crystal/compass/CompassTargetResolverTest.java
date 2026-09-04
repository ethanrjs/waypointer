package com.babbur.waypointer.crystal.compass;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompassTargetResolverTest {

    @Test
    void candidateSetUsesCrystalStateAndInventoryEffects() {
        EnumMap<Crystal, CrystalState> crystals = collectedCrystals();
        crystals.put(Crystal.AMBER, CrystalState.MISSING);
        crystals.put(Crystal.AMETHYST, CrystalState.MISSING);
        assertEquals(EnumSet.of(WishingCompassTarget.CRYSTAL_NUCLEUS,
                        WishingCompassTarget.GOBLIN_QUEEN, WishingCompassTarget.JUNGLE_TEMPLE),
                CompassTargetResolver.candidates(crystals, true, true));
    }

    @Test
    void resolvesAllCapturedCoordinateFixtures() {
        assertOnly(new Vec3d(735, 98, 451), WishingCompassTarget.MINES_OF_DIVAN);
        assertOnly(new Vec3d(377, 87, 550), WishingCompassTarget.GOBLIN_KING);
        assertOnly(new Vec3d(604, 124, 681), WishingCompassTarget.PRECURSOR_CITY);
        assertOnly(new Vec3d(343, 72, 424), WishingCompassTarget.JUNGLE_TEMPLE);
        assertOnly(new Vec3d(737, 56, 444), WishingCompassTarget.BAL);
        assertOnly(new Vec3d(322, 139, 769), WishingCompassTarget.GOBLIN_QUEEN);
        assertOnly(new Vec3d(349, 110, 390), WishingCompassTarget.ODAWA);
    }

    @Test
    void jungleRuleDropsBalWhenTempleAndBalBothSurvive() {
        EnumMap<Crystal, CrystalState> crystals = collectedCrystals();
        crystals.put(Crystal.AMETHYST, CrystalState.MISSING);
        crystals.put(Crystal.TOPAZ, CrystalState.MISSING);
        assertEquals(EnumSet.of(WishingCompassTarget.JUNGLE_TEMPLE),
                CompassTargetResolver.resolve(new Vec3d(343, 72, 424),
                        CrystalHollowsZone.JUNGLE, crystals,
                        EnumSet.of(WishingCompassTarget.JUNGLE_TEMPLE, WishingCompassTarget.BAL)));
    }

    @Test
    void nucleusAlwaysWins() {
        assertEquals(EnumSet.of(WishingCompassTarget.CRYSTAL_NUCLEUS),
                CompassTargetResolver.resolve(new Vec3d(513, 106, 551),
                        CrystalHollowsZone.CRYSTAL_NUCLEUS, Map.of(),
                        EnumSet.allOf(WishingCompassTarget.class)));
    }

    private static void assertOnly(Vec3d point, WishingCompassTarget target) {
        assertEquals(EnumSet.of(target), CompassTargetResolver.resolve(point,
                CrystalHollowsZone.JUNGLE, Map.of(), EnumSet.of(target)));
    }

    private static EnumMap<Crystal, CrystalState> collectedCrystals() {
        EnumMap<Crystal, CrystalState> crystals = new EnumMap<>(Crystal.class);
        for (Crystal crystal : Crystal.values()) crystals.put(crystal, CrystalState.COLLECTED);
        return crystals;
    }
}
