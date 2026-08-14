package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublishFormModelTest {

    @Test
    void validationIsAnExplicitFormStateMachine() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        CatalogPublishFormModel form = new CatalogPublishFormModel(group, null);
        assertEquals(CatalogPublishFormModel.Validation.EMPTY_ROUTE, form.validation());

        group.add(Waypoint.at(1, 64, 2));
        group.setTemp(true);
        assertEquals(CatalogPublishFormModel.Validation.TEMPORARY_ROUTE, form.validation());
        group.setTemp(false);

        form.setTitle("  ");
        assertEquals(CatalogPublishFormModel.Validation.TITLE_REQUIRED, form.validation());
        form.setTitle("Published route");
        assertEquals(CatalogPublishFormModel.Validation.DESCRIPTION_TOO_SHORT,
                form.validation());

        form.setDescription("A useful route description.");
        assertNull(form.validation());
        assertTrue(form.valid());
    }

    @Test
    void editsNormalizeOnceAndNotifyOnlyForRealChanges() {
        AtomicInteger edits = new AtomicInteger();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        CatalogPublishFormModel form = new CatalogPublishFormModel(group, edits::incrementAndGet);

        form.setTitle("Route");
        form.setDescription("line one\r\nline two\u0000");
        form.setDescription("line one\nline two");
        form.setVisibility(CatalogPublishRequest.Visibility.PUBLIC);

        assertEquals(2, edits.get());
        assertEquals("line one\nline two", form.description());
        assertEquals(CatalogPublishRequest.Visibility.PUBLIC, form.visibility());
    }

    @Test
    void descriptionLengthCountsCodePointsAndCapsInput() {
        assertFalse(CatalogPublishFormModel.descriptionLengthValid("123456789"));
        assertTrue(CatalogPublishFormModel.descriptionLengthValid("😀".repeat(500)));
        assertFalse(CatalogPublishFormModel.descriptionLengthValid("😀".repeat(501)));
        assertEquals(500, CatalogPublishFormModel.normalizeDescriptionInput(
                "😀".repeat(501)).codePointCount(0, 1000));
        assertEquals(0, CatalogPublishFormModel.descriptionCharactersRemaining(
                "😀".repeat(500)));
    }
}
