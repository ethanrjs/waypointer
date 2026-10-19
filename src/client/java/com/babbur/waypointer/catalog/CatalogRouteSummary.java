package com.babbur.waypointer.catalog;

import com.babbur.waypointer.core.RouteIcon;
import java.util.List;
import java.util.Locale;

public record CatalogRouteSummary(
        String id,
        String title,
        String description,
        String authorName,
        String publisherId,
        boolean publisherVerified,
        String visibility,
        String zoneId,
        String zoneLabel,
        int waypointCount,
        int groupCount,
        int codecVersion,
        int version,
        long downloads,
        String createdAt,
        String updatedAt,
        String sharePath,
        List<String> tags,
        String icon) {

    public static final String TAG_MINING_CULT = "mining-cult";

    public CatalogRouteSummary {
        tags = tags == null ? List.of() : List.copyOf(tags);
        icon = RouteIcon.normalize(icon);
    }

    public CatalogRouteSummary(String id, String title, String description, String authorName,
                               String publisherId, boolean publisherVerified, String visibility,
                               String zoneId, String zoneLabel, int waypointCount, int groupCount,
                               int codecVersion, int version, long downloads, String createdAt,
                               String updatedAt, String sharePath, List<String> tags) {
        this(id, title, description, authorName, publisherId, publisherVerified, visibility,
                zoneId, zoneLabel, waypointCount, groupCount, codecVersion, version, downloads,
                createdAt, updatedAt, sharePath, tags, "");
    }

    public CatalogRouteSummary(String id, String title, String description, String authorName,
                               String publisherId, boolean publisherVerified, String visibility,
                               String zoneId, String zoneLabel, int waypointCount, int groupCount,
                               int codecVersion, int version, long downloads, String createdAt,
                               String updatedAt, String sharePath) {
        this(id, title, description, authorName, publisherId, publisherVerified, visibility,
                zoneId, zoneLabel, waypointCount, groupCount, codecVersion, version, downloads,
                createdAt, updatedAt, sharePath, List.of(), "");
    }

    public String displayIcon() {
        return RouteIcon.display(icon, zoneId);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return contains(title, normalized)
                || contains(description, normalized)
                || contains(authorName, normalized)
                || contains(publisherId, normalized)
                || contains(zoneId, normalized)
                || contains(zoneLabel, normalized);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
