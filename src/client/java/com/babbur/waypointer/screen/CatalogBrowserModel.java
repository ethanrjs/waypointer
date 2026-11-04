package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.util.MathUtil;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class CatalogBrowserModel {

    enum Sort {
        RECOMMENDED("recommended"),
        POPULAR("popular"), NEWEST("newest"), UPDATED("updated"), WAYPOINTS("waypoints"), NAME("name");

        final String id;

        Sort(String id) { this.id = id; }

        Comparator<CatalogRouteSummary> comparator() {
            Comparator<CatalogRouteSummary> byTitle = Comparator.comparing(
                    route -> route.title().toLowerCase(Locale.ROOT));
            return switch (this) {
                case RECOMMENDED -> (a, b) -> 0;
                case POPULAR -> Comparator.comparingLong(CatalogRouteSummary::downloads).reversed()
                        .thenComparing(CatalogRouteSummary::updatedAt, Comparator.reverseOrder())
                        .thenComparing(byTitle);
                case NEWEST -> Comparator.comparing(CatalogRouteSummary::createdAt, Comparator.reverseOrder())
                        .thenComparing(byTitle);
                case UPDATED -> Comparator.comparing(CatalogRouteSummary::updatedAt, Comparator.reverseOrder())
                        .thenComparing(byTitle);
                case WAYPOINTS -> Comparator.comparingInt(CatalogRouteSummary::waypointCount).reversed()
                        .thenComparing(byTitle);
                case NAME -> byTitle;
            };
        }
    }

    private List<CatalogRouteSummary> routes = List.of();
    private Sort sort = Sort.RECOMMENDED;
    private String tagFilter;
    private List<CatalogRouteSummary> loadedRoutes = List.of();
    private boolean filteringInstalled;
    private String query = "";
    private String zoneFilter;
    private List<String> here = List.of();
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

    String zoneFilter() {
        return zoneFilter;
    }

    boolean setZoneFilter(String zoneId) {
        String next = zoneId == null || zoneId.isBlank() ? null : zoneId;
        if (Objects.equals(next, zoneFilter)) return false;
        zoneFilter = next;
        loadedRoutes = routes = List.of();
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
        loadedRoutes = routes = List.of();
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
        loadedRoutes = routes = List.of();
        nextCursor = null;
        selectedRouteId = null;
        scrollOffset = 0;
    }

    void applyPage(CatalogPage page, boolean append) {
        Objects.requireNonNull(page, "page");
        if (append) {
            LinkedHashMap<String, CatalogRouteSummary> combined = new LinkedHashMap<>();
            for (CatalogRouteSummary route : loadedRoutes) combined.put(route.id(), route);
            for (CatalogRouteSummary route : page.routes()) combined.put(route.id(), route);
            routes = List.copyOf(combined.values());
        } else {
            routes = List.copyOf(page.routes());
        }
        nextCursor = page.hasMore() && page.nextCursor() != null
                && !page.nextCursor().isBlank() ? page.nextCursor() : null;
        loadedRoutes = routes;
        reconcileSelection();
    }

    Sort sort() {
        return sort;
    }

    boolean setSort(Sort next) {
        if (next == null || next == sort) return false;
        sort = next;
        scrollOffset = 0;
        return true;
    }

    String tagFilter() {
        return tagFilter;
    }

    boolean setTagFilter(String tag) {
        if (Objects.equals(tag, tagFilter)) return false;
        tagFilter = tag;
        scrollOffset = 0;
        return true;
    }

    boolean anyLoadedRouteHas(String tag) {
        return loadedRoutes.stream().anyMatch(route -> route.hasTag(tag));
    }

    void setHere(List<String> zones) {
        here = zones == null ? List.of() : List.copyOf(zones);
    }

    void filterInstalled(String apiRoot, ActiveGroupManager manager, boolean onlyNotInstalled) {
        if (filteringInstalled != onlyNotInstalled) scrollOffset = 0;
        filteringInstalled = onlyNotInstalled;
        Map<String, CatalogInstallState.Action> actions = new HashMap<>();
        Function<CatalogRouteSummary, CatalogInstallState.Action> action =
                route -> actions.computeIfAbsent(route.id(),
                        id -> manager == null ? null : installState(apiRoot, manager, route).action());
        List<CatalogRouteSummary> filtered = loadedRoutes.stream()
                .filter(route -> tagFilter == null || route.hasTag(tagFilter))
                .filter(route -> {
                    if (!onlyNotInstalled) return true;
                    CatalogInstallState.Action state = action.apply(route);
                    return state != CatalogInstallState.Action.INSTALLED
                            && state != CatalogInstallState.Action.LOCAL_NEWER;
                })
                .toList();
        List<CatalogRouteSummary> visible = sort == Sort.RECOMMENDED
                ? CatalogRecommendation.recommended(filtered, action, here, !query.isBlank(), Instant.now())
                : CatalogRecommendation.installedLast(filtered.stream().sorted(sort.comparator()).toList(), action);
        if (routes.equals(visible)) return;
        routes = visible;
        reconcileSelection();
    }

    void markLoadFailed(boolean append) {
        if (!append) loadedRoutes = routes = List.of();
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
