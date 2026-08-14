package com.babbur.waypointer.catalog;

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
        String sharePath) {

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

    public String publisherLabel() {
        if (authorName == null || authorName.isBlank()) return "Unknown publisher";
        return publisherVerified ? authorName + " verified" : authorName;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
