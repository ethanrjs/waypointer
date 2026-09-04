package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteFolderEditLayoutTest {
    @Test
    void normalEditorKeepsOrderedControlsInsideACompactPanel() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(400, 240, true, true);

        assertTrue(layout.panelY() >= 0);
        assertTrue(layout.panelBottom() <= 240);
        assertTrue(layout.panelX() >= 0);
        assertTrue(layout.panelX() + layout.panelWidth() <= 400);
        assertTrue(layout.detailVisible());
        assertTrue(layout.fieldLabelsVisible());
        assertTrue(layout.titleY() >= layout.panelY());
        assertTrue(layout.titleY() < layout.detailY());
        assertTrue(layout.detailY() < layout.sectionDividerY());
        assertTrue(layout.sectionDividerY() < layout.nameLabelY());
        assertTrue(layout.nameLabelY() < layout.nameFieldY());
        assertTrue(layout.nameFieldY() + BTN_H <= layout.colorLabelY());
        assertTrue(layout.colorLabelY() < layout.colorControlY());
        assertTrue(layout.colorControlY() + BTN_H <= layout.footerY());
        assertTrue(layout.footerY()
                - (layout.colorControlY() + BTN_H) <= 28);
        assertActionsFit(layout);
    }

    @Test
    void emptyCreateFolderHidesTheSelectionDetailLine() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(400, 240, false, false);

        assertFalse(layout.detailVisible());
        RouteFolderEditLayout.Layout selected =
                RouteFolderEditLayout.calculate(400, 240, false, true);
        assertTrue(layout.panelHeight() < selected.panelHeight());
        assertTrue(layout.nameFieldY() - layout.titleY()
                < selected.nameFieldY() - selected.titleY());
        assertTrue(layout.colorControlY() + BTN_H < layout.footerY());
        assertActionsFit(layout);
    }

    @Test
    void selectedCreateFolderKeepsTheRouteCountDetailLine() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(400, 240, false, true);

        assertTrue(layout.detailVisible());
    }

    @Test
    void wideEditorKeepsAllThreeActionsOnOneFooterRow() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(400, 220, true, true);

        assertFalse(layout.wrappedActions());
        assertEquals(1, layout.actions().stream()
                .map(RouteFolderEditLayout.ActionPlacement::y).distinct().count());
        assertEquals(3, layout.actions().size());
        assertActionsFit(layout);
    }

    @Test
    void twoHundredFortyPixelEditorFitsThreeActionsWithoutOverlap() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(240, 200, true, true);

        assertFalse(layout.wrappedActions());
        assertTrue(layout.panelX() >= 0);
        assertTrue(layout.panelX() + layout.panelWidth() <= 240);
        assertEquals(1, layout.actions().stream()
                .map(RouteFolderEditLayout.ActionPlacement::y).distinct().count());
        assertNotNull(layout.action(RouteFolderEditLayout.Action.CANCEL));
        assertNotNull(layout.action(RouteFolderEditLayout.Action.SAVE));
        assertNotNull(layout.action(RouteFolderEditLayout.Action.DELETE));
        assertActionsFit(layout);
    }

    @Test
    void panelAndActionsFitScreensNarrowerThanLegacyMinimum() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(200, 160, true, true);

        assertTrue(layout.panelWidth() <= 200);
        assertTrue(layout.panelX() >= 0);
        assertTrue(layout.nameFieldY() + BTN_H
                <= layout.actions().stream()
                        .mapToInt(RouteFolderEditLayout.ActionPlacement::y).min().orElseThrow());
        assertActionsFit(layout);
    }

    @Test
    void shortEditorKeepsFieldsAndFooterInsidePanel() {
        RouteFolderEditLayout.Layout layout =
                RouteFolderEditLayout.calculate(240, 140, true, true);
        int firstActionY = layout.actions().stream()
                .mapToInt(RouteFolderEditLayout.ActionPlacement::y).min().orElseThrow();

        assertTrue(layout.nameFieldY() + BTN_H <= firstActionY);
        assertTrue(layout.colorControlY() + BTN_H <= firstActionY);
        assertTrue(layout.validationVisible());
        assertTrue(layout.footerY() + BTN_H <= layout.panelBottom());
        assertTrue(layout.panelBottom() <= 140);
        assertActionsFit(layout);
    }

    @Test
    void generatedFolderNameUsesLocalizedFormat() {
        assertEquals("Folder 3", RouteFolderEditScreen.defaultFolderName(3));
    }

    @Test
    void folderColorAcceptsSixDigitHexAndFormatsTheStoredRgbValue() {
        assertEquals(0x4FB3C4, RouteFolderEditScreen.parseColor("#4fb3c4"));
        assertEquals(0xC46DFF, RouteFolderEditScreen.parseColor(" C46DFF "));
        assertEquals("#4FB3C4", RouteFolderEditScreen.formatColor(0xAA4FB3C4));
        assertEquals(null, RouteFolderEditScreen.parseColor("#FFF"));
        assertEquals(null, RouteFolderEditScreen.parseColor("#GG00AA"));
    }

    private static void assertActionsFit(RouteFolderEditLayout.Layout layout) {
        for (RouteFolderEditLayout.ActionPlacement placement : layout.actions()) {
            assertTrue(placement.x() >= layout.contentX());
            assertTrue(placement.right() <= layout.contentRight());
            assertTrue(placement.y() >= layout.panelY());
            assertTrue(placement.y() + BTN_H <= layout.panelBottom());
        }
        for (int y : layout.actions().stream()
                .map(RouteFolderEditLayout.ActionPlacement::y).distinct().toList()) {
            List<RouteFolderEditLayout.ActionPlacement> row = layout.actions().stream()
                    .filter(placement -> placement.y() == y)
                    .sorted(java.util.Comparator.comparingInt(
                            RouteFolderEditLayout.ActionPlacement::x))
                    .toList();
            for (int index = 1; index < row.size(); index++) {
                assertTrue(row.get(index - 1).right() <= row.get(index).x());
            }
        }
    }
}
