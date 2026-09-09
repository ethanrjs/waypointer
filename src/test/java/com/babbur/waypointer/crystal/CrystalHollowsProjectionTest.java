package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalHollowsProjectionTest {

    @Test
    void refreshingStylesAppliesBulkChangesAndResetOnlyToRuntimeStructureGroups() {
        var manager = new ActiveGroupManager();
        var config = new WaypointerConfig();
        var style = com.babbur.waypointer.core.WaypointStyle.EMPTY.with("waypointIcon", "STAR");
        var structure = new WaypointGroup(CrystalHollowsStructureFolder.GROUP_PREFIX + "fairy_grotto:2",
                "Grotto", "crystal_hollows");
        var nucleus = new WaypointGroup(CrystalHollowsStructureFolder.GROUP_PREFIX + "nucleus",
                "Nucleus", "crystal_hollows");
        var unrelated = new WaypointGroup("other-runtime", "Other", "crystal_hollows");
        var saved = new WaypointGroup(CrystalHollowsStructureFolder.GROUP_PREFIX + "odawa",
                "Saved", "crystal_hollows");
        for (var group : List.of(structure, nucleus, unrelated, saved)) {
            group.setRuntimeOnly(group != saved);
            group.setStyleOverride(style);
            manager.add(group);
        }
        var replacement = style.with("waypointMarkerScale", 1.5);
        config.setCrystalHollowsStyle("fairy_grotto", replacement);
        config.setCrystalHollowsStyle("crystal_nucleus", replacement);
        CrystalHollowsProjection.refreshStyles(manager, config);
        assertEquals(replacement, structure.styleOverride());
        assertEquals(replacement, nucleus.styleOverride());
        config.resetToDefaults();
        CrystalHollowsProjection.refreshStyles(manager, config);
        assertTrue(structure.styleOverride().isEmpty());
        assertTrue(nucleus.styleOverride().isEmpty());
        assertEquals(style, unrelated.styleOverride());
        assertEquals(style, saved.styleOverride());
        assertEquals(structure, manager.get(structure.id()));
    }

    @Test
    void detectionPreferencesFilterCachedRemoteAndLocalPointsWithoutDeletingUserRoutes() {
        var manager = new ActiveGroupManager();
        var config = new WaypointerConfig();
        config.setCrystalHollowsNucleusWaypoints(false);
        var projection = new CrystalHollowsProjection(manager, config);
        var lobby = new CrystalHollowsLobbyState("test", 0, 0);
        var userRoute = new WaypointGroup("my-odawa-route", "Odawa", "crystal_hollows");
        userRoute.add(Waypoint.at(300, 100, 300));
        manager.add(userRoute);
        lobby.merge(new StructureSighting(CrystalHollowsStructure.ODAWA, 300, 100, 300,
                SightingConfidence.ENTITY, "test", 1));
        lobby.merge(new StructureSighting(CrystalHollowsStructure.KING_YOLKAR, 400, 100, 400,
                SightingConfidence.SHARED_REMOTE, "relay", 1));
        lobby.merge(new StructureSighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 400, 100, 300,
                SightingConfidence.COMPASS, "compass", 1));
        lobby.merge(new StructureSighting(CrystalHollowsStructure.XALX, 500, 100, 300,
                SightingConfidence.MANUAL, "manual", 1));
        for (var sighting : lobby.sightings()) {
            config.setCrystalHollowsDetectionEnabled(sighting.structure().id(), false);
        }
        projection.rebuild(lobby);
        assertEquals(java.util.Set.of(userRoute.id(), CrystalHollowsStructureFolder.GROUP_PREFIX + "jungle_temple",
                        CrystalHollowsStructureFolder.GROUP_PREFIX + "xalx"),
                manager.allGroupsList().stream().map(WaypointGroup::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, lobby.sightings().size());
        config.setCrystalHollowsDetectionEnabled("odawa", true);
        config.setCrystalHollowsDetectionEnabled("king_yolkar", true);
        projection.rebuild(lobby);
        assertEquals(5, manager.allGroupsList().size());
        assertEquals(userRoute, manager.get(userRoute.id()));
    }

    @Test
    void structureTypeAppearanceSurvivesProjectionAndLobbyChanges() {
        var manager = new ActiveGroupManager();
        var config = new WaypointerConfig();
        var style = com.babbur.waypointer.core.WaypointStyle.EMPTY.with("waypointIcon", "STAR")
                .with("waypointMarkerScale", 1.5);
        config.setCrystalHollowsStyle(CrystalHollowsStructure.ODAWA.id(), style);
        var projection = new CrystalHollowsProjection(manager, config);
        for (int i = 0; i < 2; i++) {
            var lobby = new CrystalHollowsLobbyState("lobby" + i, 0L, 0);
            lobby.merge(new StructureSighting(CrystalHollowsStructure.ODAWA,
                    349 + i, 110, 390, SightingConfidence.ENTITY, "test", 1L));
            projection.rebuild(lobby);
            var group = manager.get(CrystalHollowsStructureFolder.GROUP_PREFIX + "odawa");
            assertEquals(style, group.styleOverride());
            assertEquals("STAR", com.babbur.waypointer.config.WaypointStyles
                    .resolve(config, group, group.get(0)).waypointIcon());
            projection.endSession();
        }
    }

    @Test
    void keeperDetectionReplacesRoughDivanMarkerWithVisibleCentre() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        WaypointerConfig config = new WaypointerConfig();
        config.setCrystalHollowsShowRoughMarkers(false);
        CrystalHollowsProjection projection = new CrystalHollowsProjection(manager, config);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", 0L, 0);
        lobby.merge(new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                700, 100, 400, SightingConfidence.ROUGH_AREA, "sidebar", 1L));
        projection.rebuild(lobby);
        String id = CrystalHollowsStructureFolder.GROUP_PREFIX + "mines_of_divan";
        assertNull(manager.get(id));

        var keeper = CrystalHollowsEntityAnchor.match("[NPC] Keeper of Diamond").orElseThrow();
        lobby.merge(new StructureSighting(keeper.structure(),
                662 + keeper.offsetX(), 87 + keeper.offsetY(), 418 + keeper.offsetZ(),
                SightingConfidence.ENTITY, "entity:Keeper of Diamond", 2L));
        projection.rebuild(lobby);

        WaypointGroup divan = manager.get(id);
        assertNotNull(divan);
        assertTrue(manager.activeGroups().contains(divan));
        assertEquals(695, divan.get(0).x());
        assertEquals(87, divan.get(0).y());
        assertEquals(421, divan.get(0).z());
        assertTrue(divan.get(0).hasFlag(Waypoint.FLAG_THROUGH_WALL));
    }

    @Test
    void nucleusWaypointsRemainIndependentOfStructureWaypoints() {
        WaypointerConfig config = new WaypointerConfig();
        config.setCrystalHollowsStructureWaypoints(false);
        config.setCrystalHollowsNucleusWaypoints(true);
        ActiveGroupManager manager = new ActiveGroupManager();
        CrystalHollowsProjection projection = new CrystalHollowsProjection(manager, config);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", 0L, 0);
        lobby.merge(new StructureSighting(
                CrystalHollowsStructure.ODAWA, 349, 110, 390, SightingConfidence.ENTITY,
                "test", 1L));

        projection.rebuild(lobby);

        assertEquals(List.of("crystal_hollows:structure:nucleus"),
                manager.allGroupsList().stream().map(group -> group.id()).toList());
        assertTrue(manager.get("crystal_hollows:structure:nucleus").runtimeOnly());
    }

    @Test
    void disablingBothStructureAndNucleusWaypointsLeavesNoGeneratedGroups() {
        WaypointerConfig config = new WaypointerConfig();
        config.setCrystalHollowsStructureWaypoints(false);
        config.setCrystalHollowsNucleusWaypoints(false);
        ActiveGroupManager manager = new ActiveGroupManager();
        CrystalHollowsProjection projection = new CrystalHollowsProjection(manager, config);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", 0L, 0);
        lobby.merge(new StructureSighting(
                CrystalHollowsStructure.ODAWA, 349, 110, 390, SightingConfidence.ENTITY,
                "test", 1L));

        projection.rebuild(lobby);

        assertTrue(manager.allGroupsList().isEmpty());
        assertEquals(List.of(), manager.groupIdsInFolder(CrystalHollowsStructureFolder.FOLDER_ID));
    }

    @Test
    void hidingStructuresRemovesOnlyRuntimeProjectionAndRestoresRouteVisibility() {
        WaypointerConfig config = new WaypointerConfig();
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup saved = new WaypointGroup("saved", "Saved", "crystal_hollows");
        manager.add(saved);
        manager.addFolder(new RouteFolder("saved-folder", "Saved", "crystal_hollows", false),
                List.of(saved.id()));
        CrystalHollowsProjection projection = new CrystalHollowsProjection(manager, config);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", 0L, 0);
        lobby.merge(new StructureSighting(
                CrystalHollowsStructure.ODAWA, 349, 110, 390, SightingConfidence.ENTITY,
                "test", 1L));

        projection.rebuild(lobby);
        WaypointGroup generated = manager.get("crystal_hollows:structure:odawa");
        assertNotNull(generated);
        generated.setEnabled(false);
        manager.fireTransientDataChanged();

        config.setCrystalHollowsHideStructuresFolder(true);
        projection.rebuild(lobby);

        assertNull(manager.get("crystal_hollows:structure:odawa"));
        assertNull(manager.folder(CrystalHollowsStructureFolder.FOLDER_ID));
        assertNotNull(manager.get(saved.id()));
        assertNotNull(manager.folder("saved-folder"));

        config.setCrystalHollowsHideStructuresFolder(false);
        projection.rebuild(lobby);

        assertNotNull(manager.folder(CrystalHollowsStructureFolder.FOLDER_ID));
        assertNotNull(manager.get("crystal_hollows:structure:odawa"));
        assertTrue(manager.get("crystal_hollows:structure:odawa").enabled());
    }

    @Test
    void leavingAndRejoiningRestoresFolderAndHiddenMarkers() {
        WaypointerConfig config = new WaypointerConfig();
        ActiveGroupManager manager = new ActiveGroupManager();
        CrystalHollowsProjection projection = new CrystalHollowsProjection(manager, config);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", 0L, 0);
        lobby.merge(new StructureSighting(CrystalHollowsStructure.ODAWA,
                349, 110, 390, SightingConfidence.ENTITY, "test", 1L));
        projection.rebuild(lobby);
        manager.get("crystal_hollows:structure:odawa").setEnabled(false);

        projection.endSession();
        assertNull(manager.folder(CrystalHollowsStructureFolder.FOLDER_ID));
        projection.rebuild(lobby);
        assertTrue(manager.get("crystal_hollows:structure:odawa").enabled());

        config.setCrystalHollowsHideStructuresFolder(true);
        projection.rebuild(lobby);
        assertTrue(config.crystalHollowsHideStructuresFolder());
        projection.endSession();
        assertFalse(config.crystalHollowsHideStructuresFolder());
        projection.rebuild(lobby);
        assertNotNull(manager.folder(CrystalHollowsStructureFolder.FOLDER_ID));
        assertTrue(manager.get("crystal_hollows:structure:odawa").enabled());
    }
}
