package com.babbur.waypointer.catalog;

import java.util.regex.Pattern;

public final class PublisherNamePolicy {
    private static final Pattern MINECRAFT_NAME =
            Pattern.compile("[A-Za-z0-9_]{3,16}");

    private PublisherNamePolicy() {
    }

    public static boolean valid(String name) {
        return name != null && MINECRAFT_NAME.matcher(name).matches();
    }

    public static String requireValid(String name) {
        if (!valid(name)) {
            throw new IllegalArgumentException(
                    "Publisher name must contain 3-16 letters, numbers, or underscores");
        }
        return name;
    }
}
