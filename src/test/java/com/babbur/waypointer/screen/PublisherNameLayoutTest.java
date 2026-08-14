package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherNameLayoutTest {
    @Test
    void ordinaryWindowKeepsFullEntryCopyAndSeparatedActions() {
        PublisherNameLayout.Layout layout = PublisherNameLayout.calculate(320, 240, 120);

        assertTrue(layout.entryDetailsVisible());
        assertInsideScreen(layout, 320, 240);
        assertActionsSeparated(layout);
        assertTrue(layout.fieldY() + BTN_H < layout.footerY());
    }

    @Test
    void narrowWindowShrinksActionsWithoutPanelOverflow() {
        PublisherNameLayout.Layout layout = PublisherNameLayout.calculate(200, 140, 160);

        assertInsideScreen(layout, 200, 140);
        assertActionsSeparated(layout);
        assertTrue(layout.fieldY() + BTN_H <= layout.footerY());
    }

    @Test
    void shortWindowKeepsCoreControlsAndOmitsSecondaryCopy() {
        PublisherNameLayout.Layout layout = PublisherNameLayout.calculate(200, 100, 120);

        assertInsideScreen(layout, 200, 100);
        assertActionsSeparated(layout);
        assertFalse(layout.entryDetailsVisible());
        assertFalse(layout.questionVisible());
        assertFalse(layout.warningVisible());
        assertTrue(layout.fieldY() + BTN_H <= layout.footerY());
        assertTrue(layout.cardY() + 28 + GuiTokens.GAP <= layout.footerY());
    }

    private static void assertInsideScreen(
            PublisherNameLayout.Layout layout, int width, int height) {
        assertTrue(layout.panelX() >= 0);
        assertTrue(layout.panelY() >= 0);
        assertTrue(layout.panelX() + layout.panelWidth() <= width);
        assertTrue(layout.panelBottom() <= height);
        assertTrue(layout.primaryX() + layout.primaryWidth() <= layout.contentRight());
    }

    private static void assertActionsSeparated(PublisherNameLayout.Layout layout) {
        assertTrue(layout.secondaryX() + layout.secondaryWidth() <= layout.primaryX());
    }
}
