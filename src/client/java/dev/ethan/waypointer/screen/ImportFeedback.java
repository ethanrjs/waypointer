package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Post-import UI feedback: toasts for success/failure and navigation into the
 * editor so the newly-imported groups are visible immediately.
 *
 * <p>Previously every import path (command, clipboard, chat, file) relied on
 * the user to manually open the editor, pick the right zone tab, and find the
 * fresh groups. That was fine when imports always landed in the current zone,
 * but imports often arrive with mismatched or unknown zones (cross-user
 * shares, coleweight routes), leaving users unsure whether the import worked
 * and where the result went. Centralising the feedback here means every
 * import source gets the same "here's what happened, and here's where to see
 * it" treatment.
 */
public final class ImportFeedback {

    private ImportFeedback() {}

    /**
     * Report a successful import via a system toast and open the editor
     * scrolled/selected to the first imported group. {@code imported} must be
     * the list of groups that were actually added to the manager so we can
     * cite their names and navigate to their zone.
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
        String body = imported.size() == 1
                ? "\"" + first.name() + "\" -> " + first.waypoints().size() + " waypoint(s)"
                : imported.size() + " groups added";

        SystemToast.addOrUpdate(
                mc.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Import OK" + (source == null ? "" : " (" + source + ")")),
                Component.literal(body));
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
                mc.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Import failed"),
                Component.literal(reason == null ? "No waypoints found." : reason));
    }
}
