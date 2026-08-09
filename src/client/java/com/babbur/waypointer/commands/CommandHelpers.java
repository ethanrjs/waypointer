package com.babbur.waypointer.commands;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

public final class CommandHelpers {

    private CommandHelpers() {}

    public static CompletableFuture<Suggestions> suggestIndexed(
            SuggestionsBuilder builder, int count, IntFunction<String> tooltipFor) {
        String prefix = builder.getRemaining();
        for (int i = 0; i < count; i++) {
            String s = Integer.toString(i);
            if (!s.startsWith(prefix)) continue;
            builder.suggest(i, Component.literal(tooltipFor.apply(i)));
        }
        return builder.buildFuture();
    }

    public static void suggestText(SuggestionsBuilder builder, String value, String tooltip) {
        if (value == null || value.isBlank()) return;
        if (value.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(value, Component.literal(tooltip));
        }
    }

    static void info(FabricClientCommandSource source, Component message) {
        source.sendFeedback(WaypointerChatFeedback.suppress(message));
    }

    static void success(FabricClientCommandSource source, Component message) {
        source.sendFeedback(WaypointerChatFeedback.suppress(
                message.copy().withStyle(ChatFormatting.GREEN)));
    }

    static void warn(FabricClientCommandSource source, Component message) {
        source.sendFeedback(WaypointerChatFeedback.suppress(
                message.copy().withStyle(ChatFormatting.YELLOW)));
    }

    static void error(FabricClientCommandSource source, Component message) {
        source.sendError(WaypointerChatFeedback.suppress(
                message.copy().withStyle(ChatFormatting.RED)));
    }
}
