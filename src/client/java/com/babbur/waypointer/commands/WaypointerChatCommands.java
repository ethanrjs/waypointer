package com.babbur.waypointer.commands;

import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerChatCommands {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    WaypointerChatCommands(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    int runAddTempAt(FabricClientCommandSource src, int x, int y, int z, String sourceName) {
        WaypointGroup target = addConfiguredTempWaypoint(x, y, z, sourceName);
        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(target, target.size() - 1);
        }

        success(src, Component.translatable(
                "waypointer.command.temp.added",
                target.name(), x, y, z, defaultTempExpiryDescription()));
        return 1;
    }

    int runChatTempClick(FabricClientCommandSource src, int x, int y, int z,
                                 String senderArg, String encodedSource) {
        String senderName = "-".equals(senderArg) ? "" : senderArg;
        String sourceName = decodeChatTempSource(encodedSource);

        if (hasShiftDown()) {
            if (senderName.isBlank()) {
                warn(src, Component.translatable(
                        "waypointer.command.blacklist.unknown_sender"));
            } else {
                boolean nowBlocked = config.toggleChatCoordSenderBlacklist(senderName);
                int removed = nowBlocked ? manager.removeTempWaypointsFromSender(senderName) : 0;
                if (nowBlocked) {
                    success(src, removed > 0
                            ? Component.translatable(
                                    "waypointer.command.blacklist.added_and_removed",
                                    senderName, removed)
                            : Component.translatable(
                                    "waypointer.command.blacklist.added", senderName));
                } else {
                    success(src, Component.translatable(
                            "waypointer.command.blacklist.removed", senderName));
                }
            }
            return 1;
        }

        ActiveGroupManager.TempWaypointSelection selection = manager.findTempWaypoint(x, y, z, senderName);
        boolean created = false;
        if (selection == null) {
            WaypointGroup target = addConfiguredTempWaypoint(x, y, z, sourceName);
            int index = target.size() - 1;
            selection = new ActiveGroupManager.TempWaypointSelection(target, index);
            created = true;
        }

        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(selection.group(), selection.index());
            success(src, Component.translatable(
                    "waypointer.command.temp.focused", x, y, z));
            return 1;
        }

        success(src, Component.translatable(
                created
                        ? "waypointer.command.temp.created"
                        : "waypointer.command.temp.exists",
                x, y, z));
        return 1;
    }

    WaypointGroup addConfiguredTempWaypoint(int x, int y, int z, String sourceName) {
        long now = System.currentTimeMillis();
        return manager.addTempWaypoint(x, y, z, sourceName,
                config.tempDefaultMode(),
                config.defaultTempExpiresAtMillis(now),
                config.defaultWaypointColor());
    }

    Component defaultTempExpiryDescription() {
        return switch (config.tempDefaultMode()) {
            case Waypoint.TEMP_TIME -> Component.translatable(
                    "waypointer.command.temp.expiry.timed",
                    config.tempDefaultDurationSec());
            case Waypoint.TEMP_UNTIL_REACHED -> Component.translatable(
                    "waypointer.command.temp.expiry.reached");
            case Waypoint.TEMP_UNTIL_LEAVE -> Component.translatable(
                    "waypointer.command.temp.expiry.disconnect");
            default -> Component.translatable(
                    "waypointer.command.temp.expiry.temporary");
        };
    }

    int runChatCoordBlacklist(FabricClientCommandSource src) {
        List<String> names = config.chatCoordSenderBlacklist();
        if (names.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.blacklist.empty"));
            return 0;
        }
        info(src, Component.translatable(
                "waypointer.command.blacklist.list",
                Component.literal(String.join(", ", names))
                        .withStyle(ChatFormatting.YELLOW)));
        return names.size();
    }

    int runChatCoordBlacklistAdd(FabricClientCommandSource src, String senderName) {
        boolean added = config.addChatCoordSenderBlacklist(senderName);
        int removed = added ? manager.removeTempWaypointsFromSender(senderName) : 0;
        if (added) {
            success(src, removed > 0
                    ? Component.translatable(
                            "waypointer.command.blacklist.added_and_removed",
                            senderName, removed)
                    : Component.translatable(
                            "waypointer.command.blacklist.added", senderName));
        } else {
            info(src, Component.translatable(
                    "waypointer.command.blacklist.already", senderName));
        }
        return added ? 1 : 0;
    }

    int runChatCoordBlacklistRemove(FabricClientCommandSource src, String senderName) {
        if (config.removeChatCoordSenderBlacklist(senderName)) {
            success(src, Component.translatable(
                    "waypointer.command.blacklist.removed", senderName));
            return 1;
        }
        info(src, Component.translatable(
                "waypointer.command.blacklist.not_listed", senderName));
        return 0;
    }

    static String decodeChatTempSource(String encoded) {
        if (encoded == null || encoded.isBlank() || "-".equals(encoded)) return "";
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    static boolean hasShiftDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var win = mc.getWindow();
        return InputConstants.isKeyDown(win, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(win, 344 /* GLFW_KEY_RIGHT_SHIFT */);
    }

    String zoneSuffix() {
        Zone zone = manager.currentZone();
        return zone == null ? "" : " (" + zone.displayName() + ")";
    }
}
