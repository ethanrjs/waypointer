package com.babbur.waypointer.screen;

import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogRouteRowButtonTest {

    @Test
    void downloadCountsStayCompactInRowMetadata() {
        LocalizedNumberFormatter numbers =
                LocalizedNumberFormatter.forMinecraftLocale("en_us");
        assertEquals("0", CatalogRouteRowButton.compactCount(0, numbers));
        assertEquals("1", CatalogRouteRowButton.compactCount(1, numbers));
        assertEquals("999", CatalogRouteRowButton.compactCount(999, numbers));
        assertEquals("1k", CatalogRouteRowButton.compactCount(1_000, numbers));
        assertEquals("1.2k", CatalogRouteRowButton.compactCount(1_234, numbers));
        assertEquals("9.9k", CatalogRouteRowButton.compactCount(9_999, numbers));
        assertEquals("12k", CatalogRouteRowButton.compactCount(12_345, numbers));
        assertEquals("999k", CatalogRouteRowButton.compactCount(999_999, numbers));
        assertEquals("1M", CatalogRouteRowButton.compactCount(1_000_000, numbers));
        assertEquals("2.5M", CatalogRouteRowButton.compactCount(2_500_000, numbers));
    }

    @Test
    void negativeServerCountsNeverRenderBelowZero() {
        LocalizedNumberFormatter numbers =
                LocalizedNumberFormatter.forMinecraftLocale("en_us");
        assertEquals("0", CatalogRouteRowButton.compactCount(-5, numbers));
    }
}
