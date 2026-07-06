package dev.ethan.waypointer.dungeon.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonConfigTest {

    @Test
    void defaultsEnableRoomDetectionWithoutDebugNoise() {
        DungeonConfig config = new DungeonConfig();

        assertTrue(config.enabled());
        assertFalse(config.debugLogRoomChanges());
        assertEquals("NW", config.defaultDirection());
    }

    @Test
    void booleanSettersUpdateIndependentFeatureGates() {
        DungeonConfig config = new DungeonConfig();

        config.setEnabled(false);
        config.setDebugLogRoomChanges(true);

        assertFalse(config.enabled());
        assertTrue(config.debugLogRoomChanges());
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

}
