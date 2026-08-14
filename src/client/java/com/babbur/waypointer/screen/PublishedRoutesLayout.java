package com.babbur.waypointer.screen;

final class PublishedRoutesLayout {
    private PublishedRoutesLayout() {
    }

    static int rowsPerPage(
            int rowsTop, int pagerTop, int rowHeight, int rowGap, int sectionGap) {
        int rowsBottom = pagerTop - sectionGap;
        int availableHeight = rowsBottom - rowsTop;
        return Math.max(1, (availableHeight + rowGap) / (rowHeight + rowGap));
    }

    static int rowsBottom(int rowsTop, int rowsPerPage, int rowHeight, int rowGap) {
        return rowsTop + rowsPerPage * rowHeight
                + Math.max(0, rowsPerPage - 1) * rowGap;
    }
}
