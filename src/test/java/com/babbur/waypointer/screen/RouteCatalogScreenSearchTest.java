package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCatalogScreenSearchTest {
    @Test
    void editedSearchRebuildsTheClearedViewThenRestoresSearchFocus() {
        CatalogBrowserModel browser = new CatalogBrowserModel();
        browser.applyPage(new CatalogPage(List.of(summary()), true, "next"), false);
        List<String> events = new ArrayList<>();

        assertTrue(RouteCatalogScreen.applySearchEdit(
                browser, "crystal",
                () -> events.add("state"),
                () -> {
                    assertTrue(browser.routes().isEmpty());
                    events.add("rebuild");
                },
                () -> events.add("focus")));

        assertEquals(List.of("state", "rebuild", "focus"), events);
        assertFalse(RouteCatalogScreen.applySearchEdit(
                browser, "crystal",
                () -> events.add("unexpected-state"),
                () -> events.add("unexpected-rebuild"),
                () -> events.add("unexpected-focus")));
        assertEquals(List.of("state", "rebuild", "focus"), events);
    }

    private static CatalogRouteSummary summary() {
        return new CatalogRouteSummary(
                "AAAAAAAAAAAAAAAAAAAAAA", "Route", "Description", "Tester", "", false,
                "unlisted", "hub", "Hub", 1, 1, 9, 1,
                0, "", "", "/r/AAAAAAAAAAAAAAAAAAAAAA");
    }
}
