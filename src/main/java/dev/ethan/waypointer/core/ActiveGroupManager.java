package dev.ethan.waypointer.core;

import dev.ethan.waypointer.diana.DianaBurrowWaypointGroup;

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
import java.util.function.Predicate;
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

    private final List<Runnable> dataListeners = new ArrayList<>();
    private static final Pattern USERNAME_TOKEN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern BRACKETED_PREFIX = Pattern.compile("\\[[^\\]]*\\]");

    // Cached result of activeGroups(). The renderer calls this every frame from two
    // separate END_MAIN handlers, so rebuilding on every call burns avoidable
    // young-gen garbage. Invalidated on zone change and on fireDataChanged(),
    // which every mutation path funnels through.
    private volatile List<WaypointGroup> cachedActive;

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
        List<WaypointGroup> snapshot = cachedActive;
        if (snapshot != null) return snapshot;

        Zone zone = currentZone;
        if (zone == null) {
            snapshot = Collections.emptyList();
            cachedActive = snapshot;
            return snapshot;
        }
        String zoneId = zone.id();
        WaypointGroup focused = focusedTempGroupForZone(zoneId);
        if (focused != null) {
            snapshot = List.of(focused);
            cachedActive = snapshot;
            return snapshot;
        }

        List<WaypointGroup> active = new ArrayList<>();
        for (WaypointGroup g : byId.values()) {
            if (g.enabled() && zoneId.equals(g.zoneId())) active.add(g);
        }
        snapshot = List.copyOf(active);
        cachedActive = snapshot;
        return snapshot;
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
     * <p>One bucket per zone, keyed by {@code "temp::<zoneId>"}. The editor shows
     * all of these buckets under its virtual Temporary zone instead of exposing
     * the implementation detail in each group's name.
     */
    public WaypointGroup getOrCreateTempGroup() {
        return getOrCreateTempGroup("");
    }

    public WaypointGroup getOrCreateTempGroup(String sourceName) {
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
        String source = sanitizeTempSourceName(sourceName);
        WaypointGroup target = getOrCreateTempGroup(source);
        int mode = Waypoint.normalizeTempMode(tempMode);
        long expiresAt = mode == Waypoint.TEMP_TIME ? Math.max(0L, expiresAtMillis) : 0L;
        Waypoint waypoint = Waypoint.at(x, y, z)
                .withName(source)
                .withTemp(mode, expiresAt);
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
        if (removed > 0) fireDataChanged();
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
        if (removed > 0) fireDataChanged();
        return removed;
    }

    /**
     * Remove temporary waypoints in the current zone when the player enters
     * their reach radius. This is intentionally zone-scoped so standing at the
     * same coordinates on another island cannot clear unrelated temp markers.
     *
     * @return number of temporary waypoints removed
     */
    public int removeReachedTempWaypoints(double px, double py, double pz) {
        return removeReachedTempWaypoints(px, py, pz, waypoint -> true);
    }

    public int removeReachedTempWaypoints(double px, double py, double pz, Predicate<Waypoint> shouldRemove) {
        if (currentZone == null) return 0;
        Predicate<Waypoint> filter = shouldRemove == null ? waypoint -> true : shouldRemove;

        int removed = 0;
        String zoneId = currentZone.id();
        boolean focusCleared = false;
        for (WaypointGroup group : byId.values()) {
            if (!group.temp() || !zoneId.equals(group.zoneId()) || group.isEmpty()) continue;
            // Diana burrows are re-synced from particle state; removing markers here
            // can desync the detector's signature cache (see DianaBurrowDetector).
            if (DianaBurrowWaypointGroup.ID.equals(group.id())) continue;

            List<Integer> reached = new ArrayList<>();
            group.forEachNearbyIndex(px, py, pz, group.maxEffectiveRadius(), i -> {
                if (i < 0 || i >= group.size()) return true;
                Waypoint waypoint = group.get(i);
                if (!waypoint.isTemp()) return true;
                if (!filter.test(waypoint)) return true;
                if (isWithinReach(group, waypoint, px, py, pz)) reached.add(i);
                return true;
            });

            if (reached.isEmpty()) continue;
            if (!focusCleared) {
                clearTempWaypointFocus();
                focusCleared = true;
            }
            for (int i = reached.size() - 1; i >= 0; i--) {
                group.remove(reached.get(i));
                removed++;
            }
        }

        if (removed > 0) fireDataChanged();
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

    private static boolean isWithinReach(WaypointGroup group, Waypoint waypoint,
                                         double px, double py, double pz) {
        double radius = group.effectiveRadius(waypoint);
        double dx = (waypoint.x() + 0.5) - px;
        double dy = (waypoint.y() + 0.5) - py;
        double dz = (waypoint.z() + 0.5) - pz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
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

    public record TempWaypointSelection(WaypointGroup group, int index) {}
}
