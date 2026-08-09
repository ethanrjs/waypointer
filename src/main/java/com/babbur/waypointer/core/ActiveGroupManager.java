package com.babbur.waypointer.core;


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

/** Client-thread owner of live waypoint groups. */
public final class ActiveGroupManager {

    private final Map<String, WaypointGroup> byId = new LinkedHashMap<>();
    private final Collection<WaypointGroup> allGroupsView = Collections.unmodifiableCollection(byId.values());
    private Zone currentZone;
    private final List<Consumer<Zone>> zoneListeners = new ArrayList<>();
    private String focusedTempGroupId;
    private String focusedAuthoringGroupId;
    private String isolatedEditingGroupId;
    private WaypointGroup waypointPreview;

    private final List<Runnable> dataListeners = new ArrayList<>();
    private final List<Runnable> persistentDataListeners = new ArrayList<>();
    private static final Pattern USERNAME_TOKEN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern BRACKETED_PREFIX = Pattern.compile("\\[[^\\]]*\\]");

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

    public List<WaypointGroup> activeGroups() {
        if (cachedActive != null) return cachedActive;

        if (isolatedEditingGroupId != null) {
            WaypointGroup isolated = isolatedEditingGroup();
            cachedActive = isolated == null ? Collections.emptyList() : List.of(isolated);
            return cachedActive;
        }

        if (currentZone == null) {
            WaypointGroup focused = focusedAuthoringGroup();
            List<WaypointGroup> active = focused != null
                    && focused.enabled() && shouldSurfaceActiveGroup(focused)
                    ? List.of(focused)
                    : Collections.emptyList();
            cachedActive = withWaypointPreview(active);
            return cachedActive;
        }
        String zoneId = currentZone.id();
        WaypointGroup focused = focusedTempGroupForZone(zoneId);
        if (focused != null) {
            cachedActive = withWaypointPreview(List.of(focused));
            return cachedActive;
        }

        List<WaypointGroup> active = new ArrayList<>();
        for (WaypointGroup g : byId.values()) {
            if (g.enabled() && zoneId.equals(g.zoneId()) && shouldSurfaceActiveGroup(g)) {
                active.add(g);
            }
        }
        cachedActive = withWaypointPreview(active);
        return cachedActive;
    }

    private List<WaypointGroup> withWaypointPreview(List<WaypointGroup> active) {
        if (waypointPreview == null) return List.copyOf(active);
        List<WaypointGroup> combined = new ArrayList<>(active.size() + 1);
        combined.addAll(active);
        combined.add(waypointPreview);
        return List.copyOf(combined);
    }

    public void setWaypointPreview(WaypointGroup preview) {
        waypointPreview = preview;
        cachedActive = null;
    }

    public void clearWaypointPreview(WaypointGroup preview) {
        if (waypointPreview != preview) return;
        waypointPreview = null;
        cachedActive = null;
    }

    private static boolean shouldSurfaceActiveGroup(WaypointGroup group) {
        return !isCompletedDungeonRoomGroup(group) && !isStoredDungeonRoomGroup(group);
    }

    private static boolean isStoredDungeonRoomGroup(WaypointGroup group) {
        return !group.temp()
                && !group.runtimeOnly()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    private static boolean isCompletedDungeonRoomGroup(WaypointGroup group) {
        return !group.temp()
                && group.isComplete()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
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

    public Collection<WaypointGroup> allGroups() {
        return allGroupsView;
    }

    public List<WaypointGroup> allGroupsList() {
        return List.copyOf(byId.values());
    }

    public WaypointGroup firstActiveGroup() {
        List<WaypointGroup> active = activeGroups();
        return active.isEmpty() ? null : active.get(0);
    }

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

    public void isolateRouteForEditing(WaypointGroup group) {
        String nextId = group != null && byId.get(group.id()) == group ? group.id() : null;
        if (Objects.equals(nextId, isolatedEditingGroupId)) return;
        isolatedEditingGroupId = nextId;
        cachedActive = null;
    }

    private WaypointGroup isolatedEditingGroup() {
        WaypointGroup selected = byId.get(isolatedEditingGroupId);
        if (selected == null) return null;
        if (!isStoredDungeonRoomGroup(selected)) return selected;
        for (WaypointGroup candidate : byId.values()) {
            if (candidate.runtimeOnly()
                    && selected.id().equals(candidate.runtimeSourceGroupId())) {
                return candidate;
            }
        }
        return null;
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
            if (!g.temp() && !g.runtimeOnly() && g.enabled() && zoneId.equals(g.zoneId())) {
                return g;
            }
        }
        return null;
    }

    public WaypointGroup getOrCreateTempGroup() {
        Zone zone = currentZone == null ? Zone.UNKNOWN : currentZone;
        String tempId = "temp::" + zone.id();
        WaypointGroup existing = byId.get(tempId);
        if (existing != null && existing.temp()) {
            existing.setName("Temporary");
            return existing;
        }

        WaypointGroup g = new WaypointGroup(tempId, "Temporary", zone.id());
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setTemp(true);
        add(g);
        return g;
    }

    public WaypointGroup addTempWaypoint(int x, int y, int z) {
        return addTempWaypoint(x, y, z, "");
    }

    public WaypointGroup addTempWaypoint(int x, int y, int z, String sourceName) {
        return addTempWaypoint(x, y, z, sourceName, Waypoint.TEMP_UNTIL_LEAVE, 0L);
    }

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
        String zoneId = currentZone == null ? Zone.UNKNOWN.id() : currentZone.id();
        TempWaypointSelection fallback = null;
        for (WaypointGroup group : byId.values()) {
            if (!group.temp() || !zoneId.equals(group.zoneId())) continue;
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

    public void addAll(Collection<WaypointGroup> groups) {
        if (groups.isEmpty()) return;
        boolean persistent = false;
        for (WaypointGroup group : groups) {
            WaypointGroup previous = byId.put(group.id(), group);
            persistent |= isPersistent(previous) || isPersistent(group);
        }
        fireDataChanged(persistent);
    }

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
