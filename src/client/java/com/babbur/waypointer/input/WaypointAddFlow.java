package com.babbur.waypointer.input;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.core.ActiveGroupManager;
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

        ActiveGroupManager manager = WaypointerClient.manager();
        if (manager != null) manager.noteRouteEdited(group);
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
                group.name(), group.displayIndexLabel(waypointIndex), waypoint, showChatShareButtons));
    }

    static Component waypointAddedMessage(String groupName, String label, Waypoint waypoint,
                                          boolean showChatShareButtons) {
        String coordinates = waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
        MutableComponent position = Component.literal("(").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(Integer.toString(waypoint.x())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(waypoint.y())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(waypoint.z())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        MutableComponent message = Component.translatableWithFallback("waypointer.input.added",
                "Added %1$s to %2$s at %3$s",
                Component.translatableWithFallback("waypointer.input.added.waypoint", "Waypoint %s",
                        label).withStyle(ChatFormatting.AQUA),
                Component.literal("\"" + groupName + "\"").withStyle(ChatFormatting.GREEN),
                position).withStyle(ChatFormatting.GRAY);
        if (!showChatShareButtons) return message;
        return message
                .append(Component.literal(" "))
                .append(chatShareButton(
                        Component.translatableWithFallback("waypointer.input.share.all", "All"),
                        "/ac " + coordinates))
                .append(Component.literal(" "))
                .append(chatShareButton(
                        Component.translatableWithFallback("waypointer.input.share.party", "Party"),
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
