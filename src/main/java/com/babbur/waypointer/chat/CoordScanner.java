package com.babbur.waypointer.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds likely Minecraft coordinate triples in chat while rejecting common number formats. */
public final class CoordScanner {

    // Boundaries keep versions, hostnames, and decimals from matching.
    private static final Pattern COORD = Pattern.compile(
            "(?<![\\w.\\-])(-?\\d{1,5})([\\s,;/]+)(-?\\d{1,4})([\\s,;/]+)(-?\\d{1,5})(?![\\w.\\-])"
    );

    // Labeled coordinates require commas between the three integer axes.
    private static final Pattern COORD_LABELED = Pattern.compile(
            "(?<![\\w.\\-])[xX]\\s*:\\s*(-?\\d{1,5})\\s*,\\s*[yY]\\s*:\\s*(-?\\d{1,4})\\s*,\\s*[zZ]\\s*:\\s*(-?\\d{1,5})(?![\\w.\\-])"
    );

    public static final int MAX_MATCHES_PER_MESSAGE = 5;
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    public static final int MAX_HORIZONTAL = 30_000;

    private CoordScanner() {}

    public record Coord(int x, int y, int z) {}

    public record Match(int start, int end, int x, int y, int z) {
        public Coord coord() { return new Coord(x, y, z); }
    }

    public static List<Coord> scan(String text) {
        List<Match> matches = scanWithPositions(text);
        List<Coord> out = new ArrayList<>(matches.size());
        for (Match m : matches) out.add(m.coord());
        return out;
    }

    /** Returns coordinates and their character ranges in the source text. */
    public static List<Match> scanWithPositions(String text) {
        if (text == null || text.isEmpty()) return List.of();

        // The patterns cannot overlap, so merge their matches by source position.
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
            out.add(new Match(m.start(), m.end(), x, y, z));
            if (++found >= MAX_MATCHES_PER_MESSAGE) break;
        }
    }

    /** Rejects values such as {@code 1,145,926} that look like coordinates. */
    private static boolean looksLikeThousandsSeparator(String middleRaw, String sep1, String sep2) {
        if (!",".equals(sep1) || !",".equals(sep2)) return false;
        if (middleRaw.length() != 3) return false;
        return middleRaw.charAt(0) != '-';
    }

    /** Rejects mixed slash separators such as {@code 3 99/100}. */
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
