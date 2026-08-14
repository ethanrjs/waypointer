package com.babbur.waypointer.text;

/**
 * Translates player-friendly Minecraft formatting codes in waypoint labels.
 *
 * <p>Waypoint names stay stored exactly as the user typed them. Rendering paths
 * call this helper at the edge so names like {@code &e&lMineshaft} display with
 * the same color/style language Skyblock players already use in chat.
 */
public final class AmpersandFormatting {

    private static final char FORMAT_PREFIX = '\u00A7';

    private AmpersandFormatting() {}

    public static String translate(String text) {
        if (text == null || text.indexOf('&') < 0) return text == null ? "" : text;

        StringBuilder out = null;
        int copiedUntil = 0;
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '&' || !isFormattingCode(text.charAt(i + 1))) {
                continue;
            }
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, copiedUntil, i);
            out.append(FORMAT_PREFIX).append(Character.toLowerCase(text.charAt(i + 1)));
            i++;
            copiedUntil = i + 1;
        }

        if (out == null) return text;
        out.append(text, copiedUntil, text.length());
        return out.toString();
    }

    private static boolean isFormattingCode(char c) {
        if (c >= '0' && c <= '9') return true;
        c = Character.toLowerCase(c);
        return c >= 'a' && c <= 'f'
                || c >= 'k' && c <= 'o'
                || c == 'r';
    }
}
