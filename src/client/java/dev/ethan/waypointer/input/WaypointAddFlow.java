package dev.ethan.waypointer.input;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Shared side-effects run on every "player just created a waypoint" event,
 * regardless of source (keybind, command, chat-coord click, GUI "Add here").
 *
 * <p>Centralising the post-add behaviour prevents the bug where the rule was
 * added at one entry point but forgotten at another -- every add site now
 * funnels through {@link #afterWaypointAdded(WaypointGroup, int)} after mutating the
 * group, keeping the user-visible contract consistent.
 *
 * <p>The flow does two things: it focuses the route on the newly-created waypoint
 * so the user sees immediate feedback, then suppresses proximity for that one
 * index until they step away. Without the suppression, a waypoint created at the
 * player's feet would be advanced or hidden on the very next tick. It also keeps
 * the existing skip-ahead auto-disable behavior when that setting is enabled.
 */
public final class WaypointAddFlow {

    private final WaypointerConfig config;

    public WaypointAddFlow(WaypointerConfig config) {
        this.config = config;
    }

    /**
     * Backward-compatible append helper for older call sites.
     */
    public void afterWaypointAdded(WaypointGroup group) {
        if (group == null) return;
        afterWaypointAdded(group, group.size() - 1);
    }

    /**
     * Call immediately after a new waypoint has been appended or inserted into
     * {@code group}. Temp groups are intentionally excluded because they never
     * participate in skip-ahead to begin with, and the toast would be a lie.
     */
    public void afterWaypointAdded(WaypointGroup group, int waypointIndex) {
        if (group == null) return;
        if (group.temp()) return;

        group.focusNewWaypoint(waypointIndex);
        if (!config.disableGroupSkipAheadOnWaypointAdd()) return;
        if (group.skipAheadEnabled()) {
            group.setSkipAheadEnabled(false);
            showSkipAheadDisabledToast(group.name());
        }
    }

    /**
     * Fires a system toast explaining the skip-ahead auto-disable. Kept package-
     * private so a caller can reuse the exact messaging if it ever needs to
     * surface the state outside of "just added a waypoint" (e.g. manual toggle
     * via a command).
     */
    private static void showSkipAheadDisabledToast(String groupName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        // "PERIODIC_NOTIFICATION" is Mojang's catch-all id for non-critical
        // status toasts -- it de-dupes with itself so repeated adds in quick
        // succession don't stack three toasts on top of each other, they just
        // refresh the top one.
        SystemToast.addOrUpdate(
                mc.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Skip-ahead disabled"),
                Component.literal("\"" + groupName + "\" -- auto-disabled because the new "
                        + "waypoint is nearby and would be skipped instantly."));
    }
}
