package com.babbur.waypointer.catalog;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "WAYPOINTER_CATALOG_WRITE_LIVE", matches = "true")
class CatalogLiveUpdateTest {
    @org.junit.jupiter.api.io.TempDir Path directory;

    @Test void signedPublishEditReplaceAndFetchUseTheSameRoute() {
        var client = new RouteCatalogClient("1.11.0");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var identity = PublisherIdentity.generate(Instant.now()).withPublisherName("Wp" + suffix);
        var route = WaypointGroup.create("Verification " + suffix, "hub");
        int x = Math.floorMod(suffix.hashCode(), 100_000);
        route.add(Waypoint.at(x, 70, 10));
        var prepared = CatalogProtocol.prepareCatalogPayload(List.of(route));
        var request = new CatalogPublishRequest(prepared.payload(), route.name(),
                "Temporary route for catalog verification.", CatalogPublishRequest.Visibility.PUBLIC,
                "hub", identity.publisherName());
        var receipt = client.publishPreparedRoute(request, identity, prepared).join();
        var published = receipt.result().route();
        var registry = new CatalogPublicationRegistry(directory.resolve("publications.json"));
        var publication = registry.recordSuccessfulPublish(receipt, client.apiRoot(), Instant.now());
        System.out.println("Temporary catalog publisher: " + identity.publisherId());
        try {
            var unlisted = CatalogPublicationManager.update(client, identity, registry, publication,
                    CatalogRouteUpdate.metadata(null, null, CatalogPublishRequest.Visibility.UNLISTED),
                    null).join();
            assertEquals(CatalogPublishRequest.Visibility.UNLISTED, unlisted.visibility());
            assertEquals(published.version() + 1, unlisted.version());
            var edited = CatalogPublicationManager.update(client, identity, registry, unlisted,
                    CatalogRouteUpdate.metadata(route.name() + " updated",
                            "Updated catalog verification description.", null), null).join();
            assertEquals(unlisted.version() + 1, edited.version());
            route.add(Waypoint.at(x + 3, 71, 14));
            var second = WaypointGroup.create("Second group", "hub");
            second.add(Waypoint.at(x + 9, 72, 20));
            var replacement = CatalogProtocol.prepareCatalogPayload(List.of(route, second));
            var updated = CatalogPublicationManager.update(client, identity, registry, edited,
                    new CatalogRouteUpdate(null, null, null, replacement.payload(), "hub"),
                    replacement.payloadSha256()).join();
            assertEquals(edited.version() + 1, updated.version());
            assertEquals(published.sharePath(), updated.sharePath());
            assertEquals(edited.title(), updated.title());
            assertEquals(CatalogPublishRequest.Visibility.UNLISTED, updated.visibility());
            assertEquals(updated, registry.list().getFirst());
            var details = client.getRoute(published.id()).join();
            var manager = new ActiveGroupManager();
            CatalogRouteInstaller.install(manager, client.apiRoot(), details);
            assertEquals(2, manager.allGroups().size());
            assertEquals(3, manager.allGroups().stream().mapToInt(group -> group.size()).sum());
        } finally {
            client.deleteRoute(published.id(), identity).join();
        }
        var missing = assertThrows(CompletionException.class,
                () -> client.getRoute(published.id()).join());
        assertEquals(404, assertInstanceOf(CatalogApiException.class, missing.getCause()).status());
    }
}
