package dev.ethan.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerCommandSuggestionOverrideTest {

    @Test
    void installsWaypointerSuggestionsWithoutRemovingServerChildren() {
        CommandDispatcher<ClientSuggestionProvider> serverSuggestions = new CommandDispatcher<>();
        serverSuggestions.register(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("wp")
                .then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("serveronly")));
        CommandNode<FabricClientCommandSource> clientRoot = clientSuggestionRoot();

        boolean installed = new WaypointerCommandSuggestionOverride()
                .installWpSuggestionRoot(serverSuggestions.getRoot(), clientRoot);

        CommandNode<ClientSuggestionProvider> mergedRoot = serverSuggestions.getRoot().getChild("wp");
        assertTrue(installed);
        assertTrue(WaypointerCommandSuggestionOverride.isWaypointerRoot(mergedRoot));
        assertNotNull(mergedRoot.getChild("serveronly"));
        assertNotNull(mergedRoot.getChild("importchat"));
        assertNotNull(mergedRoot.getChild("importfile"));
        assertNotNull(mergedRoot.getChild("debug"));
    }

    @Test
    void installedSuggestionRootExposesMergedCompletions() {
        CommandDispatcher<ClientSuggestionProvider> serverSuggestions = new CommandDispatcher<>();
        serverSuggestions.register(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("wp")
                .then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("serveronly")));
        new WaypointerCommandSuggestionOverride()
                .installWpSuggestionRoot(serverSuggestions.getRoot(), clientSuggestionRoot());

        ParseResults<ClientSuggestionProvider> parsed = serverSuggestions.parse("wp ", null);
        Suggestions suggestions = serverSuggestions.getCompletionSuggestions(parsed).join();

        assertTrue(suggestions.getList().stream().anyMatch(s -> "serveronly".equals(s.getText())));
        assertTrue(suggestions.getList().stream().anyMatch(s -> "importchat".equals(s.getText())));
        assertTrue(suggestions.getList().stream().anyMatch(s -> "importfile".equals(s.getText())));
        assertTrue(suggestions.getList().stream().anyMatch(s -> "debug".equals(s.getText())));
    }

    @Test
    void waypointerRootSignatureRejectsGenericServerCommands() {
        CommandNode<ClientSuggestionProvider> genericServerRoot =
                LiteralArgumentBuilder.<ClientSuggestionProvider>literal("wp")
                        .then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("import"))
                        .then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("group"))
                        .build();

        assertFalse(WaypointerCommandSuggestionOverride.isWaypointerRoot(null));
        assertFalse(WaypointerCommandSuggestionOverride.isWaypointerRoot(genericServerRoot));
        assertTrue(WaypointerCommandSuggestionOverride.isWaypointerRoot(clientSuggestionRoot()));
    }

    private static CommandNode<FabricClientCommandSource> clientSuggestionRoot() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("wp")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("importchat"))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("importfile"))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("debug"))
                .build();
    }
}
