package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPresetsTest {

    @Test
    void minimalPresetQuietsTheHudButKeepsNavigationCore() {
        WaypointerConfig minimal = SettingsPresets.minimal(new WaypointerConfig());

        assertTrue(minimal.showWaypointNames(), "names stay on - they are the navigation core");
        assertFalse(minimal.showWaypointDistances());
        assertFalse(minimal.showLabelBackdrop());
        assertFalse(minimal.showLabelTextShadow());
        assertFalse(minimal.showTracer());
        assertFalse(minimal.editSounds());
        assertFalse(minimal.showEditModeSubtitle());
        assertFalse(minimal.showWaypointChatShareButtons());
        assertFalse(minimal.showContributorBadges());
        assertEquals(12, minimal.maxWaypointLabels());
        assertEquals(128.0, minimal.maxStaticWaypointRenderDistance());
    }

    @Test
    void presetsPreserveTheLiveChatSenderBlacklist() {
        WaypointerConfig live = new WaypointerConfig();
        live.addChatCoordSenderBlacklist("Notch");

        assertTrue(SettingsPresets.minimal(live).isChatCoordSenderBlacklisted("Notch"));
        assertTrue(SettingsPresets.minimal(null).chatCoordSenderBlacklist().isEmpty());
    }

    @Test
    void presetsRegisterAsChangedSettingsAgainstDefaults() {
        WaypointerConfig defaults = new WaypointerConfig();
        assertTrue(SettingsCatalog.countChangedSettings(defaults, SettingsPresets.minimal(defaults)) > 0);
    }

    @Test
    void disableAllPresetKeepsStoredValues() {
        WaypointerConfig config = new WaypointerConfig();
        DungeonConfig dungeon = new DungeonConfig();
        config.addChatCoordSenderBlacklist("Notch");
        config.setImportedRouteDefaultColor(0x123456);

        SettingsPresets.applyDisableAll(config, dungeon);

        assertFalse(config.showWaypointNames());
        assertFalse(config.chatCoordDetection());
        assertFalse(dungeon.enabled());
        assertFalse(dungeon.showDungeonRouteLines());
        assertTrue(config.isChatCoordSenderBlacklisted("Notch"));
        assertEquals(0x123456, config.importedRouteDefaultColor());
    }

    @Test
    void recentSettingsStoreOrdersMostRecentFirstAndCaps() {
        RecentSettings.clear();
        assertTrue(RecentSettings.isEmpty());

        RecentSettings.record("a");
        RecentSettings.record("b");
        RecentSettings.record("a"); // re-change moves to front
        assertEquals(java.util.List.of("a", "b"), RecentSettings.mostRecentFirst());

        for (int i = 0; i < 20; i++) {
            RecentSettings.record("setting" + i);
        }
        assertEquals(RecentSettings.MAX_ENTRIES, RecentSettings.mostRecentFirst().size());
        assertEquals("setting19", RecentSettings.mostRecentFirst().get(0));

        RecentSettings.clear();
        assertTrue(RecentSettings.isEmpty());
    }
}
