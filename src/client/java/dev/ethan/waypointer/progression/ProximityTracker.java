package dev.ethan.waypointer.progression;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public ProximityTracker(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    /*[[AI-FN-DOC
Function:
onTick
Purpose:
Sample the local player position once per client tick and update every active group's progression.
Why this exists:
Progression is driven by player proximity, so the client tick is the central place to gather config flags and route updates.
When to use:
Registered by install as the END_CLIENT_TICK callback. Do not call from render code.
Inputs:
mc is the Minecraft client instance supplied by Fabric; it may have a null player when not in a world.
Outputs:
No return value. Active groups may be advanced or have static reach state updated.
Side effects:
Reads player position and config values, then mutates active waypoint groups through updateGroupProgress.
Failure modes:
If no player exists, returns without work. Group-level helpers handle empty, complete, and temp groups.
Important invariants:
Config values should be read once per tick so all groups update under one consistent policy snapshot.
Internal logic:
Return when player is absent, capture coordinates and progression settings, then update each active group.
Pseudocode:
player = mc.player
if player null, return
read player x/y/z
read loop, global skip, visible-only skip, and static hide settings
for each active group, update progress with those settings
Implementation notes:
The visible-only skip setting is threaded here rather than read in static helpers so tests can call helpers with explicit policies.
AI self-check:
Verify skipAheadOnlyVisibleWaypoints is passed to updateGroupProgress.
]]*/
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
            updateGroupProgress(group, px, py, pz, loop, globalSkipAhead,
                    skipOnlyVisible, hideReachedStatic);
        }
    }

    /*[[AI-FN-DOC
Function:
updateGroupProgress
Purpose:
Advance one active waypoint group for the player's current position using the current route progression settings.
Why this exists:
The tick loop needs one testable entry point that applies temp-group, static-route, skip-ahead, and restart rules consistently.
When to use:
Use from the client tick path and tests that need to verify a single group's progression. Do not use for manual skip commands because those intentionally bypass proximity checks.
Inputs:
group is the mutable route to update and must not be null; px, py, and pz are player world coordinates; restartWhenComplete controls loop reset; globalSkipAhead is the master skip-ahead toggle; skipOnlyVisible limits automatic jumps to visible route context; hideReachedStatic enables static checklist hiding.
Outputs:
No return value. The group may mutate current progress or static reach state.
Side effects:
May mutate route progress, static reach bits, proximity suppression, and temp waypoint membership.
Failure modes:
Temp groups return without mutation. Empty or complete groups are ignored by lower-level advancement helpers.
Important invariants:
Static reach tracking and sequence advancement must remain mutually exclusive in a tick. The global skip setting must always override per-route skip enablement.
Internal logic:
Ignore temp containers, route static checklist mode to markReachedStaticWaypoints, combine global and group skip gates, then advance through proximity.
Pseudocode:
if group is temp, return
if static reach hiding applies, mark static waypoints and return
allowSkip = globalSkipAhead and group.skipAheadEnabled
advanceIfReached with restart, allowSkip, and visible-only flag
Implementation notes:
This overload preserves the older public signature below for tests and callers that do not care about visible-only skip.
AI self-check:
Confirm the client tick passes config.skipAheadOnlyVisibleWaypoints and legacy tests still compile through the compatibility overload.
]]*/
    public static void updateGroupProgress(WaypointGroup group,
                                           double px, double py, double pz,
                                           boolean restartWhenComplete,
                                           boolean globalSkipAhead,
                                           boolean skipOnlyVisible,
                                           boolean hideReachedStatic) {
        // Temp-only bucket groups don't participate in progression -- they hold
        // ad-hoc markers whose own expiry modes handle cleanup. Running proximity
        // on them would re-enter the "advance past waypoint" logic on a container
        // whose order is meaningless.
        if (group.temp()) return;

        if (hideReachedStatic && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            markReachedStaticWaypoints(group, px, py, pz);
            return;
        }

        // Group-level skip-ahead gate. Global off always wins over group on --
        // the config is the master switch; the group flag is a per-route opt-out.
        boolean allowSkip = globalSkipAhead && group.skipAheadEnabled();
        advanceIfReached(group, px, py, pz, restartWhenComplete, allowSkip, skipOnlyVisible);
    }

    /*[[AI-FN-DOC
Function:
updateGroupProgress
Purpose:
Compatibility overload for updating group progress without explicitly supplying visible-only skip policy.
Why this exists:
Existing tests and callers use the older signature, so keeping this overload minimizes churn while the new full overload carries the setting.
When to use:
Use from legacy tests that do not care about visible-only skip. Prefer the overload with skipOnlyVisible when wiring real config.
Inputs:
group is the route; px, py, pz are player coordinates; restartWhenComplete controls looping; globalSkipAhead is the master skip toggle; hideReachedStatic controls static checklist mode.
Outputs:
No return value. Delegates to the full overload.
Side effects:
May mutate group progression through the delegated call.
Failure modes:
Same as the full overload.
Important invariants:
This overload preserves legacy behavior by passing skipOnlyVisible=false.
Internal logic:
Call the full updateGroupProgress overload with skipOnlyVisible false.
Pseudocode:
updateGroupProgress(group, px, py, pz, restartWhenComplete, globalSkipAhead, false, hideReachedStatic)
Implementation notes:
Keeping the legacy policy here avoids silently changing old tests that are not about the new setting.
AI self-check:
Confirm call sites that should use the default config setting call the full overload from onTick.
]]*/
    public static void updateGroupProgress(WaypointGroup group,
                                           double px, double py, double pz,
                                           boolean restartWhenComplete,
                                           boolean globalSkipAhead,
                                           boolean hideReachedStatic) {
        updateGroupProgress(group, px, py, pz, restartWhenComplete, globalSkipAhead,
                false, hideReachedStatic);
    }

    /**
     * Static groups are unordered map overlays, so reach tracking scans every
     * waypoint instead of advancing a single route index. Reaching the final
     * hidden marker resets the group immediately (handled by WaypointGroup),
     * making the next cycle visible without requiring a reconnect or command.
     */
    public static boolean markReachedStaticWaypoints(WaypointGroup group,
                                                     double px, double py, double pz) {
        updateProximitySuppression(group, px, py, pz);
        boolean[] changed = { false };
        group.forEachNearbyIndex(px, py, pz, group.maxEffectiveRadius(), i -> {
            if (group.isSubwaypoint(i)) return true;
            if (group.isStaticWaypointReached(i)) return true;
            if (group.isProximitySuppressed(i)) return true;

            Waypoint w = group.get(i);
            double r = group.effectiveRadius(w);
            double dx = (w.x() + 0.5) - px;
            double dy = (w.y() + 0.5) - py;
            double dz = (w.z() + 0.5) - pz;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
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

    /*[[AI-FN-DOC
Function:
advanceIfReached
Purpose:
Advance a route when the player is within reach of the current waypoint or an eligible future waypoint.
Why this exists:
Progression behavior is shared by the tick loop and unit tests, and now needs to distinguish legacy farthest-skip behavior from visible-context skip behavior.
When to use:
Use for automatic proximity progression. Do not use for explicit command jumps because explicit commands should set the target directly.
Inputs:
group is the mutable route; px, py, pz are player coordinates; restartWhenComplete controls route looping; allowSkipAhead enables future waypoint checks; skipOnlyVisible restricts future checks to visible route-context indices when allowSkipAhead is true.
Outputs:
Returns true when the group advanced or removed reached temp waypoints, false when no eligible waypoint was reached.
Side effects:
May clear proximity suppression, advance current route index, remove TEMP_UNTIL_REACHED waypoints, and restart completed routes.
Failure modes:
Complete groups return false. Suppressed waypoints are ignored until the player leaves their radius. Invalid indices are guarded by helper methods.
Important invariants:
Normal current-waypoint advancement must work even when skipOnlyVisible is true. Subwaypoints are not automatic skip-ahead targets unless they are the explicit current target.
Internal logic:
Skip complete groups, update suppression, choose the reached index with either current-only or skip-ahead logic, remove reached temp waypoints, then optionally restart completed routes.
Pseudocode:
if group is complete, return false
update proximity suppression
from = group.currentIndex
if skip-ahead is allowed:
  if current target is a subwaypoint, test that exact target first
  if no subwaypoint target was reached, find highest nearby eligible reached index using visible filtering when requested
otherwise test only current index
if no reached index, return false
advance group past reached index
remove reached temp main waypoints from reached down to from
restart if configured
return true
Implementation notes:
The visible-only flag is threaded into the skip helper rather than baked into the group so tests can exercise both policies without mutating config.
AI self-check:
Verify current-only progression is unchanged and future skip candidates are capped to forEachVisibleIndex when requested.
]]*/
    public static boolean advanceIfReached(WaypointGroup group, double px, double py, double pz,
                                           boolean restartWhenComplete, boolean allowSkipAhead,
                                           boolean skipOnlyVisible) {
        if (group.isComplete()) return false;
        updateProximitySuppression(group, px, py, pz);

        int size = group.size();
        int from = group.currentIndex();

        int reachedIndex;
        if (allowSkipAhead) {
            reachedIndex = group.isSubwaypoint(from)
                    ? currentReachedIndex(group, from, px, py, pz)
                    : -1;
            if (reachedIndex < 0) {
                reachedIndex = highestNearbyReachedIndex(group, from, px, py, pz, skipOnlyVisible);
            }
        } else {
            reachedIndex = currentReachedIndex(group, from, px, py, pz);
        }
        if (reachedIndex < 0) return false;

        // Collect reach-based temps in [from..reachedIndex] BEFORE advancing,
        // because advancing changes currentIndex which we use to bound the scan.
        // Remove in reverse so earlier indices don't shift under us.
        group.advancePast(reachedIndex);
        for (int j = reachedIndex; j >= from; j--) {
            if (group.isSubwaypoint(j)) continue;
            Waypoint wj = group.get(j);
            if (wj.tempMode() == Waypoint.TEMP_UNTIL_REACHED) {
                group.remove(j);
            }
        }
        group.restartIfRouteCompleted(restartWhenComplete);
        return true;
    }

    /*[[AI-FN-DOC
Function:
currentReachedIndex
Purpose:
Check whether the exact current route target is within reach.
Why this exists:
Strict progression and explicit subwaypoint targets must test the current index directly instead of relying on the main-waypoint spatial skip scan.
When to use:
Use inside advanceIfReached before optional future skip-ahead scanning.
Inputs:
group is the route; index is the current target index; px, py, pz are player coordinates.
Outputs:
Returns index when the current target is reachable, otherwise -1.
Side effects:
None.
Failure modes:
Invalid indices and proximity-suppressed indices return -1.
Important invariants:
Subwaypoint current targets are allowed here so /wp skipto decimal targets can advance.
Internal logic:
Validate bounds and suppression, then delegate distance math to isWithinReach.
Pseudocode:
if index out of bounds, return -1
if index is proximity suppressed, return -1
if waypoint at index is within reach, return index
return -1
Implementation notes:
Subwaypoints are intentionally not filtered in this helper; automatic future child skips remain blocked in highestNearbyReachedIndex.
AI self-check:
Verify explicitSubwaypointTargetAdvancesWhenReached covers the subwaypoint path.
]]*/
    private static int currentReachedIndex(WaypointGroup group, int index,
                                            double px, double py, double pz) {
        if (index < 0 || index >= group.size()) return -1;
        if (group.isProximitySuppressed(index)) return -1;
        return isWithinReach(group, group.get(index), px, py, pz) ? index : -1;
    }

    /*[[AI-FN-DOC
Function:
highestNearbyReachedIndex
Purpose:
Find the farthest eligible future waypoint currently within reach for automatic skip-ahead.
Why this exists:
Skip-ahead needs a spatial-index scan for performance, while the new visible-only setting needs an additional eligibility filter.
When to use:
Use only from advanceIfReached when automatic skip-ahead is enabled. Do not use for strict current progression or explicit commands.
Inputs:
group is the route being progressed; from is the current route index lower bound; px, py, pz are player coordinates; skipOnlyVisible controls whether future candidates must be in group.forEachVisibleIndex.
Outputs:
Returns the highest eligible reached index, or -1 when none is reachable.
Side effects:
No persistent side effects. Allocates a small visibility mask only when visible filtering is enabled.
Failure modes:
Invalid or empty groups naturally return -1 through the spatial scan. Suppressed and subwaypoint candidates are skipped unless current progression handles them separately.
Important invariants:
Candidates before the current index must never count. The current explicit subwaypoint target remains handled by currentReachedIndex before this helper is considered.
Internal logic:
Optionally build a boolean mask of visible indices, scan nearby spatial-index candidates, skip ineligible indices, and keep the highest reached index.
Pseudocode:
visible = build mask if skipOnlyVisible else null
reached = -1
for each nearby index:
  if index is before from, continue
  if visible mask exists and index is not visible, continue
  if index is suppressed or subwaypoint, continue
  if index is not beyond current best, continue
  if waypoint is within reach, store index
return reached
Implementation notes:
The spatial index currently stores main waypoints only, so subwaypoint skip-ahead is intentionally command-driven/current-target-driven rather than automatic far-future child jumping.
AI self-check:
Confirm visible filtering is only applied when requested and legacy behavior remains available through skipOnlyVisible=false.
]]*/
    private static int highestNearbyReachedIndex(WaypointGroup group, int from,
                                                 double px, double py, double pz,
                                                 boolean skipOnlyVisible) {
        boolean[] visible = skipOnlyVisible ? visibleIndexMask(group) : null;
        int[] reachedIndex = { -1 };
        group.forEachNearbyIndex(px, py, pz, group.maxEffectiveRadius(), i -> {
            if (i < from || group.isProximitySuppressed(i)) return true;
            if (visible != null && (i >= visible.length || !visible[i])) return true;
            if (group.isSubwaypoint(i)) return true;
            if (i <= reachedIndex[0]) return true;
            if (isWithinReach(group, group.get(i), px, py, pz)) {
                reachedIndex[0] = i;
            }
            return true;
        });
        return reachedIndex[0];
    }

    /*[[AI-FN-DOC
Function:
visibleIndexMask
Purpose:
Build a compact lookup table for the route indices surfaced by WaypointGroup.forEachVisibleIndex.
Why this exists:
Automatic skip-ahead checks candidate indices from a spatial index, and testing visibility repeatedly through callbacks would be awkward and error-prone.
When to use:
Use when a progression helper needs O(1) membership checks against the current visible route context. Do not store the result beyond the current tick.
Inputs:
group is the route whose visible indices should be captured; it must not be null.
Outputs:
Returns a boolean array sized to group.size() with true for each visible index.
Side effects:
None beyond allocating the boolean array and invoking the group's visibility callback.
Failure modes:
Empty groups produce an empty array. Out-of-range callback values are ignored defensively.
Important invariants:
The mask reflects the group's current load mode and focus state at the moment it is built.
Internal logic:
Allocate an array, iterate visible indices, and mark valid entries true.
Pseudocode:
mask = new boolean[group.size]
for each visible index:
  if index is within mask bounds, set mask[index] true
return mask
Implementation notes:
This is intentionally per-call so route edits or current-index changes cannot leave stale visibility state behind.
AI self-check:
Verify callers only use this for immediate skip filtering and not as cached route state.
]]*/
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

    /*[[AI-FN-DOC
Function:
isWithinReach.
Purpose:
Determine whether the player is within the effective reach radius of a waypoint's center.
Why this exists:
Progression needs one distance helper that handles normal block-centered waypoints and precise small subwaypoints consistently.
When to use:
Use from proximity progression helpers whenever testing whether a player has reached a waypoint. Do not use for render culling, which has separate distance policies.
Inputs:
group supplies the effective radius policy; w is the waypoint being tested and must not be null; px/py/pz are player world coordinates.
Outputs:
Returns true when squared distance from the player's position to the waypoint center is within radius squared.
Side effects:
None.
Failure modes:
None expected for finite player coordinates. Non-finite inputs naturally produce false through the squared comparison.
Important invariants:
Small waypoint precise centers must count for explicit subwaypoint targets, while legacy waypoints still resolve to x/y/z plus 0.5.
Internal logic:
Read the effective radius, subtract player coordinates from waypoint centerX/centerY/centerZ, compare squared distance against squared radius.
Pseudocode:
r = group.effectiveRadius(w)
dx = w.centerX - px
dy = w.centerY - py
dz = w.centerZ - pz
return dx*dx + dy*dy + dz*dz <= r*r
Implementation notes:
Using center methods keeps this helper aligned with labels, connectors, and the small waypoint renderer.
AI self-check:
Verify block-centered waypoint reach behavior is unchanged because default centers are x/y/z + 0.5.
]]*/
    private static boolean isWithinReach(WaypointGroup group, Waypoint w,
                                          double px, double py, double pz) {
        double r = group.effectiveRadius(w);
        double dx = w.centerX() - px;
        double dy = w.centerY() - py;
        double dz = w.centerZ() - pz;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }
}
