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
 * Large imported routes can contain thousands of points, so the tracker asks each
 * group for nearby spatial-index candidates instead of walking the whole list
 * every tick. When skip-ahead is enabled we still choose the highest reachable
 * index, preserving the old "farthest-ahead waypoint wins" behaviour.
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
        boolean hideReachedStatic = config.hideReachedStaticWaypointsUntilCycleComplete();
        for (WaypointGroup group : manager.activeGroups()) {
            updateGroupProgress(group, px, py, pz, loop, globalSkipAhead, hideReachedStatic);
        }
    }

    public static void updateGroupProgress(WaypointGroup group,
                                           double px, double py, double pz,
                                           boolean restartWhenComplete,
                                           boolean globalSkipAhead,
                                           boolean hideReachedStatic) {
        // Temp-only bucket groups don't participate in progression -- they hold
        // ad-hoc markers whose own expiry modes handle cleanup. Running proximity
        // on them would re-enter the "advance past waypoint" logic on a container
        // whose order is meaningless.
        if (group.temp()) return;

        if (hideReachedStatic && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            markReachedStaticWaypoints(group, px, py, pz);
        }

        // Group-level skip-ahead gate. Global off always wins over group on --
        // the config is the master switch; the group flag is a per-route opt-out.
        boolean allowSkip = globalSkipAhead && group.skipAheadEnabled();
        advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkip);
    }

    /**
     * Static groups are unordered map overlays, so reach tracking scans every
     * waypoint instead of advancing a single route index. Reaching the final
     * hidden marker resets the group immediately (handled by WaypointGroup),
     * making the next cycle visible without requiring a reconnect or command.
     */
    public static boolean markReachedStaticWaypoints(WaypointGroup group,
                                                     double px, double py, double pz) {
        updateProximitySuppression(group, px, py, pz);
        boolean[] changed = { false };
        group.forEachNearbyIndex(px, py, pz, group.maxEffectiveRadius(), i -> {
            if (group.isStaticWaypointReached(i)) return true;
            if (group.isProximitySuppressed(i)) return true;

            Waypoint w = group.get(i);
            double r = group.effectiveRadius(w);
            double dx = (w.x() + 0.5) - px;
            double dy = (w.y() + 0.5) - py;
            double dz = (w.z() + 0.5) - pz;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                if (group.markStaticWaypointReached(i)) {
                    changed[0] = true;
                    if (group.consumeStaticCycleJustCompleted()) {
                        return false;
                    }
                }
            }
            return true;
        });
        return changed[0];
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
        updateProximitySuppression(group, px, py, pz);

        int size = group.size();
        int from = group.currentIndex();

        int reachedIndex = allowSkipAhead
                ? highestNearbyReachedIndex(group, from, px, py, pz)
                : currentReachedIndex(group, from, px, py, pz);
        if (reachedIndex < 0) return false;

        // Collect reach-based temps in [from..reachedIndex] BEFORE advancing,
        // because advancing changes currentIndex which we use to bound the scan.
        // Remove in reverse so earlier indices don't shift under us.
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

    private static int currentReachedIndex(WaypointGroup group, int index,
                                           double px, double py, double pz) {
        if (index < 0 || index >= group.size()) return -1;
        if (group.isProximitySuppressed(index)) return -1;
        return isWithinReach(group, group.get(index), px, py, pz) ? index : -1;
    }

    private static int highestNearbyReachedIndex(WaypointGroup group, int from,
                                                 double px, double py, double pz) {
        int[] reachedIndex = { -1 };
        group.forEachNearbyIndex(px, py, pz, group.maxEffectiveRadius(), i -> {
            if (i < from || group.isProximitySuppressed(i)) return true;
            if (i <= reachedIndex[0]) return true;
            if (isWithinReach(group, group.get(i), px, py, pz)) {
                reachedIndex[0] = i;
            }
            return true;
        });
        return reachedIndex[0];
    }

    private static void updateProximitySuppression(WaypointGroup group,
                                                   double px, double py, double pz) {
        int index = group.proximitySuppressedIndex();
        if (index < 0) return;
        if (index >= group.size()) {
            group.clearProximitySuppression();
            return;
        }

        Waypoint w = group.get(index);
        if (!isWithinReach(group, w, px, py, pz)) {
            group.clearProximitySuppression();
        }
    }

    private static boolean isWithinReach(WaypointGroup group, Waypoint w,
                                         double px, double py, double pz) {
        double r = group.effectiveRadius(w);
        double dx = (w.x() + 0.5) - px;
        double dy = (w.y() + 0.5) - py;
        double dz = (w.z() + 0.5) - pz;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }
}
