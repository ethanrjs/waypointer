package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsScreenTest {

    @Test
    void newEditModeVisualDefaultsMatchRequestedStates() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.sharpWaypointEdges());
        assertTrue(config.editSounds());
        assertTrue(config.showEditModeSubtitle());
    }

    @Test
    void hideReachedStaticWaypointsTooltipExplainsChecklistSemantics() {
        Setting setting = SettingsCatalog.byId("hideReachedStaticWaypointsUntilCycleComplete");
        String tooltip = setting.tooltip();

        assertTrue(tooltip.contains("disappear as you reach them"));
        assertTrue(tooltip.contains("like a checklist"));
        assertTrue(tooltip.contains("come back once you've reached every one"));
    }

    @Test
    void settingsSearchClearButtonActivatesOnlyWhenQueryHasText() {
        assertFalse(SettingsScreen.settingsSearchClearButtonActive(null));
        assertFalse(SettingsScreen.settingsSearchClearButtonActive(""));
        assertTrue(SettingsScreen.settingsSearchClearButtonActive(" "));
        assertTrue(SettingsScreen.settingsSearchClearButtonActive("tracer"));
    }

    @Test
    void parseRgbHexColorCommitsOnlyCompleteSixDigitHexValues() {
        assertEquals(0x12ABEF, SettingsScreen.parseRgbHexColor("12ABEF"));
        assertEquals(0x00FFAA, SettingsScreen.parseRgbHexColor(" 00ffaa "));

        assertNull(SettingsScreen.parseRgbHexColor(null));
        assertNull(SettingsScreen.parseRgbHexColor(""));
        assertNull(SettingsScreen.parseRgbHexColor("FFF"));
        assertNull(SettingsScreen.parseRgbHexColor("1234567"));
        assertNull(SettingsScreen.parseRgbHexColor("GGGGGG"));
    }

    @Test
    void maxScrollClampsToZeroWhenContentFitsAndKeepsBottomSlackOtherwise() {
        assertEquals(0, SettingsScreen.maxScrollFor(100, 200));
        assertEquals(0, SettingsScreen.maxScrollFor(0, 200));
        // 300 of content in a 200 viewport: 100 overflow + 8 bottom slack.
        assertEquals(108, SettingsScreen.maxScrollFor(300, 200));
        // Degenerate viewport never yields a negative clamp ceiling.
        assertEquals(300 + 8, SettingsScreen.maxScrollFor(300, -50));
    }

    @Test
    void contentDeadStripSwallowsClicksAboveAndBelowTheViewportOnly() {
        int mainLeft = 180;
        int mainRight = 600;
        int top = 33;
        int rowsTop = 65;
        int bottom = 300;
        int footerTop = 332;

        // Header strip and the gap between list bottom and footer are dead.
        assertTrue(SettingsScreen.inContentDeadStrip(200, 50, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertTrue(SettingsScreen.inContentDeadStrip(200, 310, mainLeft, mainRight, top, rowsTop, bottom, footerTop));

        // Inside the viewport, in the sidebar, and in the footer are live.
        assertFalse(SettingsScreen.inContentDeadStrip(200, 100, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertFalse(SettingsScreen.inContentDeadStrip(50, 50, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertFalse(SettingsScreen.inContentDeadStrip(200, 340, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
    }

    @Test
    void enumCyclingAdvancesInOptionOrderAndWraps() {
        Setting boxStyle = SettingsCatalog.byId("boxStyle");

        assertEquals(WaypointerConfig.BoxStyle.FILLED,
                SettingsScreen.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.OUTLINED));
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED,
                SettingsScreen.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.FILLED));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                SettingsScreen.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.FILLED_OUTLINED));
        // Unknown current value falls back to the first option.
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                SettingsScreen.nextEnumValue(boxStyle, "garbage"));
    }

    @Test
    void tooltipNormalizationCollapsesLinesButKeepsParagraphBreaks() {
        assertEquals("", SettingsScreen.normalizeTooltipText(null));
        assertEquals("one two", SettingsScreen.normalizeTooltipText("one\ntwo"));
        assertEquals("one\n\ntwo", SettingsScreen.normalizeTooltipText("one\n\ntwo"));
        assertEquals("a b\n\nc", SettingsScreen.normalizeTooltipText("  a \r\n b \r\n\r\n c "));
    }
}
