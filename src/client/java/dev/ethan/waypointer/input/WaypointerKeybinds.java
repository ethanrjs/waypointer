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
 * Eight bindings today:
 *
 *   - Open Editor -- the primary way into the GUI.
 *   - Skip Waypoint -- advances the current active group(s) past their current
 *     waypoint. Useful for dungeon speedruns or when a waypoint is physically
 *     unreachable. Unbound by default; players who don't want it just don't
 *     bind the key.
 *   - Previous Waypoint -- moves active route progress back one waypoint, the
 *     inverse of Skip Waypoint. Also unbound by default because it mutates
 *     route progress.
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
    private final KeyMapping previousWaypoint;
    private final KeyMapping addWaypointHere;
    private final KeyMapping addNamedWaypointHere;
    private final KeyMapping repositionAddWaypoint;
    private final KeyMapping repositionAddNamedWaypoint;
    private final KeyMapping addTempWaypointHere;
    private final Runnable openGui;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointAddFlow addFlow;

    /*[[AI-FN-DOC
Function:
WaypointerKeybinds constructor.
Purpose:
Create and register every Waypointer client keybind, then retain the collaborators needed when those bindings fire.
Why this exists:
Fabric key mappings are global client state and must be registered once through KeyBindingHelper before the tick listener starts polling them.
When to use:
Use during Waypointer client initialization after the active group manager and live config are available. Do not call repeatedly for the same client session because duplicate key mappings would appear in controls.
Inputs:
openGui is a non-null Runnable that opens the Waypointer editor; manager is the active group manager whose routes will be mutated; config is the live mutable config used for placement and route-loop settings.
Outputs:
Constructs a WaypointerKeybinds instance with registered KeyMapping objects. No explicit return value beyond the constructed instance.
Side effects:
Registers key mappings with Fabric's global keybind registry and creates a WaypointAddFlow helper for add-keybind post-processing.
Failure modes:
Null collaborators are not explicitly checked here and would fail later when a keybind action uses them; GLFW/InputConstants registration failures would come from the underlying Minecraft/Fabric APIs.
Important invariants:
Open Editor remains bound to U by default, all route-mutating and data-creating actions stay unbound by default, and Previous Waypoint is registered immediately after Skip Waypoint so controls settings list them together.
Internal logic:
Store collaborators, create the add-flow helper, register the editor binding, register skip and previous route-progress bindings, then register creation/reposition/temp bindings in their visible settings order.
Pseudocode:
save openGui, manager, and config fields
create addFlow
register open editor with default U
register skip waypoint unbound
register previous waypoint unbound directly after skip
register add waypoint unbound
register add named waypoint unbound
register reposition add waypoint unbound
register reposition add named waypoint unbound
register add temp waypoint unbound
Implementation notes:
The constructor keeps registration order close to the documentation order because vanilla controls settings use that order within the category.
AI self-check:
Verify every key translation exists in en_us.json and every mutating action remains opt-in.
]]*/
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
        this.previousWaypoint = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.waypointer.previous_waypoint",
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

    /*[[AI-FN-DOC
Function:
install
Purpose:
Attach Waypointer keybind polling to the Fabric client tick lifecycle.
Why this exists:
Minecraft key mappings expose consumed-click polling, so the mod needs a per-tick hook that can react to player input outside GUI screens.
When to use:
Call once after constructing WaypointerKeybinds during client setup. Do not call multiple times for the same instance because the tick handler would be registered multiple times.
Inputs:
No parameters. Uses this instance as the tick callback target.
Outputs:
No return value.
Side effects:
Registers this::onTick with ClientTickEvents.END_CLIENT_TICK.
Failure modes:
If called repeatedly, each registration would poll the same bindings and could duplicate effects. Fabric event registration itself is expected to succeed during normal client initialization.
Important invariants:
Keybinds are polled at end-of-client-tick so screen state and player state are current for that tick.
Internal logic:
Pass the onTick method reference to Fabric's END_CLIENT_TICK event.
Pseudocode:
register this::onTick on END_CLIENT_TICK
Implementation notes:
Keeping this as a separate method makes construction side-effect scope clear: constructor registers mappings, install registers polling.
AI self-check:
Verify no keybind action runs before install is called by the client initializer.
]]*/
    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    /*[[AI-FN-DOC
Function:
onTick
Purpose:
Poll registered keybind clicks and dispatch each key to the matching Waypointer action.
Why this exists:
KeyMapping.consumeClick is edge-triggered per client tick, so central polling prevents held keys from repeatedly mutating routes or opening duplicate screens.
When to use:
Called by Fabric at the end of every client tick after install registers it. Do not call manually except in tests with a fully mocked Minecraft instance.
Inputs:
mc is the current Minecraft client instance supplied by Fabric; it may have a null player or an open screen depending on client state.
Outputs:
No return value.
Side effects:
May open screens, add persistent or temporary waypoints, start reposition mode, mutate active route progress, autosave route data through manager.fireDataChanged, and drain pending click states.
Failure modes:
Actions that need a player return early when no player exists. When a screen is open, route/data keybinds are drained to avoid replay from text entry instead of being executed.
Important invariants:
Open Editor is handled before route/data mutations, Previous Waypoint stays immediately after Skip Waypoint, and screen-opening actions drain all other pending Waypointer clicks before returning.
Internal logic:
If any screen is open, drain pending Waypointer clicks and stop. Otherwise consume editor clicks first, then skip, previous, add, named add, reposition add, reposition named add, and temp add clicks in registration order.
Pseudocode:
if mc.screen exists:
  drain Waypointer keybind clicks
  return
while open editor clicked:
  run openGui
  drain clicks
  return
while skip clicked, skip current waypoint
while previous clicked, go back to previous waypoint
while add waypoint clicked, add waypoint at player
while named add clicked:
  open naming prompt
  drain clicks
  return
while reposition add clicked:
  start unnamed reposition add flow
  drain clicks
  return
while reposition named add clicked:
  start named reposition add flow
  drain clicks
  return
while temp add clicked, add temp waypoint at player
Implementation notes:
The ordering mirrors the controls list and avoids running a later mutating action after an action opens a screen.
AI self-check:
Verify consumeClick loops do not allow held keys to repeat without new click events and every screen-opening branch drains pending clicks.
]]*/
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
        while (previousWaypoint.consumeClick()) goBackToPreviousWaypoint(mc);
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

    /*[[AI-FN-DOC
Function:
drainWaypointKeybindClicks
Purpose:
Consume pending Waypointer keybind click events without executing their actions.
Why this exists:
Text-entry screens can still feed bound keys into KeyMapping, and those clicks would otherwise replay as route mutations after the screen closes.
When to use:
Use whenever a GUI screen is open or immediately after opening a Waypointer screen from a keybind. Do not use during normal gameplay polling unless intentionally discarding clicks.
Inputs:
No parameters. Reads all KeyMapping fields on this instance.
Outputs:
No return value.
Side effects:
Mutates each KeyMapping's internal consumed-click count by draining it to zero.
Failure modes:
None expected; missing a key mapping here would let that binding replay after typing, so every registered binding must be listed.
Important invariants:
The drain order includes Previous Waypoint directly after Skip Waypoint and contains every Waypointer key mapping exactly once.
Internal logic:
Loop consumeClick for each key mapping until each returns false.
Pseudocode:
while open editor has clicks, consume them
while skip has clicks, consume them
while previous has clicks, consume them
while add has clicks, consume them
while add named has clicks, consume them
while reposition add has clicks, consume them
while reposition named add has clicks, consume them
while temp add has clicks, consume them
Implementation notes:
Empty loop bodies are intentional because the function's only job is clearing queued click state.
AI self-check:
Verify this list is updated whenever a new Waypointer keybind is registered.
]]*/
    private void drainWaypointKeybindClicks() {
        while (openEditor.consumeClick()) {}
        while (skipWaypoint.consumeClick()) {}
        while (previousWaypoint.consumeClick()) {}
        while (addWaypointHere.consumeClick()) {}
        while (addNamedWaypointHere.consumeClick()) {}
        while (repositionAddWaypoint.consumeClick()) {}
        while (repositionAddNamedWaypoint.consumeClick()) {}
        while (addTempWaypointHere.consumeClick()) {}
    }

    /*[[AI-FN-DOC
Function:
skipCurrentWaypoint
Purpose:
Advance every active route group past its current waypoint.
Why this exists:
Players need an opt-in shortcut for routes where the next waypoint is unreachable, already handled, or not relevant to the current run.
When to use:
Use only from the Skip Waypoint keybind during gameplay. Do not use for automatic proximity progression, which already calls WaypointGroup.advancePast through its own validation path.
Inputs:
mc is the current Minecraft client and is used only for action-bar feedback when no route can be skipped.
Outputs:
No return value.
Side effects:
Mutates currentIndex and active subwaypoint hold state on active groups, may restart completed groups based on config, fires manager.fireDataChanged to recache and autosave, and may show action-bar feedback.
Failure modes:
Empty or complete groups are ignored. If nothing can be skipped, a yellow action-bar message is shown instead of firing a data change.
Important invariants:
All active, non-complete groups advance together to match the active route model, and restartRouteWhenComplete is applied immediately after each skip advance.
Internal logic:
Read the route-loop setting, iterate active groups, skip empty or complete groups, advance each current index, apply completion restart, count mutations, then either show no-op feedback or fire the manager change hook.
Pseudocode:
skipped = 0
loop = config.restartRouteWhenComplete
for each active group:
  if group complete or empty, continue
  group.advancePast(group.currentIndex)
  group.restartIfRouteCompleted(loop)
  increment skipped
if skipped is zero:
  show no active route feedback
  return
manager.fireDataChanged
Implementation notes:
The method deliberately mutates all active groups instead of picking a primary group because rendering and proximity already treat them as the current active route set.
AI self-check:
Verify a key press cannot silently mutate nothing and successful skips are persisted.
]]*/
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

    /*[[AI-FN-DOC
Function:
goBackToPreviousWaypoint
Purpose:
Move every active route group back to its previous waypoint target.
Why this exists:
The Previous Waypoint keybind is the intentional inverse of Skip Waypoint, letting players undo an accidental skip or revisit the prior route step without opening the editor.
When to use:
Use only from the Previous Waypoint keybind during gameplay. Do not use for structural edits or explicit skip-to commands that should choose a specific target.
Inputs:
mc is the current Minecraft client and is used only for action-bar feedback when no active route has a previous waypoint.
Outputs:
No return value.
Side effects:
May mutate active route progress, clears proximity suppression through WaypointGroup.retreatToPreviousTarget, fires manager.fireDataChanged when anything moved, and may show no-op feedback.
Failure modes:
Empty groups and groups already at the first target are ignored. If every active group is at the start or no active route exists, a yellow action-bar message is shown.
Important invariants:
Completed routes can move back to their final target, and active groups move together just like Skip Waypoint.
Internal logic:
Iterate active groups, ask each non-empty group to retreat one target, count successful retreats, then either report no previous waypoint or fire the manager change hook.
Pseudocode:
moved = 0
for each active group:
  if group empty, continue
  if group.retreatToPreviousTarget returns true, increment moved
if moved is zero:
  show no previous waypoint feedback
  return
manager.fireDataChanged
Implementation notes:
The route-specific logic stays in WaypointGroup so keybind code does not duplicate subwaypoint, completion, or visual-hold rules.
AI self-check:
Verify completed routes are not filtered out before they can retreat and successful retreats are persisted.
]]*/
    private void goBackToPreviousWaypoint(Minecraft mc) {
        int moved = 0;
        for (WaypointGroup g : manager.activeGroups()) {
            if (g.isEmpty()) continue;
            if (g.retreatToPreviousTarget()) moved++;
        }
        if (moved == 0) {
            showFeedback(mc, Component.literal("Nothing to go back to -- no previous waypoint.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        manager.fireDataChanged();
    }

    /*[[AI-FN-DOC
Function:
addWaypointAtPlayer
Purpose:
Create a persistent waypoint at the player's configured player-relative position.
Why this exists:
The create-waypoint keybind should match /wp add behavior while avoiding repeated UI navigation for fast route authoring.
When to use:
Use from the Create Waypoint keybind when no naming prompt is needed. Do not use for temporary waypoints or block-target reposition placement flows.
Inputs:
mc is the current Minecraft client. mc.player may be null when the client is not in a world.
Outputs:
No return value.
Side effects:
May create or reuse an active route group, append a waypoint, update add-flow selection/focus, and fire manager.fireDataChanged for autosave and cache refresh.
Failure modes:
Returns without mutation when the local player is null.
Important invariants:
The waypoint uses PlayerWaypointPlacement so below-player config is honored, and new active groups inherit the global skip-ahead default.
Internal logic:
Read the local player, return if missing, compute the configured block position, get or create the active group, append a blank waypoint with default color, run add-flow post-processing, and fire data changed.
Pseudocode:
p = mc.player
if p is null, return
pos = playerWaypointPosition(p)
target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled)
target.add(new waypoint at pos with default color)
addFlow.afterWaypointAdded(target, last index)
manager.fireDataChanged
Implementation notes:
This path creates persistent route data; the keybind remains unbound by default so players opt in.
AI self-check:
Verify the added waypoint coordinates match command placement semantics.
]]*/
    private void addWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);

        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        target.add(new Waypoint(
                pos.x(), pos.y(), pos.z(), "", config.defaultWaypointColor(), 0, 0.0));
        addFlow.afterWaypointAdded(target, target.size() - 1);
        manager.fireDataChanged();
    }

    /*[[AI-FN-DOC
Function:
openNamedWaypointPrompt
Purpose:
Open the one-field naming screen for a waypoint at the player's configured position.
Why this exists:
Named waypoint creation needs a small UI prompt but should still use the same player-position placement and group selection as the unnamed add keybind.
When to use:
Use from the Create Named Waypoint keybind. Do not use for block-target reposition naming, which is handled by WaypointRepositionMode.
Inputs:
mc is the current Minecraft client. mc.player may be null when the client is not in a world.
Outputs:
No return value.
Side effects:
May create or reuse an active group and opens AddNamedWaypointScreen.
Failure modes:
Returns without opening a screen when the local player is null.
Important invariants:
The prompt receives the exact coordinates that would be used for an unnamed player-position add.
Internal logic:
Read the local player, return if missing, compute the configured position, get or create the active group, then open AddNamedWaypointScreen with no parent screen.
Pseudocode:
p = mc.player
if p is null, return
pos = playerWaypointPosition(p)
target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled)
AddNamedWaypointScreen.openAt(null, manager, config, target, pos.x, pos.y, pos.z)
Implementation notes:
The caller drains keybind clicks after opening this screen so typed names do not replay as keybind actions.
AI self-check:
Verify this does not add a waypoint until the prompt commits.
]]*/
    private void openNamedWaypointPrompt(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        AddNamedWaypointScreen.openAt(
                null, manager, config, target, pos.x(), pos.y(), pos.z());
    }

    /*[[AI-FN-DOC
Function:
addTempWaypointAtPlayer
Purpose:
Create a temporary waypoint at the player's configured player-relative position using the saved temp defaults.
Why this exists:
Players need a fast keybind for throwaway points without mixing temporary route markers into persistent route groups.
When to use:
Use from the Add Temp Waypoint Here keybind. Do not use for persistent route waypoints or chat-imported temporary waypoints that carry sender metadata.
Inputs:
mc is the current Minecraft client. mc.player may be null when the client is not in a world.
Outputs:
No return value.
Side effects:
May mutate the per-zone temp waypoint group, may focus the new temp waypoint, fires manager.fireDataChanged, and shows an action-bar confirmation.
Failure modes:
Returns without mutation when the local player is null. Invalid config temp mode/duration values are normalized or clamped before use.
Important invariants:
Temporary waypoints go into the dedicated temp bucket instead of the active persistent route, and time-based expiry is stamped from the current wall clock.
Internal logic:
Read the player, compute placement, normalize temp mode and duration, compute expiry for time mode, append a temp waypoint to the temp group, optionally focus it, fire data changed, and show feedback.
Pseudocode:
p = mc.player
if p is null, return
pos = playerWaypointPosition(p)
mode = normalized config temp mode
durationMin = max 1 and config duration
expiresAt = now plus duration if mode is time else 0
target = manager.getOrCreateTempGroup
target.add waypoint at pos with default color and temp metadata
if focus temp waypoints enabled, focus the new temp
manager.fireDataChanged
show action-bar confirmation
Implementation notes:
Keeping temp markers out of real routes avoids gradient churn and accidental proximity progression through temporary data.
AI self-check:
Verify mode and duration normalization happen before constructing the waypoint.
]]*/
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
        target.add(Waypoint.at(pos.x(), pos.y(), pos.z())
                .withColor(config.defaultWaypointColor())
                .withTemp(mode, expiresAt));
        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(target, target.size() - 1);
        }
        manager.fireDataChanged();

        showFeedback(mc, Component.literal("Temp (" + Waypoint.tempModeName(mode) + ") added at "
                + pos.x() + ", " + pos.y() + ", " + pos.z()).withStyle(ChatFormatting.AQUA));
    }

    /*[[AI-FN-DOC
Function:
playerWaypointPosition
Purpose:
Convert a player's current world position into the block position Waypointer should use for player-position waypoint creation.
Why this exists:
Persistent, named, and temporary add keybinds must share the same below-player placement semantics instead of duplicating coordinate rounding.
When to use:
Use when a keybind wants to create a waypoint at the player rather than at a targeted block. Do not use for reposition-mode block picking.
Inputs:
player is the non-null local player whose current double x/y/z coordinates should be converted.
Outputs:
Returns a PlayerWaypointPlacement.BlockPosition containing integer x, y, and z placement coordinates.
Side effects:
None.
Failure modes:
If player is null the caller would throw before entering this method; callers guard null player state before calling.
Important invariants:
The conversion delegates to PlayerWaypointPlacement.fromPlayer so config.placeNewWaypointsBelowPlayer is honored consistently.
Internal logic:
Read player x/y/z doubles and pass them with config to PlayerWaypointPlacement.fromPlayer.
Pseudocode:
return PlayerWaypointPlacement.fromPlayer(player.getX, player.getY, player.getZ, config)
Implementation notes:
This small helper keeps coordinate policy in one place without introducing hidden state.
AI self-check:
Verify all player-position keybinds call this helper.
]]*/
    private PlayerWaypointPlacement.BlockPosition playerWaypointPosition(LocalPlayer player) {
        return PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
    }

    /*[[AI-FN-DOC
Function:
showFeedback
Purpose:
Display a transient keybind result message in the Minecraft action bar.
Why this exists:
Keybind-driven actions need lightweight acknowledgement so players can tell a missed press from a blocked action without adding chat spam.
When to use:
Use for immediate keybind feedback, especially no-op or temporary waypoint confirmations. Do not use for persistent chat logs or multi-line command output.
Inputs:
mc is the current Minecraft client; msg is the component to show and should already include any desired styling.
Outputs:
No return value.
Side effects:
Calls Minecraft's GUI overlay message API when the GUI exists.
Failure modes:
If mc.gui is null, the method returns without displaying anything.
Important invariants:
Feedback is transient and does not write to chat history.
Internal logic:
Guard missing GUI, then set the overlay message with animateColor=false.
Pseudocode:
if mc.gui is null, return
mc.gui.setOverlayMessage(msg, false)
Implementation notes:
The action bar is intentionally used for potentially high-frequency keybinds.
AI self-check:
Verify callers pass concise messages that fit the action bar.
]]*/
    private static void showFeedback(Minecraft mc, Component msg) {
        if (mc.gui == null) return;
        mc.gui.setOverlayMessage(msg, false);
    }
}
