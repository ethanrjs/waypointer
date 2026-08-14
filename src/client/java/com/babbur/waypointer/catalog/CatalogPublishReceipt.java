package com.babbur.waypointer.catalog;

record CatalogPublishReceipt(
        CatalogPublishResult result,
        CatalogPublishRequest request,
        PublisherIdentity identity,
        String expectedPublisherName,
        String payloadSha256,
        int groupCount,
        int waypointCount,
        String zoneId,
        int codecVersion) {
}
