package com.babbur.waypointer.catalog;

import com.babbur.waypointer.core.ActiveGroupManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only service check; installs into an isolated in-memory route manager. */
@EnabledIfEnvironmentVariable(named = "WAYPOINTER_CATALOG_LIVE", matches = "true")
class CatalogLiveReadTest {
    @Test void publicRoutesCanBeFetchedDecodedAndInstalled() {
        var client = new RouteCatalogClient("1.11.0");
        var page = client.listRoutes().join();
        assertFalse(page.routes().isEmpty(), "Live catalog returned no routes to verify");
        assertAll(page.routes().stream().map(summary -> () -> {
            var details = client.getRoute(summary.id()).join();
            var prepared = CatalogRouteInstaller.prepare(details);
            var manager = new ActiveGroupManager();
            CatalogRouteInstaller.install(manager, client.apiRoot(), prepared);
            assertEquals(details.summary().waypointCount(),
                    manager.allGroups().stream().mapToInt(group -> group.size()).sum(), summary.id());
        }));
    }
}
