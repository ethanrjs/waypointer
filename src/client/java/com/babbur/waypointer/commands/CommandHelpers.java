package com.babbur.waypointer.commands;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

/**
 * Shared Brigadier suggestion helpers for client command classes.
 */
public final class CommandHelpers {

    private CommandHelpers() {}

    /**
     * Emit integer suggestions {@code 0..count-1} that match the prefix the user
     * has typed so far, each annotated with a tooltip from {@code tooltipFor}.
     * Brigadier only re-sorts numerically when the raw suggestion is parseable as
     * an int, so we pass the number as a string and let the framework handle ordering.
     */
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
}
