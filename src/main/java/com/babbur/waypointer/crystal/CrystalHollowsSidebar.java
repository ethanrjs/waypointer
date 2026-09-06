package com.babbur.waypointer.crystal;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing helpers for the colour-stripped Crystal Hollows sidebar. */
public final class CrystalHollowsSidebar {

    private static final Pattern AREA = Pattern.compile("(?m)[⏣\uE067][\\t ]*([^\\r\\n]+)");
    private static final Pattern COORDINATE_SUFFIX = Pattern.compile(
            "\\s*(?:\\(|\\[)?-?\\d+\\s*[, ]\\s*-?\\d+\\s*[, ]\\s*-?\\d+(?:\\)|\\])?\\s*$");
    private static final Pattern DATE_SERVER = Pattern.compile(
            "(?m)^\\s*\\d{2}/\\d{2}/\\d{2}\\s+(m(?:ini)?\\d{1,4}[A-Za-z]{0,3})\\b");
    private static final Pattern CLOSING_SERVER = Pattern.compile(
            "(?m)Server closing:\\s*\\d+:\\d+\\s+(\\S+)");
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern COMBINING_MARK = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CrystalHollowsSidebar() {}

    public static String areaName(String blob) {
        if (blob == null) return null;
        Matcher matcher = AREA.matcher(stripFormatting(blob));
        if (!matcher.find()) return null;
        return COORDINATE_SUFFIX.matcher(matcher.group(1)).replaceFirst("").trim();
    }

    public static CrystalHollowsStructure structureForArea(String area) {
        String normalized = normalizeName(area);
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            if (structure.sidebarName() != null
                    && normalizeName(structure.sidebarName()).equals(normalized)) {
                return structure;
            }
        }
        return "khazad-dm".equals(normalized) ? CrystalHollowsStructure.KHAZAD_DUM : null;
    }

    public static CrystalHollowsZone zoneForArea(String area) {
        return switch (normalizeName(area)) {
            case "crystal nucleus" -> CrystalHollowsZone.CRYSTAL_NUCLEUS;
            case "jungle" -> CrystalHollowsZone.JUNGLE;
            case "mithril deposits" -> CrystalHollowsZone.MITHRIL_DEPOSITS;
            case "goblin holdout" -> CrystalHollowsZone.GOBLIN_HOLDOUT;
            case "precursor remnants" -> CrystalHollowsZone.PRECURSOR_REMNANTS;
            case "magma fields" -> CrystalHollowsZone.MAGMA_FIELDS;
            default -> null;
        };
    }

    public static String serverId(String blob) {
        if (blob == null) return null;
        String stripped = stripFormatting(blob);
        Matcher date = DATE_SERVER.matcher(stripped);
        if (date.find()) return date.group(1);
        Matcher closing = CLOSING_SERVER.matcher(stripped);
        return closing.find() ? closing.group(1) : null;
    }

    public static String normalizeName(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(stripFormatting(value), Normalizer.Form.NFD);
        String unmarked = COMBINING_MARK.matcher(decomposed).replaceAll("");
        return WHITESPACE.matcher(unmarked.toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
    }

    static String stripFormatting(String value) {
        return FORMATTING.matcher(value).replaceAll("");
    }
}
