package dev.ethan.waypointer.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers and polls the mod's keybinds.
 *
 * Three bindings today:
 *
 *   - Open Editor -- the primary way into the GUI.
 *   - Skip Waypoint -- advances the current active group(s) past their current
 *     waypoint. Useful for dungeon speedruns or when a waypoint is physically
 *     unreachable. Gated by {@link WaypointerConfig#skipWaypointKeybindEnabled()}
 *     so a player can neutralise an accidental conflict without clearing the bind.
 *   - Add Waypoint Here -- drops a waypoint at the player's position into the
 *     first active group (auto-creating one if the zone has none). Matches
 *     {@code /wp add} in behavior so muscle memory transfers between the command
 *     and the keybind.
 *
 * All three are registered under a single Waypointer category via the
 * identifier-based API so the vanilla controls screen groups them together.
 * None are bound by default (apart from Open Editor): the mod treats every
 * action that writes or mutates route state as opt-in.
 */
public final class WaypointerKeybinds {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "main"));

    private final KeyMapping openEditor;
    private final KeyMapping skipWaypoint;
    private final KeyMapping addWaypointHere;
    private final KeyMapping addTempWaypointHere;
    private final Runnable openGui;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public WaypointerKeybinds(Runnable openGui, ActiveGroupManager manager, WaypointerConfig config) {
        this.openGui = openGui;
        this.manager = manager;
        this.config = config;
        this.openEditor = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.open_editor",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                CATEGORY));
        // Unbound by default: skip is a destructive-ish shortcut (it mutates route
        // progress) and players should opt in by choosing a key, not discover it by
        // accident on first launch.
        this.skipWaypoint = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.skip_waypoint",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        // Also unbound by default. Adding a waypoint is non-destructive but it *does*
        // create persistent data, which we don't want triggering on whatever default
        // key we pick. Players bind it intentionally.
        this.addWaypointHere = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.add_waypoint_here",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        // Same opt-in story as the other creation keybinds. Uses the user's
        // last-picked mode + duration (stored in config) so a single tap drops
        // a temp without an intermediate picker: the editor button path is for
        // changing those defaults, the keybind is for fast repeat use.
        this.addTempWaypointHere = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.add_temp_waypoint_here",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        // consumeClick returns true at most once per press, so holding the key doesn't
        // spam new screens / repeated skips / repeated adds.
        while (openEditor.consumeClick()) openGui.run();
        while (skipWaypoint.consumeClick()) {
            if (config.skipWaypointKeybindEnabled()) skipCurrentWaypoint(mc);
        }
        while (addWaypointHere.consumeClick()) addWaypointAtPlayer(mc);
        while (addTempWaypointHere.consumeClick()) addTempWaypointAtPlayer(mc);
    }

    /**
     * Advances the current waypoint in every active, non-complete group by one.
     *
     * Targeting every active group mirrors how {@link dev.ethan.waypointer.progression.ProximityTracker}
     * treats "active" -- the player is in this zone, these groups are enabled, so
     * progress on all of them is the single concept the UI already exposes. Picking
     * just one would force the player to remember which is "primary".
     */
    private void skipCurrentWaypoint(Minecraft mc) {
        int skipped = 0;
        boolean loop = config.restartRouteWhenComplete();
        for (WaypointGroup g : manager.activeGroups()) {
            if (g.isComplete() || g.isEmpty()) continue;
            g.advancePast(g.currentIndex());
            g.restartIfRouteCompleted(loop);
            skipped++;
        }
        if (skipped == 0) {
            showFeedback(mc, Component.literal("Nothing to skip -- no active route.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        // fireDataChanged re-caches activeGroups() and triggers autosave so the new
        // progress index survives a crash or /reload.
        manager.fireDataChanged();
    }

    /**
     * Drops a waypoint at the player's feet, reusing the first active group in the
     * current zone (or bootstrapping one if needed). Coordinates are floored to
     * match {@code /wp add} so the keybind and command produce identical data.
     */
    private void addWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        int x = (int) Math.floor(p.getX());
        int y = (int) Math.floor(p.getY());
        int z = (int) Math.floor(p.getZ());

        WaypointGroup target = manager.getOrCreateActiveGroup();
        target.add(new Waypoint(x, y, z, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        manager.fireDataChanged();

        showFeedback(mc, Component.literal("Waypoint added to \"" + target.name()
                        + "\" at " + x + ", " + y + ", " + z)
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * Drops a temporary waypoint at the player's feet, using the user's last
     * picks for mode + duration (stored in config). Time-mode temps get their
     * expiry stamped from {@link System#currentTimeMillis()} at the moment of
     * creation; other modes ignore duration.
     *
     * <p>Like the regular add path, the target group is either the first
     * active group in the current zone or a freshly-created one -- temporary
     * waypoints live in the same groups as normal ones, they're just flagged.
     */
    private void addTempWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        int x = (int) Math.floor(p.getX());
        int y = (int) Math.floor(p.getY());
        int z = (int) Math.floor(p.getZ());

        int mode = clampTempMode(config.tempDefaultMode());
        int durationMin = Math.max(1, config.tempDefaultDurationMin());
        long expiresAt = mode == Waypoint.TEMP_TIME
                ? System.currentTimeMillis() + durationMin * 60_000L
                : 0L;

        WaypointGroup target = manager.getOrCreateActiveGroup();
        target.add(Waypoint.at(x, y, z).withTemp(mode, expiresAt));
        manager.fireDataChanged();

        showFeedback(mc, Component.literal("Temp (" + tempModeName(mode) + ") added at "
                        + x + ", " + y + ", " + z).withStyle(ChatFormatting.AQUA));
    }

    private static int clampTempMode(int v) {
        if (v < Waypoint.TEMP_TIME || v > Waypoint.TEMP_UNTIL_LEAVE) return Waypoint.TEMP_UNTIL_REACHED;
        return v;
    }

    private static String tempModeName(int mode) {
        return switch (mode) {
            case Waypoint.TEMP_TIME          -> "TIME";
            case Waypoint.TEMP_UNTIL_REACHED -> "REACH";
            case Waypoint.TEMP_UNTIL_LEAVE   -> "LEAVE";
            default -> "?";
        };
    }

    /**
     * Writes a transient confirmation to the action bar. Keybind-driven actions
     * need some acknowledgement or players can't tell a missed keypress from a
     * silently-blocked one (wrong zone, no groups, etc). The action bar is less
     * intrusive than chat spam for a potentially high-frequency action.
     */
    private static void showFeedback(Minecraft mc, Component msg) {
        if (mc.gui == null) return;
        mc.gui.setOverlayMessage(msg, false);
    }
}
