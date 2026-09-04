package com.babbur.waypointer.crystal.compass;

import com.babbur.waypointer.crystal.CrystalHollowsStructure;

/** Server targets that can be selected by the Wishing Compass. */
public enum WishingCompassTarget {
    GOBLIN_QUEEN,
    GOBLIN_KING,
    BAL,
    JUNGLE_TEMPLE,
    ODAWA,
    PRECURSOR_CITY,
    MINES_OF_DIVAN,
    CRYSTAL_NUCLEUS;

    public CrystalHollowsStructure structure() {
        return switch (this) {
            case GOBLIN_QUEEN -> CrystalHollowsStructure.GOBLIN_QUEENS_DEN;
            case GOBLIN_KING -> CrystalHollowsStructure.KING_YOLKAR;
            case BAL -> CrystalHollowsStructure.KHAZAD_DUM;
            case JUNGLE_TEMPLE -> CrystalHollowsStructure.JUNGLE_TEMPLE;
            case ODAWA -> CrystalHollowsStructure.ODAWA;
            case PRECURSOR_CITY -> CrystalHollowsStructure.LOST_PRECURSOR_CITY;
            case MINES_OF_DIVAN -> CrystalHollowsStructure.MINES_OF_DIVAN;
            case CRYSTAL_NUCLEUS -> CrystalHollowsStructure.CRYSTAL_NUCLEUS;
        };
    }
}
