package dev.ethan.waypointer.chat;

import java.util.ArrayList;
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

        Matcher m = COORD.matcher(text);
        List<Match> out = new ArrayList<>();
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
            out.add(new Match(m.start(), m.end(), x, y, z));
            if (out.size() >= MAX_MATCHES_PER_MESSAGE) break;
        }
        return out;
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

    private static int parseOrSentinel(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return Integer.MIN_VALUE; }
    }
}
