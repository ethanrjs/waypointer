package com.babbur.waypointer.chat;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
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

/** Replaces share codes in chat with clickable import text. */
public final class ChatImportDetector {

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

        List<StyledRun> runs = collectRuns(msg);
        return spliceMatches(runs, matches);
    }

    private record StyledRun(String text, Style style) {}

    private static List<StyledRun> collectRuns(Component msg) {
        List<StyledRun> runs = new ArrayList<>();
        msg.visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
            if (!text.isEmpty()) runs.add(new StyledRun(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    /** Preserves the original styling outside each matched share code. */
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
                    int sliceEnd = m == null ? runEnd : Math.min(m.start(), runEnd);
                    String slice = run.text.substring(cursor - runStart, sliceEnd - runStart);
                    out.append(Component.literal(slice).withStyle(run.style));
                    cursor = sliceEnd;
                    continue;
                }

                if (cursor == m.start()) {
                    out.append(buildText(m));
                }
                int consumedTo = Math.min(m.end(), runEnd);
                cursor = consumedTo;
                if (consumedTo == m.end()) matchIdx++;
            }
            flatPos = runEnd;
        }
        return out;
    }

    private MutableComponent buildText(CodecScanner.Match match) {
        if (!match.valid()) {
            return buildInvalidPill(match);
        }

        String handle = cache.put(match.text());
        String command = "/waypointer importchat " + handle;

        Style notUnderlined = Style.EMPTY.withUnderlined(false);

        MutableComponent pill = Component.empty()
                .append(Component.literal("[")
                        .withStyle(notUnderlined.withColor(ChatFormatting.DARK_AQUA)))
                .append(Component.literal("\u25C6 ")
                        .withStyle(notUnderlined.withColor(ACCENT)))
                .append(Component.translatable("waypointer.chat.import.click")
                        .withStyle(Style.EMPTY.withColor(PILL_COLOR).withUnderlined(true)))
                .append(Component.literal("]")
                        .withStyle(notUnderlined.withColor(ChatFormatting.DARK_AQUA)));

        Style interactive = Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverText(match)));
        return pill.withStyle(interactive);
    }

    private static MutableComponent buildInvalidPill(CodecScanner.Match match) {
        Style notUnderlined = Style.EMPTY.withUnderlined(false);
        MutableComponent pill = Component.empty()
                .append(Component.literal("[")
                        .withStyle(notUnderlined.withColor(ChatFormatting.DARK_RED)))
                .append(Component.translatable("waypointer.chat.import.invalid")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)))
                .append(Component.literal("]")
                        .withStyle(notUnderlined.withColor(ChatFormatting.DARK_RED)));

        Style interactive = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                Component.empty()
                .append(Component.translatable("waypointer.chat.import.invalid.title")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.translatable("waypointer.chat.import.invalid.detail")
                        .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("\n"))
                        .append(Component.translatable(
                                "waypointer.chat.import.size", match.length())
                                .withStyle(ChatFormatting.GRAY))));
        return pill.withStyle(interactive);
    }

    private static Component hoverText(CodecScanner.Match match) {
        MutableComponent c = Component.empty();
        c.append(Component.translatable("waypointer.chat.import.route")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        // Decode only the label here; the full route is decoded after the click.
        String label = peekLabelSafely(match.text());
        if (!label.isEmpty()) {
            c.append(Component.literal("\n"));
            c.append(Component.literal("\u201C").withStyle(ChatFormatting.GRAY));
            c.append(Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
            c.append(Component.literal("\u201D").withStyle(ChatFormatting.GRAY));
        }

        c.append(Component.literal("\n"));
        c.append(Component.translatable("waypointer.chat.import.size", match.length())
                .withStyle(ChatFormatting.GRAY));
        c.append(Component.literal("\n\n"));
        c.append(Component.translatable("waypointer.chat.import.hover")
                .withStyle(ChatFormatting.YELLOW));
        return c;
    }

    private static String peekLabelSafely(String codec) {
        try {
            return WaypointCodec.peekLabel(codec).orElse("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
