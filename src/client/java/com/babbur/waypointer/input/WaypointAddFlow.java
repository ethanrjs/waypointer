package com.babbur.waypointer.input;

import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
 * <p>The flow focuses the route on the newly-created waypoint, keeps the route
 * sequenced, disables route-level skip-ahead, then suppresses proximity for that
 * one index until the player steps away. Without the suppression, a waypoint
 * created at the player's feet would be advanced or hidden on the very next tick.
 */
public final class WaypointAddFlow {

    public void afterWaypointAdded(WaypointGroup group, int waypointIndex) {
        if (group == null) return;
        if (group.temp()) return;

        boolean skipAheadWasEnabled = group.skipAheadEnabled();
        group.focusNewWaypoint(waypointIndex, false);
        if (skipAheadWasEnabled) {
            group.setSkipAheadEnabled(false);
        }
        showWaypointAddedMessage(group, waypointIndex);
        showCurrentWaypointFocusedMessage(group.name(), waypointIndex);
        if (skipAheadWasEnabled) {
            showSkipAheadDisabledMessage(group.name());
        }

    }

    private static void showWaypointAddedMessage(WaypointGroup group, int waypointIndex) {
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;
        Waypoint waypoint = group.get(waypointIndex);
        showChatFeedback(Component.literal("Added waypoint " + waypointIndex + " at "
                + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z())
                .withStyle(ChatFormatting.GREEN));
    }

    private static void showCurrentWaypointFocusedMessage(String groupName, int waypointIndex) {
        showChatFeedback(Component.literal("\"" + groupName + "\" current waypoint set to "
                + waypointIndex + ".").withStyle(ChatFormatting.YELLOW));
    }

    private static void showSkipAheadDisabledMessage(String groupName) {
        showChatFeedback(Component.literal("\"" + groupName
                + "\" skip-ahead disabled to keep new waypoints in sequence.")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void showChatFeedback(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        LocalPlayer player = mc.player;
        if (player == null) return;
        player.sendSystemMessage(WaypointerChatFeedback.suppress(message));
    }

}
