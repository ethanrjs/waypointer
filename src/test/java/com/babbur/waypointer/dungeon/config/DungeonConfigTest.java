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
    void corruptConfigIsQuarantinedBeforeDefaultsCanBeSaved(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("dungeon.json");
        String corrupt = "{ definitely not valid JSON";
        Files.writeString(file, corrupt);

        DungeonConfig config = DungeonConfig.load(file);

        Path quarantine = dir.resolve("dungeon.json.invalid");
        assertFalse(Files.exists(file));
        assertEquals(corrupt, Files.readString(quarantine));

        config.setShowDungeonTracers(true);
        config.flush();

        assertTrue(Files.exists(file));
        assertTrue(DungeonConfig.load(file).showDungeonTracers());
        assertEquals(corrupt, Files.readString(quarantine));
    }

    @Test
    void nullConfigIsQuarantinedBeforeDefaultsCanBeSaved(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("dungeon.json");
        String corrupt = "null";
        Files.writeString(file, corrupt);

        DungeonConfig config = DungeonConfig.load(file);

        Path quarantine = dir.resolve("dungeon.json.invalid");
        assertFalse(Files.exists(file));
        assertEquals(corrupt, Files.readString(quarantine));

        config.setShowDungeonTracers(true);
        config.flush();

        assertTrue(Files.exists(file));
        assertTrue(DungeonConfig.load(file).showDungeonTracers());
        assertEquals(corrupt, Files.readString(quarantine));
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
        assertFalse(config.showDungeonRouteLines());
        assertFalse(config.showDungeonTracers());
        assertFalse(config.secretCompletionSound());
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
        config.setShowDungeonRouteLines(false);
        config.setShowDungeonTracers(true);
        config.setVisibleSecretStages(5);
        config.setSecretCompletionSound(false);
        enabledChanges.set(0);

        config.resetToDefaults();

        assertTrue(config.enabled());
        assertFalse(config.debugLogRoomChanges());
        assertEquals("NW", config.defaultDirection());
        assertTrue(config.hideCompletedRooms());
        assertTrue(config.showDungeonRouteLines());
        assertFalse(config.showDungeonTracers());
        assertEquals(1, config.visibleSecretStages());
        assertTrue(config.secretCompletionSound());
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
        config.setShowDungeonRouteLines(true);
        config.setShowDungeonRouteLines(false);
        config.setShowDungeonTracers(false);
        config.setShowDungeonTracers(true);
        config.setVisibleSecretStages(1);
        config.setVisibleSecretStages(2);
        config.setVisibleSecretStages(2);
        config.setSecretCompletionSound(true);
        config.setSecretCompletionSound(false);
        config.setDefaultDirection("nw");
        config.setDefaultDirection("se");
        config.setDefaultDirection("SE");
        assertEquals(8, changes.get());
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
