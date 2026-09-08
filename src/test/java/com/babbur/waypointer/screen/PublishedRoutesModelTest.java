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
    void searchIncludesLaterPagesAndFriendlyZonesAndSurvivesReplacement() {
        PublishedRoutesModel model = modelWithFiveRoutes();
        model.setRowsPerPage(2);
        model.changePage(1);
        model.setQuery("TITLE 4");
        assertEquals(0, model.page());
        assertEquals(List.of(publication(4)), model.visiblePublications());
        assertEquals("TITLE 4", model.query());

        model.replace(List.of(publication(0), publication(4)));
        assertEquals(List.of(publication(4)), model.publications());
        model.setQuery(" Crystal Hollows ");
        assertEquals(2, model.publications().size());
        model.setQuery("missing");
        assertTrue(model.publications().isEmpty());
        model.setQuery("");
        assertEquals(2, model.publications().size());
    }

    @Test
    void searchKeepsUnlistedBundles() {
        CatalogPublication route = publication(0);
        CatalogPublication bundle = new CatalogPublication(
                "unlisted-bundle", route.publisherId(), route.publisherName(),
                "Mining bundle", route.description(),
                com.babbur.waypointer.catalog.CatalogPublishRequest.Visibility.UNLISTED,
                "multiple", route.version(), route.codecVersion(), route.serverCreatedAt(),
                "/r/unlisted-bundle", route.apiRoot(), route.payloadSha256(), route.recordedAt());
        PublishedRoutesModel model = new PublishedRoutesModel();
        model.setRowsPerPage(1);
        model.replace(List.of(route, bundle));
        model.setQuery("bundle");
        assertEquals(List.of(bundle), model.visiblePublications());
    }

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
                "A route description.",
                com.babbur.waypointer.catalog.CatalogPublishRequest.Visibility.PUBLIC,
                "crystal_hollows", 1, 9, "2026-01-01T00:00:00Z",
                "/r/route-" + index, "https://catalog.example", "sha256", Instant.EPOCH);
    }
}
