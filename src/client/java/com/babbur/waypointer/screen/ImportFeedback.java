package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ImportFeedback {

    private ImportFeedback() {}

    public static void success(List<WaypointGroup> imported, String source) {
        if (imported == null || imported.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        WaypointGroup first = imported.get(0);
        Component body = Component.translatable(imported.size() == 1
                ? "waypointer.import.success.one"
                : "waypointer.import.success.many",
                imported.size() == 1 ? first.name() : imported.size(),
                first.waypoints().size());

        SystemToast.addOrUpdate(
                MinecraftCompat.toastManager(mc),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                source == null
                        ? Component.translatable("waypointer.import.success.title")
                        : Component.translatable("waypointer.import.success.title.source", source),
                body);
    }

    public static void successDungeonRoutes(int roomCount, int waypointCount, String source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        SystemToast.addOrUpdate(
                MinecraftCompat.toastManager(mc),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                source == null
                        ? Component.translatable("waypointer.import.dungeon.title")
                        : Component.translatable("waypointer.import.dungeon.title.source", source),
                Component.translatable("waypointer.import.dungeon.body",
                        roomCount, waypointCount));
    }

    public static void failure(String reason) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        SystemToast.addOrUpdate(
                MinecraftCompat.toastManager(mc),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("waypointer.import.failed.title"),
                reason == null
                        ? Component.translatable("waypointer.import.failed.empty")
                        : Component.literal(reason));
    }
}
