package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
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
        assertFalse(minimal.showContributorBadges());
        assertEquals(12, minimal.maxWaypointLabels());
        assertEquals(128.0, minimal.maxStaticWaypointRenderDistance());
    }

    @Test
    void everythingPresetEnablesAllDisplayFeaturesWithUnlimitedBudgets() {
        WaypointerConfig everything = SettingsPresets.everything(new WaypointerConfig());

        assertTrue(everything.showWaypointNames());
        assertTrue(everything.showWaypointDistances());
        assertTrue(everything.showRouteProgress());
        assertTrue(everything.showLabelTextShadow());
        assertTrue(everything.showTracer());
        assertTrue(everything.showRouteLines());
        assertTrue(everything.showDungeonEntryPathToFirstWaypoint());
        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, everything.beaconBeamMode());
        assertEquals(0, everything.maxWaypointLabels());
        assertEquals(0.0, everything.maxStaticWaypointRenderDistance());
    }

    @Test
    void presetsPreserveTheLiveChatSenderBlacklist() {
        WaypointerConfig live = new WaypointerConfig();
        live.addChatCoordSenderBlacklist("Notch");

        assertTrue(SettingsPresets.minimal(live).isChatCoordSenderBlacklisted("Notch"));
        assertTrue(SettingsPresets.everything(live).isChatCoordSenderBlacklisted("Notch"));
        assertTrue(SettingsPresets.minimal(null).chatCoordSenderBlacklist().isEmpty());
    }

    @Test
    void presetsRegisterAsChangedSettingsAgainstDefaults() {
        WaypointerConfig defaults = new WaypointerConfig();
        assertTrue(SettingsCatalog.countChangedSettings(defaults, SettingsPresets.minimal(defaults)) > 0);
        assertTrue(SettingsCatalog.countChangedSettings(defaults, SettingsPresets.everything(defaults)) > 0);
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
