package com.babbur.waypointer.dungeon.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonConfigTest {

    @Test
    void failedWriteRemainsDirtyAndFlushCanRetry(@TempDir Path dir) throws IOException {
        Path blockedParent = dir.resolve("not-a-directory");
        Files.writeString(blockedParent, "block directory creation");
        Path file = blockedParent.resolve("dungeon.json");
        DungeonConfig config = DungeonConfig.load(file);
        config.setShowDungeonTracers(true);

        assertThrows(UncheckedIOException.class, config::flush);

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        config.flush();

        assertTrue(DungeonConfig.load(file).showDungeonTracers());
    }

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
        assertEquals(0x2EE0FF, config.automaticSecretColor());
        assertEquals(0x9C2EFF, config.automaticEtherwarpColor());
        assertEquals(0x4FE05A, config.automaticBreakBlocksColor());
        assertEquals(0xFF8A2E, config.automaticInteractColor());
        assertEquals(0xFFB300, config.automaticSuperboomColor());
        assertEquals(0xFFD800, config.automaticItemColor());
        assertEquals(0x9C5A2E, config.automaticBatColor());
        assertEquals(0x6EE7B7, config.automaticDungeonbreakerColor());
        assertEquals(0xC0C0FF, config.automaticPearlColor());
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
        config.setAutomaticSecretColor(0x010101);
        config.setAutomaticEtherwarpColor(0x020202);
        config.setAutomaticBreakBlocksColor(0x030303);
        config.setAutomaticInteractColor(0x040404);
        config.setAutomaticSuperboomColor(0x050505);
        config.setAutomaticItemColor(0x060606);
        config.setAutomaticBatColor(0x070707);
        config.setAutomaticDungeonbreakerColor(0x080808);
        config.setAutomaticPearlColor(0x090909);
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
        assertEquals(0x2EE0FF, config.automaticSecretColor());
        assertEquals(0x9C2EFF, config.automaticEtherwarpColor());
        assertEquals(0x4FE05A, config.automaticBreakBlocksColor());
        assertEquals(0xFF8A2E, config.automaticInteractColor());
        assertEquals(0xFFB300, config.automaticSuperboomColor());
        assertEquals(0xFFD800, config.automaticItemColor());
        assertEquals(0x9C5A2E, config.automaticBatColor());
        assertEquals(0x6EE7B7, config.automaticDungeonbreakerColor());
        assertEquals(0xC0C0FF, config.automaticPearlColor());
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

    @Test
    void everySetterNotifiesGeneralListenersOnlyForActualChanges() {
        DungeonConfig config = new DungeonConfig();
        AtomicInteger changes = new AtomicInteger();
        config.addChangeListener(changes::incrementAndGet);

        config.setEnabled(true);
        config.setEnabled(false);
        config.setEnabled(false);
        config.setDebugLogRoomChanges(false);
        config.setDebugLogRoomChanges(true);
        config.setDebugLogRoomChanges(true);
        config.setHideCompletedRooms(true);
        config.setHideCompletedRooms(false);
        config.setAutoCompleteRoomsOnGreenCheckmark(true);
        config.setAutoCompleteRoomsOnGreenCheckmark(false);
        config.setShowDungeonRouteLines(true);
        config.setShowDungeonRouteLines(false);
        config.setShowDungeonTracers(false);
        config.setShowDungeonTracers(true);
        config.setVisibleSecretStages(1);
        config.setVisibleSecretStages(2);
        config.setVisibleSecretStages(2);
        config.setSecretCompletionSound(true);
        config.setSecretCompletionSound(false);
        config.setShowPearlTrajectories(true);
        config.setShowPearlTrajectories(false);
        config.setAutomaticSecretColor(0x010101);
        config.setAutomaticEtherwarpColor(0x020202);
        config.setAutomaticBreakBlocksColor(0x030303);
        config.setAutomaticInteractColor(0x040404);
        config.setAutomaticSuperboomColor(0x050505);
        config.setAutomaticItemColor(0x060606);
        config.setAutomaticBatColor(0x070707);
        config.setAutomaticDungeonbreakerColor(0x080808);
        config.setAutomaticPearlColor(0x090909);
        config.setRoutesPromptDismissed(false);
        config.setRoutesPromptDismissed(true);
        config.setDefaultDirection("nw");
        config.setDefaultDirection("se");
        config.setDefaultDirection("SE");
        config.setRoomRouteEnabled("room-a", true);
        config.setRoomRouteEnabled("room-a", false);
        config.setRoomRouteEnabled("room-a", false);
        config.setRoomRouteEnabled("room-a", true);
        config.disableRoomRoutes(List.of("room-a", "room-a"));
        config.disableRoomRoutes(List.of("room-a"));

        assertEquals(23, changes.get());
    }

    @Test
    void automaticColorsMaskAlphaAndPersist(@TempDir Path dir) {
        Path file = dir.resolve("dungeon.json");
        DungeonConfig config = DungeonConfig.load(file);

        config.setAutomaticSecretColor(0xAA112233);
        config.setAutomaticEtherwarpColor(0xBB445566);
        config.flush();

        DungeonConfig loaded = DungeonConfig.load(file);
        assertEquals(0x112233, loaded.automaticSecretColor());
        assertEquals(0x445566, loaded.automaticEtherwarpColor());
    }

    @Test
    void bulkMutationsDoNotNotifyWhenAlreadyAtTheRequestedState() {
        DungeonConfig config = new DungeonConfig();
        AtomicInteger changes = new AtomicInteger();
        config.addChangeListener(changes::incrementAndGet);

        config.resetToDefaults();
        assertEquals(0, changes.get());

        config.disableAllSettings();
        config.disableAllSettings();
        assertEquals(1, changes.get());

        config.resetToDefaults();
        config.resetToDefaults();
        assertEquals(2, changes.get());
    }

}
