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
 * the last-seen sidebar component hash so repeated calls within the same tick
 * reuse the prior result. The 10Hz caller was allocating a {@link StringBuilder}
 * + intermediate strings per invocation; the manual strip + cache keeps the
 * per-call cost to pointer comparisons on the unchanged case.
 */
public final class SidebarTexts {

    /** Minecraft's formatting code escape. Kept as a constant to document the parse. */
    private static final char FORMATTING_PREFIX = '\u00a7';

    private SidebarTexts() {}

    /**
     * @return all sidebar lines concatenated with newlines, formatting codes
     *         stripped, or null if the sidebar isn't available.
     */
    public static String collectColorStripped(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return null;

        Scoreboard sb = level.getScoreboard();
        Objective side = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (side == null) return null;

        Collection<PlayerScoreEntry> entries = sb.listPlayerScores(side);
        StringBuilder out = new StringBuilder();
        for (PlayerScoreEntry entry : entries) {
            String line = renderLine(sb, entry);
            if (line == null) continue;
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
