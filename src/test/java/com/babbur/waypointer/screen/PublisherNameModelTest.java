package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherNameModelTest {
    @Test
    void invalidSuggestionStartsEmptyAndCannotAdvance() {
        PublisherNameModel model = new PublisherNameModel("two words");

        assertEquals("", model.name());
        assertFalse(model.valid());
        assertEquals(PublisherNameModel.AdvanceResult.REJECTED, model.advance());
        assertEquals(PublisherNameModel.Stage.ENTRY, model.stage());
    }

    @Test
    void validNameRequiresASeparateConfirmationStep() {
        PublisherNameModel model = new PublisherNameModel("Player_123");

        assertTrue(model.valid());
        assertEquals(PublisherNameModel.AdvanceResult.SHOW_CONFIRMATION, model.advance());
        assertEquals(PublisherNameModel.Stage.CONFIRM, model.stage());
        assertEquals(PublisherNameModel.AdvanceResult.CONFIRMED, model.advance());
        assertEquals(PublisherNameModel.Stage.COMPLETE, model.stage());
        assertEquals("Player_123", model.confirmedName());
        assertEquals(PublisherNameModel.AdvanceResult.REJECTED, model.advance());
    }

    @Test
    void backFromConfirmationPreservesTheNameForEditing() {
        PublisherNameModel model = new PublisherNameModel(null);
        model.edit("ChosenName");
        model.advance();

        assertFalse(model.back());
        assertEquals(PublisherNameModel.Stage.ENTRY, model.stage());
        assertEquals("ChosenName", model.name());
        assertTrue(model.back());
    }

    @Test
    void screenCannotDeliverANameBeforeTheExplicitConfirmationAction() {
        PublisherNameModel model = new PublisherNameModel("FirstName");
        assertThrows(IllegalStateException.class, model::confirmedName);

        model.advance();
        assertThrows(IllegalStateException.class, model::confirmedName);
        assertFalse(model.back());
        model.edit("FinalName");
        model.advance();
        model.advance();

        assertEquals("FinalName", model.confirmedName());
    }
}
