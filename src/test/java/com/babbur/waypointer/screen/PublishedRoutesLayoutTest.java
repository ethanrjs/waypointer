package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRoutesLayoutTest {
    @Test
    void galleryAddsColumnsAndRowsWithoutCrossingTheContentBounds() {
        for (int width : new int[] {264, 440, 640, 960}) {
            int columns = PublishedRoutesLayout.columns(width, 8);
            int cardWidth = PublishedRoutesLayout.cardWidth(width, columns, 8);
            assertTrue(columns * cardWidth + (columns - 1) * 8 <= width);
            assertTrue(cardWidth >= 220);
            int rows = PublishedRoutesLayout.rowsPerPage(62, 320, 76, 8, 8);
            assertEquals(3, rows);
            assertTrue(PublishedRoutesLayout.rowsBottom(62, rows, 76, 8) <= 312);
        }
        assertEquals(1, PublishedRoutesLayout.columns(440, 8));
        assertEquals(2, PublishedRoutesLayout.columns(640, 8));
        assertEquals(4, PublishedRoutesLayout.columns(960, 8));
    }

    @Test
    void searchAndTwoLineRowsFitTheSmallestAndFullPanels() {
        for (int panelHeight : new int[] {190, 270, 320}) {
            int rowsTop = 34 + 20 + 8;
            int pagerTop = panelHeight - 16 - 20 - 14 - 20 - 8;
            int rows = PublishedRoutesLayout.rowsPerPage(rowsTop, pagerTop, 34, 2, 8);
            assertTrue(PublishedRoutesLayout.rowsBottom(rowsTop, rows, 34, 2)
                    <= pagerTop - 8);
        }
    }

    @Test
    void normalPanelKeepsFiveRowsAbovePager() {
        int rows = PublishedRoutesLayout.rowsPerPage(46, 192, 24, 2, 8);

        assertEquals(5, rows);
        assertTrue(PublishedRoutesLayout.rowsBottom(46, rows, 24, 2) <= 192 - 8);
    }

    @Test
    void minimumPanelKeepsTwoRowsAbovePager() {
        int rows = PublishedRoutesLayout.rowsPerPage(46, 112, 24, 2, 8);

        assertEquals(2, rows);
        assertTrue(PublishedRoutesLayout.rowsBottom(46, rows, 24, 2) <= 112 - 8);
    }
}
