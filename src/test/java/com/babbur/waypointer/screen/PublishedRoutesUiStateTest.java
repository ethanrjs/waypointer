package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRoutesUiStateTest {
    @Test
    void loadAndPostDeleteRefreshKeepOnlyTheCurrentApiRoot() {
        CatalogPublication first = publication("one", "https://one");
        CatalogPublication second = publication("two", "https://two");

        assertEquals(List.of(first), PublishedRoutesUiState.forApiRoot(
                List.of(first, second), "https://one"));
        assertTrue(PublishedRoutesUiState.forApiRoot(null, "https://one").isEmpty());
        assertTrue(PublishedRoutesUiState.forApiRoot(List.of(first), null).isEmpty());
    }

    private static CatalogPublication publication(String routeId, String apiRoot) {
        return new CatalogPublication(
                routeId, "publisher", "Publisher", "Title " + routeId,
                "A route description.",
                CatalogPublishRequest.Visibility.PUBLIC,
                "crystal_hollows", 1, 9, "2026-01-01T00:00:00Z",
                "/r/" + routeId, apiRoot, "sha256", Instant.EPOCH);
    }
}
