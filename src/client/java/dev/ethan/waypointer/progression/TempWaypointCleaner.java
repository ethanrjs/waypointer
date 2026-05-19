package dev.ethan.waypointer.progression;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * Removes temporary waypoints at the two lifecycle moments their modes imply:
 *
 * <ul>
 *   <li>{@code TEMP_TIME}: every {@value #CHECK_INTERVAL_TICKS} ticks we sweep all
 *       groups and drop any time-based temp whose deadline has passed. A per-tick
 *       sweep is overkill — a 2s granularity is well below the resolution of the
 *       shortest duration the UI offers (1 min).</li>
 *   <li>{@code TEMP_UNTIL_LEAVE}: on {@link ClientPlayConnectionEvents#JOIN}
 *       and {@link ClientPlayConnectionEvents#DISCONNECT} we wipe every
 *       temporary waypoint of every mode. Hypixel lobby transfers can create a
 *       new play connection without waiting for the old session's temp markers
 *       to feel obviously "disconnected" to the player, so join is treated as
 *       a hard boundary too.</li>
 * </ul>
 *
 * Reach cleanup is handled inside {@link ProximityTracker} at the moment the
 * player enters a marker's radius. Route-owned {@code TEMP_UNTIL_REACHED}
 * waypoints are removed as progression advances; temp bucket markers can also
 * opt into the same behavior through the user's settings.
 */
public final class TempWaypointCleaner {

    /**
     * How many client ticks between expiry sweeps. At 20 TPS this is a 2-second
     * cadence — small enough that a user who set a 1-minute temp sees it vanish
     * within two seconds of expiry, large enough that we're not scanning every
     * group on every tick.
     */
    private static final int CHECK_INTERVAL_TICKS = 40;

    private final ActiveGroupManager manager;
    private int tickCounter;

    public TempWaypointCleaner(ActiveGroupManager manager) {
        this.manager = manager;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clearAllTemps());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearAllTemps());
    }

    private void onTick(Minecraft mc) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        boolean anyRemoved = false;
        for (WaypointGroup g : manager.allGroups()) {
            if (g.removeExpired(now) > 0) anyRemoved = true;
        }
        // Only poke the data-changed channel when something actually changed —
        // otherwise the per-tick sweep would trigger an autosave storm even on
        // empty worlds. fireDataChanged() also refreshes the active-groups
        // cache, which the renderer picks up on its next frame.
        if (anyRemoved) manager.fireDataChanged();
    }

    /** Lobby/server transitions are the hard deadline for every temp mode. */
    private void clearAllTemps() {
        manager.clearTemporaryWaypoints();
    }
}
