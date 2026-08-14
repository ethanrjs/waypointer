package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherNamePolicyTest {
    @Test
    void matchesMinecraftNameRulesExactly() {
        assertTrue(PublisherNamePolicy.valid("abc"));
        assertTrue(PublisherNamePolicy.valid("Ethan_1234567890"));
        assertFalse(PublisherNamePolicy.valid("ab"));
        assertFalse(PublisherNamePolicy.valid("x".repeat(17)));
        assertFalse(PublisherNamePolicy.valid("two words"));
        assertFalse(PublisherNamePolicy.valid("hyphen-name"));
        assertFalse(PublisherNamePolicy.valid("名前"));
        assertFalse(PublisherNamePolicy.valid(" name"));
    }
}
