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

public final class WaypointAddFlow {

    public void afterWaypointAdded(WaypointGroup group, int waypointIndex,
                                   boolean showChatShareButtons) {
        if (group == null) return;
        if (group.temp()) return;

        group.focusNewWaypoint(waypointIndex, false);
        if (group.skipAheadEnabled()) {
            group.setSkipAheadEnabled(false);
        }
        showWaypointAddedMessage(group, waypointIndex, showChatShareButtons);
    }

    private static void showWaypointAddedMessage(WaypointGroup group, int waypointIndex,
                                                 boolean showChatShareButtons) {
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;
        Waypoint waypoint = group.get(waypointIndex);
        showChatFeedback(waypointAddedMessage(
                group.name(), waypointIndex, waypoint, showChatShareButtons));
    }

    static Component waypointAddedMessage(String groupName, int waypointIndex, Waypoint waypoint,
                                          boolean showChatShareButtons) {
        String coordinates = waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
        MutableComponent message = Component.empty()
                .append(Component.literal("Added ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Waypoint " + (waypointIndex + 1))
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" to ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\"" + groupName + "\"").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" at (").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(waypoint.x()))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(waypoint.y()))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(waypoint.z()))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        if (!showChatShareButtons) return message;
        return message
                .append(Component.literal(" "))
                .append(chatShareButton(
                        Component.literal("All"),
                        "/ac " + coordinates))
                .append(Component.literal(" "))
                .append(chatShareButton(
                        Component.literal("Party"),
                        "/pc " + coordinates));
    }

    private static Component chatShareButton(Component label, String command) {
        return Component.literal("[").append(label)
                .append(Component.literal("]"))
                .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable("waypointer.input.share.hover", label))));
    }

    private static void showChatFeedback(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        LocalPlayer player = mc.player;
        if (player == null) return;
        player.sendSystemMessage(WaypointerChatFeedback.suppress(message));
    }

}
