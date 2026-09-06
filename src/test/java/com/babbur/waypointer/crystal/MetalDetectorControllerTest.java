package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetalDetectorControllerTest {
    @Test
    void lootDetectionIgnoresUnrelatedFindsAndOnlyBrieflySuppressesNearbyStaleReadings() {
        assertFalse(MetalDetectorController.isTreasureFound("You found a Fairy Soul!"));
        assertTrue(MetalDetectorController.isTreasureFound("You found treasure with your Metal Detector!"));
        assertFalse(MetalDetectorController.ignorePostLootReading(1, 0, 100));
        assertTrue(MetalDetectorController.ignorePostLootReading(1, 100, 500_000_000));
        assertFalse(MetalDetectorController.ignorePostLootReading(10, 100, 500_000_000));
        assertFalse(MetalDetectorController.ignorePostLootReading(1, 100, 1_000_000_100));
    }

    @Test
    void everyCandidateIsShownWithoutClaimingAnUnconfirmedSingleton() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        MetalDetectorController controller = new MetalDetectorController(manager, null, new WaypointerConfig());
        var show = MetalDetectorController.class.getDeclaredMethod("show", List.class);
        show.setAccessible(true);
        List<CrystalHollowsPosition> positions = java.util.stream.IntStream.range(0, 12)
                .mapToObj(x -> new CrystalHollowsPosition(650 + x, 78, 426)).toList();
        show.invoke(controller, positions);
        WaypointGroup group = manager.get(MetalDetectorController.GROUP_ID);
        assertEquals(12, group.size());
        assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
        show.invoke(controller, List.of(positions.getFirst()));
        assertEquals(WaypointGroup.LoadMode.STATIC, manager.get(MetalDetectorController.GROUP_ID).loadMode());

        var solverField = MetalDetectorController.class.getDeclaredField("solver");
        solverField.setAccessible(true);
        MetalDetectorSolver solver = (MetalDetectorSolver) solverField.get(controller);
        var centre = new CrystalHollowsPosition(700, 100, 400);
        solver.accept(centre, 663, 79, 426, 1);
        solver.accept(centre, 663, 79, 426, 1);
        assertTrue(solver.solved());
        show.invoke(controller, solver.candidates());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, manager.get(MetalDetectorController.GROUP_ID).loadMode());
    }

    @Test
    void soundDeduplicatesUntilReset() throws Exception {
        MetalDetectorController controller = new MetalDetectorController(new ActiveGroupManager(), null, new WaypointerConfig());
        var announce = MetalDetectorController.class.getDeclaredMethod("shouldAnnounce", CrystalHollowsPosition.class);
        announce.setAccessible(true);
        var position = new CrystalHollowsPosition(662, 78, 426);
        assertEquals(true, announce.invoke(controller, position));
        assertEquals(false, announce.invoke(controller, position));
        var clear = MetalDetectorController.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(controller);
        assertEquals(true, announce.invoke(controller, position));
    }

    @Test
    void markersLeaveRoutesVisibleAndClearWhenDisabled() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        WaypointGroup route = new WaypointGroup("route", "Route", "crystal_hollows");
        route.setEnabled(true);
        manager.add(route);
        WaypointerConfig config = new WaypointerConfig();
        MetalDetectorController controller = new MetalDetectorController(manager, null, config);
        var show = MetalDetectorController.class.getDeclaredMethod("show", List.class);
        show.setAccessible(true);
        show.invoke(controller, List.of(new CrystalHollowsPosition(662, 78, 426)));
        WaypointGroup treasure = manager.get("crystal_hollows:metal_detector");
        assertNotNull(treasure);
        assertTrue(treasure.runtimeOnly());
        assertTrue(manager.activeGroups().contains(route));
        assertTrue(manager.activeGroups().contains(treasure));
        assertFalse(manager.tempWaypointFocusActive());

        config.setCrystalHollowsMetalDetector(false);
        var message = MetalDetectorController.class.getDeclaredMethod("onMessage", Component.class, boolean.class);
        message.setAccessible(true);
        message.invoke(controller, Component.literal("TREASURE: 12m"), true);
        assertNull(manager.get(treasure.id()));
        assertTrue(manager.activeGroups().contains(route));
    }
}
