package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RouteFolderListModel {

    record FolderSection(RouteFolder folder, List<WaypointGroup> groups,
                         boolean searchReveal) {}

    record Snapshot(List<FolderSection> folders, List<WaypointGroup> unfiled) {}

    private RouteFolderListModel() {}

    static Snapshot build(ActiveGroupManager manager, String zoneId, String searchQuery) {
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        List<WaypointGroup> zoneGroups = new ArrayList<>();
        for (WaypointGroup group : manager.groupsForZone(zoneId)) {
            RouteFolder memberFolder = manager.folderForGroup(group.id());
            boolean listedRuntime = group.runtimeOnly()
                    && memberFolder != null
                    && memberFolder.runtimeOnly();
            if (!group.temp() && (!group.runtimeOnly() || listedRuntime)
                    && group.routeKind() == WaypointGroup.RouteKind.REGULAR) {
                zoneGroups.add(group);
            }
        }

        Set<String> filed = new HashSet<>();
        List<FolderSection> folders = new ArrayList<>();
        for (RouteFolder folder : manager.foldersForZone(zoneId)) {
            boolean folderMatches = query.isEmpty()
                    || folder.name().toLowerCase(Locale.ROOT).contains(query);
            List<WaypointGroup> members = new ArrayList<>();
            for (WaypointGroup group : zoneGroups) {
                if (!folder.id().equals(manager.folderIdForGroup(group.id()))) continue;
                filed.add(group.id());
                if (query.isEmpty() || folderMatches || RouteListPresentation.groupMatchesSearch(
                        group, query, WaypointerZoneCatalog.displayZoneLabel(group.zoneId()))) {
                    members.add(group);
                }
            }
            if (query.isEmpty() || folderMatches || !members.isEmpty()) {
                folders.add(new FolderSection(folder, List.copyOf(members), !query.isEmpty()));
            }
        }

        List<WaypointGroup> unfiled = new ArrayList<>();
        for (WaypointGroup group : zoneGroups) {
            if (filed.contains(group.id())) continue;
            if (query.isEmpty() || RouteListPresentation.groupMatchesSearch(
                    group, query, WaypointerZoneCatalog.displayZoneLabel(group.zoneId()))) {
                unfiled.add(group);
            }
        }
        return new Snapshot(List.copyOf(folders), List.copyOf(unfiled));
    }
}
