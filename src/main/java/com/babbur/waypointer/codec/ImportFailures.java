package com.babbur.waypointer.codec;

import com.babbur.waypointer.Waypointer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Turns a failed import into something a player can act on (issue #197). A damaged code used to
 * print every wire version's own decoder error in chat; now the player is told the code is
 * damaged and how to get a good copy, and the decoder detail goes to the log and the debug
 * report (/wp debug) instead.
 */
public final class ImportFailures {

    public enum Kind {
        /** The text is not a route code of any format Waypointer reads. */
        NOT_A_CODE("waypointer.import.error.unrecognized"),
        /** A Waypointer code that no longer decodes: cut off, or changed on the way. */
        DAMAGED("waypointer.import.error.damaged"),
        /** A code that unpacks to more data than an import may hold. */
        TOO_LARGE("waypointer.import.error.too_large"),
        /** Anything else; its own message already says what is wrong. */
        OTHER(null);

        private final String translationKey;

        Kind(String translationKey) {
            this.translationKey = translationKey;
        }

        /** The message to show, or null when the failure's own message should be shown. */
        public String translationKey() {
            return translationKey;
        }

        /**
         * The message for this payload: a damaged {@code WP:} code also gets the Discord code-block
         * hint, since Discord's formatting is the usual way such codes get mangled.
         */
        public String translationKey(String payload) {
            if (this == DAMAGED && payload != null && payload.trim().startsWith(WaypointCodec.MAGIC)) {
                return "waypointer.import.error.damaged_discord";
            }
            return translationKey;
        }
    }

    /** One failed import, without the code itself: only its length and format prefix. */
    public record Recent(long atMillis, String origin, Kind kind, int length, String prefix, String detail) {}

    private static final int MAX_RECENT = 8;
    private static final int MAX_DETAIL = 600;
    private static final Deque<Recent> RECENT = new ArrayDeque<>();

    private ImportFailures() {}

    public static Kind classify(String payload, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals("InflatedPayloadLimitException")) return Kind.TOO_LARGE;
            if (cause.getCause() == cause) break;
        }
        String message = failure == null || failure.getMessage() == null ? "" : failure.getMessage().trim();
        String text = payload == null ? "" : payload.trim();
        if (message.startsWith("codec decode failed")
                || message.startsWith("share decode failed")
                || message.startsWith("route library decode failed")
                || message.startsWith("catalog v9 decode failed")) {
            return Kind.DAMAGED;
        }
        boolean waypointerCode = text.startsWith(WaypointCodec.MAGIC) || RouteLibraryCodec.isPayload(text);
        // A Waypointer code is damaged only if it no longer decodes; a good code of the wrong
        // kind (a config code given to a route import) keeps its own explanation.
        if (waypointerCode && !decodes(text)) return Kind.DAMAGED;
        if (message.isEmpty()
                || message.startsWith("unrecognized waypoint payload")
                || message.startsWith("not a Waypointer export")) {
            return Kind.NOT_A_CODE;
        }
        return Kind.OTHER;
    }

    private static boolean decodes(String text) {
        try {
            UniversalShareCodec.decode(text);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Classifies a failed import and keeps its decoder detail for the log and the debug report. */
    public static Kind record(String origin, String payload, Throwable failure) {
        Kind kind = classify(payload, failure);
        String text = payload == null ? "" : payload.trim();
        String detail = failure == null ? "(no exception)"
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (detail.length() > MAX_DETAIL) detail = detail.substring(0, MAX_DETAIL) + "...";
        Recent recent = new Recent(System.currentTimeMillis(), origin == null ? "unknown" : origin, kind,
                text.length(), formatPrefix(text), detail.replace('\n', ' ').replace('\r', ' '));
        synchronized (RECENT) {
            RECENT.addFirst(recent);
            while (RECENT.size() > MAX_RECENT) RECENT.removeLast();
        }
        try {
            Waypointer.LOGGER.warn("Import from {} failed ({}, {} chars, starts {}): {}",
                    recent.origin(), kind.name().toLowerCase(Locale.ROOT), recent.length(),
                    recent.prefix(), recent.detail());
        } catch (RuntimeException | LinkageError loggingUnavailable) {
            // Unit tests run without the mod's logger.
        }
        return kind;
    }

    /** The newest failures first. */
    public static List<Recent> recent() {
        synchronized (RECENT) {
            return List.copyOf(RECENT);
        }
    }

    public static void clearRecent() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    /** The format marker a code starts with (such as {@code WP:}), never its contents. */
    private static String formatPrefix(String text) {
        if (text.isEmpty()) return "(empty)";
        int end = 0;
        while (end < text.length() && end < 6 && (Character.isLetterOrDigit(text.charAt(end))
                || text.charAt(end) == ':' || text.charAt(end) == '{' || text.charAt(end) == '[')) {
            end++;
            if (text.charAt(end - 1) == ':') break;
        }
        return end == 0 ? "(no marker)" : text.substring(0, end);
    }
}
