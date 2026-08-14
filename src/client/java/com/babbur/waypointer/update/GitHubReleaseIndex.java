package com.babbur.waypointer.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

final class GitHubReleaseIndex {

    private GitHubReleaseIndex() {
    }

    static Optional<AvailableUpdate> findUpdate(List<String> pages, String currentVersionText) {
        Optional<StableVersion> parsedCurrent = StableVersion.parse(currentVersionText);
        if (parsedCurrent.isEmpty()) return Optional.empty();

        NavigableMap<StableVersion, Release> stableReleases = new TreeMap<>();
        for (String page : pages) {
            collectStableReleases(page, stableReleases);
        }

        StableVersion currentVersion = parsedCurrent.orElseThrow();
        Release currentRelease = stableReleases.get(currentVersion);
        if (currentRelease == null || stableReleases.isEmpty()) return Optional.empty();

        var latestEntry = stableReleases.lastEntry();
        if (latestEntry.getKey().compareTo(currentVersion) <= 0) return Optional.empty();

        int versionsBehind = stableReleases.tailMap(currentVersion, false).size();
        return Optional.of(new AvailableUpdate(
                currentVersion.toString(),
                currentRelease.publishedAt(),
                latestEntry.getKey().toString(),
                latestEntry.getValue().publishedAt(),
                versionsBehind));
    }

    static int releaseCount(String page) {
        return parseArray(page).size();
    }

    private static void collectStableReleases(
            String page, NavigableMap<StableVersion, Release> stableReleases) {
        for (JsonElement element : parseArray(page)) {
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (booleanValue(release, "draft") || booleanValue(release, "prerelease")) continue;

            Optional<StableVersion> version = StableVersion.parse(stringValue(release, "tag_name"));
            Optional<Instant> publishedAt = instantValue(release, "published_at");
            if (version.isEmpty() || publishedAt.isEmpty()) continue;

            stableReleases.merge(
                    version.orElseThrow(),
                    new Release(publishedAt.orElseThrow()),
                    (first, second) -> first.publishedAt().isAfter(second.publishedAt())
                            ? first
                            : second);
        }
    }

    private static JsonArray parseArray(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) throw new IllegalArgumentException("GitHub release response is not an array");
        return root.getAsJsonArray();
    }

    private static boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Optional<Instant> instantValue(JsonObject object, String key) {
        try {
            String value = stringValue(object, key);
            return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (DateTimeException ignored) {
            return Optional.empty();
        }
    }

    private record Release(Instant publishedAt) {
    }
}
