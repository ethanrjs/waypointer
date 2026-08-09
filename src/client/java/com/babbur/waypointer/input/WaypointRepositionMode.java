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
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.render.RenderHelpers;
import com.babbur.waypointer.render.RenderSubmission;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.babbur.waypointer.screen.AddNamedWaypointScreen;
import com.babbur.waypointer.screen.GroupEditScreen;
import com.babbur.waypointer.screen.WaypointerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import com.babbur.waypointer.render.WorldOverlayCompat;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class WaypointRepositionMode {

    private static final int OUTLINE_COLOR = 0x4FB3C4;
    private static final float OUTLINE_ALPHA = 0.95f;
    private static final float OUTLINE_WIDTH = 4.0f;
    private static final double OUTLINE_EXPAND = 0.002;
    private static final double PRECISE_SMALL_SIZE = 1.0 / 16.0;
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
            "waypointer.input.edit_mode.convert_definition_first")
            .withStyle(ChatFormatting.YELLOW);

    private static final WaypointEditPicker EDIT_PICKER = new WaypointEditPicker();
    private static Session active;
    private static ActiveGroupManager editManager;
    private static WaypointerConfig editConfig;
    private static String editTargetGroupId;
    private static ClientLevel lastEditModeActionLevel;
    private static long lastEditModeActionGameTime = Long.MIN_VALUE;
    private static boolean editModeEnabled;

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
        UseItemCallback.EVENT.register((player, world, hand) ->
                handleRightClick(world, player.getItemInHand(hand)));
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                handleRightClick(world, player.getItemInHand(hand)));
        ClientTickEvents.END_CLIENT_TICK.register(WaypointRepositionMode::onTick);
        WorldOverlayCompat.register(WaypointRepositionMode::renderOutline);
    }

    private static InteractionResult handleRightClick(Level world, ItemStack heldItem) {
        if (!world.isClientSide() || (active == null && !editModeEnabled)) return InteractionResult.PASS;
        return handleEditModeRightClick(Minecraft.getInstance(), heldItem);
    }

    public static boolean isEditModeEnabled() {
        return editModeEnabled;
    }

    public static boolean toggleEditMode(ActiveGroupManager manager, WaypointerConfig config) {
        setEditModeEnabled(manager, config, !editModeEnabled);
        return editModeEnabled;
    }

    public static boolean toggleEditMode(ActiveGroupManager manager, WaypointerConfig config,
                                         WaypointGroup target) {
        setEditModeEnabled(manager, config, target, !editModeEnabled);
        return editModeEnabled;
    }

    public static void setEditModeEnabled(ActiveGroupManager manager, WaypointerConfig config,
                                           boolean enabled) {
        setEditModeEnabled(manager, config, null, enabled);
    }

    public static void setEditModeEnabled(ActiveGroupManager manager, WaypointerConfig config,
                                          WaypointGroup target, boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        boolean wasEnabled = editModeEnabled;
        lastEditModeActionLevel = null;
        lastEditModeActionGameTime = Long.MIN_VALUE;
        clearEditSelectionCycle();
        if (enabled) {
            if (manager == null || config == null) {
                if (editManager != null) editManager.isolateRouteForEditing(null);
                editModeEnabled = false;
                editManager = null;
                editConfig = null;
                editTargetGroupId = null;
                active = null;
                showStatus(mc, HELP_EDIT_UNAVAILABLE);
                return;
            }
            if (editManager != null && editManager != manager) {
                editManager.isolateRouteForEditing(null);
            }
            editManager = manager;
            editConfig = config;
            if (target != null || !wasEnabled) {
                editTargetGroupId = target == null ? null : target.id();
            }
            editManager.isolateRouteForEditing(
                    editTargetGroupId == null ? null : editManager.get(editTargetGroupId));
            editModeEnabled = true;
            showStatus(mc, HELP_EDIT_ON);
            if (!wasEnabled) {
                playEditSound(mc, config);
            }
            return;
        }

        WaypointerConfig soundConfig = config == null ? editConfig : config;
        if (editManager != null) editManager.isolateRouteForEditing(null);
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
        EDIT_PICKER.clear();
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
        // Reject edits that would disappear on the next room rebuild.
        if (DungeonRoomRouteLibrary.durableEditTarget(manager, group) == null) {
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
            if (editManager != null) editManager.isolateRouteForEditing(null);
            editModeEnabled = false;
            editManager = null;
            editConfig = null;
            editTargetGroupId = null;
        }
    }

    private static boolean handleEditModeAttack(Minecraft mc, int clickCount) {
        if (clickCount == 0) return true;
        if (!editModeEnabled) return false;

        WaypointEditPicker.Selection selected = EDIT_PICKER.find(mc, editManager, editConfig);
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

        WaypointEditPicker.Selection selected = EDIT_PICKER.find(mc, editManager, editConfig);
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
        WaypointGroup target = DungeonRoomRouteLibrary.durableEditTarget(manager, visibleGroup);
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

    static WaypointGroup editModeAddTarget(ActiveGroupManager manager) {
        if (manager == null) return null;
        if (editTargetGroupId == null) return manager.getOrCreateActiveGroup();

        WaypointGroup selected = manager.get(editTargetGroupId);
        if (selected == null) return null;
        return DungeonRoomRouteLibrary.durableEditTarget(manager, selected);
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
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
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
        DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(
                target, session.waypointIndex, pos.getX(), pos.getY(), pos.getZ());
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

    private static WaypointGroup moveWriteTarget(Session session) {
        WaypointGroup target = DungeonRoomRouteLibrary.durableEditTarget(
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

    private static void addStored(WaypointGroup group, Waypoint actualWaypoint) {
        group.add(DungeonRoomWaypointPlacement.toStoredWaypoint(group, actualWaypoint));
    }

    private static boolean addBlockedByInstalledSecrets(Minecraft mc, ActiveGroupManager manager) {
        return false;
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
            WaypointGroup editTarget = DungeonRoomRouteLibrary.durableEditTarget(
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
