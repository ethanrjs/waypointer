package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePublishScreenTest {

    @Test
    void descriptionRequiresTenToFiveHundredTrimmedCharacters() {
        assertFalse(RoutePublishScreen.descriptionLengthValid(null));
        assertFalse(RoutePublishScreen.descriptionLengthValid("         "));
        assertFalse(RoutePublishScreen.descriptionLengthValid("123456789"));
        assertTrue(RoutePublishScreen.descriptionLengthValid("1234567890"));
        assertTrue(RoutePublishScreen.descriptionLengthValid("  1234567890  "));
        assertTrue(RoutePublishScreen.descriptionLengthValid("x".repeat(500)));
        assertFalse(RoutePublishScreen.descriptionLengthValid("x".repeat(501)));
        assertTrue(RoutePublishScreen.descriptionLengthValid("😀".repeat(500)));
        assertFalse(RoutePublishScreen.descriptionLengthValid("😀".repeat(501)));
    }

    @Test
    void multilineDescriptionPreservesLinesAndCountsDownAfterOneHundred() {
        assertTrue(RoutePublishScreen.normalizeDescriptionInput("line one\r\nline two")
                .equals("line one\nline two"));
        assertTrue(RoutePublishScreen.normalizeDescriptionInput("😀".repeat(501))
                .equals("😀".repeat(500)));
        assertTrue(RoutePublishScreen.descriptionCharactersRemaining("x".repeat(99)) < 0);
        assertTrue(RoutePublishScreen.descriptionCharactersRemaining("x".repeat(100)) == 400);
        assertTrue(RoutePublishScreen.descriptionCharactersRemaining("😀".repeat(500)) == 0);
    }
}
