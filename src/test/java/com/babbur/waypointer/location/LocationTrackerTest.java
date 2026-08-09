package com.babbur.waypointer.location;

import org.junit.jupiter.api.Test;

import com.babbur.waypointer.core.Zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocationTrackerTest {

    @Test
    void sourceSelectionUsesHypixelApiWhenAvailableAndScoreboardFallbackOtherwise() {
        assertInstanceOf(HypixelApiZoneSource.class, LocationTracker.createSource(true));
        assertInstanceOf(ScoreboardZoneResolver.class, LocationTracker.createSource(false));
    }

    @Test
    void privateWorldOverridesMissingServerZoneAndClearsWhenLeaving() {
        assertEquals(Zone.PRIVATE_WORLD,
                LocationTracker.zoneAfterPrivateWorldCheck(null, true, false));
        assertEquals(Zone.fromId("hub"),
                LocationTracker.zoneAfterPrivateWorldCheck(Zone.fromId("hub"), true, true));
        assertNull(LocationTracker.zoneAfterPrivateWorldCheck(
                Zone.PRIVATE_WORLD, false, false));
        assertNull(LocationTracker.zoneAfterPrivateWorldCheck(
                Zone.PRIVATE_WORLD, true, true));
        assertNull(LocationTracker.zoneAfterPrivateWorldCheck(
                Zone.fromId("hub"), false, true));
    }
}
