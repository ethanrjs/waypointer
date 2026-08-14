package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublication;

import java.util.List;

final class PublishedRoutesModel {
    private List<CatalogPublication> publications = List.of();
    private String selectedRouteId;
    private int page;
    private int rowsPerPage = 1;

    void replace(List<CatalogPublication> replacement) {
        publications = List.copyOf(replacement);
        clampPage();
        clearSelectionUnlessVisible();
    }

    void setRowsPerPage(int value) {
        if (value < 1) throw new IllegalArgumentException("rowsPerPage must be positive");
        if (rowsPerPage == value) return;
        rowsPerPage = value;
        clampPage();
        clearSelectionUnlessVisible();
    }

    List<CatalogPublication> publications() {
        return publications;
    }

    List<CatalogPublication> visiblePublications() {
        int start = page * rowsPerPage;
        int end = Math.min(publications.size(), start + rowsPerPage);
        return publications.subList(start, end);
    }

    boolean select(String routeId) {
        boolean visible = visiblePublications().stream()
                .anyMatch(publication -> publication.routeId().equals(routeId));
        selectedRouteId = visible ? routeId : null;
        return visible;
    }

    CatalogPublication selected() {
        if (selectedRouteId == null) return null;
        return visiblePublications().stream()
                .filter(publication -> publication.routeId().equals(selectedRouteId))
                .findFirst()
                .orElse(null);
    }

    boolean changePage(int delta) {
        int replacement = Math.max(0, Math.min(maximumPage(), page + delta));
        if (replacement == page) return false;
        page = replacement;
        selectedRouteId = null;
        return true;
    }

    int page() {
        return page;
    }

    int rowsPerPage() {
        return rowsPerPage;
    }

    int maximumPage() {
        return publications.isEmpty() ? 0 : (publications.size() - 1) / rowsPerPage;
    }

    String selectedRouteId() {
        return selectedRouteId;
    }

    private void clampPage() {
        page = Math.max(0, Math.min(page, maximumPage()));
    }

    private void clearSelectionUnlessVisible() {
        if (selected() == null) selectedRouteId = null;
    }
}
