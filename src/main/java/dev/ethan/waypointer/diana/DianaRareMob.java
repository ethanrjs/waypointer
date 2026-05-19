package dev.ethan.waypointer.diana;

import java.util.Locale;

public enum DianaRareMob {
    MINOS_INQUISITOR("Minos Inquisitor", true, "inq", "inquisitor", "minos inquisitor"),
    MINOS_CHAMPION("Minos Champion", false, "champion", "minos champion"),
    KING_MINOS("King Minos", false, "king", "king minos"),
    GAIA_CONSTRUCT("Gaia Construct", false, "gaia construct"),
    MINOTAUR("Minotaur", false, "minotaur"),
    MINOS_HUNTER("Minos Hunter", false, "minos hunter"),
    SIAMESE_LYNX("Siamese Lynx", false, "siamese lynx", "siamese lynxes"),
    MANTICORE("Manticore", false, "manticore"),
    SPHINX("Sphinx", false, "sphinx");

    private final String label;
    private final boolean sharedByDefault;
    private final String[] needles;

    DianaRareMob(String label, boolean sharedByDefault, String... needles) {
        this.label = label;
        this.sharedByDefault = sharedByDefault;
        this.needles = needles;
    }

    public String label() {
        return label;
    }

    public boolean sharedByDefault() {
        return sharedByDefault;
    }

    public boolean matches(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (containsToken(lower, needle)) return true;
        }
        return false;
    }

    private static boolean containsToken(String text, String needle) {
        int index = text.indexOf(needle);
        while (index >= 0) {
            int before = index - 1;
            int after = index + needle.length();
            boolean leftBoundary = before < 0 || !isNameChar(text.charAt(before));
            boolean rightBoundary = after >= text.length() || !isNameChar(text.charAt(after));
            if (leftBoundary && rightBoundary) return true;
            index = text.indexOf(needle, index + 1);
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static DianaRareMob fromMobName(String mobName) {
        for (DianaRareMob mob : values()) {
            if (mob.matches(mobName)) return mob;
        }
        return null;
    }
}
