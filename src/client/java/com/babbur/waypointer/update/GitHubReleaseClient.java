package com.babbur.waypointer.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class GitHubReleaseClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final String RELEASES_ENDPOINT =
            "https://api.github.com/repos/ethanrjs/waypointer/releases";

    private final URI releasesEndpoint;
    private final Transport transport;
    private final String userAgent;

    GitHubReleaseClient(String currentVersion) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        releasesEndpoint = URI.create(RELEASES_ENDPOINT);
        transport = request -> httpClient.sendAsync(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> new Response(response.statusCode(), response.body()));
        userAgent = "Waypointer/" + currentVersion;
    }

    GitHubReleaseClient(String currentVersion, URI releasesEndpoint, Transport transport) {
        this.releasesEndpoint = releasesEndpoint;
        this.transport = transport;
        userAgent = "Waypointer/" + currentVersion;
    }

    CompletableFuture<Optional<AvailableUpdate>> findUpdate(String currentVersion) {
        if (StableVersion.parse(currentVersion).isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        List<String> pages = new ArrayList<>();
        return fetchPage(1, pages).thenApply(ignored ->
                GitHubReleaseIndex.findUpdate(List.copyOf(pages), currentVersion));
    }

    private CompletableFuture<Void> fetchPage(int page, List<String> pages) {
        URI uri = URI.create(releasesEndpoint + "?per_page=" + PAGE_SIZE + "&page=" + page);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        return transport.send(request)
                .thenCompose(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "GitHub returned HTTP " + response.statusCode()));
                    }

                    String body = response.body();
                    pages.add(body);
                    int releaseCount = GitHubReleaseIndex.releaseCount(body);
                    if (releaseCount < PAGE_SIZE) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (page >= MAX_PAGES) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "GitHub release history exceeds the page limit"));
                    }
                    return fetchPage(page + 1, pages);
                });
    }

    @FunctionalInterface
    interface Transport {
        CompletableFuture<Response> send(HttpRequest request);
    }

    record Response(int statusCode, String body) {
    }
}
