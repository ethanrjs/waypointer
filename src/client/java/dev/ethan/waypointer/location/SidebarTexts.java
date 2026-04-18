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
import java.util.regex.Pattern;

/**
 * Renders the full Skyblock sidebar as plain text so zone logic can scan for
 * sub-areas (e.g. Glacite Tunnels vs Dwarven Mines) that never appear as
 * distinct {@code mode} values in the Hypixel location packet.
 */
public final class SidebarTexts {

    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");

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
            out.append(stripColors(line));
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

    private static String stripColors(String s) {
        return COLOR_CODE.matcher(s).replaceAll("");
    }
}
