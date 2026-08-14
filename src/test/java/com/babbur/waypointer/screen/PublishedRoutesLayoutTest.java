package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRoutesLayoutTest {
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
