package com.babbur.waypointer.core;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
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
    private String focusedAuthoringGroupId;

    private final List<Runnable> dataListeners = new ArrayList<>();
    private final List<Runnable> persistentDataListeners = new ArrayList<>();
    private static final Pattern USERNAME_TOKEN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern BRACKETED_PREFIX = Pattern.compile("\\[[^\\]]*\\]");

    // Cached result of activeGroups(). The renderer calls this every frame from two
    // separate COLLECT_SUBMITS handlers, so rebuilding on every call burns avoidable
    // young-gen garbage. Invalidated on zone change and on fireDataChanged(),
    // which every mutation path funnels through.
    private List<WaypointGroup> cachedActive;

    public Zone currentZone() {
        return currentZone;
    }

    public void onZoneChanged(Zone newZone) {
        if (Objects.equals(newZone, currentZone)) return;
        for (WaypointGroup group : byId.values()) group.resetRouteTiming();
        currentZone = newZone;
        cachedActive = null;
        for (Consumer<Zone> l : List.copyOf(zoneListeners)) l.accept(newZone);
    }

    /**
     * Groups that should render right now: matching current zone AND enabled.
     *
     * If no zone has been detected (non-Skyblock world, menu, or before the zone
     * source has reported in), only the one route explicitly selected for
     * authoring may render. This keeps offline editing usable without falling
     * back to every route in an arbitrary zone.
     *
     * Returned list is cached and reused across frames -- callers must treat it as
     * read-only. The cache is rebuilt lazily on the next call after any
     * invalidation (zone change or data change).
     */
    public List<WaypointGroup> activeGroups() {
        if (cachedActive != null) return cachedActive;

        if (currentZone == null) {
            WaypointGroup focused = focusedAuthoringGroup();
            cachedActive = focused != null && focused.enabled() && shouldSurfaceActiveGroup(focused)
                    ? List.of(focused)
                    : Collections.emptyList();
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
            if (g.enabled() && zoneId.equals(g.zoneId()) && shouldSurfaceActiveGroup(g)) {
                active.add(g);
            }
        }
        cachedActive = List.copyOf(active);
        return cachedActive;
    }

    private static boolean shouldSurfaceActiveGroup(WaypointGroup group) {
        return !isCompletedDungeonRoomGroup(group) && !isStoredDungeonRoomGroup(group);
    }

    /**
     * Stored (persisted) dungeon-room routes hold room-local coordinates, so
     * rendering them directly would paint boxes at raw local positions. They
     * act in-world only through the runtime mirror {@code DungeonRoomRouteSync}
     * projects for the current room placement.
     */
    private static boolean isStoredDungeonRoomGroup(WaypointGroup group) {
        return !group.temp()
                && !group.runtimeOnly()
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    private static boolean isCompletedDungeonRoomGroup(WaypointGroup group) {
        return !group.temp()
                && group.isComplete()
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    public List<WaypointGroup> completedDungeonRoomGroupsInCurrentZone() {
        if (currentZone == null) return Collections.emptyList();

        String zoneId = currentZone.id();
        List<WaypointGroup> completed = new ArrayList<>();
        for (WaypointGroup group : byId.values()) {
            if (group.enabled()
                    && zoneId.equals(group.zoneId())
                    && isCompletedDungeonRoomGroup(group)) {
                completed.add(group);
            }
        }
        return List.copyOf(completed);
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
     * Selects the one persisted route that may be previewed and edited while no
     * Hypixel zone is available. Temp/runtime groups and room-local dungeon
     * routes are deliberately excluded from this direct world-space preview.
     */
    public void focusRouteForAuthoring(WaypointGroup group) {
        String nextId = group != null
                && byId.get(group.id()) == group
                && !group.temp()
                && !group.runtimeOnly()
                && shouldSurfaceActiveGroup(group)
                ? group.id()
                : null;
        if (Objects.equals(nextId, focusedAuthoringGroupId)) return;
        focusedAuthoringGroupId = nextId;
        cachedActive = null;
    }

    private WaypointGroup focusedAuthoringGroup() {
        if (focusedAuthoringGroupId == null) return null;
        WaypointGroup group = byId.get(focusedAuthoringGroupId);
        if (group == null || group.temp() || group.runtimeOnly() || !shouldSurfaceActiveGroup(group)) {
            focusedAuthoringGroupId = null;
            return null;
        }
        return group;
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
        return getOrCreateActiveGroup(true);
    }

    public WaypointGroup getOrCreateActiveGroup(boolean skipAheadDefault) {
        WaypointGroup existing = firstActiveRouteGroup();
        if (existing != null) return existing;
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        WaypointGroup g = WaypointGroup.create(
                "Route -- " + zone.displayName().toLowerCase(Locale.ROOT),
                zone.id(),
                skipAheadDefault);
        add(g);
        return g;
    }

    private WaypointGroup firstActiveRouteGroup() {
        if (currentZone == null) {
            WaypointGroup focused = focusedAuthoringGroup();
            return focused != null && focused.enabled() ? focused : null;
        }

        String zoneId = currentZone.id();
        for (WaypointGroup g : byId.values()) {
            // Never hand out runtime mirrors as an add target: waypoints added
            // to a projected dungeon-room mirror vanish on its next rebuild.
            if (!g.temp() && !g.runtimeOnly() && g.enabled() && zoneId.equals(g.zoneId())) {
                return g;
            }
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
     * <p>One bucket per zone, keyed by {@code "temp::<zoneId>"}. The editor shows
     * all of these buckets under its virtual Temporary zone instead of exposing
     * the implementation detail in each group's name.
     */
    public WaypointGroup getOrCreateTempGroup() {
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        String tempId = "temp::" + zone.id();
        WaypointGroup existing = byId.get(tempId);
        if (existing != null && existing.temp()) {
            existing.setName("Temporary");
            return existing;
        }

        // No existing temp bucket for this zone -- build one. Static load mode
        // keeps all temps visible at once (they're not a sequenced route), and
        // the skip-ahead flag is irrelevant because temp groups are excluded
        // from proximity advance.
        WaypointGroup g = new WaypointGroup(tempId, "Temporary", zone.id());
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setTemp(true);
        add(g);
        return g;
    }

    /** Add a disconnect-scoped temporary waypoint in the current zone's isolated temp bucket. */
    public WaypointGroup addTempWaypoint(int x, int y, int z) {
        return addTempWaypoint(x, y, z, "");
    }

    public WaypointGroup addTempWaypoint(int x, int y, int z, String sourceName) {
        return addTempWaypoint(x, y, z, sourceName, Waypoint.TEMP_UNTIL_LEAVE, 0L);
    }

    /**
     * Add a temporary waypoint in the current zone's isolated temp bucket using
     * the caller's chosen lifecycle mode. Chat detection, commands, and keybinds
     * share this path so temp markers stay static-rendered and out of real routes.
     */
    public WaypointGroup addTempWaypoint(int x, int y, int z, String sourceName,
                                         int tempMode, long expiresAtMillis) {
        return addTempWaypoint(x, y, z, sourceName, tempMode, expiresAtMillis,
                Waypoint.DEFAULT_COLOR);
    }

    public WaypointGroup addTempWaypoint(int x, int y, int z, String sourceName,
                                         int tempMode, long expiresAtMillis,
                                         int color) {
        String source = sanitizeTempSourceName(sourceName);
        WaypointGroup target = getOrCreateTempGroup();
        int mode = Waypoint.normalizeTempMode(tempMode);
        long expiresAt = mode == Waypoint.TEMP_TIME ? Math.max(0L, expiresAtMillis) : 0L;
        Waypoint waypoint = Waypoint.at(x, y, z)
                .withColor(color & 0xFFFFFF)
                .withName(source)
                .withTemp(mode, expiresAt);
        target.add(waypoint);
        fireTransientDataChanged();
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

    /**
     * Remove every temporary waypoint from every zone bucket. Empty temp groups
     * may remain in memory, but they are hidden from the Temporary menu and
     * never persist to disk.
     *
     * @return number of temporary waypoints removed
     */
    public int clearTemporaryWaypoints() {
        clearTempWaypointFocus();

        int removed = 0;
        for (WaypointGroup group : byId.values()) {
            if (group.temp()) removed += group.removeAllTemp();
        }
        if (removed > 0) fireTransientDataChanged();
        return removed;
    }

    public TempWaypointSelection findTempWaypoint(int x, int y, int z, String senderName) {
        String wantedSender = normalizeSenderName(senderName);
        TempWaypointSelection fallback = null;
        for (WaypointGroup group : byId.values()) {
            if (!group.temp()) continue;
            for (int i = 0; i < group.size(); i++) {
                Waypoint waypoint = group.get(i);
                if (!waypoint.isTemp()
                        || waypoint.x() != x
                        || waypoint.y() != y
                        || waypoint.z() != z) {
                    continue;
                }
                TempWaypointSelection selection = new TempWaypointSelection(group, i);
                if (fallback == null) fallback = selection;
                if (!wantedSender.isEmpty()
                        && wantedSender.equalsIgnoreCase(senderNameForTempWaypoint(waypoint))) {
                    return selection;
                }
            }
        }
        return fallback;
    }

    public int removeTempWaypointsFromSender(String senderName) {
        String wantedSender = normalizeSenderName(senderName);
        if (wantedSender.isEmpty()) return 0;

        clearTempWaypointFocus();
        int removed = 0;
        for (WaypointGroup group : byId.values()) {
            if (!group.temp()) continue;
            for (int i = group.size() - 1; i >= 0; i--) {
                if (wantedSender.equalsIgnoreCase(senderNameForTempWaypoint(group.get(i)))) {
                    group.remove(i);
                    removed++;
                }
            }
        }
        if (removed > 0) fireTransientDataChanged();
        return removed;
    }

    private static String sanitizeTempSourceName(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        StringBuilder out = new StringBuilder(Math.min(trimmed.length(), 128));
        for (int i = 0; i < trimmed.length() && out.length() < 128; i++) {
            char c = trimmed.charAt(i);
            if ((c == '\u00A7' && i + 1 < trimmed.length()) || !Character.isISOControl(c)) {
                out.append(c);
            }
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\u00A7') out.setLength(len - 1);
        return out.toString().trim();
    }

    private static String senderNameForTempWaypoint(Waypoint waypoint) {
        if (waypoint == null || !waypoint.hasName()) return "";
        String plain = stripLegacyFormatting(waypoint.name());
        if (plain.startsWith("From ")) plain = plain.substring(5);
        plain = BRACKETED_PREFIX.matcher(plain).replaceAll(" ");
        Matcher matcher = USERNAME_TOKEN.matcher(plain);
        String last = "";
        while (matcher.find()) last = matcher.group();
        return last;
    }

    private static String stripLegacyFormatting(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String normalizeSenderName(String senderName) {
        return senderName == null ? "" : senderName.trim();
    }

    public List<WaypointGroup> groupsForZone(String zoneId) {
        String canonicalZoneId = Zone.canonicalId(Objects.requireNonNull(zoneId, "zoneId"));
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup g : byId.values()) {
            if (canonicalZoneId.equals(g.zoneId())) out.add(g);
        }
        return out;
    }

    /** Distinct zone ids that at least one group is attached to, preserving insertion order. */
    public List<String> knownZoneIds() {
        Set<String> out = new LinkedHashSet<>();
        for (WaypointGroup g : byId.values()) {
            out.add(g.zoneId());
        }
        return List.copyOf(out);
    }

    public WaypointGroup get(String id) {
        return byId.get(id);
    }

    public void add(WaypointGroup group) {
        WaypointGroup previous = byId.put(group.id(), group);
        fireDataChanged(isPersistent(previous) || isPersistent(group));
    }

    /**
     * Add several groups as one logical mutation. Bulk import paths use this so
     * autosave and external API listeners see a single completed import rather
     * than one intermediate notification per group.
     */
    public void addAll(Collection<WaypointGroup> groups) {
        if (groups.isEmpty()) return;
        boolean persistent = false;
        for (WaypointGroup group : groups) {
            WaypointGroup previous = byId.put(group.id(), group);
            persistent |= isPersistent(previous) || isPersistent(group);
        }
        fireDataChanged(persistent);
    }

    /**
     * Replace all persisted groups in one mutation after the caller has fully
     * validated the replacement set. Storage load uses this to avoid clearing
     * the live manager until malformed files have already been rejected.
     */
    public void replaceAll(Collection<WaypointGroup> groups) {
        boolean persistent = false;
        for (WaypointGroup group : byId.values()) persistent |= isPersistent(group);
        byId.clear();
        for (WaypointGroup group : groups) {
            byId.put(group.id(), group);
            persistent |= isPersistent(group);
        }
        fireDataChanged(persistent);
    }

    public void remove(String id) {
        WaypointGroup removed = byId.remove(id);
        if (removed != null) fireDataChanged(isPersistent(removed));
    }

    public void removeAll(Collection<String> ids) {
        boolean changed = false;
        boolean persistent = false;
        for (String id : ids) {
            WaypointGroup removed = byId.remove(id);
            if (removed == null) continue;
            changed = true;
            persistent |= isPersistent(removed);
        }
        if (changed) fireDataChanged(persistent);
    }

    public void clear() {
        boolean persistent = false;
        for (WaypointGroup group : byId.values()) persistent |= isPersistent(group);
        byId.clear();
        fireDataChanged(persistent);
    }

    public void fireDataChanged() {
        fireDataChanged(true);
    }

    public void fireDataChangedFor(WaypointGroup group) {
        fireDataChanged(isPersistent(group));
    }

    public void fireTransientDataChanged() {
        fireDataChanged(false);
    }

    private void fireDataChanged(boolean persistent) {
        cachedActive = null;
        for (Runnable l : List.copyOf(dataListeners)) l.run();
        if (persistent) {
            for (Runnable l : List.copyOf(persistentDataListeners)) l.run();
        }
    }

    private static boolean isPersistent(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly();
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
    public void addPersistentDataListener(Runnable listener) { persistentDataListeners.add(listener); }
    public void removePersistentDataListener(Runnable listener) { persistentDataListeners.remove(listener); }

    public record TempWaypointSelection(WaypointGroup group, int index) {}
}
