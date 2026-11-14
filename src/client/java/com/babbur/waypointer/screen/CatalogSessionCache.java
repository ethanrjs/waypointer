package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catalog pages and map previews kept for the whole game session, per catalog API, so the
 * community browser opens on what it showed last time and only refreshes in the background.
 * Client thread only.
 */
final class CatalogSessionCache {

    static final int PAGE_LIMIT = 32;
    static final int PREVIEW_LIMIT = 256;
    /** A cached list younger than this is shown without asking the catalog again. */
    static final long FRESH_NANOS = 60_000_000_000L;

    private static final Map<String, CatalogSessionCache> BY_API_ROOT = new HashMap<>();

    final LinkedHashMap<String, CatalogPage> pages = RouteCatalogScreen.boundedPageCache(PAGE_LIMIT);
    final LinkedHashMap<String, CatalogRoutePreview> previews = RouteCatalogScreen.boundedPageCache(PREVIEW_LIMIT);
    private long pagesFetchedAtNanos = Long.MIN_VALUE;

    static CatalogSessionCache forApiRoot(String apiRoot) {
        return BY_API_ROOT.computeIfAbsent(apiRoot == null ? "" : apiRoot, root -> new CatalogSessionCache());
    }

    static void clearAll() {
        BY_API_ROOT.clear();
    }

    /** Previews are kept per route version, so an updated route never shows its old map. */
    static String previewKey(CatalogRouteSummary route) {
        return route.id() + '@' + route.version();
    }

    void markPagesFetched(long nowNanos) {
        pagesFetchedAtNanos = nowNanos;
    }

    boolean pagesFresh(long nowNanos) {
        return pagesFetchedAtNanos != Long.MIN_VALUE && nowNanos - pagesFetchedAtNanos < FRESH_NANOS;
    }

    void clearPages() {
        pages.clear();
        pagesFetchedAtNanos = Long.MIN_VALUE;
    }
}
