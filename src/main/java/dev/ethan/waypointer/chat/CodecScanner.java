package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.AsciiStreamCodec;
import dev.ethan.waypointer.codec.CjkBase16384;
import dev.ethan.waypointer.codec.WaypointCodec;

import java.util.ArrayList;
import java.util.List;

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
 *     alphabet.
 *   - The body must be at least {@value #MIN_BODY} characters so a bare magic
 *     prefix surrounded by ordinary text isn't flagged as a codec.
 *
 * When greedy extension could swallow trailing sentence punctuation that is also
 * an alphabet character, we trim those suffixes only while {@link WaypointCodec#isValidCodec}
 * rejects the candidate (cheap integrity probe), so prose delimiters do not corrupt
 * an otherwise valid paste.
 */
public final class CodecScanner {

    /** Minimum body characters required to register as a match. */
    private static final int MIN_BODY = 3;

    /** Upper bound per chat line; stops pathological inputs from hanging the extractor. */
    private static final int MAX_MATCHES_PER_MESSAGE = 3;

    private CodecScanner() {}

    public record Match(int start, int end, String text, boolean valid) {
        public Match(int start, int end, String text) {
            this(start, end, text, true);
        }

        public int length() { return end - start; }
    }

    public static List<Match> scan(String message) {
        if (message == null || message.isEmpty()) return List.of();

        List<Match> out = new ArrayList<>();
        int i = 0;
        while (i < message.length() && out.size() < MAX_MATCHES_PER_MESSAGE) {
            if (!matchMagicAt(message, i) || !isAtWordBoundary(message, i)) { i++; continue; }

            int bodyStart = i + WaypointCodec.MAGIC.length();
            int bodyEnd = bodyStart;
            while (bodyEnd < message.length() && isCodecBodyChar(message.charAt(bodyEnd))) {
                bodyEnd++;
            }
            int greedyBodyEnd = bodyEnd;
            boolean valid = false;
            // Printable-ASCII bodies share most punctuation with normal prose. Without a
            // backscan, greedy extension can swallow trailing delimiters (e.g. commas)
            // that belong to the surrounding sentence. Shrink the end only while
            // {@link WaypointCodec#isValidCodec} rejects the candidate (cheap probe).
            while (bodyEnd > bodyStart + MIN_BODY - 1) {
                if (WaypointCodec.isValidCodec(message.substring(i, bodyEnd))) {
                    valid = true;
                    break;
                }
                bodyEnd--;
            }
            // If the greedy span is valid as a whole but a clause delimiter at the end
            // is prose (not payload), the payload-with-delimiter can still decode as an
            // unrelated blob — trim when dropping that suffix keeps a valid decode.
            if (valid) {
                while (bodyEnd > bodyStart + MIN_BODY) {
                    char last = message.charAt(bodyEnd - 1);
                    if (!isClauseSuffixDelim(last)) break;
                    String shorter = message.substring(i, bodyEnd - 1);
                    if (WaypointCodec.isValidCodec(shorter)) {
                        bodyEnd--;
                    } else {
                        break;
                    }
                }
            } else {
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
