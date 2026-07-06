package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigScreenTest {

    @Test
    void newEditModeVisualDefaultsMatchRequestedStates() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.sharpWaypointEdges());
        assertTrue(config.editSounds());
        assertTrue(config.showEditModeSubtitle());
    }

    @Test
    void hideReachedStaticWaypointsTooltipExplainsChecklistSemantics() {
        String tooltip = ConfigScreen.hideReachedStaticWaypointsTooltip();

        assertTrue(tooltip.contains("Static routes hide reached main markers"));
        assertTrue(tooltip.contains("current/tracer does not advance"));
        assertTrue(tooltip.contains("subwaypoints are ignored"));
    }

    @Test
    void fuzzySettingMatchRequiresEverySearchToken() {
        String searchable = "Visuals Tracer Opacity Shows the tracer through walls";

        assertTrue(ConfigScreen.fuzzySettingMatch(null, searchable));
        assertTrue(ConfigScreen.fuzzySettingMatch("  ", searchable));
        assertTrue(ConfigScreen.fuzzySettingMatch("tracer opacity", searchable));
        assertTrue(ConfigScreen.fuzzySettingMatch("VISUALS walls", searchable));
        assertFalse(ConfigScreen.fuzzySettingMatch("tracer missing", searchable));
        assertFalse(ConfigScreen.fuzzySettingMatch("tracer", null));
    }

    @Test
    void settingsSearchClearButtonActivatesOnlyWhenQueryHasText() {
        assertFalse(ConfigScreen.settingsSearchClearButtonActive(null));
        assertFalse(ConfigScreen.settingsSearchClearButtonActive(""));
        assertTrue(ConfigScreen.settingsSearchClearButtonActive(" "));
        assertTrue(ConfigScreen.settingsSearchClearButtonActive("tracer"));
    }

    @Test
    void parseRgbHexColorCommitsOnlyCompleteSixDigitHexValues() {
        assertEquals(0x12ABEF, ConfigScreen.parseRgbHexColor("12ABEF"));
        assertEquals(0x00FFAA, ConfigScreen.parseRgbHexColor(" 00ffaa "));

        assertNull(ConfigScreen.parseRgbHexColor(null));
        assertNull(ConfigScreen.parseRgbHexColor(""));
        assertNull(ConfigScreen.parseRgbHexColor("FFF"));
        assertNull(ConfigScreen.parseRgbHexColor("1234567"));
        assertNull(ConfigScreen.parseRgbHexColor("GGGGGG"));
    }
}
