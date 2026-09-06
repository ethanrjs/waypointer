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
