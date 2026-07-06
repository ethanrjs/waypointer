package dev.ethan.waypointer.progression;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Watches the local player each client tick and advances any active group whose
 * next-or-later waypoint is within {@link WaypointGroup#effectiveRadius(Waypoint)}.
 *
 * Advancing past a later waypoint (skipping ahead) is a first-class operation: if the
 * player walks near waypoint N+3 before N, the group jumps straight to N+4. That
 * matters for dungeon speedruns where players intentionally cut corners.
 *
 * Large imported routes can contain thousands of points, so the tracker asks each
 * group for nearby spatial-index candidates instead of walking the whole list
 * every tick. When skip-ahead is enabled we still choose the highest reachable
 * index, preserving the old "farthest-ahead waypoint wins" behaviour.
 */
public final class ProximityTracker {

    private static final double STAND_SKIP_SCAN_RADIUS = 1.75D;
    static final long STAND_SKIP_HOLD_MS = 500L;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public ProximityTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        UseBlockCallback.EVENT.register(this::onUseBlock);
    }

    private InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                         BlockHitResult hit) {
        if (!world.isClientSide()) return InteractionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        boolean restart = config.restartRouteWhenComplete();
        for (WaypointGroup group : manager.activeGroups()) {
            boolean advanced = advanceIfInteractedWithBlock(group, pos.getX(), pos.getY(), pos.getZ(), restart);
            if (advanced && shouldHideCompletedDungeonRoomRoute(group)) {
                manager.fireDataChanged();
            }
        }
        return InteractionResult.PASS;
    }

    private void onTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();

        // Groups keep their own defaultRadius for progression checks; the config value
        // is used as the starting radius for groups created through commands/UI so the
        // player's preferred feel is baked in from day one.
        boolean loop = config.restartRouteWhenComplete();
        boolean globalSkipAhead = config.skipAheadMechanicEnabled();
        boolean skipOnlyVisible = config.skipAheadOnlyVisibleWaypoints();
        boolean hideReachedStatic = config.hideReachedStaticWaypointsUntilCycleComplete();
        for (WaypointGroup group : manager.activeGroups()) {
            boolean changed = updateGroupProgress(group, px, py, pz, loop, globalSkipAhead,
                    skipOnlyVisible, hideReachedStatic);
            if (changed && shouldHideCompletedDungeonRoomRoute(group)) {
                manager.fireDataChanged();
            }
        }
    }

    public static boolean updateGroupProgress(WaypointGroup group,
                                              double px, double py, double pz,
                                              boolean restartWhenComplete,
                                              boolean globalSkipAhead,
                                              boolean skipOnlyVisible,
                                              boolean hideReachedStatic) {
        // Temp-only bucket groups don't participate in progression -- they hold
        // ad-hoc markers whose own expiry modes handle cleanup. Running proximity
        // on them would re-enter the "advance past waypoint" logic on a container
        // whose order is meaningless.
        if (group.temp()) return false;

        boolean releasedSubwaypointParentHold =
                releaseCompletionWrappedSubwaypointParentHoldIfOutsideReach(group, px, py, pz);

        if (hideReachedStatic && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            boolean markedStatic = markReachedStaticWaypoints(group, px, py, pz);
            return releasedSubwaypointParentHold || markedStatic;
        }

        // Group-level skip-ahead gate. Global off always wins over group on --
        // the config is the master switch; the group flag is a per-route opt-out.
        boolean allowSkip = globalSkipAhead && group.skipAheadEnabled();
        boolean restart = restartWhenComplete && !isDungeonRoomRouteGroup(group);
        boolean advanced = advanceIfReached(group, px, py, pz, restart, allowSkip, skipOnlyVisible);
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

    /**
     * Static groups are unordered map overlays, so reach tracking scans every
     * waypoint instead of advancing a single route index. Reaching the final
     * hidden marker resets the group immediately (handled by WaypointGroup),
     * making the next cycle visible without requiring a reconnect or command.
     */
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

    /**
     * Reverse-scan from the last waypoint down to {@code currentIndex}; if any is within
     * reach, jump past it. Visible for tests so progression logic stays unit-testable
     * without needing a live client.
     *
     * The 4-arg overload defaults {@code restartWhenComplete} to {@code false} so
     * unit tests can assert "route complete" without the loop behaviour.
     */
    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz) {
        return advanceIfReached(group, px, py, pz, false, true);
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, true);
    }

    /**
     * @param restartWhenComplete when {@code true}, completing the last waypoint
     *                              immediately resets progress to the start (see
     *                              {@link WaypointGroup#restartIfRouteCompleted(boolean)}).
     * @param allowSkipAhead      when {@code true} (default behaviour), a hit on
     *                              waypoint N+3 advances past N+3 in one step --
     *                              the "corner-cutting" mode. When {@code false},
     *                              only the immediate next waypoint counts; the
     *                              player has to visit each one in order. The
     *                              config flag threads through here verbatim.
     */
    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete, boolean allowSkipAhead) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkipAhead, false);
    }

    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete, boolean allowSkipAhead,
                                           boolean skipOnlyVisible) {
        return advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkipAhead,
                skipOnlyVisible, System.currentTimeMillis());
    }

    static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                    boolean restartWhenComplete, boolean allowSkipAhead,
                                    boolean skipOnlyVisible, long nowMillis) {
        if (group.isComplete()) return false;
        updateProximitySuppression(group, px, py, pz);
        resetStandSkipHoldIfNotStanding(group, px, py, pz);

        int size = group.size();
        int from = group.currentIndex();

        int reachedIndex;
        if (allowSkipAhead) {
            reachedIndex = group.isSubwaypoint(from)
                    ? currentReachedIndex(group, from, px, py, pz, nowMillis)
                    : -1;
            if (reachedIndex < 0) {
                reachedIndex = highestNearbyReachedIndex(group, from, px, py, pz,
                        skipOnlyVisible, nowMillis);
            }
        } else {
            reachedIndex = currentReachedIndex(group, from, px, py, pz, nowMillis);
        }
        if (reachedIndex < 0) return false;

        return advancePastReachedIndex(group, from, reachedIndex, restartWhenComplete);
    }

    public static boolean advanceIfInteractedWithBlock(WaypointGroup group,
                                                       int blockX, int blockY, int blockZ,
                                                       boolean restartWhenComplete) {
        if (!isEligibleDungeonTriggerGroup(group)) return false;
        int from = group.currentIndex();
        for (int i = group.size() - 1; i >= from; i--) {
            Waypoint waypoint = group.get(i);
            if (!waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT)) continue;
            if (!waypointMatchesBlock(waypoint, blockX, blockY, blockZ)) continue;
            return advancePastReachedIndex(group, from, i,
                    restartWhenComplete && !isDungeonRoomRouteGroup(group));
        }
        return false;
    }

    private static boolean advancePastReachedIndex(WaypointGroup group, int from,
                                                   int reachedIndex,
                                                   boolean restartWhenComplete) {
        group.advancePast(reachedIndex);
        for (int j = reachedIndex; j >= from; j--) {
            if (group.isSubwaypoint(j)) continue;
            Waypoint wj = group.get(j);
            if (wj.tempMode() == Waypoint.TEMP_UNTIL_REACHED) {
                group.remove(j);
            }
        }
        group.restartIfRouteCompleted(restartWhenComplete && !isDungeonRoomRouteGroup(group));
        return true;
    }

    private static int currentReachedIndex(WaypointGroup group, int index,
                                           double px, double py, double pz,
                                           long nowMillis) {
        if (index < 0 || index >= group.size()) return -1;
        if (group.isProximitySuppressed(index)) return -1;
        return isReachedByRadiusOrStand(group, index, group.get(index), px, py, pz, nowMillis)
                ? index
                : -1;
    }

    private static int highestNearbyReachedIndex(WaypointGroup group, int from,
                                                 double px, double py, double pz,
                                                 boolean skipOnlyVisible,
                                                 long nowMillis) {
        boolean[] visible = skipOnlyVisible ? visibleIndexMask(group) : null;
        int[] reachedIndex = { -1 };
        double scanRadius = Math.max(group.maxEffectiveRadius(), STAND_SKIP_SCAN_RADIUS);
        group.forEachNearbyIndex(px, py, pz, scanRadius, i -> {
            if (i < from || group.isProximitySuppressed(i)) return true;
            if (visible != null && (i >= visible.length || !visible[i])) return true;
            if (i > from && !isAutomaticSkipAheadCandidate(group, i)) return true;
            if (i <= reachedIndex[0]) return true;
            if (isReachedByRadiusOrStand(group, i, group.get(i), px, py, pz, nowMillis)) {
                reachedIndex[0] = i;
            }
            return true;
        });
        return reachedIndex[0];
    }

    private static boolean isAutomaticSkipAheadCandidate(WaypointGroup group, int index) {
        if (index < 0 || index >= group.size()) return false;
        if (group.isSubwaypoint(index)) return false;
        Waypoint waypoint = group.get(index);
        return !waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_STAND)
                && !waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT);
    }

    private static boolean[] visibleIndexMask(WaypointGroup group) {
        boolean[] visible = new boolean[group.size()];
        group.forEachVisibleIndex(i -> {
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
        if (isDungeonInteractSkipWaypoint(group, w)) return false;
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
                && DungeonRoomData.definition(group.zoneId()) != null;
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
}
