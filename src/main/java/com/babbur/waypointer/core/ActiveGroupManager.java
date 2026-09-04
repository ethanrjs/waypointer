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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client-thread owner of live waypoint groups. */
public final class ActiveGroupManager {

    private final Map<String, WaypointGroup> byId = new LinkedHashMap<>();
    private final Collection<WaypointGroup> allGroupsView = Collections.unmodifiableCollection(byId.values());
    private final Map<String, RouteFolder> foldersById = new LinkedHashMap<>();
    private final Map<String, String> folderIdByGroupId = new LinkedHashMap<>();
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
                    && focused.enabled()
                    && shouldSurfaceActiveGroup(focused)
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
            if (g.enabled()
                    && zoneId.equals(g.zoneId()) && shouldSurfaceActiveGroup(g)) {
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

    public List<RouteFolder> folders() {
        return List.copyOf(foldersById.values());
    }

    public List<RouteFolder> foldersForZone(String zoneId) {
        String canonical = Zone.canonicalId(Objects.requireNonNull(zoneId, "zoneId"));
        List<RouteFolder> out = new ArrayList<>();
        for (RouteFolder folder : foldersById.values()) {
            if (canonical.equals(folder.zoneId())) out.add(folder);
        }
        return List.copyOf(out);
    }

    public RouteFolder folder(String folderId) {
        return foldersById.get(folderId);
    }

    public RouteFolder folderForGroup(String groupId) {
        return foldersById.get(folderIdByGroupId.get(groupId));
    }

    public String folderIdForGroup(String groupId) {
        return folderIdByGroupId.get(groupId);
    }

    public List<String> groupIdsInFolder(String folderId) {
        if (!foldersById.containsKey(folderId)) return List.of();
        List<String> out = new ArrayList<>();
        for (String groupId : byId.keySet()) {
            if (folderId.equals(folderIdByGroupId.get(groupId))) out.add(groupId);
        }
        return List.copyOf(out);
    }

    public RouteFolder createFolder(String name, String zoneId, Collection<String> groupIds) {
        return createFolder(name, zoneId, groupIds, RouteFolder.DEFAULT_COLOR);
    }

    public RouteFolder createFolder(
            String name, String zoneId, Collection<String> groupIds, int color) {
        RouteFolder folder = new RouteFolder(
                UUID.randomUUID().toString(), name, zoneId, false, color);
        addFolder(folder, groupIds);
        return folder;
    }

    public void addFolder(RouteFolder folder, Collection<String> groupIds) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(groupIds, "groupIds");
        if (foldersById.containsKey(folder.id())) {
            throw new IllegalArgumentException("Duplicate route folder ID " + folder.id());
        }
        List<String> members = List.copyOf(groupIds);
        for (String groupId : members) validateFolderMember(folder, groupId);
        foldersById.put(folder.id(), folder);
        for (String groupId : members) folderIdByGroupId.put(groupId, folder.id());
        fireDataChanged(isPersistent(folder));
    }

    public boolean renameFolder(String folderId, String name) {
        RouteFolder current = foldersById.get(folderId);
        if (current == null) return false;
        RouteFolder replacement = current.withName(name);
        if (replacement.equals(current)) return false;
        foldersById.put(folderId, replacement);
        fireDataChanged(isPersistent(current));
        return true;
    }

    public boolean setFolderCollapsed(String folderId, boolean collapsed) {
        RouteFolder current = foldersById.get(folderId);
        if (current == null || current.collapsed() == collapsed) return false;
        foldersById.put(folderId, current.withCollapsed(collapsed));
        fireDataChanged(isPersistent(current));
        return true;
    }

    public boolean setFolderColor(String folderId, int color) {
        RouteFolder current = foldersById.get(folderId);
        if (current == null) return false;
        RouteFolder replacement = current.withColor(color);
        if (replacement.equals(current)) return false;
        foldersById.put(folderId, replacement);
        fireDataChanged(isPersistent(current));
        return true;
    }

    public boolean toggleFolderCollapsed(String folderId) {
        RouteFolder current = foldersById.get(folderId);
        return current != null && setFolderCollapsed(folderId, !current.collapsed());
    }

    /** Routes remain saved when their folder is deleted. */
    public boolean deleteFolder(String folderId) {
        RouteFolder removed = foldersById.remove(folderId);
        if (removed == null) return false;
        folderIdByGroupId.values().removeIf(folderId::equals);
        fireDataChanged(isPersistent(removed));
        return true;
    }

    public boolean assignGroupToFolder(String groupId, String folderId) {
        RouteFolder folder = foldersById.get(folderId);
        if (folder == null) throw new IllegalArgumentException("Unknown route folder " + folderId);
        WaypointGroup group = byId.get(groupId);
        validateFolderMember(folder, group, groupId);
        if (folderId.equals(folderIdByGroupId.get(groupId))) return false;
        folderIdByGroupId.put(groupId, folderId);
        fireDataChanged(isPersistent(folder) || isPersistent(group));
        return true;
    }

    public boolean removeGroupFromFolder(String groupId) {
        String folderId = folderIdByGroupId.get(groupId);
        if (folderId == null) return false;
        RouteFolder folder = foldersById.get(folderId);
        WaypointGroup group = byId.get(groupId);
        folderIdByGroupId.remove(groupId);
        fireDataChanged(isPersistent(folder) || isPersistent(group));
        return true;
    }

    public boolean canMoveGroupToContainer(
            String groupId, String destinationFolderId, String beforeGroupId) {
        return dropDestination(groupId, destinationFolderId, beforeGroupId) != null;
    }

    public boolean moveGroupToContainer(
            String groupId, String destinationFolderId, String beforeGroupId) {
        DropDestination destination = dropDestination(
                groupId, destinationFolderId, beforeGroupId);
        if (destination == null) return false;

        List<WaypointGroup> original = new ArrayList<>(byId.values());
        int originalIndex = indexOfGroup(original, groupId);
        List<WaypointGroup> reordered = new ArrayList<>(original);
        reordered.remove(originalIndex);

        int insertIndex;
        if (beforeGroupId != null) {
            insertIndex = indexOfGroup(reordered, beforeGroupId);
            if (insertIndex < 0) return false;
        } else {
            insertIndex = insertionIndexAtContainerEnd(
                    reordered, destination.source(), destinationFolderId, originalIndex);
        }
        reordered.add(insertIndex, destination.source());

        boolean membershipChanged = destination.source().routeKind()
                == WaypointGroup.RouteKind.REGULAR
                && !Objects.equals(folderIdByGroupId.get(groupId), destinationFolderId);
        boolean orderChanged = !sameGroupOrder(original, reordered);
        if (!membershipChanged && !orderChanged) return false;

        byId.clear();
        for (WaypointGroup group : reordered) byId.put(group.id(), group);
        if (destination.source().routeKind() == WaypointGroup.RouteKind.REGULAR) {
            if (destinationFolderId == null) {
                folderIdByGroupId.remove(groupId);
            } else {
                folderIdByGroupId.put(groupId, destinationFolderId);
            }
        }
        fireDataChanged(true);
        return true;
    }

    private DropDestination dropDestination(
            String groupId, String destinationFolderId, String beforeGroupId) {
        WaypointGroup source = byId.get(groupId);
        if (!isReorderEligible(source)) return null;

        if (source.routeKind() == WaypointGroup.RouteKind.REGULAR) {
            if (destinationFolderId != null) {
                RouteFolder folder = foldersById.get(destinationFolderId);
                if (folder == null
                        || !folder.zoneId().equals(source.zoneId())
                        || folder.runtimeOnly() != source.runtimeOnly()) return null;
            }
        } else if (destinationFolderId != null) {
            return null;
        }

        if (beforeGroupId != null) {
            if (beforeGroupId.equals(groupId)) return null;
            WaypointGroup before = byId.get(beforeGroupId);
            if (!isReorderEligible(before)
                    || before.routeKind() != source.routeKind()
                    || !before.zoneId().equals(source.zoneId())) {
                return null;
            }
            if (source.routeKind() == WaypointGroup.RouteKind.REGULAR
                    && !Objects.equals(
                    destinationFolderId, folderIdByGroupId.get(beforeGroupId))) {
                return null;
            }
        }
        return new DropDestination(source);
    }

    private int insertionIndexAtContainerEnd(
            List<WaypointGroup> groups, WaypointGroup source,
            String destinationFolderId, int originalIndex) {
        int last = -1;
        for (int index = 0; index < groups.size(); index++) {
            WaypointGroup candidate = groups.get(index);
            if (!isReorderEligible(candidate)
                    || candidate.routeKind() != source.routeKind()
                    || !candidate.zoneId().equals(source.zoneId())) {
                continue;
            }
            if (source.routeKind() == WaypointGroup.RouteKind.REGULAR
                    && !Objects.equals(
                    destinationFolderId, folderIdByGroupId.get(candidate.id()))) {
                continue;
            }
            last = index;
        }
        return last >= 0 ? last + 1 : Math.min(originalIndex, groups.size());
    }

    private static boolean sameGroupOrder(
            List<WaypointGroup> left, List<WaypointGroup> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).id().equals(right.get(index).id())) return false;
        }
        return true;
    }

    private record DropDestination(WaypointGroup source) {
    }

    public boolean canMoveGroupBy(String groupId, int delta) {
        return adjacentReorderPeer(groupId, delta) != null;
    }

    /** Moves a saved route one slot without crossing its folder, zone, or dungeon room. */
    public boolean moveGroupBy(String groupId, int delta) {
        if (delta == 0) return false;
        String peerId = adjacentReorderPeer(groupId, delta);
        if (peerId == null) return false;
        List<WaypointGroup> ordered = new ArrayList<>(byId.values());
        int from = indexOfGroup(ordered, groupId);
        int to = indexOfGroup(ordered, peerId);
        Collections.swap(ordered, from, to);
        byId.clear();
        for (WaypointGroup group : ordered) byId.put(group.id(), group);
        fireDataChanged(true);
        return true;
    }

    private String adjacentReorderPeer(String groupId, int delta) {
        WaypointGroup group = byId.get(groupId);
        if (!isReorderEligible(group)) return null;
        String folderId = folderIdByGroupId.get(groupId);
        List<String> peers = new ArrayList<>();
        for (WaypointGroup candidate : byId.values()) {
            if (!sameReorderContainer(group, folderId, candidate)) continue;
            peers.add(candidate.id());
        }
        int from = peers.indexOf(groupId);
        int to = from + Integer.signum(delta);
        return from >= 0 && to >= 0 && to < peers.size() ? peers.get(to) : null;
    }

    private boolean sameReorderContainer(
            WaypointGroup group, String folderId, WaypointGroup candidate) {
        if (!isReorderEligible(candidate)
                || group.routeKind() != candidate.routeKind()
                || !group.zoneId().equals(candidate.zoneId())) {
            return false;
        }
        return group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                || Objects.equals(folderId, folderIdByGroupId.get(candidate.id()));
    }

    private static int indexOfGroup(List<WaypointGroup> groups, String id) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private void validateFolderMember(RouteFolder folder, String groupId) {
        WaypointGroup group = byId.get(Objects.requireNonNull(groupId, "groupId"));
        validateFolderMember(folder, group, groupId);
    }

    private static void validateFolderMember(
            RouteFolder folder, WaypointGroup group, String groupId) {
        if (!isFolderEligible(group, folder)) {
            throw new IllegalArgumentException(
                    "Route folder member is not a matching regular route " + groupId);
        }
        if (!folder.zoneId().equals(group.zoneId())) {
            throw new IllegalArgumentException("Route folder member belongs to another zone " + groupId);
        }
    }

    private static boolean isFolderEligible(WaypointGroup group, RouteFolder folder) {
        return group != null
                && !group.temp()
                && group.routeKind() == WaypointGroup.RouteKind.REGULAR
                && group.runtimeOnly() == folder.runtimeOnly();
    }

    private static boolean isReorderEligible(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly();
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

    public void replaceGroupsAtomically(
            Collection<String> removeIds, Collection<WaypointGroup> replacements) {
        replaceGroupsAtomically(removeIds, replacements, Map.of());
    }

    public void replaceGroupsAtomically(
            Collection<String> removeIds, Collection<WaypointGroup> replacements,
            Map<String, String> replacementFolderIds) {
        replaceGroupsAtomically(
                removeIds, replacements, replacementFolderIds, Map.of());
    }

    public void replaceGroupsAtomically(
            Collection<String> removeIds, Collection<WaypointGroup> replacements,
            Map<String, String> replacementFolderIds,
            Map<String, String> replacementIdByRemovedId) {
        Objects.requireNonNull(removeIds, "removeIds");
        Objects.requireNonNull(replacements, "replacements");
        Objects.requireNonNull(replacementFolderIds, "replacementFolderIds");
        Objects.requireNonNull(replacementIdByRemovedId, "replacementIdByRemovedId");

        Set<String> removals = new LinkedHashSet<>();
        for (String id : removeIds) removals.add(Objects.requireNonNull(id, "removeId"));

        List<WaypointGroup> additions = List.copyOf(replacements);
        Set<String> additionIds = new LinkedHashSet<>();
        Map<String, WaypointGroup> additionsById = new LinkedHashMap<>();
        for (WaypointGroup group : additions) {
            Objects.requireNonNull(group, "replacement");
            if (!additionIds.add(group.id())) {
                throw new IllegalArgumentException("Duplicate replacement group ID " + group.id());
            }
            additionsById.put(group.id(), group);
            if (byId.containsKey(group.id()) && !removals.contains(group.id())) {
                throw new IllegalArgumentException(
                        "Replacement group ID already exists " + group.id());
            }
        }
        Map<String, String> folderTransfers = Map.copyOf(replacementFolderIds);
        for (Map.Entry<String, String> transfer : folderTransfers.entrySet()) {
            String groupId = Objects.requireNonNull(transfer.getKey(), "replacementGroupId");
            String folderId = Objects.requireNonNull(transfer.getValue(), "replacementFolderId");
            WaypointGroup group = additionsById.get(groupId);
            if (group == null) {
                throw new IllegalArgumentException(
                        "Folder transfer does not name a replacement group " + groupId);
            }
            RouteFolder folder = foldersById.get(folderId);
            if (folder == null) {
                throw new IllegalArgumentException("Unknown route folder " + folderId);
            }
            validateFolderMember(folder, group, groupId);
        }
        Map<String, String> replacementAnchors = Map.copyOf(replacementIdByRemovedId);
        Set<String> anchoredReplacementIds = new LinkedHashSet<>();
        for (Map.Entry<String, String> anchor : replacementAnchors.entrySet()) {
            String removedId = Objects.requireNonNull(anchor.getKey(), "removedGroupId");
            String replacementId = Objects.requireNonNull(
                    anchor.getValue(), "anchoredReplacementId");
            if (!removals.contains(removedId) || !byId.containsKey(removedId)) {
                throw new IllegalArgumentException(
                        "Replacement anchor does not name a live removed group " + removedId);
            }
            if (!additionIds.contains(replacementId)) {
                throw new IllegalArgumentException(
                        "Replacement anchor does not name a replacement group " + replacementId);
            }
            if (!anchoredReplacementIds.add(replacementId)) {
                throw new IllegalArgumentException(
                        "Replacement group has more than one list anchor " + replacementId);
            }
        }

        boolean changed = !additions.isEmpty();
        boolean persistent = false;
        for (String id : removals) {
            WaypointGroup current = byId.get(id);
            if (current == null) continue;
            changed = true;
            persistent |= isPersistent(current);
        }
        for (WaypointGroup group : additions) persistent |= isPersistent(group);
        if (!changed) return;

        for (String id : removals) folderIdByGroupId.remove(id);
        if (replacementAnchors.isEmpty()) {
            for (String id : removals) byId.remove(id);
            for (WaypointGroup group : additions) byId.put(group.id(), group);
        } else {
            Map<String, WaypointGroup> nextGroups = new LinkedHashMap<>();
            for (WaypointGroup current : byId.values()) {
                if (!removals.contains(current.id())) {
                    nextGroups.put(current.id(), current);
                    continue;
                }
                String replacementId = replacementAnchors.get(current.id());
                if (replacementId != null) {
                    nextGroups.put(replacementId, additionsById.get(replacementId));
                }
            }
            for (WaypointGroup group : additions) {
                nextGroups.putIfAbsent(group.id(), group);
            }
            byId.clear();
            byId.putAll(nextGroups);
        }
        folderIdByGroupId.putAll(folderTransfers);
        fireDataChanged(persistent);
    }

    public void replaceAll(Collection<WaypointGroup> groups) {
        replaceAll(groups, List.of(), Map.of());
    }

    public void replaceAll(Collection<WaypointGroup> groups, Collection<RouteFolder> folders,
                           Map<String, String> folderMemberships) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(folders, "folders");
        Objects.requireNonNull(folderMemberships, "folderMemberships");
        boolean persistent = false;
        for (WaypointGroup group : byId.values()) persistent |= isPersistent(group);
        for (RouteFolder folder : foldersById.values()) persistent |= isPersistent(folder);
        byId.clear();
        for (WaypointGroup group : groups) {
            byId.put(group.id(), group);
            persistent |= isPersistent(group);
        }
        foldersById.clear();
        folderIdByGroupId.clear();
        for (RouteFolder folder : folders) {
            if (foldersById.putIfAbsent(folder.id(), folder) != null) {
                throw new IllegalArgumentException("Duplicate route folder ID " + folder.id());
            }
            persistent |= isPersistent(folder);
        }
        for (Map.Entry<String, String> entry : folderMemberships.entrySet()) {
            RouteFolder folder = foldersById.get(entry.getValue());
            if (folder == null) throw new IllegalArgumentException("Unknown route folder " + entry.getValue());
            validateFolderMember(folder, entry.getKey());
            folderIdByGroupId.put(entry.getKey(), entry.getValue());
        }
        fireDataChanged(persistent);
    }

    public void remove(String id) {
        WaypointGroup removed = byId.remove(id);
        folderIdByGroupId.remove(id);
        if (removed != null) fireDataChanged(isPersistent(removed));
    }

    public void removeAll(Collection<String> ids) {
        boolean changed = false;
        boolean persistent = false;
        for (String id : ids) {
            WaypointGroup removed = byId.remove(id);
            if (removed == null) continue;
            folderIdByGroupId.remove(id);
            changed = true;
            persistent |= isPersistent(removed);
        }
        if (changed) fireDataChanged(persistent);
    }

    public void clear() {
        boolean persistent = false;
        for (WaypointGroup group : byId.values()) persistent |= isPersistent(group);
        for (RouteFolder folder : foldersById.values()) persistent |= isPersistent(folder);
        byId.clear();
        foldersById.clear();
        folderIdByGroupId.clear();
        fireDataChanged(persistent);
    }

    public void fireDataChanged() {
        fireDataChanged(true);
    }

    public void fireDataChangedFor(WaypointGroup group) {
        fireDataChanged(isPersistent(group));
    }

    /** Publishes one change for a batch, persisting it when any group is saved. */
    public void fireDataChangedFor(Collection<? extends WaypointGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        boolean persistent = false;
        for (WaypointGroup group : groups) persistent |= isPersistent(group);
        fireDataChanged(persistent);
    }

    public void fireTransientDataChanged() {
        fireDataChanged(false);
    }

    private void fireDataChanged(boolean persistent) {
        reconcileFolderMemberships();
        cachedActive = null;
        for (Runnable l : List.copyOf(dataListeners)) l.run();
        if (persistent) {
            for (Runnable l : List.copyOf(persistentDataListeners)) l.run();
        }
    }

    private void reconcileFolderMemberships() {
        folderIdByGroupId.entrySet().removeIf(entry -> {
            RouteFolder folder = foldersById.get(entry.getValue());
            WaypointGroup group = byId.get(entry.getKey());
            return folder == null || !isFolderEligible(group, folder)
                    || !folder.zoneId().equals(group.zoneId());
        });
    }

    private static boolean isPersistent(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly();
    }

    private static boolean isPersistent(RouteFolder folder) {
        return folder != null && !folder.runtimeOnly();
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
