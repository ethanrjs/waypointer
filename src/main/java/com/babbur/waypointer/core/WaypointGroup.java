package com.babbur.waypointer.core;

import com.babbur.waypointer.color.GradientColorizer;
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

/** Mutable route state. Mutate it only on the client thread. */
public final class WaypointGroup {

    private static final int PROXIMITY_CELL_SIZE = 16;
    private static final int MAX_PROXIMITY_CELL_VISITS = 4096;

    public enum GradientMode {
        STATIC,
        AUTO,
        MANUAL
    }

    public enum LoadMode {
        STATIC,
        SEQUENCE
    }

    public enum RouteKind {
        REGULAR,
        DUNGEON
    }

    private final String id;
    private String name;
    private String zoneId;
    private final List<Waypoint> waypoints;
    private final List<Integer> manualColors;
    private int currentIndex;
    private boolean enabled;
    private GradientMode gradientMode;
    private LoadMode loadMode;
    private RouteKind routeKind;
    private double defaultRadius;
    private boolean skipAheadEnabled = true;
    private boolean temp = false;
    private boolean runtimeOnly = false;
    private int staticColor = Waypoint.DEFAULT_COLOR;
    private int gradientStartColor = 0x00BFFF;
    private int gradientEndColor   = 0xFF3040;
    private WaypointPaint paint;
    private boolean paintEnabled = true;
    private CatalogRouteProvenance catalogProvenance;
    private transient boolean[] staticReached;
    private transient int proximitySuppressedIndex = -1;
    private transient Integer focusedVisibleIndex;
    private transient int activeSubwaypointParentIndex = -1;
    private transient int visibleMainSteps;
    private transient String runtimeSourceGroupId;
    private transient int standSkipHoldIndex = -1;
    private transient long standSkipHoldStartedAtMillis;
    private transient boolean staticCycleJustCompleted;
    private transient ProximityIndex proximityIndex;

        public WaypointGroup(String id, String name, String zoneId) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null ? "" : name;
        this.zoneId = Zone.canonicalId(Objects.requireNonNull(zoneId));
        this.waypoints = new ArrayList<>();
        this.manualColors = new ArrayList<>();
        this.currentIndex = 0;
        this.enabled = true;
        this.gradientMode = GradientMode.AUTO;
        this.loadMode = LoadMode.SEQUENCE;
        this.routeKind = RouteKind.REGULAR;
        this.defaultRadius = Waypoint.DEFAULT_REACH_RADIUS;
    }

    public static WaypointGroup create(String name, String zoneId) {
        return new WaypointGroup(UUID.randomUUID().toString(), name, zoneId);
    }

    public static WaypointGroup create(String name, String zoneId, boolean skipAheadEnabled) {
        WaypointGroup group = create(name, zoneId);
        group.setSkipAheadEnabled(skipAheadEnabled);
        return group;
    }

    public WaypointGroup exportSnapshot() {
        WaypointGroup copy = new WaypointGroup(id, name, zoneId);
        copy.loadMode = loadMode;
        copy.routeKind = routeKind;
        copy.gradientMode = gradientMode;
        copy.staticColor = staticColor;
        copy.gradientStartColor = gradientStartColor;
        copy.gradientEndColor = gradientEndColor;
        copy.defaultRadius = defaultRadius;
        copy.skipAheadEnabled = skipAheadEnabled;
        copy.waypoints.addAll(waypoints);
        copy.manualColors.addAll(manualColors);
        return copy;
    }

    public String id()            { return id; }
    public String name()          { return name; }
    public String zoneId()        { return zoneId; }
    public int currentIndex()     { return currentIndex; }
    public boolean enabled()      { return enabled; }
    public GradientMode gradientMode() { return gradientMode; }
    public LoadMode loadMode()    { return loadMode; }
    public RouteKind routeKind()  { return routeKind; }
    public double defaultRadius() { return defaultRadius; }
        public int staticColor()      { return staticColor; }
    public int gradientStartColor() { return gradientStartColor; }
    public int gradientEndColor()   { return gradientEndColor; }
    public WaypointPaint paint()    { return paint; }
    public boolean paintEnabled()   { return paintEnabled; }
    public boolean skipAheadEnabled() { return skipAheadEnabled; }
    public boolean temp()           { return temp; }
    public boolean runtimeOnly()    { return runtimeOnly; }
    public CatalogRouteProvenance catalogProvenance() { return catalogProvenance; }
    public int visibleMainSteps()   { return visibleMainSteps; }
    public String runtimeSourceGroupId() { return runtimeSourceGroupId; }
    public List<Waypoint> waypoints() { return Collections.unmodifiableList(waypoints); }
    public List<Integer> manualColorSnapshot() { return List.copyOf(manualColors); }
    public int size()             { return waypoints.size(); }
    public boolean isEmpty()      { return waypoints.isEmpty(); }
    public boolean isComplete()   { return currentIndex >= waypoints.size(); }

    /** Catalog provenance stays local and is omitted from exports. */
    public void setCatalogProvenance(CatalogRouteProvenance provenance) {
        this.catalogProvenance = provenance;
    }

    public boolean isSubwaypoint(int index) {
        return index >= 0 && index < waypoints.size() && waypoints.get(index).isSubwaypoint();
    }

    public boolean isWaypointDisabled(int index) {
        return index >= 0 && index < waypoints.size() && waypoints.get(index).isDisabled();
    }

    public boolean isWaypointEnabled(int index) {
        return index >= 0 && index < waypoints.size() && !isWaypointDisabled(index);
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

    public int enabledMainWaypointCount() {
        int count = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            if (!isSubwaypoint(i) && isWaypointEnabled(i)) count++;
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
        if (current < 0) return enabledMainWaypointCount();

        int ordinal = 0;
        for (int i = 0; i <= current; i++) {
            if (!isSubwaypoint(i) && isWaypointEnabled(i)) ordinal++;
        }
        return ordinal;
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

    public int previousEnabledMainIndexBefore(int index) {
        for (int i = Math.min(index - 1, waypoints.size() - 1); i >= 0; i--) {
            if (!isSubwaypoint(i) && isWaypointEnabled(i)) return i;
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
        if (currentIndex >= 0
                && currentIndex < waypoints.size()
                && isSubwaypoint(currentIndex)
                && parentMainIndex(currentIndex) == index) {
            return true;
        }

        int current = currentMainIndex();
        if (current < 0) return false;

        int next = nextMainIndexAfter(index);
        return next >= 0
                ? current == next
                : current == firstMainIndex();
    }

    public boolean clearActiveSubwaypointParent() {
        if (activeSubwaypointParentIndex < 0) return false;
        activeSubwaypointParentIndex = -1;
        return true;
    }

    public void setName(String newName)                 { this.name = newName == null ? "" : newName; }
        public void setZoneId(String newZoneId)             { this.zoneId = Zone.canonicalId(Objects.requireNonNull(newZoneId)); }
    public void setEnabled(boolean on)                  {
        this.enabled = on;
    }
    public void setDefaultRadius(double r)              { this.defaultRadius = Waypoint.normalizeDefaultRadius(r); invalidateProximityIndex(); }
    public void setSkipAheadEnabled(boolean on)         { this.skipAheadEnabled = on; }
    public void setTemp(boolean on)                     { this.temp = on; }
    public void setRuntimeOnly(boolean on)              { this.runtimeOnly = on; }
    public void setVisibleMainSteps(int count)           { this.visibleMainSteps = Math.max(0, count); }
    public void setRuntimeSourceGroupId(String id)       { this.runtimeSourceGroupId = id; }
    public void setRouteKind(RouteKind kind)              { this.routeKind = Objects.requireNonNull(kind); }
    public void setPaint(WaypointPaint paint)            { this.paint = paint; }
    public void setPaintEnabled(boolean on)               { this.paintEnabled = on; }

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
        GradientMode next = Objects.requireNonNull(mode);
        if (next != gradientMode && gradientMode == GradientMode.MANUAL) {
            captureManualColors();
        }
        gradientMode = next;
        if (next == GradientMode.MANUAL) {
            restoreManualColors(false);
        } else if (next == GradientMode.AUTO) {
            restoreManualColors(true);
        }
        applyColorMode();
    }

    public boolean setManualColorSnapshot(Collection<Integer> colors) {
        if (colors == null || colors.size() != waypoints.size()) return false;
        List<Integer> normalized = new ArrayList<>(colors.size());
        for (Integer color : colors) {
            if (color == null) return false;
            normalized.add(color & 0xFFFFFF);
        }
        manualColors.clear();
        manualColors.addAll(normalized);
        if (gradientMode == GradientMode.MANUAL) {
            restoreManualColors(false);
        } else if (gradientMode == GradientMode.AUTO) {
            restoreManualColors(true);
            applyColorMode();
        }
        return true;
    }

    public void setLoadMode(LoadMode mode) {
        LoadMode next = Objects.requireNonNull(mode);
        this.loadMode = next;
    }

    public void forEachVisibleIndex(IntConsumer action) {
        forEachVisibleIndex(SequenceVisibility.DEFAULT, false, action);
    }

    public void forEachVisibleIndex(boolean keepSubwaypointsVisibleUntilNextWaypoint,
                                    IntConsumer action) {
        forEachVisibleIndex(SequenceVisibility.DEFAULT,
                keepSubwaypointsVisibleUntilNextWaypoint, action);
    }

    public void forEachVisibleIndex(SequenceVisibility visibility, IntConsumer action) {
        forEachVisibleIndex(visibility, false, action);
    }

    public void forEachVisibleIndex(SequenceVisibility visibility,
                                    boolean keepSubwaypointsVisibleUntilNextWaypoint,
                                    IntConsumer action) {
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(action, "action");
        int n = waypoints.size();
        if (n == 0) return;

        if (focusedVisibleIndex != null) {
            int index = focusedVisibleIndex;
            if (isWaypointEnabled(index)) action.accept(index);
            return;
        }

        if (loadMode == LoadMode.STATIC) {
            for (int i = 0; i < n; i++) {
                if (isWaypointEnabled(i)) action.accept(i);
            }
            return;
        }

        if (isComplete()) {
            int activeParent = activeSubwaypointParentIndex();
            int lastMain = activeParent >= 0 ? activeParent : lastMainIndex();
            emitPreviousMainContext(lastMain, visibility.previousLimit(n), activeParent,
                    keepSubwaypointsVisibleUntilNextWaypoint, action, true);
            return;
        }

        int cur = currentMainIndex();
        if (cur < 0) return;

        int activeParent = activeSubwaypointParentIndex();
        if (isDungeonRoomRoute()) {
            if (visibleMainSteps > 0) {
                int exactCurrent = currentIndex;
                if (exactCurrent >= 0 && exactCurrent < n
                        && !waypoints.get(exactCurrent).hasFlag(
                        Waypoint.FLAG_DUNGEON_PEARL_TARGET)) {
                    if (isWaypointEnabled(exactCurrent)) action.accept(exactCurrent);
                }
                int stage = currentMainIndex();
                for (int remaining = visibleMainSteps - 1; remaining > 0 && stage >= 0; remaining--) {
                    stage = nextMainIndexAfter(stage);
                    if (stage >= 0 && isWaypointEnabled(stage)) action.accept(stage);
                }
                return;
            }
            if (activeParent >= 0) {
                if (isWaypointEnabled(activeParent)) action.accept(activeParent);
                for (int child = activeParent + 1; child < n && isSubwaypoint(child); child++) {
                    if (isWaypointEnabled(child)) action.accept(child);
                }
            }
            for (int i = cur; i < n; i++) {
                if (isWaypointEnabled(i)) action.accept(i);
            }
            return;
        }

        emitPreviousMainContext(cur, visibility.previousLimit(n), activeParent,
                keepSubwaypointsVisibleUntilNextWaypoint, action, false);
        boolean activeParentAlreadyEmitted = activeParent > cur
                && isWaypointEnabled(activeParent)
                && visibility.previousLimit(n) > 0;
        if (visibility.current()) {
            emitMainBlock(cur, activeParent,
                    keepSubwaypointsVisibleUntilNextWaypoint, action,
                    activeParent < 0 || activeParent == cur);
        }
        int next = cur;
        for (int remaining = visibility.nextLimit(n); remaining > 0; remaining--) {
            next = nextMainIndexAfter(next);
            if (next < 0) break;
            if (activeParentAlreadyEmitted && next == activeParent) continue;
            action.accept(next);
        }
    }

    private void emitPreviousMainContext(int beforeOrLast, int count, int activeParent,
                                         boolean keepSubwaypointsVisibleUntilNextWaypoint,
                                         IntConsumer action, boolean includeAnchor) {
        if (count <= 0 || beforeOrLast < 0) return;
        if (!includeAnchor && activeParent > beforeOrLast && isWaypointEnabled(activeParent)) {
            emitMainBlock(activeParent, activeParent,
                    keepSubwaypointsVisibleUntilNextWaypoint, action, true);
            count--;
            if (count == 0) return;
        }
        int newest = includeAnchor
                ? beforeOrLast : previousEnabledMainIndexBefore(beforeOrLast);
        if (newest < 0) return;
        int oldest = newest;
        for (int found = 1; found < count; found++) {
            int previous = previousEnabledMainIndexBefore(oldest);
            if (previous < 0) break;
            oldest = previous;
        }
        for (int main = oldest; main >= 0; main = nextMainIndexAfter(main)) {
            emitMainBlock(main, activeParent,
                    keepSubwaypointsVisibleUntilNextWaypoint, action, main == activeParent);
            if (main == newest) break;
        }
    }

    private void emitMainBlock(int main, int activeParent,
                               boolean keepSubwaypointsVisibleUntilNextWaypoint,
                               IntConsumer action, boolean includeChildren) {
        if (main < 0 || !isWaypointEnabled(main)) return;
        action.accept(main);
        if (!includeChildren) return;
        for (int child = main + 1; child < waypoints.size() && isSubwaypoint(child); child++) {
            if (isWaypointEnabled(child)
                    && shouldSurfaceSubwaypointForParent(child, main, activeParent,
                    keepSubwaypointsVisibleUntilNextWaypoint)) {
                action.accept(child);
            }
        }
    }

    private boolean shouldSurfaceSubwaypointForParent(int childIndex,
                                                       int parentIndex,
                                                       int activeParentIndex,
                                                       boolean keepVisibleUntilNextWaypoint) {
        if (childIndex < 0 || childIndex >= waypoints.size() || !isSubwaypoint(childIndex)) {
            return false;
        }
        if (parentIndex != activeParentIndex) return true;
        if (keepVisibleUntilNextWaypoint) return true;
        if (isDungeonRoute()) return true;
        return !waypoints.get(childIndex)
                .hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED);
    }

    private boolean isDungeonRoute() {
        return routeKind == RouteKind.DUNGEON;
    }

    private boolean isDungeonRoomRoute() {
        return routeKind == RouteKind.DUNGEON;
    }

    public Waypoint get(int index) {
        return waypoints.get(index);
    }

    public Waypoint current() {
        if (currentIndex < 0 || currentIndex >= waypoints.size()) return null;
        Waypoint current = waypoints.get(currentIndex);
        return current.isDisabled() ? null : current;
    }

    public boolean setWaypointDisabled(int index, boolean disabled) {
        if (index < 0 || index >= waypoints.size()) return false;
        Waypoint current = waypoints.get(index);
        if (current.isDisabled() == disabled) return false;
        int flags = disabled
                ? current.flags() | Waypoint.FLAG_DISABLED
                : current.flags() & ~Waypoint.FLAG_DISABLED;
        waypoints.set(index, current.withFlags(flags));
        if (!disabled && staticReached != null && index < staticReached.length) {
            staticReached[index] = false;
        }
        normalizeCurrentIndexToEnabledTarget();
        if (focusedVisibleIndex != null && !isWaypointEnabled(focusedVisibleIndex)) {
            focusedVisibleIndex = null;
        }
        if (proximitySuppressedIndex == index && disabled) {
            proximitySuppressedIndex = -1;
        }
        clearStandSkipHold();
        invalidateProximityIndex();
        reconcileStaticReachState();
        return true;
    }

    public boolean toggleWaypointDisabled(int index) {
        return index >= 0 && index < waypoints.size()
                && setWaypointDisabled(index, !isWaypointDisabled(index));
    }

        public void set(int index, Waypoint replacement) {
        Waypoint normalized = normalizeWaypointForIndex(index, replacement);
        if (gradientMode == GradientMode.STATIC) {
            normalized = normalized.withColor(staticColor);
        }
        waypoints.set(index, normalized);
        if (gradientMode == GradientMode.MANUAL) {
            manualColors.set(index, normalized.color() & 0xFFFFFF);
        }
        afterWaypointStructureChanged();
    }

    public void moveWaypointTo(int index, int x, int y, int z) {
        waypoints.set(index, waypoints.get(index).withPos(x, y, z));
        afterWaypointStructureChanged();
        focusNewWaypoint(index);
    }

    public void moveWaypointToPrecise(int index, int preciseX, int preciseY, int preciseZ) {
        if (index < 0 || index >= waypoints.size()) return;
        waypoints.set(index, waypoints.get(index).withPreciseSixteenths(preciseX, preciseY, preciseZ));
        afterWaypointStructureChanged();
        focusNewWaypoint(index);
    }

        public void add(Waypoint w) {
        int oldSize = waypoints.size();
        waypoints.add(w);
        manualColors.add(w.color() & 0xFFFFFF);
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
        for (int i = oldSize; i < waypoints.size(); i++) {
            manualColors.add(waypoints.get(i).color() & 0xFFFFFF);
        }
        normalizeSubwaypointStructure();
        resizeStaticReachAfterAppend(oldSize);
        normalizeCurrentIndexToMain();
        invalidateProximityIndex();
        applyColorMode();
    }

        public void replaceWaypoints(Collection<Waypoint> replacements) {
        waypoints.clear();
        waypoints.addAll(replacements);
        manualColors.clear();
        for (Waypoint waypoint : waypoints) {
            manualColors.add(waypoint.color() & 0xFFFFFF);
        }
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
        manualColors.add(index, w.color() & 0xFFFFFF);
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
        boolean removedSubwaypoint = isSubwaypoint(index);
        waypoints.remove(index);
        manualColors.remove(index);
        if (!removedSubwaypoint) promoteOrphanedSubwaypoints(index);
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

    public void advancePast(int reachedIndex) {
        if (isSubwaypoint(reachedIndex)) {
            int next = nextCompletableChildAfter(reachedIndex);
            if (next >= 0) {
                currentIndex = next;
                activeSubwaypointParentIndex = parentMainIndex(next);
                return;
            }
            int nextMain = nextMainIndexAtOrAfter(childEndExclusive(parentMainIndex(reachedIndex)));
            currentIndex = nextMain >= 0 ? nextMain : waypoints.size();
            activeSubwaypointParentIndex = -1;
            return;
        }
        int firstChild = firstCompletableChild(reachedIndex);
        if (firstChild >= 0) {
            currentIndex = firstChild;
            activeSubwaypointParentIndex = reachedIndex;
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

    private int firstCompletableChild(int parentIndex) {
        if (parentIndex < 0 || parentIndex >= waypoints.size() || isSubwaypoint(parentIndex)) {
            return -1;
        }
        int end = childEndExclusive(parentIndex);
        for (int i = parentIndex + 1; i < end; i++) {
            if (isWaypointEnabled(i) && hasDungeonCompletion(waypoints.get(i))) return i;
        }
        return -1;
    }

    private int nextCompletableChildAfter(int childIndex) {
        int parent = parentMainIndex(childIndex);
        if (parent < 0) return -1;
        int end = childEndExclusive(parent);
        for (int i = childIndex + 1; i < end; i++) {
            if (isWaypointEnabled(i) && hasDungeonCompletion(waypoints.get(i))) return i;
        }
        return -1;
    }

    private static boolean hasDungeonCompletion(Waypoint waypoint) {
        return waypoint != null
                && (waypoint.flags() & Waypoint.DUNGEON_COMPLETION_FLAGS) != 0;
    }

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
            target = previousEnabledMainIndexBefore(currentIndex);
        }

        if (target < 0) return false;

        target = previousEnabledTargetAtOrBefore(target);
        if (target < 0) return false;

        int before = currentIndex;
        currentIndex = target;
        activeSubwaypointParentIndex = -1;
        clearProximitySuppression();
        clearStandSkipHold();
        return currentIndex != before;
    }

    private int previousEnabledTargetAtOrBefore(int index) {
        for (int i = Math.min(index, waypoints.size() - 1); i >= 0; i--) {
            if (!isWaypointEnabled(i)) continue;
            if (routeKind != RouteKind.DUNGEON
                    || !isSubwaypoint(i)
                    || hasDungeonCompletion(waypoints.get(i))) {
                return i;
            }
        }
        return -1;
    }

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
        currentIndex = firstMain >= 0 ? firstMain : waypoints.size();
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

    boolean hasStaticReachState() {
        return staticReached != null;
    }

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

    public void suppressProximityUntilExit(int index) {
        proximitySuppressedIndex = index >= 0 && index < waypoints.size() ? index : -1;
    }

    public void clearProximitySuppression() {
        proximitySuppressedIndex = -1;
    }

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
        clearStandSkipHold();
    }

    public void setCurrentTargetIndex(int index) {
        if (waypoints.isEmpty()) {
            currentIndex = 0;
            activeSubwaypointParentIndex = -1;
            clearProximitySuppression();
            clearStandSkipHold();
            return;
        }
        if (index >= waypoints.size()) {
            currentIndex = waypoints.size();
            activeSubwaypointParentIndex = -1;
            clearProximitySuppression();
            clearStandSkipHold();
            return;
        }
        activeSubwaypointParentIndex = -1;
        currentIndex = Math.max(0, index);
        normalizeCurrentIndexToEnabledTarget();
        clearProximitySuppression();
        clearStandSkipHold();
    }

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

    public boolean standSkipHeldLongEnough(int index, long nowMillis, long requiredMillis) {
        if (index < 0 || index >= waypoints.size()) {
            clearStandSkipHold();
            return false;
        }
        if (standSkipHoldIndex != index) {
            standSkipHoldIndex = index;
            standSkipHoldStartedAtMillis = nowMillis;
            return requiredMillis <= 0;
        }
        return nowMillis - standSkipHoldStartedAtMillis >= requiredMillis;
    }

    public int standSkipHoldIndex() {
        return standSkipHoldIndex;
    }

    public void clearStandSkipHold() {
        standSkipHoldIndex = -1;
        standSkipHoldStartedAtMillis = 0L;
    }

    public void clearStandSkipHold(int index) {
        if (standSkipHoldIndex == index) clearStandSkipHold();
    }

        private void applyColorMode() {
        if (gradientMode == GradientMode.STATIC) {
            applyStaticColor();
        } else if (gradientMode == GradientMode.AUTO) {
            GradientColorizer.apply(this);
        }
    }

    private void captureManualColors() {
        if (manualColors.size() != waypoints.size()) {
            manualColors.clear();
            for (Waypoint waypoint : waypoints) {
                manualColors.add(waypoint.color() & 0xFFFFFF);
            }
            return;
        }
        for (int i = 0; i < waypoints.size(); i++) {
            manualColors.set(i, waypoints.get(i).color() & 0xFFFFFF);
        }
    }

    private void restoreManualColors(boolean lockedOnly) {
        if (manualColors.size() != waypoints.size()) return;
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint waypoint = waypoints.get(i);
            if (lockedOnly && !waypoint.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) continue;
            int color = manualColors.get(i);
            if (waypoint.color() != color) {
                waypoints.set(i, waypoint.withColor(color));
            }
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
        clearStandSkipHold();
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

    private void normalizeCurrentIndexToEnabledTarget() {
        if (currentIndex < 0 || currentIndex >= waypoints.size()) return;
        if (isWaypointEnabled(currentIndex)) return;

        if (isSubwaypoint(currentIndex)) {
            int child = nextCompletableChildAfter(currentIndex);
            if (child >= 0) {
                currentIndex = child;
                activeSubwaypointParentIndex = parentMainIndex(child);
                return;
            }
        }

        int next = nextMainIndexAtOrAfter(currentIndex + 1);
        currentIndex = next >= 0 ? next : waypoints.size();
        activeSubwaypointParentIndex = -1;
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
        if (!isSubwaypoint(clamped) && isWaypointEnabled(clamped)) return clamped;

        int parent = parentMainIndex(clamped);
        if (parent >= 0 && isWaypointEnabled(parent)) return parent;

        int next = nextMainIndexAtOrAfter(clamped);
        return next >= 0 ? next : waypoints.size();
    }

    private int nextMainIndexAtOrAfter(int index) {
        for (int i = Math.max(0, index); i < waypoints.size(); i++) {
            if (!isSubwaypoint(i) && isWaypointEnabled(i)) return i;
        }
        return -1;
    }

    private int nextPhysicalMainIndexAfter(int index) {
        for (int i = Math.max(0, index + 1); i < waypoints.size(); i++) {
            if (!isSubwaypoint(i)) return i;
        }
        return -1;
    }

    private int firstMainIndex() {
        return nextMainIndexAtOrAfter(0);
    }

    public int lastMainIndex() {
        for (int i = waypoints.size() - 1; i >= 0; i--) {
            if (!isSubwaypoint(i) && isWaypointEnabled(i)) return i;
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
            int next = nextPhysicalMainIndexAfter(index);
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
        int manualColor = manualColors.remove(from);
        waypoints.add(to, waypoint);
        manualColors.add(to, manualColor);
    }

    private void moveRange(int start, int end, int insertAt) {
        if (start >= end || insertAt >= start && insertAt <= end) return;

        List<Waypoint> block = new ArrayList<>(waypoints.subList(start, end));
        List<Integer> manualBlock = new ArrayList<>(manualColors.subList(start, end));
        waypoints.subList(start, end).clear();
        manualColors.subList(start, end).clear();
        int adjustedInsert = insertAt > start ? insertAt - block.size() : insertAt;
        waypoints.addAll(adjustedInsert, block);
        manualColors.addAll(adjustedInsert, manualBlock);
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
            if (isSubwaypoint(i) || isWaypointDisabled(i)) continue;
            hasMainWaypoint = true;
            if (!staticReached[i]) return false;
        }
        return hasMainWaypoint;
    }

    private void reconcileStaticReachState() {
        if (staticReached == null) return;
        if (allStaticWaypointsReached()) {
            resetStaticReachState();
            staticCycleJustCompleted = true;
        }
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
                if (waypoint.isSubwaypoint() || waypoint.isDisabled()) continue;

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

            long cellsX = (long) maxX - minX + 1;
            long cellsY = (long) maxY - minY + 1;
            long cellsZ = (long) maxZ - minZ + 1;
            if (cellsX <= 0 || cellsY <= 0 || cellsZ <= 0
                    || cellsX > MAX_PROXIMITY_CELL_VISITS
                    || cellsY > MAX_PROXIMITY_CELL_VISITS / cellsX
                    || cellsZ > MAX_PROXIMITY_CELL_VISITS / (cellsX * cellsY)) {
                for (IntBucket bucket : buckets.values()) {
                    for (int i = 0; i < bucket.size; i++) {
                        if (!action.test(bucket.values[i])) return false;
                    }
                }
                return true;
            }

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
