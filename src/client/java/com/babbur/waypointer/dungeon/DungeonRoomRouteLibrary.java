package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/** Keeps saved dungeon routes and their temporary rendered copies in sync. */
public final class DungeonRoomRouteLibrary {

    private DungeonRoomRouteLibrary() {}

    public static WaypointGroup storedRouteForRoom(ActiveGroupManager manager, String roomId) {
        if (manager == null || roomId == null) return null;
        WaypointGroup disabled = null;
        for (WaypointGroup group : manager.groupsForZone(roomId)) {
            if (group.routeKind() != WaypointGroup.RouteKind.DUNGEON
                    || group.temp() || group.runtimeOnly() || group.isEmpty()) continue;
            if (group.enabled()) return group;
            if (disabled == null) disabled = group;
        }
        return disabled;
    }

    public static WaypointGroup storedSourceForMirror(ActiveGroupManager manager,
                                                      WaypointGroup mirror) {
        if (!DungeonRoomRouteProjection.isGeneratedGroup(mirror)) return null;
        if (mirror.runtimeSourceGroupId() != null) {
            WaypointGroup exact = manager.get(mirror.runtimeSourceGroupId());
            if (exact != null && !exact.runtimeOnly()) return exact;
        }
        return storedRouteForRoom(manager, mirror.zoneId());
    }

    public static WaypointGroup durableEditTarget(ActiveGroupManager manager,
                                                   WaypointGroup visibleGroup) {
        if (!DungeonRoomRouteProjection.isGeneratedGroup(visibleGroup)) return visibleGroup;
        return storedSourceForMirror(manager, visibleGroup);
    }

    public static void setManualCurrentIndex(ActiveGroupManager manager,
                                             WaypointGroup visibleGroup,
                                             int index) {
        applyManualProgress(manager, visibleGroup, group -> group.setCurrentIndex(index));
    }

    public static void resetManualProgress(ActiveGroupManager manager,
                                           WaypointGroup visibleGroup) {
        applyManualProgress(manager, visibleGroup, WaypointGroup::resetProgress);
    }

    private static void applyManualProgress(ActiveGroupManager manager,
                                            WaypointGroup visibleGroup,
                                            Consumer<WaypointGroup> mutation) {
        if (visibleGroup == null || mutation == null) return;
        mutation.accept(visibleGroup);
        if (manager == null) return;

        WaypointGroup stored = storedSourceForMirror(manager, visibleGroup);
        if (stored != null) mutation.accept(stored);
        WaypointGroup source = stored == null
                && !DungeonRoomRouteProjection.isGeneratedGroup(visibleGroup)
                ? visibleGroup
                : stored;
        if (source == null) return;

        WaypointGroup mirror = manager.get(
                DungeonRoomRouteProjection.generatedGroupId(source.zoneId()));
        if (mirror != null && mirror != visibleGroup) mutation.accept(mirror);
    }

    public static void setRouteEnabled(ActiveGroupManager manager,
                                       DungeonConfig config,
                                       WaypointGroup visibleGroup,
                                       boolean enabled) {
        if (visibleGroup == null) return;
        WaypointGroup stored = storedSourceForMirror(manager, visibleGroup);
        if (stored != null) stored.setEnabled(enabled);
        visibleGroup.setEnabled(enabled);

        WaypointGroup source = stored == null
                && !DungeonRoomRouteProjection.isGeneratedGroup(visibleGroup)
                ? visibleGroup
                : stored;
        if (manager != null && source != null) {
            WaypointGroup mirror = manager.get(
                    DungeonRoomRouteProjection.generatedGroupId(source.zoneId()));
            if (mirror != null) mirror.setEnabled(enabled);
        }
    }

    public static List<WaypointGroup> installRoutes(ActiveGroupManager manager,
                                                    Collection<WaypointGroup> routes) {
        if (manager == null || routes == null || routes.isEmpty()) return List.of();
        List<WaypointGroup> imported = new ArrayList<>();
        for (WaypointGroup route : routes) {
            if (route == null || route.isEmpty()) continue;
            route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            imported.add(route);
        }
        if (imported.isEmpty()) return List.of();
        DungeonRoomRouteSync.batched(() -> {
            for (WaypointGroup existing : manager.allGroups()) {
                if (!existing.temp() && !existing.runtimeOnly()
                        && existing.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
                    existing.setEnabled(false);
                }
            }
            manager.addAll(imported);
        });
        manager.fireDataChanged();
        return List.copyOf(imported);
    }

    /** Migrates the retired dungeon_rooms.json store without overwriting saved routes. */
    public static int installMissingLegacyRoutes(ActiveGroupManager manager,
                                                 Collection<WaypointGroup> routes) {
        if (manager == null || routes == null || routes.isEmpty()) return 0;
        int changed = 0;
        for (WaypointGroup legacy : routes) {
            if (legacy == null || legacy.isEmpty()) continue;
            WaypointGroup existing = null;
            for (WaypointGroup candidate : manager.groupsForZone(legacy.zoneId())) {
                if (!candidate.temp() && !candidate.runtimeOnly() && !candidate.isEmpty()) {
                    existing = candidate;
                    break;
                }
            }
            if (existing != null) {
                if (existing.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
                    existing.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
                    changed++;
                }
                continue;
            }
            legacy.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            manager.add(legacy);
            changed++;
        }
        if (changed > 0) manager.fireDataChanged();
        return changed;
    }

    /** @return number of saved dungeon route groups removed. */
    public static int deleteAllDungeonRoutes(ActiveGroupManager manager, DungeonConfig config) {
        if (manager == null) return 0;
        List<String> removeIds = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() || group.runtimeOnly()) continue;
            if (group.routeKind() == WaypointGroup.RouteKind.DUNGEON) removeIds.add(group.id());
        }
        DungeonRoomRouteSync.batched(() -> manager.removeAll(removeIds));
        manager.fireDataChanged();
        return removeIds.size();
    }
}
