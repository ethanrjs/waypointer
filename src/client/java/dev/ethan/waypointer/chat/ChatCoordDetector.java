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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;

/**
 * Sniffs incoming game chat for "x y z" coordinate triples and recolors the coord
 * numbers themselves into clickable aqua-underlined runs. By default, each
 * detected coordinate also drops a session-scoped temporary waypoint (expires
 * on disconnect) into the zone's temp bucket; users can turn that automatic
 * creation off and keep the click-to-add chip only.
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

    private final WaypointerConfig config;
    private final ActiveGroupManager manager;

    public ChatCoordDetector(WaypointerConfig config, ActiveGroupManager manager) {
        this.config = config;
        this.manager = manager;
    }

    public void install() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
    }

    private Component onMessage(Component msg, boolean overlay) {
        if (WaypointerChatFeedback.consumeIfSuppressed(msg)) return msg;
        // Action bar / overlay messages disappear after a fraction of a second;
        // decorating them is wasted effort and visually noisy.
        if (overlay) return msg;
        if (!config.chatCoordDetection()) return msg;

        String flat = msg.getString();
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions(flat);
        if (matches.isEmpty()) return msg;

        if (config.autoAddChatTempWaypoints()) {
            for (CoordScanner.Match match : matches) {
                manager.addTempWaypoint(match.x(), match.y(), match.z(),
                        senderNameForChatTemp(flat, match.start()));
            }
        }

        return rebuildWithHighlights(msg, matches, flat,
                config.autoAddChatTempWaypoints());
    }

    /**
     * Walk {@code msg}'s styled runs and rebuild a new component with the coord
     * substrings restyled as click chips. Segments outside coord matches keep their
     * original style verbatim; segments inside coord matches override color +
     * underline + click event (hover wraps both).
     */
    private static Component rebuildWithHighlights(Component msg, List<CoordScanner.Match> matches,
                                                   String flatText,
                                                   boolean chatTempAlreadyAutoAdded) {
        Builder builder = new Builder(matches, flatText, chatTempAlreadyAutoAdded);
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

        Builder(List<CoordScanner.Match> matches, String flatText,
                boolean chatTempAlreadyAutoAdded) {
            this.matches = matches;
            this.flatText = flatText;
            this.chatTempAlreadyAutoAdded = chatTempAlreadyAutoAdded;
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
                            chatTempAlreadyAutoAdded)));
                    localStart = matchEndLocal;
                }

                if (m.end() > segmentEnd) {
                    // Match continues into a later segment; don't advance matchIdx yet.
                    break;
                }
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
            boolean chatTempAlreadyAutoAdded) {
        // Target the temp variant so the waypoint auto-cleans on disconnect --
        // see the class javadoc for why chat-shared coords default to
        // session-scoped rather than permanent.
        Style styled = base
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true);
        if (chatTempAlreadyAutoAdded) {
            // Auto-add already created these temps; omit click so the chip cannot
            // add a second identical waypoint to the same temp group.
            return styled.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal("Temp waypoint already added at "
                            + m.x() + ", " + m.y() + ", " + m.z()
                            + "\n(expires on disconnect)")));
        }
        String source = senderNameForChatTemp(flatText, m.start());
        String cmd = "/waypointer addtemp at " + m.x() + " " + m.y() + " " + m.z()
                + (source.isEmpty() ? "" : " " + source);
        return styled
                .withClickEvent(new ClickEvent.RunCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Add temp waypoint at "
                                + m.x() + ", " + m.y() + ", " + m.z()
                                + "\n(expires on disconnect)")));
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
