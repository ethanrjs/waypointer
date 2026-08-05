package com.babbur.waypointer.chat;

import com.babbur.waypointer.config.WaypointerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WaypointerContributorBadgeTest {
    private static final UUID BABBUR_ID = UUID.fromString("d0d70e3d-2475-4001-b27e-16b5118e5534");
    private static final UUID SOMEONE_ELSE_ID = UUID.fromString("68b3d8a2-0f62-48a5-a958-4fb0ab1899a2");

    @Test
    void replacesOnlyBabbursNumericChatLevelBadge() {
        WaypointerConfig config = new WaypointerConfig();

        Component replaced = WaypointerContributorBadge.apply(
                Component.literal("[338] Babbur: hi"), config);
        Component ignored = WaypointerContributorBadge.apply(
                Component.literal("[338] SomeoneElse: hi"), config);
        Component ranked = WaypointerContributorBadge.apply(
                Component.literal("[338] [MVP++] Babbur: hi"), config);

        assertEquals("[WP] Babbur: hi", replaced.getString());
        assertEquals("[338] SomeoneElse: hi", ignored.getString());
        assertEquals("[WP] [MVP++] Babbur: hi", ranked.getString());
    }

    @Test
    void disabledSettingLeavesTextAlone() {
        WaypointerConfig config = new WaypointerConfig();
        config.setShowContributorBadges(false);

        Component result = WaypointerContributorBadge.apply(
                Component.literal("[338] Babbur: hi"), config);

        assertEquals("[338] Babbur: hi", result.getString());
    }

    @Test
    void ignoresMentionsAndUsernameSubstrings() {
        WaypointerConfig config = new WaypointerConfig();

        Component mention = WaypointerContributorBadge.apply(
                Component.literal("[338] SomeoneElse: Babbur found a secret"), config);
        Component substring = WaypointerContributorBadge.apply(
                Component.literal("[338] NotBabbur: hi"), config);
        Component suffix = WaypointerContributorBadge.apply(
                Component.literal("[338] BabburTwo: hi"), config);

        assertEquals("[338] SomeoneElse: Babbur found a secret", mention.getString());
        assertEquals("[338] NotBabbur: hi", substring.getString());
        assertEquals("[338] BabburTwo: hi", suffix.getString());
    }
    @Test
    void replacesOnlyNumericLevelInStrictChatHeader() {
        WaypointerConfig config = new WaypointerConfig();

        Component sender = WaypointerContributorBadge.apply(
                Component.literal("[130] Babbur: level [999] is not a badge"), config);
        Component laterMention = WaypointerContributorBadge.apply(
                Component.literal("[130] SomeoneElse: Babbur: hi"), config);
        Component noSpace = WaypointerContributorBadge.apply(
                Component.literal("[130]Babbur: hi"), config);
        Component channel = WaypointerContributorBadge.apply(
                Component.literal("[Guild] [130] [MVP++] Babbur: hi"), config);
        Component bodyBadge = WaypointerContributorBadge.apply(
                Component.literal("[130] Babbur: the marker is [WP]"), config);
        Component emptyLevel = WaypointerContributorBadge.apply(
                Component.literal("[] Babbur: hi"), config);

        assertEquals("[WP] Babbur: level [999] is not a badge", sender.getString());
        assertEquals("[130] SomeoneElse: Babbur: hi", laterMention.getString());
        assertEquals("[130]Babbur: hi", noSpace.getString());
        assertEquals("[Guild] [WP] [MVP++] Babbur: hi", channel.getString());
        assertEquals("[WP] Babbur: the marker is [WP]", bodyBadge.getString());
        assertEquals("[] Babbur: hi", emptyLevel.getString());
    }

    @Test
    void leavesContributorChatWithoutNumericLevelUnchanged() {
        WaypointerConfig config = new WaypointerConfig();

        Component plain = WaypointerContributorBadge.apply(
                Component.literal("Babbur: hi"), config);
        Component ranked = WaypointerContributorBadge.apply(
                Component.literal("[MVP++] Babbur: hi"), config);
        Component vanilla = WaypointerContributorBadge.apply(
                Component.literal("<Babbur> hi"), config);

        assertEquals("Babbur: hi", plain.getString());
        assertEquals("[MVP++] Babbur: hi", ranked.getString());
        assertEquals("<Babbur> hi", vanilla.getString());
    }

    @Test
    void acceptsStandaloneStatusGlyphsInContributorPrefixes() {
        WaypointerConfig config = new WaypointerConfig();

        Component oneGlyph = WaypointerContributorBadge.apply(
                Component.literal("[338] ᛝ [MVP+] Babbur: shork"), config);
        Component multipleGlyphs = WaypointerContributorBadge.apply(
                Component.literal("[42] ᛝ ᛝ [VIP] Babbur: hi"), config);

        assertEquals("[WP] ᛝ [MVP+] Babbur: shork", oneGlyph.getString());
        assertEquals("[WP] ᛝ ᛝ [VIP] Babbur: hi", multipleGlyphs.getString());
    }

    @Test
    void playerNameUsesProfileIdentityAndDoesNotDuplicateTheBadge() {
        WaypointerConfig config = new WaypointerConfig();

        Component rankOnly = WaypointerContributorBadge.applyPlayerName(
                Component.literal("[MVP++] Babbur"), "Babbur", SOMEONE_ELSE_ID, config);
        Component withLevel = WaypointerContributorBadge.applyPlayerName(
                Component.literal("[338] Babbur"), "Babbur", SOMEONE_ELSE_ID, config);
        Component alreadyBadged = WaypointerContributorBadge.applyPlayerName(
                Component.literal("[WP] Babbur"), "Babbur", SOMEONE_ELSE_ID, config);
        Component wrongProfile = WaypointerContributorBadge.applyPlayerName(
                Component.literal("Babbur"), "SomeoneElse", SOMEONE_ELSE_ID, config);

        assertEquals("[WP] [MVP++] Babbur", rankOnly.getString());
        assertEquals("[WP] Babbur", withLevel.getString());
        assertEquals("[WP] Babbur", alreadyBadged.getString());
        assertEquals("Babbur", wrongProfile.getString());
    }

    @Test
    void playerNameUsesStableContributorUuidWhenProfileNameIsMissingOrDifferent() {
        WaypointerConfig config = new WaypointerConfig();

        Component missingName = WaypointerContributorBadge.applyPlayerName(
                Component.literal("[338] Babbur"), "", BABBUR_ID, config);
        Component differentName = WaypointerContributorBadge.applyPlayerName(
                Component.literal("[MVP+] Babbur"), "HypixelAlias", BABBUR_ID, config);
        Component nonContributor = WaypointerContributorBadge.applyPlayerName(
                Component.literal("[MVP+] Babbur"), "HypixelAlias", SOMEONE_ELSE_ID, config);

        assertEquals("[WP] Babbur", missingName.getString());
        assertEquals("[WP] [MVP+] Babbur", differentName.getString());
        assertEquals("[MVP+] Babbur", nonContributor.getString());
    }
    @Test
    void tabNameUsesStrictContributorLevelLabel() {
        WaypointerConfig config = new WaypointerConfig();

        Component result = WaypointerContributorBadge.applyTabName(
                Component.literal("[338] Babbur: $"), config);

        assertEquals("[WP] Babbur", result.getString());
        assertEquals(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY).getStyle().getColor(),
                result.getStyle().getColor());
        assertEquals(Component.literal("WP").withStyle(ChatFormatting.DARK_RED).getStyle().getColor(),
                result.getSiblings().get(0).getStyle().getColor());
        assertEquals(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY).getStyle().getColor(),
                result.getSiblings().get(1).getStyle().getColor());
        assertEquals(Component.literal("Babbur").withStyle(ChatFormatting.AQUA).getStyle().getColor(),
                result.getSiblings().get(3).getStyle().getColor());
    }

    @Test
    void tabNameRequiresDirectNumericLevelBeforeBabbur() {
        WaypointerConfig config = new WaypointerConfig();

        Component ranked = WaypointerContributorBadge.applyTabName(
                Component.literal("[338] [MVP++] Babbur: $"), config);
        Component header = WaypointerContributorBadge.applyTabName(
                Component.literal("Coop with Babbur (2)"), config);
        Component otherPlayer = WaypointerContributorBadge.applyTabName(
                Component.literal("[338] BabburTwo: $"), config);
        Component styled = WaypointerContributorBadge.applyTabName(
                Component.literal("[130] ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("Babbur").withStyle(ChatFormatting.BLUE)), config);

        assertEquals("[338] [MVP++] Babbur: $", ranked.getString());
        assertEquals("Coop with Babbur (2)", header.getString());
        assertEquals("[338] BabburTwo: $", otherPlayer.getString());
        assertEquals("[WP] Babbur", styled.getString());
    }

    @Test
    void stripsLegacyFormattingBeforeMatchingChatAndTab() {
        WaypointerConfig config = new WaypointerConfig();
        Component chat = WaypointerContributorBadge.apply(
                Component.literal("\u00a77[\u00a7d130\u00a77] \u00a7bBabbur\u00a7f: hi"), config);
        Component ampersandChat = WaypointerContributorBadge.apply(
                Component.literal("&7[&d130&7] &bBabbur&f: hi"), config);
        Component tab = WaypointerContributorBadge.applyTabName(
                Component.literal("\u00a77[\u00a7d130\u00a77] \u00a7bBabbur"), config);

        assertEquals("\u00a77[WP] \u00a7bBabbur\u00a7f: hi", chat.getString());
        assertEquals("&7[WP] &bBabbur&f: hi", ampersandChat.getString());
        assertEquals("[WP] Babbur", tab.getString());
    }

    @Test
    void buildsHypixelRankPrefixUsingServerPriority() {
        Component custom = WaypointerContributorBadge.hypixelRankPrefix(
                "NORMAL", "MVP_PLUS", "SUPERSTAR", "\u00a7c[OWNER]");
        Component staff = WaypointerContributorBadge.hypixelRankPrefix(
                "YOUTUBER", "MVP_PLUS", "SUPERSTAR", null);
        Component monthly = WaypointerContributorBadge.hypixelRankPrefix(
                "NORMAL", "MVP_PLUS", "SUPERSTAR", null);
        Component packageRank = WaypointerContributorBadge.hypixelRankPrefix(
                "NORMAL", "MVP_PLUS", "NONE", null);
        Component noRank = WaypointerContributorBadge.hypixelRankPrefix(
                "NORMAL", "NONE", "NONE", null);

        assertEquals("[OWNER]", custom.getString());
        assertEquals("[YOUTUBE]", staff.getString());
        assertEquals("[MVP++]", monthly.getString());
        assertEquals("[MVP+]", packageRank.getString());
        assertNull(noRank);
    }

    @Test
    void preservesExistingColorsOutsideLevelBadge() {
        WaypointerConfig config = new WaypointerConfig();
        Component message = Component.literal("[338] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Babbur").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": hi").withStyle(ChatFormatting.GREEN));

        Component result = WaypointerContributorBadge.apply(message, config);

        assertEquals("[WP] Babbur: hi", result.getString());
        List<Component> siblings = result.getSiblings();
        assertEquals(Component.literal(" ").withStyle(ChatFormatting.GOLD).getStyle().getColor(),
                siblings.get(1).getStyle().getColor());
        assertEquals(Component.literal("Babbur").withStyle(ChatFormatting.AQUA).getStyle().getColor(),
                siblings.get(2).getStyle().getColor());
        assertEquals(Component.literal(": hi").withStyle(ChatFormatting.GREEN).getStyle().getColor(),
                siblings.get(3).getStyle().getColor());
    }
}
