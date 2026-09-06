package com.babbur.waypointer.commands;

import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.mojang.brigadier.CommandDispatcher;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalHollowsCommandTreeTest {

    @Test
    void registersCanonicalTreeAndAlias() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        new CrystalHollowsCommands(null, null, null).register(dispatcher);

        assertNotNull(dispatcher.getRoot().getChild("wpch"));
        assertNotNull(dispatcher.getRoot().getChild("waypointer-crystal"));
        assertEquals(Set.of("info", "share", "add", "remove", "clear", "compass", "toggle"),
                dispatcher.getRoot().getChild("wpch").getChildren().stream()
                        .map(node -> node.getName()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void resolvesStableIdsAndAliases() {
        assertEquals(CrystalHollowsStructure.JUNGLE_TEMPLE,
                CrystalHollowsCommands.resolveStructure("jungle_temple"));
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN,
                CrystalHollowsCommands.resolveStructure("divan"));
    }

    @Test
    void shareAndRemoveAcceptNumberedSightings() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        new CrystalHollowsCommands(null, null, null).register(dispatcher);
        for (String action : new String[]{"share", "remove"}) {
            var parsed = dispatcher.parse("wpch " + action + " wishing_target:2", null);
            assertFalse(parsed.getReader().canRead());
            assertTrue(parsed.getExceptions().isEmpty());
            assertNotNull(parsed.getContext().getCommand());
        }
    }
}
