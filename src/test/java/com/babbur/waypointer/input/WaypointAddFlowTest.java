package com.babbur.waypointer.input;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointAddFlowTest {

    @Test
    void waypointAddedMessageUsesOneCompactLineWithShareActions() {
        Component message = WaypointAddFlow.waypointAddedMessage(
                "Route 1", 2, Waypoint.at(12, 70, -4), true);

        assertEquals("Added Waypoint 3 to \"Route 1\" at (12, 70, -4) [All] [Party]",
                message.getString());
        List<Component> buttons = message.getSiblings().stream()
                .filter(component -> component.getStyle().getClickEvent() != null)
                .toList();
        assertEquals(2, buttons.size());
        assertCommand(buttons.get(0), "/ac 12, 70, -4");
        assertCommand(buttons.get(1), "/pc 12, 70, -4");
        List<Component> separators = message.getSiblings().stream()
                .filter(component -> component.getString().equals(" "))
                .toList();
        assertEquals(2, separators.size());
        assertTrue(separators.stream().allMatch(component -> !component.getStyle().isUnderlined()
                && component.getStyle().getClickEvent() == null));
    }

    @Test
    void waypointAddedMessageOmitsShareActionsWhenDisabled() {
        Component message = WaypointAddFlow.waypointAddedMessage(
                "Route 1", 2, Waypoint.at(12, 70, -4), false);

        assertEquals("Added Waypoint 3 to \"Route 1\" at (12, 70, -4)", message.getString());
        assertFalse(message.getSiblings().stream()
                .anyMatch(component -> component.getStyle().getClickEvent() != null));
    }

    private static void assertCommand(Component action, String expected) {
        ClickEvent.RunCommand command = assertInstanceOf(ClickEvent.RunCommand.class,
                action.getStyle().getClickEvent());
        assertEquals(expected, command.command());
        assertTrue(action.getStyle().isUnderlined());
    }
}
