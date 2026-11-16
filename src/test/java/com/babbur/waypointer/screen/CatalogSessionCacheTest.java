package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogSessionCacheTest {

    @AfterEach
    void clear() {
        CatalogSessionCache.clearAll();
    }

    @Test
    void pagesOutliveTheScreenAndGoStaleAfterAMinute() {
        CatalogSessionCache session = CatalogSessionCache.forApiRoot("https://catalog.example/api/");
        assertSame(session, CatalogSessionCache.forApiRoot("https://catalog.example/api/"));
        assertNotSame(session, CatalogSessionCache.forApiRoot("https://other.example/api/"));
        assertFalse(session.pagesFresh(0));

        session.pages.put(RouteCatalogScreen.searchCacheKey(null, ""), new CatalogPage(List.of(), false, null));
        session.markPagesFetched(1_000L);
        assertTrue(session.pagesFresh(1_000L + CatalogSessionCache.FRESH_NANOS - 1));
        assertFalse(session.pagesFresh(1_000L + CatalogSessionCache.FRESH_NANOS));

        session.clearPages();
        assertTrue(session.pages.isEmpty());
        assertFalse(session.pagesFresh(1_000L));
    }
}
