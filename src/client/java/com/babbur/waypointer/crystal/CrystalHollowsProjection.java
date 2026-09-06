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
import net.minecraft.network.chat.MutableComponent;

/** Builds runtime-only groups for the Structures folder. */
public final class CrystalHollowsProjection {

    private static final int WAYPOINT_FLAGS =
            Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final Map<String, Boolean> enabledByGroupId = new HashMap<>();
    private final List<StructureSighting> arrivals = new ArrayList<>();
    private CrystalHollowsLobbyState arrivalLobby;

    public CrystalHollowsProjection(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public boolean ensureFolder() {
        if (config.crystalHollowsHideStructuresFolder()) return false;
        if (manager.folder(CrystalHollowsStructureFolder.FOLDER_ID) != null) return false;
        manager.addFolder(new RouteFolder(
                CrystalHollowsStructureFolder.FOLDER_ID,
                Component.translatable("waypointer.crystal.folder.structures").getString(),
                CrystalHollowsStructureFolder.ZONE_ID,
                false,
                CrystalHollowsStructureFolder.FOLDER_COLOR,
                true), List.of());
        return true;
    }

    public void rebuild(CrystalHollowsLobbyState lobby) {
        arrivalSession(lobby);
        if (config.crystalHollowsHideStructuresFolder()) {
            clear();
            enabledByGroupId.clear();
            return;
        }
        rememberVisibility();
        ensureFolder();
        boolean structureWaypoints = config.crystalHollowsStructureWaypoints();
        List<CrystalHollowsStructureFolder.PlannedGroup> plans =
                CrystalHollowsStructureFolder.plan(
                        structureWaypoints && lobby != null ? lobby.sightings() : List.of(),
                        structureWaypoints && config.crystalHollowsShowRoughMarkers(),
                        config.crystalHollowsNucleusWaypoints());
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
        deleteRuntimeFolder();
    }

    public void endSession() {
        clear();
        arrivalSession(null);
        enabledByGroupId.clear();
        if (config.crystalHollowsHideStructuresFolder()) {
            config.setCrystalHollowsHideStructuresFolder(false);
        }
    }

    public boolean ownsGroup(String groupId) {
        return groupId != null && groupId.startsWith(CrystalHollowsStructureFolder.GROUP_PREFIX);
    }

    public static String structureReferenceForGroup(String groupId) {
        if (groupId == null || !groupId.startsWith(CrystalHollowsStructureFolder.GROUP_PREFIX)) {
            return null;
        }
        String suffix = groupId.substring(CrystalHollowsStructureFolder.GROUP_PREFIX.length());
        return "nucleus".equals(suffix) ? CrystalHollowsStructure.CRYSTAL_NUCLEUS.id() : suffix;
    }

    private WaypointGroup toGroup(CrystalHollowsStructureFolder.PlannedGroup plan) {
        String localizedName = localizedGroupName(plan).getString();
        WaypointGroup group = new WaypointGroup(
                plan.id(), localizedName, CrystalHollowsStructureFolder.ZONE_ID);
        group.setRuntimeOnly(true);
        group.setRouteKind(WaypointGroup.RouteKind.REGULAR);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setEnabled(enabledByGroupId.getOrDefault(group.id(), true));
        List<Waypoint> waypoints = new ArrayList<>(plan.waypoints().size());
        for (int index = 0; index < plan.waypoints().size(); index++) {
            CrystalHollowsStructureFolder.PlannedWaypoint planned = plan.waypoints().get(index);
            CrystalHollowsPosition position = planned.position();
            Waypoint waypoint = Waypoint.at(position.x(), position.y(), position.z());
            if (plan.nucleus() && index == 0) {
                waypoint = waypoint.withPreciseSixteenths(
                        Waypoint.snapToPreciseSixteenths(CrystalHollowsGeometry.NUCLEUS_CENTRE_X),
                        Waypoint.snapToPreciseSixteenths(CrystalHollowsGeometry.NUCLEUS_CENTRE_Y),
                        Waypoint.snapToPreciseSixteenths(CrystalHollowsGeometry.NUCLEUS_CENTRE_Z));
            }
            waypoint = waypoint
                    .withName(localizedWaypointName(plan, index, localizedName))
                    .withColor(planned.color())
                    .withFlags(WAYPOINT_FLAGS);
            waypoints.add(waypoint);
        }
        group.addAll(waypoints);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < group.size(); index++) {
            CrystalHollowsPosition position = plan.waypoints().get(index).position();
            for (StructureSighting arrival : arrivals) {
                if (arrival.structure().id().equals(plan.structureId())
                        && (!arrival.structure().multiInstance()
                            || arrival.position().distanceSquared(position) <= 60.0 * 60.0)) {
                    CompassMarkerState.markArrived(group.get(index));
                    break;
                }
            }
        }
        return group;
    }

    boolean markArrived(CrystalHollowsLobbyState lobby, StructureSighting sighting) {
        arrivalSession(lobby);
        for (StructureSighting arrival : arrivals) {
            if (arrival.structure() == sighting.structure()
                    && (!arrival.structure().multiInstance()
                        || arrival.position().distanceSquared(sighting.position()) <= 60.0 * 60.0)) return false;
        }
        arrivals.add(sighting);
        return true;
    }

    private void arrivalSession(CrystalHollowsLobbyState lobby) {
        if (arrivalLobby == lobby) return;
        arrivalLobby = lobby;
        arrivals.clear();
    }

    private static Component localizedGroupName(
            CrystalHollowsStructureFolder.PlannedGroup plan) {
        Component base;
        if (CrystalHollowsStructure.WISHING_TARGET.id().equals(plan.structureId())) {
            MutableComponent candidates = Component.empty();
            if (plan.candidates().isEmpty()) {
                candidates.append(Component.translatable("waypointer.crystal.compass.unknown"));
            } else {
                boolean first = true;
                for (CrystalHollowsStructure candidate : plan.candidates()) {
                    if (!first) candidates.append(Component.literal(" / "));
                    candidates.append(structureName(candidate));
                    first = false;
                }
            }
            base = Component.translatable("waypointer.crystal.label.compass_target", candidates);
        } else {
            CrystalHollowsStructure structure = structureById(plan.structureId());
            base = structure == null ? Component.literal(plan.name()) : structureName(structure);
            if (plan.approximate()) {
                base = Component.translatable("waypointer.crystal.label.approximate", base);
            }
        }
        return plan.instance() > 1
                ? Component.translatable("waypointer.crystal.label.instance", base, plan.instance())
                : base;
    }

    private static String localizedWaypointName(
            CrystalHollowsStructureFolder.PlannedGroup plan, int index, String groupName) {
        if (!plan.nucleus() || index == 0) return groupName;
        String entranceId = CrystalHollowsGeometry.NUCLEUS_ENTRANCES.get(index - 1).id();
        return Component.translatable(
                "waypointer.crystal.nucleus_entrance." + entranceId).getString();
    }

    private static CrystalHollowsStructure structureById(String id) {
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            if (structure.id().equals(id)) return structure;
        }
        return null;
    }

    private static Component structureName(CrystalHollowsStructure structure) {
        return Component.translatable("waypointer.crystal.structure." + structure.id());
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
            if (group.runtimeOnly() && ownsGroup(group.id())) ids.add(group.id());
        }
        return List.copyOf(ids);
    }

    private void deleteRuntimeFolder() {
        RouteFolder folder = manager.folder(CrystalHollowsStructureFolder.FOLDER_ID);
        if (folder != null && folder.runtimeOnly()) {
            manager.deleteFolder(folder.id());
        }
    }
}
