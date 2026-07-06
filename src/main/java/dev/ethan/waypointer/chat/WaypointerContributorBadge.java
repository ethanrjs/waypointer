package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;

public final class WaypointerContributorBadge {
    private static final String CONTRIBUTOR = "Babbur";
    private static final String HOVER_TEXT = "This user is a contributor of Waypointer";

    private WaypointerContributorBadge() {
    }

    public static Component apply(Component component, WaypointerConfig config) {
        if (component == null || config == null || !config.showContributorBadges()) return component;
        return replace(component);
    }

    static Component replace(Component component) {
        String fullText = component.getString();
        int levelStart = contributorLevelStart(fullText);
        if (levelStart >= 0) {
            int levelEnd = fullText.indexOf(']', levelStart);
            MutableComponent out = Component.empty().withStyle(component.getStyle());
            out.append(Component.literal(fullText.substring(0, levelStart)).withStyle(component.getStyle()));
            out.append(badge());
            out.append(Component.literal(fullText.substring(levelEnd + 1)).withStyle(component.getStyle()));
            return out;
        }

        MutableComponent out = replaceOwnText(component);
        for (Component sibling : component.getSiblings()) {
            out.append(replace(sibling));
        }
        return out;
    }

    private static MutableComponent replaceOwnText(Component component) {
        if (!(component.getContents() instanceof PlainTextContents text)) {
            return component.plainCopy().withStyle(component.getStyle());
        }

        String raw = text.text();
        int levelStart = contributorLevelStart(raw);
        if (levelStart < 0) {
            return Component.literal(raw).withStyle(component.getStyle());
        }

        int levelEnd = raw.indexOf(']', levelStart);
        MutableComponent out = Component.empty().withStyle(component.getStyle());
        out.append(Component.literal(raw.substring(0, levelStart)).withStyle(component.getStyle()));
        out.append(badge());
        out.append(Component.literal(raw.substring(levelEnd + 1)).withStyle(component.getStyle()));
        return out;
    }

    private static int contributorLevelStart(String raw) {
        int nameStart = raw.indexOf(CONTRIBUTOR);
        if (nameStart < 0) return -1;

        int searchFrom = 0;
        while (searchFrom < nameStart) {
            int start = raw.indexOf('[', searchFrom);
            if (start < 0 || start >= nameStart) return -1;
            int end = raw.indexOf(']', start);
            if (end < 0 || end >= nameStart) return -1;
            if (start + 1 < end && isDigits(raw, start + 1, end)) return start;
            searchFrom = end + 1;
        }
        return -1;
    }

    private static boolean isDigits(String raw, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (!Character.isDigit(raw.charAt(i))) return false;
        }
        return true;
    }

    private static MutableComponent badge() {
        return Component.literal("[")
                .withStyle(ChatFormatting.DARK_GRAY)
                .withStyle(style -> style.withHoverEvent(hover()))
                .append(Component.literal("WP")
                        .withStyle(ChatFormatting.DARK_RED)
                        .withStyle(style -> style.withHoverEvent(hover())))
                .append(Component.literal("]")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .withStyle(style -> style.withHoverEvent(hover())));
    }

    private static HoverEvent hover() {
        return new HoverEvent.ShowText(Component.literal(HOVER_TEXT));
    }
}
