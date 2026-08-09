package com.babbur.waypointer.chat;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.codec.CjkBase16384;
import com.babbur.waypointer.codec.WaypointCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds {@link WaypointCodec} strings in chat. Matches start at a word or
 * punctuation boundary, stay within the chat limit, and trim a few trailing
 * punctuation characters when needed.
 */
public final class CodecScanner {

    private static final int MIN_BODY = 3;
    private static final int MAX_MATCHES_PER_MESSAGE = 3;
    private static final int MAX_CODEC_CHARS = 256;
    private static final int MAX_SUFFIX_TRIMS = 3;

    private CodecScanner() {}

    public record Match(int start, int end, String text, boolean valid) {
        public Match(int start, int end, String text) {
            this(start, end, text, true);
        }

        public int length() { return end - start; }
    }

    public static List<Match> scan(String message) {
        return scan(message, WaypointCodec::isValidCodec);
    }

    static List<Match> scan(String message, Predicate<String> validator) {
        if (message == null || message.isEmpty()) return List.of();

        List<Match> out = new ArrayList<>();
        int i = 0;
        while (i < message.length() && out.size() < MAX_MATCHES_PER_MESSAGE) {
            if (!matchMagicAt(message, i) || !isAtWordBoundary(message, i)) { i++; continue; }

            int bodyStart = i + WaypointCodec.MAGIC.length();
            int bodyEnd = bodyStart;
            int candidateLimit = Math.min(message.length(), i + MAX_CODEC_CHARS);
            while (bodyEnd < candidateLimit && isCodecBodyChar(message.charAt(bodyEnd))) {
                bodyEnd++;
            }
            int greedyBodyEnd = bodyEnd;
            boolean valid = bodyEnd - bodyStart >= MIN_BODY
                    && validator.test(message.substring(i, bodyEnd));
            if (!valid) {
                for (int trims = 0;
                     trims < MAX_SUFFIX_TRIMS
                             && bodyEnd > bodyStart + MIN_BODY
                             && isClauseSuffixDelim(message.charAt(bodyEnd - 1));
                     trims++) {
                    bodyEnd--;
                    valid = validator.test(message.substring(i, bodyEnd));
                    if (valid) break;
                }
            }
            if (!valid) {
                // Surface broken codes too, so the user sees an import error.
                bodyEnd = greedyBodyEnd;
            }
            int bodyLen = bodyEnd - bodyStart;
            if (bodyLen >= MIN_BODY) {
                out.add(new Match(i, bodyEnd, message.substring(i, bodyEnd), valid));
                i = bodyEnd;
            } else {
                i += WaypointCodec.MAGIC.length();
            }
        }
        return out;
    }

    private static boolean matchMagicAt(String s, int i) {
        return s.regionMatches(i, WaypointCodec.MAGIC, 0, WaypointCodec.MAGIC.length());
    }

    private static boolean isAtWordBoundary(String s, int i) {
        if (i == 0) return true;
        char prev = s.charAt(i - 1);
        if (!isCodecBodyChar(prev)) return true;
        return isClauseBoundaryBeforeMagic(prev);
    }

    private static boolean isCodecBodyChar(char c) {
        return AsciiStreamCodec.isAlphabetChar(c) || CjkBase16384.isAlphabetChar(c);
    }

    private static boolean isClauseBoundaryBeforeMagic(char c) {
        return switch (c) {
            case ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}', '\'', '"', '-' -> true;
            default -> false;
        };
    }

    private static boolean isClauseSuffixDelim(char c) {
        return switch (c) {
            case ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"' -> true;
            default -> false;
        };
    }
}
