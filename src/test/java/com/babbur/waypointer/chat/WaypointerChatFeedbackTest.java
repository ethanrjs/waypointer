package com.babbur.waypointer.chat;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerChatFeedbackTest {

    @AfterEach
    void clearSuppressedMessages() {
        WaypointerChatFeedback.clearForTests();
    }

    @Test
    void suppressedWaypointFeedbackIsConsumedOnce() {
        Component feedback = Component.literal("Added waypoint 0 at 100, 64, 200");

        assertSame(feedback, WaypointerChatFeedback.suppress(feedback));
        assertTrue(WaypointerChatFeedback.consumeIfSuppressed(
                Component.literal("Added waypoint 0 at 100, 64, 200")));
        assertFalse(WaypointerChatFeedback.consumeIfSuppressed(
                Component.literal("Added waypoint 0 at 100, 64, 200")));
    }

    @Test
    void unrelatedCoordinateMessagesStillReachDetector() {
        WaypointerChatFeedback.suppress(
                Component.literal("Added waypoint 0 at 100, 64, 200"));

        assertFalse(WaypointerChatFeedback.consumeIfSuppressed(
                Component.literal("Player: meet at 100, 64, 200")));
    }

    @Test
    void nonCoordinateFeedbackIsNotTracked() {
        WaypointerChatFeedback.suppress(Component.literal("Deleted group \"Mines\""));

        assertFalse(WaypointerChatFeedback.consumeIfSuppressed(
                Component.literal("Deleted group \"Mines\"")));
    }
}
