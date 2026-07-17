package com.babbur.waypointer.dungeon.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
    void enabledListenersOnlyRunWhenTheMasterSwitchChanges() {
        DungeonConfig config = new DungeonConfig();
        AtomicInteger changes = new AtomicInteger();
        Runnable listener = changes::incrementAndGet;
        config.addEnabledListener(listener);

        config.setEnabled(true);
        config.setEnabled(false);
        config.setEnabled(false);
        config.setEnabled(true);

        assertEquals(2, changes.get());

        config.removeEnabledListener(listener);
        config.setEnabled(false);
        assertEquals(2, changes.get());
    }

    @Test
    void disableAllSettingsTurnsOffEveryDungeonBehaviorToggle() {
        DungeonConfig config = new DungeonConfig();
        config.setDebugLogRoomChanges(true);

        config.disableAllSettings();

        assertFalse(config.enabled());
        assertFalse(config.debugLogRoomChanges());
        assertFalse(config.hideCompletedRooms());
        assertFalse(config.autoCompleteRoomsOnGreenCheckmark());
    }

    @Test
    void resetToDefaultsRestoresEveryDungeonSettingAndNotifiesMasterSwitch() {
        DungeonConfig config = new DungeonConfig();
        AtomicInteger enabledChanges = new AtomicInteger();
        config.addEnabledListener(enabledChanges::incrementAndGet);
        config.setEnabled(false);
        config.setDebugLogRoomChanges(true);
        config.setDefaultDirection("SE");
        config.setHideCompletedRooms(false);
        config.setAutoCompleteRoomsOnGreenCheckmark(false);
        config.setRoutesPromptDismissed(true);
        enabledChanges.set(0);

        config.resetToDefaults();

        assertTrue(config.enabled());
        assertFalse(config.debugLogRoomChanges());
        assertEquals("NW", config.defaultDirection());
        assertTrue(config.hideCompletedRooms());
        assertTrue(config.autoCompleteRoomsOnGreenCheckmark());
        assertFalse(config.routesPromptDismissed());
        assertEquals(1, enabledChanges.get());
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
