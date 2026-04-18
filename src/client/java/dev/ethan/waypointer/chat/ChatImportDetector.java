package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.config.WaypointerConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects Waypointer codec strings inside incoming chat messages and replaces them
 * with a short, on-theme "Click to import" pill.
 *
 * Replacement rather than appending (which is what {@link ChatCoordDetector} does
 * for coordinates) because a raw codec is a wall of random ASCII -- leaving it inline
 * makes chat unreadable for everyone else in the channel. The pill links back to the
 * cached codec via {@link ChatImportCache} so the click handler can recover the full
 * payload without blowing past the 256-char chat input cap.
 *
 * The pill uses Waypointer's brand color (cyan-teal matching the default waypoint
 * color) and a bracketed label so it's unambiguously an interactive element.
 */
public final class ChatImportDetector {

    /** Matches {@link dev.ethan.waypointer.core.Waypoint#DEFAULT_COLOR}'s hue. */
    private static final ChatFormatting PILL_COLOR = ChatFormatting.AQUA;
    private static final ChatFormatting ACCENT     = ChatFormatting.LIGHT_PURPLE;

    private final WaypointerConfig config;
    private final ChatImportCache cache;

    public ChatImportDetector(WaypointerConfig config, ChatImportCache cache) {
        this.config = config;
        this.cache = cache;
    }

    public void install() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
    }

    private Component onMessage(Component msg, boolean overlay) {
        if (overlay) return msg;
        if (!config.chatCodecDetection()) return msg;

        String text = msg.getString();
        List<CodecScanner.Match> matches = CodecScanner.scan(text);
        if (matches.isEmpty()) return msg;

        // Walk the component tree into (style, segment) runs so we can splice in the
        // pill without throwing away upstream formatting (server prefix colors, the
        // "[MVP++]" ranks, etc.). The previous implementation used msg.getString()
        // which flattens everything to unstyled literal text -- chat lines survived
        // the replacement but rendered as plain white text, losing the context that
        // tells the reader who sent the message.
        List<StyledRun> runs = collectRuns(msg);
        return spliceMatches(runs, matches);
    }

    /** One styled slice of the original component tree, in read order. */
    private record StyledRun(String text, Style style) {}

    private static List<StyledRun> collectRuns(Component msg) {
        List<StyledRun> runs = new ArrayList<>();
        msg.visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
            if (!text.isEmpty()) runs.add(new StyledRun(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    /**
     * Rebuilds the message by re-emitting each styled run, replacing any slice that
     * falls inside a {@link CodecScanner.Match} with a pill component. Matches span
     * the flattened character range so a match can start in one run and end in
     * another -- we slice every run against the match boundaries.
     */
    private MutableComponent spliceMatches(List<StyledRun> runs, List<CodecScanner.Match> matches) {
        MutableComponent out = Component.empty();
        int flatPos = 0;
        int matchIdx = 0;

        for (StyledRun run : runs) {
            int runStart = flatPos;
            int runEnd   = flatPos + run.text.length();
            int cursor   = runStart;

            while (cursor < runEnd) {
                CodecScanner.Match m = matchIdx < matches.size() ? matches.get(matchIdx) : null;

                if (m == null || cursor < m.start()) {
                    // Plain slice of this run up to the next match (or end of run).
                    int sliceEnd = m == null ? runEnd : Math.min(m.start(), runEnd);
                    String slice = run.text.substring(cursor - runStart, sliceEnd - runStart);
                    out.append(Component.literal(slice).withStyle(run.style));
                    cursor = sliceEnd;
                    continue;
                }

                // We're inside a match -- emit the pill exactly once (at match start)
                // and fast-forward the cursor to the end of the match or this run,
                // whichever comes first. Matches always start on a WP:... boundary
                // which is ASCII-contiguous, so they won't overlap inherited styles
                // in a meaningful way -- the pill's own styling is self-contained.
                if (cursor == m.start()) {
                    out.append(buildPill(m));
                }
                int consumedTo = Math.min(m.end(), runEnd);
                cursor = consumedTo;
                if (consumedTo == m.end()) matchIdx++;
            }
            flatPos = runEnd;
        }
        return out;
    }

    /**
     * Builds the click-to-import pill. Only the "Click to import Waypoints" text
     * carries the underline; the brackets, diamond, and trailing space do not.
     * The click/hover events live on the top-level component so the whole pill is
     * one interactive target -- that's what lets the user click anywhere inside
     * the brackets to trigger the import.
     */
    private MutableComponent buildPill(CodecScanner.Match match) {
        String handle = cache.put(match.text());
        String command = "/wp importchat " + handle;

        // Each segment's style explicitly sets underlined=false except for the
        // clickable text. Without the explicit false, children would inherit the
        // underline we'd otherwise apply at the pill root -- which is how the old
        // implementation ended up underlining the space after the diamond.
        Style notUnderlined = Style.EMPTY.withUnderlined(false);

        MutableComponent pill = Component.empty()
                .append(Component.literal("[")
                        .withStyle(notUnderlined.withColor(ChatFormatting.DARK_AQUA)))
                .append(Component.literal("\u25C6 ")
                        .withStyle(notUnderlined.withColor(ACCENT)))
                .append(Component.literal("Click to import Waypoints")
                        .withStyle(Style.EMPTY.withColor(PILL_COLOR).withUnderlined(true)))
                .append(Component.literal("]")
                        .withStyle(notUnderlined.withColor(ChatFormatting.DARK_AQUA)));

        Style interactive = Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverText(match)));
        return pill.withStyle(interactive);
    }

    private static Component hoverText(CodecScanner.Match match) {
        MutableComponent c = Component.empty();
        c.append(Component.literal("Waypointer route").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        // peekLabel does the cheapest possible decode -- header byte + optional
        // varint+UTF-8 -- so it's safe to call once per detected codec per chat
        // line. Anything richer (counts/groups) would need a full decode and
        // we'd rather pay that on click than on every incoming line.
        String label = peekLabelSafely(match.text());
        if (!label.isEmpty()) {
            c.append(Component.literal("\n"));
            c.append(Component.literal("\u201C").withStyle(ChatFormatting.GRAY));
            // Render as a literal -- the label is sanitized at encode time
            // (sanitizeLabel strips section signs and control chars), but
            // forcing white + no other style here makes that tampering
            // visually impossible even on a future schema slip.
            c.append(Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
            c.append(Component.literal("\u201D").withStyle(ChatFormatting.GRAY));
        }

        c.append(Component.literal("\n"));
        c.append(Component.literal("Size: ").withStyle(ChatFormatting.GRAY));
        c.append(Component.literal(match.length() + " chars").withStyle(ChatFormatting.WHITE));
        c.append(Component.literal("\n\n"));
        c.append(Component.literal("Click to import into your current zone.").withStyle(ChatFormatting.YELLOW));
        return c;
    }

    private static String peekLabelSafely(String codec) {
        try {
            return WaypointCodec.peekLabel(codec).orElse("");
        } catch (RuntimeException ignored) {
            // peekLabel only walks the header + label, but a truncated codec
            // pasted into chat could still throw -- we don't want a malformed
            // payload to suppress the entire pill, just hide the label.
            return "";
        }
    }
}
