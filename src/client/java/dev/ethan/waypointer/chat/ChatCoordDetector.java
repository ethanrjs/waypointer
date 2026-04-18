package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Optional;

/**
 * Sniffs incoming game chat for "x y z" coordinate triples and recolors the coord
 * numbers themselves into clickable aqua-underlined runs. Clicking runs
 * {@code /wp add at <x> <y> <z>}.
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

    private final WaypointerConfig config;

    public ChatCoordDetector(WaypointerConfig config) {
        this.config = config;
    }

    public void install() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
    }

    private Component onMessage(Component msg, boolean overlay) {
        // Action bar / overlay messages disappear after a fraction of a second;
        // decorating them is wasted effort and visually noisy.
        if (overlay) return msg;
        if (!config.chatCoordDetection()) return msg;

        String flat = msg.getString();
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions(flat);
        if (matches.isEmpty()) return msg;

        return rebuildWithHighlights(msg, matches);
    }

    /**
     * Walk {@code msg}'s styled runs and rebuild a new component with the coord
     * substrings restyled as click chips. Segments outside coord matches keep their
     * original style verbatim; segments inside coord matches override color +
     * underline + click event (hover wraps both).
     */
    private static Component rebuildWithHighlights(Component msg, List<CoordScanner.Match> matches) {
        Builder builder = new Builder(matches);
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
        private int cursor;        // absolute offset in flat text
        private int matchIdx;      // index into matches list
        private final MutableComponent out = Component.empty();

        Builder(List<CoordScanner.Match> matches) {
            this.matches = matches;
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
                    out.append(Component.literal(slice).setStyle(chipStyle(style, m)));
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
    private static Style chipStyle(Style base, CoordScanner.Match m) {
        String cmd = "/wp add at " + m.x() + " " + m.y() + " " + m.z();
        return base
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Add waypoint at " + m.x() + ", " + m.y() + ", " + m.z())));
    }
}
