package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsScreenTest {

    @Test
    void nextSequenceCountRejectsAll() {
        Setting setting = SettingsCatalog.byId("sequenceNextWaypointCount");

        assertEquals("32", SettingsText.localizedValue(setting, 32.0D).getString());
        assertNull(SettingsValuePolicy.acceptedNumberValue(setting, "All"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(setting, "33"));
    }

    @Test
    void booleanSettingsUseTwentyPixelCheckboxes() {
        assertEquals(20, SettingsScreen.CHECKBOX_SIZE);
    }

    @Test
    void presetsAppearAsMinimalDefaultAndNothing() {
        assertEquals(java.util.List.of("minimal", "default", "nothing"),
                SettingsValuePolicy.visiblePresetIds());
    }

    @Test
    void newEditModeVisualDefaultsMatchRequestedStates() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.editSounds());
        assertTrue(config.showEditModeSubtitle());
    }

    @Test
    void disableAllCountIncludesEveryEnabledDungeonToggle() {
        DungeonConfig config = new DungeonConfig();
        config.setDebugLogRoomChanges(true);
        config.setShowDungeonTracers(true);

        assertEquals(6, SettingsScreen.changedDungeonSettingsWhenDisabled(config));

        config.disableAllSettings();
        assertEquals(0, SettingsScreen.changedDungeonSettingsWhenDisabled(config));
    }

    @Test
    void settingsSearchClearButtonActivatesOnlyWhenQueryHasText() {
        assertFalse(SettingsValuePolicy.searchClearActive(null));
        assertFalse(SettingsValuePolicy.searchClearActive(""));
        assertTrue(SettingsValuePolicy.searchClearActive(" "));
        assertTrue(SettingsValuePolicy.searchClearActive("tracer"));
    }

    @Test
    void searchFocusRestorationUsesThePostRebuildInitialFocusHook() throws Exception {
        var hook = SettingsScreen.class.getDeclaredMethod("setInitialFocus");

        assertTrue(Modifier.isProtected(hook.getModifiers()));
    }

    @Test
    void maxScrollClampsToZeroWhenContentFitsAndKeepsBottomSlackOtherwise() {
        assertEquals(0, SettingsScreen.maxScrollFor(100, 200));
        assertEquals(0, SettingsScreen.maxScrollFor(0, 200));
        assertEquals(108, SettingsScreen.maxScrollFor(300, 200));
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

        assertTrue(SettingsScreen.inContentDeadStrip(200, 50, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertTrue(SettingsScreen.inContentDeadStrip(200, 310, mainLeft, mainRight, top, rowsTop, bottom, footerTop));

        assertFalse(SettingsScreen.inContentDeadStrip(200, 100, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertFalse(SettingsScreen.inContentDeadStrip(50, 50, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertFalse(SettingsScreen.inContentDeadStrip(200, 340, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
    }

    @Test
    void enumCyclingAdvancesInOptionOrderAndWraps() {
        Setting boxStyle = SettingsCatalog.byId("boxStyle");

        assertEquals(WaypointerConfig.BoxStyle.FILLED,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.OUTLINED));
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.FILLED));
        assertEquals(WaypointerConfig.BoxStyle.PAINT,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.FILLED_OUTLINED));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.PAINT));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                SettingsValuePolicy.nextEnumValue(boxStyle, "garbage"));
    }

    @Test
    void boundedNumberInputRejectsInvalidTextInsteadOfSilentlyClamping() {
        Setting size = SettingsCatalog.byId("waypointMarkerScale");
        Setting duration = SettingsCatalog.byId("tempDefaultDurationSec");

        assertEquals(0.25, SettingsValuePolicy.acceptedNumberValue(size, "0.25"));
        assertEquals(3.0, SettingsValuePolicy.acceptedNumberValue(size, "3"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "0.24"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "3.01"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "NaN"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "Infinity"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, ""));
        assertEquals(3.0, SettingsValuePolicy.acceptedNumberValue(duration, "3"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(duration, "3.5"));
    }

    @Test
    void multiButtonRowsUseEqualColumnsAndTheSameOuterEdges() {
        int controlRight = 400;
        int clusterWidth = 232;

        assertEquals(112, SettingsScreen.actionGridButtonWidth(clusterWidth, 2));
        assertEquals(72, SettingsScreen.actionGridButtonWidth(clusterWidth, 3));
        assertEquals(168, SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 2, 0));
        assertEquals(168, SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 3, 0));
        assertEquals(controlRight,
                SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 2, 1)
                        + SettingsScreen.actionGridButtonWidth(clusterWidth, 2));
        assertEquals(controlRight,
                SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 3, 2)
                        + SettingsScreen.actionGridButtonWidth(clusterWidth, 3));
    }

    @Test
    void tooltipNormalizationCollapsesLinesButKeepsParagraphBreaks() {
        assertEquals("", SettingsText.normalizeTooltip(null));
        assertEquals("one two", SettingsText.normalizeTooltip("one\ntwo"));
        assertEquals("one\n\ntwo", SettingsText.normalizeTooltip("one\n\ntwo"));
        assertEquals("a b\n\nc", SettingsText.normalizeTooltip("  a \r\n b \r\n\r\n c "));
    }

    @Test
    void descriptionlessSettingTooltipIsOnlyTheGrayControlLabel() {
        Setting setting = SettingsCatalog.byId("hideReachedStaticWaypointsUntilCycleComplete");

        Component tooltip = SettingsText.tooltip(setting);

        assertEquals(setting.label(), tooltip.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), tooltip.getStyle().getColor());
    }
}
