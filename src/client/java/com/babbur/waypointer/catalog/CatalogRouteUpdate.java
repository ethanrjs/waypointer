package com.babbur.waypointer.catalog;

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

    public boolean isEmpty() {
        return title == null && description == null && visibility == null && payload == null;
    }
}
