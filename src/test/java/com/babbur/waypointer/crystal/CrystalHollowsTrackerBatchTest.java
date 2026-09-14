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
    void fairyGrottoSidebarCreatesAndCorrectsWaypointAtReportedPosition() throws Exception {
        for (boolean hasRemote : new boolean[] {false, true}) {
            var manager = new ActiveGroupManager();
            var config = new WaypointerConfig();
            config.setCrystalHollowsShowRoughMarkers(true);
            var tracker = new CrystalHollowsTracker(manager, config, null);
            var lobby = new CrystalHollowsLobbyState("mini196BN", 1, 6);
            set(tracker, "active", true);
            set(tracker, "lobby", lobby);
            var structure = CrystalHollowsSidebar.structureForArea("Fairy Grotto");
            set(tracker, "areaSession", new CrystalHollowsAreaSession(structure, 246.220, 104, 625.300));
            var remote = new StructureSighting(structure, 253, 107, 618,
                    SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 1);
            if (hasRemote) lobby.merge(remote);
            tracker.sampleRoughArea(246.220, 104, 625.300);
            assertEquals(1, lobby.sightings().size());
            var observed = lobby.sightings().getFirst();
            assertEquals(new CrystalHollowsPosition(246, 104, 625), observed.position());
            assertEquals(SightingConfidence.ROUGH_AREA, observed.confidence());
            assertEquals("sidebar", observed.source());
            var group = manager.get(CrystalHollowsStructureFolder.GROUP_PREFIX + "fairy_grotto");
            assertNotNull(group, "sidebar alone must create the generated waypoint");
            assertEquals(246, group.get(0).x());
            assertEquals(104, group.get(0).y());
            assertEquals(625, group.get(0).z());
            assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED, lobby.merge(remote));
            tracker.sampleRoughArea(252.220, 104, 629.300);
            assertEquals(new CrystalHollowsPosition(249, 104, 627), lobby.sightings().getFirst().position());
            assertEquals(1, lobby.sightings().size(), "refinement must not duplicate the grotto");
        }
    }

    @Test
    void incorrectSkyblockerRequiresContinuousArrivalAndCannotReturnFromSocket() throws Exception {
        var manager = new ActiveGroupManager();
        var tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        var lobby = new CrystalHollowsLobbyState("m14ap", 1, 17);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        var local = new StructureSighting(CrystalHollowsStructure.LOST_PRECURSOR_CITY, 610, 100, 610,
                SightingConfidence.SHARED_CHAT, "chat:player", 1);
        var remote = new StructureSighting(local.structure(), 600, 100, 600,
                SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 2);
        lobby.merge(local);
        lobby.merge(remote);
        tracker.focusCompassTarget(remote);
        for (int i = 0; i < 29; i++) {
            assertTrue(tracker.checkIncorrectStructures("Precursor Remnants", 600, 100, 600, true).isEmpty());
        }
        assertTrue(tracker.checkIncorrectStructures("Precursor Remnants", 600, 100, 600, false).isEmpty());
        for (int i = 0; i < 29; i++) {
            assertTrue(tracker.checkIncorrectStructures("Precursor Remnants", 600, 100, 600, true).isEmpty());
        }
        assertTrue(tracker.checkIncorrectStructures("Precursor Remnants", 610, 100, 600, true).isEmpty());
        for (int i = 0; i < 29; i++) {
            assertTrue(tracker.checkIncorrectStructures("Precursor Remnants", 600, 100, 600, true).isEmpty());
        }
        assertEquals(java.util.List.of(remote.structure()),
                tracker.checkIncorrectStructures("Precursor Remnants", 600, 100, 600, true));
        assertEquals(java.util.List.of(local), lobby.sightings());
        assertNull(tracker.compassTargetSighting());
        var repeated = new StructureSighting(remote.structure(), 600, 100, 600,
                SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 100);
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED, lobby.merge(repeated));
        var restored = CrystalHollowsLobbyState.identify("m14ap", 17, 100, lobby, null);
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED, restored.merge(repeated));
        assertTrue(tracker.checkIncorrectStructures("Precursor Remnants", 600, 100, 600, true).isEmpty());
    }

    @Test
    void rejectsFairyGrottoAtReportedFourMeterPosition() throws Exception {
        var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), new WaypointerConfig(), null);
        var lobby = new CrystalHollowsLobbyState("mini192AF", 1, 8);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        var remote = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO, 734, 100, 422,
                SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 1);
        lobby.merge(remote);
        for (int i = 0; i < 29; i++) {
            assertTrue(tracker.checkIncorrectStructures("Mithril Deposits", 731.3, 97, 421.3, true).isEmpty());
        }
        assertEquals(java.util.List.of(CrystalHollowsStructure.FAIRY_GROTTO),
                tracker.checkIncorrectStructures("Mithril Deposits", 731.3, 97, 421.3, true));
        assertTrue(lobby.sightings().isEmpty());
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED, lobby.merge(remote));
    }

    @Test
    void disabledStructureRejectsEveryAutomaticSourceButKeepsExplicitNavigation() throws Exception {
        for (SightingConfidence confidence : SightingConfidence.values()) {
            var config = new WaypointerConfig();
            config.setCrystalHollowsDetectionEnabled("odawa", false);
            var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), config, null);
            var lobby = new CrystalHollowsLobbyState("test", 0, 0);
            set(tracker, "active", true);
            set(tracker, "lobby", lobby);
            var sighting = new StructureSighting(CrystalHollowsStructure.ODAWA, 300, 100, 300,
                    confidence, "test", 1);
            boolean explicit = confidence == SightingConfidence.COMPASS || confidence == SightingConfidence.MANUAL;
            assertEquals(explicit, tracker.detectionEnabled(sighting), confidence.name());
            assertEquals(explicit ? CrystalHollowsLobbyState.MergeResult.ADDED
                            : CrystalHollowsLobbyState.MergeResult.IGNORED,
                    tracker.merge(sighting, false), confidence.name());
            assertEquals(explicit ? 1 : 0, lobby.sightings().size(), confidence.name());
            if (confidence == SightingConfidence.ENTITY || confidence == SightingConfidence.COMPASS
                    || confidence == SightingConfidence.NPC_CHAT) {
                var remote = new StructureSighting(CrystalHollowsStructure.ODAWA, 301, 100, 301,
                        SightingConfidence.SHARED_REMOTE, "relay", 2, java.util.List.of(), "", confidence);
                assertFalse(tracker.detectionEnabled(remote), "Remote evidence must not bypass preference: " + confidence);
            }
        }
    }

    @Test
    void detectionToggleRebuildsExistingRoutesAndAllowsEntityRediscovery() throws Exception {
        var manager = new ActiveGroupManager();
        var config = new WaypointerConfig();
        config.setCrystalHollowsNucleusWaypoints(false);
        var tracker = new CrystalHollowsTracker(manager, config, null);
        var lobby = new CrystalHollowsLobbyState("test", 0, 0);
        lobby.merge(sighting(CrystalHollowsStructure.ODAWA));
        set(tracker, "lobby", lobby);
        var refresh = CrystalHollowsTracker.class.getDeclaredMethod("refreshProjectionSettings");
        refresh.setAccessible(true);
        refresh.invoke(tracker);
        String generatedId = CrystalHollowsStructureFolder.GROUP_PREFIX + "odawa";
        assertNotNull(manager.get(generatedId));
        set(tracker, "lastRoughPosition", new CrystalHollowsPosition(300, 100, 300));
        @SuppressWarnings("unchecked")
        var processed = (java.util.Set<Integer>) get(tracker, "processedEntityIds");
        processed.add(42);
        config.setCrystalHollowsDetectionEnabled("odawa", false);
        refresh.invoke(tracker);
        assertNull(manager.get(generatedId));
        assertEquals(1, lobby.sightings().size());
        assertTrue(processed.isEmpty());
        assertNull(get(tracker, "lastRoughPosition"));
        processed.add(43);
        config.setCrystalHollowsDetectionEnabled("odawa", true);
        refresh.invoke(tracker);
        assertNotNull(manager.get(generatedId));
        assertTrue(processed.isEmpty());
    }

    @Test
    void caveInWarningsAreExactScopedAndDoNotExtendTheDeadline() throws Exception {
        var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), new WaypointerConfig(), null);
        String warning = "You can hear mighty rumbles and explosions in the distance...";
        tracker.observeCaveInWarning(warning, 100_000);
        assertEquals(-1, tracker.closingInSeconds(null, 100_000));
        set(tracker, "active", true);
        set(tracker, "lobby", new CrystalHollowsLobbyState("m14ap", 100_000, 17));
        tracker.observeCaveInWarning("[MVP+] Player: " + warning, 100_000);
        assertEquals(-1, tracker.closingInSeconds(null, 100_000));
        tracker.observeCaveInWarning("§c" + warning, 100_000);
        assertEquals(60, tracker.closingInSeconds(null, 100_000));
        tracker.observeCaveInWarning("The ground around you starts to wobble and crack...", 120_000);
        assertEquals(40, tracker.closingInSeconds(null, 120_000));
        tracker.observeCaveInWarning("You notice the ceiling starting to give way...", 130_000);
        assertEquals(30, tracker.closingInSeconds(null, 130_000));
        assertEquals(5, tracker.closingInSeconds("Server closing: 00:05 m14ap", 130_000));
        assertEquals(0, tracker.closingInSeconds(null, 161_000));
        var reset = CrystalHollowsTracker.class.getDeclaredMethod("resetLobbyTransients");
        reset.setAccessible(true);
        reset.invoke(tracker);
        assertEquals(-1, tracker.closingInSeconds(null, 161_000));
    }

    @Test
    void relaySnapshotReplacesOnlyRelayDataAndClearsRemovedNavigation() throws Exception {
        var manager = new ActiveGroupManager();
        var store = new CrystalHollowsStore(directory.resolve("relay-snapshot.json"));
        var tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), store);
        long now = System.currentTimeMillis();
        var lobby = new CrystalHollowsLobbyState("m14ap", now, 17);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        var local = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN, 600, 100, 300,
                SightingConfidence.COMPASS, "compass", now);
        var relay = new StructureSighting(local.structure(), 610, 100, 300,
                SightingConfidence.SHARED_REMOTE, "relay:45fdbbc1-e3dd-4a82-a898-1d43b48ebf07",
                now - 6 * 60 * 60_000L, java.util.List.of(), "", SightingConfidence.ENTITY);
        var otherProvider = new StructureSighting(CrystalHollowsStructure.ODAWA, 400, 100, 300,
                SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, now);
        var removed = new StructureSighting(CrystalHollowsStructure.KING_YOLKAR, 400, 100, 400,
                SightingConfidence.SHARED_REMOTE, "relay", now);
        try {
            lobby.merge(local);
            lobby.merge(relay);
            lobby.merge(otherProvider);
            lobby.merge(removed);
            tracker.focusCompassTarget(removed);
            assertFalse(lobby.expireRemoteSightings(now - 1_800_000, SkyblockerStructureProtocol.SOURCE));
            assertTrue(lobby.sightings().contains(relay));
            tracker.applyRelayUpdate(null);
            assertTrue(lobby.sightings().contains(removed));
            tracker.applyRelayUpdate(new StructureRelayProtocol.Update(true, java.util.List.of(relay)));
            assertEquals(2, lobby.sightings().size());
            assertTrue(lobby.sightings().contains(relay));
            assertTrue(lobby.sightings().contains(otherProvider));
            assertNull(tracker.compassTargetSighting());
            tracker.applyRelayUpdate(new StructureRelayProtocol.Update(true, java.util.List.of()));
            assertEquals(java.util.Set.of(local, otherProvider), java.util.Set.copyOf(lobby.sightings()));
        } finally {
            store.discardPendingSave();
        }
    }

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
