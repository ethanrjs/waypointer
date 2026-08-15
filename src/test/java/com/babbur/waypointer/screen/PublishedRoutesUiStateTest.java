package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRoutesUiStateTest {
    @Test
    void destructiveActionsRequireSelectionIdentityAndIdleDeleteState() {
        PublishedRoutesModel model = new PublishedRoutesModel();
        model.replace(List.of(publication("one", "https://one")));
        model.select("one");
        assertEquals(1, model.rowsPerPage());
        assertEquals(1, model.publications().size());
        assertEquals("one", model.selectedRouteId());

        PublishedRoutesUiState.Controls noIdentity = PublishedRoutesUiState.controls(
                model, false, false);
        assertFalse(noIdentity.copyEnabled());
        assertFalse(noIdentity.deleteEnabled());

        PublishedRoutesUiState.Controls ready = PublishedRoutesUiState.controls(
                model, true, false);
        assertTrue(ready.copyEnabled());
        assertTrue(ready.deleteEnabled());

        PublishedRoutesUiState.Controls deleting = PublishedRoutesUiState.controls(
                model, true, true);
        assertFalse(deleting.copyEnabled());
        assertFalse(deleting.deleteEnabled());
        assertFalse(deleting.previousEnabled());
        assertFalse(deleting.nextEnabled());
    }

    @Test
    void pagerActionsFollowTheModelPageAndLockDuringDelete() {
        PublishedRoutesModel model = new PublishedRoutesModel();
        model.setRowsPerPage(1);
        model.replace(List.of(
                publication("one", "https://one"),
                publication("two", "https://one")));

        PublishedRoutesUiState.Controls first = PublishedRoutesUiState.controls(
                model, true, false);
        assertFalse(first.previousEnabled());
        assertTrue(first.nextEnabled());

        model.changePage(1);
        PublishedRoutesUiState.Controls last = PublishedRoutesUiState.controls(
                model, true, false);
        assertTrue(last.previousEnabled());
        assertFalse(last.nextEnabled());
    }

    @Test
    void loadAndPostDeleteRefreshKeepOnlyTheCurrentApiRoot() {
        CatalogPublication first = publication("one", "https://one");
        CatalogPublication second = publication("two", "https://two");

        assertEquals(List.of(first), PublishedRoutesUiState.forApiRoot(
                List.of(first, second), "https://one"));
        assertTrue(PublishedRoutesUiState.forApiRoot(null, "https://one").isEmpty());
        assertTrue(PublishedRoutesUiState.forApiRoot(List.of(first), null).isEmpty());
    }

    @Test
    void publishedDateIsTheCalendarPortionOfTheServerTimestamp() {
        assertEquals("2026-01-01",
                PublishedRoutesUiState.publishedDate("2026-01-01T00:00:00Z"));
        assertEquals("2026-08-14", PublishedRoutesUiState.publishedDate("2026-08-14"));
        assertEquals("", PublishedRoutesUiState.publishedDate(null));
        assertEquals("", PublishedRoutesUiState.publishedDate(""));
        assertEquals("", PublishedRoutesUiState.publishedDate("2026-08"));
    }

    private static CatalogPublication publication(String routeId, String apiRoot) {
        return new CatalogPublication(
                routeId, "publisher", "Publisher", "Title " + routeId,
                CatalogPublishRequest.Visibility.PUBLIC,
                "crystal_hollows", 1, 9, "2026-01-01T00:00:00Z",
                "/r/" + routeId, apiRoot, "sha256", Instant.EPOCH);
    }
}
