package com.babbur.waypointer.core;

/**
 * Cleans text that came from other players before it is shown: Minecraft formatting codes,
 * control characters and invisible direction overrides can make a route name pose as
 * something else (a coloured system line, reversed text), so they never reach the screen.
 */
public final class SafeText {

    private SafeText() {}

    /** One line of shared text, trimmed. */
    public static String line(String raw) {
        return clean(raw, false);
    }

    /** Shared text that may keep its line breaks, such as a route description. */
    public static String paragraph(String raw) {
        return clean(raw, true);
    }

    private static String clean(String raw, boolean keepNewlines) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' && keepNewlines) {
                out.append(c);
            } else if (!isHidden(c)) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    /** Formatting codes, controls, direction marks/overrides/isolates and zero-width breaks. */
    public static boolean isHidden(char c) {
        return c == '§'
                || c < 0x20 || (c >= 0x7F && c <= 0x9F)
                || c == '؜' || c == '​' || c == '‎' || c == '‏'
                || (c >= '‪' && c <= '‮')
                || (c >= '⁠' && c <= '⁩')
                || c == '﻿';
    }
}
