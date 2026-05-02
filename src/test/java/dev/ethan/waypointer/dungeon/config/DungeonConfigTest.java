package dev.ethan.waypointer.dungeon.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonConfigTest {

    @Test
    void defaultsEnableMvpRenderingWithoutDebugNoise() {
        DungeonConfig config = new DungeonConfig();

        assertTrue(config.enabled());
        assertTrue(config.showSecretWaypoints());
        assertTrue(config.showHighlights());
        assertFalse(config.debugLogRoomChanges());
        assertFalse(config.drawRoomBounds());
        assertTrue(config.showFoundSecrets());
        assertEquals("ALL", config.routeRenderMode());
        assertEquals("NW", config.defaultDirection());
    }

    @Test
    void booleanSettersUpdateIndependentFeatureGates() {
        DungeonConfig config = new DungeonConfig();

        config.setEnabled(false);
        config.setShowSecretWaypoints(false);
        config.setShowHighlights(false);
        config.setDebugLogRoomChanges(true);
        config.setDrawRoomBounds(true);
        config.setShowFoundSecrets(false);

        assertFalse(config.enabled());
        assertFalse(config.showSecretWaypoints());
        assertFalse(config.showHighlights());
        assertTrue(config.debugLogRoomChanges());
        assertTrue(config.drawRoomBounds());
        assertFalse(config.showFoundSecrets());
    }

    @Test
    void defaultDirectionAcceptsOnlyCardinalDungeonRotations() {
        DungeonConfig config = new DungeonConfig();

        config.setDefaultDirection(" se ");
        assertEquals("SE", config.defaultDirection());

        config.setDefaultDirection("north");
        assertEquals("SE", config.defaultDirection());

        config.setDefaultDirection(null);
        assertEquals("SE", config.defaultDirection());
    }

    @Test
    void routeRenderModeAcceptsOnlySupportedModes() {
        DungeonConfig config = new DungeonConfig();

        config.setRouteRenderMode("active");
        assertEquals("ACTIVE", config.routeRenderMode());

        config.setRouteRenderMode("everything");
        assertEquals("ACTIVE", config.routeRenderMode());

        config.setRouteRenderMode("all");
        assertEquals("ALL", config.routeRenderMode());
    }
}
