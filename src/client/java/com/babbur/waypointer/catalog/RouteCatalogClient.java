package com.babbur.waypointer.catalog;

import com.babbur.waypointer.debug.DebugSignals;
import com.babbur.waypointer.util.BoundedBody;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

public final class RouteCatalogClient {
    static final URI PRODUCTION_API = URI.create("https://waypointermod.com/api/");
    private static final String PRODUCTION_AUDIENCE =
            CatalogRequestSigner.PRODUCTION_AUDIENCE;
    static final int MAX_LIST_BYTES = 256 * 1024;
    static final int MAX_ROUTE_BYTES = 512 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern ZONE_ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern CATALOG_CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,683}");

    interface Transport {
        CompletableFuture<Response> send(HttpRequest request, int maximumBytes);
    }

    record Response(int status, String contentType, byte[] body) {
    }

    private final URI apiRoot;
    private final String signatureAudience;
    private final Transport transport;
    private final String userAgent;

    public RouteCatalogClient(String version) {
        this(PRODUCTION_API, new HttpTransport(), "Waypointer/" + version);
    }

    public static RouteCatalogClient production() {
        String version = FabricLoader.getInstance().getModContainer("waypointer")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        return new RouteCatalogClient(version);
    }

    RouteCatalogClient(URI apiRoot, Transport transport, String userAgent) {
        this(apiRoot, transport, userAgent, PRODUCTION_AUDIENCE);
    }

    RouteCatalogClient(
            URI apiRoot, Transport transport, String userAgent,
            String signatureAudience) {
        this.apiRoot = requireApiRoot(apiRoot);
        this.transport = transport;
        this.userAgent = userAgent;
        this.signatureAudience = requireSignatureAudience(signatureAudience);
    }

    public CompletableFuture<CatalogPage> listRoutes() {
        return listRoutes(null, null, null);
    }

    public CompletableFuture<CatalogPage> listRoutes(
            String query, String zone, String cursor) {
        return listRoutes(query, zone, null, null, cursor);
    }

    public CompletableFuture<CatalogPage> listRoutes(
            String query, String zone, String tag, String sort, String cursor) {
        StringBuilder relative = new StringBuilder("routes?limit=50");
        appendQuery(relative, "q", normalizeSearch(query));
        appendQuery(relative, "zone", normalizeZone(zone));
        appendQuery(relative, "tag", normalizeTag(tag));
        appendQuery(relative, "sort", normalizeSort(sort));
        appendQuery(relative, "cursor", normalizeCursor(cursor));
        HttpRequest request = request(relative.toString()).GET().build();
        return sendJson(request, MAX_LIST_BYTES, 200).thenApply(CatalogJson::parsePage);
    }

    public static final List<String> SORTS =
            List.of("recommended", "popular", "newest", "updated", "waypoints", "name");

    public CompletableFuture<CatalogRoutePreviewData> getRoutePreview(String routeId) {
        if (!isValidRouteId(routeId)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid catalog route ID"));
        }
        HttpRequest request = request("routes/" + routeId + "/preview").GET().build();
        return sendJson(request, MAX_LIST_BYTES, 200).thenApply(CatalogJson::parsePreview);
    }

    public CompletableFuture<CatalogRouteDetails> getRoute(String routeId) {
        if (!isValidRouteId(routeId)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid catalog route ID"));
        }
        HttpRequest request = request("routes/" + routeId).GET().build();
        return sendJson(request, MAX_ROUTE_BYTES, 200).thenApply(json -> {
            CatalogRouteDetails details = CatalogJson.parseDetails(json);
            if (!routeId.equals(details.summary().id())) {
                throw new CatalogApiException(200, "route_id_mismatch",
                        "The catalog returned a different route than requested.");
            }
            return details;
        });
    }

    CompletableFuture<CatalogPublishReceipt> publishRoute(
            CatalogPublishRequest publish, PublisherIdentity identity) {
        requirePublishArguments(publish, identity);
        return publishValidated(publish, identity,
                CatalogProtocol.validatePublishRequest(publish, identity));
    }

    CompletableFuture<CatalogPublishReceipt> publishPreparedRoute(
            CatalogPublishRequest publish,
            PublisherIdentity identity,
            CatalogProtocol.PreparedCatalogPayload prepared) {
        requirePublishArguments(publish, identity);
        return publishValidated(publish, identity,
                CatalogProtocol.validatePreparedPublishRequest(
                        publish, identity, prepared));
    }

    private CompletableFuture<CatalogPublishReceipt> publishValidated(
            CatalogPublishRequest publish,
            PublisherIdentity identity,
            CatalogProtocol.PublishExpectation expectation) {
        String json = CatalogJson.publishBody(publish);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder = request("routes")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, String> header : CatalogRequestSigner.sign(
                identity, signatureAudience, "POST", "/api/routes", body).entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return sendJson(builder.build(), MAX_LIST_BYTES, 201)
                .thenApply(CatalogJson::parsePublishResult)
                .thenApply(result -> CatalogProtocol.validatePublishResponse(
                        result, publish, identity, expectation));
    }

    private static void requirePublishArguments(
            CatalogPublishRequest publish, PublisherIdentity identity) {
        if (publish == null) {
            throw new IllegalArgumentException("Publish request is required");
        }
        if (identity == null) {
            throw new IllegalArgumentException("Publisher identity is required");
        }
    }

    public String apiRoot() {
        return apiRoot.toString();
    }

    public CompletableFuture<Void> deleteRoute(
            String routeId, PublisherIdentity identity) {
        requireRouteId(routeId);
        if (identity == null) {
            throw new IllegalArgumentException("Publisher identity is required");
        }
        String path = "/api/routes/" + routeId;
        byte[] body = new byte[0];
        HttpRequest.Builder builder = request("routes/" + routeId).DELETE();
        addSignature(builder, identity, "DELETE", path, body);
        return sendEmpty(builder.build(), MAX_LIST_BYTES, 204);
    }

    public CompletableFuture<CatalogRouteSummary> updateRoute(
            String routeId, CatalogRouteUpdate update, PublisherIdentity identity) {
        requireRouteId(routeId);
        if (identity == null) {
            throw new IllegalArgumentException("Publisher identity is required");
        }
        if (update == null || update.isEmpty()) {
            throw new IllegalArgumentException("Route update has no changes");
        }
        byte[] body = CatalogJson.updateBody(update).getBytes(StandardCharsets.UTF_8);
        String path = "/api/routes/" + routeId;
        HttpRequest.Builder builder = request("routes/" + routeId)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body));
        addSignature(builder, identity, "PATCH", path, body);
        return sendJson(builder.build(), MAX_LIST_BYTES, 200)
                .thenApply(json -> {
                    try {
                        CatalogRouteSummary route = CatalogJson.parseRouteResponse(json);
                        if (!routeId.equals(route.id())) {
                            throw new CatalogApiException(200, "invalid_response",
                                    "The catalog confirmed a different route.");
                        }
                        if (!identity.publisherId().equals(route.publisherId())) {
                            throw new CatalogApiException(200, "invalid_response",
                                    "The catalog confirmed a different publisher.");
                        }
                        return route;
                    } catch (RuntimeException failure) {
                        DebugSignals.recordCatalogFailure("PATCH", 200, "invalid_response");
                        if (failure instanceof CatalogApiException) throw failure;
                        throw new CatalogApiException(200, "invalid_response", "The catalog returned an invalid route.");
                    }
                });
    }

    public CompletableFuture<Void> recordInstall(String routeId) {
        return recordInstall(routeId, null, null);
    }

    public CompletableFuture<Void> recordInstall(
            String routeId, PublisherIdentity identity) {
        return recordInstall(routeId, identity, null);
    }

    public CompletableFuture<Void> recordInstall(
            String routeId, PublisherIdentity identity, String installToken) {
        requireRouteId(routeId);
        String path = "/api/routes/" + routeId + "/install";
        byte[] body = new byte[0];
        HttpRequest.Builder builder = request("routes/" + routeId + "/install")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (installToken != null && !installToken.isBlank()) {
            builder.header("x-waypointer-install-token", installToken);
        }
        if (identity != null) {
            addSignature(builder, identity, "POST", path, body);
        }
        return sendEmpty(builder.build(), MAX_LIST_BYTES, 204);
    }

    private HttpRequest.Builder request(String relative) {
        return HttpRequest.newBuilder(apiRoot.resolve(relative))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
    }

    private CompletableFuture<String> sendJson(
            HttpRequest request, int maximumBytes, int expectedStatus) {
        CompletableFuture<Response> network = transport.send(request, maximumBytes);
        CompletableFuture<String> result = network.thenApply(response -> {
            String json = new String(response.body(), StandardCharsets.UTF_8);
            if (response.status() != expectedStatus) {
                throw CatalogJson.parseError(response.status(), json);
            }
            String contentType = response.contentType() == null ? "" : response.contentType();
            if (!isJsonMediaType(contentType)) {
                throw new CatalogApiException(response.status(), "invalid_response",
                        "The catalog returned non-JSON data.");
            }
            return json;
        }).orTimeout(12, TimeUnit.SECONDS);
        return result.whenComplete((ignored, failure) -> {
            recordFailure(request, failure);
            if (failure instanceof TimeoutException) network.cancel(true);
        });
    }

    private static boolean isJsonMediaType(String contentType) {
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator))
                .trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json")
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    private CompletableFuture<Void> sendEmpty(
            HttpRequest request, int maximumBytes, int expectedStatus) {
        CompletableFuture<Response> network = transport.send(request, maximumBytes);
        CompletableFuture<Void> result = network.thenApply(response -> {
            if (response.status() != expectedStatus) {
                String json = new String(response.body(), StandardCharsets.UTF_8);
                throw CatalogJson.parseError(response.status(), json);
            }
            return (Void) null;
        }).orTimeout(12, TimeUnit.SECONDS);
        return result.whenComplete((ignored, failure) -> {
            recordFailure(request, failure);
            if (failure instanceof TimeoutException) network.cancel(true);
        });
    }

    private static void recordFailure(HttpRequest request, Throwable failure) {
        if (failure == null) return;
        while ((failure instanceof CompletionException
                || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        int status = failure instanceof CatalogApiException api ? api.status() : 0;
        String code = failure instanceof CatalogApiException api ? api.code()
                : failure instanceof TimeoutException ? "timeout" : "transport_error";
        DebugSignals.recordCatalogFailure(request.method(), status, code);
    }

    private void addSignature(
            HttpRequest.Builder builder, PublisherIdentity identity,
            String method, String path, byte[] body) {
        for (Map.Entry<String, String> header : CatalogRequestSigner.sign(
                identity, signatureAudience, method, path, body).entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
    }

    private static void appendQuery(StringBuilder relative, String name, String value) {
        if (value == null) return;
        relative.append('&').append(name).append('=').append(
                URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private static String normalizeSearch(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > 80) {
            throw new IllegalArgumentException("Catalog search is too long");
        }
        return trimmed;
    }

    private static String normalizeZone(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (!ZONE_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid catalog zone ID");
        }
        return trimmed;
    }

    private static String normalizeTag(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Invalid catalog tag");
        }
        return trimmed;
    }

    private static String normalizeSort(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return SORTS.contains(trimmed) && !trimmed.equals("recommended") ? trimmed : null;
    }

    private static String normalizeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (!CATALOG_CURSOR.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid catalog cursor");
        }
        return trimmed;
    }

    private static URI requireApiRoot(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Catalog API must use a plain HTTPS origin");
        }
        String text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }

    private static String requireSignatureAudience(String audience) {
        if (audience == null || !audience.matches("[a-z0-9:-]{1,64}")) {
            throw new IllegalArgumentException("Invalid catalog signature audience");
        }
        return audience;
    }

    public static boolean isValidRouteId(String routeId) {
        return routeId != null && ROUTE_ID.matcher(routeId).matches();
    }

    private static void requireRouteId(String routeId) {
        if (!isValidRouteId(routeId)) {
            throw new IllegalArgumentException("Invalid catalog route ID");
        }
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public CompletableFuture<Response> send(HttpRequest request, int maximumBytes) {
            return client.sendAsync(request, BoundedBody.handler(maximumBytes,
                            () -> new IllegalStateException("Catalog response is too large")))
                    .thenApply(response -> new Response(
                            response.statusCode(),
                            response.headers().firstValue("Content-Type").orElse(""),
                            response.body()));
        }
    }
}
