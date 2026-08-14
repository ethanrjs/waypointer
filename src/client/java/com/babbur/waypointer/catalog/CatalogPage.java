package com.babbur.waypointer.catalog;

import java.util.List;

public record CatalogPage(List<CatalogRouteSummary> routes, boolean hasMore, String nextCursor) {
    public CatalogPage {
        routes = List.copyOf(routes);
    }
}
