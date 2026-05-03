package dev.ethan.waypointer.commands;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

/**
 * Shared Brigadier suggestion helpers for client commands.
 */
public final class CommandSuggestionHelpers {

    private CommandSuggestionHelpers() {}

    /**
     * Emit integer suggestions {@code 0..count-1} matching the typed prefix, each with a tooltip.
     */
    public static CompletableFuture<Suggestions> suggestIndexed(
            SuggestionsBuilder builder, int count, IntFunction<String> tooltipFor) {
        String prefix = builder.getRemaining();
        for (int i = 0; i < count; i++) {
            String value = Integer.toString(i);
            if (value.startsWith(prefix)) {
                builder.suggest(i, Component.literal(tooltipFor.apply(i)));
            }
        }
        return builder.buildFuture();
    }

    public static void suggestText(SuggestionsBuilder builder, String value, String tooltip) {
        if (value == null || value.isBlank()) return;
        if (value.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(value, Component.literal(tooltip));
        }
    }
}
