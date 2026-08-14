package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublication;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRoutesModelTest {
    @Test
    void pageChangeClearsSelectionAndHiddenIdsCannotBeSelected() {
        PublishedRoutesModel model = modelWithFiveRoutes();
        model.setRowsPerPage(2);

        assertTrue(model.select("route-0"));
        assertTrue(model.changePage(1));
        assertNull(model.selected());
        assertFalse(model.select("route-0"));
        assertNull(model.selected());
    }

    @Test
    void resizeClampsPageAndClearsHiddenSelection() {
        PublishedRoutesModel model = modelWithFiveRoutes();
        model.setRowsPerPage(2);
        model.changePage(2);
        assertTrue(model.select("route-4"));

        model.setRowsPerPage(3);

        assertEquals(1, model.page());
        assertEquals(List.of("route-3", "route-4"), model.visiblePublications().stream()
                .map(CatalogPublication::routeId).toList());
        assertEquals("route-4", model.selected().routeId());

        model.setRowsPerPage(5);
        assertEquals(0, model.page());
        assertEquals("route-4", model.selected().routeId());
    }

    @Test
    void replacementClampsFinalPageAndDropsMissingSelection() {
        PublishedRoutesModel model = modelWithFiveRoutes();
        model.setRowsPerPage(2);
        model.changePage(2);
        assertTrue(model.select("route-4"));

        model.replace(List.of(publication(0), publication(1)));

        assertEquals(0, model.page());
        assertNull(model.selected());
        assertEquals(0, model.maximumPage());
    }

    private static PublishedRoutesModel modelWithFiveRoutes() {
        PublishedRoutesModel model = new PublishedRoutesModel();
        model.replace(List.of(
                publication(0), publication(1), publication(2), publication(3), publication(4)));
        return model;
    }

    private static CatalogPublication publication(int index) {
        return new CatalogPublication(
                "route-" + index, "publisher", "Publisher", "Title " + index,
                com.babbur.waypointer.catalog.CatalogPublishRequest.Visibility.PUBLIC,
                "crystal_hollows", 1, 9, "2026-01-01T00:00:00Z",
                "/r/route-" + index, "https://catalog.example", "sha256", Instant.EPOCH);
    }
}
