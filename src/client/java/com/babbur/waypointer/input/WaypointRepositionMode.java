package com.babbur.waypointer.input;

import com.babbur.waypointer.compat.InputCompat;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonItemIdentity;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.render.RenderHelpers;
import com.babbur.waypointer.render.RenderSubmission;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.babbur.waypointer.screen.AddNamedWaypointScreen;
import com.babbur.waypointer.screen.GroupEditScreen;
import com.babbur.waypointer.screen.WaypointerScreen;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import com.babbur.waypointer.render.WorldOverlayCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class WaypointRepositionMode {

    private static final int OUTLINE_COLOR = 0x4FB3C4;
    private static final float OUTLINE_ALPHA = 0.95f;
    private static final float OUTLINE_WIDTH = 4.0f;
    private static final double OUTLINE_EXPAND = 0.002;
    private static final double PRECISE_SMALL_SIZE = 1.0 / 16.0;
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

    static final double MIN_PLACEMENT_DISTANCE = 1.0;
    private static final Object MISS_ANCHOR = new Object();
    private static int placementOffsetSteps;
    private static Object placementAnchor;
    private static double scrollAccumulator;
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
        ClientTickEvents.END_CLIENT_TICK.register(WaypointRepositionMode::onTick);
        WorldOverlayCompat.register(WaypointRepositionMode::renderOutline);
    }

    public static boolean handleRightClick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null
                || (active == null && !editModeEnabled)) return false;
        return handleEditModeRightClick(mc, mc.player.getMainHandItem()) != InteractionResult.PASS;
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
        resetPlacementOffset();
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
            editManager.fireTransientDataChanged();
            showStatus(mc, HELP_EDIT_ON);
            if (!wasEnabled) {
                playEditSound(mc, config);
            }
            return;
        }

        WaypointerConfig soundConfig = config == null ? editConfig : config;
        ActiveGroupManager previousManager = editManager;
        if (editManager != null) editManager.isolateRouteForEditing(null);
        editModeEnabled = false;
        editManager = null;
        editConfig = null;
        editTargetGroupId = null;
        active = null;
        if (previousManager != null) previousManager.fireTransientDataChanged();
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
        WaypointGroup editTarget = DungeonRoomRouteLibrary.durableEditTarget(manager, group);
        if (editTarget == null) {
            showStatus(mc, HELP_CONVERT_SECRETS_FIRST);
            return;
        }
        if (!DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(editTarget)) {
            showStatus(mc, DungeonRoomWaypointPlacement.unavailableMessage(editTarget));
            return;
        }
        Waypoint waypoint = group.get(waypointIndex);
        boolean preciseSmallPlacement = waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
        active = new Session(manager, config, group, waypointIndex,
                preciseSmallPlacement, reopenEditorAfterCommit);
        resetPlacementOffset();
        MinecraftCompat.setScreen(mc, null);
    }

    public static void openAddWhereLooking(ActiveGroupManager manager, WaypointerConfig config) {
        if (manager == null || config == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

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
        rememberEditModeAction(mc.level);

        WaypointGroup group = editModeAddTarget(editManager);
        if (group == null) {
            showStatus(mc, HELP_EDIT_UNAVAILABLE);
            return InteractionResult.FAIL;
        }
        int flags = defaultDungeonEditFlags(group, mc.level, pos);
        Waypoint stored = DungeonRoomWaypointPlacement.toStoredWaypoint(group,
                new Waypoint(pos.getX(), pos.getY(), pos.getZ(),
                        "", editConfig.defaultWaypointColor(), flags, 0.0));
        if (stored == null) {
            showStatus(mc, DungeonRoomWaypointPlacement.unavailableMessage(group));
            return InteractionResult.FAIL;
        }
        group.add(stored);
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
        return InputCompat.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputCompat.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    private static void playEditSound(Minecraft mc, WaypointerConfig config) {
        if (mc == null || config == null || !config.editSounds()) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.STONE_HIT, 0.55F, 0.55F));
    }

    private static void commit(Minecraft mc) {
        Session session = active;
        if (session == null) return;

        if (session.preciseSmallPlacement) {
            PreciseTarget target = targetedPrecise(mc);
            if (target != null) moveExistingPrecise(mc, session, target);
            return;
        }
        BlockPos pos = targetedBlock(mc);
        if (pos != null) moveExisting(mc, session, pos);
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
        if (!DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(
                target, session.waypointIndex, pos.getX(), pos.getY(), pos.getZ())) {
            active = null;
            showStatus(mc, DungeonRoomWaypointPlacement.unavailableMessage(target));
            return;
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
        if (stored == null) {
            active = null;
            showStatus(mc, DungeonRoomWaypointPlacement.unavailableMessage(writeTarget));
            return;
        }
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
        if (active == null && !editModeEnabled) return;

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

        if (active != null && active.preciseSmallPlacement) {
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
        return placementPrecise(placementHit(mc));
    }

    static PreciseTarget placementPrecise(BlockHitResult hit) {
        if (hit == null) return null;
        Vec3 location = hit.getLocation();
        return new PreciseTarget(
                Waypoint.snapToPreciseSixteenths(location.x),
                Waypoint.snapToPreciseSixteenths(location.y),
                Waypoint.snapToPreciseSixteenths(location.z));
    }

    private static BlockPos targetedBlock(Minecraft mc) {
        return placementBlock(placementHit(mc));
    }

    static BlockPos placementBlock(BlockHitResult hit) {
        if (hit == null) return null;
        return hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : BlockPos.containing(hit.getLocation());
    }

    private static BlockHitResult placementHit(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 direction = mc.player.getViewVector(1.0F);
        BlockHitResult base = placementHit(mc.level, eye, direction,
                placementReach(placementConfig(), mc.player.blockInteractionRange()),
                CollisionContext.of(mc.player));
        syncPlacementAnchor(base);
        return applyPlacementOffset(base, eye, direction, placementOffsetSteps,
                WaypointerConfig.MAX_EDIT_MODE_PLACEMENT_REACH);
    }

    public static boolean handleScroll(Minecraft mc, double vertical) {
        if (mc == null || mc.player == null || mc.level == null || MinecraftCompat.screen(mc) != null) return false;
        if (!editModeEnabled && active == null) return false;
        if (vertical == 0 || isShiftDown()) return false;
        scrollAccumulator += vertical;
        int notches = (int) scrollAccumulator;
        if (notches == 0) return true;
        scrollAccumulator -= notches;
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult base = placementHit(mc.level, eye, mc.player.getViewVector(1.0F),
                placementReach(placementConfig(), mc.player.blockInteractionRange()),
                CollisionContext.of(mc.player));
        syncPlacementAnchor(base);
        for (int notch = 0; notch < Math.abs(notches); notch++) {
            placementOffsetSteps = nextPlacementOffset(placementOffsetSteps, notches,
                    base.getType() == HitResult.Type.BLOCK, eye.distanceTo(base.getLocation()),
                    WaypointerConfig.MAX_EDIT_MODE_PLACEMENT_REACH);
        }
        double distance = placementDistance(base, eye, placementOffsetSteps,
                WaypointerConfig.MAX_EDIT_MODE_PLACEMENT_REACH);
        if (mc.gui != null) {
            MinecraftCompat.setOverlayMessage(mc, Component.translatable(
                    "waypointer.input.edit_mode.distance", String.format(Locale.ROOT, "%.1f", distance))
                    .withStyle(ChatFormatting.AQUA), false);
        }
        return true;
    }

    private static void resetPlacementOffset() {
        placementOffsetSteps = 0;
        placementAnchor = null;
        scrollAccumulator = 0;
    }

    private static void syncPlacementAnchor(BlockHitResult base) {
        Object anchor = base == null ? null
                : base.getType() == HitResult.Type.BLOCK ? base.getBlockPos() : MISS_ANCHOR;
        if (anchor != null && !anchor.equals(placementAnchor)) {
            placementAnchor = anchor;
            placementOffsetSteps = 0;
        }
    }

    static int nextPlacementOffset(int steps, double wheel, boolean blockHit, double baseDistance,
                                   double maxReach) {
        int next = wheel < 0 ? steps + 1 : steps - 1;
        double farthest = blockHit ? baseDistance : Math.max(baseDistance, maxReach);
        double nearest = Math.min(MIN_PLACEMENT_DISTANCE, baseDistance);
        if (baseDistance - next < nearest) next = (int) Math.floor(baseDistance - nearest);
        if (baseDistance - next > farthest) next = (int) Math.ceil(baseDistance - farthest);
        return next;
    }

    static double placementDistance(BlockHitResult base, Vec3 eye, int steps, double maxReach) {
        double baseDistance = eye.distanceTo(base.getLocation());
        if (steps == 0) return baseDistance;
        double farthest = base.getType() == HitResult.Type.BLOCK ? baseDistance : Math.max(baseDistance, maxReach);
        double nearest = Math.min(MIN_PLACEMENT_DISTANCE, baseDistance);
        return Math.max(nearest, Math.min(farthest, baseDistance - steps));
    }

    static BlockHitResult applyPlacementOffset(BlockHitResult base, Vec3 eye, Vec3 direction,
                                               int steps, double maxReach) {
        if (base == null || steps == 0) return base;
        double distance = placementDistance(base, eye, steps, maxReach);
        Vec3 point = eye.add(direction.normalize().scale(distance));
        return BlockHitResult.miss(point, Direction.UP, BlockPos.containing(point));
    }

    private static WaypointerConfig placementConfig() {
        Session session = active;
        if (session != null && session.config() != null) return session.config();
        return editConfig != null ? editConfig : WaypointerClient.config();
    }

    static double placementReach(WaypointerConfig config, double vanillaReach) {
        double configured = config == null
                ? WaypointerConfig.DEFAULT_EDIT_MODE_PLACEMENT_REACH : config.editModePlacementReach();
        return Math.max(configured, vanillaReach);
    }

    static BlockHitResult placementHit(BlockGetter level, Vec3 eye, Vec3 direction,
                                       double reach, CollisionContext context) {
        Vec3 end = eye.add(direction.scale(reach));
        return level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, context));
    }

    record PreciseTarget(int preciseX, int preciseY, int preciseZ) {
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
                           WaypointGroup group, int waypointIndex,
                           boolean preciseSmallPlacement, boolean reopenEditorAfterCommit) {
    }
}
