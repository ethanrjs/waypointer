package dev.ethan.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import dev.ethan.waypointer.Waypointer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * Keeps the short {@code /wp} alias useful on servers that also advertise a
 * server-side {@code /wp} command.
 *
 * Fabric currently executes same-name client commands first, but the chat box
 * can still read suggestions from the server's command node. Merging the
 * Waypointer suggestion tree into the vanilla suggestion root for {@code /wp}
 * preserves Waypointer's short alias without touching unrelated server commands.
 */
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
        if (isWaypointerRoot(targetRoot)) {
            rememberInstalledState(connection, target, targetRoot);
            return;
        }

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
        // Many unrelated servers expose add/group/import under /wp; Waypointer also
        // registers importchat and importfile, which is a much tighter signature.
        return node.getChild("importchat") != null && node.getChild("importfile") != null;
    }

    boolean installWpSuggestionRoot(CommandNode<ClientSuggestionProvider> targetRoot,
                                    CommandNode<FabricClientCommandSource> clientRoot) {
        try {
            targetRoot.addChild(copyAsSuggestionNode(clientRoot));
            return isWaypointerRoot(targetRoot.getChild(ROOT));
        } catch (RuntimeException e) {
            warnInstallFailure(e);
            return false;
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
