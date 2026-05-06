package dev.ethan.waypointer.input;

import dev.ethan.waypointer.chat.WaypointerChatFeedback;
import dev.ethan.waypointer.config.WaypointerConfig;
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
 * next tick. It also keeps the existing skip-ahead auto-disable behavior when
 * that setting is enabled.
 */
public final class WaypointAddFlow {

    /**
     * Where post-add status lines are shown. Keybind-driven adds use the action
     * bar so rapid use does not flood chat; commands and GUI use chat.
     */
    public enum UserFeedbackSurface {
        CHAT,
        ACTION_BAR
    }

    private final WaypointerConfig config;

    public WaypointAddFlow(WaypointerConfig config) {
        this.config = config;
    }

    /**
     * Call immediately after a new waypoint has been appended or inserted into
     * {@code group}. Temp groups are intentionally excluded because they never
     * participate in skip-ahead to begin with, and the toast would be a lie.
     */
    public void afterWaypointAdded(WaypointGroup group, int waypointIndex) {
        afterWaypointAdded(group, waypointIndex, UserFeedbackSurface.CHAT);
    }

    /**
     * Same as {@link #afterWaypointAdded(WaypointGroup, int)} but controls whether
     * player-visible lines go to chat or the transient action bar overlay.
     */
    public void afterWaypointAdded(WaypointGroup group, int waypointIndex, UserFeedbackSurface surface) {
        if (group == null) return;
        if (group.temp()) return;

        WaypointGroup.LoadMode loadModeBefore = group.loadMode();
        group.focusNewWaypoint(waypointIndex);
        boolean switchedToStatic = loadModeBefore == WaypointGroup.LoadMode.SEQUENCE;
        if (switchedToStatic) {
            group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        }
        showWaypointAddedMessage(group, waypointIndex, surface);
        if (switchedToStatic) {
            showRouteStaticMessage(group.name(), waypointIndex, surface);
        } else if (loadModeBefore == WaypointGroup.LoadMode.STATIC) {
            showCurrentWaypointFocusedMessage(group.name(), waypointIndex, surface);
        }

        if (!config.disableGroupSkipAheadOnWaypointAdd()) return;
        if (group.skipAheadEnabled()) {
            group.setSkipAheadEnabled(false);
            showSkipAheadDisabledToast(group.name());
        }
    }

    private static void showWaypointAddedMessage(
            WaypointGroup group, int waypointIndex, UserFeedbackSurface surface) {
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;
        Waypoint waypoint = group.get(waypointIndex);
        showUserFeedback(Component.literal("Added waypoint " + waypointIndex + " at "
                + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z())
                .withStyle(ChatFormatting.GREEN), surface);
    }

    private static void showRouteStaticMessage(
            String groupName, int waypointIndex, UserFeedbackSurface surface) {
        showUserFeedback(Component.literal("\"" + groupName + "\" is now static; current waypoint set to "
                + waypointIndex + ".").withStyle(ChatFormatting.YELLOW), surface);
    }

    private static void showCurrentWaypointFocusedMessage(
            String groupName, int waypointIndex, UserFeedbackSurface surface) {
        showUserFeedback(Component.literal("\"" + groupName + "\" current waypoint set to "
                + waypointIndex + ".").withStyle(ChatFormatting.YELLOW), surface);
    }

    private static void showUserFeedback(Component message, UserFeedbackSurface surface) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (surface == UserFeedbackSurface.ACTION_BAR) {
            if (mc.gui == null) return;
            mc.gui.setOverlayMessage(message, false);
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null) return;
        player.displayClientMessage(WaypointerChatFeedback.suppress(message), false);
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
