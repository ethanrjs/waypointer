package com.babbur.waypointer.commands;

import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

final class WaypointerCommandUsage {

    private record Partial(CommandNode<FabricClientCommandSource> parent,
                           CommandNode<FabricClientCommandSource> node, List<String> usages) {}

    private WaypointerCommandUsage() {}

    static void completePartialCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                        LiteralCommandNode<FabricClientCommandSource> root) {
        List<Partial> partials = new ArrayList<>();
        collect(dispatcher, root, "/" + root.getLiteral(), partials);
        for (Partial partial : partials) {
            Command<FabricClientCommandSource> usage = ctx -> {
                showUsage(partial.usages(), ctx.getSource());
                return 0;
            };
            CommandNode<FabricClientCommandSource> withUsage = switch (partial.node()) {
                case LiteralCommandNode<FabricClientCommandSource> literal ->
                        LiteralArgumentBuilder.<FabricClientCommandSource>literal(literal.getLiteral())
                                .executes(usage).build();
                case ArgumentCommandNode<FabricClientCommandSource, ?> argument ->
                        RequiredArgumentBuilder.<FabricClientCommandSource, Object>argument(
                                argument.getName(), cast(argument)).executes(usage).build();
                default -> null;
            };
            if (withUsage != null) partial.parent().addChild(withUsage);
        }
    }

    @SuppressWarnings("unchecked")
    private static com.mojang.brigadier.arguments.ArgumentType<Object> cast(
            ArgumentCommandNode<FabricClientCommandSource, ?> argument) {
        return (com.mojang.brigadier.arguments.ArgumentType<Object>) argument.getType();
    }

    private static void collect(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                CommandNode<FabricClientCommandSource> node, String path,
                                List<Partial> out) {
        for (CommandNode<FabricClientCommandSource> child : node.getChildren()) {
            String childPath = path + " " + child.getUsageText();
            if (child.getCommand() == null && child.getRedirect() == null) {
                List<String> usages = new ArrayList<>();
                for (String usage : dispatcher.getSmartUsage(child, null).values()) {
                    usages.add(childPath + " " + usage);
                }
                out.add(new Partial(node, child, List.copyOf(usages)));
            }
            collect(dispatcher, child, childPath, out);
        }
    }

    private static void showUsage(List<String> usages, FabricClientCommandSource source) {
        source.sendError(WaypointerChatFeedback.suppress(Component.translatable(
                "waypointer.command.incomplete").withStyle(ChatFormatting.RED)));
        for (String usage : usages) {
            String suggestion = usage.replaceAll(" [<\\[(].*$", " ");
            source.sendFeedback(WaypointerChatFeedback.suppress(Component.literal("  " + usage)
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                            .withClickEvent(new ClickEvent.SuggestCommand(suggestion)))));
        }
    }
}
