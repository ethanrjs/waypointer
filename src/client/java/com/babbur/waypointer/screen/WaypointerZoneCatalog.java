package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Zone names, ordering, and route-target rules used by the main screen. */
final class WaypointerZoneCatalog {

    static final String TEMPORARY_ZONE_ID = "__temporary__";
    static final String DUNGEON_ROOMS_ZONE_ID = "__dungeon_rooms__";

    private static final String TEMPORARY_ZONE_LABEL = "Temporary";
    private static final String DUNGEON_ROOMS_LABEL = "Dungeon Rooms";
    private static final String DUNGEON_ROOM_LABEL_PREFIX = "Dungeons: ";

    private WaypointerZoneCatalog() {}

    static String initialSelectedZoneId(ActiveGroupManager manager) {
        List<String> ids = zoneIdsForManager(manager);
        String fallback = selectorEntryForZoneId(currentZoneId(manager));
        if (ids.contains(fallback)) return fallback;
        return ids.isEmpty() ? Zone.UNKNOWN.id() : ids.get(0);
    }

    static String currentZoneId(ActiveGroupManager manager) {
        Zone current = manager.currentZone();
        return current == null ? Zone.UNKNOWN.id() : current.id();
    }

    static List<String> islandDropdownIdsForManager(ActiveGroupManager manager) {
        return islandDropdownIdsForManager(manager, false);
    }

    static List<String> islandDropdownIdsForManager(ActiveGroupManager manager,
                                                     boolean includeEmpty) {
        List<String> all = zoneIdsForManager(manager);
        String currentSelection = selectorEntryForZoneId(currentZoneId(manager));
        List<String> out = new ArrayList<>();
        List<String> populated = new ArrayList<>();
        List<String> empty = new ArrayList<>();

        if (zoneHasRoutes(manager, TEMPORARY_ZONE_ID)) out.add(TEMPORARY_ZONE_ID);
        if (all.contains(currentSelection) && !isDungeonRoomsZone(currentSelection)
                && !out.contains(currentSelection)) {
            out.add(currentSelection);
        }
        for (String id : all) {
            if (out.contains(id) || isTemporaryZone(id) || isDungeonRoomsZone(id)) continue;
            if (zoneHasRoutes(manager, id)) populated.add(id);
            else empty.add(id);
        }
        Comparator<String> byLabel = Comparator
                .comparing((String id) -> displayZoneLabel(id).toLowerCase(Locale.ROOT))
                .thenComparing(id -> id);
        populated.sort(byLabel);
        empty.sort(byLabel);
        out.addAll(populated);
        if (includeEmpty) out.addAll(empty);
        return out;
    }

    static List<String> zoneIdsForManager(ActiveGroupManager manager) {
        List<String> zones = new ArrayList<>();
        zones.add(TEMPORARY_ZONE_ID);
        zones.add(Zone.UNKNOWN.id());
        List<String> dungeonRooms = new ArrayList<>();
        for (String zoneId : manager.knownZoneIds()) {
            if (normalGroupCountForZone(manager, zoneId) > 0 && !zones.contains(zoneId)) {
                zones.add(zoneId);
            }
        }
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && !group.runtimeOnly()
                    && group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    && !dungeonRooms.contains(group.zoneId())) {
                dungeonRooms.add(group.zoneId());
            }
        }
        Zone currentZone = manager.currentZone();
        if (currentZone != null) {
            String currentId = currentZone.id();
            if (isDungeonRoomZone(currentId)) {
                if (!dungeonRooms.contains(currentId)) dungeonRooms.add(0, currentId);
            } else {
                zones.remove(currentId);
                zones.add(1, currentId);
            }
        }
        for (Zone zone : Zone.knownZones()) {
            if (!zones.contains(zone.id())) zones.add(zone.id());
        }
        if (!dungeonRooms.isEmpty()) zones.add(DUNGEON_ROOMS_ZONE_ID);
        return zones;
    }

    static List<String> orderedDungeonRoomIds(Collection<String> roomIds,
                                              Set<String> populatedRoomIds) {
        return orderedDungeonRoomIds(roomIds, populatedRoomIds, null);
    }

    static List<String> orderedDungeonRoomIds(Collection<String> roomIds,
                                              Set<String> populatedRoomIds,
                                              String currentRoomZoneId) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(roomIds));
        Set<String> populated = populatedRoomIds == null ? Set.of() : populatedRoomIds;
        ordered.sort(Comparator
                .comparing((String id) -> !populated.contains(id))
                .thenComparing(id -> displayZoneLabel(id).toLowerCase(Locale.ROOT))
                .thenComparing(id -> id));
        if (currentRoomZoneId != null && ordered.remove(currentRoomZoneId)) {
            ordered.add(0, currentRoomZoneId);
        }
        return ordered;
    }

    static int normalGroupCountForZone(ActiveGroupManager manager, String zoneId) {
        int count = 0;
        for (WaypointGroup group : manager.groupsForZone(zoneId)) {
            if (!group.temp() && group.routeKind() == WaypointGroup.RouteKind.REGULAR) count++;
        }
        return count;
    }

    static int dungeonRoomGroupCount(ActiveGroupManager manager) {
        int count = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && group.routeKind() == WaypointGroup.RouteKind.DUNGEON) count++;
        }
        return count;
    }

    static boolean isTemporaryZone(String zoneId) {
        return TEMPORARY_ZONE_ID.equals(zoneId);
    }

    static boolean isDungeonRoomsZone(String zoneId) {
        return DUNGEON_ROOMS_ZONE_ID.equals(zoneId);
    }

    static boolean isDungeonRoomZone(String zoneId) {
        return DungeonRoomData.entry(zoneId) != null;
    }

    static String currentDungeonRoomZoneId(ActiveGroupManager manager) {
        if (manager == null || manager.currentZone() == null) return null;
        String zoneId = manager.currentZone().id();
        return isDungeonRoomZone(zoneId) ? zoneId : null;
    }

    static String selectorEntryForZoneId(String zoneId) {
        return zoneId != null && isDungeonRoomZone(zoneId) ? DUNGEON_ROOMS_ZONE_ID : zoneId;
    }

    static String displayZoneLabel(String zoneId) {
        if (isTemporaryZone(zoneId)) return TEMPORARY_ZONE_LABEL;
        if (isDungeonRoomsZone(zoneId)) return DUNGEON_ROOMS_LABEL;
        DungeonRoomCatalogEntry catalogEntry = DungeonRoomData.entry(zoneId);
        if (catalogEntry != null) return DUNGEON_ROOM_LABEL_PREFIX + catalogEntry.displayName();
        return Zone.fromId(zoneId).displayName();
    }

    static boolean canMoveRouteZone(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly()
                && group.routeKind() == WaypointGroup.RouteKind.REGULAR;
    }

    static boolean canRetargetRoute(WaypointGroup group, String zoneId) {
        String canonicalTarget = Zone.canonicalId(zoneId);
        if (!canMoveRouteZone(group) || zoneId == null
                || isTemporaryZone(zoneId) || isDungeonRoomsZone(zoneId)
                || isDungeonRoomZone(zoneId)
                || isCatacombsOrMasterModeZone(canonicalTarget)
                || Zone.UNKNOWN.id().equals(canonicalTarget)
                || Zone.PRIVATE_WORLD.id().equals(canonicalTarget)) {
            return false;
        }
        return !canonicalTarget.equals(group.zoneId());
    }

    static boolean isCatacombsOrMasterModeZone(String zoneId) {
        String id = Zone.canonicalId(zoneId);
        return id.equals("dungeon")
                || id.startsWith("dungeon_f")
                || id.startsWith("dungeon_m");
    }

    static boolean retargetRoute(WaypointGroup group, String zoneId) {
        if (!canRetargetRoute(group, zoneId)) return false;
        group.setZoneId(Zone.canonicalId(zoneId));
        return true;
    }

    static String newRouteTargetZoneId(String selectedZoneId, String currentZoneId) {
        return newRouteTargetZoneId(selectedZoneId, null, currentZoneId);
    }

    static String newRouteTargetZoneId(String selectedZoneId,
                                       String selectedDungeonRoomZoneId,
                                       String currentZoneId) {
        if (isTemporaryZone(selectedZoneId)) {
            return currentZoneId == null ? Zone.UNKNOWN.id() : currentZoneId;
        }
        if (isDungeonRoomsZone(selectedZoneId)) {
            if (selectedDungeonRoomZoneId != null && !selectedDungeonRoomZoneId.isBlank()) {
                return selectedDungeonRoomZoneId;
            }
            return isDungeonRoomZone(currentZoneId) ? currentZoneId : null;
        }
        return selectedZoneId == null ? Zone.UNKNOWN.id() : selectedZoneId;
    }

    static String newRouteBlockedNotice(String selectedZoneId) {
        return isDungeonRoomsZone(selectedZoneId)
                ? "Stand in a detected dungeon room to create a room route."
                : "Choose a route zone first.";
    }

    static String importTargetZoneId(String selectedZoneId, String currentZoneId) {
        if (isTemporaryZone(selectedZoneId)) {
            return currentZoneId == null ? Zone.UNKNOWN.id() : currentZoneId;
        }
        if (isDungeonRoomsZone(selectedZoneId)) {
            return isDungeonRoomZone(currentZoneId) ? currentZoneId : Zone.UNKNOWN.id();
        }
        return selectedZoneId == null ? Zone.UNKNOWN.id() : selectedZoneId;
    }

    static void retargetUnknownImportedGroups(List<WaypointGroup> groups, String targetZoneId) {
        if (groups == null) return;
        for (WaypointGroup group : groups) {
            if (group != null
                    && Zone.shouldRetargetImportedZone(group.zoneId(), targetZoneId)) {
                group.setZoneId(targetZoneId);
            }
        }
    }

    private static boolean zoneHasRoutes(ActiveGroupManager manager, String zoneId) {
        if (isTemporaryZone(zoneId)) {
            for (WaypointGroup group : manager.allGroups()) {
                if (group.temp() && !group.isEmpty()) return true;
            }
            return false;
        }
        if (isDungeonRoomsZone(zoneId)) {
            return dungeonRoomGroupCount(manager) > 0;
        }
        return normalGroupCountForZone(manager, zoneId) > 0;
    }
}
