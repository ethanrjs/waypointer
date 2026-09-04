package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/** Applies pure structure-folder plans to runtime-only Waypointer groups. */
public final class CrystalHollowsProjection {

    private static final int WAYPOINT_FLAGS =
            Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final Map<String, Boolean> enabledByGroupId = new HashMap<>();

    public CrystalHollowsProjection(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void ensureFolder() {
        if (manager.folder(CrystalHollowsStructureFolder.FOLDER_ID) != null) return;
        manager.addFolder(new RouteFolder(
                CrystalHollowsStructureFolder.FOLDER_ID,
                Component.translatable("waypointer.crystal.folder.structures").getString(),
                CrystalHollowsStructureFolder.ZONE_ID,
                false,
                CrystalHollowsStructureFolder.FOLDER_COLOR,
                true), List.of());
    }

    public void rebuild(CrystalHollowsLobbyState lobby) {
        rememberVisibility();
        ensureFolder();
        List<CrystalHollowsStructureFolder.PlannedGroup> plans =
                config.crystalHollowsStructureWaypoints()
                        ? CrystalHollowsStructureFolder.plan(lobby == null ? List.of() : lobby.sightings(),
                                config.crystalHollowsShowRoughMarkers(),
                                config.crystalHollowsNucleusWaypoints())
                        : List.of();
        List<String> removals = generatedGroupIds();
        List<WaypointGroup> replacements = new ArrayList<>(plans.size());
        Map<String, String> folders = new LinkedHashMap<>();
        for (CrystalHollowsStructureFolder.PlannedGroup plan : plans) {
            WaypointGroup group = toGroup(plan);
            replacements.add(group);
            folders.put(group.id(), CrystalHollowsStructureFolder.FOLDER_ID);
        }
        manager.replaceGroupsAtomically(removals, replacements, folders);
    }

    public void clear() {
        rememberVisibility();
        List<String> generated = generatedGroupIds();
        if (!generated.isEmpty()) manager.replaceGroupsAtomically(generated, List.of());
        manager.deleteFolder(CrystalHollowsStructureFolder.FOLDER_ID);
    }

    public boolean ownsGroup(String groupId) {
        return groupId != null && groupId.startsWith(CrystalHollowsStructureFolder.GROUP_PREFIX);
    }

    public static String structureIdForGroup(String groupId) {
        if (groupId == null || !groupId.startsWith(CrystalHollowsStructureFolder.GROUP_PREFIX)) {
            return null;
        }
        String suffix = groupId.substring(CrystalHollowsStructureFolder.GROUP_PREFIX.length());
        int instanceSeparator = suffix.indexOf(':');
        return instanceSeparator < 0 ? suffix : suffix.substring(0, instanceSeparator);
    }

    private WaypointGroup toGroup(CrystalHollowsStructureFolder.PlannedGroup plan) {
        WaypointGroup group = new WaypointGroup(
                plan.id(), plan.name(), CrystalHollowsStructureFolder.ZONE_ID);
        group.setRuntimeOnly(true);
        group.setRouteKind(WaypointGroup.RouteKind.REGULAR);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setEnabled(enabledByGroupId.getOrDefault(group.id(), true));
        List<Waypoint> waypoints = new ArrayList<>(plan.waypoints().size());
        for (CrystalHollowsStructureFolder.PlannedWaypoint planned : plan.waypoints()) {
            CrystalHollowsPosition position = planned.position();
            waypoints.add(Waypoint.at(position.x(), position.y(), position.z())
                    .withName(planned.name())
                    .withColor(planned.color())
                    .withFlags(WAYPOINT_FLAGS));
        }
        group.addAll(waypoints);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        return group;
    }

    private void rememberVisibility() {
        for (String groupId : generatedGroupIds()) {
            WaypointGroup group = manager.get(groupId);
            if (group != null) enabledByGroupId.put(groupId, group.enabled());
        }
    }

    private List<String> generatedGroupIds() {
        List<String> ids = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (ownsGroup(group.id())) ids.add(group.id());
        }
        return List.copyOf(ids);
    }
}
