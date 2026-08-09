package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedIdConfirmationTest {

    @Test
    void matchRequiresTheSameIdsInsideTheWindow() {
        TimedIdConfirmation confirmation = new TimedIdConfirmation();
        confirmation.arm(List.of("first", "second"), 1_000L, 1_000L);

        assertTrue(confirmation.matches(List.of("first", "second"), 1_500L));
        assertFalse(confirmation.matches(List.of("first"), 1_500L));
        assertFalse(confirmation.matches(List.of("first", "second"), 2_000L));
    }

    @Test
    void clearDisarmsConfirmation() {
        TimedIdConfirmation confirmation = new TimedIdConfirmation();
        confirmation.arm(List.of("first"), 1_000L, 1_000L);
        confirmation.clear();

        assertFalse(confirmation.matches(List.of("first"), 1_500L));
    }

    @Test
    void expireReportsOnlyAnArmedWindow() {
        TimedIdConfirmation confirmation = new TimedIdConfirmation();
        confirmation.arm(List.of("first"), 1_000L, 1_000L);

        assertFalse(confirmation.expire(1_999L));
        assertTrue(confirmation.expire(2_000L));
        assertFalse(confirmation.expire(2_001L));
    }
}
