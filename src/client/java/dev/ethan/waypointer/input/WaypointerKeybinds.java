package dev.ethan.waypointer.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import dev.ethan.waypointer.screen.AddNamedWaypointScreen;
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
 * Seven bindings today:
 *
 *   - Open Editor -- the primary way into the GUI.
 *   - Skip Waypoint -- advances the current active group(s) past their current
 *     waypoint. Useful for dungeon speedruns or when a waypoint is physically
 *     unreachable. Unbound by default; players who don't want it just don't
 *     bind the key.
 *   - Create Waypoint -- drops a waypoint at the player's position into the
 *     first active group (auto-creating one if the zone has none). Matches
 *     {@code /wp add} in behavior so muscle memory transfers between the command
 *     and the keybind.
 *   - Create Named Waypoint -- opens a one-field prompt, then creates the
 *     waypoint at the player's current position with that name.
 *   - Reposition Mode: Add Waypoint -- pick a block in-world, then add an
 *     unnamed waypoint there.
 *   - Reposition Mode: Add Named Waypoint -- pick a block in-world, then name
 *     the waypoint before adding it.
 *   - Add Temp Waypoint Here -- drops a temporary waypoint using the user's
 *     default expiry mode and duration.
 *
 * All bindings are registered under a single Waypointer category via the
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
    private final KeyMapping addNamedWaypointHere;
    private final KeyMapping repositionAddWaypoint;
    private final KeyMapping repositionAddNamedWaypoint;
    private final KeyMapping addTempWaypointHere;
    private final Runnable openGui;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointAddFlow addFlow;

    public WaypointerKeybinds(Runnable openGui, ActiveGroupManager manager, WaypointerConfig config) {
        this.openGui = openGui;
        this.manager = manager;
        this.config = config;
        this.addFlow = new WaypointAddFlow();
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
        this.addNamedWaypointHere = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.add_named_waypoint_here",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        this.repositionAddWaypoint = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.reposition_add_waypoint",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        this.repositionAddNamedWaypoint = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.reposition_add_named_waypoint",
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
        if (mc.screen != null) {
            drainWaypointKeybindClicks();
            return;
        }

        // consumeClick returns true at most once per press, so holding the key doesn't
        // spam new screens / repeated skips / repeated adds.
        while (openEditor.consumeClick()) {
            openGui.run();
            drainWaypointKeybindClicks();
            return;
        }
        while (skipWaypoint.consumeClick()) skipCurrentWaypoint(mc);
        while (addWaypointHere.consumeClick()) addWaypointAtPlayer(mc);
        while (addNamedWaypointHere.consumeClick()) {
            openNamedWaypointPrompt(mc);
            drainWaypointKeybindClicks();
            return;
        }
        while (repositionAddWaypoint.consumeClick()) {
            WaypointRepositionMode.startAdd(manager, config, false);
            drainWaypointKeybindClicks();
            return;
        }
        while (repositionAddNamedWaypoint.consumeClick()) {
            WaypointRepositionMode.startAdd(manager, config, true);
            drainWaypointKeybindClicks();
            return;
        }
        while (addTempWaypointHere.consumeClick()) addTempWaypointAtPlayer(mc);
    }

    /**
     * Text-entry screens still feed bound keys into {@link KeyMapping}. Draining
     * prevents a typed waypoint name from replaying later as route/temp actions.
     */
    private void drainWaypointKeybindClicks() {
        while (openEditor.consumeClick()) {}
        while (skipWaypoint.consumeClick()) {}
        while (addWaypointHere.consumeClick()) {}
        while (addNamedWaypointHere.consumeClick()) {}
        while (repositionAddWaypoint.consumeClick()) {}
        while (repositionAddNamedWaypoint.consumeClick()) {}
        while (addTempWaypointHere.consumeClick()) {}
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
     * Drops a waypoint at the configured player-relative position, reusing the
     * first active group in the current zone (or bootstrapping one if needed).
     * This matches {@code /wp add} so keybind and command muscle memory align.
     */
    private void addWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);

        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        target.add(new Waypoint(
                pos.x(), pos.y(), pos.z(), "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        addFlow.afterWaypointAdded(target, target.size() - 1);
        manager.fireDataChanged();
    }

    private void openNamedWaypointPrompt(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        AddNamedWaypointScreen.openAt(
                null, manager, config, target, pos.x(), pos.y(), pos.z());
    }

    /**
     * Drops a temporary waypoint at the configured player-relative position,
     * using the user's last picks for mode + duration (stored in config).
     * Time-mode temps get their expiry stamped from {@link System#currentTimeMillis()}
     * at the moment of creation; other modes ignore duration.
     *
     * <p>Temps go into the per-zone temp bucket (see
     * {@link ActiveGroupManager#getOrCreateTempGroup()}), not the player's
     * actual route. Mixing temps into a real route used to cause visible churn
     * (gradient recolouring every time a temp dropped, proximity advancing
     * past temps as if they were route steps); the dedicated bucket keeps
     * those concerns completely separate.
     */
    private void addTempWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);

        int mode = Waypoint.normalizeTempMode(config.tempDefaultMode());
        int durationMin = Math.max(1, config.tempDefaultDurationMin());
        long expiresAt = mode == Waypoint.TEMP_TIME
                ? System.currentTimeMillis() + durationMin * 60_000L
                : 0L;

        WaypointGroup target = manager.getOrCreateTempGroup();
        target.add(Waypoint.at(pos.x(), pos.y(), pos.z()).withTemp(mode, expiresAt));
        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(target, target.size() - 1);
        }
        manager.fireDataChanged();

        showFeedback(mc, Component.literal("Temp (" + Waypoint.tempModeName(mode) + ") added at "
                + pos.x() + ", " + pos.y() + ", " + pos.z()).withStyle(ChatFormatting.AQUA));
    }

    private PlayerWaypointPlacement.BlockPosition playerWaypointPosition(LocalPlayer player) {
        return PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
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
