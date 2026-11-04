package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublication;

import java.util.List;
import java.util.Objects;

final class PublishedRoutesUiState {
    private PublishedRoutesUiState() {
    }

    static List<CatalogPublication> forApiRoot(
            List<CatalogPublication> publications, String apiRoot) {
        if (publications == null || apiRoot == null) return List.of();
        return publications.stream()
                .filter(Objects::nonNull)
                .filter(record -> apiRoot.equals(record.apiRoot()))
                .toList();
    }
}
