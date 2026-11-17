package com.babbur.waypointer.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.List;

/** One space-free word, so waypoint labels can also be IDs like {@code @mod1#15}. */
final class RouteTokenArgument implements ArgumentType<String> {

    private static final Collection<String> EXAMPLES = List.of("3", "2.1", "@mod1#15");

    static RouteTokenArgument token() {
        return new RouteTokenArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && reader.peek() == '"') return reader.readString();
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        if (reader.getCursor() == start) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
