package com.babbur.waypointer.commands;

import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalHollowsCommandTreeTest {

    @Test
    void registersManualAdditionAndDefocus() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        new CrystalHollowsCommands(null, null, null).register(dispatcher);

        assertNotNull(dispatcher.getRoot().getChild("wpch"));
        assertNull(dispatcher.getRoot().getChild("waypointer-crystal"));
        assertNull(dispatcher.getRoot().getChild("wpch").getCommand());
        assertEquals(Set.of("add", "defocus", "scan", "sendcoords"),
                dispatcher.getRoot().getChild("wpch").getChildren().stream()
                        .map(node -> node.getName()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void shareClickParsesCapturedCoordinatesWithoutRestoringShareCommand() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        new CrystalHollowsCommands(null, null, null).register(dispatcher);
        var sighting = new com.babbur.waypointer.crystal.StructureSighting(
                CrystalHollowsStructure.FAIRY_GROTTO, 253, 107, 618,
                com.babbur.waypointer.crystal.SightingConfidence.SHARED_REMOTE, "skyblocker", 1);
        String command = com.babbur.waypointer.crystal.CrystalHollowsShare.share(sighting).command().substring(1);
        var parsed = dispatcher.parse(command, null);
        assertFalse(parsed.getReader().canRead());
        assertTrue(parsed.getExceptions().isEmpty());
        assertNotNull(parsed.getContext().getCommand());
        var context = parsed.getContext().build(command);
        assertEquals("fairy_grotto", context.getArgument("structure", String.class));
        assertEquals(253, (int) context.getArgument("x", Integer.class));
        assertEquals(107, (int) context.getArgument("y", Integer.class));
        assertEquals(618, (int) context.getArgument("z", Integer.class));
        assertNull(dispatcher.getRoot().getChild("wpch").getChild("share"));
    }

    @Test
    void resolvesStableIdsAndAliases() {
        assertEquals(CrystalHollowsStructure.JUNGLE_TEMPLE,
                CrystalHollowsCommands.resolveStructure("jungle_temple"));
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN,
                CrystalHollowsCommands.resolveStructure("divan"));
    }

    @Test
    void manualCoordinatesParseAndRemainExact() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        new CrystalHollowsCommands(null, null, null).register(dispatcher);
        String command = "wpch add odawa -349 110 390";
        var parsed = dispatcher.parse(command, null);
        assertFalse(parsed.getReader().canRead());
        assertTrue(parsed.getExceptions().isEmpty());
        assertNotNull(parsed.getContext().getCommand());
        var context = parsed.getContext().build(command);
        assertEquals("odawa", context.getArgument("structure", String.class));
        assertEquals(-349, (int) context.getArgument("x", Integer.class));
        assertEquals(110, (int) context.getArgument("y", Integer.class));
        assertEquals(390, (int) context.getArgument("z", Integer.class));
    }

    @Test
    void removedCommandsAndIncompleteCoordinatesAreRejected() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        new CrystalHollowsCommands(null, null, null).register(dispatcher);
        for (String command : new String[]{"wpch", "wpch info", "wpch share wishing_target:2",
                "wpch share odawa 349 110 390", "wpch share odawa 349 110", "wpch share odawa 349 110 nope",
                "wpch share odawa 349 110 390 extra", "wpch share odawa 200 110 390",
                "wpch share odawa 349 189 390", "wpch share odawa 349 110 824",
                "wpch remove odawa", "wpch clear", "wpch compass", "wpch compass reset",
                "wpch toggle rough", "wpch add", "wpch add odawa", "wpch add odawa 1",
                "wpch add odawa 1 2", "wpch add odawa 1 2 nope", "wpch add odawa 1 2 3 extra",
                "waypointer-crystal add odawa 1 2 3"}) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, null), command);
        }
    }

    @Test
    void crystalHelpListsOnlyManualAdditionWithCoordinates() {
        java.util.List<Component> feedback = new java.util.ArrayList<>();
        FabricClientCommandSource source = (FabricClientCommandSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{FabricClientCommandSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendFeedback")) {
                        feedback.add((Component) args[0]);
                        return null;
                    }
                    throw new AssertionError("Unexpected source call: " + method.getName());
                });
        assertEquals(1, WaypointerCommandHelp.run(source, "wp", "crystal"));
        assertEquals(java.util.List.of("/wpch add <structure> <x> <y> <z>"), feedback.stream()
                .map(Component::getString).map(String::trim)
                .filter(line -> line.startsWith("/wpch")).toList());
    }
}
