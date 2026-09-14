package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogRouteUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouteReuploadScreenTest {
    @Test
    void confirmingReplacementIncludesPendingMetadataWithoutOverwritingUnchangedFields() {
        CatalogRouteUpdate edited = RouteReuploadScreen.replacementUpdate(
                CatalogRouteUpdate.metadata("New title", "New description",
                        CatalogPublishRequest.Visibility.UNLISTED), "WP:replacement", "hub");
        assertEquals("New title", edited.title());
        assertEquals("New description", edited.description());
        assertEquals(CatalogPublishRequest.Visibility.UNLISTED, edited.visibility());
        assertEquals("WP:replacement", edited.payload());
        assertEquals("hub", edited.zoneId());

        CatalogRouteUpdate unchanged = RouteReuploadScreen.replacementUpdate(
                CatalogRouteUpdate.metadata(null, null, null), "WP:replacement", "multiple");
        assertNull(unchanged.title());
        assertNull(unchanged.description());
        assertNull(unchanged.visibility());
        assertEquals("WP:replacement", unchanged.payload());
        assertEquals("multiple", unchanged.zoneId());
    }
}
