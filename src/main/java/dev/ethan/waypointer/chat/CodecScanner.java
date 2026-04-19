package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.AsciiPackCodec;
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
 *   - The character immediately before the magic (if any) must be outside the
 *     base-84 alphabet. The alphabet is printable ASCII, so without this
 *     boundary rule a chat line like {@code "helloWP:stuff"} would fire a
 *     false [Invalid Waypointer Code] pill on every mid-word substring. The
 *     old CJK layer implicitly had this property because its alphabet sat
 *     outside the ordinary ASCII range.
 *   - Body characters are extended greedily while they fall in the base-84
 *     alphabet.
 *   - The body must be at least {@value #MIN_BODY} characters so a bare magic
 *     prefix surrounded by ordinary text isn't flagged as a codec.
 *
 * Intentional non-goal: we don't validate the payload here. A malformed codec
 * still gets flagged; the actual decode happens when the user clicks the import
 * chip and {@link WaypointCodec#decode} surfaces any errors. Validating at scan
 * time would run deflate + base-84 decode on every chat line, which is wasteful.
 */
public final class CodecScanner {

    /** Minimum body characters required to register as a match. */
    private static final int MIN_BODY = 3;

    /** Upper bound per chat line; stops pathological inputs from hanging the extractor. */
    private static final int MAX_MATCHES_PER_MESSAGE = 3;

    private CodecScanner() {}

    public record Match(int start, int end, String text) {
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
            while (bodyEnd < message.length() && AsciiPackCodec.isAlphabetChar(message.charAt(bodyEnd))) {
                bodyEnd++;
            }
            int bodyLen = bodyEnd - bodyStart;
            if (bodyLen >= MIN_BODY) {
                out.add(new Match(i, bodyEnd, message.substring(i, bodyEnd)));
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
     * True iff position {@code i} is at the start of the string or the
     * preceding character is not part of the base-84 alphabet. Rejects
     * mid-word and URL-embedded false positives without a heavy regex.
     *
     * The rule is intentionally stricter than "is not alphanumeric" because
     * the alphabet includes punctuation like {@code /} and {@code :} that
     * frequently appears inside URLs. Requiring a non-alphabet char (which
     * covers every whitespace class and every non-ASCII rune) catches all
     * the organic paste shapes while filtering obvious false positives.
     */
    private static boolean isAtWordBoundary(String s, int i) {
        if (i == 0) return true;
        return !AsciiPackCodec.isAlphabetChar(s.charAt(i - 1));
    }
}
