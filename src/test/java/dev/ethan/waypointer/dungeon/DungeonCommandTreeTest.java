package dev.ethan.waypointer.dungeon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonCommandTreeTest {

    @Test
    void resetConfirmCommandsParseOnDungeonAliases() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        for (String root : List.of("wpd", "waypointer-dungeon")) {
            assertNotNull(dispatcher.getRoot().getChild(root), "missing dungeon command root " + root);
            assertParses(dispatcher, root + " reset");
            assertParses(dispatcher, root + " reset confirm");
        }
    }

    @Test
    void criticalDungeonAuthoringCommandsParseWithoutSyntaxErrors() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        List<String> commands = List.of(
                "wpd info",
                "wpd test",
                "wpd room create admin Admin Room",
                "wpd room rename Renamed Room",
                "wpd room fingerprint add",
                "wpd room list",
                "wpd waypoint list",
                "wpd waypoint add chest Secret Chest",
                "wpd waypoint remove 0",
                "wpd waypoint move 0",
                "wpd waypoint trigger 0 chest",
                "wpd waypoint trigger 0 dungeonbreaker",
                "wpd highlight list 0",
                "wpd highlight add 0 filled",
                "wpd highlight remove 0 1",
                "wpd breakbox add 0",
                "wpd route next",
                "wpd route reset",
                "wpd route found 1",
                "wpd rotate sw",
                "wpd rotate auto",
                "wpd toggle enabled",
                "wpd toggle greencheck",
                "wpd toggle hidecompleted",
                "wpd toggle debug");

        for (String command : commands) {
            assertParses(dispatcher, command);
        }
    }

    @Test
    void authoredSecretCheckRejectsNonexistentRouteSecrets() {
        DungeonRoomData.clearAllCustom();
        try {
            DungeonRoom room = new DungeonRoom(DungeonRoomType.ROOM, DungeonRoomShape.ONE_BY_ONE,
                    Direction.NW, 0, 0, List.of(DungeonRoom.packSegment(0, 0)));
            DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                    "command-route-room", "Command Route", room);
            DungeonWaypoint support = waypoint("support", 0);
            DungeonRoom authoredRoom = room.withDefinition(definition.id(), definition.displayName());
            DungeonRoomData.addWaypoint(definition.id(), support);
            DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
            DungeonRoomData.addWaypoint(definition.id(), waypoint("third", 3));

            assertFalse(DungeonCommands.isProgressSecretWaypoint(support));
            assertEquals("support", DungeonCommands.secretIndexDescriptor(support));
            assertTrue(DungeonCommands.isAuthoredSecretIndex(authoredRoom, 1));
            assertTrue(DungeonCommands.isAuthoredSecretIndex(authoredRoom, 3));
            assertFalse(DungeonCommands.isAuthoredSecretIndex(authoredRoom, 0));
            assertFalse(DungeonCommands.isAuthoredSecretIndex(authoredRoom, 2));
            assertEquals("#1, #3", DungeonCommands.availableAuthoredSecretIndexes(authoredRoom));
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    private static CommandDispatcher<FabricClientCommandSource> registeredDispatcher() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        DungeonCommands commands = new DungeonCommands(null, null, null);
        Method register = DungeonCommands.class.getDeclaredMethod(
                "register", CommandDispatcher.class, String.class);
        register.setAccessible(true);
        register.invoke(commands, dispatcher, "wpd");
        register.invoke(commands, dispatcher, "waypointer-dungeon");
        return dispatcher;
    }

    private static void assertParses(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                     String command) {
        ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
        assertTrue(parsed.getExceptions().isEmpty(),
                command + " should parse without syntax errors: " + parsed.getExceptions());
        assertTrue(!parsed.getContext().getNodes().isEmpty(),
                command + " should produce parsed command nodes");
    }

    private static DungeonWaypoint waypoint(String id, int secretIndex) {
        return new DungeonWaypoint(id, secretIndex, DungeonSecretCategory.CHEST,
                16, 70, 16, id, List.of());
    }
}
