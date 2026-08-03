package com.babbur.waypointer.input;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonItemIdentity;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.render.RenderHelpers;
import com.babbur.waypointer.render.RenderSubmission;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.babbur.waypointer.screen.AddNamedWaypointScreen;
import com.babbur.waypointer.screen.GroupEditScreen;
import com.babbur.waypointer.screen.WaypointerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * One-shot "pick a block in the world" mode for waypoint edits.
 *
 * <p>The editor starts this from Shift + left-click on a waypoint row, and keybinds
 * can start the same picker for creating new waypoints at the targeted block. While
 * active we close the GUI, outline the currently-targeted block, intercept left
 * click to commit, and intercept right click to cancel without placing or using
 * items.
 *
 * <p>Persistent edit mode is different from a one-shot placement session: left
 * click selects an existing visible waypoint for movement, and right-click adds
 * a waypoint at the targeted block without server interaction.
 */
public final class WaypointRepositionMode {

    private static final int OUTLINE_COLOR = 0x4FB3C4;
    private static final float OUTLINE_ALPHA = 0.95f;
    private static final float OUTLINE_WIDTH = 4.0f;
    private static final double OUTLINE_EXPAND = 0.002;
    private static final double PRECISE_SMALL_SIZE = 1.0 / 16.0;
    private static final double EDIT_PICK_RANGE = 512.0;
    private static final double EDIT_PICK_PADDING = 0.18;
    private static final double RAY_AXIS_EPSILON = 1.0E-7;
    private static final Component HELP_ADD_WHERE_LOOKING = Component.translatable(
            "waypointer.input.reposition.add_named")
            .withStyle(ChatFormatting.AQUA);
    private static final Component HELP_EDIT_ON = Component.translatable(
            "waypointer.input.edit_mode.enabled")
            .withStyle(ChatFormatting.AQUA);
    private static final Component HELP_EDIT_OFF = Component.translatable(
            "waypointer.input.edit_mode.disabled")
            .withStyle(ChatFormatting.YELLOW);
    private static final Component HELP_EDIT_UNAVAILABLE = Component.translatable(
            "waypointer.input.edit_mode.unavailable")
            .withStyle(ChatFormatting.YELLOW);
    private static final Component HELP_EDIT_NO_BLOCK_TARGET = Component.translatable(
            "waypointer.input.edit_mode.no_block")
            .withStyle(ChatFormatting.YELLOW);
    private static final Component HELP_EDIT_REMOVED = Component.translatable(
            "waypointer.input.edit_mode.removed")
            .withStyle(ChatFormatting.AQUA);
    private static final Component HELP_CONVERT_SECRETS_FIRST = Component.translatable(
            "waypointer.input.edit_mode.convert_first")
            .withStyle(ChatFormatting.YELLOW);

    private static Session active;
    private static ActiveGroupManager editManager;
    private static WaypointerConfig editConfig;
    /**
     * Id of the persistent route selected when Edit Mode was opened from its
     * editor. A null id keeps the keybind and command behaviour, which targets
     * the normal active route for the player's current zone.
     */
    private static String editTargetGroupId;
    private static ClientLevel lastEditModeActionLevel;
    private static long lastEditModeActionGameTime = Long.MIN_VALUE;
    private static boolean editModeEnabled;
    private static WaypointGroup lastEditSelectionGroup;
    private static int lastEditSelectionIndex = -1;

    private WaypointRepositionMode() {}

    public static void install() {
        ClientPreAttackCallback.EVENT.register(
                (client, player, clickCount) -> {
            if (active != null) {
                if (clickCount == 0) return true;
                commit(client);
                return true;
            }
            if (!editModeEnabled) return false;
            return handleEditModeAttack(client, clickCount);
        });
        UseItemCallback.EVENT.register(
                (player, world, hand) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;

            Minecraft mc = Minecraft.getInstance();
            if (active != null) {
                return handleEditModeRightClick(mc, player.getItemInHand(hand));
            }
            if (!editModeEnabled) return InteractionResult.PASS;
            return handleEditModeRightClick(mc, player.getItemInHand(hand));
        });
        UseBlockCallback.EVENT.register(
                (player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;

            Minecraft mc = Minecraft.getInstance();
            if (active != null) {
                return handleEditModeRightClick(mc, player.getItemInHand(hand));
            }
            if (!editModeEnabled) return InteractionResult.PASS;
            return handleEditModeRightClick(mc, player.getItemInHand(hand));
        });
        ClientTickEvents.END_CLIENT_TICK.register(WaypointRepositionMode::onTick);
        LevelRenderEvents.COLLECT_SUBMITS.register(WaypointRepositionMode::renderOutline);
    }

    public static boolean isEditModeEnabled() {
        return editModeEnabled;
    }

    public static boolean toggleEditMode(ActiveGroupManager manager, WaypointerConfig config) {
        setEditModeEnabled(manager, config, !editModeEnabled);
        return editModeEnabled;
    }

    /** Enable or disable Edit Mode for the route currently open in its editor. */
    public static boolean toggleEditMode(ActiveGroupManager manager, WaypointerConfig config,
                                         WaypointGroup target) {
        setEditModeEnabled(manager, config, target, !editModeEnabled);
        return editModeEnabled;
    }

    public static void setEditModeEnabled(ActiveGroupManager manager, WaypointerConfig config,
                                           boolean enabled) {
        setEditModeEnabled(manager, config, null, enabled);
    }

    /**
     * Configure Edit Mode and, when supplied, keep writes bound to the
     * persistent route that opened it rather than choosing another active
     * route or creating a new one.
     */
    public static void setEditModeEnabled(ActiveGroupManager manager, WaypointerConfig config,
                                          WaypointGroup target, boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        boolean wasEnabled = editModeEnabled;
        lastEditModeActionLevel = null;
        lastEditModeActionGameTime = Long.MIN_VALUE;
        clearEditSelectionCycle();
        if (enabled) {
            if (manager == null || config == null) {
                editModeEnabled = false;
                editManager = null;
                editConfig = null;
                editTargetGroupId = null;
                active = null;
                showStatus(mc, HELP_EDIT_UNAVAILABLE);
                return;
            }
            editManager = manager;
            editConfig = config;
            if (target != null || !wasEnabled) {
                editTargetGroupId = target == null ? null : target.id();
            }
            editModeEnabled = true;
            showStatus(mc, HELP_EDIT_ON);
            if (!wasEnabled) {
                playEditSound(mc, config);
            }
            return;
        }

        WaypointerConfig soundConfig = config == null ? editConfig : config;
        editModeEnabled = false;
        editManager = null;
        editConfig = null;
        editTargetGroupId = null;
        active = null;
        showStatus(mc, HELP_EDIT_OFF);
        if (wasEnabled) {
            playEditSound(mc, soundConfig);
        }
    }

    private static void clearEditSelectionCycle() {
        lastEditSelectionGroup = null;
        lastEditSelectionIndex = -1;
    }

    public static void start(ActiveGroupManager manager, WaypointerConfig config,
                             WaypointGroup group, int waypointIndex) {
        startMove(manager, config, group, waypointIndex, true);
    }

    private static void startMove(ActiveGroupManager manager, WaypointerConfig config,
                                  WaypointGroup group, int waypointIndex,
                                  boolean reopenEditorAfterCommit) {
        if (manager == null || config == null || group == null) return;
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;

        Minecraft mc = Minecraft.getInstance();
        // A definition-backed mirror (downloaded secrets, no user route) has no
        // stored group to write the move into; the edit would silently vanish
        // on the mirror's next rebuild.
        if (DungeonRoomRouteSync.durableEditTarget(manager, group) == null) {
            showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
            return;
        }
        Waypoint waypoint = group.get(waypointIndex);
        boolean preciseSmallPlacement = waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
        active = new Session(manager, config, group, waypointIndex,
                Mode.MOVE_EXISTING, preciseSmallPlacement, reopenEditorAfterCommit);
        MinecraftCompat.setScreen(mc, null);
        showHelp(mc);
    }

    public static void startAddWhereLooking(ActiveGroupManager manager, WaypointerConfig config) {
        startAddSession(manager, config, Mode.ADD_WHERE_LOOKING, false);
    }

    /** Capture the current crosshair target and open the naming screen immediately. */
    public static void openAddWhereLooking(ActiveGroupManager manager, WaypointerConfig config) {
        if (manager == null || config == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (addBlockedByInstalledSecrets(mc, manager)) return;

        BlockPos pos = targetedBlock(mc);
        PreciseTarget precise = targetedPrecise(mc);
        if (pos == null || precise == null) {
            showStatus(mc, HELP_EDIT_NO_BLOCK_TARGET);
            return;
        }

        WaypointGroup group = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        int flags = defaultDungeonEditFlags(group, mc.level, pos);
        AddNamedWaypointScreen.openWhereLooking(null, manager, config, group,
                pos.getX(), pos.getY(), pos.getZ(),
                precise.preciseX(), precise.preciseY(), precise.preciseZ(), flags);
    }

    private static void startAddSession(ActiveGroupManager manager, WaypointerConfig config,
                                        Mode mode, boolean preciseSmallPlacement) {
        if (manager == null || config == null) return;
        if (mode == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (addBlockedByInstalledSecrets(mc, manager)) return;

        WaypointGroup group = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        active = new Session(manager, config, group, -1, mode, preciseSmallPlacement, true);
        MinecraftCompat.setScreen(mc, null);
        showHelp(mc);
    }

    private static void onTick(Minecraft mc) {
        boolean missingWorld = mc.player == null || mc.level == null;
        if (missingWorld) {
            lastEditModeActionLevel = null;
            lastEditModeActionGameTime = Long.MIN_VALUE;
            clearEditSelectionCycle();
        }
        if (active != null) {
            if (missingWorld || MinecraftCompat.screen(mc) != null) {
                active = null;
            }
        }
        if (editModeEnabled && missingWorld) {
            editModeEnabled = false;
            editManager = null;
            editConfig = null;
        }
    }

    private static boolean handleEditModeAttack(Minecraft mc, int clickCount) {
        if (clickCount == 0) return true;
        if (!editModeEnabled) return false;

        SelectedWaypoint selected = findWaypointUnderCrosshair(mc);
        if (selected == null) {
            clearEditSelectionCycle();
            return true;
        }

        startMove(editManager, editConfig, selected.group(), selected.waypointIndex(), false);
        return true;
    }

    private static InteractionResult handleEditModeRightClick(Minecraft mc, ItemStack held) {
        if (editModeEnabled && isShiftDown()) {
            return InteractionResult.FAIL;
        }

        if (active != null) {
            cancel(mc);
            return InteractionResult.FAIL;
        }

        if (!editModeEnabled) return InteractionResult.PASS;
        if (DungeonItemIdentity.isDungeonbreaker(held)) {
            return removeWaypointFromEditModeRightClick(mc);
        }
        return addWaypointFromEditModeRightClick(mc);
    }

    private static InteractionResult removeWaypointFromEditModeRightClick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) return InteractionResult.PASS;
        if (editManager == null || editConfig == null) {
            showStatus(mc, HELP_EDIT_UNAVAILABLE);
            return InteractionResult.FAIL;
        }
        if (isDuplicateEditModeAction(mc.level)) return InteractionResult.FAIL;
        rememberEditModeAction(mc.level);

        SelectedWaypoint selected = findWaypointUnderCrosshair(mc);
        if (selected == null) {
            return InteractionResult.FAIL;
        }
        if (!removeWaypoint(editManager, selected.group(), selected.waypointIndex())) {
            showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
            return InteractionResult.FAIL;
        }

        clearEditSelectionCycle();
        editManager.fireDataChanged();
        playEditSound(mc, editConfig);
        showStatus(mc, HELP_EDIT_REMOVED);
        return InteractionResult.FAIL;
    }

    static boolean removeWaypoint(ActiveGroupManager manager, WaypointGroup visibleGroup,
                                  int waypointIndex) {
        if (manager == null || visibleGroup == null || waypointIndex < 0) return false;
        WaypointGroup target = DungeonRoomRouteSync.durableEditTarget(manager, visibleGroup);
        if (target == null || waypointIndex >= target.size()) return false;
        target.remove(waypointIndex);
        return true;
    }

    private static InteractionResult addWaypointFromEditModeRightClick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) return InteractionResult.PASS;
        if (editManager == null || editConfig == null) {
            showStatus(mc, HELP_EDIT_UNAVAILABLE);
            return InteractionResult.FAIL;
        }

        BlockPos pos = targetedBlock(mc);
        if (pos == null) {
            showStatus(mc, HELP_EDIT_NO_BLOCK_TARGET);
            return InteractionResult.FAIL;
        }

        if (isDuplicateEditModeAction(mc.level)) return InteractionResult.FAIL;
        if (addBlockedByInstalledSecrets(mc, editManager)) return InteractionResult.FAIL;
        rememberEditModeAction(mc.level);

        WaypointGroup group = editModeAddTarget(editManager);
        if (group == null) {
            showStatus(mc, HELP_EDIT_UNAVAILABLE);
            return InteractionResult.FAIL;
        }
        int flags = defaultDungeonEditFlags(group, mc.level, pos);
        addStored(group, new Waypoint(pos.getX(), pos.getY(), pos.getZ(),
                "", editConfig.defaultWaypointColor(), flags, 0.0));
        int index = group.size() - 1;
        new WaypointAddFlow().afterWaypointAdded(group, index,
                editConfig.showWaypointChatShareButtons());
        editManager.fireDataChanged();
        playEditSound(mc, editConfig);
        return InteractionResult.FAIL;
    }

    /**
     * Resolve the route that receives a right-click waypoint while Edit Mode is
     * active. Editor-started sessions must retain their explicit route. Generic
     * keybind sessions keep the long-standing active-route fallback.
     */
    static WaypointGroup editModeAddTarget(ActiveGroupManager manager) {
        if (manager == null) return null;
        if (editTargetGroupId == null) return manager.getOrCreateActiveGroup();

        WaypointGroup selected = manager.get(editTargetGroupId);
        if (selected == null) return null;
        return DungeonRoomRouteSync.durableEditTarget(manager, selected);
    }

    private static boolean isDuplicateEditModeAction(ClientLevel level) {
        return level != null
                && lastEditModeActionLevel == level
                && lastEditModeActionGameTime == level.getGameTime();
    }

    private static void rememberEditModeAction(ClientLevel level) {
        if (level == null) {
            lastEditModeActionLevel = null;
            lastEditModeActionGameTime = Long.MIN_VALUE;
            return;
        }
        lastEditModeActionLevel = level;
        lastEditModeActionGameTime = level.getGameTime();
    }

    static int defaultDungeonEditFlags(WaypointGroup group) {
        return defaultDungeonEditFlags(group, false);
    }

    static int defaultDungeonEditFlags(WaypointGroup group, boolean interactDefaultBlock) {
        if (!isDungeonRoomGroup(group)) return 0;
        return interactDefaultBlock
                ? Waypoint.FLAG_SKIP_ON_INTERACT
                : Waypoint.FLAG_SKIP_ON_STAND;
    }

    private static int defaultDungeonEditFlags(WaypointGroup group, ClientLevel level, BlockPos pos) {
        return defaultDungeonEditFlags(group, isDungeonInteractDefaultBlock(level, pos));
    }

    private static boolean isDungeonInteractDefaultBlock(ClientLevel level, BlockPos pos) {
        return level != null && pos != null
                && isDungeonInteractDefaultBlock(level.getBlockState(pos));
    }

    static boolean isDungeonInteractDefaultBlock(BlockState state) {
        if (state == null) return false;
        Object block = state.getBlock();
        return block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof ChestBlock
                || block instanceof EnderChestBlock;
    }

    private static boolean isDungeonRoomGroup(WaypointGroup group) {
        return group != null
                && !group.temp()
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    private static boolean isShiftDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, 344 /* GLFW_KEY_RIGHT_SHIFT */);
    }

    private static void playEditSound(Minecraft mc, WaypointerConfig config) {
        if (mc == null || config == null || !config.editSounds()) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.STONE_HIT, 0.55F, 0.55F));
    }

    private static SelectedWaypoint findWaypointUnderCrosshair(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) return null;
        if (editManager == null || editConfig == null) return null;

        ClientLevel level = mc.level;
        Vec3 origin = MinecraftCompat.mainCamera(mc.gameRenderer).position();
        Vec3 view = mc.player.getViewVector(1.0F);
        Vec3 direction = normalizedDirection(view);
        if (direction == null) return null;

        List<SelectedWaypoint> candidates = new ArrayList<>();
        List<Double> distances = new ArrayList<>();

        for (WaypointGroup group : editManager.activeGroups()) {
            group.forEachVisibleIndex(editConfig.keepSubwaypointsVisibleUntilNextWaypoint(),
                    index -> {
                if (index < 0 || index >= group.size()) return;

                Waypoint waypoint = group.get(index);
                AABB bounds = WaypointRenderer.waypointBoxBounds(level, waypoint);
                if (bounds == null) return;

                double distance = rayBoxDistance(origin, direction,
                        expandedBounds(bounds, EDIT_PICK_PADDING), EDIT_PICK_RANGE);
                if (distance < 0.0) return;

                insertEditPickCandidate(candidates, distances,
                        new SelectedWaypoint(group, index), distance);
            });
        }

        return chooseEditSelectionCandidate(candidates);
    }

    private static void insertEditPickCandidate(List<SelectedWaypoint> candidates,
                                                List<Double> distances,
                                                SelectedWaypoint candidate,
                                                double distance) {
        if (candidates == null
                || distances == null
                || candidate == null
                || !Double.isFinite(distance)
                || distance < 0.0) {
            return;
        }
        if (candidates.size() != distances.size()) {
            candidates.clear();
            distances.clear();
        }

        int insertAt = 0;
        while (insertAt < distances.size() && distances.get(insertAt) <= distance) {
            insertAt++;
        }
        candidates.add(insertAt, candidate);
        distances.add(insertAt, distance);
    }

    private static SelectedWaypoint chooseEditSelectionCandidate(List<SelectedWaypoint> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            clearEditSelectionCycle();
            return null;
        }

        int previousPosition = -1;
        for (int i = 0; i < candidates.size(); i++) {
            SelectedWaypoint candidate = candidates.get(i);
            if (candidate.group() == lastEditSelectionGroup
                    && candidate.waypointIndex() == lastEditSelectionIndex) {
                previousPosition = i;
                break;
            }
        }

        int chosenIndex = previousPosition >= 0
                ? (previousPosition + 1) % candidates.size()
                : 0;
        SelectedWaypoint chosen = candidates.get(chosenIndex);
        lastEditSelectionGroup = chosen.group();
        lastEditSelectionIndex = chosen.waypointIndex();
        return chosen;
    }

    private static Vec3 normalizedDirection(Vec3 view) {
        if (view == null
                || !Double.isFinite(view.x)
                || !Double.isFinite(view.y)
                || !Double.isFinite(view.z)) {
            return null;
        }

        double lengthSq = view.x * view.x + view.y * view.y + view.z * view.z;
        if (lengthSq <= RAY_AXIS_EPSILON * RAY_AXIS_EPSILON) return null;

        double invLength = 1.0 / Math.sqrt(lengthSq);
        return new Vec3(view.x * invLength, view.y * invLength, view.z * invLength);
    }

    private static AABB expandedBounds(AABB bounds, double padding) {
        double safePadding = Double.isFinite(padding) && padding > 0.0 ? padding : 0.0;
        return new AABB(
                bounds.minX - safePadding,
                bounds.minY - safePadding,
                bounds.minZ - safePadding,
                bounds.maxX + safePadding,
                bounds.maxY + safePadding,
                bounds.maxZ + safePadding);
    }

    private static double rayBoxDistance(Vec3 origin, Vec3 direction, AABB box,
                                         double maxDistance) {
        if (origin == null || direction == null || box == null) return -1.0;
        if (!Double.isFinite(maxDistance) || maxDistance < 0.0) return -1.0;
        if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)) {
            return -1.0;
        }
        if (!Double.isFinite(direction.x)
                || !Double.isFinite(direction.y)
                || !Double.isFinite(direction.z)) {
            return -1.0;
        }

        double tMin = 0.0;
        double tMax = maxDistance;

        if (Math.abs(direction.x) < RAY_AXIS_EPSILON) {
            if (origin.x < box.minX || origin.x > box.maxX) return -1.0;
        } else {
            double inv = 1.0 / direction.x;
            double near = (box.minX - origin.x) * inv;
            double far = (box.maxX - origin.x) * inv;
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) return -1.0;
        }

        if (Math.abs(direction.y) < RAY_AXIS_EPSILON) {
            if (origin.y < box.minY || origin.y > box.maxY) return -1.0;
        } else {
            double inv = 1.0 / direction.y;
            double near = (box.minY - origin.y) * inv;
            double far = (box.maxY - origin.y) * inv;
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) return -1.0;
        }

        if (Math.abs(direction.z) < RAY_AXIS_EPSILON) {
            if (origin.z < box.minZ || origin.z > box.maxZ) return -1.0;
        } else {
            double inv = 1.0 / direction.z;
            double near = (box.minZ - origin.z) * inv;
            double far = (box.maxZ - origin.z) * inv;
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) return -1.0;
        }

        return tMin <= maxDistance ? tMin : -1.0;
    }

    private static void commit(Minecraft mc) {
        Session session = active;
        if (session == null) return;

        if (session.mode == Mode.MOVE_EXISTING && session.preciseSmallPlacement) {
            PreciseTarget target = targetedPrecise(mc);
            if (target == null) {
                showHelp(mc);
                return;
            }
            moveExistingPrecise(mc, session, target);
            return;
        }
        BlockPos pos = targetedBlock(mc);
        if (pos == null) {
            showHelp(mc);
            return;
        }

        switch (session.mode) {
            case MOVE_EXISTING -> moveExisting(mc, session, pos);
            case ADD_WHERE_LOOKING -> openWhereLookingPrompt(mc, session, pos,
                    targetedPrecise(mc));
        }
    }

    private static void moveExisting(Minecraft mc, Session session, BlockPos pos) {
        if (session.waypointIndex < 0 || session.waypointIndex >= session.group.size()) {
            active = null;
            return;
        }

        WaypointGroup target = moveWriteTarget(session);
        if (target == null) {
            active = null;
            showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
            return;
        }
        if (isDungeonRoomGroup(target)) {
            // Stored room routes are room-local; convert the picked world block
            // into the room frame before writing.
            int[] stored = DungeonRoomWaypointPlacement.toStoredPrecisePosition(target,
                    pos.getX() * Waypoint.PRECISE_SCALE,
                    pos.getY() * Waypoint.PRECISE_SCALE,
                    pos.getZ() * Waypoint.PRECISE_SCALE);
            target.moveWaypointToPrecise(session.waypointIndex, stored[0], stored[1], stored[2]);
        } else {
            target.moveWaypointTo(session.waypointIndex, pos.getX(), pos.getY(), pos.getZ());
        }
        session.manager.fireDataChanged();
        active = null;
        finishMoveSession(mc, session);
    }

    private static void moveExistingPrecise(Minecraft mc, Session session, PreciseTarget target) {
        if (session.waypointIndex < 0 || session.waypointIndex >= session.group.size()) {
            active = null;
            return;
        }

        WaypointGroup writeTarget = moveWriteTarget(session);
        if (writeTarget == null) {
            active = null;
            showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
            return;
        }
        int[] stored = DungeonRoomWaypointPlacement.toStoredPrecisePosition(writeTarget,
                target.preciseX(), target.preciseY(), target.preciseZ());
        writeTarget.moveWaypointToPrecise(session.waypointIndex, stored[0], stored[1], stored[2]);
        session.manager.fireDataChanged();
        active = null;
        finishMoveSession(mc, session);
    }

    /**
     * The group a committed move must mutate. Edit-mode picks select the
     * runtime mirror (that is what renders), but the mirror is rebuilt on
     * every data change — the durable edit goes to the stored room-local
     * source at the same index (mirror and source share waypoint order).
     * Returns null when the mirror has no stored source (downloaded secrets).
     */
    private static WaypointGroup moveWriteTarget(Session session) {
        WaypointGroup target = DungeonRoomRouteSync.durableEditTarget(
                session.manager, session.group);
        if (target == null || session.waypointIndex >= target.size()) return null;
        return target;
    }

    private static void finishMoveSession(Minecraft mc, Session session) {
        playEditSound(mc, session.config());
        if (session.reopenEditorAfterCommit()) {
            reopenEditor(mc, session);
        }
    }

    /**
     * Append with room-local conversion: stored dungeon-room routes keep
     * room-local coordinates, so a waypoint picked in world space converts
     * before it lands in the group. Identity for every other group.
     */
    private static void addStored(WaypointGroup group, Waypoint actualWaypoint) {
        group.add(DungeonRoomWaypointPlacement.toStoredWaypoint(group, actualWaypoint));
    }

    /**
     * In a room showing downloaded secrets with no user route, an add would
     * either land on the throwaway mirror or silently suppress the secrets.
     * Refuse and point at the conversion flow instead.
     */
    private static boolean addBlockedByInstalledSecrets(Minecraft mc, ActiveGroupManager manager) {
        if (manager == null || manager.currentZone() == null) return false;
        if (!DungeonRoomRouteSync.secretsRequireConversion(manager, manager.currentZone().id())) {
            return false;
        }
        showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
        return true;
    }

    private static void openWhereLookingPrompt(Minecraft mc, Session session, BlockPos pos,
                                               PreciseTarget preciseTarget) {
        ClientLevel level = mc == null ? null : mc.level;
        int flags = defaultDungeonEditFlags(session.group, level, pos);
        active = null;
        if (mc == null || preciseTarget == null) return;
        mc.execute(
                () -> AddNamedWaypointScreen.openWhereLooking(null, session.manager, session.config,
                session.group, pos.getX(), pos.getY(), pos.getZ(),
                preciseTarget.preciseX(), preciseTarget.preciseY(), preciseTarget.preciseZ(),
                flags));
    }

    private static void cancel(Minecraft mc) {
        WaypointerConfig soundConfig = active == null ? editConfig : active.config();
        active = null;
        playEditSound(mc, soundConfig);
    }

    private static void reopenEditor(Minecraft mc, Session session) {
        if (mc == null) return;
        mc.execute(
                () -> {
            WaypointerScreen parent = new WaypointerScreen(session.manager, session.config);
            WaypointGroup editTarget = DungeonRoomRouteSync.durableEditTarget(
                    session.manager, session.group);
            if (editTarget == null) return;
            GroupEditScreen.openFocused(parent, session.manager, session.config,
                    editTarget, session.waypointIndex);
        });
    }

    private static void showHelp(Minecraft mc) {
        if (mc == null || active == null || active.mode() == Mode.MOVE_EXISTING) return;
        showStatus(mc, active.help());
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

    private static void renderOutline(LevelRenderContext ctx) {
        if (active == null) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = ctx.poseStack();
        if (ps == null) return;

        Vec3 cam = MinecraftCompat.mainCamera(mc.gameRenderer).position();
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;

        if (active.preciseSmallPlacement) {
            PreciseTarget target = targetedPrecise(mc);
            if (target == null) return;
            minX = target.x();
            minY = target.y();
            minZ = target.z();
            maxX = minX + PRECISE_SMALL_SIZE;
            maxY = minY + PRECISE_SMALL_SIZE;
            maxZ = minZ + PRECISE_SMALL_SIZE;
        } else {
            BlockPos pos = targetedBlock(mc);
            if (pos == null) return;
            minX = pos.getX();
            minY = pos.getY();
            minZ = pos.getZ();
            maxX = pos.getX() + 1.0;
            maxY = pos.getY() + 1.0;
            maxZ = pos.getZ() + 1.0;
        }

        float x1 = (float) (minX - cam.x - OUTLINE_EXPAND);
        float y1 = (float) (minY - cam.y - OUTLINE_EXPAND);
        float z1 = (float) (minZ - cam.z - OUTLINE_EXPAND);
        float x2 = (float) (maxX - cam.x + OUTLINE_EXPAND);
        float y2 = (float) (maxY - cam.y + OUTLINE_EXPAND);
        float z2 = (float) (maxZ - cam.z + OUTLINE_EXPAND);

        var type = WaypointerRenderPipelines.linesThroughWalls();
        RenderSubmission.submit(ctx, ps, type, (lines, submittedPose) ->
                RenderHelpers.emitLineBox(lines, submittedPose,
                        x1, y1, z1, x2, y2, z2,
                        OUTLINE_COLOR, OUTLINE_ALPHA, OUTLINE_WIDTH));
    }

    private static PreciseTarget targetedPrecise(Minecraft mc) {
        if (mc == null || !(mc.hitResult instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        Vec3 location = hit.getLocation();
        return new PreciseTarget(
                Waypoint.snapToPreciseSixteenths(location.x),
                Waypoint.snapToPreciseSixteenths(location.y),
                Waypoint.snapToPreciseSixteenths(location.z));
    }

    private static BlockPos targetedBlock(Minecraft mc) {
        if (mc == null || !(mc.hitResult instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return hit.getBlockPos();
    }

    private enum Mode {
        MOVE_EXISTING,
        ADD_WHERE_LOOKING
    }

    private record SelectedWaypoint(WaypointGroup group, int waypointIndex) {}

    private record PreciseTarget(int preciseX, int preciseY, int preciseZ) {
        double x() {
            return preciseX / (double) Waypoint.PRECISE_SCALE;
        }

        double y() {
            return preciseY / (double) Waypoint.PRECISE_SCALE;
        }

        double z() {
            return preciseZ / (double) Waypoint.PRECISE_SCALE;
        }
    }

    private record Session(ActiveGroupManager manager, WaypointerConfig config,
                           WaypointGroup group, int waypointIndex, Mode mode,
                           boolean preciseSmallPlacement, boolean reopenEditorAfterCommit) {
        Component help() {
            return switch (mode) {
                case ADD_WHERE_LOOKING -> HELP_ADD_WHERE_LOOKING;
                case MOVE_EXISTING -> null;
            };
        }
    }
}
