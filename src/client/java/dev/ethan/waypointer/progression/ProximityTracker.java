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
        for (WaypointGroup group : manager.activeGroups()) {
            advanceIfReached(group, px, py, pz, loop);
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
        return advanceIfReached(group, px, py, pz, false);
    }

    /**
     * @param restartWhenComplete when {@code true}, completing the last waypoint
     *                              immediately resets progress to the start (see
     *                              {@link WaypointGroup#restartIfRouteCompleted(boolean)}).
     */
    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete) {
        if (group.isComplete()) return false;

        int size = group.size();
        int from = group.currentIndex();

        for (int i = size - 1; i >= from; i--) {
            Waypoint w = group.get(i);
            double r = group.effectiveRadius(w);
            double dx = (w.x() + 0.5) - px;
            double dy = (w.y() + 0.5) - py;
            double dz = (w.z() + 0.5) - pz;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                group.advancePast(i);
                group.restartIfRouteCompleted(restartWhenComplete);
                return true;
            }
        }
        return false;
    }
}
