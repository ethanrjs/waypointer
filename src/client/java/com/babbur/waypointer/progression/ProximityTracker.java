package com.babbur.waypointer.progression;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.dungeon.DungeonChestInteractionGuard;
import com.babbur.waypointer.dungeon.DungeonItemIdentity;
import com.babbur.waypointer.dungeon.DungeonSecretCompletionSound;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;

public final class ProximityTracker {

    private static final double STAND_SKIP_SCAN_RADIUS = 1.75D;
    static final long STAND_SKIP_HOLD_MS = 500L;
    static final int MINE_BLOCK_UNKNOWN = 0;
    static final int MINE_BLOCK_PRESENT = 1;
    static final int MINE_BLOCK_AIR = 2;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final DungeonChestInteractionGuard chestInteractionGuard;
    private final DungeonConfig dungeonConfig;
    private final Set<MineTarget> observedMineTargets = new HashSet<>();

    public ProximityTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, null);
    }

    public ProximityTracker(ActiveGroupManager manager, WaypointerConfig config,
                            DungeonChestInteractionGuard chestInteractionGuard) {
        this(manager, config, chestInteractionGuard, null);
    }

    public ProximityTracker(ActiveGroupManager manager, WaypointerConfig config,
                            DungeonChestInteractionGuard chestInteractionGuard,
                            DungeonConfig dungeonConfig) {
        this.manager = manager;
        this.config = config;
        this.chestInteractionGuard = chestInteractionGuard;
        this.dungeonConfig = dungeonConfig;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        UseBlockCallback.EVENT.register(this::onUseBlock);
        UseItemCallback.EVENT.register(this::onUseItem);
    }

    private InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                         BlockHitResult hit) {
        if (!world.isClientSide()) return InteractionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        boolean superboomHeld = DungeonItemIdentity.isSuperboom(player.getItemInHand(hand));
        boolean restart = config.restartRouteWhenComplete();
        ClientLevel clientLevel = world instanceof ClientLevel level ? level : null;
        boolean deferChest = chestInteractionGuard != null
                && clientLevel != null
                && isChest(world.getBlockState(pos));
        for (WaypointGroup group : manager.activeGroups()) {
            if (deferChest) {
                int from = group.currentIndex();
                int reachedIndex = interactedWaypointIndex(
                        group, pos.getX(), pos.getY(), pos.getZ(), from, superboomHeld);
                if (reachedIndex < 0) continue;
                boolean completesSecret =
                        group.get(reachedIndex).hasFlag(Waypoint.FLAG_DUNGEON_SECRET);
                String groupId = group.id();
                chestInteractionGuard.defer(clientLevel, () -> {
                    boolean advanced = advanceDeferredInteraction(
                            manager, groupId, from, pos.getX(), pos.getY(), pos.getZ(), restart,
                            superboomHeld, config.allowBackwardProgress());
                    WaypointGroup current = manager.get(groupId);
                    if (advanced && completesSecret) {
                        DungeonSecretCompletionSound.play(dungeonConfig);
                    }
                    if (advanced && shouldHideCompletedDungeonRoomRoute(current)) {
                        manager.fireTransientDataChanged();
                    }
                });
                continue;
            }
            int from = group.currentIndex();
            int reachedIndex = interactedWaypointIndex(
                    group, pos.getX(), pos.getY(), pos.getZ(), from, superboomHeld);
            boolean advanced = reachedIndex >= 0 && advancePastReachedIndex(
                    group, from, reachedIndex,
                    restart && !isDungeonRoomRouteGroup(group),
                    config.allowBackwardProgress());
            if (advanced
                    && group.get(reachedIndex).hasFlag(Waypoint.FLAG_DUNGEON_SECRET)) {
                DungeonSecretCompletionSound.play(dungeonConfig);
            }
            if (advanced && shouldHideCompletedDungeonRoomRoute(group)) {
                manager.fireTransientDataChanged();
            }
        }
        return InteractionResult.PASS;
    }

    private InteractionResult onUseItem(Player player, Level world, InteractionHand hand) {
        if (!world.isClientSide() || !player.getItemInHand(hand).is(Items.ENDER_PEARL)) {
            return InteractionResult.PASS;
        }
        for (WaypointGroup group : manager.activeGroups()) {
            int current = group.currentIndex();
            if (current < 0 || current >= group.size()) continue;
            Waypoint waypoint = group.get(current);
            if (waypoint.isDisabled()
                    || !waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL)
                    || !isWithinReach(group, waypoint,
                    player.getX(), player.getY(), player.getZ())) {
                continue;
            }
            advancePastReachedIndex(
                    group, current, current, false, config.allowBackwardProgress());
        }
        return InteractionResult.PASS;
    }

    private void onTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            observedMineTargets.clear();
            return;
        }

        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();

        boolean loop = config.restartRouteWhenComplete();
        boolean globalSkipAhead = config.skipAheadMechanicEnabled();
        boolean skipOnlyVisible = config.skipAheadOnlyVisibleWaypoints();
        boolean hideReachedStatic = config.hideReachedStaticWaypointsUntilCycleComplete();
        boolean allowBackwardProgress = config.allowBackwardProgress();
        Set<MineTarget> liveMineTargets = new HashSet<>();
        for (WaypointGroup group : manager.activeGroups()) {
            int beforeIndex = group.currentIndex();
            boolean completingSecret = beforeIndex >= 0 && beforeIndex < group.size()
                    && group.get(beforeIndex).hasFlag(Waypoint.FLAG_DUNGEON_SECRET);
            boolean skippingEnabled = globalSkipAhead && group.skipAheadEnabled();
            boolean mined = updateMinedWaypointProgress(group, (x, y, z) -> {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return MINE_BLOCK_UNKNOWN;
                return level.getBlockState(pos).isAir() ? MINE_BLOCK_AIR : MINE_BLOCK_PRESENT;
            }, observedMineTargets, liveMineTargets, loop, skippingEnabled,
                    allowBackwardProgress);
            boolean changed = updateGroupProgress(group, px, py, pz, loop, globalSkipAhead,
                    skipOnlyVisible, hideReachedStatic, config.sequenceVisibility(),
                    allowBackwardProgress);
            if ((mined || changed) && completingSecret && group.currentIndex() != beforeIndex) {
                DungeonSecretCompletionSound.play(dungeonConfig);
            }
            if ((mined || changed) && shouldHideCompletedDungeonRoomRoute(group)) {
                manager.fireTransientDataChanged();
            }
        }
        observedMineTargets.retainAll(liveMineTargets);
    }

    public static boolean updateGroupProgress(WaypointGroup group,
                                              double px, double py, double pz,
                                              boolean restartWhenComplete,
                                              boolean globalSkipAhead,
                                              boolean skipOnlyVisible,
                                              boolean hideReachedStatic) {
        return updateGroupProgress(group, px, py, pz, restartWhenComplete, globalSkipAhead,
                skipOnlyVisible, hideReachedStatic, SequenceVisibility.DEFAULT, false);
    }

    static boolean updateGroupProgress(WaypointGroup group,
                                       double px, double py, double pz,
                                       boolean restartWhenComplete,
                                       boolean globalSkipAhead,
                                       boolean skipOnlyVisible,
                                       boolean hideReachedStatic,
                                       SequenceVisibility visibility) {
        return updateGroupProgress(group, px, py, pz, restartWhenComplete, globalSkipAhead,
                skipOnlyVisible, hideReachedStatic, visibility, false);
    }

    static boolean updateGroupProgress(WaypointGroup group,
                                       double px, double py, double pz,
                                       boolean restartWhenComplete,
                                       boolean globalSkipAhead,
                                       boolean skipOnlyVisible,
                                       boolean hideReachedStatic,
                                       SequenceVisibility visibility,
                                       boolean allowBackwardProgress) {
        // Temporary bucket groups are unordered and use their own expiry rules.
        if (group.temp()) return false;
        boolean releasedSubwaypointParentHold =
                releaseCompletionWrappedSubwaypointParentHoldIfOutsideReach(group, px, py, pz);

        if (hideReachedStatic && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            boolean markedStatic = markReachedStaticWaypoints(group, px, py, pz);
            return releasedSubwaypointParentHold || markedStatic;
        }

        // The global switch controls every route; groups can only opt out.
        boolean allowSkip = globalSkipAhead && group.skipAheadEnabled();
        boolean restart = restartWhenComplete && !isDungeonRoomRouteGroup(group);
        boolean advanced = advanceIfReached(group, px, py, pz, restart, allowSkip,
                skipOnlyVisible, visibility, allowBackwardProgress,
                System.currentTimeMillis());
        return releasedSubwaypointParentHold || advanced;
    }

    public static boolean updateGroupProgress(WaypointGroup group,
                                              double px, double py, double pz,
                                              boolean restartWhenComplete,
                                              boolean globalSkipAhead,
                                              boolean hideReachedStatic) {
        return updateGroupProgress(group, px, py, pz, restartWhenComplete, globalSkipAhead,
                false, hideReachedStatic);
    }

    private static boolean releaseCompletionWrappedSubwaypointParentHoldIfOutsideReach(WaypointGroup group,
                                                                                       double px,
                                                                                       double py,
                                                                                       double pz) {
        int activeParent = group.activeSubwaypointParentIndex();
        if (activeParent < 0) return false;
        if (group.nextMainIndexAfter(activeParent) >= 0) return false;

        Waypoint parent = group.get(activeParent);
        if (isWithinReach(group, parent, px, py, pz)) return false;
        return group.clearActiveSubwaypointParent();
    }

    /** Static groups scan every waypoint because they have no route order. */
    public static boolean markReachedStaticWaypoints(WaypointGroup group,
                                                     double px, double py, double pz) {
        return markReachedStaticWaypoints(group, px, py, pz, System.currentTimeMillis());
    }

    static boolean markReachedStaticWaypoints(WaypointGroup group,
                                             double px, double py, double pz,
                                             long nowMillis) {
        updateProximitySuppression(group, px, py, pz);
        resetStandSkipHoldIfNotStanding(group, px, py, pz);
        boolean[] changed = { false };
        double scanRadius = Math.max(group.maxEffectiveRadius(), STAND_SKIP_SCAN_RADIUS);
        group.forEachNearbyIndex(px, py, pz, scanRadius, i -> {
            if (group.isSubwaypoint(i)) return true;
            if (group.isWaypointDisabled(i)) return true;
            if (group.isStaticWaypointReached(i)) return true;
            if (group.isProximitySuppressed(i)) return true;

            Waypoint w = group.get(i);
            if (isReachedByRadiusOrStand(group, i, w, px, py, pz, nowMillis)) {
                if (group.markStaticWaypointReached(i)) {
                    changed[0] = true;
                    if (group.consumeStaticCycleJustCompleted()) {
                        return false;
                    }
                }
            }
            return true;
        });
        return changed[0];
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz) {
        return advanceIfReached(group, px, py, pz, false, true);
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, true);
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete, boolean allowSkipAhead) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkipAhead, false);
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete, boolean allowSkipAhead,
                                           boolean skipOnlyVisible) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkipAhead,
                skipOnlyVisible, SequenceVisibility.DEFAULT, false,
                System.currentTimeMillis());
    }

    private static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                            boolean restartWhenComplete, boolean allowSkipAhead,
                                            boolean skipOnlyVisible, SequenceVisibility visibility) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkipAhead,
                skipOnlyVisible, visibility, false, System.currentTimeMillis());
    }

    static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                    boolean restartWhenComplete, boolean allowSkipAhead,
                                    boolean skipOnlyVisible, long nowMillis) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkipAhead,
                skipOnlyVisible, SequenceVisibility.DEFAULT, false, nowMillis);
    }

    private static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                            boolean restartWhenComplete, boolean allowSkipAhead,
                                            boolean skipOnlyVisible, SequenceVisibility visibility,
                                            boolean allowBackwardProgress,
                                            long nowMillis) {
        if (group.isComplete() && !allowBackwardProgress) return false;
        updateProximitySuppression(group, px, py, pz);
        resetStandSkipHoldIfNotStanding(group, px, py, pz);
        if (allowBackwardProgress && group.proximitySuppressedIndex() >= 0) return false;

        int from = group.currentIndex();

        int reachedIndex;
        if (allowSkipAhead) {
            reachedIndex = group.isSubwaypoint(from)
                    ? currentReachedIndex(group, from, px, py, pz, nowMillis)
                    : -1;
            if (reachedIndex < 0) {
                reachedIndex = highestNearbyReachedIndex(group, from, px, py, pz,
                        skipOnlyVisible, visibility, nowMillis);
            }
        } else {
            reachedIndex = currentReachedIndex(group, from, px, py, pz, nowMillis);
        }
        if (reachedIndex < 0) {
            return BackwardProgressionPolicy.retreatIfReached(
                    group, allowBackwardProgress, px, py, pz);
        }

        return advancePastReachedIndex(
                group, from, reachedIndex, restartWhenComplete, allowBackwardProgress);
    }

    static boolean updateMinedWaypointProgress(WaypointGroup group,
                                               MineBlockLookup blocks,
                                               Set<MineTarget> observed,
                                               Set<MineTarget> live,
                                               boolean restartWhenComplete,
                                               boolean skippingEnabled) {
        return updateMinedWaypointProgress(group, blocks, observed, live,
                restartWhenComplete, skippingEnabled, false);
    }

    static boolean updateMinedWaypointProgress(WaypointGroup group,
                                               MineBlockLookup blocks,
                                               Set<MineTarget> observed,
                                               Set<MineTarget> live,
                                               boolean restartWhenComplete,
                                               boolean skippingEnabled,
                                               boolean allowBackwardProgress) {
        if (!isEligibleDungeonTriggerGroup(group) || blocks == null
                || observed == null || live == null) {
            return false;
        }

        int from = group.currentIndex();
        int to = group.loadMode() == WaypointGroup.LoadMode.STATIC || skippingEnabled
                ? group.size() - 1
                : from;
        int highestMined = -1;
        boolean staticChanged = false;
        for (int i = Math.max(0, from); i <= to; i++) {
            if (group.isWaypointDisabled(i)) continue;
            if (group.isSubwaypoint(i) && i != from) continue;
            Waypoint waypoint = group.get(i);
            if (!waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_MINE)) continue;

            MineTarget target = new MineTarget(
                    group.id(), i, waypoint.x(), waypoint.y(), waypoint.z());
            live.add(target);
            int state = blocks.stateAt(waypoint.x(), waypoint.y(), waypoint.z());
            if (state == MINE_BLOCK_PRESENT) {
                observed.add(target);
            } else if (state == MINE_BLOCK_AIR && observed.remove(target)) {
                if (group.loadMode() == WaypointGroup.LoadMode.STATIC) {
                    staticChanged |= group.markStaticWaypointReached(i);
                } else {
                    highestMined = i;
                }
            }
        }

        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) return staticChanged;
        return highestMined >= 0 && advancePastReachedIndex(
                group, from, highestMined,
                restartWhenComplete && !isDungeonRoomRouteGroup(group),
                allowBackwardProgress);
    }

    public static boolean advanceIfInteractedWithBlock(WaypointGroup group,
                                                       int blockX, int blockY, int blockZ,
                                                       boolean restartWhenComplete) {
        if (!isEligibleDungeonTriggerGroup(group)) return false;
        int from = group.currentIndex();
        int reachedIndex = interactedWaypointIndex(group, blockX, blockY, blockZ, from);
        return reachedIndex >= 0 && advancePastReachedIndex(group, from, reachedIndex,
                restartWhenComplete && !isDungeonRoomRouteGroup(group));
    }

    private static int interactedWaypointIndex(WaypointGroup group,
                                               int blockX, int blockY, int blockZ,
                                               int from) {
        return interactedWaypointIndex(group, blockX, blockY, blockZ, from, false);
    }

    private static int interactedWaypointIndex(WaypointGroup group,
                                               int blockX, int blockY, int blockZ,
                                               int from, boolean superboomHeld) {
        if (!isEligibleDungeonTriggerGroup(group)) return -1;
        int end = group.visibleMainSteps() > 0
                ? Math.min(group.size() - 1, from)
                : group.size() - 1;
        for (int i = end; i >= from; i--) {
            Waypoint waypoint = group.get(i);
            if (waypoint.isDisabled()) continue;
            if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_SUPERBOOM) && !superboomHeld) {
                continue;
            }
            if (waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT)
                    && waypointMatchesBlock(waypoint, blockX, blockY, blockZ)) {
                return i;
            }
        }
        return -1;
    }

    static boolean advanceDeferredInteraction(ActiveGroupManager manager,
                                              String groupId,
                                              int expectedCurrentIndex,
                                              int blockX, int blockY, int blockZ,
                                              boolean restartWhenComplete) {
        return advanceDeferredInteraction(manager, groupId, expectedCurrentIndex,
                blockX, blockY, blockZ, restartWhenComplete, false);
    }

    static boolean advanceDeferredInteraction(ActiveGroupManager manager,
                                              String groupId,
                                              int expectedCurrentIndex,
                                               int blockX, int blockY, int blockZ,
                                               boolean restartWhenComplete,
                                               boolean superboomHeld) {
        return advanceDeferredInteraction(manager, groupId, expectedCurrentIndex,
                blockX, blockY, blockZ, restartWhenComplete, superboomHeld, false);
    }

    static boolean advanceDeferredInteraction(ActiveGroupManager manager,
                                              String groupId,
                                              int expectedCurrentIndex,
                                              int blockX, int blockY, int blockZ,
                                              boolean restartWhenComplete,
                                              boolean superboomHeld,
                                              boolean allowBackwardProgress) {
        WaypointGroup current = manager == null ? null : manager.get(groupId);
        if (current == null || current.currentIndex() != expectedCurrentIndex) return false;
        int reachedIndex = interactedWaypointIndex(
                current, blockX, blockY, blockZ, expectedCurrentIndex, superboomHeld);
        return reachedIndex >= 0 && advancePastReachedIndex(
                current, expectedCurrentIndex, reachedIndex,
                restartWhenComplete && !isDungeonRoomRouteGroup(current),
                allowBackwardProgress);
    }

    private static boolean isChest(BlockState state) {
        return state != null && (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST));
    }

    private static boolean advancePastReachedIndex(WaypointGroup group, int from,
                                                   int reachedIndex,
                                                   boolean restartWhenComplete) {
        return advancePastReachedIndex(group, from, reachedIndex, restartWhenComplete, false);
    }

    private static boolean advancePastReachedIndex(WaypointGroup group, int from,
                                                   int reachedIndex,
                                                   boolean restartWhenComplete,
                                                   boolean suppressDepartedWaypoint) {
        Waypoint reachedWaypoint = group.get(reachedIndex);
        int retainedReachedIndex = reachedIndex;
        boolean reachedRetained = true;
        if (suppressDepartedWaypoint) group.suppressProximityUntilExit(reachedIndex);
        group.advancePast(reachedIndex);
        for (int j = reachedIndex; j >= from; j--) {
            if (group.isSubwaypoint(j)) continue;
            Waypoint wj = group.get(j);
            if (wj.tempMode() == Waypoint.TEMP_UNTIL_REACHED) {
                if (wj == reachedWaypoint) {
                    reachedRetained = false;
                } else if (j < retainedReachedIndex) {
                    retainedReachedIndex--;
                }
                group.remove(j);
            }
        }
        boolean restart = restartWhenComplete && !isDungeonRoomRouteGroup(group);
        group.restartIfRouteCompleted(restart);
        if (suppressDepartedWaypoint && group.proximitySuppressedIndex() < 0 && reachedRetained
                && retainedReachedIndex >= 0 && retainedReachedIndex < group.size()
                && group.get(retainedReachedIndex) == reachedWaypoint) {
            group.suppressProximityUntilExit(retainedReachedIndex);
        }
        if (restart && group.enabledMainWaypointCount() == 1) {
            group.suppressProximityUntilExit(group.currentIndex());
        }
        return true;
    }

    private static int currentReachedIndex(WaypointGroup group, int index,
                                           double px, double py, double pz,
                                           long nowMillis) {
        if (index < 0 || index >= group.size()) return -1;
        if (group.isWaypointDisabled(index)) return -1;
        if (group.isProximitySuppressed(index)) return -1;
        return isReachedByRadiusOrStand(group, index, group.get(index), px, py, pz, nowMillis)
                ? index
                : -1;
    }

    private static int highestNearbyReachedIndex(WaypointGroup group, int from,
                                                 double px, double py, double pz,
                                                 boolean skipOnlyVisible,
                                                 SequenceVisibility visibility,
                                                 long nowMillis) {
        boolean[] visible = skipOnlyVisible ? visibleIndexMask(group, visibility) : null;
        int[] reachedIndex = { -1 };
        double scanRadius = Math.max(group.maxEffectiveRadius(), STAND_SKIP_SCAN_RADIUS);
        group.forEachNearbyIndex(px, py, pz, scanRadius, i -> {
            if (group.isWaypointDisabled(i)) return true;
            if (i < from || group.isProximitySuppressed(i)) return true;
            if (i > from && visible != null && (i >= visible.length || !visible[i])) return true;
            if (i > from && group.isSubwaypoint(i)) return true;
            if (i <= reachedIndex[0]) return true;
            Waypoint waypoint = group.get(i);
            // A later main waypoint still wins over the current dungeon trigger.
            boolean reached = i > from
                    ? isWithinReach(group, waypoint, px, py, pz)
                    : isReachedByRadiusOrStand(group, i, waypoint, px, py, pz, nowMillis);
            if (reached) {
                reachedIndex[0] = i;
            }
            return true;
        });
        return reachedIndex[0];
    }

    private static boolean[] visibleIndexMask(WaypointGroup group, SequenceVisibility visibility) {
        boolean[] visible = new boolean[group.size()];
        group.forEachVisibleIndex(visibility, i -> {
            if (i >= 0 && i < visible.length) visible[i] = true;
        });
        return visible;
    }

    private static void updateProximitySuppression(WaypointGroup group,
                                                   double px, double py, double pz) {
        int index = group.proximitySuppressedIndex();
        if (index < 0) return;
        if (index >= group.size()) {
            group.clearProximitySuppression();
            return;
        }

        Waypoint w = group.get(index);
        if (!isWithinReach(group, w, px, py, pz)) {
            group.clearProximitySuppression();
        }
    }

    private static boolean isReachedByRadiusOrStand(WaypointGroup group, int index, Waypoint w,
                                                    double px, double py, double pz,
                                                    long nowMillis) {
        if (isDungeonStandSkipWaypoint(group, w)) {
            return isStandSkipReached(group, index, w, px, py, pz, nowMillis);
        }
        if (isDungeonInteractSkipWaypoint(group, w) || isDungeonMineSkipWaypoint(group, w)) {
            return false;
        }
        return isWithinReach(group, w, px, py, pz);
    }

    private static boolean isDungeonStandSkipWaypoint(WaypointGroup group, Waypoint w) {
        return isEligibleDungeonTriggerGroup(group)
                && w != null
                && w.hasFlag(Waypoint.FLAG_SKIP_ON_STAND);
    }

    private static boolean isDungeonInteractSkipWaypoint(WaypointGroup group, Waypoint w) {
        return isEligibleDungeonTriggerGroup(group)
                && w != null
                && w.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT);
    }

    private static boolean isDungeonMineSkipWaypoint(WaypointGroup group, Waypoint w) {
        return isEligibleDungeonTriggerGroup(group)
                && w != null
                && w.hasFlag(Waypoint.FLAG_SKIP_ON_MINE);
    }

    private static boolean isStandSkipReached(WaypointGroup group, int index, Waypoint w,
                                              double px, double py, double pz,
                                              long nowMillis) {
        return isStandSkipReached(group, index, w, px, py, pz, nowMillis,
                standSkipRequiresExactBlock(w));
    }

    static boolean isStandSkipReached(WaypointGroup group, int index, Waypoint w,
                                      double px, double py, double pz,
                                      long nowMillis,
                                      boolean requiresExactBlock) {
        if (!w.hasFlag(Waypoint.FLAG_SKIP_ON_STAND)) return false;
        boolean standingOnBlock = isStandingOnWaypointBlock(w, px, py, pz);
        if (standingOnBlock) {
            return group.standSkipHeldLongEnough(index, nowMillis, STAND_SKIP_HOLD_MS);
        }
        group.clearStandSkipHold(index);
        if (requiresExactBlock) {
            return false;
        }
        return isWithinReach(group, w, px, py, pz);
    }

    private static boolean standSkipRequiresExactBlock(Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || w == null) return true;
        BlockPos pos = new BlockPos(w.x(), w.y(), w.z());
        BlockState state = mc.level.getBlockState(pos);
        return state == null || !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private static void resetStandSkipHoldIfNotStanding(WaypointGroup group,
                                                        double px, double py, double pz) {
        int heldIndex = group.standSkipHoldIndex();
        if (heldIndex < 0) return;
        if (heldIndex >= group.size()) {
            group.clearStandSkipHold();
            return;
        }
        Waypoint held = group.get(heldIndex);
        if (!isDungeonStandSkipWaypoint(group, held)
                || !isStandingOnWaypointBlock(held, px, py, pz)) {
            group.clearStandSkipHold(heldIndex);
        }
    }

    private static boolean isStandingOnWaypointBlock(Waypoint w,
                                                     double px, double py, double pz) {
        int feetX = blockCoordinate(px);
        int feetY = blockCoordinate(py);
        int feetZ = blockCoordinate(pz);
        return waypointMatchesBlock(w, feetX, feetY, feetZ)
                || waypointMatchesBlock(w, feetX, blockCoordinate(py - 0.01D), feetZ);
    }

    private static boolean shouldHideCompletedDungeonRoomRoute(WaypointGroup group) {
        return isDungeonRoomRouteGroup(group) && group.isComplete();
    }

    private static boolean isDungeonRoomRouteGroup(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    private static boolean isEligibleDungeonTriggerGroup(WaypointGroup group) {
        return isDungeonRoomRouteGroup(group) && !group.isComplete();
    }

    private static boolean waypointMatchesBlock(Waypoint w, int blockX, int blockY, int blockZ) {
        return w.x() == blockX && w.y() == blockY && w.z() == blockZ;
    }

    private static int blockCoordinate(double value) {
        return (int) Math.floor(value);
    }

    private static boolean isWithinReach(WaypointGroup group, Waypoint w,
                                          double px, double py, double pz) {
        double r = group.effectiveRadius(w);
        double dx = w.centerX() - px;
        double dy = w.centerY() - py;
        double dz = w.centerZ() - pz;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    @FunctionalInterface
    interface MineBlockLookup {
        int stateAt(int x, int y, int z);
    }

    record MineTarget(String groupId, int index, int x, int y, int z) {
    }
}
