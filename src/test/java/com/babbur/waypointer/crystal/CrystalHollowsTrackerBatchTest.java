package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrystalHollowsTrackerBatchTest {
    @TempDir Path directory;

    @Test
    void removedCompassWaypointStopsMinimapNavigationEvenWhenTheGroupRemains() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new com.babbur.waypointer.core.Zone("crystal_hollows", "Crystal Hollows"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        StructureSighting target = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                606, 88, 265, SightingConfidence.COMPASS, "compass", 1L);
        tracker.focusCompassTarget(target);
        var group = manager.getOrCreateTempGroup();
        assertNotNull(tracker.compassNavigationTarget());
        group.remove(0);
        assertNotNull(manager.get(group.id()));
        assertNull(tracker.compassNavigationTarget());

        tracker.focusCompassTarget(target);
        assertNotNull(tracker.compassNavigationTarget());
        manager.clearTemporaryWaypoints();
        assertNull(tracker.compassNavigationTarget());
    }

    @Test
    void compassAutoFocusCanBeDisabledWithoutArrivalOrStealingManualFocus() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new com.babbur.waypointer.core.Zone("crystal_hollows", "Crystal Hollows"));
        WaypointerConfig config = new WaypointerConfig();
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, config, null);
        StructureSighting target = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                606, 88, 265, SightingConfidence.COMPASS, "compass", 1L);
        tracker.focusCompassTarget(target);
        var group = manager.getOrCreateTempGroup();
        var marker = group.get(0);
        assertTrue(manager.tempWaypointFocusActive());
        assertTrue(CompassMarkerState.isTarget(marker));
        config.setCrystalHollowsCompassAutoFocus(false);
        tracker.refreshCompassFocusSetting();
        assertFalse(manager.tempWaypointFocusActive());
        assertFalse(CompassMarkerState.arrived(marker));
        assertEquals(target, tracker.compassTargetSighting());
        tracker.focusCompassTarget(target);
        assertFalse(manager.tempWaypointFocusActive());
        assertFalse(CompassMarkerState.arrived(group.get(1)));
        manager.focusTempWaypoint(group, 1);
        tracker.refreshCompassFocusSetting();
        assertTrue(manager.tempWaypointFocusActive());
        config.setCrystalHollowsCompassAutoFocus(true);
        tracker.refreshCompassFocusSetting();
        tracker.focusCompassTarget(target);
        manager.focusTempWaypoint(group, 0);
        config.setCrystalHollowsCompassAutoFocus(false);
        tracker.refreshCompassFocusSetting();
        assertTrue(manager.tempWaypointFocusActive());
        assertEquals(0, group.focusedVisibleIndex());
    }

    @Test
    void expiredRemoteNavigationWithoutLocalEvidenceIsRemoved() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new com.babbur.waypointer.core.Zone("crystal_hollows", "Crystal Hollows"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", 0, 0);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        StructureSighting remote = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                606, 88, 265, SightingConfidence.SHARED_REMOTE, "relay", 1L,
                java.util.List.of(), "", SightingConfidence.ENTITY);
        lobby.merge(remote);
        tracker.focusCompassTarget(remote);
        lobby.clearRemoteSightings();
        tracker.rebuildProjection();
        assertNull(tracker.compassTargetSighting());
        assertEquals(0, manager.getOrCreateTempGroup().size());
        assertFalse(manager.tempWaypointFocusActive());
    }

    @Test
    void sharedEntityRefinesCompassInEitherArrivalOrderWithoutEndingNavigation() throws Exception {
        for (boolean sharedFirst : new boolean[] {true, false}) {
            ActiveGroupManager manager = new ActiveGroupManager();
            manager.onZoneChanged(new com.babbur.waypointer.core.Zone("crystal_hollows", "Crystal Hollows"));
            CrystalHollowsStore store = new CrystalHollowsStore(directory.resolve("shared-" + sharedFirst + ".json"));
            CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), store);
            set(tracker, "active", true);
            set(tracker, "lobby", new CrystalHollowsLobbyState("test", System.currentTimeMillis(), 0));
            StructureSighting compass = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                    606, 100, 265, SightingConfidence.COMPASS, "compass", 1L);
            StructureSighting shared = new StructureSighting(compass.structure(), 610, 88, 269,
                    SightingConfidence.SHARED_REMOTE, "relay", 2L, java.util.List.of(), "",
                    SightingConfidence.ENTITY);
            try {
                if (sharedFirst) tracker.merge(shared, false);
                tracker.merge(compass, false);
                tracker.focusCompassTarget(compass);
                if (!sharedFirst) tracker.merge(shared, false);
                assertEquals(shared, tracker.compassTargetSighting());
                assertEquals(shared, tracker.compassShare(tracker.compassShareReference()));
                var marker = manager.getOrCreateTempGroup().get(0);
                assertEquals(610, marker.x());
                assertEquals(88, marker.y());
                assertTrue(manager.tempWaypointFocusActive());
                assertFalse(CompassMarkerState.arrived(marker));
                if (sharedFirst) tracker.lobby().clearRemoteSightings();
                else tracker.lobby().expireRemoteSightings(3L);
                tracker.rebuildProjection();
                assertEquals(compass, tracker.compassTargetSighting());
                assertEquals(compass, tracker.compassShare(tracker.compassShareReference()));
                assertEquals(606, manager.getOrCreateTempGroup().get(0).x());
                assertTrue(manager.tempWaypointFocusActive());
                tracker.merge(shared, false);
                tracker.merge(new StructureSighting(compass.structure(), 615, 90, 270,
                        SightingConfidence.ENTITY, "entity:Keeper of Diamond", 3L), false);
                assertEquals(615, tracker.compassTargetSighting().x());
                assertFalse(manager.tempWaypointFocusActive());
            } finally {
                store.discardPendingSave();
            }
        }
    }

    @Test
    void localDetectionEndsNavigationAndSurvivesProjectionRebuild() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        CrystalHollowsStore store = new CrystalHollowsStore(directory.resolve("arrival.json"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), store);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", System.currentTimeMillis(), 0);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        StructureSighting target = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                606, 88, 265, SightingConfidence.COMPASS, "compass", 1L);
        tracker.focusCompassTarget(target);
        String groupId = CrystalHollowsStructureFolder.GROUP_PREFIX + "mines_of_divan";
        try {
            tracker.merge(new StructureSighting(target.structure(), 606, 88, 265,
                    SightingConfidence.SHARED_CHAT, "chat:player", 2L), false);
            assertFalse(CompassMarkerState.arrived(manager.getOrCreateTempGroup().get(0)));
            assertNotNull(tracker.compassNavigationTarget());
            assertFalse(CompassMarkerState.arrived(manager.get(groupId).get(0)));
            tracker.merge(new StructureSighting(target.structure(), 606, 88, 265,
                    SightingConfidence.ENTITY, "entity:Keeper of Diamond", 3L), false);
            assertTrue(CompassMarkerState.arrived(manager.getOrCreateTempGroup().get(0)));
            assertNull(tracker.compassNavigationTarget());
            assertFalse(manager.tempWaypointFocusActive());
            assertTrue(CompassMarkerState.arrived(manager.get(groupId).get(0)));
            tracker.rebuildProjection();
            assertEquals(1, manager.get(groupId).size());
            assertTrue(CompassMarkerState.arrived(manager.get(groupId).get(0)));
            set(tracker, "lobby", new CrystalHollowsLobbyState("other", 0, 0));
            tracker.merge(new StructureSighting(target.structure(), 606, 88, 265,
                    SightingConfidence.SHARED_REMOTE, "relay", 4L), false);
            assertFalse(CompassMarkerState.arrived(manager.get(groupId).get(0)));
        } finally {
            store.discardPendingSave();
        }
    }

    @Test
    void batchesProjectionAndSnapshotButOutsideMergesStayImmediate() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        CrystalHollowsStore store = new CrystalHollowsStore(directory.resolve("structures.json"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), store);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("test", System.currentTimeMillis(), 0);
        set(tracker, "active", true);
        set(tracker, "serverId", "test");
        set(tracker, "lobby", lobby);
        tracker.rebuildProjection();
        AtomicInteger projections = new AtomicInteger();
        manager.addDataListener(projections::incrementAndGet);
        try {
            tracker.batchDetections(() -> {
                tracker.merge(sighting(CrystalHollowsStructure.MINES_OF_DIVAN), false);
                tracker.merge(sighting(CrystalHollowsStructure.ODAWA), false);
                assertEquals(0, projections.get());
                assertNull(get(store, "pendingSnapshot"));
            });
            assertEquals(2, lobby.sightings().size());
            assertEquals(1, projections.get());
            assertNotNull(get(store, "pendingSnapshot"));
            tracker.merge(sighting(CrystalHollowsStructure.KING_YOLKAR), false);
            assertEquals(3, lobby.sightings().size());
            assertEquals(2, projections.get());
        } finally {
            store.discardPendingSave();
        }
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure) {
        return new StructureSighting(structure, 300, 100, 300, SightingConfidence.ENTITY,
                "test", System.currentTimeMillis());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
