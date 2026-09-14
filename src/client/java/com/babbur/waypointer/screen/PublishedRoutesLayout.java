package com.babbur.waypointer.screen;

final class PublishedRoutesLayout {
    private PublishedRoutesLayout() {
    }

    static int columns(int contentWidth, int gap) {
        return Math.max(1, (contentWidth + gap) / (220 + gap));
    }

    static int cardWidth(int contentWidth, int columns, int gap) {
        return Math.max(1, (contentWidth - (columns - 1) * gap) / columns);
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
