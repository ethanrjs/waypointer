package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationRegistryTest {
    private static final String ROUTE_ID = "Abcdefghijklmnopqrstuv";
    private static final String API_ROOT = "https://waypointermod.com/api/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void successfulPublishPersistsPublicProvenanceWithoutSecrets() throws Exception {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Tester_1");
        Path file = temporaryDirectory.resolve("publisher/publications.json");
        CatalogPublicationRegistry registry = new CatalogPublicationRegistry(file);
        CatalogPublishRequest request = request(null);

        CatalogPublication recorded = registry.recordSuccessfulPublish(
                result(identity, "Tester_1", 1, "secret-manage-token"),
                request, identity, API_ROOT, Instant.parse("2026-08-13T01:00:00Z"));
        CatalogPublication reloaded = new CatalogPublicationRegistry(file).list().getFirst();
        String json = Files.readString(file);

        assertEquals(ROUTE_ID, recorded.routeId());
        assertEquals(identity.publisherId(), reloaded.publisherId());
        assertEquals("https://waypointermod.com/r/" + ROUTE_ID, reloaded.shareUrl());
        assertEquals(CatalogPublishRequest.Visibility.UNLISTED, reloaded.visibility());
        assertEquals(43, reloaded.payloadSha256().length());
        assertFalse(json.contains("secret-manage-token"));
        assertFalse(json.contains("WP:test"));
        assertFalse(json.contains("privateKey"));
    }

    @Test
    void rejectsMismatchedResponseIdentityBeforeWriting() {
        PublisherIdentity signer = PublisherIdentity.generate(Instant.EPOCH);
        PublisherIdentity other = PublisherIdentity.generate(Instant.EPOCH);
        CatalogPublicationRegistry registry = new CatalogPublicationRegistry(
                temporaryDirectory.resolve("publisher/publications.json"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.recordSuccessfulPublish(
                        result(other, "Tester_1", 1, ""), request("Tester_1"),
                        signer, API_ROOT, Instant.EPOCH));
        assertFalse(Files.exists(registry.file()));
    }

    @Test
    void republishUpdatesOneRouteRecordAndSignedDeleteCanRemoveIt() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Tester_1");
        CatalogPublicationRegistry registry = new CatalogPublicationRegistry(
                temporaryDirectory.resolve("publisher/publications.json"));

        registry.recordSuccessfulPublish(
                result(identity, "Tester_1", 1, ""), request(null),
                identity, API_ROOT, Instant.EPOCH);
        registry.recordSuccessfulPublish(
                result(identity, "Tester_1", 2, ""), request(null),
                identity, API_ROOT, Instant.EPOCH.plusSeconds(1));

        assertEquals(1, registry.listForPublisher(identity.publisherId()).size());
        assertEquals(2, registry.list().getFirst().version());
        assertTrue(registry.remove(ROUTE_ID, identity.publisherId()));
        assertTrue(registry.list().isEmpty());
        assertFalse(registry.remove(ROUTE_ID, identity.publisherId()));
    }

    @Test
    void lifecyclePersistsBeforeItReportsSuccessToTheScreen() {
        Path identityFile = temporaryDirectory.resolve("publisher/identity.json");
        PublisherIdentityStore identityStore = new PublisherIdentityStore(identityFile);
        PublisherIdentity identity = identityStore.loadOrCreate();
        CatalogPublicationRegistry registry = CatalogPublicationRegistry
                .nextToIdentity(identityStore);
        CatalogPublishRequest request = request("Tester_1");
        CompletableFuture<RouteCatalogClient.Response> network = new CompletableFuture<>();
        RouteCatalogClient client = new RouteCatalogClient(
                URI.create(API_ROOT), (ignored, maximum) -> network,
                "Waypointer/Test", CatalogRequestSigner.PRODUCTION_AUDIENCE);

        CompletableFuture<CatalogPublishLifecycle.Completion> completion =
                CatalogPublishLifecycle.publishAndPersist(
                        client, request, identity, identityStore, registry);
        network.complete(new RouteCatalogClient.Response(
                201, "application/json", responseJson(identity, "Tester_1")
                        .getBytes(StandardCharsets.UTF_8)));
        CatalogPublishLifecycle.Completion completed = completion.join();

        assertEquals("Tester_1", identityStore.load().publisherName());
        assertEquals(ROUTE_ID, registry.list().getFirst().routeId());
        assertNotNull(completed.publication());
        assertFalse(completed.nameSaveFailed());
        assertFalse(completed.publicationSaveFailed());
    }

    @Test
    void routeRecordSurvivesWhenPublisherNameFileCannotBeUpdated() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        PublisherIdentityStore differentIdentityStore = new PublisherIdentityStore(
                temporaryDirectory.resolve("identity.json"));
        differentIdentityStore.loadOrCreate();
        CatalogPublicationRegistry registry = new CatalogPublicationRegistry(
                temporaryDirectory.resolve("publications.json"));

        CatalogPublishLifecycle.Completion completed = CatalogPublishLifecycle.persist(
                result(identity, "Tester_1", 1, ""), request("Tester_1"), identity,
                differentIdentityStore, registry, API_ROOT, Instant.EPOCH);

        assertTrue(completed.nameSaveFailed());
        assertFalse(completed.publicationSaveFailed());
        assertEquals(ROUTE_ID, registry.list().getFirst().routeId());
    }

    @Test
    void signedOwnerDeleteRemovesTheDurableRecord() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Tester_1");
        CatalogPublicationRegistry registry = registryWithRoute(identity);
        RecordingTransport transport = new RecordingTransport(
                new RouteCatalogClient.Response(204, "", new byte[0]));
        RouteCatalogClient client = new RouteCatalogClient(
                URI.create(API_ROOT), transport, "Waypointer/Test",
                CatalogRequestSigner.PRODUCTION_AUDIENCE);

        CatalogPublicationManager.delete(
                client, identity, registry, registry.list().getFirst()).join();

        assertTrue(registry.list().isEmpty());
        assertEquals("DELETE", transport.request.method());
        assertEquals(identity.publisherId(), transport.request.headers()
                .firstValue("X-Waypointer-Publisher").orElseThrow());
        assertTrue(transport.request.headers()
                .firstValue("X-Waypointer-Signature").isPresent());
    }

    @Test
    void missingRemoteRouteIsReconciledButOtherFailuresKeepRecoveryData() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Tester_1");
        CatalogPublicationRegistry missingRegistry = registryWithRoute(identity);
        RouteCatalogClient missingClient = new RouteCatalogClient(
                URI.create(API_ROOT), new RecordingTransport(
                        error(404, "route_not_found")), "Waypointer/Test",
                CatalogRequestSigner.PRODUCTION_AUDIENCE);

        CatalogPublicationManager.delete(missingClient, identity, missingRegistry,
                missingRegistry.list().getFirst()).join();
        assertTrue(missingRegistry.list().isEmpty());

        CatalogPublicationRegistry failedRegistry = registryWithRoute(identity);
        RouteCatalogClient failedClient = new RouteCatalogClient(
                URI.create(API_ROOT), new RecordingTransport(
                        error(503, "unavailable")), "Waypointer/Test",
                CatalogRequestSigner.PRODUCTION_AUDIENCE);

        assertThrows(Exception.class, () -> CatalogPublicationManager.delete(
                failedClient, identity, failedRegistry,
                failedRegistry.list().getFirst()).join());
        assertEquals(1, failedRegistry.list().size());
    }

    @Test
    void deleteRejectsAChangedIdentityOrCatalogBeforeNetworkAccess() {
        PublisherIdentity owner = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Tester_1");
        PublisherIdentity other = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Other_1");
        CatalogPublicationRegistry registry = registryWithRoute(owner);
        RecordingTransport transport = new RecordingTransport(
                new RouteCatalogClient.Response(204, "", new byte[0]));
        RouteCatalogClient client = new RouteCatalogClient(
                URI.create(API_ROOT), transport, "Waypointer/Test",
                CatalogRequestSigner.PRODUCTION_AUDIENCE);

        assertThrows(IllegalArgumentException.class,
                () -> CatalogPublicationManager.delete(
                        client, other, registry, registry.list().getFirst()));
        assertEquals(null, transport.request);
    }

    private static CatalogPublishRequest request(String publisherName) {
        return new CatalogPublishRequest(
                "WP:test", "Route", "A useful route description.",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", publisherName);
    }

    private CatalogPublicationRegistry registryWithRoute(PublisherIdentity identity) {
        Path path = temporaryDirectory.resolve(
                "registry-" + System.nanoTime() + "/publications.json");
        CatalogPublicationRegistry registry = new CatalogPublicationRegistry(path);
        registry.recordSuccessfulPublish(
                result(identity, identity.publisherName(), 1, ""), request(null),
                identity, API_ROOT, Instant.EPOCH);
        return registry;
    }

    private static RouteCatalogClient.Response error(int status, String code) {
        return new RouteCatalogClient.Response(status, "application/json",
                ("{\"error\":{\"code\":\"" + code
                        + "\",\"message\":\"failure\"}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static CatalogPublishResult result(
            PublisherIdentity identity, String publisherName,
            int version, String manageToken) {
        return new CatalogPublishResult(new CatalogRouteSummary(
                ROUTE_ID, "Route", "A useful route description.", publisherName,
                identity.publisherId(), true, "unlisted", "hub", "Hub",
                1, 1, 9, version, 0,
                "2026-08-13T00:00:00Z", "2026-08-13T00:00:00Z",
                "/r/" + ROUTE_ID), manageToken);
    }

    private static String responseJson(PublisherIdentity identity, String publisherName) {
        return """
                {"route":{
                  "id":"Abcdefghijklmnopqrstuv","title":"Route",
                  "description":"A useful route description.",
                  "authorName":"%s","publisherId":"%s",
                  "publisherVerified":true,"visibility":"unlisted",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":1,"groupCount":1,"codecVersion":9,"version":1,
                  "createdAt":"2026-08-13T00:00:00Z",
                  "updatedAt":"2026-08-13T00:00:00Z",
                  "sharePath":"/r/Abcdefghijklmnopqrstuv"
                },"manageToken":"discarded-token"}
                """.formatted(publisherName, identity.publisherId());
    }

    private static final class RecordingTransport implements RouteCatalogClient.Transport {
        private final RouteCatalogClient.Response response;
        private HttpRequest request;

        private RecordingTransport(RouteCatalogClient.Response response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<RouteCatalogClient.Response> send(
                HttpRequest request, int maximumBytes) {
            this.request = request;
            return CompletableFuture.completedFuture(response);
        }
    }
}
