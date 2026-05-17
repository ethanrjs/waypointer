package dev.ethan.waypointer.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, side-effect-free scanner that pulls plausible Minecraft coordinate triples
 * out of a chat string. Lives in the main source set (no Minecraft dependencies)
 * so the false-positive policy can be unit-tested without bootstrapping the game.
 *
 * The scanner is intentionally conservative: it would rather miss a real coord
 * callout than dump a misleading "+ 2024 12 31" chip onto a build-version line.
 * Tighten the bounds further if real-world testing surfaces specific bad matches.
 */
public final class CoordScanner {

    /**
     * Three integers separated by whitespace, comma, semicolon, or slash.
     * Lookbehind/lookahead exclude alphanumerics, hyphen, and dot to avoid
     * catching the middle of versions, hostnames, or decimals.
     */
    private static final Pattern COORD = Pattern.compile(
            "(?<![\\w.\\-])(-?\\d{1,5})([\\s,;/]+)(-?\\d{1,4})([\\s,;/]+)(-?\\d{1,5})(?![\\w.\\-])"
    );

    /**
     * Labeled "x: <num>, y: <num>, z: <num>" form -- case-insensitive, with
     * optional whitespace around the colons and commas. Matches the common
     * in-game callout style ("x: 100, y: 64, z: -200") that the bare-number
     * regex cannot recognize because the alphabetic axis labels break its
     * all-digit/separator structure.
     *
     * <p>Integers only: matching the bare scanner's behavior keeps the
     * detector's false-positive policy consistent (decimals are caught by
     * the trailing lookahead just like in the bare form). Comma is the only
     * inter-axis separator, so {@code "x: 1,000, y: 64, z: 0"} fails to
     * parse -- the comma inside {@code 1,000} leaves no axis label between
     * x's value and the next group, which preserves the issue #3 fix
     * against thousands-separated numbers.
     */
    private static final Pattern COORD_LABELED = Pattern.compile(
            "(?<![\\w.\\-])[xX]\\s*:\\s*(-?\\d{1,5})\\s*,\\s*[yY]\\s*:\\s*(-?\\d{1,4})\\s*,\\s*[zZ]\\s*:\\s*(-?\\d{1,5})(?![\\w.\\-])"
    );

    public static final int MAX_MATCHES_PER_MESSAGE = 5;
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    public static final int MAX_HORIZONTAL = 30_000;

    private CoordScanner() {}

    public record Coord(int x, int y, int z) {}

    /** A coordinate match with its character offsets in the scanned text. */
    public record Match(int start, int end, int x, int y, int z) {
        public Coord coord() { return new Coord(x, y, z); }
    }

    public static List<Coord> scan(String text) {
        List<Match> matches = scanWithPositions(text);
        List<Coord> out = new ArrayList<>(matches.size());
        for (Match m : matches) out.add(m.coord());
        return out;
    }

    /**
     * Same filtering as {@link #scan} but returns match positions too, so callers that
     * want to modify the source text in place (e.g. recolor the coord numbers) can
     * slice at exact character boundaries.
     */
    public static List<Match> scanWithPositions(String text) {
        if (text == null || text.isEmpty()) return List.of();

        // Bare and labeled regexes can't overlap (one starts on a digit/minus,
        // the other on an axis letter), so collecting separately and merging by
        // start offset is sound. Each pass enforces the per-message cap; if both
        // saturate it we trim once more after sorting so chips stay in the order
        // they appear in chat.
        List<Match> out = new ArrayList<>();
        collectBareMatches(text, out);
        collectLabeledMatches(text, out);

        if (out.size() > 1) {
            out.sort(Comparator.comparingInt(Match::start));
        }
        if (out.size() > MAX_MATCHES_PER_MESSAGE) {
            return new ArrayList<>(out.subList(0, MAX_MATCHES_PER_MESSAGE));
        }
        return out;
    }

    private static void collectBareMatches(String text, List<Match> out) {
        Matcher m = COORD.matcher(text);
        while (m.find()) {
            int x = parseOrSentinel(m.group(1));
            String sep1 = m.group(2);
            int y = parseOrSentinel(m.group(3));
            String sep2 = m.group(4);
            int z = parseOrSentinel(m.group(5));
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) continue;
            if (y < MIN_Y || y > MAX_Y) continue;
            if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL) continue;
            if (looksLikeThousandsSeparator(m.group(3), sep1, sep2)) continue;
            if (looksLikeFractionSeparator(sep1, sep2)) continue;
            out.add(new Match(m.start(), m.end(), x, y, z));
            if (out.size() >= MAX_MATCHES_PER_MESSAGE) break;
        }
    }

    private static void collectLabeledMatches(String text, List<Match> out) {
        Matcher m = COORD_LABELED.matcher(text);
        int found = 0;
        while (m.find()) {
            int x = parseOrSentinel(m.group(1));
            int y = parseOrSentinel(m.group(2));
            int z = parseOrSentinel(m.group(3));
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) continue;
            if (y < MIN_Y || y > MAX_Y) continue;
            if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL) continue;
            // The thousands-separator filter doesn't apply: the explicit axis
            // labels disambiguate the format, and the regex itself rejects
            // "x: 1,000, ..." because the inner comma leaves no `y:` token in
            // the right place for the next group.
            out.add(new Match(m.start(), m.end(), x, y, z));
            if (++found >= MAX_MATCHES_PER_MESSAGE) break;
        }
    }

    /**
     * Detects numbers written with thousands separators -- {@code "1,145,926"}
     * (bank interest), {@code "12,345,678"} (coin drops), {@code "1,000,000"}
     * (leaderboards). These trivially satisfy "three integers comma-separated"
     * but are never real coordinates.
     *
     * <p>The signature is: both separators are a bare comma (no whitespace)
     * AND the middle group is exactly 3 digits AND the trailing group starts
     * with 3 digits. Real coord callouts use comma+space or space alone, so
     * this filter has effectively zero overlap with legitimate input.
     */
    private static boolean looksLikeThousandsSeparator(String middleRaw, String sep1, String sep2) {
        if (!",".equals(sep1) || !",".equals(sep2)) return false;
        if (middleRaw.length() != 3) return false;
        // No negatives in thousands-separated numbers; a leading '-' on the
        // middle or trailing group guarantees these are distinct integers.
        return middleRaw.charAt(0) != '-';
    }

    /**
     * Slash-delimited coords are accepted only in the explicit {@code x/y/z}
     * form. Mixed whitespace/slash strings are overwhelmingly fractions or
     * progress counters, e.g. {@code "3 99/100"}, not coordinates.
     */
    private static boolean looksLikeFractionSeparator(String sep1, String sep2) {
        boolean slash1 = sep1.indexOf('/') >= 0;
        boolean slash2 = sep2.indexOf('/') >= 0;
        if (!slash1 && !slash2) return false;
        return !("/".equals(sep1) && "/".equals(sep2));
    }

    private static int parseOrSentinel(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return Integer.MIN_VALUE; }
    }
}
