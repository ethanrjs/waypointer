package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublication;

import java.util.List;
import java.util.Objects;

final class PublishedRoutesUiState {
    private PublishedRoutesUiState() {
    }

    record Controls(
            boolean copyEnabled,
            boolean deleteEnabled,
            boolean previousEnabled,
            boolean nextEnabled) {
    }

    static Controls controls(
            PublishedRoutesModel model, boolean identityAvailable, boolean deleting) {
        boolean selected = model != null && model.selected() != null && identityAvailable;
        return new Controls(
                selected && !deleting,
                selected && !deleting,
                model != null && model.page() > 0 && !deleting,
                model != null && model.page() < model.maximumPage() && !deleting);
    }

    static List<CatalogPublication> forApiRoot(
            List<CatalogPublication> publications, String apiRoot) {
        if (publications == null || apiRoot == null) return List.of();
        return publications.stream()
                .filter(Objects::nonNull)
                .filter(record -> apiRoot.equals(record.apiRoot()))
                .toList();
    }

    /** Calendar date of an ISO-8601 timestamp, or empty when it is missing or short. */
    static String publishedDate(String serverCreatedAt) {
        if (serverCreatedAt == null || serverCreatedAt.length() < 10) return "";
        return serverCreatedAt.substring(0, 10);
    }
}
