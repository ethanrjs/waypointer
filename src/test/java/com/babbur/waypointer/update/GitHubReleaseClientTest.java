package com.babbur.waypointer.update;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseClientTest {

    private static final URI ENDPOINT = URI.create("https://example.invalid/releases");

    @Test
    void buildsBoundedGitHubRequestsWithoutUsingLiveNetwork() {
        List<HttpRequest> requests = new ArrayList<>();
        GitHubReleaseClient client = new GitHubReleaseClient(
                "1.8.7",
                ENDPOINT,
                request -> {
                    requests.add(request);
                    return CompletableFuture.completedFuture(new GitHubReleaseClient.Response(
                            200, releases()));
                });

        AvailableUpdate update = client.findUpdate("1.8.7").join().orElseThrow();

        assertEquals("1.9.0", update.latestVersion());
        assertEquals(1, requests.size());
        HttpRequest request = requests.getFirst();
        assertEquals("https://example.invalid/releases?per_page=100&page=1",
                request.uri().toString());
        assertEquals("Waypointer/1.8.7",
                request.headers().firstValue("User-Agent").orElseThrow());
        assertEquals("GET", request.method());
        assertEquals(8, request.timeout().orElseThrow().toSeconds());
    }

    @Test
    void skipsNetworkForANonStableInstalledVersion() {
        GitHubReleaseClient client = new GitHubReleaseClient(
                "1.8.7-beta",
                ENDPOINT,
                request -> CompletableFuture.failedFuture(
                        new AssertionError("transport must not run")));

        Optional<AvailableUpdate> update = client.findUpdate("1.8.7-beta").join();

        assertTrue(update.isEmpty());
    }

    @Test
    void readsAnotherPageWhenGitHubReturnsAFullPage() {
        FakePagedTransport transport = new FakePagedTransport();
        GitHubReleaseClient client = new GitHubReleaseClient("1.8.7", ENDPOINT, transport::send);

        AvailableUpdate update = client.findUpdate("1.8.7").join().orElseThrow();

        assertEquals("1.9.0", update.latestVersion());
        assertEquals(1, update.versionsBehind());
        assertEquals(2, transport.requests.size());
        assertTrue(transport.requests.get(1).uri().toString().endsWith("page=2"));
    }

    @Test
    void reportsHttpFailuresToTheCaller() {
        GitHubReleaseClient client = new GitHubReleaseClient(
                "1.8.7",
                ENDPOINT,
                request -> CompletableFuture.completedFuture(
                        new GitHubReleaseClient.Response(403, "rate limited")));

        assertThrows(CompletionException.class, () -> client.findUpdate("1.8.7").join());
    }

    private static String releases() {
        return """
                [
                  {"tag_name":"v1.9.0","draft":false,"prerelease":false,
                   "published_at":"2026-12-19T12:00:00Z"},
                  {"tag_name":"v1.8.7","draft":false,"prerelease":false,
                   "published_at":"2026-08-10T00:00:33Z"}
                ]
                """;
    }

    private static final class FakePagedTransport {
        private final List<HttpRequest> requests = new ArrayList<>();

        CompletableFuture<GitHubReleaseClient.Response> send(HttpRequest request) {
            requests.add(request);
            String body = requests.size() == 1 ? fullFirstPage() : currentVersionPage();
            return CompletableFuture.completedFuture(
                    new GitHubReleaseClient.Response(200, body));
        }

        private static String fullFirstPage() {
            String release = """
                    {"tag_name":"v1.9.0","draft":false,"prerelease":false,
                     "published_at":"2026-12-19T12:00:00Z"}
                    """;
            return "[" + String.join(",", java.util.Collections.nCopies(100, release)) + "]";
        }

        private static String currentVersionPage() {
            return """
                    [
                      {"tag_name":"v1.8.7","draft":false,"prerelease":false,
                       "published_at":"2026-08-10T00:00:33Z"}
                    ]
                    """;
        }
    }
}
