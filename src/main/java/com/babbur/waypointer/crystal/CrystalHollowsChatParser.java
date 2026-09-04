package com.babbur.waypointer.crystal;

import com.babbur.waypointer.chat.CoordScanner;
import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses server and player chat signals used by the Crystal Hollows feature. */
public final class CrystalHollowsChatParser {

    public record NpcDialogue(CrystalHollowsStructure structure, String prefix) {}
    public record CrystalUpdate(Crystal crystal, CrystalState state, boolean resetAll) {}
    public record SharedCoordinate(CrystalHollowsStructure structure, int x, int y, int z,
                                   String formatTag) {}
    public record PlayerChat(String sender, String body) {}

    public enum CompassServerMessage {
        USE_CONFIRMED,
        NO_TARGET
    }

    private record Alias(CrystalHollowsStructure structure, String text) {}

    private static final Pattern SBE_PREFIX = Pattern.compile("^\\$(?:DSM|SBE)CHWP:(.*)$", Pattern.DOTALL);
    private static final Pattern SBE_ENTRY = Pattern.compile("^\\s*(.+?)@-(\\d{1,4}),(-?\\d{1,3}),(\\d{1,4})\\s*$");
    private static final Pattern NAME_COORDS = Pattern.compile(
            "(?i)(?:\\[Skyblocker]\\s*)?([^:@|]+?)\\s*:\\s*(-?\\d{1,5})\\s*[, ]\\s*(-?\\d{1,4})\\s*[, ]\\s*(-?\\d{1,5})(?:\\s*$|\\s*\\|)");
    private static final Pattern AT_COORDS = Pattern.compile(
            "(?i)([^:@|]+?)\\s*@\\s*(-?\\d{1,5})\\s*[, ]\\s*(-?\\d{1,4})\\s*[, ]\\s*(-?\\d{1,5})");
    private static final Pattern PLACED = Pattern.compile(
            "✦ You placed the (Amber|Amethyst|Jade|Sapphire|Topaz) Crystal!", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECLAIMED = Pattern.compile(
            "✦ You reclaimed the (Amber|Amethyst|Jade|Sapphire|Topaz) Crystal!", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_PLACED = Pattern.compile(
            ".*: You haven't placed the (Amber|Amethyst|Jade|Sapphire|Topaz) Crystal yet!", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALREADY_PLACED = Pattern.compile(
            ".*: You have already placed the (Amber|Amethyst|Jade|Sapphire|Topaz) Crystal!", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOT_CRYSTAL = Pattern.compile(
            "^\\s{2,}(Amber|Amethyst|Jade|Sapphire|Topaz) Crystal$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Party|Guild|Officer|Co-op|From|To)\\s*>?\\s*)?"
                    + "(?:\\[[^]]+]\\s*)*([A-Za-z0-9_]{3,16}):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String LOOT_BUNDLE = "  CRYSTAL NUCLEUS LOOT BUNDLE";
    private static final String JADE_KEEPER = "You found all of the items! Behold... the Jade Crystal!";
    private static final String SHATTERED = "Your Wishing Compass shattered into pieces!";
    private static final String NO_TARGET = "The Wishing Compass can't seem to locate anything!";
    private static final List<Alias> ALIASES = buildAliases();

    private CrystalHollowsChatParser() {}

    public static Optional<NpcDialogue> parseNpcDialogue(String text) {
        String stripped = clean(text);
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            for (String prefix : structure.npcChatPrefixes()) {
                if (stripped.startsWith(prefix)) return Optional.of(new NpcDialogue(structure, prefix));
            }
        }
        return Optional.empty();
    }

    public static Optional<CrystalUpdate> parseCrystalState(String text) {
        String stripped = clean(text);
        if (stripped.startsWith(LOOT_BUNDLE)) {
            return Optional.of(new CrystalUpdate(null, CrystalState.MISSING, true));
        }
        if (stripped.startsWith("[NPC] Keeper of ") && stripped.contains(JADE_KEEPER)) {
            return Optional.of(new CrystalUpdate(Crystal.JADE, CrystalState.COLLECTED, false));
        }
        Optional<CrystalUpdate> update = matchCrystal(PLACED, stripped, CrystalState.PLACED);
        if (update.isPresent()) return update;
        update = matchCrystal(RECLAIMED, stripped, CrystalState.COLLECTED);
        if (update.isPresent()) return update;
        update = matchCrystal(NOT_PLACED, stripped, CrystalState.COLLECTED);
        if (update.isPresent()) return update;
        update = matchCrystal(ALREADY_PLACED, stripped, CrystalState.PLACED);
        if (update.isPresent()) return update;
        return matchCrystal(LOOT_CRYSTAL, stripped, CrystalState.COLLECTED);
    }

    public static List<SharedCoordinate> parseSharedCoordinates(String text) {
        String stripped = clean(text);
        if (stripped.isEmpty()) return List.of();
        Matcher compact = SBE_PREFIX.matcher(stripped);
        if (compact.matches()) return parseCompactEntries(compact.group(1));

        List<SharedCoordinate> named = parseNamedCoordinates(stripped, NAME_COORDS, "named");
        if (!named.isEmpty()) return named;
        named = parseNamedCoordinates(stripped, AT_COORDS, "dsm_plain");
        if (!named.isEmpty()) return named;

        CrystalHollowsStructure structure = structureFromText(stripped);
        if (structure == null) return List.of();
        List<SharedCoordinate> results = new ArrayList<>();
        for (CoordScanner.Match match : CoordScanner.scanWithPositions(stripped)) {
            if (insideShareBounds(match.x(), match.y(), match.z())) {
                results.add(new SharedCoordinate(structure, match.x(), match.y(), match.z(), "generic"));
            }
        }
        return List.copyOf(results);
    }

    /** Splits a normal player chat line from its rank prefixes; system messages return empty. */
    public static Optional<PlayerChat> playerChat(String text) {
        Matcher matcher = PLAYER_CHAT.matcher(clean(text));
        return matcher.matches()
                ? Optional.of(new PlayerChat(matcher.group(1), matcher.group(2)))
                : Optional.empty();
    }

    public static Optional<CompassServerMessage> parseCompassServerMessage(String text) {
        String stripped = clean(text);
        if (stripped.contains(NO_TARGET)) return Optional.of(CompassServerMessage.NO_TARGET);
        if (stripped.contains(SHATTERED)) return Optional.of(CompassServerMessage.USE_CONFIRMED);
        return Optional.empty();
    }

    public static boolean isDelayTrigger(String text) {
        String stripped = clean(text).trim();
        return stripped.contains("You died")
                || stripped.contains("☠ You were killed")
                || stripped.startsWith("Warp");
    }

    public static String formatShare(CrystalHollowsStructure structure, int x, int y, int z) {
        return structure.shareName() + ": " + x + " " + y + " " + z;
    }

    public static CrystalHollowsStructure structureFromText(String text) {
        String normalized = CrystalHollowsSidebar.normalizeName(text)
                .replace('’', '\'');
        for (Alias alias : ALIASES) {
            if (containsWholePhrase(normalized, alias.text())) return alias.structure();
        }
        return null;
    }

    private static List<SharedCoordinate> parseCompactEntries(String body) {
        List<SharedCoordinate> results = new ArrayList<>();
        for (String entry : body.split("\\\\n")) {
            Matcher matcher = SBE_ENTRY.matcher(entry);
            if (!matcher.matches()) continue;
            int x = Integer.parseInt(matcher.group(2));
            int y = Integer.parseInt(matcher.group(3));
            int z = Integer.parseInt(matcher.group(4));
            if (!insideShareBounds(x, y, z)) continue;
            CrystalHollowsStructure structure = structureFromText(matcher.group(1));
            results.add(new SharedCoordinate(structure, x, y, z, "dsm_sbe"));
        }
        return List.copyOf(results);
    }

    private static List<SharedCoordinate> parseNamedCoordinates(String text, Pattern pattern, String tag) {
        Matcher matcher = pattern.matcher(text);
        List<SharedCoordinate> results = new ArrayList<>();
        while (matcher.find()) {
            int x = Integer.parseInt(matcher.group(2));
            int y = Integer.parseInt(matcher.group(3));
            int z = Integer.parseInt(matcher.group(4));
            if (!insideShareBounds(x, y, z)) continue;
            CrystalHollowsStructure structure = structureFromText(matcher.group(1));
            if (structure == null) structure = structureFromText(text);
            results.add(new SharedCoordinate(structure, x, y, z, tag));
        }
        return List.copyOf(results);
    }

    private static Optional<CrystalUpdate> matchCrystal(Pattern pattern, String text, CrystalState state) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches() && !matcher.find()) return Optional.empty();
        Crystal crystal = Crystal.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        return Optional.of(new CrystalUpdate(crystal, state, false));
    }

    private static boolean insideShareBounds(int x, int y, int z) {
        return x >= 201 && x <= 824 && z >= 201 && z <= 824 && y >= 30 && y <= 189;
    }

    private static String clean(String text) {
        return text == null ? "" : CrystalHollowsSidebar.stripFormatting(text);
    }

    private static List<Alias> buildAliases() {
        List<Alias> aliases = new ArrayList<>();
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            for (String alias : structure.aliases()) {
                aliases.add(new Alias(structure, CrystalHollowsSidebar.normalizeName(alias)));
            }
        }
        aliases.sort(Comparator.comparingInt((Alias alias) -> alias.text().length()).reversed());
        return List.copyOf(aliases);
    }

    private static boolean containsWholePhrase(String value, String phrase) {
        int from = 0;
        while (from <= value.length() - phrase.length()) {
            int index = value.indexOf(phrase, from);
            if (index < 0) return false;
            int end = index + phrase.length();
            boolean before = index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1));
            boolean after = end == value.length() || !Character.isLetterOrDigit(value.charAt(end));
            if (before && after) return true;
            from = index + 1;
        }
        return false;
    }
}
