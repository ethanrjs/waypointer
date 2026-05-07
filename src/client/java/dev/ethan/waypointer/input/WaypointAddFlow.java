package dev.ethan.waypointer.input;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
 * <p>The flow focuses the route on the newly-created waypoint, forces the route
 * to static mode so the new marker remains visible, then suppresses proximity
 * for that one index until the player steps away. Without the suppression, a
 * waypoint created at the player's feet would be advanced or hidden on the very
 * next tick. It also auto-disables route skip-ahead so the new waypoint cannot
 * be skipped immediately.
 */
public final class WaypointAddFlow {

    /**
     * Call immediately after a new waypoint has been appended or inserted into
     * {@code group}. Temp groups are intentionally excluded because they never
     * participate in skip-ahead to begin with, and the toast would be a lie.
     */
    public void afterWaypointAdded(WaypointGroup group, int waypointIndex) {
        if (group == null) return;
        if (group.temp()) return;

        WaypointGroup.LoadMode loadModeBefore = group.loadMode();
        group.focusNewWaypoint(waypointIndex, false);
        boolean switchedToStatic = loadModeBefore == WaypointGroup.LoadMode.SEQUENCE;
        if (switchedToStatic) {
            group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        }
        showWaypointAddedMessage(group, waypointIndex);
        if (switchedToStatic) {
            showRouteStaticMessage(group.name(), waypointIndex);
        } else if (loadModeBefore == WaypointGroup.LoadMode.STATIC) {
            showCurrentWaypointFocusedMessage(group.name(), waypointIndex);
        }

        if (group.skipAheadEnabled()) {
            group.setSkipAheadEnabled(false);
            showSkipAheadDisabledToast(group.name());
        }
    }

    private static void showWaypointAddedMessage(WaypointGroup group, int waypointIndex) {
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;
        Waypoint waypoint = group.get(waypointIndex);
        showChatFeedback(Component.literal("Added waypoint " + waypointIndex + " at "
                + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z())
                .withStyle(ChatFormatting.GREEN));
    }

    private static void showRouteStaticMessage(String groupName, int waypointIndex) {
        showChatFeedback(Component.literal("\"" + groupName + "\" is now static; current waypoint set to "
                + waypointIndex + ".").withStyle(ChatFormatting.YELLOW));
    }

    private static void showCurrentWaypointFocusedMessage(String groupName, int waypointIndex) {
        showChatFeedback(Component.literal("\"" + groupName + "\" current waypoint set to "
                + waypointIndex + ".").withStyle(ChatFormatting.YELLOW));
    }

    private static void showChatFeedback(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        LocalPlayer player = mc.player;
        if (player == null) return;
        player.displayClientMessage(message, false);
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
