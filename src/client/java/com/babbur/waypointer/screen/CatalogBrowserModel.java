package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.util.MathUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class CatalogBrowserModel {
    private List<CatalogRouteSummary> routes = List.of();
    private String query = "";
    private String zoneFilter;
    private String nextCursor;
    private String selectedRouteId;
    private int scrollOffset;
    private boolean searchPending;

    List<CatalogRouteSummary> routes() {
        return routes;
    }

    String query() {
        return query;
    }

    String normalizedQuery() {
        return query.trim();
    }

    String nextCursor() {
        return nextCursor;
    }

    String selectedRouteId() {
        return selectedRouteId;
    }

    int scrollOffset() {
        return scrollOffset;
    }

    boolean searchPending() {
        return searchPending;
    }

    /** Server-side zone filter; {@code null} means every zone. */
    String zoneFilter() {
        return zoneFilter;
    }

    boolean setZoneFilter(String zoneId) {
        String next = zoneId == null || zoneId.isBlank() ? null : zoneId;
        if (Objects.equals(next, zoneFilter)) return false;
        zoneFilter = next;
        routes = List.of();
        nextCursor = null;
        selectedRouteId = null;
        scrollOffset = 0;
        return true;
    }

    boolean editSearch(String value) {
        String next = value == null ? "" : value;
        if (next.equals(query)) return false;
        query = next;
        searchPending = true;
        routes = List.of();
        nextCursor = null;
        selectedRouteId = null;
        scrollOffset = 0;
        return true;
    }

    boolean submitPendingSearch() {
        if (!searchPending) return false;
        searchPending = false;
        return true;
    }

    void beginRefresh() {
        searchPending = false;
        routes = List.of();
        nextCursor = null;
        selectedRouteId = null;
        scrollOffset = 0;
    }

    void applyPage(CatalogPage page, boolean append) {
        Objects.requireNonNull(page, "page");
        if (append) {
            LinkedHashMap<String, CatalogRouteSummary> combined = new LinkedHashMap<>();
            for (CatalogRouteSummary route : routes) combined.put(route.id(), route);
            for (CatalogRouteSummary route : page.routes()) combined.put(route.id(), route);
            routes = List.copyOf(combined.values());
        } else {
            routes = List.copyOf(page.routes());
        }
        nextCursor = page.hasMore() && page.nextCursor() != null
                && !page.nextCursor().isBlank() ? page.nextCursor() : null;
        reconcileSelection();
    }

    void markLoadFailed(boolean append) {
        if (!append) routes = List.of();
        reconcileSelection();
    }

    boolean select(CatalogRouteSummary route) {
        Objects.requireNonNull(route, "route");
        if (route.id().equals(selectedRouteId)) return false;
        selectedRouteId = route.id();
        return true;
    }

    CatalogRouteSummary selectedRoute() {
        if (selectedRouteId == null) return null;
        for (CatalogRouteSummary route : routes) {
            if (route.id().equals(selectedRouteId)) return route;
        }
        return null;
    }

    void reconcileSelection() {
        if (selectedRouteId != null && selectedRoute() == null) selectedRouteId = null;
    }

    void clampScroll(int visibleRows) {
        scrollOffset = MathUtil.clamp(
                scrollOffset, 0, Math.max(0, routes.size() - visibleRows));
    }

    boolean scrollBy(int rows, int visibleRows) {
        int next = MathUtil.clamp(scrollOffset + rows,
                0, Math.max(0, routes.size() - visibleRows));
        if (next == scrollOffset) return false;
        scrollOffset = next;
        return true;
    }

    void scrollIntoView(int index, int visibleRows) {
        int maximum = Math.max(0, routes.size() - visibleRows);
        int start = MathUtil.clamp(scrollOffset, 0, maximum);
        if (index < start) {
            start = index;
        } else if (index >= start + visibleRows) {
            start = index - visibleRows + 1;
        }
        scrollOffset = MathUtil.clamp(start, 0, maximum);
    }

    CatalogInstallState installState(
            String apiRoot, ActiveGroupManager manager, CatalogRouteSummary route) {
        Objects.requireNonNull(manager, "manager");
        if (route == null) {
            return new CatalogInstallState(
                    CatalogInstallState.Action.INSTALL, 0, List.of());
        }
        return CatalogInstallState.inspect(apiRoot, route, manager.allGroups());
    }

    static boolean sameRouteContract(
            CatalogRouteSummary requested, CatalogRouteSummary selected) {
        return requested != null
                && selected != null
                && requested.id().equals(selected.id())
                && requested.title().equals(selected.title())
                && requested.description().equals(selected.description())
                && requested.authorName().equals(selected.authorName())
                && requested.publisherId().equals(selected.publisherId())
                && requested.visibility().equals(selected.visibility())
                && requested.version() == selected.version()
                && requested.codecVersion() == selected.codecVersion()
                && requested.groupCount() == selected.groupCount()
                && requested.waypointCount() == selected.waypointCount()
                && requested.zoneId().equals(selected.zoneId())
                && requested.sharePath().equals(selected.sharePath());
    }
}
