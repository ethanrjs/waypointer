package com.babbur.waypointer.commands;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import com.babbur.waypointer.core.WaypointGroup;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

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
        suggestText(builder, value, Component.literal(tooltip));
    }

    public static void suggestText(SuggestionsBuilder builder, String value, Component tooltip) {
        if (value == null || value.isBlank()) return;
        if (value.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(value, tooltip);
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

    static MutableComponent routeName(WaypointGroup route) {
        String name = route == null || route.name().isBlank() ? "?" : route.name();
        if (route == null) return Component.literal(name).withStyle(ChatFormatting.AQUA);
        return Component.literal(name).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand(
                        "/waypointer gui " + StringArgumentType.escapeIfRequired(route.id())))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable("waypointer.command.route.open_hover"))));
    }

    static void routeSuccess(FabricClientCommandSource source, WaypointGroup route, Component message) {
        source.sendFeedback(WaypointerChatFeedback.suppress(Component.empty()
                .append(routeName(route))
                .append(Component.literal(" \u203A ").withStyle(ChatFormatting.DARK_GRAY))
                .append(message.copy().withStyle(ChatFormatting.GREEN))));
    }
}
