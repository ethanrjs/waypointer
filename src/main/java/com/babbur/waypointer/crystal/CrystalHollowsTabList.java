package com.babbur.waypointer.crystal;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minecraft-free parsing of Crystal Hollows player-list widgets. */
public final class CrystalHollowsTabList {

    private static final Pattern CRYSTAL_LINE = Pattern.compile(
            "(?i)\\b(Amber|Amethyst|Jade|Sapphire|Topaz):?\\s*[✖✔]?\\s*(Not Found|Not Placed|Placed)\\b");

    private CrystalHollowsTabList() {}

    public static Map<Crystal, CrystalState> parseCrystalStates(List<String> lines) {
        EnumMap<Crystal, CrystalState> states = new EnumMap<>(Crystal.class);
        if (lines == null) return states;
        for (String line : lines) {
            if (line == null) continue;
            Matcher matcher = CRYSTAL_LINE.matcher(CrystalHollowsSidebar.stripFormatting(line));
            if (!matcher.find()) continue;
            Crystal crystal = Crystal.valueOf(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
            CrystalState state = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
                case "placed" -> CrystalState.PLACED;
                case "not placed" -> CrystalState.COLLECTED;
                default -> CrystalState.MISSING;
            };
            states.put(crystal, state);
        }
        return states;
    }

    public static boolean hasKingsScent(List<String> lines) {
        if (lines == null) return false;
        for (String line : lines) {
            if (line != null && CrystalHollowsSidebar.stripFormatting(line).contains("King's Scent")) {
                return true;
            }
        }
        return false;
    }

    /** Tab widget state is authoritative while present; chat history is the fallback. */
    public static Map<Crystal, CrystalState> preferredStates(
            Map<Crystal, CrystalState> tabStates,
            Map<Crystal, CrystalState> chatStates) {
        if (tabStates != null && !tabStates.isEmpty()) return Map.copyOf(tabStates);
        return chatStates == null || chatStates.isEmpty() ? Map.of() : Map.copyOf(chatStates);
    }
}
