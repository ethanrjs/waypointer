package com.babbur.waypointer.chat;

import com.babbur.waypointer.config.WaypointerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WaypointerContributorBadge {
    private static final String CONTRIBUTOR = "Babbur";
    private static final UUID CONTRIBUTOR_ID = UUID.fromString("d0d70e3d-2475-4001-b27e-16b5118e5534");
    private static final String HOVER_TEXT = "This user is a contributor of Waypointer";

    private WaypointerContributorBadge() {
    }

    public static Component apply(Component component, WaypointerConfig config) {
        if (component == null || config == null || !config.showContributorBadges()) return component;
        PlainMessage message = plainMessage(component.getString());
        int levelStart = contributorChatLevelStart(message.text());
        if (levelStart < 0) return component;

        int levelEnd = message.text().indexOf(']', levelStart) + 1;
        return replaceLevelSpan(component,
                message.sourceIndex(levelStart),
                message.sourceIndex(levelEnd - 1) + 1);
    }

    public static Component applyPlayerName(Component component, String profileName, UUID profileId,
                                             WaypointerConfig config) {
        if (component == null || config == null || !config.showContributorBadges()
                || !isContributor(profileName, profileId)) {
            return component;
        }
        if (hasBadge(component)) return component;
        return contributorLevelStart(component.getString()) >= 0
                ? replace(component)
                : prependBadge(component);
    }
    public static Component applyTabName(Component component, WaypointerConfig config) {
        if (component == null || config == null || !config.showContributorBadges()) return component;
        return hasContributorTabLevel(plainMessage(component.getString()).text())
                ? badge().append(Component.literal(" "))
                        .append(Component.literal(CONTRIBUTOR).withStyle(ChatFormatting.AQUA))
                : component;
    }

    static Component hypixelRankPrefix(String playerRank, String packageRank,
                                        String monthlyPackageRank, String customPrefix) {
        String custom = stripLegacyFormatting(customPrefix);
        if (custom != null && !custom.isBlank()) return Component.literal(custom.trim());

        if ("YOUTUBER".equals(playerRank)) return rank("YOUTUBE", ChatFormatting.RED);
        if ("STAFF".equals(playerRank)) return rank("STAFF", ChatFormatting.DARK_GREEN);
        if ("ADMIN".equals(playerRank)) return rank("ADMIN", ChatFormatting.RED);
        if ("SUPERSTAR".equals(monthlyPackageRank)) return rank("MVP++", ChatFormatting.GOLD);
        if ("MVP_PLUS".equals(packageRank)) {
            return Component.literal("[MVP").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("+").withStyle(ChatFormatting.RED))
                    .append(Component.literal("]").withStyle(ChatFormatting.AQUA));
        }
        if ("MVP".equals(packageRank)) return rank("MVP", ChatFormatting.AQUA);
        if ("VIP_PLUS".equals(packageRank)) {
            return Component.literal("[VIP").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("+").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("]").withStyle(ChatFormatting.GREEN));
        }
        if ("VIP".equals(packageRank)) return rank("VIP", ChatFormatting.GREEN);
        return null;
    }

    private static Component rank(String name, ChatFormatting color) {
        return Component.literal("[" + name + "]").withStyle(color);
    }

    private static String stripLegacyFormatting(String text) {
        return text == null ? null : plainMessage(text).text();
    }

    static Component replace(Component component) {
        String fullText = component.getString();
        int levelStart = contributorLevelStart(fullText);
        if (levelStart >= 0) return replaceLevelSpan(component, levelStart, fullText.indexOf(']', levelStart) + 1);

        MutableComponent out = replaceOwnText(component);
        for (Component sibling : component.getSiblings()) {
            out.append(replace(sibling));
        }
        return out;
    }

    private static boolean isContributor(String username, UUID profileId) {
        return CONTRIBUTOR_ID.equals(profileId)
                || username != null && CONTRIBUTOR.equalsIgnoreCase(username);
    }

    private static int contributorDisplayNameStart(String raw) {
        int nameStart = raw.indexOf(CONTRIBUTOR);
        while (nameStart >= 0) {
            int nameEnd = nameStart + CONTRIBUTOR.length();
            if (isUsernameToken(raw, nameStart)
                    && hasOnlyDisplayDecorations(raw, 0, nameStart)
                    && hasOnlyTabSuffix(raw, nameEnd)) {
                return nameStart;
            }
            nameStart = raw.indexOf(CONTRIBUTOR, nameEnd);
        }
        return -1;
    }

    private static boolean hasOnlyTabSuffix(String raw, int start) {
        int cursor = start;
        while (cursor < raw.length()) {
            char character = raw.charAt(cursor);
            if (character == '[') {
                int bracketEnd = raw.indexOf(']', cursor + 1);
                if (bracketEnd < 0) return false;
                cursor = bracketEnd + 1;
            } else {
                if (isUsernameCharacter(character)) return false;
                cursor++;
            }
        }
        return true;
    }

    private static Component addMissingRank(Component component, Component rankPrefix, int nameStart) {
        if (!isRankPrefix(rankPrefix)) return component;
        String rank = rankPrefix.getString().trim();
        if (component.getString().contains(rank)) return component;

        MutableComponent insertion = Component.empty().append(rankPrefix.copy());
        String prefixText = rankPrefix.getString();
        if (prefixText.isEmpty() || !Character.isWhitespace(prefixText.charAt(prefixText.length() - 1))) {
            insertion.append(Component.literal(" "));
        }
        return insertAt(component, nameStart, insertion);
    }

    private static boolean isRankPrefix(Component component) {
        if (component == null) return false;
        String text = component.getString().trim();
        if (text.length() < 3 || text.length() > 32
                || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            return false;
        }
        for (int i = 1; i < text.length() - 1; i++) {
            if (Character.isLetter(text.charAt(i))) return true;
        }
        return false;
    }

    private static Component insertAt(Component component, int offset, Component insertion) {
        List<Segment> segments = new ArrayList<>();
        collectSegments(component, segments);

        MutableComponent out = Component.empty().withStyle(component.getStyle());
        int cursor = 0;
        boolean inserted = false;
        for (Segment segment : segments) {
            int segmentStart = cursor;
            int segmentEnd = cursor + segment.text.length();
            if (!inserted && offset <= segmentEnd) {
                appendRange(out, segment, segmentStart, Math.max(segmentStart, offset), segmentStart);
                out.append(insertion.copy());
                appendRange(out, segment, Math.max(segmentStart, offset), segmentEnd, segmentStart);
                inserted = true;
            } else {
                appendRange(out, segment, segmentStart, segmentEnd, segmentStart);
            }
            cursor = segmentEnd;
        }
        if (!inserted) out.append(insertion.copy());
        return out;
    }

    private static boolean hasBadge(Component component) {
        return component.getString().contains("[WP]");
    }

    private static int contributorChatLevelStart(String raw) {
        int firstColon = raw.indexOf(':');
        String sender = " " + CONTRIBUTOR + ":";
        if (firstColon < 0 || !raw.substring(0, firstColon + 1).endsWith(sender)) {
            return -1;
        }
        return numericLevelStart(raw, 0, firstColon - CONTRIBUTOR.length());
    }

    private static boolean hasContributorTabLevel(String raw) {
        int searchStart = 0;
        while (searchStart < raw.length()) {
            int levelStart = numericLevelStart(raw, searchStart, raw.length());
            if (levelStart < 0) return false;
            int levelEnd = raw.indexOf(']', levelStart);
            int nameStart = levelEnd + 2;
            int nameEnd = nameStart + CONTRIBUTOR.length();
            if (levelEnd + 1 < raw.length()
                    && raw.charAt(levelEnd + 1) == ' '
                    && raw.startsWith(CONTRIBUTOR, nameStart)
                    && (nameEnd >= raw.length() || !isUsernameCharacter(raw.charAt(nameEnd)))) {
                return true;
            }
            searchStart = levelEnd + 1;
        }
        return false;
    }

    private static int numericLevelStart(String raw, int startInclusive, int endExclusive) {
        int cursor = raw.indexOf('[', startInclusive);
        while (cursor >= 0 && cursor < endExclusive) {
            int levelEnd = raw.indexOf(']', cursor + 1);
            if (levelEnd < 0 || levelEnd >= endExclusive) return -1;
            if (levelEnd > cursor + 1 && isDigits(raw, cursor + 1, levelEnd)) return cursor;
            cursor = raw.indexOf('[', cursor + 1);
        }
        return -1;
    }

    private static boolean isContributorChatSender(String raw) {
        int nameStart = raw.indexOf(CONTRIBUTOR);
        while (nameStart >= 0) {
            if (isUsernameToken(raw, nameStart)
                    && isChatSenderPrefix(raw, nameStart)
                    && isChatSenderSuffix(raw, nameStart + CONTRIBUTOR.length())) {
                return true;
            }
            nameStart = raw.indexOf(CONTRIBUTOR, nameStart + CONTRIBUTOR.length());
        }
        return false;
    }

    private static boolean isChatSenderPrefix(String raw, int nameStart) {
        int cursor = 0;
        while (cursor < nameStart && Character.isWhitespace(raw.charAt(cursor))) cursor++;
        if (cursor == nameStart) return true;
        if (cursor + 1 == nameStart && raw.charAt(cursor) == '<') return true;
        return hasOnlyDisplayDecorations(raw, cursor, nameStart);
    }

    private static boolean isChatSenderSuffix(String raw, int nameEnd) {
        if (nameEnd >= raw.length()) return false;
        if (raw.charAt(nameEnd) == '>') return true;
        int cursor = nameEnd;
        while (cursor < raw.length() && Character.isWhitespace(raw.charAt(cursor))) cursor++;
        return cursor < raw.length() && raw.charAt(cursor) == ':';
    }

    private static MutableComponent prependBadge(Component component) {
        return Component.empty()
                .append(badge())
                .append(Component.literal(" "))
                .append(component.copy());
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
        while (nameStart >= 0) {
            if (isUsernameToken(raw, nameStart)) {
                int levelStart = levelPrefixStart(raw, nameStart);
                if (levelStart >= 0) return levelStart;
            }
            nameStart = raw.indexOf(CONTRIBUTOR, nameStart + CONTRIBUTOR.length());
        }
        return -1;
    }

    private static boolean isUsernameToken(String raw, int start) {
        int before = start - 1;
        int after = start + CONTRIBUTOR.length();
        return (before < 0 || !isUsernameCharacter(raw.charAt(before)))
                && (after >= raw.length() || !isUsernameCharacter(raw.charAt(after)));
    }

    private static boolean isUsernameCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_';
    }

    private static int levelPrefixStart(String raw, int nameStart) {
        int cursor = 0;
        while (cursor < nameStart && Character.isWhitespace(raw.charAt(cursor))) cursor++;
        if (cursor >= nameStart || raw.charAt(cursor) != '[') return -1;

        int levelEnd = raw.indexOf(']', cursor + 1);
        if (levelEnd < 0 || levelEnd >= nameStart
                || !isDigits(raw, cursor + 1, levelEnd)) return -1;
        return hasOnlyDisplayDecorations(raw, levelEnd + 1, nameStart) ? cursor : -1;
    }

    private static boolean hasOnlyDisplayDecorations(String raw, int start, int end) {
        int cursor = start;
        while (cursor < end) {
            char character = raw.charAt(cursor);
            if (Character.isWhitespace(character)) {
                cursor++;
            } else if (character == '[') {
                int bracketEnd = raw.indexOf(']', cursor + 1);
                if (bracketEnd < 0 || bracketEnd >= end) return false;
                cursor = bracketEnd + 1;
            } else {
                if (isUsernameCharacter(character) || character == ':') return false;
                cursor++;
            }
        }
        return true;
    }

    private static MutableComponent replaceLevelSpan(Component component, int levelStart, int levelEnd) {
        List<Segment> segments = new ArrayList<>();
        collectSegments(component, segments);

        MutableComponent out = Component.empty().withStyle(component.getStyle());
        int cursor = 0;
        boolean badgeAdded = false;
        for (Segment segment : segments) {
            int segmentStart = cursor;
            int segmentEnd = cursor + segment.text.length();
            appendRange(out, segment, segmentStart, Math.min(levelStart, segmentEnd), segmentStart);
            if (!badgeAdded && segmentEnd >= levelStart) {
                out.append(badge());
                badgeAdded = true;
            }
            appendRange(out, segment, Math.max(levelEnd, segmentStart), segmentEnd, segmentStart);
            cursor = segmentEnd;
        }
        return out;
    }

    private static void collectSegments(Component component, List<Segment> segments) {
        if (component.getContents() instanceof PlainTextContents text && !text.text().isEmpty()) {
            segments.add(new Segment(text.text(), component.getStyle()));
        }
        for (Component sibling : component.getSiblings()) {
            collectSegments(sibling, segments);
        }
    }

    private static void appendRange(MutableComponent out, Segment segment, int start, int end, int segmentStart) {
        if (end > start) {
            out.append(Component.literal(segment.text.substring(start - segmentStart, end - segmentStart))
                    .withStyle(segment.style));
        }
    }

    private static boolean isDigits(String raw, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) return false;
        for (int i = startInclusive; i < endExclusive; i++) {
            if (!Character.isDigit(raw.charAt(i))) return false;
        }
        return true;
    }

    private static PlainMessage plainMessage(String raw) {
        StringBuilder plain = new StringBuilder(raw.length());
        int[] sourceIndexes = new int[raw.length() + 1];
        int plainLength = 0;
        for (int sourceIndex = 0; sourceIndex < raw.length(); sourceIndex++) {
            if (isLegacyFormattingCode(raw, sourceIndex)) {
                sourceIndex++;
                continue;
            }
            sourceIndexes[plainLength] = sourceIndex;
            plain.append(raw.charAt(sourceIndex));
            plainLength++;
        }
        sourceIndexes[plainLength] = raw.length();
        int[] trimmedIndexes = new int[plainLength + 1];
        System.arraycopy(sourceIndexes, 0, trimmedIndexes, 0, plainLength + 1);
        return new PlainMessage(plain.toString(), trimmedIndexes);
    }

    private static boolean isLegacyFormattingCode(String raw, int index) {
        if (index + 1 >= raw.length()) return false;
        char prefix = raw.charAt(index);
        if (prefix != '\u00a7' && prefix != '&') return false;
        char code = Character.toLowerCase(raw.charAt(index + 1));
        return code >= '0' && code <= '9'
                || code >= 'a' && code <= 'f'
                || code >= 'k' && code <= 'o'
                || code == 'r'
                || code == 'x';
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

    private record Segment(String text, Style style) {
    }

    private record PlainMessage(String text, int[] sourceIndexes) {
        private int sourceIndex(int plainIndex) {
            return sourceIndexes[plainIndex];
        }
    }
}
