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
    private final List<Runnable> dataListeners = new ArrayList<>();

    // Cached result of activeGroups(). The renderer calls this every frame from two
    // separate END_MAIN handlers, so returning a fresh ArrayList each time burned a
    // measurable chunk of young-gen garbage. Invalidated on zone change and on
    // fireDataChanged(), which every mutation path funnels through.
    private List<WaypointGroup> cachedActive;
    private final List<WaypointGroup> activeScratch = new ArrayList<>();

    public Zone currentZone() {
        return currentZone;
    }

    public void onZoneChanged(Zone newZone) {
        if (Objects.equals(newZone, currentZone)) return;
        currentZone = newZone;
        cachedActive = null;
        for (Consumer<Zone> l : zoneListeners) l.accept(newZone);
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
        String zid = currentZone.id();
        activeScratch.clear();
        for (WaypointGroup g : byId.values()) {
            if (g.enabled() && zid.equals(g.zoneId())) activeScratch.add(g);
        }
        cachedActive = Collections.unmodifiableList(activeScratch);
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
     * Returns {@link #firstActiveGroup()}, or creates a fresh group in the current
     * zone and returns that. The newly-created group's name is built via
     * {@code "Route -- " + zone.displayName()} so first-time users get a labelled
     * route without a naming prompt, while still being able to rename in the UI.
     *
     * Fires {@link #fireDataChanged()} when a group is created so autosave and
     * listeners see the change without the caller needing to remember.
     */
    public WaypointGroup getOrCreateActiveGroup() {
        WaypointGroup existing = firstActiveGroup();
        if (existing != null) return existing;
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        WaypointGroup g = WaypointGroup.create(
                "Route -- " + zone.displayName().toLowerCase(Locale.ROOT), zone.id());
        add(g);
        return g;
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
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        String tempId = "temp::" + zone.id();
        WaypointGroup existing = byId.get(tempId);
        if (existing != null && existing.temp()) return existing;

        // No existing temp bucket for this zone -- build one. Static load mode
        // keeps all temps visible at once (they're not a sequenced route), and
        // the skip-ahead flag is irrelevant because temp groups are excluded
        // from proximity advance.
        WaypointGroup g = new WaypointGroup(tempId, "Temp -- " + zone.displayName(), zone.id());
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.setTemp(true);
        add(g);
        return g;
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

    public void remove(String id) {
        if (byId.remove(id) != null) fireDataChanged();
    }

    public void clear() {
        byId.clear();
        fireDataChanged();
    }

    public void fireDataChanged() {
        cachedActive = null;
        for (Runnable l : dataListeners) l.run();
    }

    public void addZoneListener(Consumer<Zone> listener) { zoneListeners.add(listener); }
    public void addDataListener(Runnable listener)        { dataListeners.add(listener); }
}
