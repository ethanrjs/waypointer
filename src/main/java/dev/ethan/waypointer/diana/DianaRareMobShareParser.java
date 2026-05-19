package dev.ethan.waypointer.diana;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DianaRareMobShareParser {

    private static final char FORMAT_PREFIX = '\u00A7';
    private static final Pattern LABELED_COORDS = Pattern.compile(
            "(?i)(?<player>.+?):\\s*x:\\s*(?<x>-?\\d+(?:\\.\\d+)?),?\\s*y:\\s*(?<y>-?\\d+(?:\\.\\d+)?),?\\s*z:\\s*(?<z>-?\\d+(?:\\.\\d+)?)(?:\\s*\\|\\s*(?<mob>[^|]+))?.*");
    private static final Pattern INQUISITOR_COORDS = Pattern.compile(
            "(?i)(?<player>.+?):\\s*A MINOS INQUISITOR has spawned near \\[.*?]\\s*at Coords\\s+(?<x>-?\\d+(?:\\.\\d+)?)\\s+(?<y>-?\\d+(?:\\.\\d+)?)\\s+(?<z>-?\\d+(?:\\.\\d+)?).*");
    private static final Pattern USERNAME_TOKEN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final Pattern BRACKETED_PREFIX = Pattern.compile("\\[[^\\]]*]");

    private DianaRareMobShareParser() {}

    public static Optional<Share> parse(String message) {
        if (message == null || message.isBlank()) return Optional.empty();

        String clean = stripLegacyFormatting(message);
        Matcher inquisitor = INQUISITOR_COORDS.matcher(clean);
        if (inquisitor.matches()) {
            return Optional.of(share(inquisitor, "Minos Inquisitor"));
        }

        Matcher labeled = LABELED_COORDS.matcher(clean);
        if (labeled.matches()) {
            String mob = labeled.group("mob");
            String mobName = cleanMobName(mob == null ? clean : mob);
            if (mobName == null) return Optional.empty();
            return Optional.of(share(labeled, mobName));
        }

        return Optional.empty();
    }

    private static Share share(Matcher matcher, String mobName) {
        return new Share(
                playerName(matcher.group("player")),
                mobName,
                blockCoord(matcher.group("x")),
                blockCoord(matcher.group("y")),
                blockCoord(matcher.group("z")));
    }

    private static int blockCoord(String raw) {
        return (int) Math.round(Double.parseDouble(raw));
    }

    private static String cleanMobName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        String lower = cleaned.toLowerCase(Locale.ROOT);
        DianaRareMob known = DianaRareMob.fromMobName(lower);
        if (known != null) return known.label();
        return null;
    }

    private static String playerName(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown";
        String prefixless = raw;
        int party = prefixless.lastIndexOf('>');
        if (party >= 0) prefixless = prefixless.substring(party + 1);
        prefixless = BRACKETED_PREFIX.matcher(prefixless).replaceAll(" ");

        Matcher matcher = USERNAME_TOKEN.matcher(prefixless);
        String last = "";
        while (matcher.find()) last = matcher.group();
        return last.isBlank() ? "Unknown" : last;
    }

    private static String stripLegacyFormatting(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == FORMAT_PREFIX && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public record Share(String playerName, String mobName, int x, int y, int z) {}
}
