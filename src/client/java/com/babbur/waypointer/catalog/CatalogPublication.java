package com.babbur.waypointer.catalog;

import java.net.URI;
import java.time.Instant;

public record CatalogPublication(
        String routeId,
        String publisherId,
        String publisherName,
        String title,
        CatalogPublishRequest.Visibility visibility,
        String zoneId,
        int version,
        int codecVersion,
        String serverCreatedAt,
        String sharePath,
        String apiRoot,
        String payloadSha256,
        Instant recordedAt) {

    public String shareUrl() {
        URI api = URI.create(apiRoot);
        URI origin = URI.create(api.getScheme() + "://" + api.getAuthority() + "/");
        return origin.resolve(sharePath.substring(1)).toString();
    }
}
