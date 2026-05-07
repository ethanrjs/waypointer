package dev.ethan.waypointer.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Owns every {@link WaypointGroup} the user has configured and tracks which zone
 * is currently active, exposing the subset of groups that should render right now.
 *
 * Keyed by group id internally; zone-membership is discovered on the fly via
 * {@link WaypointGroup#zoneId()}. This lets a group be reassigned to a different
 * zone without any global bookkeeping.
 *
 * Not thread-safe -- all mutations run on the render/client thread.
 */
public final class ActiveGroupManager {

    private final Map<String, WaypointGroup> byId = new LinkedHashMap<>();
    /**
     * Live unmodifiable {@link Collection} view over {@link #byId}'s values,
     * returned from {@link #allGroups()}. Backed directly by the map, so no
     * per-call allocation -- important for the tick-level hot callers
     * ({@code TempWaypointCleaner}, {@code ProximityTracker}, autosave
     * listeners). Iteration order matches insertion order via
     * {@link LinkedHashMap}'s contract.
     */
    private final Collection<WaypointGroup> allGroupsView = Collections.unmodifiableCollection(byId.values());
    private Zone currentZone;
    private final List<Consumer<Zone>> zoneListeners = new ArrayList<>();
    private String focusedTempGroupId;

    private static final Pattern TEMP_GROUP_ID_UNSAFE = Pattern.compile("[^a-z0-9_]+");
    private final List<Runnable> dataListeners = new ArrayList<>();

    // Cached result of activeGroups(). The renderer calls this every frame from two
    // separate END_MAIN handlers, so rebuilding on every call burns avoidable
    // young-gen garbage. Invalidated on zone change and on fireDataChanged(),
    // which every mutation path funnels through.
    private List<WaypointGroup> cachedActive;

    public Zone currentZone() {
        return currentZone;
    }

    public void onZoneChanged(Zone newZone) {
        if (Objects.equals(newZone, currentZone)) return;
        currentZone = newZone;
        cachedActive = null;
        for (Consumer<Zone> l : List.copyOf(zoneListeners)) l.accept(newZone);
    }

    /**
     * Groups that should render right now: matching current zone AND enabled.
     *
     * If no zone has been detected (non-Skyblock world, menu, or before the zone
     * source has reported in) we return empty. Previously this fell back to the
     * {@link Zone#UNKNOWN} id so waypoints created on non-Skyblock servers could
     * still render, but that meant the mod painted boxes in singleplayer and
     * non-Skyblock Hypixel gamemodes too. Since Waypointer is explicitly a
     * Skyblock tool, gating on a resolved zone is the honest behaviour -- users
     * who want generic multiplayer waypoints can run a different mod.
     *
     * Returned list is cached and reused across frames -- callers must treat it as
     * read-only. The cache is rebuilt lazily on the next call after any
     * invalidation (zone change or data change).
     */
    public List<WaypointGroup> activeGroups() {
        if (cachedActive != null) return cachedActive;

        if (currentZone == null) {
            cachedActive = Collections.emptyList();
            return cachedActive;
        }
        String zoneId = currentZone.id();
        WaypointGroup focused = focusedTempGroupForZone(zoneId);
        if (focused != null) {
            cachedActive = List.of(focused);
            return cachedActive;
        }

        List<WaypointGroup> active = new ArrayList<>();
        for (WaypointGroup g : byId.values()) {
            if (g.enabled() && zoneId.equals(g.zoneId())) active.add(g);
        }
        cachedActive = List.copyOf(active);
        return cachedActive;
    }

    /**
     * All groups the user has configured, in insertion order. Returned as a
     * live unmodifiable view -- fine for iteration and {@code isEmpty()} /
     * {@code size()} checks. Callers that need list semantics (indexed
     * lookup, etc.) should use {@link #allGroupsList()} instead.
     */
    public Collection<WaypointGroup> allGroups() {
        return allGroupsView;
    }

    /**
     * Snapshot of every group as a random-access list. Allocates -- reserved
     * for callers that actually need {@code list.get(i)} semantics (command
     * handlers that parse numeric arguments, UI code walking by index).
     */
    public List<WaypointGroup> allGroupsList() {
        return List.copyOf(byId.values());
    }

    /**
     * The first active group in insertion order, or {@code null} if none are active.
     *
     * "First" is a deterministic hook for UX flows that need "a sensible target
     * group" without forcing the user to pick -- quick-add keybinds, the
     * {@code /wp add} command, etc. Using insertion order means the group the
     * user created first in this zone stays the default target even as others
     * come and go, which matches what players report expecting.
     */
    public WaypointGroup firstActiveGroup() {
        List<WaypointGroup> active = activeGroups();
        return active.isEmpty() ? null : active.get(0);
    }

    /**
     * Returns the first active non-temp route group, or creates a fresh group in
     * the current zone and returns that. The newly-created group's name is built via
     * {@code "Route -- " + zone.displayName()} so first-time users get a labelled
     * route without a naming prompt, while still being able to rename in the UI.
     *
     * Fires {@link #fireDataChanged()} when a group is created so autosave and
     * listeners see the change without the caller needing to remember.
     */
    public WaypointGroup getOrCreateActiveGroup() {
        WaypointGroup existing = firstActiveRouteGroup();
        if (existing != null) return existing;
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        WaypointGroup g = WaypointGroup.create(
                "Route -- " + zone.displayName().toLowerCase(Locale.ROOT), zone.id());
        add(g);
        return g;
    }

    private WaypointGroup firstActiveRouteGroup() {
        if (currentZone == null) return null;

        String zoneId = currentZone.id();
        for (WaypointGroup g : byId.values()) {
            if (!g.temp() && g.enabled() && zoneId.equals(g.zoneId())) return g;
        }
        return null;
    }

    /**
     * The per-zone bucket that owns all temporary waypoints dropped in that zone.
     *
     * <p>Temp waypoints used to be added onto whichever "real" route group
     * happened to be the first active one. That leaked temps into the user's
     * actual route -- reordering, renaming, skip-ahead, gradient colouring, all
     * of it would catch stray temps. Worse, a temp mid-route would make
     * sequence-mode groups visually chaotic. Keeping temps in their own
     * isolated group (marked {@link WaypointGroup#temp()} and forced to
     * {@link WaypointGroup.LoadMode#STATIC}) sidesteps every one of those
     * interactions: the proximity tracker skips it (see
     * {@code ProximityTracker}), the renderer treats it like any other static
     * group, and the UI can filter/collapse it if desired.
     *
     * <p>One bucket per zone, keyed by display-named group id prefix
     * {@code "temp::<zoneId>"}. Lazy creation means zones the player never drops
     * a temp into stay clean.
     */
    public WaypointGroup getOrCreateTempGroup() {
        return getOrCreateTempGroup("");
    }

    public WaypointGroup getOrCreateTempGroup(String sourceName) {
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        String source = sanitizeTempSourceName(sourceName);
        String tempId = source.isEmpty()
                ? "temp::" + zone.id()
                : "temp::" + zone.id() + "::" + tempGroupId(source);
        WaypointGroup existing = byId.get(tempId);
        if (existing != null && existing.temp()) return existing;

        // No existing temp bucket for this zone -- build one. Static load mode
        // keeps all temps visible at once (they're not a sequenced route), and
        // the skip-ahead flag is irrelevant because temp groups are excluded
        // from proximity advance.
        String groupName = source.isEmpty()
                ? "Temp -- " + zone.displayName()
                : "Temp -- " + source + " -- " + zone.displayName();
        WaypointGroup g = new WaypointGroup(tempId, groupName, zone.id());
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setTemp(true);
        add(g);
        return g;
    }

    /**
     * Add a session-scoped temporary waypoint in the current zone's isolated
     * temp bucket. Chat coordinate detection and the {@code /wp addtemp}
     * command share this path so automatic and click-to-add temp markers behave
     * identically: static rendering, no proximity progression, and cleanup on
     * disconnect via {@link dev.ethan.waypointer.progression.TempWaypointCleaner}.
     */
    public WaypointGroup addTempWaypoint(int x, int y, int z) {
        return addTempWaypoint(x, y, z, "");
    }

    public WaypointGroup addTempWaypoint(int x, int y, int z, String sourceName) {
        String source = sanitizeTempSourceName(sourceName);
        WaypointGroup target = getOrCreateTempGroup(source);
        Waypoint waypoint = Waypoint.at(x, y, z)
                .withName(source.isEmpty() ? "" : source + ": " + x + ", " + y + ", " + z)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L);
        target.add(waypoint);
        fireDataChanged();
        return target;
    }

    /**
     * Temporarily narrow rendering to one newly-created temp waypoint. This does
     * not mutate group enabled flags, so disconnect cleanup can restore the user's
     * active zone by simply clearing this transient focus.
     */
    public void focusTempWaypoint(WaypointGroup group, int waypointIndex) {
        if (group == null || !group.temp()) return;
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;
        if (!group.get(waypointIndex).isTemp()) return;

        clearTempWaypointFocus();
        focusedTempGroupId = group.id();
        group.focusNewWaypoint(waypointIndex);
        group.focusOnlyVisibleIndex(waypointIndex);
        cachedActive = null;
    }

    public boolean tempWaypointFocusActive() {
        if (currentZone == null) return false;
        return focusedTempGroupForZone(currentZone.id()) != null;
    }

    public void clearTempWaypointFocus() {
        if (focusedTempGroupId == null) return;

        WaypointGroup group = byId.get(focusedTempGroupId);
        if (group != null) group.clearFocusedVisibleIndex();
        focusedTempGroupId = null;
        cachedActive = null;
    }

    private static String sanitizeTempSourceName(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32).trim() : trimmed;
    }

    private static String tempGroupId(String sourceName) {
        String id = sourceName.toLowerCase(Locale.ROOT);
        id = TEMP_GROUP_ID_UNSAFE.matcher(id).replaceAll("_");
        id = id.replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "unknown" : id;
    }

    public List<WaypointGroup> groupsForZone(String zoneId) {
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup g : byId.values()) {
            if (zoneId.equals(g.zoneId())) out.add(g);
        }
        return out;
    }

    /** Distinct zone ids that at least one group is attached to, preserving insertion order. */
    public List<String> knownZoneIds() {
        List<String> out = new ArrayList<>();
        for (WaypointGroup g : byId.values()) {
            if (!out.contains(g.zoneId())) out.add(g.zoneId());
        }
        return Collections.unmodifiableList(out);
    }

    public WaypointGroup get(String id) {
        return byId.get(id);
    }

    public void add(WaypointGroup group) {
        byId.put(group.id(), group);
        fireDataChanged();
    }

    /**
     * Add several groups as one logical mutation. Bulk import paths use this so
     * autosave and external API listeners see a single completed import rather
     * than one intermediate notification per group.
     */
    public void addAll(Collection<WaypointGroup> groups) {
        if (groups.isEmpty()) return;
        for (WaypointGroup group : groups) byId.put(group.id(), group);
        fireDataChanged();
    }

    /**
     * Replace all persisted groups in one mutation after the caller has fully
     * validated the replacement set. Storage load uses this to avoid clearing
     * the live manager until malformed files have already been rejected.
     */
    public void replaceAll(Collection<WaypointGroup> groups) {
        byId.clear();
        for (WaypointGroup group : groups) byId.put(group.id(), group);
        fireDataChanged();
    }

    public void remove(String id) {
        if (byId.remove(id) != null) fireDataChanged();
    }

    public void clear() {
        byId.clear();
        fireDataChanged();
    }

    public void fireDataChanged() {
        cachedActive = null;
        for (Runnable l : List.copyOf(dataListeners)) l.run();
    }

    private WaypointGroup focusedTempGroupForZone(String zoneId) {
        if (focusedTempGroupId == null) return null;

        WaypointGroup group = byId.get(focusedTempGroupId);
        if (group == null
                || !group.temp()
                || !group.enabled()
                || !zoneId.equals(group.zoneId())) {
            clearTempWaypointFocus();
            return null;
        }

        int index = group.focusedVisibleIndex();
        if (index < 0 || index >= group.size() || !group.get(index).isTemp()) {
            clearTempWaypointFocus();
            return null;
        }
        return group;
    }

    public void addZoneListener(Consumer<Zone> listener) { zoneListeners.add(listener); }
    public void removeZoneListener(Consumer<Zone> listener) { zoneListeners.remove(listener); }

    public void addDataListener(Runnable listener)        { dataListeners.add(listener); }
    public void removeDataListener(Runnable listener)     { dataListeners.remove(listener); }
}
