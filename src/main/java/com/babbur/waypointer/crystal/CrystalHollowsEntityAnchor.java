package com.babbur.waypointer.crystal;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minecraft-free name matching and placement offsets for visible NPC anchors. */
public final class CrystalHollowsEntityAnchor {

    public record Match(CrystalHollowsStructure structure, int offsetX, int offsetY, int offsetZ,
                        boolean divanKeeper) {}

    private static final Pattern KEEPER = Pattern.compile("^Keeper of (Diamond|Lapis|Emerald|Gold)\\b");

    private CrystalHollowsEntityAnchor() {}

    public static Optional<Match> match(String rawName) {
        String name = CrystalHollowsSidebar.stripFormatting(rawName == null ? "" : rawName).trim();
        Matcher keeper = KEEPER.matcher(name);
        if (keeper.find()) {
            return Optional.of(switch (keeper.group(1)) {
                case "Diamond" -> anchor(CrystalHollowsStructure.MINES_OF_DIVAN, 33, 0, 3, true);
                case "Lapis" -> anchor(CrystalHollowsStructure.MINES_OF_DIVAN, -33, 0, -3, true);
                case "Emerald" -> anchor(CrystalHollowsStructure.MINES_OF_DIVAN, -3, 0, 33, true);
                default -> anchor(CrystalHollowsStructure.MINES_OF_DIVAN, 3, 0, -33, true);
            });
        }
        if (name.equals("King Yolkar") || name.endsWith("King Yolkar")) {
            return Optional.of(anchor(CrystalHollowsStructure.KING_YOLKAR));
        }
        if (name.equals("Odawa") || name.startsWith("Odawa")) {
            return Optional.of(anchor(CrystalHollowsStructure.ODAWA));
        }
        if (name.contains("Professor Robot")) {
            return Optional.of(anchor(CrystalHollowsStructure.LOST_PRECURSOR_CITY));
        }
        if (name.contains("Boss Corleone") || name.equals("Team Treasurite")) {
            return Optional.of(anchor(CrystalHollowsStructure.CORLEONE));
        }
        if (name.contains("Key Guardian")) {
            return Optional.of(anchor(CrystalHollowsStructure.KEY_GUARDIAN));
        }
        if (name.contains("Kalhuiki Door Guardian")) {
            return Optional.of(anchor(CrystalHollowsStructure.JUNGLE_TEMPLE));
        }
        if (name.contains("Golden Dragon")) {
            return Optional.of(anchor(CrystalHollowsStructure.DRAGONS_LAIR));
        }
        if (name.equals("Xalx") || name.startsWith("Xalx")) {
            return Optional.of(anchor(CrystalHollowsStructure.XALX));
        }
        if (name.contains("Goblin Queen")) {
            return Optional.of(anchor(CrystalHollowsStructure.GOBLIN_QUEENS_DEN));
        }
        return Optional.empty();
    }

    private static Match anchor(CrystalHollowsStructure structure) {
        return anchor(structure, 0, 0, 0, false);
    }

    private static Match anchor(CrystalHollowsStructure structure, int x, int y, int z,
                                boolean divanKeeper) {
        return new Match(structure, x, y, z, divanKeeper);
    }
}
