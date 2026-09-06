package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
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
    void dungeonImportReaderAcceptsUniversalKindFour(@TempDir Path dir) throws Exception {
        WaypointGroup route = dungeonRoute("crypt-a", "Crypt Route");
        route.add(new Waypoint(1, 70, -2, "Chest", 0x123456,
                Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_SKIP_ON_INTERACT, 2.5)
                .withPreciseSixteenths(23, 1128, -25));
        Path file = dir.resolve("routes.wp");
        Files.writeString(file, "```text\n"
                + UniversalShareCodec.encodeDungeon(List.of(route)) + "\n```");

        DungeonCommands.ImportReadResult result =
                DungeonCommands.readImportFile(file, 1_000_000);

        assertFalse(result.readFailure());
        assertNull(result.error());
        assertNotNull(result.result());
        assertEquals(DungeonRouteImporter.Format.WAYPOINTER, result.result().format());
        assertEquals(1, result.result().groups().size());
        WaypointGroup decoded = result.result().groups().getFirst();
        assertEquals(WaypointGroup.RouteKind.DUNGEON, decoded.routeKind());
        assertEquals(route.zoneId(), decoded.zoneId());
        assertEquals(route.name(), decoded.name());
        assertEquals(route.waypoints(), decoded.waypoints());
    }

    @Test
    void dungeonImportReaderPreservesEveryLegacyFileFormat(@TempDir Path dir) throws Exception {
        WaypointGroup route = dungeonRoute("crypt-a", "Crypt Route");
        route.add(Waypoint.at(1, 70, 2));
        List<LegacyDungeonPayload> payloads = List.of(
                new LegacyDungeonPayload(
                        DungeonRoomShareCodec.encode(List.of(route)),
                        DungeonRouteImporter.Format.WAYPOINTER),
                new LegacyDungeonPayload("""
                        {"schema":1,"rooms":[{"id":"native-room","name":"Native Room",
                        "type":"ROOM","shape":"ONE_BY_ONE","waypoints":[
                        {"id":"s1","secretIndex":1,"category":"chest",
                        "x":5,"y":70,"z":5,"highlights":[]}]}]}
                        """, DungeonRouteImporter.Format.WAYPOINTER),
                new LegacyDungeonPayload("""
                        {"Altar":[{"blockPos":{"x":10,"y":70,"z":12},
                        "color":"#00FF00FF","filled":false,"depth":false,
                        "type":"SECRET"}]}
                        """, DungeonRouteImporter.Format.ODIN_PACK),
                new LegacyDungeonPayload("""
                        {"Arrow-Trap-1":[{"locations":[[14,69,4]],
                        "secret":{"type":"bat","location":[26,77,10]}}]}
                        """, DungeonRouteImporter.Format.SECRET_ROUTES));

        Path file = dir.resolve("routes.txt");
        for (LegacyDungeonPayload fixture : payloads) {
            Files.writeString(file, fixture.payload());
            DungeonCommands.ImportReadResult result =
                    DungeonCommands.readImportFile(file, 1_000_000);

            assertFalse(result.readFailure(), fixture.format().name());
            assertNull(result.error(), fixture.format().name());
            assertNotNull(result.result(), fixture.format().name());
            assertEquals(fixture.format(), result.result().format());
            assertFalse(result.result().groups().isEmpty(), fixture.format().name());
        }
    }

    @Test
    void dungeonImportReaderRejectsWrongUniversalKindsWithoutInstallingThem(
            @TempDir Path dir) throws Exception {
        WaypointGroup regularRoute = WaypointGroup.create("Mining Route", "hub");
        regularRoute.add(Waypoint.at(1, 2, 3));
        WaypointGroup dungeonRoute = WaypointGroup.create("Dungeon Route", "room-a");
        dungeonRoute.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        dungeonRoute.add(Waypoint.at(1, 2, 3));
        List<WrongDungeonPayload> payloads = List.of(
                new WrongDungeonPayload(
                        UniversalShareCodec.encodeConfig(new WaypointerConfig()),
                        "got a configuration code"),
                new WrongDungeonPayload(
                        UniversalShareCodec.encodeWaypoints(
                                List.of(regularRoute), WaypointCodec.Options.BARE_COORDINATES),
                        "got a waypoint route code"),
                new WrongDungeonPayload(
                        WaypointCodec.encode(List.of(dungeonRoute)),
                        "got a waypoint route code"));

        Path file = dir.resolve("wrong-kind.wp");
        for (WrongDungeonPayload fixture : payloads) {
            Files.writeString(file, fixture.payload());
            DungeonCommands.ImportReadResult result =
                    DungeonCommands.readImportFile(file, 1_000_000);

            assertFalse(result.readFailure());
            assertNull(result.result());
            assertNotNull(result.error());
            assertTrue(result.error().contains(fixture.errorFragment()), result.error());
        }
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

    private static WaypointGroup dungeonRoute(String roomId, String name) {
        WaypointGroup route = WaypointGroup.create(name, roomId);
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        return route;
    }

    private record LegacyDungeonPayload(String payload, DungeonRouteImporter.Format format) {}

    private record WrongDungeonPayload(String payload, String errorFragment) {}

}
