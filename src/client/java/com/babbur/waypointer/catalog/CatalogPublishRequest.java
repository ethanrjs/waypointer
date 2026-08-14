package com.babbur.waypointer.catalog;

public record CatalogPublishRequest(
        String payload,
        String title,
        String description,
        Visibility visibility,
        String zoneId,
        String publisherName) {

    public enum Visibility {
        PUBLIC("public"),
        UNLISTED("unlisted");

        private final String wireName;

        Visibility(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
