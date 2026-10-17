package com.babbur.waypointer.commands;

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
    void serverArgumentsThatAskTheServerCannotStallWaypointerSuggestions() throws Exception {
        // Hypixel-style tree: the server's own /wp (and aliases) take any text and ask the server
        // for completions, which may never answer. Fabric merges Waypointer's nodes into it.
        CommandDispatcher<ClientSuggestionProvider> serverSuggestions = new CommandDispatcher<>();
        for (String name : java.util.List.of("wp", "waypointer", "wptr")) {
            serverSuggestions.register(LiteralArgumentBuilder.<ClientSuggestionProvider>literal(name)
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<ClientSuggestionProvider, String>argument("args",
                                    com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> new java.util.concurrent.CompletableFuture<>())));
        }
        serverSuggestions.getRoot().getChild("wp").addChild(
                LiteralArgumentBuilder.<ClientSuggestionProvider>literal("debug").build());
        CommandNode<FabricClientCommandSource> clientRoot = clientSuggestionRoot();
        CommandDispatcher<FabricClientCommandSource> client = new CommandDispatcher<>();
        client.getRoot().addChild(clientRoot);
        client.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("waypointer").redirect(clientRoot));

        assertTrue(new WaypointerCommandSuggestionOverride()
                .installWpSuggestionRoot(serverSuggestions.getRoot(), clientRoot));

        for (String typed : java.util.List.of("wp ", "waypointer ", "wptr ")) {
            Suggestions suggestions = serverSuggestions.getCompletionSuggestions(
                    serverSuggestions.parse(typed, null)).get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(suggestions.getList().stream().anyMatch(s -> "importchat".equals(s.getText())),
                    typed + " should suggest Waypointer's commands: " + suggestions.getList());
        }
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
