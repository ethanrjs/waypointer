package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.compass.WishingCompassSolver;
import org.junit.jupiter.api.Test;

class WishingCompassControllerTest {

    @Test
    void sharedConfirmationDoesNotRedirectToDistantOrUnrelatedInstances() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        StructureSighting target = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO,
                300, 100, 300, SightingConfidence.COMPASS, "compass", 1L);
        tracker.focusCompassTarget(target);
        tracker.refineCompassTarget(new StructureSighting(target.structure(), 400, 100, 300,
                SightingConfidence.SHARED_REMOTE, "relay", 2L, java.util.List.of(), "",
                SightingConfidence.ENTITY));
        assertEquals(target, tracker.compassTargetSighting());

        StructureSighting ambiguous = new StructureSighting(CrystalHollowsStructure.WISHING_TARGET,
                600, 100, 700, SightingConfidence.COMPASS, "compass", 1L,
                java.util.List.of(CrystalHollowsStructure.MINES_OF_DIVAN), "");
        tracker.focusCompassTarget(ambiguous);
        tracker.refineCompassTarget(new StructureSighting(CrystalHollowsStructure.LOST_PRECURSOR_CITY,
                600, 100, 700, SightingConfidence.SHARED_REMOTE, "relay", 2L, java.util.List.of(), "",
                SightingConfidence.ENTITY));
        assertEquals(ambiguous, tracker.compassTargetSighting());
        assertTrue(manager.tempWaypointFocusActive());
    }

    @Test
    void arrivalStateDoesNotTransferToAnIdenticalNewMarker() {
        Waypoint first = Waypoint.at(300, 100, 300);
        Waypoint second = Waypoint.at(300, 100, 300);
        CompassMarkerState.markArrived(first);
        assertTrue(CompassMarkerState.arrived(first));
        assertFalse(CompassMarkerState.arrived(second));
    }

    @Test
    void solvedTargetKeepsPreviousCompassMarkerVisible() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        var unrelated = manager.addTempWaypoint(300, 100, 300, "Other marker");
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        StructureSighting first = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                695, 87, 421, SightingConfidence.COMPASS, "compass", 1L);
        tracker.focusCompassTarget(first);
        String firstShare = tracker.compassShareReference();

        assertTrue(manager.tempWaypointFocusActive());
        assertEquals(1, unrelated.focusedVisibleIndex());
        assertEquals(Waypoint.TEMP_UNTIL_LEAVE, unrelated.get(1).tempMode());
        assertTrue(unrelated.get(1).hasFlag(Waypoint.FLAG_THROUGH_WALL));
        assertEquals(695, unrelated.get(1).x());

        tracker.refineCompassTarget(new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                695, 80, 421, SightingConfidence.ENTITY, "entity:Keeper of Diamond", 2L));
        assertEquals(80, unrelated.get(1).y());
        assertTrue(manager.tempWaypointFocusActive());

        tracker.focusCompassTarget(new StructureSighting(CrystalHollowsStructure.ODAWA,
                349, 110, 390, SightingConfidence.COMPASS, "compass", 2L));
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN,
                tracker.compassShare(firstShare).structure());
        assertEquals(3, unrelated.size());
        assertEquals(349, unrelated.get(2).x());
        assertTrue(CompassMarkerState.arrived(unrelated.get(1)));
        tracker.clearCompassTarget();
        assertEquals(2, unrelated.size());
        assertEquals("Other marker", unrelated.get(0).name());
        assertFalse(manager.tempWaypointFocusActive());
    }

    @Test
    void citySidebarWithHiddenPrefixClearsFocusAndKeepsRoutesVisible() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        var other = com.babbur.waypointer.core.WaypointGroup.create(
                "Other route", "crystal_hollows", false);
        other.add(Waypoint.at(300, 100, 300));
        manager.add(other);
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        tracker.focusCompassTarget(new StructureSighting(CrystalHollowsStructure.LOST_PRECURSOR_CITY,
                600, 100, 700, SightingConfidence.COMPASS, "compass", 1L));
        String reference = tracker.compassShareReference();
        assertFalse(manager.activeGroups().contains(other));

        String sidebar = "09/05/26 m14AP\n\u200b§b⏣ Lost Precursor City\nPurse: 192,862";
        var area = CrystalHollowsSidebar.structureForArea(CrystalHollowsSidebar.areaName(sidebar));
        tracker.checkCompassArrival(area, 594, 100, 700);

        assertFalse(manager.tempWaypointFocusActive());
        assertTrue(manager.activeGroups().contains(other));
        assertEquals(1, manager.getOrCreateTempGroup().size());
        assertTrue(CompassMarkerState.arrived(manager.getOrCreateTempGroup().get(0)));
        assertEquals(CrystalHollowsStructure.LOST_PRECURSOR_CITY,
                tracker.compassShare(reference).structure());
    }

    @Test
    void enteringCityResolvesAmbiguousMarkerAndStopsFocusWithoutRemovingIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        tracker.focusCompassTarget(new StructureSighting(CrystalHollowsStructure.WISHING_TARGET,
                600, 100, 700, SightingConfidence.COMPASS, "compass", 1L,
                java.util.List.of(CrystalHollowsStructure.LOST_PRECURSOR_CITY), ""));
        var group = manager.getOrCreateTempGroup();
        tracker.checkCompassArrival(CrystalHollowsStructure.LOST_PRECURSOR_CITY, 570, 100, 680);
        assertEquals(1, group.size());
        assertFalse(manager.tempWaypointFocusActive());
        assertTrue(CompassMarkerState.arrived(group.get(0)));
        assertFalse(group.get(0).hasFlag(Waypoint.FLAG_DISABLED));
        assertEquals(CrystalHollowsStructure.LOST_PRECURSOR_CITY,
                tracker.compassTargetSighting().structure());
    }

    @Test
    void pointTargetStopsOnlyWithinTenBlocksAndStaysShareable() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        CrystalHollowsTracker tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        tracker.focusCompassTarget(new StructureSighting(CrystalHollowsStructure.KING_YOLKAR,
                300, 100, 700, SightingConfidence.COMPASS, "compass", 1L));
        tracker.checkCompassArrival(null, 311, 100, 700);
        assertTrue(manager.tempWaypointFocusActive());
        tracker.checkCompassArrival(null, 310, 100, 700);
        assertFalse(manager.tempWaypointFocusActive());
        assertEquals(1, manager.getOrCreateTempGroup().size());
        assertEquals(CrystalHollowsStructure.KING_YOLKAR, tracker.compassTargetSighting().structure());
    }

    @Test
    void disablingSolverClearsAnInProgressCaptureOnTheNextTick() {
        WaypointerConfig config = new WaypointerConfig();
        WishingCompassController controller = new WishingCompassController(null, config);
        controller.solver().onUse(300, 100, 300, CrystalHollowsZone.JUNGLE, 100);
        assertEquals(WishingCompassSolver.State.WAITING_PARTICLES,
                controller.solver().state());

        config.setCrystalHollowsWishingCompassSolver(false);
        controller.tick(101);

        assertEquals(WishingCompassSolver.State.IDLE, controller.solver().state());
        assertEquals("reset", controller.lastEvent());
    }
}
