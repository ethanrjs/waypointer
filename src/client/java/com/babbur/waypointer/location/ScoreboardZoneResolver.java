package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

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
    private static final Pattern LOCATION_LINE = Pattern.compile(
            "(?m)⏣[\\t ]*([^,\\r\\n]+?)[\\t ]*(?:,|$)");

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
        if (mc == null || mc.level == null || mc.player == null) return null;
        return resolveSidebarText(SidebarTexts.collectColorStripped(mc));
    }

    static Zone resolveSidebarText(String blob) {
        if (blob == null || blob.isBlank()) return null;
        // Specific mineshaft variants need scoreboard data because the Hypixel
        // location id does not identify their layouts. Dwarven surface areas use
        // the broad mining_3 location and resolve to dwarven_mines below.
        Zone mineshaftType = Zone.tryResolveMineshaftTypeFromSidebarBlob(blob);
        if (mineshaftType != null) return mineshaftType;
        Zone mineshaftArea = Zone.tryResolveDwarvenSubAreaFromSidebarBlob(blob);
        if (mineshaftArea != null) return mineshaftArea;
        Zone catacombsFloor = CatacombsFloorRefiner.tryResolveFromSidebarBlob(blob);
        if (catacombsFloor != null) return catacombsFloor;

        Matcher location = LOCATION_LINE.matcher(blob);
        if (location.find()) return Zone.resolveFromDisplayName(location.group(1).trim());
        return null;
    }
}
