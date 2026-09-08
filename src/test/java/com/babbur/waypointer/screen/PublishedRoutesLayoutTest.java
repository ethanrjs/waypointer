package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRoutesLayoutTest {
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
