package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sniffs incoming game chat for "x y z" coordinate triples and recolors the coord
 * numbers themselves into clickable aqua-underlined runs. If the user enables
 * automatic chat temp waypoints, each detected coordinate also drops a temporary
 * waypoint into the zone's temp bucket; the default stays click-to-add only.
 *
 * <p>Chat-shared coords are almost always one-offs -- "meet me at 100 64 200"
 * -- so committing them to the permanent route was the wrong default and
 * caused routes to accumulate stale waypoints from old sessions. The
 * {@code addtemp} variant matches the intent: mark it while useful, let it
 * vanish on its own.
 *
 * We walk the original component's styled runs with {@link FormattedText#visit}
 * so styling on non-coord text (rank prefixes, mode colors, etc.) is preserved.
 * Only the coord-number substrings pick up the aqua/underline/click overrides,
 * giving the message an inline-highlighted look instead of a trailing chip.
 *
 * False-positive policy lives in {@link CoordScanner} -- that class has no
 * Minecraft dependencies and is unit-tested.
 */
public final class ChatCoordDetector {

    private static final Pattern BRACKETED_PREFIX = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern USERNAME_TOKEN = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");
    private static final char FORMAT_PREFIX = '\u00A7';
    private static final long AUTO_ADD_DEDUPE_MS = 15_000L;

    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final Map<String, Long> recentAutoAddedChatTemps = new HashMap<>();

    public ChatCoordDetector(WaypointerConfig config, ActiveGroupManager manager) {
        this.config = config;
        this.manager = manager;
    }

    public void install() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
    }

    private Component onMessage(Component msg, boolean overlay) {
        // Action bar / overlay messages disappear after a fraction of a second;
        // decorating them is wasted effort and visually noisy.
        if (overlay) return msg;
        if (WaypointerChatFeedback.consumeIfSuppressed(msg)) return msg;
        if (!config.chatCoordDetection()) return msg;

        String flat = msg.getString();
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions(flat);
        if (matches.isEmpty()) return msg;

        List<CoordScanner.Match> visibleMatches = new ArrayList<>(matches.size());
        List<String> tempLabels = new ArrayList<>(matches.size());
        List<String> senderNames = new ArrayList<>(matches.size());
        for (CoordScanner.Match match : matches) {
            String senderName = senderNameForChatTemp(flat, match.start());
            if (!senderName.isBlank() && config.isChatCoordSenderBlacklisted(senderName)) {
                continue;
            }
            visibleMatches.add(match);
            senderNames.add(senderName);
            tempLabels.add(senderLabelForChatTemp(msg, flat, match.start()));
        }
        if (visibleMatches.isEmpty()) return msg;

        if (config.autoAddChatTempWaypoints()) {
            long now = System.currentTimeMillis();
            pruneRecentAutoAddedChatTemps(now);
            for (int i = 0; i < visibleMatches.size(); i++) {
                CoordScanner.Match match = visibleMatches.get(i);
                String dedupeKey = autoAddDedupeKey(flat, match);
                if (recentAutoAddedChatTemps.containsKey(dedupeKey)) {
                    continue;
                }
                recentAutoAddedChatTemps.put(dedupeKey, now);
                var group = manager.addTempWaypoint(match.x(), match.y(), match.z(),
                        tempLabels.get(i),
                        config.tempDefaultMode(),
                        config.defaultTempExpiresAtMillis(now),
                        config.defaultWaypointColor());
                if (config.focusTempWaypoints()) {
                    manager.focusTempWaypoint(group, group.size() - 1);
                }
            }
        }

        return rebuildWithHighlights(msg, visibleMatches, flat,
                config.autoAddChatTempWaypoints(), tempLabels, senderNames);
    }

    private void pruneRecentAutoAddedChatTemps(long now) {
        recentAutoAddedChatTemps.entrySet().removeIf(entry -> entry.getValue() + AUTO_ADD_DEDUPE_MS < now);
    }

    static String autoAddDedupeKey(String flatText, CoordScanner.Match match) {
        String line = flatText == null ? "" : flatText;
        if (match == null) return line;
        return line + "\n@" + match.x() + "," + match.y() + "," + match.z();
    }

    /**
     * Walk {@code msg}'s styled runs and rebuild a new component with the coord
     * substrings restyled as click chips. Segments outside coord matches keep their
     * original style verbatim; segments inside coord matches override color +
     * underline + click event (hover wraps both).
     */
    private static Component rebuildWithHighlights(Component msg, List<CoordScanner.Match> matches,
                                                   String flatText,
                                                   boolean chatTempAlreadyAutoAdded,
                                                   List<String> tempLabels,
                                                   List<String> senderNames) {
        Builder builder = new Builder(matches, flatText, chatTempAlreadyAutoAdded, tempLabels, senderNames);
        // visit() walks the full styled-run tree and returns Optional.empty() on
        // success; we rely on that to feed every substring into our builder in order.
        msg.visit((style, content) -> {
            builder.append(style, content);
            return Optional.<Boolean>empty();
        }, Style.EMPTY);
        return builder.build();
    }

    /**
     * Stateful assembler that knows the absolute character offset it has consumed so
     * far, the remaining coord matches to inject, and the output component. Each
     * styled run is either (a) fully outside every match (appended verbatim),
     * (b) fully inside a match (restyled), or (c) spans a match boundary (split).
     */
    private static final class Builder {
        private final List<CoordScanner.Match> matches;
        private final String flatText;
        private int cursor;        // absolute offset in flat text
        private int matchIdx;      // index into matches list
        private final MutableComponent out = Component.empty();
        private final boolean chatTempAlreadyAutoAdded;
        private final List<String> tempLabels;
        private final List<String> senderNames;

        Builder(List<CoordScanner.Match> matches, String flatText,
                boolean chatTempAlreadyAutoAdded, List<String> tempLabels, List<String> senderNames) {
            this.matches = matches;
            this.flatText = flatText;
            this.chatTempAlreadyAutoAdded = chatTempAlreadyAutoAdded;
            this.tempLabels = tempLabels;
            this.senderNames = senderNames;
        }

        void append(Style style, String content) {
            if (content.isEmpty()) return;

            int segmentStart = cursor;
            int segmentEnd = cursor + content.length();

            int localStart = 0; // offset within `content` we've emitted up to
            while (matchIdx < matches.size()) {
                CoordScanner.Match m = matches.get(matchIdx);
                if (m.end() <= segmentStart) {
                    // Match is entirely before this segment -- the producer skipped
                    // whitespace or similar. Advance and keep scanning.
                    matchIdx++;
                    continue;
                }
                if (m.start() >= segmentEnd) break; // match is after this segment

                // Emit pre-match slice (if any) in the original style.
                int preStartLocal = Math.max(0, m.start() - segmentStart);
                if (preStartLocal > localStart) {
                    out.append(Component.literal(content.substring(localStart, preStartLocal)).setStyle(style));
                }

                // Emit overlapping-with-match slice in chip style.
                int matchEndLocal = Math.min(content.length(), m.end() - segmentStart);
                int sliceStart = Math.max(localStart, preStartLocal);
                if (matchEndLocal > sliceStart) {
                    String slice = content.substring(sliceStart, matchEndLocal);
                    out.append(Component.literal(slice).setStyle(chipStyle(style, m, flatText,
                            chatTempAlreadyAutoAdded, tempLabels.get(matchIdx),
                            senderNames.get(matchIdx))));
                    localStart = matchEndLocal;
                }

                if (m.end() > segmentEnd) {
                    // Match continues into a later segment; don't advance matchIdx yet.
                    break;
                }
                out.append(blockSenderAction(senderNames.get(matchIdx)));
                matchIdx++;
            }

            if (localStart < content.length()) {
                out.append(Component.literal(content.substring(localStart)).setStyle(style));
            }

            cursor = segmentEnd;
        }

        Component build() { return out; }
    }

    /**
     * Style overrides applied to coord-match substrings. We inherit the base style's
     * font / insertion / shadow so server-side formatting isn't clobbered, then
     * force aqua + underline + click + hover so the coord reads as a button.
     */
    private static Style chipStyle(Style base, CoordScanner.Match m, String flatText,
            boolean chatTempAlreadyAutoAdded, String tempLabel, String senderName) {
        // Target the temp variant so chat-shared coords stay ephemeral instead
        // of being appended to the user's permanent route.
        Style styled = base
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true);

        String cmd = "/waypointer chattemp " + m.x() + " " + m.y() + " " + m.z()
                + " " + (senderName == null || senderName.isBlank() ? "-" : senderName)
                + " " + encodeChatTempSource(tempLabel);
        return styled
                .withClickEvent(new ClickEvent.RunCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(tempWaypointHover(m,
                        chatTempAlreadyAutoAdded, senderName)));
    }

    private static Component blockSenderAction(String senderName) {
        if (senderName == null || senderName.isBlank()) return Component.empty();
        return Component.literal(" [Block]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent.RunCommand("/waypointer blacklist add " + senderName))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Ignore chat coordinates from " + senderName)
                                .withStyle(ChatFormatting.RED))));
    }
    private static Component tempWaypointHover(CoordScanner.Match m, boolean alreadyAdded,
                                               String senderName) {
        MutableComponent out = Component.empty()
                .append(Component.literal("Temporary waypoint at ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("(" + m.x() + ", " + m.y() + ", " + m.z() + ")")
                        .withStyle(ChatFormatting.YELLOW));

        out.append(Component.literal("\n"));
        out.append(Component.literal(alreadyAdded ? "Click to use this temporary waypoint."
                : "Click to add a temporary waypoint.").withStyle(ChatFormatting.AQUA));

        return out;
    }

    private static String encodeChatTempSource(String source) {
        if (source == null || source.isBlank()) return "-";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    static String senderLabelForChatTemp(Component message, String flatText, int coordStart) {
        SenderSpan span = senderSpanForChatTemp(flatText, coordStart);
        if (span == null) return "";

        String sender = legacyFormattedSlice(message, span.start(), span.end()).trim();
        return sender.isEmpty()
                ? ""
                : ChatFormatting.YELLOW + "From " + sender;
    }

    static String senderNameForChatTemp(String flatText, int coordStart) {
        if (flatText == null || flatText.isBlank()) return "";
        int end = Math.max(0, Math.min(coordStart, flatText.length()));
        int colon = indexOfLastSenderColon(flatText, end);
        if (colon < 0) return "";

        String prefix = BRACKETED_PREFIX.matcher(flatText.substring(0, colon)).replaceAll(" ");
        Matcher matcher = USERNAME_TOKEN.matcher(prefix);
        String last = "";
        while (matcher.find()) last = matcher.group();
        return last;
    }

    private static SenderSpan senderSpanForChatTemp(String flatText, int coordStart) {
        if (flatText == null || flatText.isBlank()) return null;
        int endLimit = Math.max(0, Math.min(coordStart, flatText.length()));
        int colon = indexOfLastSenderColon(flatText, endLimit);
        if (colon < 0) return null;

        int end = trimWhitespaceBackward(flatText, colon);
        if (end <= 0) return null;

        Matcher matcher = USERNAME_TOKEN.matcher(flatText.substring(0, end));
        int userStart = -1;
        while (matcher.find()) userStart = matcher.start();
        if (userStart < 0) return null;

        int start = userStart;
        int rankClose = flatText.lastIndexOf(']', userStart);
        if (rankClose >= 0) {
            int rankOpen = flatText.lastIndexOf('[', rankClose);
            if (rankOpen >= 0
                    && rankClose < userStart
                    && isRankLikeBracket(flatText, rankOpen, rankClose)
                    && flatText.substring(rankClose + 1, userStart).trim().isEmpty()) {
                start = rankOpen;
            }
        }
        start = includeAdjacentLegacyFormattingPrefix(flatText, start);
        return new SenderSpan(start, end);
    }

    private static boolean isRankLikeBracket(String text, int open, int close) {
        String inside = text.substring(open + 1, close).trim();
        if (inside.equalsIgnoreCase("chat")) return false;
        boolean hasLetter = false;
        for (int i = 0; i < inside.length(); i++) {
            if (Character.isLetter(inside.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private static int trimWhitespaceBackward(String text, int endExclusive) {
        int end = endExclusive;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end;
    }

    private static int includeAdjacentLegacyFormattingPrefix(String text, int start) {
        int out = start;
        while (out >= 2
                && text.charAt(out - 2) == FORMAT_PREFIX
                && isLegacyFormattingCode(text.charAt(out - 1))) {
            out -= 2;
        }
        return out;
    }

    private static String legacyFormattedSlice(Component message, int start, int end) {
        LegacySliceBuilder builder = new LegacySliceBuilder(start, end);
        message.visit((style, content) -> {
            builder.append(style, content);
            return Optional.<Boolean>empty();
        }, Style.EMPTY);
        return builder.out.toString();
    }

    private static final class LegacySliceBuilder {
        private final int start;
        private final int end;
        private int cursor;
        private final StringBuilder out = new StringBuilder();

        LegacySliceBuilder(int start, int end) {
            this.start = start;
            this.end = end;
        }

        void append(Style style, String content) {
            if (content.isEmpty()) return;

            int segmentStart = cursor;
            int segmentEnd = cursor + content.length();
            int sliceStart = Math.max(start, segmentStart);
            int sliceEnd = Math.min(end, segmentEnd);
            if (sliceStart < sliceEnd) {
                int localStart = sliceStart - segmentStart;
                int localEnd = sliceEnd - segmentStart;
                if (!startsWithLegacyFormattingCode(content, localStart, localEnd)) {
                    appendLegacyStyle(out, style);
                }
                out.append(content, localStart, localEnd);
            }
            cursor = segmentEnd;
        }
    }

    private static void appendLegacyStyle(StringBuilder out, Style style) {
        appendLegacyColor(out, style.getColor());
        if (style.isBold()) out.append(ChatFormatting.BOLD);
        if (style.isItalic()) out.append(ChatFormatting.ITALIC);
        if (style.isUnderlined()) out.append(ChatFormatting.UNDERLINE);
        if (style.isStrikethrough()) out.append(ChatFormatting.STRIKETHROUGH);
        if (style.isObfuscated()) out.append(ChatFormatting.OBFUSCATED);
    }

    private static void appendLegacyColor(StringBuilder out, TextColor color) {
        if (color == null) {
            out.append(ChatFormatting.WHITE);
            return;
        }
        for (ChatFormatting formatting : ChatFormatting.values()) {
            Integer rgb = formatting.getColor();
            if (rgb != null && (rgb & 0xFFFFFF) == (color.getValue() & 0xFFFFFF)) {
                out.append(formatting);
                return;
            }
        }
        String hex = String.format("%06X", color.getValue() & 0xFFFFFF);
        out.append('\u00A7').append('x');
        for (int i = 0; i < hex.length(); i++) {
            out.append('\u00A7').append(hex.charAt(i));
        }
    }

    private static boolean isLegacyFormattingCode(char c) {
        if (c >= '0' && c <= '9') return true;
        c = Character.toLowerCase(c);
        return c >= 'a' && c <= 'f'
                || c >= 'k' && c <= 'o'
                || c == 'r';
    }

    private static boolean startsWithLegacyFormattingCode(String text, int start, int end) {
        return start + 1 < end
                && text.charAt(start) == FORMAT_PREFIX
                && isLegacyFormattingCode(text.charAt(start + 1));
    }

    private record SenderSpan(int start, int end) {}

    /**
     * Last {@code ':'} before {@code beforeIndex} that is not part of a labeled-axis
     * prefix ({@code x:}/{@code y:}/{@code z:}). Bare coord matches after a labeled
     * triple would otherwise latch onto {@code z:} and mis-parse the sender token.
     */
    private static int indexOfLastSenderColon(String flatText, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            if (flatText.charAt(i) != ':') continue;
            if (isLabeledAxisColon(flatText, i)) continue;
            return i;
        }
        return -1;
    }

    private static boolean isLabeledAxisColon(String s, int colonIdx) {
        int j = colonIdx - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        if (j < 0) return false;
        char c = s.charAt(j);
        if (c != 'x' && c != 'X' && c != 'y' && c != 'Y' && c != 'z' && c != 'Z') return false;
        return j == 0 || !Character.isLetterOrDigit(s.charAt(j - 1));
    }
}
