package dev.ethan.waypointer.screen.settings;

import dev.ethan.waypointer.config.WaypointerConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfScenariosTest {

    @Test
    void scenarioIdsAreUniqueAndDescribed() {
        Set<String> ids = new HashSet<>();
        for (PerfScenarios.Scenario scenario : PerfScenarios.all()) {
            assertTrue(ids.add(scenario.id()), "duplicate scenario id " + scenario.id());
            assertFalse(scenario.label().isBlank());
            assertFalse(scenario.description().isBlank());
        }
        assertTrue(ids.size() >= 10, "the sweep should cover a large sampling of impact settings");
    }

    @Test
    void baselineHidesWaypointsEntirely() {
        WaypointerConfig config = new WaypointerConfig();
        first("hidden").apply().accept(config);

        assertTrue(config.hideWaypointsNearPlayer());
        assertEquals(100.0, config.hideWaypointsNearRadius());
        assertFalse(config.showWaypointNames());
        assertFalse(config.showTracer());
        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, config.beaconBeamMode());
    }

    @Test
    void scenariosIsolateTheirSubsystem() {
        WaypointerConfig config = new WaypointerConfig();

        first("beams-all-flat").apply().accept(config);
        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, config.beaconBeamMode());
        assertFalse(config.useBeaconBeamTextures());
        assertFalse(config.showTracer(), "beam scenario must not also stress tracers");
        assertFalse(config.showWaypointNames(), "beam scenario must not also stress labels");

        first("tracers").apply().accept(config);
        assertTrue(config.showTracer());
        assertFalse(config.hideTracerOnStaticRoutes());
        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, config.beaconBeamMode());
    }

    @Test
    void everyScenarioStartsFromTheBaseSoOrderCannotLeakState() {
        WaypointerConfig config = new WaypointerConfig();
        first("everything").apply().accept(config);
        assertTrue(config.showWaypointNames());
        assertTrue(config.showTracer());

        // Re-applying the label-free baseline must fully undo "everything".
        first("boxes-outlined").apply().accept(config);
        assertFalse(config.showWaypointNames());
        assertFalse(config.showTracer());
        assertFalse(config.showRouteLines());
        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, config.beaconBeamMode());
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED, config.boxStyle());
    }

    @Test
    void budgetedScenarioActuallyBudgets() {
        WaypointerConfig config = new WaypointerConfig();
        first("everything-budgeted").apply().accept(config);
        assertEquals(12, config.maxWaypointLabels());
        assertEquals(128.0, config.maxStaticWaypointRenderDistance());

        first("everything").apply().accept(config);
        assertEquals(0, config.maxWaypointLabels());
        assertEquals(0.0, config.maxStaticWaypointRenderDistance());
    }

    private static PerfScenarios.Scenario first(String id) {
        List<PerfScenarios.Scenario> all = PerfScenarios.all();
        return all.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
