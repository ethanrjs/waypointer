package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Post-import UI feedback: toasts for success/failure so import actions have a
 * visible result even when chat is not the active focus.
 *
 * <p>Previously every import path (command, clipboard, chat, file) relied on
 * the user to manually open the editor, pick the right zone tab, and find the
 * fresh groups. That was fine when imports always landed in the current zone,
 * but imports often arrive with mismatched or unknown zones (cross-user
 * shares, coleweight routes), leaving users unsure whether the import worked
 * and where the result went. Centralising the toast feedback here means every
 * import source gets the same passive "here's what happened" treatment, while
 * callers decide whether to navigate, select, or simply offer an explicit
 * editor link.
 */
public final class ImportFeedback {

    private ImportFeedback() {}

    /**
     * Report a successful import via a system toast. {@code imported} must be
     * the list of groups that were actually added to the manager so we can cite
     * their names in the toast body.
     *
     * <p>When {@code imported} is empty this is a no-op -- an empty "success"
     * report would just confuse the user. Callers that parsed a payload but
     * added nothing should use {@link #failure(String)} with the parse-level
     * reason instead.
     */
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

    /**
     * Report a failed import via a toast so users who triggered the import
     * from the GUI (no chat open, no command feedback) still see something.
     * Command-path callers also surface the error through chat; the toast is
     * additive there, not a replacement.
     */
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
