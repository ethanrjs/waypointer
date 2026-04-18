package dev.ethan.waypointer.location;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback zone source that reads the Skyblock scoreboard sidebar.
 *
 * Skyblock's sidebar reliably contains one line formatted like {@code "⏣ Dwarven Mines"}
 * while the player is on a Skyblock server -- and goes missing on other Hypixel game modes.
 * That's enough to detect both the map AND whether we're on Skyblock at all.
 *
 * We poll at 10 Hz (every 2 ticks) which is plenty: zone changes aren't time-critical
 * for rendering, and the sidebar updates slower than that anyway.
 */
public final class ScoreboardZoneResolver implements ZoneSource {

    /**
     * Skyblock prefixes every location line with the ⏣ symbol. Capture everything after it
     * up to whitespace-comma (because the scoreboard sometimes appends " , (Coords)").
     */
    private static final Pattern LOCATION_LINE = Pattern.compile("⏣\\s*([^,]+?)\\s*(?:,|$)");

    private static final int POLL_INTERVAL_TICKS = 2;

    private Consumer<Zone> listener;
    private Zone lastEmitted;
    private int tickCounter = 0;

    @Override
    public void register(Consumer<Zone> listener) {
        this.listener = listener;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;

        Zone z = detect(mc);
        if (!Objects.equals(z, lastEmitted)) {
            lastEmitted = z;
            Waypointer.LOGGER.debug("Scoreboard zone resolved to {}", z);
            if (listener != null) listener.accept(z);
        }
    }

    private Zone detect(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return null;

        Scoreboard sb = level.getScoreboard();
        Objective side = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (side == null) return null;

        Collection<PlayerScoreEntry> entries = sb.listPlayerScores(side);
        for (PlayerScoreEntry entry : entries) {
            String line = renderLine(sb, entry);
            if (line == null) continue;
            Matcher m = LOCATION_LINE.matcher(line);
            if (m.find()) {
                String name = stripColors(m.group(1)).trim();
                return Zone.resolveFromDisplayName(name);
            }
        }
        return null;
    }

    /** Turn a scoreboard entry into its rendered string (resolves team prefix/suffix + formatting). */
    private static String renderLine(Scoreboard sb, PlayerScoreEntry entry) {
        String owner = entry.owner();
        PlayerTeam team = sb.getPlayersTeam(owner);
        Component formatted = team == null
                ? Component.literal(owner)
                : PlayerTeam.formatNameForTeam(team, Component.literal(owner));
        return formatted.getString();
    }

    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private static String stripColors(String s) {
        return COLOR_CODE.matcher(s).replaceAll("");
    }
}
