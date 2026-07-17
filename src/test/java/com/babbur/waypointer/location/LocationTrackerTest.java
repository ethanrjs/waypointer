package com.babbur.waypointer.location;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LocationTrackerTest {

    @Test
    void sourceSelectionUsesHypixelApiWhenAvailableAndScoreboardFallbackOtherwise() {
        assertInstanceOf(HypixelApiZoneSource.class, LocationTracker.createSource(true));
        assertInstanceOf(ScoreboardZoneResolver.class, LocationTracker.createSource(false));
    }
}
