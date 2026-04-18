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
 * funnels through {@link #afterWaypointAdded(WaypointGroup)} after mutating the
 * group, keeping the user-visible contract consistent.
 *
 * <p>Today the flow handles one decision: when a waypoint is added to a group
 * whose skip-ahead is on, proximity advance will likely immediately skip past
 * the freshly-added waypoint because the player is standing on it. Disabling
 * skip-ahead on that group (controlled by
 * {@link WaypointerConfig#disableGroupSkipAheadOnWaypointAdd()}) sidesteps that
 * surprise. The toast tells the player <em>why</em> so they don't go hunting
 * for a bug in the progression system.
 */
public final class WaypointAddFlow {

    private final WaypointerConfig config;

    public WaypointAddFlow(WaypointerConfig config) {
        this.config = config;
    }

    /**
     * Call immediately after a new waypoint has been appended or inserted into
     * {@code group}. Temp groups are intentionally excluded because they never
     * participate in skip-ahead to begin with, and the toast would be a lie.
     */
    public void afterWaypointAdded(WaypointGroup group) {
        if (group == null) return;
        if (group.temp()) return;
        if (!config.disableGroupSkipAheadOnWaypointAdd()) return;
        if (!group.skipAheadEnabled()) return;

        group.setSkipAheadEnabled(false);
        showSkipAheadDisabledToast(group.name());
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
