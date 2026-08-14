package com.babbur.waypointer.update;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseIndexTest {

    @Test
    void findsLatestStableReleaseAndCountsDistinctNewerStableVersions() {
        String firstPage = """
                [
                  {"tag_name":"v1.9.0","draft":false,"prerelease":false,
                   "published_at":"2026-12-19T12:00:00Z"},
                  {"tag_name":"v2.0.0-beta","draft":false,"prerelease":true,
                   "published_at":"2026-12-20T12:00:00Z"},
                  {"tag_name":"v1.8.8","draft":false,"prerelease":false,
                   "published_at":"2026-09-01T12:00:00Z"}
                ]
                """;
        String secondPage = """
                [
                  {"tag_name":"v1.8.8","draft":false,"prerelease":false,
                   "published_at":"2026-08-31T12:00:00Z"},
                  {"tag_name":"v1.8.7","draft":false,"prerelease":false,
                   "published_at":"2026-08-10T00:00:33Z"},
                  {"tag_name":"v9.0.0","draft":true,"prerelease":false,
                   "published_at":"2026-12-21T12:00:00Z"},
                  {"tag_name":"release-3","draft":false,"prerelease":false,
                   "published_at":"2026-12-22T12:00:00Z"}
                ]
                """;

        AvailableUpdate update = GitHubReleaseIndex
                .findUpdate(List.of(firstPage, secondPage), "1.8.7")
                .orElseThrow();

        assertEquals("1.8.7", update.currentVersion());
        assertEquals(Instant.parse("2026-08-10T00:00:33Z"), update.currentPublishedAt());
        assertEquals("1.9.0", update.latestVersion());
        assertEquals(Instant.parse("2026-12-19T12:00:00Z"), update.latestPublishedAt());
        assertEquals(2, update.versionsBehind());
    }

    @Test
    void doesNotOfferAnUpdateWhenCurrentVersionIsLatest() {
        String releases = """
                [
                  {"tag_name":"v1.8.7","draft":false,"prerelease":false,
                   "published_at":"2026-08-10T00:00:33Z"},
                  {"tag_name":"v1.8.0-beta","draft":false,"prerelease":true,
                   "published_at":"2026-07-06T04:23:31Z"}
                ]
                """;

        assertTrue(GitHubReleaseIndex.findUpdate(List.of(releases), "v1.8.7").isEmpty());
    }

    @Test
    void requiresReleaseMetadataForTheInstalledVersion() {
        String releases = """
                [
                  {"tag_name":"v1.9.0","draft":false,"prerelease":false,
                   "published_at":"2026-12-19T12:00:00Z"}
                ]
                """;

        assertTrue(GitHubReleaseIndex.findUpdate(List.of(releases), "1.8.7").isEmpty());
        assertTrue(GitHubReleaseIndex.findUpdate(List.of(releases), "1.8.7-beta").isEmpty());
    }

    @Test
    void semanticComparisonDoesNotUseLexicographicOrdering() {
        String releases = """
                [
                  {"tag_name":"v1.10.0","draft":false,"prerelease":false,
                   "published_at":"2026-12-19T12:00:00Z"},
                  {"tag_name":"v1.9.9","draft":false,"prerelease":false,
                   "published_at":"2026-11-19T12:00:00Z"}
                ]
                """;

        AvailableUpdate update = GitHubReleaseIndex
                .findUpdate(List.of(releases), "1.9.9")
                .orElseThrow();

        assertEquals("1.10.0", update.latestVersion());
        assertEquals(1, update.versionsBehind());
    }
}
