package com.babbur.waypointer.input;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import com.babbur.waypointer.progression.ProximityTracker;
import com.babbur.waypointer.screen.AddNamedWaypointScreen;
import com.babbur.waypointer.screen.WaypointerGuiScreens;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers and polls the mod's keybinds.
 *
 * Ten bindings today:
 *
 *   - Open Waypointer GUI -- the primary way into the GUI.
 *   - Add Waypoint -- drops a waypoint at the player's position into the
 *     first active group (auto-creating one if the zone has none). Matches
 *     {@code /wp add} in behavior so muscle memory transfers between the command
 *     and the keybind.
 *   - Add Named Waypoint -- opens a one-field prompt, then creates the
 *     waypoint at the player's current position with that name.
 *   - Add Temp Waypoint at Player -- drops a temporary waypoint using the
 *     user's default expiry mode and duration.
 *   - Add Waypoint Where Looking -- pick a block in-world, then name it and
 *     optionally make it a SubWP or small SubWP.
 *   - Skip Waypoint -- advances the current active group(s) past their current
 *     waypoint. Useful for dungeon speedruns or when a waypoint is physically
 *     unreachable. Unbound by default; players who don't want it just don't
 *     bind the key.
 *   - Unskip Waypoint -- moves route progress back one waypoint, the
 *     inverse of Skip Waypoint. Also unbound by default because it mutates
 *     route progress.
 *   - Enter Edit Mode -- explicitly enables the persistent world waypoint
 *     picker where left-clicking an existing waypoint starts moving it.
 *   - Exit Edit Mode -- explicitly disables persistent edit mode.
 *   - Toggle Edit Mode -- flips persistent edit mode for players who prefer one
 *     bind instead of separate enter/exit binds.
 * All bindings are registered under a single Waypointer category via the
 * identifier-based API so the vanilla controls screen groups them together.
 * Most bindings are unbound by default so players opt in to actions that mutate
 * route data or change edit mode.
 */
public final class WaypointerKeybinds {

    static final String CATEGORY_TRANSLATION_KEY = "key.category." + Waypointer.MOD_ID + ".main";
    static final String OPEN_EDITOR_TRANSLATION_KEY = "key.waypointer.open_editor";
    static final String ADD_WAYPOINT_HERE_TRANSLATION_KEY = "key.waypointer.add_waypoint_here";
    static final String ADD_NAMED_WAYPOINT_HERE_TRANSLATION_KEY = "key.waypointer.add_named_waypoint_here";
    static final String ADD_TEMP_WAYPOINT_HERE_TRANSLATION_KEY = "key.waypointer.add_temp_waypoint_here";
    static final String SKIP_WAYPOINT_TRANSLATION_KEY = "key.waypointer.skip_waypoint";
    static final String UNSKIP_WAYPOINT_TRANSLATION_KEY = "key.waypointer.previous_waypoint";
    static final String ENTER_EDIT_MODE_TRANSLATION_KEY = "key.waypointer.enter_edit_mode";
    static final String EXIT_EDIT_MODE_TRANSLATION_KEY = "key.waypointer.exit_edit_mode";
    static final String TOGGLE_EDIT_MODE_TRANSLATION_KEY = "key.waypointer.toggle_edit_mode";
    static final String REPOSITION_ADD_WAYPOINT_TRANSLATION_KEY = "key.waypointer.reposition_add_waypoint";
    static final int OPEN_EDITOR_DEFAULT_KEY = GLFW.GLFW_KEY_U;
    static final int UNBOUND_DEFAULT_KEY = InputConstants.UNKNOWN.getValue();
    static final List<String> KEYBIND_TRANSLATION_KEYS = List.of(
            OPEN_EDITOR_TRANSLATION_KEY,
            ADD_WAYPOINT_HERE_TRANSLATION_KEY,
            ADD_NAMED_WAYPOINT_HERE_TRANSLATION_KEY,
            ADD_TEMP_WAYPOINT_HERE_TRANSLATION_KEY,
            SKIP_WAYPOINT_TRANSLATION_KEY,
            UNSKIP_WAYPOINT_TRANSLATION_KEY,
            ENTER_EDIT_MODE_TRANSLATION_KEY,
            EXIT_EDIT_MODE_TRANSLATION_KEY,
            TOGGLE_EDIT_MODE_TRANSLATION_KEY,
            REPOSITION_ADD_WAYPOINT_TRANSLATION_KEY);
    static final List<Integer> KEYBIND_DEFAULT_KEYS = List.of(
            OPEN_EDITOR_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY,
            UNBOUND_DEFAULT_KEY);

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "main"));
    private static final Component HELP_CONVERT_SECRETS_FIRST = Component.translatable(
            "waypointer.input.edit_mode.convert_first")
            .withStyle(ChatFormatting.YELLOW);
    private static KeyMapping registeredOpenEditor;
    private static KeyMapping registeredExitEditMode;

    private final KeyMapping openEditor;
    private final KeyMapping addWaypointHere;
    private final KeyMapping addNamedWaypointHere;
    private final KeyMapping addTempWaypointHere;
    private final KeyMapping skipWaypoint;
    private final KeyMapping unskipWaypoint;
    private final KeyMapping enterEditMode;
    private final KeyMapping exitEditMode;
    private final KeyMapping toggleEditMode;
    private final KeyMapping addWaypointWhereLooking;
    private final Runnable openGui;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointAddFlow addFlow;

    public WaypointerKeybinds(Runnable openGui, ActiveGroupManager manager, WaypointerConfig config) {
        this.openGui = openGui;
        this.manager = manager;
        this.config = config;
        this.addFlow = new WaypointAddFlow();
        this.openEditor = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                OPEN_EDITOR_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                OPEN_EDITOR_DEFAULT_KEY,
                CATEGORY));
        registeredOpenEditor = this.openEditor;
        // Also unbound by default. Adding a waypoint is non-destructive but it *does*
        // create persistent data, which we don't want triggering on whatever default
        // key we pick. Players bind it intentionally.
        this.addWaypointHere = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                ADD_WAYPOINT_HERE_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        this.addNamedWaypointHere = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                ADD_NAMED_WAYPOINT_HERE_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        // Same opt-in story as the other creation keybinds. Uses the user's
        // last-picked mode + duration (stored in config) so a single tap drops
        // a temp without an intermediate picker: the editor button path is for
        // changing those defaults, the keybind is for fast repeat use.
        this.addTempWaypointHere = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                ADD_TEMP_WAYPOINT_HERE_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        // Unbound by default: skip is a destructive-ish shortcut (it mutates route
        // progress) and players should opt in by choosing a key, not discover it by
        // accident on first launch.
        this.skipWaypoint = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                SKIP_WAYPOINT_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        this.unskipWaypoint = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                UNSKIP_WAYPOINT_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        this.enterEditMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                ENTER_EDIT_MODE_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        this.exitEditMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                EXIT_EDIT_MODE_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        registeredExitEditMode = this.exitEditMode;
        this.toggleEditMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                TOGGLE_EDIT_MODE_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
        this.addWaypointWhereLooking = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                REPOSITION_ADD_WAYPOINT_TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                UNBOUND_DEFAULT_KEY,
                CATEGORY));
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!WaypointerGuiScreens.owns(screen)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((ownedScreen, event) -> {
                if (!isOpenEditorKey(event)) return true;
                if (focusedEditBox(ownedScreen)) return true;
                WaypointerClient.openGui();
                return false;
            });
        });
    }

    /** Live bindings and conflicts for the troubleshooting report. */
    public List<DebugBinding> debugSnapshot() {
        List<KeyMapping> mappings = List.of(
                openEditor,
                addWaypointHere,
                addNamedWaypointHere,
                addTempWaypointHere,
                skipWaypoint,
                unskipWaypoint,
                enterEditMode,
                exitEditMode,
                toggleEditMode,
                addWaypointWhereLooking);
        List<DebugBinding> snapshot = new ArrayList<>(mappings.size());
        KeyMapping[] allMappings = Minecraft.getInstance().options.keyMappings;
        for (KeyMapping mapping : mappings) {
            List<String> conflicts = new ArrayList<>();
            for (KeyMapping candidate : allMappings) {
                if (candidate != mapping && mapping.same(candidate)) {
                    conflicts.add(candidate.getName());
                }
            }
            snapshot.add(new DebugBinding(
                    mapping.getName(),
                    mapping.isUnbound() ? "Unbound" : mapping.getTranslatedKeyMessage().getString(),
                    mapping.isUnbound(),
                    List.copyOf(conflicts)));
        }
        return List.copyOf(snapshot);
    }

    public record DebugBinding(String translationKey, String boundKey,
                               boolean unbound, List<String> conflicts) {
    }

    static boolean isOpenEditorKey(KeyEvent event) {
        if (event == null) return false;
        KeyMapping mapping = registeredOpenEditor;
        if (mapping != null) return mapping.matches(event);
        return event.key() == OPEN_EDITOR_DEFAULT_KEY;
    }

    static boolean focusedEditBox(Screen screen) {
        if (screen == null) return false;
        GuiEventListener focused = screen.getFocused();
        return focused instanceof EditBox editBox && editBox.isFocused();
    }

    public static String exitEditModeKeyName() {
        KeyMapping mapping = registeredExitEditMode;
        if (mapping == null || mapping.isUnbound()) return "";
        return mapping.getTranslatedKeyMessage().getString().trim();
    }

    private void onTick(Minecraft mc) {
        Screen currentScreen = MinecraftCompat.screen(mc);
        if (currentScreen != null) {
            if (focusedEditBox(currentScreen)) {
                drainWaypointKeybindClicks();
                return;
            }
            while (openEditor.consumeClick()) {
                if (WaypointerGuiScreens.owns(currentScreen)) {
                    openGui.run();
                }
                drainWaypointKeybindClicks();
                return;
            }
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
        while (addWaypointHere.consumeClick()) addWaypointAtPlayer(mc);
        while (addNamedWaypointHere.consumeClick()) {
            openNamedWaypointPrompt(mc);
            drainWaypointKeybindClicks();
            return;
        }
        while (addTempWaypointHere.consumeClick()) addTempWaypointAtPlayer(mc);
        while (skipWaypoint.consumeClick()) skipCurrentWaypoint();
        while (unskipWaypoint.consumeClick()) unskipCurrentWaypoint();
        while (enterEditMode.consumeClick()) {
            WaypointRepositionMode.setEditModeEnabled(manager, config, true);
            drainWaypointKeybindClicks();
            return;
        }
        while (exitEditMode.consumeClick()) {
            WaypointRepositionMode.setEditModeEnabled(manager, config, false);
            drainWaypointKeybindClicks();
            return;
        }
        while (toggleEditMode.consumeClick()) {
            WaypointRepositionMode.toggleEditMode(manager, config);
            drainWaypointKeybindClicks();
            return;
        }
        while (addWaypointWhereLooking.consumeClick()) {
            WaypointRepositionMode.openAddWhereLooking(manager, config);
            drainWaypointKeybindClicks();
            return;
        }
    }

    private void drainWaypointKeybindClicks() {
        while (openEditor.consumeClick()) {}
        while (addWaypointHere.consumeClick()) {}
        while (addNamedWaypointHere.consumeClick()) {}
        while (addTempWaypointHere.consumeClick()) {}
        while (skipWaypoint.consumeClick()) {}
        while (unskipWaypoint.consumeClick()) {}
        while (enterEditMode.consumeClick()) {}
        while (exitEditMode.consumeClick()) {}
        while (toggleEditMode.consumeClick()) {}
        while (addWaypointWhereLooking.consumeClick()) {}
    }

    private void skipCurrentWaypoint() {
        skipCurrentWaypointTargets(manager, config, System.currentTimeMillis());
    }

    public static int skipCurrentWaypointTargets(ActiveGroupManager manager, WaypointerConfig config,
                                                  long nowMillis) {
        int skipped = 0;
        boolean loop = config.restartRouteWhenComplete();
        boolean trackRouteTimes = config.routeTimesEnabled();
        for (WaypointGroup g : manager.activeGroups()) {
            if (g.isComplete() || g.isEmpty()) continue;
            advanceManualSkip(g, trackRouteTimes, nowMillis);
            g.restartIfRouteCompleted(shouldRestartCompletedRoute(g, loop));
            ProximityTracker.reportRouteCompletion(manager, g, trackRouteTimes);
            skipped++;
        }
        if (skipped == 0) return 0;
        // fireDataChanged re-caches activeGroups() and triggers autosave so the new
        // progress index survives a crash or /reload.
        manager.fireDataChanged();
        return skipped;
    }

    static void advanceManualSkip(WaypointGroup group, boolean trackRouteTimes, long nowMillis) {
        if (trackRouteTimes) {
            group.skipCurrentTimed(nowMillis);
            return;
        }
        group.resetRouteTiming();
        group.consumeRouteCompletion();
        group.advancePast(group.currentIndex());
    }

    private static boolean shouldRestartCompletedRoute(WaypointGroup group, boolean globalRestart) {
        if (group != null
                && !group.temp()
                && DungeonRoomData.definition(group.zoneId()) != null) {
            return false;
        }
        return globalRestart;
    }

    static boolean isCompletedDungeonRoomRoute(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.isComplete()
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    private void unskipCurrentWaypoint() {
        unskipCurrentWaypointTargets(manager);
    }

    public static int unskipCurrentWaypointTargets(ActiveGroupManager manager) {
        int moved = retreatPreviousWaypointTargets(manager);
        if (moved > 0) manager.fireDataChanged();
        return moved;
    }

    static int retreatPreviousWaypointTargets(ActiveGroupManager manager) {
        int moved = retreatGroups(manager.activeGroups());
        if (moved == 0 && !manager.tempWaypointFocusActive()) {
            moved = retreatGroups(manager.completedDungeonRoomGroupsInCurrentZone());
        }
        return moved;
    }

    private static int retreatGroups(List<WaypointGroup> groups) {
        int moved = 0;
        for (WaypointGroup g : groups) {
            if (g.isEmpty()) continue;
            if (g.retreatToPreviousTarget()) moved++;
        }
        return moved;
    }

    private void addWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (addBlockedByInstalledSecrets(mc)) return;

        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);

        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        int flags = WaypointRepositionMode.defaultDungeonEditFlags(target);
        // Stored dungeon-room routes keep room-local coordinates.
        target.add(DungeonRoomWaypointPlacement.toStoredWaypoint(target, new Waypoint(
                pos.x(), pos.y(), pos.z(), "", config.defaultWaypointColor(), flags, 0.0)));
        addFlow.afterWaypointAdded(target, target.size() - 1,
                config.showWaypointChatShareButtons());
        manager.fireDataChanged();
    }

    private void openNamedWaypointPrompt(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (addBlockedByInstalledSecrets(mc)) return;
        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        int flags = WaypointRepositionMode.defaultDungeonEditFlags(target);
        AddNamedWaypointScreen.openAt(
                null, manager, config, target, pos.x(), pos.y(), pos.z(), flags);
    }

    private boolean addBlockedByInstalledSecrets(Minecraft mc) {
        if (manager == null || manager.currentZone() == null) return false;
        if (!DungeonRoomRouteSync.secretsRequireConversion(manager, manager.currentZone().id())) {
            return false;
        }
        showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
        return true;
    }

    private static void showStatus(Minecraft mc, Component message) {
        if (mc == null || message == null) return;
        if (mc.player != null) {
            mc.player.sendSystemMessage(WaypointerChatFeedback.suppress(message));
        }
        if (mc.gui != null) {
            MinecraftCompat.setOverlayMessage(mc, message, false);
        }
    }

    private void addTempWaypointAtPlayer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        PlayerWaypointPlacement.BlockPosition pos = playerWaypointPosition(p);

        int mode = Waypoint.normalizeTempMode(config.tempDefaultMode());
        long expiresAt = config.defaultTempExpiresAtMillis(System.currentTimeMillis());

        WaypointGroup target = manager.getOrCreateTempGroup();
        target.add(Waypoint.at(pos.x(), pos.y(), pos.z())
                .withColor(config.defaultWaypointColor())
                .withTemp(mode, expiresAt));
        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(target, target.size() - 1);
        }
        manager.fireTransientDataChanged();
    }

    private PlayerWaypointPlacement.BlockPosition playerWaypointPosition(LocalPlayer player) {
        return PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
    }

}
