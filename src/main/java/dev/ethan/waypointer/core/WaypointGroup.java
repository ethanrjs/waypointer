package dev.ethan.waypointer.core;

import dev.ethan.waypointer.color.GradientColorizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;

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

    public enum GradientMode {
        /** Colors are auto-interpolated across the list; manual edits to unlocked entries get overwritten. */
        AUTO,
        /** Each waypoint keeps its own color. Reordering does not recolor. */
        MANUAL
    }

    /**
     * How many waypoints of the group are surfaced to the renderer at once.
     *
     * STATIC is the default because it matches what most shared routes assume (all
     * points visible so the player can pick their own order). SEQUENCE is the
     * "breadcrumb" mode: only the neighborhood of {@code currentIndex} renders,
     * which keeps the HUD clean on long routes and makes the intended order
     * obvious.
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
     * Set when a static reach pass completes the full set and clears {@link #staticReached}.
     * Lets {@link dev.ethan.waypointer.progression.ProximityTracker} stop scanning for the
     * rest of the tick so every waypoint shows as visible for at least one frame.
     */
    private transient boolean staticCycleJustCompleted;

    public WaypointGroup(String id, String name, String zoneId) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null ? "" : name;
        this.zoneId = Objects.requireNonNull(zoneId);
        this.waypoints = new ArrayList<>();
        this.currentIndex = 0;
        this.enabled = true;
        this.gradientMode = GradientMode.AUTO;
        this.loadMode = LoadMode.STATIC;
        this.defaultRadius = 3.0;
    }

    public static WaypointGroup create(String name, String zoneId) {
        return new WaypointGroup(UUID.randomUUID().toString(), name, zoneId);
    }

    public String id()            { return id; }
    public String name()          { return name; }
    public String zoneId()        { return zoneId; }
    public int currentIndex()     { return currentIndex; }
    public boolean enabled()      { return enabled; }
    public GradientMode gradientMode() { return gradientMode; }
    public LoadMode loadMode()    { return loadMode; }
    public double defaultRadius() { return defaultRadius; }
    public int gradientStartColor() { return gradientStartColor; }
    public int gradientEndColor()   { return gradientEndColor; }
    public boolean skipAheadEnabled() { return skipAheadEnabled; }
    public boolean temp()           { return temp; }
    public List<Waypoint> waypoints() { return Collections.unmodifiableList(waypoints); }
    public int size()             { return waypoints.size(); }
    public boolean isEmpty()      { return waypoints.isEmpty(); }
    public boolean isComplete()   { return currentIndex >= waypoints.size(); }

    public void setName(String newName)                 { this.name = newName == null ? "" : newName; }
    public void setZoneId(String newZoneId)             { this.zoneId = Objects.requireNonNull(newZoneId); }
    public void setEnabled(boolean on)                  { this.enabled = on; }
    public void setDefaultRadius(double r)              { this.defaultRadius = Math.max(0.5, r); }
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
        applyGradientIfAuto();
    }
    public void setGradientEndColor(int rgb) {
        this.gradientEndColor = rgb & 0xFFFFFF;
        applyGradientIfAuto();
    }

    public void setGradientMode(GradientMode mode) {
        this.gradientMode = Objects.requireNonNull(mode);
        if (mode == GradientMode.AUTO) GradientColorizer.apply(this);
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
            action.accept(n - 1);
            return;
        }

        int cur = Math.max(0, Math.min(currentIndex, n - 1));
        if (cur - 1 >= 0) action.accept(cur - 1);
        action.accept(cur);
        if (cur + 1 < n) action.accept(cur + 1);
    }

    public Waypoint get(int index) {
        return waypoints.get(index);
    }

    public Waypoint current() {
        return isComplete() ? null : waypoints.get(currentIndex);
    }

    public void set(int index, Waypoint replacement) {
        waypoints.set(index, replacement);
    }

    public void add(Waypoint w) {
        int oldSize = waypoints.size();
        waypoints.add(w);
        if (staticReached != null) {
            if (staticReached.length != oldSize) {
                staticReached = null;
            } else {
                boolean[] next = new boolean[waypoints.size()];
                System.arraycopy(staticReached, 0, next, 0, oldSize);
                staticReached = next;
            }
        }
        applyGradientIfAuto();
    }

    public void insert(int index, Waypoint w) {
        int oldSize = waypoints.size();
        waypoints.add(index, w);
        if (index <= currentIndex) currentIndex++;
        if (proximitySuppressedIndex >= index) proximitySuppressedIndex++;
        if (focusedVisibleIndex != null && focusedVisibleIndex >= index) {
            focusedVisibleIndex++;
        }
        if (staticReached != null) {
            if (staticReached.length != oldSize) {
                staticReached = null;
            } else {
                boolean[] next = new boolean[waypoints.size()];
                System.arraycopy(staticReached, 0, next, 0, index);
                System.arraycopy(staticReached, index, next, index + 1, oldSize - index);
                staticReached = next;
            }
        }
        applyGradientIfAuto();
    }

    public void remove(int index) {
        waypoints.remove(index);
        if (currentIndex > index) currentIndex--;
        currentIndex = Math.min(currentIndex, waypoints.size());
        if (proximitySuppressedIndex == index) proximitySuppressedIndex = -1;
        else if (proximitySuppressedIndex > index) proximitySuppressedIndex--;
        if (focusedVisibleIndex != null) {
            if (focusedVisibleIndex == index) focusedVisibleIndex = null;
            else if (focusedVisibleIndex > index) focusedVisibleIndex--;
        }
        staticReached = null;
        applyGradientIfAuto();
    }

    public void move(int from, int to) {
        if (from == to || from < 0 || from >= waypoints.size() || to < 0 || to >= waypoints.size()) return;
        Waypoint w = waypoints.remove(from);
        waypoints.add(to, w);
        if (currentIndex == from) currentIndex = to;
        else if (from < currentIndex && to >= currentIndex) currentIndex--;
        else if (from > currentIndex && to <= currentIndex) currentIndex++;
        proximitySuppressedIndex = -1;
        focusedVisibleIndex = null;
        staticReached = null;
        applyGradientIfAuto();
    }

    /** Advance to the next waypoint after {@code reachedIndex}. Safe to call past the end. */
    public void advancePast(int reachedIndex) {
        currentIndex = Math.max(currentIndex, reachedIndex + 1);
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
        if (isComplete()) resetProgress();
    }

    public void resetProgress() {
        currentIndex = 0;
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

        currentIndex = Math.max(0, Math.min(index, waypoints.size() - 1));
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
        currentIndex = Math.max(0, Math.min(index, waypoints.size()));
        clearProximitySuppression();
    }

    /** Radius the tracker should use for a given waypoint (its own override, else the group default). */
    public double effectiveRadius(Waypoint w) {
        return w.customRadius() > 0 ? w.customRadius() : defaultRadius;
    }

    private void applyGradientIfAuto() {
        if (gradientMode == GradientMode.AUTO) GradientColorizer.apply(this);
    }

    private void ensureStaticReachState() {
        if (staticReached == null || staticReached.length != waypoints.size()) {
            staticReached = new boolean[waypoints.size()];
        }
    }

    private boolean allStaticWaypointsReached() {
        if (staticReached == null || staticReached.length == 0) return false;

        for (boolean reached : staticReached) {
            if (!reached) return false;
        }
        return true;
    }
}
