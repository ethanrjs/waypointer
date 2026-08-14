package com.babbur.waypointer.catalog;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogJsonTest {
    private static final String ID = "Abcdefghijklmnopqrstuv";
    private static final String SECOND_ID = "Bcdefghijklmnopqrstuvw";
    private static final String THIRD_ID = "Cdefghijklmnopqrstuvwx";
    private static final String EMOJI = "\uD83D\uDE00";

    @Test
    void parsesCurrentSiteSummaryAndSupportsLocalSearch() {
        CatalogPage page = CatalogJson.parsePage("""
                {"routes":[{
                  "id":"Abcdefghijklmnopqrstuv",
                  "title":"Coal Route",
                  "description":"Fast magma fields loop",
                  "authorName":"Babbur",
                  "visibility":"public",
                  "zoneId":"crystal_hollows",
                  "zoneLabel":"Crystal Hollows",
                  "waypointCount":83,
                  "groupCount":1,
                  "codecVersion":8,
                  "createdAt":"2026-08-11T00:00:00Z",
                  "updatedAt":"2026-08-11T00:00:00Z",
                  "sharePath":"/r/Abcdefghijklmnopqrstuv"
                }],"hasMore":false}
                """);

        assertEquals(1, page.routes().size());
        assertNull(page.nextCursor());
        CatalogRouteSummary route = page.routes().getFirst();
        assertEquals(ID, route.id());
        assertEquals(1, route.version());
        assertEquals(0, route.downloads());
        assertTrue(route.matches("magma"));
        assertTrue(route.matches("BABBUR"));
        assertTrue(route.matches("crystal_hollows"));
        assertFalse(route.matches("farming"));
    }

    @Test
    void parsesPublisherMetadataWhenBackendProvidesIt() {
        CatalogPage page = CatalogJson.parsePage("""
                {"routes":[{
                  "id":"Abcdefghijklmnopqrstuv",
                  "title":"Coal Route",
                  "authorName":"Babbur",
                  "publisherId":"wp_1234567890123456789012345678901234567890123",
                  "publisherVerified":true,
                  "visibility":"public",
                  "zoneId":"crystal_hollows",
                  "zoneLabel":"Crystal Hollows",
                  "waypointCount":83,
                  "groupCount":1,
                  "codecVersion":9,
                  "version":3,
                  "downloads":2381
                }],"hasMore":true,"nextCursor":"eyJwYWdlIjoyfQ"}
                """);

        CatalogRouteSummary route = page.routes().getFirst();
        assertTrue(page.hasMore());
        assertEquals("eyJwYWdlIjoyfQ", page.nextCursor());
        assertTrue(route.publisherVerified());
        assertEquals(3, route.version());
        assertEquals(2381, route.downloads());
    }

    @Test
    void parsesRoutePayloadOnlyFromTheDetailBoundary() {
        CatalogRouteDetails details = CatalogJson.parseDetails("""
                {"route":{
                  "id":"Abcdefghijklmnopqrstuv",
                  "title":"Coal Route",
                  "authorName":"Babbur",
                  "visibility":"public",
                  "zoneId":"crystal_hollows",
                  "zoneLabel":"Crystal Hollows",
                  "waypointCount":2,
                  "groupCount":1,
                  "codecVersion":8,
                  "payload":"WP:test"
                }}
                """);

        assertEquals(ID, details.summary().id());
        assertEquals("WP:test", details.payload());
        assertThrows(IllegalArgumentException.class, () -> CatalogJson.parseDetails("""
                {"route":{
                  "id":"Abcdefghijklmnopqrstuv",
                  "title":"Coal Route",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":1,"groupCount":1,"codecVersion":8,
                  "payload":"not-a-route"
                }}
                """));
    }

    @Test
    void keepsTheFirstDuplicateIdAndRejectsAnAllInvalidPage() {
        String route = """
                {"id":"Abcdefghijklmnopqrstuv","title":"Route",
                 "zoneId":"hub","zoneLabel":"Hub",
                 "waypointCount":1,"groupCount":1,"codecVersion":8}
                """;
        CatalogPage page = CatalogJson.parsePage(
                "{\"routes\":[" + route + "," + route + "]}");
        assertEquals(1, page.routes().size());
        assertEquals(ID, page.routes().getFirst().id());

        assertThrows(IllegalArgumentException.class, () -> CatalogJson.parsePage("""
                {"routes":[{
                  "id":"Abcdefghijklmnopqrstuv","title":"Route",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":-1,"groupCount":1,"codecVersion":8
                }]}
                """));
    }

    @Test
    void usesUnicodeCodePointsForSummaryTitleAndDescriptionBounds() {
        for (int count : new int[]{40, 41, 80}) {
            CatalogPage page = CatalogJson.parsePage(pageJson(
                    summaryJson(ID, EMOJI.repeat(count), "Description")));
            assertEquals(count, page.routes().getFirst().title()
                    .codePointCount(0, page.routes().getFirst().title().length()));
        }
        assertThrows(IllegalArgumentException.class, () -> CatalogJson.parsePage(pageJson(
                summaryJson(ID, EMOJI.repeat(81), "Description"))));

        for (int count : new int[]{250, 251, 500}) {
            CatalogPage page = CatalogJson.parsePage(pageJson(
                    summaryJson(ID, "Route", EMOJI.repeat(count))));
            assertEquals(count, page.routes().getFirst().description()
                    .codePointCount(0, page.routes().getFirst().description().length()));
        }
        assertThrows(IllegalArgumentException.class, () -> CatalogJson.parsePage(pageJson(
                summaryJson(ID, "Route", EMOJI.repeat(501)))));
    }

    @Test
    void isolatesMalformedListRowsAndKeepsValidRowsInWireOrder() {
        String first = summaryJson(ID, "First", "First valid route");
        String malformed = summaryJson(
                THIRD_ID, EMOJI.repeat(81), "Malformed route");
        String second = summaryJson(SECOND_ID, "Second", "Second valid route");
        String duplicate = summaryJson(ID, "Duplicate", "Duplicate route");

        CatalogPage page = CatalogJson.parsePage(
                "{\"routes\":[" + String.join(",", first, malformed, second, duplicate)
                        + "],\"hasMore\":true,\"nextCursor\":\"next_page\"}");

        assertEquals(2, page.routes().size());
        assertEquals(ID, page.routes().get(0).id());
        assertEquals("First", page.routes().get(0).title());
        assertEquals(SECOND_ID, page.routes().get(1).id());
        assertTrue(page.hasMore());
        assertEquals("next_page", page.nextCursor());
    }

    @Test
    void appliesCodePointBoundsToDetailAndPublishResponses() {
        String title = EMOJI.repeat(80);
        String description = EMOJI.repeat(500);
        JsonObject route = summaryObject(ID, title, description);
        route.addProperty("payload", "WP:test");

        JsonObject detailRoot = new JsonObject();
        detailRoot.add("route", route);
        CatalogRouteDetails details = CatalogJson.parseDetails(detailRoot.toString());
        assertEquals(title, details.summary().title());
        assertEquals(description, details.summary().description());

        JsonObject publishRoot = new JsonObject();
        publishRoot.add("route", route);
        publishRoot.addProperty("manageToken", "token");
        CatalogPublishResult result = CatalogJson.parsePublishResult(publishRoot.toString());
        assertEquals(title, result.route().title());
        assertEquals(description, result.route().description());

        JsonObject invalidTitle = summaryObject(ID, EMOJI.repeat(81), "Description");
        invalidTitle.addProperty("payload", "WP:test");
        JsonObject invalidDetailRoot = new JsonObject();
        invalidDetailRoot.add("route", invalidTitle);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.parseDetails(invalidDetailRoot.toString()));

        JsonObject invalidDescription = summaryObject(ID, "Route", EMOJI.repeat(501));
        JsonObject invalidPublishRoot = new JsonObject();
        invalidPublishRoot.add("route", invalidDescription);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.parsePublishResult(invalidPublishRoot.toString()));
    }

    @Test
    void rejectsCoercedOrFractionalProtocolIntegers() {
        assertThrows(IllegalArgumentException.class, () -> CatalogJson.parsePage("""
                {"routes":[{
                  "id":"Abcdefghijklmnopqrstuv","title":"Route",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":"1","groupCount":1,"codecVersion":9
                }]}
                """));
        assertThrows(IllegalArgumentException.class, () -> CatalogJson.parsePage("""
                {"routes":[{
                  "id":"Abcdefghijklmnopqrstuv","title":"Route",
                  "zoneId":"hub","zoneLabel":"Hub",
                  "waypointCount":1.5,"groupCount":1,"codecVersion":9
                }]}
                """));
    }

    @Test
    void requiresAndTrimsTenCharacterDescription() {
        CatalogPublishRequest valid = publishRequest("  1234567890  ");
        String body = CatalogJson.publishBody(valid);
        assertTrue(body.contains("\"description\":\"1234567890\""));

        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.publishBody(publishRequest("123456789")));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.publishBody(publishRequest("          ")));

        CatalogPublishRequest establishedPublisher = new CatalogPublishRequest(
                "WP:test", "Route", "Description",
                CatalogPublishRequest.Visibility.PUBLIC, "hub", null);
        assertFalse(CatalogJson.publishBody(establishedPublisher).contains("publisherName"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.publishBody(new CatalogPublishRequest(
                        "WP:test", "Route", "Description",
                        CatalogPublishRequest.Visibility.PUBLIC, "hub", "two words")));
    }

    @Test
    void publishesUnicodeBoundsByCodePointAndKeepsThePayloadByteCap() {
        assertDoesNotThrow(() -> CatalogJson.publishBody(new CatalogPublishRequest(
                "WP:test", EMOJI.repeat(80), EMOJI.repeat(500),
                CatalogPublishRequest.Visibility.PUBLIC, "hub", "Tester")));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.publishBody(new CatalogPublishRequest(
                        "WP:test", EMOJI.repeat(81), "Description",
                        CatalogPublishRequest.Visibility.PUBLIC, "hub", "Tester")));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.publishBody(new CatalogPublishRequest(
                        "WP:test", "Route", EMOJI.repeat(501),
                        CatalogPublishRequest.Visibility.PUBLIC, "hub", "Tester")));

        int maximumPayloadBytes = 256 * 1024;
        String exactAsciiPayload = "WP:" + "A".repeat(maximumPayloadBytes - 3);
        assertDoesNotThrow(() -> CatalogJson.publishBody(new CatalogPublishRequest(
                exactAsciiPayload, "Route", "Description",
                CatalogPublishRequest.Visibility.PUBLIC, "hub", "Tester")));
        String oversizedUtf8Payload = "WP:" + EMOJI.repeat(maximumPayloadBytes / 4);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.publishBody(new CatalogPublishRequest(
                        oversizedUtf8Payload, "Route", "Description",
                        CatalogPublishRequest.Visibility.PUBLIC, "hub", "Tester")));
    }

    @Test
    void rejectsInvalidOptionalCursorTypesAndFormats() {
        String route = """
                {"id":"Abcdefghijklmnopqrstuv","title":"Route",
                 "zoneId":"hub","zoneLabel":"Hub",
                 "waypointCount":1,"groupCount":1,"codecVersion":9}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.parsePage(
                        "{\"routes\":[" + route + "],\"nextCursor\":12}"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogJson.parsePage(
                        "{\"routes\":[" + route + "],\"nextCursor\":\"bad cursor\"}"));
    }

    private static CatalogPublishRequest publishRequest(String description) {
        return new CatalogPublishRequest(
                "WP:test", "Route", description,
                CatalogPublishRequest.Visibility.PUBLIC, "hub", "Tester");
    }

    private static String pageJson(String route) {
        return "{\"routes\":[" + route + "]}";
    }

    private static String summaryJson(
            String id, String title, String description) {
        return summaryObject(id, title, description).toString();
    }

    private static JsonObject summaryObject(
            String id, String title, String description) {
        JsonObject route = new JsonObject();
        route.addProperty("id", id);
        route.addProperty("title", title);
        route.addProperty("description", description);
        route.addProperty("zoneId", "hub");
        route.addProperty("zoneLabel", "Hub");
        route.addProperty("waypointCount", 1);
        route.addProperty("groupCount", 1);
        route.addProperty("codecVersion", 9);
        return route;
    }
}
