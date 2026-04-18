package dev.ethan.waypointer.chat;

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
 *   - Body characters are extended greedily while they fall in the CJK base-16384
 *     alphabet range.
 *   - The body must be at least {@value #MIN_BODY} characters so a bare magic prefix
 *     surrounded by ordinary text isn't flagged as a codec.
 *
 * Intentional non-goal: we don't validate the payload here. A malformed codec
 * still gets flagged; the actual decode happens when the user clicks the import
 * chip and {@link WaypointCodec#decode} surfaces any errors. Validating at scan
 * time would run deflate + CJK decode on every chat line, which is wasteful.
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
            if (!matchMagicAt(message, i)) { i++; continue; }

            int bodyStart = i + WaypointCodec.MAGIC.length();
            int bodyEnd = bodyStart;
            while (bodyEnd < message.length() && CjkBase16384.isAlphabetChar(message.charAt(bodyEnd))) {
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
}
