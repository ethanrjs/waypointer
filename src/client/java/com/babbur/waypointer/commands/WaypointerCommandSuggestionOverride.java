package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.babbur.waypointer.Waypointer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/** Keeps /wp suggestions available when the server registers its own /wp command. */
public final class WaypointerCommandSuggestionOverride {

    private static final String ROOT = "wp";

    private boolean warnedInstallFailure;
    private CommandNode<FabricClientCommandSource> clientRoot;
    private ClientPacketListener installedConnection;
    private CommandDispatcher<ClientSuggestionProvider> installedDispatcher;
    private CommandNode<ClientSuggestionProvider> installedWpRoot;

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    void setClientRoot(CommandNode<FabricClientCommandSource> clientRoot) {
        this.clientRoot = clientRoot;
        clearInstalledState();
    }

    private void onTick(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            clearInstalledState();
            return;
        }

        CommandDispatcher<ClientSuggestionProvider> target = connection.getCommands();
        CommandNode<ClientSuggestionProvider> targetRoot = target.getRoot().getChild(ROOT);
        if (isInstalledFor(connection, target, targetRoot)) return;

        CommandNode<FabricClientCommandSource> clientRoot = this.clientRoot;
        if (clientRoot == null) return;

        if (installWpSuggestionRoot(target.getRoot(), clientRoot)) {
            rememberInstalledState(connection, target, target.getRoot().getChild(ROOT));
        }
    }

    private boolean isInstalledFor(ClientPacketListener connection,
                                   CommandDispatcher<ClientSuggestionProvider> dispatcher,
                                   CommandNode<ClientSuggestionProvider> wpRoot) {
        return installedConnection == connection
                && installedDispatcher == dispatcher
                && installedWpRoot == wpRoot;
    }

    private void rememberInstalledState(ClientPacketListener connection,
                                        CommandDispatcher<ClientSuggestionProvider> dispatcher,
                                        CommandNode<ClientSuggestionProvider> wpRoot) {
        installedConnection = connection;
        installedDispatcher = dispatcher;
        installedWpRoot = wpRoot;
    }

    private void clearInstalledState() {
        installedConnection = null;
        installedDispatcher = null;
        installedWpRoot = null;
    }

    static boolean isWaypointerRoot(CommandNode<?> node) {
        if (node == null) return false;
        // These two children distinguish Waypointer from common server-side /wp trees.
        return node.getChild("importchat") != null && node.getChild("importfile") != null;
    }

    /**
     * Makes {@code /wp} and its aliases suggest Waypointer's commands on any server. A server that
     * registers its own {@code /wp} (Hypixel does) usually gives it a catch-all argument that asks
     * the server for completions; merged with Waypointer's nodes, the client then waits on a reply
     * that may never come and shows no suggestions at all. Server arguments under these roots are
     * dropped; the server's plain subcommand words stay. Waypointer's own commands run client-side
     * either way.
     */
    boolean installWpSuggestionRoot(CommandNode<ClientSuggestionProvider> targetRoot,
                                    CommandNode<FabricClientCommandSource> clientRoot) {
        try {
            CommandNode<ClientSuggestionProvider> root = installRoot(targetRoot, ROOT, copyAsSuggestionNode(clientRoot));
            for (String alias : ALIASES) {
                CommandNode<ClientSuggestionProvider> aliasNode = targetRoot.getChild(alias);
                if (aliasNode != null && aliasNode.getRedirect() == root && aliasNode.getChildren().isEmpty()) continue;
                removeChild(targetRoot, alias);
                targetRoot.addChild(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<ClientSuggestionProvider>literal(alias)
                        .executes(ctx -> 0)
                        .redirect(root)
                        .build());
            }
            return isWaypointerRoot(targetRoot.getChild(ROOT));
        } catch (RuntimeException | ReflectiveOperationException e) {
            warnInstallFailure(e);
            return false;
        }
    }

    private static final java.util.List<String> ALIASES = java.util.List.of("waypointer", "wptr");

    private static CommandNode<ClientSuggestionProvider> installRoot(
            CommandNode<ClientSuggestionProvider> targetRoot, String name,
            CommandNode<ClientSuggestionProvider> ours) throws ReflectiveOperationException {
        CommandNode<ClientSuggestionProvider> existing = targetRoot.getChild(name);
        java.util.List<CommandNode<ClientSuggestionProvider>> serverWords = new java.util.ArrayList<>();
        if (existing != null) {
            for (CommandNode<ClientSuggestionProvider> child : existing.getChildren()) {
                if (child instanceof com.mojang.brigadier.tree.LiteralCommandNode<ClientSuggestionProvider>
                        && ours.getChild(child.getName()) == null) {
                    serverWords.add(child);
                }
            }
            removeChild(targetRoot, name);
        }
        targetRoot.addChild(ours);
        CommandNode<ClientSuggestionProvider> installed = targetRoot.getChild(name);
        for (CommandNode<ClientSuggestionProvider> word : serverWords) installed.addChild(word);
        return installed;
    }

    /** Brigadier has no public removal; its three child maps are package-private fields. */
    private static void removeChild(CommandNode<?> parent, String name) throws ReflectiveOperationException {
        for (String field : new String[]{"children", "literals", "arguments"}) {
            java.lang.reflect.Field map = CommandNode.class.getDeclaredField(field);
            map.setAccessible(true);
            ((java.util.Map<?, ?>) map.get(parent)).remove(name);
        }
    }

    private void warnInstallFailure(Exception e) {
        if (warnedInstallFailure) return;
        warnedInstallFailure = true;

        Waypointer.LOGGER.warn("Unable to install /wp suggestions", e);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CommandNode<ClientSuggestionProvider> copyAsSuggestionNode(CommandNode<?> origin) {
        ArgumentBuilder builder = origin.createBuilder();
        builder.requires(source -> true);
        if (builder.getCommand() != null) {
            builder.executes(ctx -> 0);
        }

        CommandNode copy = builder.build();
        for (CommandNode child : origin.getChildren()) {
            copy.addChild(copyAsSuggestionNode(child));
        }
        return copy;
    }

}
