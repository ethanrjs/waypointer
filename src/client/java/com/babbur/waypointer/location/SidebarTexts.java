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

/** Reads and caches the visible scoreboard sidebar text used for zone detection. */
public final class SidebarTexts {

    private static final char FORMATTING_PREFIX = '\u00a7';

    private static Scoreboard cachedScoreboard;
    private static Objective cachedObjective;
    private static List<String> cachedRenderedLines;
    private static String cachedText;

    private SidebarTexts() {}

    /** Returns the sidebar's score lines as plain text, or {@code null} when none are shown. */
    public static String collectColorStripped(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            clearCache();
            return null;
        }

        Scoreboard scoreboard = level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            clearCache();
            return null;
        }

        Collection<PlayerScoreEntry> entries = scoreboard.listPlayerScores(sidebar);
        List<String> renderedLines = scanSidebar(scoreboard, entries);
        if (matchesCache(scoreboard, sidebar, renderedLines)) return cachedText;

        String text = buildStrippedText(renderedLines);
        remember(scoreboard, sidebar, renderedLines, text);
        return text;
    }

    private static List<String> scanSidebar(
            Scoreboard scoreboard, Collection<PlayerScoreEntry> entries) {
        List<String> renderedLines = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            String line = renderLine(scoreboard, entry);
            if (line == null) continue;

            renderedLines.add(line);
        }
        return renderedLines;
    }

    private static boolean matchesCache(
            Scoreboard scoreboard, Objective sidebar, List<String> renderedLines) {
        return cachedScoreboard == scoreboard
                && cachedObjective == sidebar
                && renderedLines.equals(cachedRenderedLines);
    }

    private static void remember(
            Scoreboard scoreboard, Objective sidebar, List<String> renderedLines, String text) {
        cachedScoreboard = scoreboard;
        cachedObjective = sidebar;
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

    private static String renderLine(Scoreboard scoreboard, PlayerScoreEntry entry) {
        String owner = entry.owner();
        PlayerTeam team = scoreboard.getPlayersTeam(owner);
        Component formatted = team == null
                ? Component.literal(owner)
                : PlayerTeam.formatNameForTeam(team, Component.literal(owner));
        return formatted.getString();
    }

    // Hidden row names contain formatting pairs; strip the pairs, not their text.
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
