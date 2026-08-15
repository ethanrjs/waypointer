package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void debounceCountsTicksOfKeystrokeSilenceAndFiresExactlyOnce() {
        int ticks = 6;
        int fired = 0;
        for (int tick = 0; tick < 10; tick++) {
            int before = ticks;
            ticks = RouteCatalogScreen.advanceSearchDebounce(before);
            if (RouteCatalogScreen.searchDebounceFired(before, ticks)) fired++;
        }
        assertEquals(0, ticks);
        assertEquals(1, fired);
        assertFalse(RouteCatalogScreen.searchDebounceFired(
                0, RouteCatalogScreen.advanceSearchDebounce(0)));
    }

    @Test
    void highlightAnchorRecoversTheSelectionAnchorAroundTheCursor() {
        assertEquals(3, RouteCatalogScreen.searchHighlightAnchor("abcdef", 3, ""));
        assertEquals(3, RouteCatalogScreen.searchHighlightAnchor("abcdef", 3, null));
        assertEquals(5, RouteCatalogScreen.searchHighlightAnchor("abcdef", 2, "cde"));
        assertEquals(0, RouteCatalogScreen.searchHighlightAnchor("abcdef", 3, "abc"));
        assertEquals(0, RouteCatalogScreen.searchHighlightAnchor("aaa", 3, "aaa"));
    }

    @Test
    void searchCacheIsBoundedAndEvictsTheLeastRecentlyUsedQuery() {
        java.util.LinkedHashMap<String, Integer> cache =
                RouteCatalogScreen.boundedPageCache(2);
        cache.put(RouteCatalogScreen.searchCacheKey(null, "a"), 1);
        cache.put(RouteCatalogScreen.searchCacheKey(null, "b"), 2);
        cache.get(RouteCatalogScreen.searchCacheKey(null, "a"));
        cache.put(RouteCatalogScreen.searchCacheKey(null, "c"), 3);

        assertEquals(2, cache.size());
        assertTrue(cache.containsKey(RouteCatalogScreen.searchCacheKey(null, "a")));
        assertFalse(cache.containsKey(RouteCatalogScreen.searchCacheKey(null, "b")));
        assertFalse(RouteCatalogScreen.searchCacheKey("hub", "")
                .equals(RouteCatalogScreen.searchCacheKey(null, "hub")));
    }

    @Test
    void zoneFilterOptionsStartWithAllZonesAndListEveryPublishableZoneOnce() {
        List<String> ids = RouteCatalogScreen.zoneFilterDropdownIds();
        assertEquals("", ids.get(0));
        assertTrue(ids.contains("hub"));
        assertTrue(ids.contains("crystal_hollows"));
        assertFalse(ids.contains("unknown"));
        assertFalse(ids.contains("private_world"));
        assertFalse(ids.contains("mineshaft"));
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size());
    }

    @Test
    void emptyListCopyDistinguishesSearchZoneFilterAndTrueEmptiness() {
        assertEquals("waypointer.screen.route_catalog.empty",
                RouteCatalogScreen.emptyListKey(true, false));
        assertEquals("waypointer.screen.route_catalog.empty.zone",
                RouteCatalogScreen.emptyListKey(true, true));
        assertEquals("waypointer.screen.route_catalog.empty.search",
                RouteCatalogScreen.emptyListKey(false, false));
        assertEquals("waypointer.screen.route_catalog.empty.search",
                RouteCatalogScreen.emptyListKey(false, true));
    }

    @Test
    void zoneDropdownLayoutStaysInsideThePanelAndSizesToItsRows() {
        RouteCatalogScreen.ZoneDropdownGeometry geo =
                RouteCatalogScreen.zoneDropdownGeometry(200, 40, 100, 12, 600, 400, 3);
        assertEquals(200, geo.x1());
        assertEquals(350, geo.x2());
        assertEquals(44, geo.y1());
        assertEquals(45, geo.rowsTop());
        assertEquals(45 + 3 * 22, geo.rowsBottom());
        assertEquals(geo.rowsBottom() + 1, geo.y2());

        RouteCatalogScreen.ZoneDropdownGeometry clipped =
                RouteCatalogScreen.zoneDropdownGeometry(500, 40, 100, 12, 600, 400, 60);
        assertEquals(12 + 600 - 1, clipped.x2());
        assertTrue(clipped.y2() <= 400 - 4 + 1);
        assertTrue((clipped.rowsBottom() - clipped.rowsTop()) % 22 == 0);

        RouteCatalogScreen.ZoneDropdownGeometry tiny =
                RouteCatalogScreen.zoneDropdownGeometry(20, 40, 100, 12, 600, 40, 60);
        assertEquals(22, tiny.rowsBottom() - tiny.rowsTop());
    }

    @Test
    void zoneDropdownScrollCentersTheSelectionAndClampsAtTheEdges() {
        assertEquals(0, RouteCatalogScreen.centeredDropdownScroll(-1, 60, 110));
        assertEquals(0, RouteCatalogScreen.centeredDropdownScroll(0, 60, 110));
        assertEquals(0, RouteCatalogScreen.centeredDropdownScroll(1, 60, 110));
        assertEquals(30 * 22 - (110 / 2 - 11),
                RouteCatalogScreen.centeredDropdownScroll(30, 60, 110));
        assertEquals(60 * 22 - 110,
                RouteCatalogScreen.centeredDropdownScroll(59, 60, 110));
    }

    @Test
    void dropdownScrollbarThumbOnlyAppearsWhenContentOverflows() {
        assertNull(RouteCatalogScreen.dropdownThumb(110, 66, 0, 0));
        int[] top = RouteCatalogScreen.dropdownThumb(110, 1320, 0, 1210);
        int[] bottom = RouteCatalogScreen.dropdownThumb(110, 1320, 1210, 1210);
        assertEquals(0, top[0]);
        assertEquals(110 - bottom[1], bottom[0]);
        assertTrue(bottom[1] >= 8);
        assertEquals(0, RouteCatalogScreen.dropdownThumb(110, 200, 0, 0)[0]);
    }

    @Test
    void manualRefreshCooldownCountsWholeSecondsAndCapsAtTen() {
        assertEquals(0, RouteCatalogScreen.refreshCooldownSeconds(false, 0, 5_000_000_000L));
        assertEquals(0, RouteCatalogScreen.refreshCooldownSeconds(true, 6_000_000_000L,
                5_000_000_000L));
        assertEquals(1, RouteCatalogScreen.refreshCooldownSeconds(true, 4_999_999_999L,
                5_000_000_000L));
        assertEquals(5, RouteCatalogScreen.refreshCooldownSeconds(true, 1_000_000_000L,
                5_500_000_000L));
        assertEquals(10, RouteCatalogScreen.refreshCooldownSeconds(true, 0,
                99_000_000_000L));
    }

    @Test
    void installedFocusOpensTheFirstImportedGroupThatStillExists() {
        com.babbur.waypointer.core.ActiveGroupManager manager =
                new com.babbur.waypointer.core.ActiveGroupManager();
        com.babbur.waypointer.core.WaypointGroup group =
                com.babbur.waypointer.core.WaypointGroup.create("Installed", "hub");
        manager.addAll(List.of(group));
        com.babbur.waypointer.api.ImportSummary summary =
                new com.babbur.waypointer.api.ImportSummary(
                        com.babbur.waypointer.api.ImportSource.WAYPOINTER, "Route",
                        1, 1, List.of("missing", group.id()));

        assertNull(RouteCatalogScreen.installedFocus(null, summary));
        assertNull(RouteCatalogScreen.installedFocus(manager, null));
        assertSame(group, RouteCatalogScreen.installedFocus(manager, summary));
        assertNull(RouteCatalogScreen.installedFocus(manager,
                new com.babbur.waypointer.api.ImportSummary(
                        com.babbur.waypointer.api.ImportSource.WAYPOINTER, "Route",
                        1, 1, List.of("missing"))));
    }

    @Test
    void filterSelectGeometryAlignsLabelWithDropdownRowsAndInsetsCaret() {
        int[] geo = RouteCatalogScreen.filterSelectGeometry(40, 30, 110, 20);
        assertEquals(40 + GuiTokens.GAP, geo[0], "label shares the dropdown row column");
        assertEquals(110 - 27, geo[1], "label stops 2px short of the caret");
        assertEquals(40 + 110 - 8, geo[2] + 5, "caret right edge mirrors the label inset");
        assertEquals(30 + 9, geo[3], "caret optically centered in a 20px control");

        // Label max width never collapses below a legible floor on narrow buttons.
        assertEquals(12, RouteCatalogScreen.filterSelectGeometry(0, 0, 30, 20)[1]);
    }

    private static CatalogRouteSummary summary() {
        return new CatalogRouteSummary(
                "AAAAAAAAAAAAAAAAAAAAAA", "Route", "Description", "Tester", "", false,
                "unlisted", "hub", "Hub", 1, 1, 9, 1,
                0, "", "", "/r/AAAAAAAAAAAAAAAAAAAAAA");
    }
}
