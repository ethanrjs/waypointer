package com.babbur.waypointer.catalog;

/**
 * An owner edit of a published route. Every field is optional; {@code null} leaves the
 * server's value untouched. A payload replaces the route's waypoints while the route id,
 * downloads and share link stay the same.
 */
public record CatalogRouteUpdate(
        String title,
        String description,
        CatalogPublishRequest.Visibility visibility,
        String payload,
        String zoneId) {

    public static CatalogRouteUpdate metadata(
            String title, String description, CatalogPublishRequest.Visibility visibility) {
        return new CatalogRouteUpdate(title, description, visibility, null, null);
    }

    public static CatalogRouteUpdate replacePayload(String payload, String zoneId) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("A replacement payload is required");
        }
        return new CatalogRouteUpdate(null, null, null, payload, zoneId);
    }

    public boolean replacesPayload() {
        return payload != null;
    }

    public boolean isEmpty() {
        return title == null && description == null && visibility == null && payload == null;
    }
}
