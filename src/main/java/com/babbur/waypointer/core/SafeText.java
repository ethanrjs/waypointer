package com.babbur.waypointer.core;

public final class SafeText {

    private SafeText() {}

    public static String line(String raw) {
        return clean(raw, false);
    }

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

    public static boolean isHidden(char c) {
        return c == '§'
                || c < 0x20 || (c >= 0x7F && c <= 0x9F)
                || c == '؜' || c == '​' || c == '‎' || c == '‏'
                || (c >= '‪' && c <= '‮')
                || (c >= '⁠' && c <= '⁩')
                || c == '﻿';
    }
}
