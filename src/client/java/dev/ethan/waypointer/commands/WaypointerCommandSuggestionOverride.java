package dev.ethan.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import dev.ethan.waypointer.Waypointer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Keeps the short {@code /wp} alias useful on servers that also advertise a
 * server-side {@code /wp} command.
 *
 * Fabric currently executes same-name client commands first, but the chat box
 * can still read suggestions from the server's command node. Replacing only the
 * vanilla suggestion node for {@code /wp} preserves Waypointer's short alias
 * without touching unrelated server commands.
 */
public final class WaypointerCommandSuggestionOverride {

    private static final String ROOT = "wp";

    private static final Field CHILDREN_FIELD = findCommandNodeMap("children");
    private static final Field LITERALS_FIELD = findCommandNodeMap("literals");
    private static final Field ARGUMENTS_FIELD = findCommandNodeMap("arguments");

    private boolean warnedReflectionFailure;
    private ClientPacketListener installedConnection;
    private CommandDispatcher<ClientSuggestionProvider> installedDispatcher;
    private CommandNode<ClientSuggestionProvider> installedWpRoot;

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
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

        CommandNode<FabricClientCommandSource> clientRoot = clientRoot();
        if (clientRoot == null) return;

        if (replaceWpRoot(target.getRoot(), clientRoot)) {
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

    @SuppressWarnings("unchecked")
    private static CommandNode<FabricClientCommandSource> clientRoot() {
        CommandDispatcher<FabricClientCommandSource> dispatcher =
                (CommandDispatcher<FabricClientCommandSource>) ClientCommandInternals.getActiveDispatcher();
        if (dispatcher == null) return null;
        return dispatcher.getRoot().getChild(ROOT);
    }

    private static boolean isWaypointerRoot(CommandNode<?> node) {
        if (node == null) return false;
        // Many unrelated servers expose add/group/import under /wp; Waypointer also
        // registers importchat and importfile, which is a much tighter signature.
        return node.getChild("importchat") != null && node.getChild("importfile") != null;
    }

    private boolean replaceWpRoot(CommandNode<ClientSuggestionProvider> targetRoot,
                                  CommandNode<FabricClientCommandSource> clientRoot) {
        if (!canEditCommandNodeMaps()) return false;

        try {
            removeChild(targetRoot, ROOT);
            targetRoot.addChild(copyAsSuggestionNode(clientRoot));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnReflectionFailure(e);
            return false;
        }
    }

    private boolean canEditCommandNodeMaps() {
        boolean ready = CHILDREN_FIELD != null
                && LITERALS_FIELD != null
                && ARGUMENTS_FIELD != null;
        if (!ready) warnReflectionFailure(null);
        return ready;
    }

    private void warnReflectionFailure(Exception e) {
        if (warnedReflectionFailure) return;
        warnedReflectionFailure = true;

        if (e == null) {
            Waypointer.LOGGER.warn(
                    "Unable to override /wp suggestions: Brigadier node maps were unavailable");
        } else {
            Waypointer.LOGGER.warn("Unable to override /wp suggestions", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CommandNode<?>> commandNodeMap(CommandNode<?> node, Field field)
            throws IllegalAccessException {
        return (Map<String, CommandNode<?>>) field.get(node);
    }

    private static void removeChild(CommandNode<?> parent, String name) throws IllegalAccessException {
        commandNodeMap(parent, CHILDREN_FIELD).remove(name);
        commandNodeMap(parent, LITERALS_FIELD).remove(name);
        commandNodeMap(parent, ARGUMENTS_FIELD).remove(name);
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

    private static Field findCommandNodeMap(String name) {
        try {
            Field field = CommandNode.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Waypointer.LOGGER.warn("Unable to access Brigadier CommandNode.{}", name, e);
            return null;
        }
    }
}
