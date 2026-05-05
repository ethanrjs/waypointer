package dev.ethan.waypointer.location;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Collection;

/**
 * Renders the full Skyblock sidebar as plain text so zone logic can scan for
 * sub-areas (e.g. Glacite Tunnels vs Dwarven Mines) that never appear as
 * distinct {@code mode} values in the Hypixel location packet.
 *
 * <p>Hot path (called every 2 game ticks): avoids regex stripping and caches
 * the last-seen rendered-line hash so repeated unchanged sidebars reuse the
 * prior stripped result. The 10Hz caller was allocating a {@link StringBuilder}
 * + stripped output string per invocation; the manual strip + cache keeps the
 * unchanged case out of that work.
 */
public final class SidebarTexts {

    /** Minecraft's formatting code escape. Kept as a constant to document the parse. */
    private static final char FORMATTING_PREFIX = '\u00a7';

    private static Scoreboard cachedScoreboard;
    private static Objective cachedObjective;
    private static int cachedLineHash;
    private static int cachedLineCount;
    private static String cachedText;

    private SidebarTexts() {}

    /**
     * @return all sidebar lines concatenated with newlines, formatting codes
     *         stripped, or null if the sidebar isn't available.
     */
    public static String collectColorStripped(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            clearCache();
            return null;
        }

        Scoreboard sb = level.getScoreboard();
        Objective side = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (side == null) {
            clearCache();
            return null;
        }

        Collection<PlayerScoreEntry> entries = sb.listPlayerScores(side);
        SidebarFingerprint fingerprint = fingerprintSidebar(sb, entries);
        if (matchesCache(sb, side, fingerprint)) return cachedText;

        String text = buildText(sb, entries);
        remember(sb, side, fingerprint, text);
        return text;
    }

    private static SidebarFingerprint fingerprintSidebar(
            Scoreboard sb, Collection<PlayerScoreEntry> entries) {
        int hash = 1;
        int count = 0;
        for (PlayerScoreEntry entry : entries) {
            String line = renderLine(sb, entry);
            if (line == null) continue;

            hash = 31 * hash + line.hashCode();
            count++;
        }
        return new SidebarFingerprint(hash, count);
    }

    private static boolean matchesCache(
            Scoreboard sb, Objective side, SidebarFingerprint fingerprint) {
        return cachedScoreboard == sb
                && cachedObjective == side
                && cachedLineHash == fingerprint.hash()
                && cachedLineCount == fingerprint.lineCount();
    }

    private static void remember(
            Scoreboard sb, Objective side, SidebarFingerprint fingerprint, String text) {
        cachedScoreboard = sb;
        cachedObjective = side;
        cachedLineHash = fingerprint.hash();
        cachedLineCount = fingerprint.lineCount();
        cachedText = text;
    }

    private static void clearCache() {
        cachedScoreboard = null;
        cachedObjective = null;
        cachedLineHash = 0;
        cachedLineCount = 0;
        cachedText = null;
    }

    private static String buildText(Scoreboard sb, Collection<PlayerScoreEntry> entries) {
        StringBuilder out = new StringBuilder();
        for (PlayerScoreEntry entry : entries) {
            String line = renderLine(sb, entry);
            if (line == null) continue;
            if (!out.isEmpty()) out.append('\n');
            appendStripped(out, line);
        }
        return out.isEmpty() ? null : out.toString();
    }

    private record SidebarFingerprint(int hash, int lineCount) {}

    private static String renderLine(Scoreboard sb, PlayerScoreEntry entry) {
        String owner = entry.owner();
        PlayerTeam team = sb.getPlayersTeam(owner);
        Component formatted = team == null
                ? Component.literal(owner)
                : PlayerTeam.formatNameForTeam(team, Component.literal(owner));
        return formatted.getString();
    }

    /**
     * Manual single-pass formatting-code stripper. Replaces the old regex-backed
     * version, which allocated a {@code Matcher} plus a new {@code String} on
     * every call. Minecraft formatting codes are always {@code §} followed by
     * exactly one of {@code 0-9 a-f k-o r} (case-insensitive) -- anything else
     * after {@code §} is left alone, matching the legacy regex's behaviour of
     * only stripping recognised codes.
     */
    private static void appendStripped(StringBuilder out, String line) {
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (c == FORMATTING_PREFIX && i + 1 < n && isFormattingCode(line.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
    }

    private static boolean isFormattingCode(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'a' && c <= 'f') return true;
        if (c >= 'A' && c <= 'F') return true;
        if (c >= 'k' && c <= 'o') return true;
        if (c >= 'K' && c <= 'O') return true;
        return c == 'r' || c == 'R';
    }
}
