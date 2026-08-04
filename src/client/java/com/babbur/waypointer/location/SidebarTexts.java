package com.babbur.waypointer.location;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Renders the full Skyblock sidebar as plain text so zone logic can identify
 * Glacite Mineshaft layouts and Catacombs floors that are not distinct
 * {@code mode} values in the Hypixel location packet.
 *
 * <p>Hot path (called every 2 game ticks): avoids regex stripping and caches
 * the last-seen rendered lines so repeated unchanged sidebars reuse the
 * prior stripped result. The 10Hz caller was allocating a {@link StringBuilder}
 * + stripped output string per invocation; the manual strip + cache keeps the
 * unchanged case out of that work.
 */
public final class SidebarTexts {

    /** Minecraft's formatting code escape. Kept as a constant to document the parse. */
    private static final char FORMATTING_PREFIX = '\u00a7';

    private static Scoreboard cachedScoreboard;
    private static Objective cachedObjective;
    private static List<String> cachedRenderedLines;
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
        List<String> renderedLines = scanSidebar(sb, entries);
        if (matchesCache(sb, side, renderedLines)) return cachedText;

        String text = buildStrippedText(renderedLines);
        remember(sb, side, renderedLines, text);
        return text;
    }

    private static List<String> scanSidebar(
            Scoreboard sb, Collection<PlayerScoreEntry> entries) {
        List<String> renderedLines = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            String line = renderLine(sb, entry);
            if (line == null) continue;

            renderedLines.add(line);
        }
        return renderedLines;
    }

    private static boolean matchesCache(
            Scoreboard sb, Objective side, List<String> renderedLines) {
        return cachedScoreboard == sb
                && cachedObjective == side
                && renderedLines.equals(cachedRenderedLines);
    }

    private static void remember(
            Scoreboard sb, Objective side, List<String> renderedLines, String text) {
        cachedScoreboard = sb;
        cachedObjective = side;
        cachedRenderedLines = List.copyOf(renderedLines);
        cachedText = text;
    }

    private static void clearCache() {
        cachedScoreboard = null;
        cachedObjective = null;
        cachedRenderedLines = null;
        cachedText = null;
    }

    static String buildStrippedText(List<String> renderedLines) {
        StringBuilder out = new StringBuilder();
        for (String line : renderedLines) {
            if (!out.isEmpty()) out.append('\n');
            appendStripped(out, line);
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String renderLine(Scoreboard sb, PlayerScoreEntry entry) {
        String owner = entry.owner();
        PlayerTeam team = sb.getPlayersTeam(owner);
        Component formatted = team == null
                ? Component.literal(owner)
                : PlayerTeam.formatNameForTeam(team, Component.literal(owner));
        return formatted.getString();
    }

    /**
     * Manual single-pass formatting-token stripper. Hypixel uses nonstandard
     * {@code §} pairs in hidden scoreboard owner names so each entry stays
     * unique. Those pairs can split visible text such as {@code TUNG_1}; remove
     * every pair instead of accepting only vanilla color and style codes.
     */
    private static void appendStripped(StringBuilder out, String line) {
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (c == FORMATTING_PREFIX) {
                if (i + 1 < n) i++;
                continue;
            }
            out.append(c);
        }
    }
}
