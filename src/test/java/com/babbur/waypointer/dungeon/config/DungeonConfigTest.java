package com.babbur.waypointer.dungeon.config;

import org.junit.jupiter.api.Test;

import java.util.List;
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
        assertTrue(config.showDungeonRouteLines());
        assertFalse(config.showDungeonTracers());
        assertEquals(1, config.visibleSecretStages());
        assertTrue(config.secretCompletionSound());
        assertTrue(config.showPearlTrajectories());
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
        assertFalse(config.showDungeonRouteLines());
        assertFalse(config.showDungeonTracers());
        assertFalse(config.secretCompletionSound());
        assertFalse(config.showPearlTrajectories());
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
        config.setShowDungeonRouteLines(false);
        config.setShowDungeonTracers(true);
        config.setVisibleSecretStages(5);
        config.setSecretCompletionSound(false);
        config.setShowPearlTrajectories(false);
        config.setRoutesPromptDismissed(true);
        enabledChanges.set(0);

        config.resetToDefaults();

        assertTrue(config.enabled());
        assertFalse(config.debugLogRoomChanges());
        assertEquals("NW", config.defaultDirection());
        assertTrue(config.hideCompletedRooms());
        assertTrue(config.autoCompleteRoomsOnGreenCheckmark());
        assertTrue(config.showDungeonRouteLines());
        assertFalse(config.showDungeonTracers());
        assertEquals(1, config.visibleSecretStages());
        assertTrue(config.secretCompletionSound());
        assertTrue(config.showPearlTrajectories());
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

    @Test
    void definitionRouteVisibilityDefaultsShownAndPersistsPerRoom() {
        DungeonConfig config = new DungeonConfig();

        assertTrue(config.roomRouteEnabled("room-a"));
        assertTrue(config.roomRouteEnabled("room-b"));

        config.setRoomRouteEnabled("room-a", false);
        assertFalse(config.roomRouteEnabled("room-a"));
        assertTrue(config.roomRouteEnabled("room-b"));

        config.setRoomRouteEnabled("room-a", true);
        assertTrue(config.roomRouteEnabled("room-a"));
    }

    @Test
    void bulkDisableHidesExistingDefinitionRoutes() {
        DungeonConfig config = new DungeonConfig();

        config.disableRoomRoutes(List.of("room-a", "room-b", "room-a"));

        assertFalse(config.roomRouteEnabled("room-a"));
        assertFalse(config.roomRouteEnabled("room-b"));
        assertTrue(config.roomRouteEnabled("room-c"));
    }

    @Test
    void visibleSecretStageCountClampsAndUsesGeneralChangeListeners() {
        DungeonConfig config = new DungeonConfig();
        AtomicInteger enabledChanges = new AtomicInteger();
        AtomicInteger changes = new AtomicInteger();
        config.addEnabledListener(enabledChanges::incrementAndGet);
        Runnable listener = changes::incrementAndGet;
        config.addChangeListener(listener);

        config.setVisibleSecretStages(99);
        config.setVisibleSecretStages(5);
        config.setVisibleSecretStages(0);

        assertEquals(1, config.visibleSecretStages());
        assertEquals(2, changes.get());
        assertEquals(0, enabledChanges.get());

        config.removeChangeListener(listener);
        config.setVisibleSecretStages(2);
        assertEquals(2, changes.get());
    }

}
