package com.babbur.waypointer.input;

import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Shared side-effects run on every "player just created a waypoint" event,
 * regardless of source (keybind, command, chat-coord click, GUI "Add here").
 *
 * <p>Centralising the post-add behaviour prevents the bug where the rule was
 * added at one entry point but forgotten at another -- every add site now
 * funnels through {@link #afterWaypointAdded(WaypointGroup, int, boolean)} after mutating the
 * group, keeping the user-visible contract consistent.
 *
 * <p>The flow focuses the route on the newly-created waypoint, keeps the route
 * sequenced, disables route-level skip-ahead, then suppresses proximity for that
 * one index until the player steps away. Without the suppression, a waypoint
 * created at the player's feet would be advanced or hidden on the very next tick.
 */
public final class WaypointAddFlow {

    public void afterWaypointAdded(WaypointGroup group, int waypointIndex,
                                   boolean showChatShareButtons) {
        if (group == null) return;
        if (group.temp()) return;

        boolean skipAheadWasEnabled = group.skipAheadEnabled();
        group.focusNewWaypoint(waypointIndex, false);
        if (skipAheadWasEnabled) {
            group.setSkipAheadEnabled(false);
        }
        showWaypointAddedMessage(group, waypointIndex, showChatShareButtons);
        showCurrentWaypointFocusedMessage(group.name(), waypointIndex);
        if (skipAheadWasEnabled) {
            showSkipAheadDisabledMessage(group.name());
        }

    }

    private static void showWaypointAddedMessage(WaypointGroup group, int waypointIndex,
                                                 boolean showChatShareButtons) {
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;
        Waypoint waypoint = group.get(waypointIndex);
        showChatFeedback(waypointAddedMessage(waypointIndex, waypoint, showChatShareButtons));
    }

    static Component waypointAddedMessage(int waypointIndex, Waypoint waypoint,
                                          boolean showChatShareButtons) {
        String coordinates = waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
        MutableComponent message = Component.translatable(
                "waypointer.input.added", waypointIndex, coordinates)
                .withStyle(ChatFormatting.GREEN);
        if (!showChatShareButtons) return message;
        return message
                .append(chatShareButton(
                        Component.translatable("waypointer.input.share.all"),
                        "/ac " + coordinates))
                .append(chatShareButton(
                        Component.translatable("waypointer.input.share.party"),
                        "/pc " + coordinates));
    }

    private static Component chatShareButton(Component label, String command) {
        return Component.literal(" ").append(
                Component.translatable("waypointer.input.share.button", label))
                .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable("waypointer.input.share.hover", label))));
    }

    private static void showCurrentWaypointFocusedMessage(String groupName, int waypointIndex) {
        showChatFeedback(Component.translatable(
                "waypointer.input.focused", groupName, waypointIndex)
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void showSkipAheadDisabledMessage(String groupName) {
        showChatFeedback(Component.translatable(
                "waypointer.input.skip_ahead_disabled", groupName)
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
