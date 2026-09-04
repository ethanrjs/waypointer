package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalHollowsProjectionTest {

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
}
