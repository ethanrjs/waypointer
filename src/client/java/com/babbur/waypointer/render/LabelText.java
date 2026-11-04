package com.babbur.waypointer.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public final class LabelText {

    public static final List<String> PLACEHOLDERS = List.of("{distance}", "{index}", "{total}", "{route}");
    private static final int CODE_COLOR = 0x80868E;
    private static final int UNKNOWN_PLACEHOLDER_COLOR = 0xE47B7B;

    private LabelText() {}

    public static MutableComponent styled(String raw) {
        MutableComponent out = Component.empty();
        if (raw == null || raw.isEmpty()) return out;
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int hex = hexAt(raw, i);
            if (hex >= 0) {
                flush(out, run, style);
                style = Style.EMPTY.withColor(TextColor.fromRgb(hex));
                i += hexTagLength(raw, i);
                continue;
            }
            ChatFormatting code = codeAt(raw, i);
            if (code != null) {
                flush(out, run, style);
                style = code == ChatFormatting.RESET ? Style.EMPTY : style.applyLegacyFormat(code);
                i += 2;
                continue;
            }
            run.append(raw.charAt(i++));
        }
        flush(out, run, style);
        return out;
    }

    public static FormattedCharSequence editorPreview(String value, int offset, int length, int accentRgb) {
        Style[] styles = editorStyles(value, accentRgb);
        int start = Math.max(0, Math.min(offset, value.length()));
        int end = Math.max(start, Math.min(value.length(), offset + length));
        List<FormattedCharSequence> parts = new ArrayList<>();
        int runStart = start;
        for (int i = start + 1; i <= end; i++) {
            if (i == end || !styles[i].equals(styles[runStart])) {
                parts.add(FormattedCharSequence.forward(value.substring(runStart, i), styles[runStart]));
                runStart = i;
            }
        }
        return FormattedCharSequence.composite(parts);
    }

    static Style[] editorStyles(String value, int accentRgb) {
        Style[] styles = new Style[value.length()];
        Style current = Style.EMPTY;
        Style code = Style.EMPTY.withColor(TextColor.fromRgb(CODE_COLOR));
        int i = 0;
        while (i < value.length()) {
            int hex = hexAt(value, i);
            if (hex >= 0) {
                current = Style.EMPTY.withColor(TextColor.fromRgb(hex));
                int tagLength = hexTagLength(value, i);
                for (int j = 0; j < tagLength; j++) styles[i + j] = current;
                i += tagLength;
                continue;
            }
            ChatFormatting format = codeAt(value, i);
            if (format != null) {
                styles[i] = code;
                styles[i + 1] = code;
                if (format == ChatFormatting.RESET) {
                    current = Style.EMPTY;
                } else if (format != ChatFormatting.OBFUSCATED) {
                    current = current.applyLegacyFormat(format);
                }
                i += 2;
                continue;
            }
            String placeholder = placeholderAt(value, i);
            if (placeholder != null) {
                Style lit = current.withColor(TextColor.fromRgb(accentRgb));
                for (int j = 0; j < placeholder.length(); j++) styles[i + j] = lit;
                i += placeholder.length();
                continue;
            }
            int unknownEnd = unknownPlaceholderEnd(value, i);
            if (unknownEnd > i) {
                Style wrong = current.withColor(TextColor.fromRgb(UNKNOWN_PLACEHOLDER_COLOR));
                for (int j = i; j < unknownEnd; j++) styles[j] = wrong;
                i = unknownEnd;
                continue;
            }
            styles[i++] = current;
        }
        return styles;
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style) {
        if (run.isEmpty()) return;
        out.append(Component.literal(run.toString()).withStyle(style));
        run.setLength(0);
    }

    private static String placeholderAt(String text, int i) {
        for (String placeholder : PLACEHOLDERS) {
            if (text.startsWith(placeholder, i)) return placeholder;
        }
        return null;
    }

    static int unknownPlaceholderEnd(String text, int i) {
        if (text.charAt(i) != '{') return i;
        int j = i + 1;
        while (j < text.length() && text.charAt(j) != '}' && text.charAt(j) != '{'
                && !Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        return j < text.length() && text.charAt(j) == '}' ? j + 1 : j;
    }

    private static ChatFormatting codeAt(String text, int i) {
        if (i + 1 >= text.length()) return null;
        char prefix = text.charAt(i);
        if (prefix != '&' && prefix != '§') return null;
        return ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
    }

    static int hexAt(String text, int i) {
        int digits;
        if (text.startsWith("<#", i) && text.length() >= i + 9 && text.charAt(i + 8) == '>') {
            digits = i + 2;
        } else if (text.startsWith("&#", i) && text.length() >= i + 8) {
            digits = i + 2;
        } else {
            return -1;
        }
        int rgb = 0;
        for (int j = digits; j < digits + 6; j++) {
            int value = Character.digit(text.charAt(j), 16);
            if (value < 0) return -1;
            rgb = rgb << 4 | value;
        }
        return rgb;
    }

    private static int hexTagLength(String text, int i) {
        return text.startsWith("<#", i) ? 9 : 8;
    }
}
