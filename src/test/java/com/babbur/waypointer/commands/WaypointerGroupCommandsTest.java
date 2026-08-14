package com.babbur.waypointer.commands;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerGroupCommandsTest {

    @Test
    void routeMutationCommandsApplyValidatedChangesAndReportSuccess() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointerConfig config = new WaypointerConfig();
        CommandMessages messages = new CommandMessages();
        WaypointerGroupCommands commands = commands(manager, config);

        assertEquals(1, commands.runCreateGroup(messages.source(), "First"));
        assertEquals(1, commands.runCreateGroup(messages.source(), "Second"));
        WaypointGroup first = manager.allGroupsList().get(0);
        WaypointGroup second = manager.allGroupsList().get(1);

        assertEquals("hub", first.zoneId());
        assertEquals(1, commands.runRenameGroup(messages.source(), 0, "  Renamed  "));
        assertEquals("Renamed", first.name());
        assertEquals(1, commands.runSetGroupZone(messages.source(), 0, "current"));
        assertEquals("hub", first.zoneId());
        assertEquals(1, commands.runSetGroupZone(messages.source(), 0, "crystal_hollows"));
        assertEquals("crystal_hollows", first.zoneId());
        assertEquals(1, commands.runSetGroupMode(messages.source(), 0, "static"));
        assertEquals(WaypointGroup.LoadMode.STATIC, first.loadMode());
        assertEquals(1, commands.runSetGroupRadius(messages.source(), 0, 4.5D));
        assertEquals(4.5D, first.defaultRadius());
        assertEquals(1, commands.runSetGroupSkipAhead(messages.source(), 0, "on"));
        assertTrue(first.skipAheadEnabled());
        assertEquals(1, commands.runSetGroupEnabled(messages.source(), 0, false));
        assertFalse(first.enabled());
        assertEquals(1, commands.runSetGroupColorMode(messages.source(), 0, "manual"));
        assertEquals(WaypointGroup.GradientMode.MANUAL, first.gradientMode());
        assertEquals(1, commands.runSetGroupStaticColor(messages.source(), 0, "#123456"));
        assertEquals(0x123456, first.staticColor());
        assertEquals(WaypointGroup.GradientMode.STATIC, first.gradientMode());
        assertEquals(1, commands.runSetGroupGradient(
                messages.source(), 0, "abcdef", "010203"));
        assertEquals(0xABCDEF, first.gradientStartColor());
        assertEquals(0x010203, first.gradientEndColor());
        assertEquals(WaypointGroup.GradientMode.AUTO, first.gradientMode());

        second.setZoneId(first.zoneId());
        assertEquals(1, commands.runMoveGroup(messages.source(), 1, -1));
        assertSame(second, manager.allGroupsList().get(0));
        assertSame(first, manager.allGroupsList().get(1));
        assertTrue(messages.errors.isEmpty());
        assertTrue(messages.feedback.size() >= 13);
    }

    @Test
    void invalidMutationArgumentsLeaveTheRouteUnchangedAndReportErrors() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        manager.add(group);
        CommandMessages messages = new CommandMessages();
        WaypointerGroupCommands commands = commands(manager, new WaypointerConfig());

        assertEquals(0, commands.runRenameGroup(messages.source(), 4, "Missing"));
        assertEquals(0, commands.runSetGroupZone(messages.source(), 0, "  "));
        assertEquals(0, commands.runSetGroupZone(messages.source(), 0, "current"));
        assertEquals(0, commands.runSetGroupMode(messages.source(), 0, "unknown"));
        assertEquals(0, commands.runSetGroupRadius(messages.source(), 4, 2.0D));
        assertEquals(0, commands.runSetGroupSkipAhead(messages.source(), 0, "sometimes"));
        assertEquals(0, commands.runSetGroupEnabled(messages.source(), 4, false));
        assertEquals(0, commands.runMoveGroup(messages.source(), 0, -1));
        assertEquals(0, commands.runSetGroupColorMode(messages.source(), 0, "rainbow"));
        assertEquals(0, commands.runSetGroupStaticColor(messages.source(), 0, "xyz"));
        assertEquals(0, commands.runSetGroupGradient(messages.source(), 0, "ffffff", "nope"));

        assertEquals("Route", group.name());
        assertEquals("hub", group.zoneId());
        assertTrue(group.enabled());
        assertTrue(messages.errors.size() >= 10);
        assertNull(commands.groupAtIndexOrError(messages.source(), -1));
    }

    @Test
    void listAndDeleteCommandsCoverEmptyWarningAndConfirmedFlows() {
        ActiveGroupManager manager = new ActiveGroupManager();
        CommandMessages messages = new CommandMessages();
        WaypointerGroupCommands commands = commands(manager, new WaypointerConfig());

        assertEquals(0, commands.runListGroups(messages.source()));
        assertEquals(0, commands.runDeleteGroup(messages.source(), 0, true));

        WaypointGroup enabled = WaypointGroup.create("Enabled", "hub");
        enabled.add(Waypoint.at(1, 2, 3));
        WaypointGroup disabled = WaypointGroup.create("Disabled", "hub");
        disabled.setEnabled(false);
        manager.add(enabled);
        manager.add(disabled);

        assertEquals(2, commands.runListGroups(messages.source()));
        assertEquals(0, commands.runDeleteGroup(messages.source(), 0, false));
        assertEquals(2, manager.allGroupsList().size());
        assertEquals(1, commands.runDeleteGroup(messages.source(), 0, true));
        assertEquals(List.of(disabled), manager.allGroupsList());
        assertFalse(messages.feedback.isEmpty());
        assertFalse(messages.errors.isEmpty());
    }

    @Test
    void parsersAcceptDocumentedAliasesAndRejectUnknownValues() {
        assertEquals(WaypointGroup.GradientMode.STATIC,
                WaypointerGroupCommands.parseGradientMode(" solid "));
        assertEquals(WaypointGroup.GradientMode.AUTO,
                WaypointerGroupCommands.parseGradientMode("gradient"));
        assertEquals(WaypointGroup.GradientMode.MANUAL,
                WaypointerGroupCommands.parseGradientMode("MANUAL"));
        assertNull(WaypointerGroupCommands.parseGradientMode(null));

        assertTrue(WaypointerGroupCommands.parseToggleState(null, false));
        assertFalse(WaypointerGroupCommands.parseToggleState("flip", true));
        assertTrue(WaypointerGroupCommands.parseToggleState("yes", false));
        assertFalse(WaypointerGroupCommands.parseToggleState("0", true));
        assertNull(WaypointerGroupCommands.parseToggleState("maybe", true));
    }

    private static WaypointerGroupCommands commands(
            ActiveGroupManager manager, WaypointerConfig config) {
        return new WaypointerGroupCommands(manager, config, null);
    }

    private static final class CommandMessages {
        private final List<Component> feedback = new ArrayList<>();
        private final List<Component> errors = new ArrayList<>();

        FabricClientCommandSource source() {
            return (FabricClientCommandSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{FabricClientCommandSource.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "sendFeedback" -> {
                            feedback.add((Component) args[0]);
                            yield null;
                        }
                        case "sendError" -> {
                            errors.add((Component) args[0]);
                            yield null;
                        }
                        case "toString" -> "CommandMessagesSource";
                        default -> throw new AssertionError(
                                "Unexpected command source call: " + method.getName());
                    });
        }
    }
}
