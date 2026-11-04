package com.babbur.waypointer.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

public final class BoundedJson {

    public static final int MAX_TOKENS = 500_000;
    public static final int MAX_SCALAR_VALUES = 300_000;
    public static final int MAX_CONTAINERS = 60_000;
    public static final int MAX_STRING_CHARS = 1 * 1024 * 1024;
    public static final int MAX_DEPTH = 512;

    private BoundedJson() {}

    public static JsonElement parse(String json) {
        validateBudget(json);
        return JsonParser.parseString(json);
    }

    private static void validateBudget(String json) {
        int tokens = 0;
        int scalarValues = 0;
        int containers = 0;
        int depth = 0;
        int stringChars = 0;

        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            while (true) {
                JsonToken token = reader.peek();
                if (token == JsonToken.END_DOCUMENT) return;
                tokens++;
                if (tokens > MAX_TOKENS) {
                    throw new IllegalArgumentException("JSON has too many tokens (max " + MAX_TOKENS + ")");
                }

                switch (token) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        containers++;
                        depth++;
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        containers++;
                        depth++;
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> stringChars = addStringChars(stringChars, reader.nextName().length());
                    case STRING -> {
                        scalarValues = addScalarValue(scalarValues);
                        stringChars = addStringChars(stringChars, reader.nextString().length());
                    }
                    case NUMBER -> {
                        scalarValues = addScalarValue(scalarValues);
                        reader.nextString();
                    }
                    case BOOLEAN -> {
                        scalarValues = addScalarValue(scalarValues);
                        reader.nextBoolean();
                    }
                    case NULL -> {
                        scalarValues = addScalarValue(scalarValues);
                        reader.nextNull();
                    }
                    case END_DOCUMENT -> throw new IllegalStateException("unreachable JSON token");
                }

                if (containers > MAX_CONTAINERS) {
                    throw new IllegalArgumentException(
                            "JSON has too many arrays or objects (max " + MAX_CONTAINERS + ")");
                }
                if (depth > MAX_DEPTH) {
                    throw new IllegalArgumentException("JSON is nested too deeply (max " + MAX_DEPTH + ")");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed JSON", e);
        }
    }

    private static int addScalarValue(int current) {
        if (current == MAX_SCALAR_VALUES) {
            throw new IllegalArgumentException("JSON has too many values (max " + MAX_SCALAR_VALUES + ")");
        }
        return current + 1;
    }

    private static int addStringChars(int current, int additional) {
        if (additional > MAX_STRING_CHARS - current) {
            throw new IllegalArgumentException("JSON contains too much text (max " + MAX_STRING_CHARS + " chars)");
        }
        return current + additional;
    }
}
