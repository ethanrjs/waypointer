package dev.ethan.waypointer.core;

import dev.ethan.waypointer.color.GradientColorizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * An ordered, named route of {@link Waypoint}s bound to a single {@link Zone}.
 *
 * Mutable so the UI can rename/reorder without thrashing GC, but structural mutations
 * always run on the client thread (guaranteed by driving them from ticks and Screen callbacks).
 *
 * Progress tracking lives on the group, not on individual waypoints. That keeps
 * waypoints pure data and lets us swap groups in/out on zone changes without losing state.
 */
public final class WaypointGroup {

    /**
     * Spatial bucket edge length for proximity checks. The default reach radius
     * is 3 blocks, so 16 keeps normal queries to a small neighbourhood while
     * still being coarse enough that large imports don't create huge maps.
     */
    private static final int PROXIMITY_CELL_SIZE = 16;

    public enum GradientMode {
        /** Every waypoint uses the group's single static color. */
        STATIC,
        /** Colors are auto-interpolated across the list; manual edits to unlocked entries get overwritten. */
        AUTO,
        /** Each waypoint keeps its own color. Reordering does not recolor. */
        MANUAL
    }

    /**
     * How many waypoints of the group are surfaced to the renderer at once.
     *
     * SEQUENCE is the default because loaded routes usually have an intended
     * order. It renders only the neighborhood of {@code currentIndex}, which
     * keeps the HUD clean on long routes. STATIC remains available for map-like
     * overlays where every point should be visible at once.
     */
    public enum LoadMode {
        /** All waypoints render at once (subject to FLAG_HIDE_BEACON / completion state). */
        STATIC,
        /** Only the previous/current/next waypoints around {@code currentIndex} render. */
        SEQUENCE
    }

    private final String id;
    private String name;
    private String zoneId;
    private final List<Waypoint> waypoints;
    private int currentIndex;
    private boolean enabled;
    private GradientMode gradientMode;
    private LoadMode loadMode;
    private double defaultRadius;
    /**
     * Per-group gate for the proximity skip-ahead mechanic. When {@code false},
     * the proximity tracker only advances when the player reaches the
     * immediate current waypoint on this group, even if the global mechanic is
     * on. Flipped off automatically when a new waypoint is added so a
     * freshly-added waypoint near the player isn't instantly skipped past.
     */
    private boolean skipAheadEnabled = true;
    /**
     * Marks a group as a container for temporary-only waypoints (the dedicated
     * "Temp Waypoints" bucket per zone). Temp groups are excluded from the
     * progression pipeline -- proximity never advances them, completion never
     * resets them -- because their contents come and go on their own schedule
     * and shouldn't interact with the player's route through the zone.
     */
    private boolean temp = false;
    /** Single-color route palette used when {@link #gradientMode} is {@link GradientMode#STATIC}. */
    private int staticColor = Waypoint.DEFAULT_COLOR;
    // Per-group gradient endpoints (RGB). Each group can pick its own palette so a
    // Foraging route and a Dungeons route don't have to share one theme. Defaults
    // match the old globals: cyan start, red end -- picked to read as cool → hot
    // so "next" is visually the calmest point on a route.
    private int gradientStartColor = 0x00BFFF;
    private int gradientEndColor   = 0xFF3040;
    /**
     * Session-only reach state for static-route cycling. Unlike currentIndex,
     * this is unordered: a static map overlay lets the player visit points in
     * any order, hiding each one until the whole set has been touched.
     */
    private transient boolean[] staticReached;
    /**
     * Newly-created waypoints are often at the player's feet. Suppress proximity
     * on that one index until the player leaves its radius, otherwise the tick
     * loop immediately marks it reached and the user never sees feedback that
     * the add succeeded.
     */
    private transient int proximitySuppressedIndex = -1;
    /**
     * Optional render-only focus for temp waypoint mode. Kept on the group so
     * index shifts caused by temp expiry/removal stay local to the list mutation
     * that caused them, instead of leaving a manager-level pointer stale.
     */
    private transient Integer focusedVisibleIndex;
    /**
     * Sequence-mode visual hold for a reached main waypoint that owns
     * subwaypoints. Progress still advances to the next main waypoint so the
     * tracer navigates forward, but renderers keep this parent and its children
     * bright until the next main waypoint is reached.
     */
    private transient int activeSubwaypointParentIndex = -1;
    /**
     * Set when a static reach pass completes the full set and clears {@link #staticReached}.
     * Lets {@link dev.ethan.waypointer.progression.ProximityTracker} stop scanning for the
     * rest of the tick so every waypoint shows as visible for at least one frame.
     */
    private transient boolean staticCycleJustCompleted;
    private transient ProximityIndex proximityIndex;

        public WaypointGroup(String id, String name, String zoneId) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null ? "" : name;
        this.zoneId = Zone.canonicalId(Objects.requireNonNull(zoneId));
        this.waypoints = new ArrayList<>();
        this.currentIndex = 0;
        this.enabled = true;
        this.gradientMode = GradientMode.AUTO;
        this.loadMode = LoadMode.SEQUENCE;
        this.defaultRadius = 3.0;
    }

    public static WaypointGroup create(String name, String zoneId) {
        return new WaypointGroup(UUID.randomUUID().toString(), name, zoneId);
    }

    public static WaypointGroup create(String name, String zoneId, boolean skipAheadEnabled) {
        WaypointGroup group = create(name, zoneId);
        group.setSkipAheadEnabled(skipAheadEnabled);
        return group;
    }

    public String id()            { return id; }
    public String name()          { return name; }
    public String zoneId()        { return zoneId; }
    public int currentIndex()     { return currentIndex; }
    public boolean enabled()      { return enabled; }
    public GradientMode gradientMode() { return gradientMode; }
    public LoadMode loadMode()    { return loadMode; }
    public double defaultRadius() { return defaultRadius; }
        public int staticColor()      { return staticColor; }
    public int gradientStartColor() { return gradientStartColor; }
    public int gradientEndColor()   { return gradientEndColor; }
    public boolean skipAheadEnabled() { return skipAheadEnabled; }
    public boolean temp()           { return temp; }
    public List<Waypoint> waypoints() { return Collections.unmodifiableList(waypoints); }
    public int size()             { return waypoints.size(); }
    public boolean isEmpty()      { return waypoints.isEmpty(); }
    public boolean isComplete()   { return currentIndex >= waypoints.size(); }

    public boolean isSubwaypoint(int index) {
        return index >= 0 && index < waypoints.size() && waypoints.get(index).isSubwaypoint();
    }

    public boolean hasSubwaypoints() {
        for (Waypoint waypoint : waypoints) {
            if (waypoint.isSubwaypoint()) return true;
        }
        return false;
    }

    public int mainWaypointCount() {
        int count = 0;
        for (Waypoint waypoint : waypoints) {
            if (!waypoint.isSubwaypoint()) count++;
        }
        return count;
    }

    public int currentMainIndex() {
        if (waypoints.isEmpty() || currentIndex >= waypoints.size()) return -1;
        if (!isSubwaypoint(currentIndex)) return currentIndex;

        int parent = parentMainIndex(currentIndex);
        if (parent >= 0) return parent;
        return nextMainIndexAtOrAfter(currentIndex);
    }

    public int currentMainOrdinal() {
        int current = currentMainIndex();
        if (current < 0) return mainWaypointCount();
        return mainOrdinal(current);
    }

    public int mainOrdinal(int index) {
        int mainIndex = isSubwaypoint(index) ? parentMainIndex(index) : index;
        if (mainIndex < 0 || mainIndex >= waypoints.size()) return 0;

        int ordinal = 0;
        for (int i = 0; i <= mainIndex; i++) {
            if (!isSubwaypoint(i)) ordinal++;
        }
        return ordinal;
    }

    public int childOrdinal(int index) {
        if (!isSubwaypoint(index)) return 0;

        int parent = parentMainIndex(index);
        if (parent < 0) return 0;

        int ordinal = 0;
        for (int i = parent + 1; i <= index && i < waypoints.size(); i++) {
            if (isSubwaypoint(i)) ordinal++;
            else ordinal = 0;
        }
        return ordinal;
    }

    public String displayIndexLabel(int index) {
        int mainOrdinal = mainOrdinal(index);
        if (mainOrdinal <= 0) return "#" + (index + 1);
        if (isSubwaypoint(index)) return "#" + mainOrdinal + "." + childOrdinal(index);
        return "#" + mainOrdinal;
    }

    public int parentMainIndex(int index) {
        if (index <= 0 || index >= waypoints.size() || !isSubwaypoint(index)) return -1;
        return previousMainIndexBefore(index);
    }

    public int previousMainIndexBefore(int index) {
        for (int i = Math.min(index - 1, waypoints.size() - 1); i >= 0; i--) {
            if (!isSubwaypoint(i)) return i;
        }
        return -1;
    }

    public int nextMainIndexAfter(int index) {
        return nextMainIndexAtOrAfter(index + 1);
    }

    public int childEndExclusive(int parentIndex) {
        if (parentIndex < 0 || parentIndex >= waypoints.size() || isSubwaypoint(parentIndex)) {
            return parentIndex;
        }
        int end = parentIndex + 1;
        while (end < waypoints.size() && isSubwaypoint(end)) end++;
        return end;
    }

    public int activeSubwaypointParentIndex() {
        return isActiveSubwaypointParent(activeSubwaypointParentIndex)
                ? activeSubwaypointParentIndex
                : -1;
    }

    public boolean isActiveSubwaypointParent(int index) {
        if (!canHoldActiveSubwaypointParent(index)) return false;

        int current = currentMainIndex();
        if (current < 0) return false;

        int next = nextMainIndexAfter(index);
        return next >= 0
                ? current == next
                : current == firstMainIndex();
    }

    public void setName(String newName)                 { this.name = newName == null ? "" : newName; }
        public void setZoneId(String newZoneId)             { this.zoneId = Zone.canonicalId(Objects.requireNonNull(newZoneId)); }
    public void setEnabled(boolean on)                  { this.enabled = on; }
    public void setDefaultRadius(double r)              { this.defaultRadius = Math.max(0.5, r); invalidateProximityIndex(); }
    public void setSkipAheadEnabled(boolean on)         { this.skipAheadEnabled = on; }
    public void setTemp(boolean on)                     { this.temp = on; }

    /**
     * Set the group's gradient endpoints. Setters immediately reapply the gradient
     * when the group is in AUTO mode so the colour change is visible without the
     * user needing a separate "apply" action. Locked waypoints are preserved by
     * GradientColorizer so a per-waypoint override survives a gradient re-colour.
     */
        public void setGradientStartColor(int rgb) {
        this.gradientStartColor = rgb & 0xFFFFFF;
        applyColorMode();
    }

        public void setGradientEndColor(int rgb) {
        this.gradientEndColor = rgb & 0xFFFFFF;
        applyColorMode();
    }

        public void setStaticColor(int rgb) {
        this.staticColor = rgb & 0xFFFFFF;
        applyColorMode();
    }

        public void setGradientMode(GradientMode mode) {
        this.gradientMode = Objects.requireNonNull(mode);
        applyColorMode();
    }

    public void setLoadMode(LoadMode mode) {
        this.loadMode = Objects.requireNonNull(mode);
    }

    /**
     * Which waypoint indices the renderer should surface given the current load
     * mode. Invokes {@code action} inline instead of allocating an index array;
     * renderers call this every frame, so the no-allocation path is the primary
     * API rather than a convenience overload.
     */
    public void forEachVisibleIndex(IntConsumer action) {
        int n = waypoints.size();
        if (n == 0) return;

        if (focusedVisibleIndex != null) {
            int index = focusedVisibleIndex;
            if (index >= 0 && index < n) action.accept(index);
            return;
        }

        if (loadMode == LoadMode.STATIC) {
            for (int i = 0; i < n; i++) action.accept(i);
            return;
        }

        if (isComplete()) {
            int activeParent = activeSubwaypointParentIndex();
            int lastMain = activeParent >= 0 ? activeParent : lastMainIndex();
            if (lastMain >= 0) {
                action.accept(lastMain);
                for (int child = lastMain + 1; child < n && isSubwaypoint(child); child++) {
                    action.accept(child);
                }
            }
            return;
        }

        int cur = currentMainIndex();
        if (cur < 0) return;

        int activeParent = activeSubwaypointParentIndex();
        int prev = previousMainIndexBefore(cur);
        if (activeParent >= 0) {
            action.accept(activeParent);
            for (int child = activeParent + 1; child < n && isSubwaypoint(child); child++) {
                action.accept(child);
            }
        } else if (prev >= 0) {
            action.accept(prev);
        }
        action.accept(cur);
        if (activeParent < 0) {
            for (int child = cur + 1; child < n && isSubwaypoint(child); child++) {
                action.accept(child);
            }
        }
        int next = nextMainIndexAfter(cur);
        if (next >= 0) action.accept(next);
    }

    public Waypoint get(int index) {
        return waypoints.get(index);
    }

    /*[[AI-FN-DOC
Function:
current
Purpose:
Return the exact waypoint currently targeted by route progression.
Why this exists:
Renderers and tracers need one accessor for the active target, and explicit skipto commands can now target subwaypoints directly.
When to use:
Use when caller needs the waypoint the player should currently navigate to. Do not use when caller specifically needs the parent main waypoint; use currentMainIndex for that.
Inputs:
None.
Outputs:
Returns the current Waypoint, or null when the route is empty or complete.
Side effects:
None.
Failure modes:
Out-of-range currentIndex values return null instead of throwing.
Important invariants:
When currentIndex points to a subwaypoint, this method returns that subwaypoint rather than normalizing to its parent.
Internal logic:
Check currentIndex bounds against the waypoint list and return the exact element when valid.
Pseudocode:
if currentIndex is outside 0 inclusive to size exclusive, return null
return waypoints[currentIndex]
Implementation notes:
This changed from main-waypoint normalization so /wp skipto decimal targets can drive tracers accurately.
AI self-check:
Verify callers that need parent context still use currentMainIndex.
]]*/
    public Waypoint current() {
        if (currentIndex < 0 || currentIndex >= waypoints.size()) return null;
        return waypoints.get(currentIndex);
    }

        public void set(int index, Waypoint replacement) {
        Waypoint normalized = normalizeWaypointForIndex(index, replacement);
        if (gradientMode == GradientMode.STATIC) {
            normalized = normalized.withColor(staticColor);
        }
        waypoints.set(index, normalized);
        afterWaypointStructureChanged();
    }

    /**
     * Repositioning a waypoint is a visibility-affecting edit, not just a data
     * replacement: if static reach hiding had already hidden this index, the
     * moved marker would stay invisible until the whole static cycle reset.
     */
    public void moveWaypointTo(int index, int x, int y, int z) {
        waypoints.set(index, waypoints.get(index).withPos(x, y, z));
        afterWaypointStructureChanged();
        focusNewWaypoint(index);
    }

    /*[[AI-FN-DOC
Function:
moveWaypointToPrecise.
Purpose:
Move one waypoint to an absolute sixteenth-block center while keeping route visibility/proximity state fresh.
Why this exists:
Small subwaypoint repositioning needs finer placement than whole-block coordinates without changing normal waypoint movement semantics.
When to use:
Use when a caller has already snapped a small waypoint target to Waypoint.PRECISE_SCALE units. Do not use for block-level coordinate editor changes; use moveWaypointTo instead.
Inputs:
index is the waypoint list index to move; preciseX/preciseY/preciseZ are absolute world coordinates multiplied by Waypoint.PRECISE_SCALE.
Outputs:
No return value. The waypoint at index is replaced with a precise-position copy.
Side effects:
Mutates the waypoint list, normalizes route structure/progress through afterWaypointStructureChanged, clears static reach state through that path, invalidates proximity index, and focuses the moved waypoint.
Failure modes:
Invalid indices return without mutation.
Important invariants:
The moved waypoint keeps its color, flags, radius, name, and temp metadata; only its center/block position changes.
Internal logic:
Guard the index, replace the waypoint with withPreciseSixteenths, run the shared post-structure-change refresh, and focus the moved index.
Pseudocode:
if index out of range, return
waypoints[index] = waypoints[index].withPreciseSixteenths(preciseX, preciseY, preciseZ)
afterWaypointStructureChanged()
focusNewWaypoint(index)
Implementation notes:
This mirrors moveWaypointTo's side effects so precise and block moves invalidate exactly the same derived state.
AI self-check:
Verify normal moveWaypointTo remains block-centered and this path preserves small marker precision.
]]*/
    public void moveWaypointToPrecise(int index, int preciseX, int preciseY, int preciseZ) {
        if (index < 0 || index >= waypoints.size()) return;
        waypoints.set(index, waypoints.get(index).withPreciseSixteenths(preciseX, preciseY, preciseZ));
        afterWaypointStructureChanged();
        focusNewWaypoint(index);
    }

        public void add(Waypoint w) {
        int oldSize = waypoints.size();
        waypoints.add(w);
        normalizeSubwaypointStructure();
        resizeStaticReachAfterAppend(oldSize);
        normalizeCurrentIndexToMain();
        invalidateProximityIndex();
        applyColorMode();
    }

        public void addAll(Collection<Waypoint> additions) {
        if (additions.isEmpty()) return;
        int oldSize = waypoints.size();
        waypoints.addAll(additions);
        normalizeSubwaypointStructure();
        resizeStaticReachAfterAppend(oldSize);
        normalizeCurrentIndexToMain();
        invalidateProximityIndex();
        applyColorMode();
    }

        public void replaceWaypoints(Collection<Waypoint> replacements) {
        waypoints.clear();
        waypoints.addAll(replacements);
        normalizeSubwaypointStructure();
        currentIndex = Math.min(currentIndex, waypoints.size());
        if (proximitySuppressedIndex >= waypoints.size()) proximitySuppressedIndex = -1;
        if (focusedVisibleIndex != null && focusedVisibleIndex >= waypoints.size()) {
            focusedVisibleIndex = waypoints.isEmpty() ? null : waypoints.size() - 1;
        }
        afterWaypointStructureChanged();
        applyColorMode();
    }

        public void insert(int index, Waypoint w) {
        int oldSize = waypoints.size();
        waypoints.add(index, w);
        waypoints.set(index, normalizeWaypointForIndex(index, w));
        if (index <= currentIndex) currentIndex++;
        if (proximitySuppressedIndex >= index) proximitySuppressedIndex++;
        if (focusedVisibleIndex != null && focusedVisibleIndex >= index) {
            focusedVisibleIndex++;
        }
        resizeStaticReachAfterInsert(index, oldSize);
        normalizeSubwaypointStructure();
        normalizeCurrentIndexToMain();
        invalidateProximityIndex();
        applyColorMode();
    }

    public boolean canMakeSubwaypoint(int index) {
        return index > 0
                && index < waypoints.size()
                && !isSubwaypoint(index)
                && previousMainIndexBefore(index) >= 0;
    }

        public boolean toggleSubwaypoint(int index) {
        if (index < 0 || index >= waypoints.size()) return false;
        if (isSubwaypoint(index)) {
            waypoints.set(index, waypoints.get(index).withSubwaypoint(false));
            afterWaypointStructureChanged();
            applyColorMode();
            return true;
        }
        if (!canMakeSubwaypoint(index)) return false;

        waypoints.set(index, waypoints.get(index).withSubwaypoint(true));
        afterWaypointStructureChanged();
        applyColorMode();
        return true;
    }

        public void remove(int index) {
        waypoints.remove(index);
        promoteOrphanedSubwaypoints(index);
        if (currentIndex > index) currentIndex--;
        currentIndex = Math.min(currentIndex, waypoints.size());
        if (proximitySuppressedIndex == index) proximitySuppressedIndex = -1;
        else if (proximitySuppressedIndex > index) proximitySuppressedIndex--;
        if (activeSubwaypointParentIndex == index) activeSubwaypointParentIndex = -1;
        else if (activeSubwaypointParentIndex > index) activeSubwaypointParentIndex--;
        if (focusedVisibleIndex != null) {
            if (focusedVisibleIndex == index) focusedVisibleIndex = null;
            else if (focusedVisibleIndex > index) focusedVisibleIndex--;
        }
        afterWaypointStructureChanged();
        applyColorMode();
    }

        public void move(int from, int to) {
        if (from == to || from < 0 || from >= waypoints.size() || to < 0 || to >= waypoints.size()) return;
        Waypoint current = currentWaypointReference();

        if (isSubwaypoint(from)) {
            int parent = parentMainIndex(from);
            int firstChild = parent + 1;
            int lastChild = childEndExclusive(parent) - 1;
            int clamped = Math.max(firstChild, Math.min(lastChild, to));
            moveSingle(from, clamped);
        } else {
            int blockEnd = childEndExclusive(from);
            if (to >= from && to < blockEnd) return;

            int targetBlockStart = isSubwaypoint(to) ? parentMainIndex(to) : to;
            int insertAt = to < from ? targetBlockStart : childEndExclusive(targetBlockStart);
            moveRange(from, blockEnd, insertAt);
        }

        restoreCurrentIndex(current);
        proximitySuppressedIndex = -1;
        focusedVisibleIndex = null;
        activeSubwaypointParentIndex = -1;
        afterWaypointStructureChanged();
        applyColorMode();
    }

    public int moveBy(int index, int delta) {
        if (delta == 0 || index < 0 || index >= waypoints.size()) return index;
        return isSubwaypoint(index) ? moveSubwaypointBy(index, delta) : moveMainBlockBy(index, delta);
    }

    /*[[AI-FN-DOC
Function:
advancePast
Purpose:
Move route progress beyond a reached waypoint while respecting main-waypoint and subwaypoint progression rules.
Why this exists:
Automatic progression and explicit subwaypoint targets need one mutation path that advances to the correct next target and preserves visual holds where appropriate.
When to use:
Use after proximity logic confirms a waypoint has been reached. Do not use for manual jumps to a specific target; use setCurrentIndex or setCurrentTargetIndex instead.
Inputs:
reachedIndex is the zero-based waypoint index that was reached. It may be a main waypoint, subwaypoint, or out-of-range value.
Outputs:
No return value. Mutates currentIndex and activeSubwaypointParentIndex.
Side effects:
Changes route progress state.
Failure modes:
Out-of-range main-like values fall through to completion behavior through next-main lookup. Subwaypoint branches guard by checking isSubwaypoint first.
Important invariants:
Main waypoint behavior remains compatible with existing visual hold semantics. Reaching a subwaypoint advances to the next sibling subwaypoint, next main waypoint, or completion.
Internal logic:
If the reached index is a subwaypoint, advance within its child chain or to the next main. Otherwise preserve the existing main-waypoint hold and next-main behavior.
Pseudocode:
if reached index is a subwaypoint:
  next = reachedIndex + 1
  if next exists and is subwaypoint, currentIndex = next and hold parent
  else currentIndex = next main at/after next or route size and clear hold
  return
if reached main has children, set active hold to reached main, else clear hold
set currentIndex to next main after reached or route size
normalize currentIndex to main
validate active hold still matches expected next target
Implementation notes:
The subwaypoint branch intentionally avoids normalizeCurrentIndexToMain so explicit child targets can advance through child labels one by one.
AI self-check:
Verify existing parent-with-subwaypoints tests still pass and the new explicit child target test advances to the next main.
]]*/
    public void advancePast(int reachedIndex) {
        if (isSubwaypoint(reachedIndex)) {
            int next = reachedIndex + 1;
            if (next < waypoints.size() && isSubwaypoint(next)) {
                currentIndex = next;
                activeSubwaypointParentIndex = parentMainIndex(next);
                return;
            }
            int nextMain = nextMainIndexAtOrAfter(next);
            currentIndex = nextMain >= 0 ? nextMain : waypoints.size();
            activeSubwaypointParentIndex = -1;
            return;
        }
        activeSubwaypointParentIndex = childEndExclusive(reachedIndex) > reachedIndex + 1
                ? reachedIndex
                : -1;
        int next = nextMainIndexAfter(reachedIndex);
        currentIndex = Math.max(currentIndex, next >= 0 ? next : waypoints.size());
        normalizeCurrentIndexToMain();
        if (activeSubwaypointParentIndex >= 0) {
            int expectedNext = nextMainIndexAfter(activeSubwaypointParentIndex);
            if (currentIndex != (expectedNext >= 0 ? expectedNext : waypoints.size())) {
                activeSubwaypointParentIndex = -1;
            }
        }
    }

    /*[[AI-FN-DOC
Function:
retreatToPreviousTarget
Purpose:
Move route progress back to the previous target waypoint.
Why this exists:
The Previous Waypoint keybind needs one route-owned inverse for Skip Waypoint that understands normal main-waypoint progress, exact subwaypoint targets, held subwaypoint parents, and completed routes.
When to use:
Use when an explicit user action should move a route back by one target. Do not use for structural edits, initial route setup, or automatic proximity progression.
Inputs:
No parameters. Reads this group's waypoint list, currentIndex, and activeSubwaypointParentIndex.
Outputs:
Returns true when current progress moved to an earlier target, or false when the route is empty or already at its first target.
Side effects:
Mutates currentIndex and activeSubwaypointParentIndex through setCurrentTargetIndex, and clears proximity suppression through that method.
Failure modes:
Empty groups and routes with no previous target return false without mutation. Invalid active subwaypoint hold state is ignored by falling back to last or previous main waypoint lookup.
Important invariants:
Retreating from a completed route targets the final main waypoint, retreating from an exact subwaypoint target walks to the previous exact child or parent, and retreating from a main waypoint walks to the previous main waypoint rather than a previous parent's child.
Internal logic:
Choose a target based on current progress state, then delegate to setCurrentTargetIndex so exact subwaypoint targeting and suppression clearing stay consistent with skip-to commands.
Pseudocode:
if waypoint list is empty, return false
if route is complete:
  heldParent = activeParentForCompletionWrap
  target = heldParent if valid, otherwise last main index
else if currentIndex is at or before first list entry:
  return false
else if currentIndex is a subwaypoint:
  target = currentIndex - 1
else if activeSubwaypointParentIndex is a valid active hold:
  target = activeSubwaypointParentIndex
else:
  target = previous main index before currentIndex
if target is invalid, return false
before = currentIndex
setCurrentTargetIndex(target)
return currentIndex changed from before
Implementation notes:
The method deliberately does not call resetProgress because going back one step should preserve the rest of the route state and should work from completion as a one-step undo.
AI self-check:
Verify the branch order handles completion before the at-start guard and handles exact subwaypoint current targets before main-waypoint previous-main logic.
]]*/
    public boolean retreatToPreviousTarget() {
        if (waypoints.isEmpty()) return false;

        int target;
        if (isComplete()) {
            int heldParent = activeParentForCompletionWrap();
            target = heldParent >= 0 ? heldParent : lastMainIndex();
        } else if (currentIndex <= 0) {
            return false;
        } else if (isSubwaypoint(currentIndex)) {
            target = currentIndex - 1;
        } else if (isActiveSubwaypointParent(activeSubwaypointParentIndex)) {
            target = activeSubwaypointParentIndex;
        } else {
            target = previousMainIndexBefore(currentIndex);
        }

        if (target < 0) return false;

        int before = currentIndex;
        setCurrentTargetIndex(target);
        return currentIndex != before;
    }

    /**
     * If {@code loopWhenComplete} is on and the player just finished the route
     * (current index is past the last waypoint), snap back to the first waypoint
     * so the route can be run again without manual reset.
     *
     * Call immediately after {@link #advancePast(int)} when that advance may have
     * completed the route.
     */
    public void restartIfRouteCompleted(boolean loopWhenComplete) {
        if (!loopWhenComplete || isEmpty()) return;
        if (!isComplete()) return;

        int wrapHoldParent = activeParentForCompletionWrap();
        resetProgress();
        if (wrapHoldParent >= 0) {
            activeSubwaypointParentIndex = wrapHoldParent;
        }
    }

    public void resetProgress() {
        int firstMain = nextMainIndexAtOrAfter(0);
        currentIndex = firstMain >= 0 ? firstMain : 0;
        activeSubwaypointParentIndex = -1;
        resetStaticReachState();
        clearProximitySuppression();
    }

    public boolean isStaticWaypointReached(int index) {
        return staticReached != null
                && index >= 0
                && index < staticReached.length
                && staticReached[index];
    }

    /**
     * Mark one static waypoint as reached. If that completes the visible set,
     * the cycle immediately resets so every waypoint appears again for the next
     * pass through the route.
     */
    public boolean markStaticWaypointReached(int index) {
        if (index < 0 || index >= waypoints.size()) return false;
        if (isSubwaypoint(index)) return false;

        ensureStaticReachState();
        if (staticReached[index]) return false;

        staticReached[index] = true;
        if (allStaticWaypointsReached()) {
            resetStaticReachState();
            staticCycleJustCompleted = true;
        }
        return true;
    }

    /**
     * Whether this group just finished a static reach cycle on the current tick.
     * Clears the flag so it is a one-shot signal for callers that batch marks per tick.
     */
    public boolean consumeStaticCycleJustCompleted() {
        boolean v = staticCycleJustCompleted;
        staticCycleJustCompleted = false;
        return v;
    }

    public void resetStaticReachState() {
        staticCycleJustCompleted = false;
        staticReached = null;
    }

    public void focusNewWaypoint(int index) {
        focusNewWaypoint(index, true);
    }

    /**
     * @param resetStaticReachState when {@code false}, leaves {@link #staticReached} unchanged
     *     (used after add/insert, which already resized reach bits to match the new list).
     */
    public void focusNewWaypoint(int index, boolean resetStaticReachState) {
        if (waypoints.isEmpty()) {
            proximitySuppressedIndex = -1;
            return;
        }

        currentIndex = normalizedMainIndexFor(index);
        proximitySuppressedIndex = currentIndex;
        if (resetStaticReachState) {
            resetStaticReachState();
        }
    }

    public void focusOnlyVisibleIndex(int index) {
        focusedVisibleIndex = index < 0 ? null : Math.min(index, waypoints.size() - 1);
    }

    public int focusedVisibleIndex() {
        return focusedVisibleIndex == null ? -1 : focusedVisibleIndex;
    }

    public void clearFocusedVisibleIndex() {
        focusedVisibleIndex = null;
    }

    public int proximitySuppressedIndex() {
        return proximitySuppressedIndex;
    }

    public boolean isProximitySuppressed(int index) {
        return index == proximitySuppressedIndex;
    }

    public void clearProximitySuppression() {
        proximitySuppressedIndex = -1;
    }

    /**
     * Drop every time-based temporary waypoint whose deadline has passed.
     * Returns the number of waypoints removed so callers can short-circuit
     * save/dirty notifications when nothing changed.
     *
     * <p>The reach-based and server-leave-based temps are handled elsewhere
     * ({@code ProximityTracker} / {@code TempWaypointCleaner#onDisconnect}).
     * Centralising only the time branch here keeps the scheduler code in one
     * place and avoids spreading "what counts as expired" across modules.
     */
    public int removeExpired(long nowMillis) {
        int removed = 0;
        for (int i = waypoints.size() - 1; i >= 0; i--) {
            if (waypoints.get(i).isExpired(nowMillis)) {
                remove(i);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Drop every temporary waypoint regardless of mode. Used on server
     * disconnect -- the contract is that no temp waypoint outlives the session
     * that created it.
     */
    public int removeAllTemp() {
        int removed = 0;
        for (int i = waypoints.size() - 1; i >= 0; i--) {
            if (waypoints.get(i).isTemp()) {
                remove(i);
                removed++;
            }
        }
        return removed;
    }

    public void setCurrentIndex(int index) {
        currentIndex = normalizedMainIndexFor(index);
        activeSubwaypointParentIndex = -1;
        clearProximitySuppression();
    }

    /*[[AI-FN-DOC
Function:
setCurrentTargetIndex
Purpose:
Set the route's current target to an exact waypoint index, including subwaypoints.
Why this exists:
The command surface can address labels like 2.2, and normal setCurrentIndex intentionally canonicalizes subwaypoints back to their parent for older UI flows.
When to use:
Use for explicit user navigation commands that target a specific displayed waypoint. Do not use for structural edits that should keep legacy main-waypoint normalization.
Inputs:
index is a zero-based waypoint list index. Values below zero clamp to the first waypoint; values at or past size mark the route complete.
Outputs:
No return value. Mutates currentIndex and clears temporary route focus/suppression state.
Side effects:
Changes progression target, may set activeSubwaypointParentIndex for subwaypoint targets, clears proximity suppression.
Failure modes:
Empty groups are marked at index 0 and no exception is thrown. Out-of-range values are clamped to route completion.
Important invariants:
Main waypoint targets clear active subwaypoint hold. Subwaypoint targets retain their parent as the active subwaypoint parent for renderer context.
Internal logic:
Handle empty and completion cases first, clamp into list bounds, assign the exact index, set visual hold to the parent only for subwaypoints, then clear suppression.
Pseudocode:
if route empty, set currentIndex to 0 and clear hold
else if index >= size, set currentIndex to size and clear hold
else clamp index to at least 0
set currentIndex to clamped
if clamped is subwaypoint, active parent = parentMainIndex(clamped), else clear active parent
clear proximity suppression
Implementation notes:
This method deliberately does not call normalizeCurrentIndexToMain because the whole point is preserving subwaypoint targets from /wp skipto.
AI self-check:
Confirm current(), renderer state, and strict proximity progression can now observe an exact subwaypoint current target.
]]*/
    public void setCurrentTargetIndex(int index) {
        if (waypoints.isEmpty()) {
            currentIndex = 0;
            activeSubwaypointParentIndex = -1;
            clearProximitySuppression();
            return;
        }
        if (index >= waypoints.size()) {
            currentIndex = waypoints.size();
            activeSubwaypointParentIndex = -1;
            clearProximitySuppression();
            return;
        }
        currentIndex = Math.max(0, index);
        activeSubwaypointParentIndex = isSubwaypoint(currentIndex)
                ? parentMainIndex(currentIndex)
                : -1;
        clearProximitySuppression();
    }

    /** Radius the tracker should use for a given waypoint (its own override, else the group default). */
    public double effectiveRadius(Waypoint w) {
        return w.customRadius() > 0 ? w.customRadius() : defaultRadius;
    }

    public double maxEffectiveRadius() {
        return proximityIndex().maxEffectiveRadius;
    }

    public boolean forEachNearbyIndex(double x, double y, double z,
                                      double radius, IntPredicate action) {
        return proximityIndex().forEachNearby(x, y, z, radius, action);
    }

        private void applyColorMode() {
        if (gradientMode == GradientMode.STATIC) {
            applyStaticColor();
        } else if (gradientMode == GradientMode.AUTO) {
            GradientColorizer.apply(this);
        }
    }

        private void applyStaticColor() {
        int target = staticColor & 0xFFFFFF;
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint waypoint = waypoints.get(i);
            if (waypoint.color() != target) {
                waypoints.set(i, waypoint.withColor(target));
            }
        }
    }

    private void afterWaypointStructureChanged() {
        normalizeSubwaypointStructure();
        normalizeCurrentIndexToMain();
        if (!isActiveSubwaypointParent(activeSubwaypointParentIndex)) {
            activeSubwaypointParentIndex = -1;
        }
        staticReached = null;
        invalidateProximityIndex();
    }

    private Waypoint normalizeWaypointForIndex(int index, Waypoint waypoint) {
        return index == 0 && waypoint.isSubwaypoint()
                ? waypoint.withSubwaypoint(false)
                : waypoint;
    }

    private void normalizeSubwaypointStructure() {
        if (!waypoints.isEmpty() && waypoints.get(0).isSubwaypoint()) {
            waypoints.set(0, waypoints.get(0).withSubwaypoint(false));
        }
    }

    private void normalizeCurrentIndexToMain() {
        currentIndex = normalizedMainIndexFor(currentIndex);
    }

    private boolean canHoldActiveSubwaypointParent(int index) {
        return loadMode == LoadMode.SEQUENCE
                && index >= 0
                && index < waypoints.size()
                && !isSubwaypoint(index)
                && childEndExclusive(index) > index + 1;
    }

    private int activeParentForCompletionWrap() {
        int parent = activeSubwaypointParentIndex;
        return canHoldActiveSubwaypointParent(parent)
                && nextMainIndexAfter(parent) < 0
                ? parent
                : -1;
    }

    private int normalizedMainIndexFor(int index) {
        if (waypoints.isEmpty()) return 0;
        if (index >= waypoints.size()) return waypoints.size();

        int clamped = Math.max(0, index);
        if (!isSubwaypoint(clamped)) return clamped;

        int parent = parentMainIndex(clamped);
        if (parent >= 0) return parent;

        int next = nextMainIndexAtOrAfter(clamped);
        return next >= 0 ? next : waypoints.size();
    }

    private int nextMainIndexAtOrAfter(int index) {
        for (int i = Math.max(0, index); i < waypoints.size(); i++) {
            if (!isSubwaypoint(i)) return i;
        }
        return -1;
    }

    private int firstMainIndex() {
        return nextMainIndexAtOrAfter(0);
    }

    public int lastMainIndex() {
        for (int i = waypoints.size() - 1; i >= 0; i--) {
            if (!isSubwaypoint(i)) return i;
        }
        return -1;
    }

    private void promoteOrphanedSubwaypoints(int start) {
        for (int i = Math.max(0, start); i < waypoints.size() && isSubwaypoint(i); i++) {
            waypoints.set(i, waypoints.get(i).withSubwaypoint(false));
        }
    }

        private int moveSubwaypointBy(int index, int delta) {
        int parent = parentMainIndex(index);
        if (parent < 0) return index;

        int firstChild = parent + 1;
        int lastChild = childEndExclusive(parent) - 1;
        int to = Math.max(firstChild, Math.min(lastChild, index + delta));
        if (to == index) return index;

        Waypoint current = currentWaypointReference();
        moveSingle(index, to);
        restoreCurrentIndex(current);
        proximitySuppressedIndex = -1;
        focusedVisibleIndex = null;
        afterWaypointStructureChanged();
        applyColorMode();
        return to;
    }

        private int moveMainBlockBy(int index, int delta) {
        int blockEnd = childEndExclusive(index);
        int insertAt;
        if (delta < 0) {
            int previous = previousMainIndexBefore(index);
            if (previous < 0) return index;
            insertAt = previous;
        } else {
            int next = nextMainIndexAfter(index);
            if (next < 0) return index;
            insertAt = childEndExclusive(next);
        }

        Waypoint current = currentWaypointReference();
        int blockLength = blockEnd - index;
        int newIndex = insertAt > index ? insertAt - blockLength : insertAt;
        moveRange(index, blockEnd, insertAt);
        restoreCurrentIndex(current);
        proximitySuppressedIndex = -1;
        focusedVisibleIndex = null;
        afterWaypointStructureChanged();
        applyColorMode();
        return newIndex;
    }

    private void moveSingle(int from, int to) {
        if (from == to) return;
        Waypoint waypoint = waypoints.remove(from);
        waypoints.add(to, waypoint);
    }

    private void moveRange(int start, int end, int insertAt) {
        if (start >= end || insertAt >= start && insertAt <= end) return;

        List<Waypoint> block = new ArrayList<>(waypoints.subList(start, end));
        waypoints.subList(start, end).clear();
        int adjustedInsert = insertAt > start ? insertAt - block.size() : insertAt;
        waypoints.addAll(adjustedInsert, block);
    }

    private Waypoint currentWaypointReference() {
        int current = currentMainIndex();
        return current < 0 ? null : waypoints.get(current);
    }

    private void restoreCurrentIndex(Waypoint current) {
        if (current == null) {
            currentIndex = waypoints.size();
            activeSubwaypointParentIndex = -1;
            return;
        }
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i) == current) {
                currentIndex = i;
                return;
            }
        }
        currentIndex = Math.min(currentIndex, waypoints.size());
    }

    private void resizeStaticReachAfterAppend(int oldSize) {
        if (staticReached == null) return;
        if (staticReached.length != oldSize) {
            staticReached = null;
            return;
        }

        boolean[] next = new boolean[waypoints.size()];
        System.arraycopy(staticReached, 0, next, 0, oldSize);
        staticReached = next;
    }

    private void resizeStaticReachAfterInsert(int index, int oldSize) {
        if (staticReached == null) return;
        if (staticReached.length != oldSize) {
            staticReached = null;
            return;
        }

        boolean[] next = new boolean[waypoints.size()];
        System.arraycopy(staticReached, 0, next, 0, index);
        System.arraycopy(staticReached, index, next, index + 1, oldSize - index);
        staticReached = next;
    }

    private void invalidateProximityIndex() {
        proximityIndex = null;
    }

    private ProximityIndex proximityIndex() {
        if (proximityIndex == null) proximityIndex = ProximityIndex.build(this);
        return proximityIndex;
    }

    private void ensureStaticReachState() {
        if (staticReached == null || staticReached.length != waypoints.size()) {
            staticReached = new boolean[waypoints.size()];
        }
    }

    private boolean allStaticWaypointsReached() {
        if (staticReached == null || staticReached.length == 0) return false;

        boolean hasMainWaypoint = false;
        for (int i = 0; i < staticReached.length; i++) {
            if (isSubwaypoint(i)) continue;
            hasMainWaypoint = true;
            if (!staticReached[i]) return false;
        }
        return hasMainWaypoint;
    }

    private static int cell(double value) {
        return Math.floorDiv((int) Math.floor(value), PROXIMITY_CELL_SIZE);
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42)
                | ((long) (y & 0x1FFFFF) << 21)
                | (z & 0x1FFFFFL);
    }

    private static final class ProximityIndex {
        private final Map<Long, IntBucket> buckets = new HashMap<>();
        private final double maxEffectiveRadius;

        static ProximityIndex build(WaypointGroup group) {
            double maxRadius = group.defaultRadius();
            Map<Long, IntBucket> buckets = new HashMap<>();

            for (int i = 0; i < group.waypoints.size(); i++) {
                Waypoint waypoint = group.waypoints.get(i);
                if (waypoint.isSubwaypoint()) continue;

                double radius = group.effectiveRadius(waypoint);
                if (radius > maxRadius) maxRadius = radius;

                long key = cellKey(
                        cell(waypoint.x()),
                        cell(waypoint.y()),
                        cell(waypoint.z()));
                buckets.computeIfAbsent(key, ignored -> new IntBucket()).add(i);
            }

            return new ProximityIndex(maxRadius, buckets);
        }

        private ProximityIndex(double maxEffectiveRadius, Map<Long, IntBucket> buckets) {
            this.maxEffectiveRadius = maxEffectiveRadius;
            this.buckets.putAll(buckets);
        }

        boolean forEachNearby(double x, double y, double z,
                              double radius, IntPredicate action) {
            int minX = cell(x - radius);
            int minY = cell(y - radius);
            int minZ = cell(z - radius);
            int maxX = cell(x + radius);
            int maxY = cell(y + radius);
            int maxZ = cell(z + radius);

            for (int cx = minX; cx <= maxX; cx++) {
                for (int cy = minY; cy <= maxY; cy++) {
                    for (int cz = minZ; cz <= maxZ; cz++) {
                        IntBucket bucket = buckets.get(cellKey(cx, cy, cz));
                        if (bucket == null) continue;
                        for (int i = 0; i < bucket.size; i++) {
                            if (!action.test(bucket.values[i])) return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    private static final class IntBucket {
        private int[] values = new int[4];
        private int size;

        void add(int value) {
            if (size == values.length) {
                int[] next = new int[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }
    }
}
