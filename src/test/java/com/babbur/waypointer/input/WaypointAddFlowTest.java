package com.babbur.waypointer.input;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointAddFlowTest {

    @Test
    void waypointAddedMessageOffersExactAllAndPartyCommands() {
        Component message = WaypointAddFlow.waypointAddedMessage(
                2, Waypoint.at(12, 70, -4), true);

        assertEquals("Added waypoint 2 at 12, 70, -4 [All] [Party]", message.getString());
        assertEquals(2, message.getSiblings().size());
        assertCommand(message.getSiblings().get(0), "/ac 12, 70, -4");
        assertCommand(message.getSiblings().get(1), "/pc 12, 70, -4");
    }

    @Test
    void waypointAddedMessageOmitsShareActionsWhenDisabled() {
        Component message = WaypointAddFlow.waypointAddedMessage(
                2, Waypoint.at(12, 70, -4), false);

        assertEquals("Added waypoint 2 at 12, 70, -4", message.getString());
        assertTrue(message.getSiblings().isEmpty());
    }

    private static void assertCommand(Component action, String expected) {
        ClickEvent.RunCommand command = assertInstanceOf(ClickEvent.RunCommand.class,
                action.getStyle().getClickEvent());
        assertEquals(expected, command.command());
        assertTrue(action.getStyle().isUnderlined());
    }
}
