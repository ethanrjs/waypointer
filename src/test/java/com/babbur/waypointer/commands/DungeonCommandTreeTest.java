package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonCommandTreeTest {

    @Test
    void dungeonAuthoringCommandsAreNotRegistered() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        var canonical = dispatcher.getRoot().getChild("wpd");
        assertSame(canonical, dispatcher.getRoot().getChild("waypointer-dungeon").getRedirect());
        for (String root : List.of("wpd", "waypointer-dungeon")) {
            var rootNode = dispatcher.getRoot().getChild(root);
            assertNotNull(rootNode, "missing dungeon command root " + root);
            if (rootNode.getRedirect() != null) rootNode = rootNode.getRedirect();
            assertNull(rootNode.getChild("test"));
            assertNull(rootNode.getChild("reset"));
            assertNull(rootNode.getChild("highlight"));
            assertNull(rootNode.getChild("breakbox"));
            assertNull(rootNode.getChild("routes"));

            var room = rootNode.getChild("room");
            assertNotNull(room);
            assertNull(room.getChild("create"));
            assertNull(room.getChild("rename"));
            assertNull(room.getChild("fingerprint"));

            assertNull(rootNode.getChild("waypoint"));
        }
    }

    @Test
    void retainedDungeonCommandsParseWithoutSyntaxErrors() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        List<String> commands = List.of(
                "wpd info",
                "wpd room list",
                "wpd import routes.json",
                "wpd route next",
                "wpd route reset",
                "wpd route found 1",
                "wpd rotate sw",
                "wpd rotate auto",
                "wpd toggle enabled",
                "wpd toggle hidecompleted",
                "wpd toggle debug",
                "waypointer-dungeon info");

        for (String command : commands) {
            assertParses(dispatcher, command);
        }
    }

    @Test
    void dungeonImportReaderRejectsInputPastItsByteLimit(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("routes.json");
        Files.writeString(file, "oversized");

        DungeonCommands.ImportReadResult result = DungeonCommands.readImportFile(file, 8);

        assertTrue(result.readFailure());
        assertTrue(result.error().contains("too large"));
        assertNull(result.result());
    }

    @Test
    void authoredSecretCheckRejectsNonexistentRouteSecrets() {
        WaypointGroup route = WaypointGroup.create("Command Route", "command-route-room");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.add(Waypoint.at(0, 70, 0));
        route.add(Waypoint.at(1, 70, 0).withSubwaypoint(true));
        route.add(Waypoint.at(2, 70, 0));

        assertTrue(DungeonCommands.isAuthoredSecretIndex(route, 1));
        assertTrue(DungeonCommands.isAuthoredSecretIndex(route, 2));
        assertFalse(DungeonCommands.isAuthoredSecretIndex(route, 0));
        assertFalse(DungeonCommands.isAuthoredSecretIndex(route, 3));
        assertEquals("#1, #2", DungeonCommands.availableAuthoredSecretIndexes(route));
    }

    private static CommandDispatcher<FabricClientCommandSource> registeredDispatcher() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        DungeonCommands commands = new DungeonCommands(null, null, null);
        Method register = DungeonCommands.class.getDeclaredMethod(
                "register", CommandDispatcher.class);
        register.setAccessible(true);
        register.invoke(commands, dispatcher);
        return dispatcher;
    }

    private static void assertParses(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                     String command) {
        ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
        assertTrue(parsed.getExceptions().isEmpty(),
                command + " should parse without syntax errors: " + parsed.getExceptions());
        assertFalse(parsed.getReader().canRead(),
                command + " left unread input: " + parsed.getReader().getRemaining());
        assertTrue(!parsed.getContext().getNodes().isEmpty(),
                command + " should produce parsed command nodes");
        assertTrue(hasCommand(parsed.getContext()),
                command + " should resolve to an executable command");
    }

    private static boolean hasCommand(CommandContextBuilder<FabricClientCommandSource> context) {
        for (CommandContextBuilder<FabricClientCommandSource> current = context;
             current != null;
             current = current.getChild()) {
            if (current.getCommand() != null) return true;
        }
        return false;
    }

}
