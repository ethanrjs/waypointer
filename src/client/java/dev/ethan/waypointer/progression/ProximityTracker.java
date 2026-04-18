package dev.ethan.waypointer.progression;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Watches the local player each client tick and advances any active group whose
 * next-or-later waypoint is within {@link WaypointGroup#effectiveRadius(Waypoint)}.
 *
 * Advancing past a later waypoint (skipping ahead) is a first-class operation: if the
 * player walks near waypoint N+3 before N, the group jumps straight to N+4. That
 * matters for dungeon speedruns where players intentionally cut corners.
 *
 * We scan the *tail* of the list starting at {@link WaypointGroup#currentIndex()} in
 * reverse. Reverse scan means the first hit is the farthest-ahead reachable waypoint,
 * so we advance past it in one step rather than N steps. Tick-hot path: don't allocate.
 */
public final class ProximityTracker {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public ProximityTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();

        // Groups keep their own defaultRadius for progression checks; the config value
        // is used as the starting radius for groups created through commands/UI so the
        // player's preferred feel is baked in from day one.
        boolean loop = config.restartRouteWhenComplete();
        boolean globalSkipAhead = config.skipAheadMechanicEnabled();
        for (WaypointGroup group : manager.activeGroups()) {
            // Temp-only bucket groups don't participate in progression -- they hold
            // ad-hoc markers whose own expiry modes handle cleanup. Running proximity
            // on them would re-enter the "advance past waypoint" logic on a container
            // whose order is meaningless.
            if (group.temp()) continue;
            // Group-level skip-ahead gate. When a waypoint was just added the
            // group's flag is flipped off (see the feature wiring in
            // WaypointerConfig#disableGroupSkipAheadOnWaypointAdd); we respect
            // that regardless of the global mechanic state. Global off always
            // wins over group on -- the config is the master switch.
            boolean allowSkip = globalSkipAhead && group.skipAheadEnabled();
            advanceIfReached(group, px, py, pz, loop, allowSkip);
        }
    }

    /**
     * Reverse-scan from the last waypoint down to {@code currentIndex}; if any is within
     * reach, jump past it. Visible for tests so progression logic stays unit-testable
     * without needing a live client.
     *
     * The 4-arg overload defaults {@code restartWhenComplete} to {@code false} so
     * unit tests can assert "route complete" without the loop behaviour.
     */
    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz) {
        return advanceIfReached(group, px, py, pz, false, true);
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, true);
    }

    /**
     * @param restartWhenComplete when {@code true}, completing the last waypoint
     *                              immediately resets progress to the start (see
     *                              {@link WaypointGroup#restartIfRouteCompleted(boolean)}).
     * @param allowSkipAhead      when {@code true} (default behaviour), a hit on
     *                              waypoint N+3 advances past N+3 in one step --
     *                              the "corner-cutting" mode. When {@code false},
     *                              only the immediate next waypoint counts; the
     *                              player has to visit each one in order. The
     *                              config flag threads through here verbatim.
     */
    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete, boolean allowSkipAhead) {
        if (group.isComplete()) return false;

        int size = group.size();
        int from = group.currentIndex();

        // When skip-ahead is disabled, we only look at the single current waypoint.
        // Scanning [from, from] keeps the hit-test uniform with the full-range
        // branch so there's one set of distance/advance logic to reason about.
        int scanStart = allowSkipAhead ? size - 1 : from;

        for (int i = scanStart; i >= from; i--) {
            Waypoint w = group.get(i);
            double r = group.effectiveRadius(w);
            double dx = (w.x() + 0.5) - px;
            double dy = (w.y() + 0.5) - py;
            double dz = (w.z() + 0.5) - pz;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                // Collect reach-based temps in [from..i] BEFORE advancing, because
                // advancing changes currentIndex which we use to bound the scan.
                // Remove in reverse so earlier indices don't shift under us.
                int reachedIndex = i;
                group.advancePast(reachedIndex);
                for (int j = reachedIndex; j >= from; j--) {
                    Waypoint wj = group.get(j);
                    if (wj.tempMode() == Waypoint.TEMP_UNTIL_REACHED) {
                        group.remove(j);
                    }
                }
                group.restartIfRouteCompleted(restartWhenComplete);
                return true;
            }
        }
        return false;
    }
}
