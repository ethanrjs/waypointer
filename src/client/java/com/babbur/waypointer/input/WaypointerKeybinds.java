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
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
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
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

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
            "waypointer.input.edit_mode.convert_definition_first")
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
        this.openEditor = register(OPEN_EDITOR_TRANSLATION_KEY, OPEN_EDITOR_DEFAULT_KEY);
        registeredOpenEditor = this.openEditor;
        this.addWaypointHere = register(ADD_WAYPOINT_HERE_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.addNamedWaypointHere = register(ADD_NAMED_WAYPOINT_HERE_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.addTempWaypointHere = register(ADD_TEMP_WAYPOINT_HERE_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.skipWaypoint = register(SKIP_WAYPOINT_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.unskipWaypoint = register(UNSKIP_WAYPOINT_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.enterEditMode = register(ENTER_EDIT_MODE_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.exitEditMode = register(EXIT_EDIT_MODE_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        registeredExitEditMode = this.exitEditMode;
        this.toggleEditMode = register(TOGGLE_EDIT_MODE_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
        this.addWaypointWhereLooking = register(REPOSITION_ADD_WAYPOINT_TRANSLATION_KEY, UNBOUND_DEFAULT_KEY);
    }

    private static KeyMapping register(String translationKey, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                translationKey, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
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
        return focused instanceof EditBox editBox && editBox.isFocused()
                || focused instanceof MultiLineEditBox multiLine && multiLine.isFocused();
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
        skipCurrentWaypointTargets(manager, config);
    }

    public static int skipCurrentWaypointTargets(ActiveGroupManager manager, WaypointerConfig config) {
        int skipped = 0;
        boolean loop = config.restartRouteWhenComplete();
        for (WaypointGroup g : manager.activeGroups()) {
            if (g.isComplete() || g.isEmpty()) continue;
            g.advancePast(g.currentIndex());
            g.restartIfRouteCompleted(shouldRestartCompletedRoute(g, loop));
            skipped++;
        }
        if (skipped == 0) return 0;
        manager.fireDataChanged();
        return skipped;
    }

    private static boolean shouldRestartCompletedRoute(WaypointGroup group, boolean globalRestart) {
        if (group != null
                && !group.temp()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
            return false;
        }
        return globalRestart;
    }

    static boolean isCompletedDungeonRoomRoute(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.isComplete()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
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
        return false;
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
