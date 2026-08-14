package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogBrowserModelTest {
    private static final String API_ROOT = "https://catalog.example/api/";
    private static final String HASH = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void pagesAppendByIdentityAndReconcileSelection() {
        CatalogBrowserModel model = new CatalogBrowserModel();
        CatalogRouteSummary first = summary("AAAAAAAAAAAAAAAAAAAAAA", "First", 1);
        CatalogRouteSummary second = summary("BBBBBBBBBBBBBBBBBBBBBB", "Second", 1);

        model.applyPage(new CatalogPage(List.of(first), true, "next"), false);
        assertEquals("next", model.nextCursor());
        assertTrue(model.select(first));
        assertSame(first, model.selectedRoute());

        CatalogRouteSummary replacement = summary(first.id(), "Replacement", 1);
        model.applyPage(new CatalogPage(List.of(replacement, second), false, null), true);

        assertEquals(List.of(replacement, second), model.routes());
        assertSame(replacement, model.selectedRoute());
        assertNull(model.nextCursor());

        model.applyPage(new CatalogPage(List.of(second), false, null), false);
        assertNull(model.selectedRoute());
    }

    @Test
    void pendingSearchClearsTheOldInstallTargetAndSubmitsOnlyOnce() {
        CatalogBrowserModel model = new CatalogBrowserModel();
        CatalogRouteSummary route = summary("AAAAAAAAAAAAAAAAAAAAAA", "Route", 1);
        CatalogRouteSummary second = summary("BBBBBBBBBBBBBBBBBBBBBB", "Second", 1);
        model.applyPage(new CatalogPage(List.of(route, second), true, "next"), false);
        model.select(route);
        model.scrollBy(1, 1);

        assertTrue(model.editSearch(" crystal "));
        assertTrue(model.searchPending());
        assertTrue(model.routes().isEmpty());
        assertNull(model.nextCursor());
        assertNull(model.selectedRoute());
        assertEquals(0, model.scrollOffset());
        assertEquals("crystal", model.normalizedQuery());
        assertTrue(model.submitPendingSearch());
        assertFalse(model.searchPending());
        assertFalse(model.submitPendingSearch());
    }

    @Test
    void scrollingClampsAndKeepsKeyboardSelectionVisible() {
        CatalogBrowserModel model = new CatalogBrowserModel();
        List<CatalogRouteSummary> routes = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> summary(
                        "AAAAAAAAAAAAAAAAAAAAA" + index, "Route " + index, 1))
                .toList();
        model.applyPage(new CatalogPage(routes, false, null), false);

        assertTrue(model.scrollBy(50, 3));
        assertEquals(7, model.scrollOffset());
        model.scrollIntoView(1, 3);
        assertEquals(1, model.scrollOffset());
        model.scrollIntoView(9, 3);
        assertEquals(7, model.scrollOffset());
    }

    @Test
    void installStateComesOnlyFromSavedGroupProvenance() {
        CatalogBrowserModel model = new CatalogBrowserModel();
        ActiveGroupManager manager = new ActiveGroupManager();
        CatalogRouteSummary route = summary("AAAAAAAAAAAAAAAAAAAAAA", "Route", 2);

        assertEquals(CatalogInstallState.Action.INSTALL,
                model.installState(API_ROOT, manager, route).action());

        WaypointGroup first = tagged(route, 0);
        WaypointGroup second = tagged(route, 1);
        manager.addAll(List.of(first, second));
        assertEquals(CatalogInstallState.Action.INSTALLED,
                model.installState(API_ROOT, manager, route).action());

        manager.remove(second.id());
        assertEquals(CatalogInstallState.Action.REPAIR,
                model.installState(API_ROOT, manager, route).action());
    }

    @Test
    void installTicketContractRejectsEveryMeaningfulMetadataChange() {
        CatalogRouteSummary route = summary("AAAAAAAAAAAAAAAAAAAAAA", "Route", 1);
        assertTrue(CatalogBrowserModel.sameRouteContract(route, route));
        assertFalse(CatalogBrowserModel.sameRouteContract(
                route, summary("BBBBBBBBBBBBBBBBBBBBBB", "Route", 1)));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                copy(route, route.version() + 1, route.codecVersion(),
                        route.groupCount(), route.waypointCount(), route.zoneId())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                copy(route, route.version(), 8,
                        route.groupCount(), route.waypointCount(), route.zoneId())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                copy(route, route.version(), route.codecVersion(),
                        2, route.waypointCount(), route.zoneId())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                copy(route, route.version(), route.codecVersion(),
                        route.groupCount(), 2, route.zoneId())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                copy(route, route.version(), route.codecVersion(),
                        route.groupCount(), route.waypointCount(), "garden")));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                textCopy(route, "Other", route.description(), route.authorName(),
                        route.publisherId(), route.visibility(), route.sharePath())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                textCopy(route, route.title(), "Other", route.authorName(),
                        route.publisherId(), route.visibility(), route.sharePath())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                textCopy(route, route.title(), route.description(), "Other",
                        route.publisherId(), route.visibility(), route.sharePath())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                textCopy(route, route.title(), route.description(), route.authorName(),
                        "wp_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        route.visibility(), route.sharePath())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                textCopy(route, route.title(), route.description(), route.authorName(),
                        route.publisherId(), "public", route.sharePath())));
        assertFalse(CatalogBrowserModel.sameRouteContract(route,
                textCopy(route, route.title(), route.description(), route.authorName(),
                        route.publisherId(), route.visibility(),
                        "/r/Zbcdefghijklmnopqrstuv")));
    }

    private static WaypointGroup tagged(CatalogRouteSummary route, int index) {
        WaypointGroup group = WaypointGroup.create("Installed", "hub");
        group.setCatalogProvenance(new CatalogRouteProvenance(
                API_ROOT, route.id(), route.version(), route.codecVersion(),
                HASH, index, route.groupCount()));
        return group;
    }

    private static CatalogRouteSummary summary(String id, String title, int groups) {
        return new CatalogRouteSummary(
                id, title, "A useful route description.", "Tester", "", false,
                "unlisted", "hub", "Hub", 1, groups, 9, 1,
                0, "", "", "/r/" + id);
    }

    private static CatalogRouteSummary copy(
            CatalogRouteSummary source, int version, int codecVersion,
            int groups, int waypoints, String zoneId) {
        return new CatalogRouteSummary(
                source.id(), source.title(), source.description(), source.authorName(),
                source.publisherId(), source.publisherVerified(), source.visibility(),
                zoneId, source.zoneLabel(), waypoints, groups, codecVersion, version,
                source.downloads(), source.createdAt(), source.updatedAt(), source.sharePath());
    }

    private static CatalogRouteSummary textCopy(
            CatalogRouteSummary source, String title, String description,
            String authorName, String publisherId, String visibility, String sharePath) {
        return new CatalogRouteSummary(
                source.id(), title, description, authorName, publisherId,
                source.publisherVerified(), visibility, source.zoneId(), source.zoneLabel(),
                source.waypointCount(), source.groupCount(), source.codecVersion(),
                source.version(), source.downloads(), source.createdAt(),
                source.updatedAt(), sharePath);
    }
}
