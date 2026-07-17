package com.babbur.waypointer.chat;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.codec.CjkBase16384;
import com.babbur.waypointer.codec.WaypointCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds {@link WaypointCodec} export strings embedded in chat messages.
 *
 * Powers the "paste a codec in chat and I'll offer to import it" flow. Pure Java
 * with no Minecraft dependencies so the extraction logic is fully unit-testable
 * before being wired to chat events.
 *
 * Rules:
 *
 *   - A match must start with {@link WaypointCodec#MAGIC}.
 *   - The character immediately before the magic (if any) must not glue the
 *     prefix to the magic: either it is outside the codec alphabet, or it is
 *     clause-like punctuation that is in the alphabet but still starts a new
 *     paste (e.g. {@code ",WP:…"}). Without this, a line like
 *     {@code "helloWP:stuff"} would fire a false pill on every mid-word substring.
 *   - Body characters are extended greedily while they fall in the codec
 *     alphabet, up to Minecraft's chat-command limit.
 *   - The body must be at least {@value #MIN_BODY} characters so a bare magic
 *     prefix surrounded by ordinary text isn't flagged as a codec.
 *
 * The greedy candidate is validated once. When extension could swallow trailing
 * sentence punctuation that is also an alphabet character, only a few known
 * delimiters are tested as removable suffixes. This keeps prose delimiters out of
 * valid pastes without decoder work proportional to candidate length.
 */
public final class CodecScanner {

    /** Minimum body characters required to register as a match. */
    private static final int MIN_BODY = 3;

    /** Upper bound per chat line; stops pathological inputs from hanging the extractor. */
    private static final int MAX_MATCHES_PER_MESSAGE = 3;

    /** A complete codec cannot exceed Minecraft's 256-byte chat-command limit. */
    private static final int MAX_CODEC_CHARS = 256;

    /** Enough for normal sentence punctuation without reopening an unbounded backscan. */
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
                // A WP: body that never decodes is still useful to surface: it is
                // almost always a truncated/corrupted route paste, and silently
                // ignoring it makes the sender think nothing happened.
                bodyEnd = greedyBodyEnd;
            }
            int bodyLen = bodyEnd - bodyStart;
            if (bodyLen >= MIN_BODY) {
                out.add(new Match(i, bodyEnd, message.substring(i, bodyEnd), valid));
                i = bodyEnd;
            } else {
                // False start: magic matched but body was too short (e.g. literal "WP:" in prose).
                i += WaypointCodec.MAGIC.length();
            }
        }
        return out;
    }

    private static boolean matchMagicAt(String s, int i) {
        return s.regionMatches(i, WaypointCodec.MAGIC, 0, WaypointCodec.MAGIC.length());
    }

    /**
     * True when {@code WP:} may start a new codec at this index: start of string,
     * a non-alphabet character, or clause punctuation that is alphabet-sized but
     * still separates a paste from the preceding word. Mid-word and URL runs
     * (e.g. {@code fileWP:}, {@code /path/WP:}) stay rejected.
     */
    private static boolean isAtWordBoundary(String s, int i) {
        if (i == 0) return true;
        char prev = s.charAt(i - 1);
        if (!isCodecBodyChar(prev)) return true;
        // Alphabet letters/digits and symbols like '/' glue runs together (URLs, words).
        // Clause punctuation is also in the v3 alphabet; treat it as a start boundary so
        // pastes like ",WP:..." still match.
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

    /** Clause punctuation that may trail an embedded paste but is rarely meaningful payload. */
    private static boolean isClauseSuffixDelim(char c) {
        return switch (c) {
            case ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"' -> true;
            default -> false;
        };
    }
}
