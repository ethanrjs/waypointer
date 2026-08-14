package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCatalogClientTest {
    private static final String ID = "Abcdefghijklmnopqrstuv";
    private static final String PAYLOAD = catalogPayload();

    @Test
    void listUsesBoundedJsonRequestAndParsesRoutes() {
        RecordingTransport transport = new RecordingTransport(new RouteCatalogClient.Response(
                200, "application/json; charset=utf-8", ("""
                {"routes":[{
                  "id":"Abcdefghijklmnopqrstuv","title":"Route",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":1,"groupCount":1,"codecVersion":8
                }],"hasMore":false}
                """).getBytes(StandardCharsets.UTF_8)));
        RouteCatalogClient client = client(transport);

        CatalogPage page = client.listRoutes().join();

        assertEquals(1, page.routes().size());
        assertEquals("https://catalog.example/api/routes?limit=50",
                transport.request.uri().toString());
        assertEquals("application/json",
                transport.request.headers().firstValue("Accept").orElseThrow());
        assertEquals(RouteCatalogClient.MAX_LIST_BYTES, transport.maximumBytes);
    }

    @Test
    void rejectsJsonpMediaType() {
        RecordingTransport transport = new RecordingTransport(new RouteCatalogClient.Response(
                200, "application/jsonp", "{\"routes\":[]}".getBytes(StandardCharsets.UTF_8)));
        CompletionException error = assertThrows(CompletionException.class,
                () -> client(transport).listRoutes("", "", null).join());
        CatalogApiException cause = (CatalogApiException) error.getCause();
        assertEquals("invalid_response", cause.code());
    }

    @Test
    void listEncodesServerFiltersAndCursor() {
        RecordingTransport transport = new RecordingTransport(new RouteCatalogClient.Response(
                200, "application/json", "{\"routes\":[]}".getBytes(StandardCharsets.UTF_8)));
        RouteCatalogClient client = client(transport);

        client.listRoutes(" magma & coal ", " CRYSTAL_HOLLOWS ", "eyJwYWdlIjoyfQ").join();

        assertEquals(
                "https://catalog.example/api/routes?limit=50&q=magma%20%26%20coal"
                        + "&zone=crystal_hollows&cursor=eyJwYWdlIjoyfQ",
                transport.request.uri().toString());
    }

    @Test
    void listRejectsInvalidFiltersBeforeNetworkAccess() {
        RecordingTransport transport = new RecordingTransport(null);
        RouteCatalogClient client = client(transport);

        assertThrows(IllegalArgumentException.class,
                () -> client.listRoutes("route", "../admin", null));
        assertThrows(IllegalArgumentException.class,
                () -> client.listRoutes("route", "hub", "bad cursor"));
        assertNull(transport.request);
    }

    @Test
    void detailsRejectUnsafeIdsBeforeNetworkAccess() {
        RecordingTransport transport = new RecordingTransport(null);
        RouteCatalogClient client = client(transport);

        assertThrows(IllegalArgumentException.class, () -> client.getRoute("../admin"));
        assertEquals(null, transport.request);
    }

    @Test
    void detailsRejectAResponseForAnotherRoute() {
        RecordingTransport transport = new RecordingTransport(new RouteCatalogClient.Response(
                200, "application/json", ("""
                {"route":{
                  "id":"Zbcdefghijklmnopqrstuv","title":"Other route",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":1,"groupCount":1,"codecVersion":9,
                  "payload":"WP:test"
                }}
                """).getBytes(StandardCharsets.UTF_8)));
        RouteCatalogClient client = client(transport);

        Exception joined = assertThrows(Exception.class, () -> client.getRoute(ID).join());
        CatalogApiException failure = (CatalogApiException) joined.getCause();
        assertEquals("route_id_mismatch", failure.code());
    }

    @Test
    void exposesServerErrorCodeAndMessage() {
        RecordingTransport transport = new RecordingTransport(new RouteCatalogClient.Response(
                503, "application/json", ("""
                {"error":{"code":"publishing_disabled",
                "message":"Route publishing is unavailable."}}
                """).getBytes(StandardCharsets.UTF_8)));
        RouteCatalogClient client = client(transport);

        Exception joined = assertThrows(Exception.class, () -> client.getRoute(ID).join());
        CatalogApiException failure = (CatalogApiException) joined.getCause();
        assertEquals(503, failure.status());
        assertEquals("publishing_disabled", failure.code());
    }

    @Test
    void publishSignsTheExactJsonBody() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        RecordingTransport transport = publishTransport(identity, "Tester");
        RouteCatalogClient client = client(transport);
        CatalogPublishRequest request = new CatalogPublishRequest(
                PAYLOAD, "Route", "Description",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", "Tester");

        CatalogPublishReceipt receipt = client.publishRoute(request, identity).join();
        CatalogPublishResult result = receipt.result();

        assertEquals(ID, result.route().id());
        assertEquals("token", result.manageToken());
        assertEquals("POST", transport.request.method());
        assertEquals(identity.publisherId(), transport.request.headers()
                .firstValue("X-Waypointer-Publisher").orElseThrow());
        assertNotNull(transport.request.headers()
                .firstValue("X-Waypointer-Signature").orElse(null));
        assertTrue(transport.request.bodyPublisher().isPresent());
    }

    @Test
    void deleteSignsTheExactRoutePath() {
        RecordingTransport transport = emptyTransport();
        RouteCatalogClient client = client(transport);
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);

        client.deleteRoute(ID, identity).join();

        assertEquals("DELETE", transport.request.method());
        assertEquals("https://catalog.example/api/routes/" + ID,
                transport.request.uri().toString());
        assertValidSignature(transport.request, identity, "DELETE", "/api/routes/" + ID);
    }

    @Test
    void installCanBeAnonymousOrSigned() {
        RecordingTransport anonymous = emptyTransport();
        RouteCatalogClient client = client(anonymous);

        client.recordInstall(ID).join();

        assertEquals("POST", anonymous.request.method());
        assertEquals("https://catalog.example/api/routes/" + ID + "/install",
                anonymous.request.uri().toString());
        assertTrue(anonymous.request.headers()
                .firstValue("X-Waypointer-Signature").isEmpty());

        RecordingTransport signed = emptyTransport();
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        client(signed).recordInstall(ID, identity).join();
        assertValidSignature(signed.request, identity, "POST",
                "/api/routes/" + ID + "/install");
    }

    private static RecordingTransport emptyTransport() {
        return new RecordingTransport(new RouteCatalogClient.Response(
                204, "", new byte[0]));
    }

    private static void assertValidSignature(
            HttpRequest request, PublisherIdentity identity, String method, String path) {
        long timestamp = Long.parseLong(request.headers()
                .firstValue("X-Waypointer-Timestamp").orElseThrow());
        String nonce = request.headers()
                .firstValue("X-Waypointer-Nonce").orElseThrow();
        byte[] signature = Base64.getUrlDecoder().decode(request.headers()
                .firstValue("X-Waypointer-Signature").orElseThrow());
        byte[] preimage = CatalogRequestSigner.preimage(
                CatalogRequestSigner.PRODUCTION_AUDIENCE,
                method, path, identity.publisherId(), timestamp, nonce, new byte[0]);
        assertTrue(identity.verifies(preimage, signature));
    }

    @Test
    void publishRejectsAResponseForAnotherPublisher() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        PublisherIdentity other = PublisherIdentity.generate(Instant.EPOCH);
        RecordingTransport transport = publishTransport(other, "Tester");
        CatalogPublishRequest request = new CatalogPublishRequest(
                PAYLOAD, "Route", "Description",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", "Tester");

        Exception joined = assertThrows(Exception.class,
                () -> client(transport).publishRoute(request, identity).join());

        CatalogApiException failure = (CatalogApiException) joined.getCause();
        assertEquals("publish_response_mismatch", failure.code());
    }

    @Test
    void publishRejectsAResponseWithAnotherPublisherName() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        RecordingTransport transport = publishTransport(identity, "WrongName");
        CatalogPublishRequest request = new CatalogPublishRequest(
                PAYLOAD, "Route", "Description",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", "Tester");

        Exception joined = assertThrows(Exception.class,
                () -> client(transport).publishRoute(request, identity).join());

        CatalogApiException failure = (CatalogApiException) joined.getCause();
        assertEquals("publish_response_mismatch", failure.code());
    }

    @Test
    void publishBindsEveryPayloadFieldBeforeSuccess() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        CatalogPublishRequest request = new CatalogPublishRequest(
                PAYLOAD, "Route", "Description",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", "Tester");
        String valid = publishResponse(identity, "Tester");

        for (String changed : List.of(
                valid.replace("\"title\":\"Route\"", "\"title\":\"Other\""),
                valid.replace("\"description\":\"Description\"",
                        "\"description\":\"Other description\""),
                valid.replace("\"visibility\":\"unlisted\"",
                        "\"visibility\":\"public\""),
                valid.replace("\"zoneId\":\"hub\"", "\"zoneId\":\"garden\""),
                valid.replace("\"waypointCount\":1", "\"waypointCount\":2"),
                valid.replace("\"groupCount\":1", "\"groupCount\":2"),
                valid.replace("\"codecVersion\":9", "\"codecVersion\":8"),
                valid.replace("\"sharePath\":\"/r/" + ID + "\"",
                        "\"sharePath\":\"/r/Zbcdefghijklmnopqrstuv\""))) {
            RecordingTransport transport = new RecordingTransport(
                    new RouteCatalogClient.Response(201, "application/json",
                            changed.getBytes(StandardCharsets.UTF_8)));
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> client(transport).publishRoute(request, identity).join());
            assertEquals("publish_response_mismatch",
                    ((CatalogApiException) failure.getCause()).code());
        }
    }

    @Test
    void permanentNameConflictFailsBeforeNetworkAccess() {
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH)
                .withPublisherName("Tester");
        RecordingTransport transport = new RecordingTransport(null);
        CatalogPublishRequest request = new CatalogPublishRequest(
                PAYLOAD, "Route", "Description",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", "OtherName");

        assertThrows(IllegalArgumentException.class,
                () -> client(transport).publishRoute(request, identity));
        assertEquals(null, transport.request);
    }

    private static RouteCatalogClient client(RecordingTransport transport) {
        return new RouteCatalogClient(
                URI.create("https://catalog.example/api/"), transport, "Waypointer/Test");
    }

    private static RecordingTransport publishTransport(
            PublisherIdentity identity, String publisherName) {
        return new RecordingTransport(new RouteCatalogClient.Response(
                201, "application/json",
                publishResponse(identity, publisherName).getBytes(StandardCharsets.UTF_8)));
    }

    private static String publishResponse(
            PublisherIdentity identity, String publisherName) {
        return """
                {"route":{
                  "id":"%s","title":"Route","description":"Description",
                  "authorName":"%s","publisherId":"%s",
                  "visibility":"unlisted","zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":1,"groupCount":1,"codecVersion":9,"version":1,
                  "sharePath":"/r/%s"
                },"manageToken":"token"}
                """.formatted(ID, publisherName, identity.publisherId(), ID);
    }

    private static String catalogPayload() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(1, 64, 2, "Start", 0x44AA66, 0, 0.0));
        return WaypointCodec.encodeCatalog(List.of(group));
    }

    private static final class RecordingTransport implements RouteCatalogClient.Transport {
        private final RouteCatalogClient.Response response;
        private HttpRequest request;
        private int maximumBytes;

        private RecordingTransport(RouteCatalogClient.Response response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<RouteCatalogClient.Response> send(
                HttpRequest request, int maximumBytes) {
            this.request = request;
            this.maximumBytes = maximumBytes;
            return CompletableFuture.completedFuture(response);
        }
    }
}
