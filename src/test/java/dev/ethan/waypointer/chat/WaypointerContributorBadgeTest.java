package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointerContributorBadgeTest {
    @Test
    void replacesOnlyBabbursNumericLevelBadge() {
        WaypointerConfig config = new WaypointerConfig();

        Component replaced = WaypointerContributorBadge.apply(
                Component.literal("[338] Babbur"), config);
        Component ignored = WaypointerContributorBadge.apply(
                Component.literal("[338] SomeoneElse"), config);
        Component ranked = WaypointerContributorBadge.apply(
                Component.literal("[338] [MVP++] Babbur: hi"), config);

        assertEquals("[WP] Babbur", replaced.getString());
        assertEquals("[338] SomeoneElse", ignored.getString());
        assertEquals("[WP] [MVP++] Babbur: hi", ranked.getString());
    }

    @Test
    void disabledSettingLeavesTextAlone() {
        WaypointerConfig config = new WaypointerConfig();
        config.setShowContributorBadges(false);

        Component result = WaypointerContributorBadge.apply(
                Component.literal("[338] Babbur"), config);

        assertEquals("[338] Babbur", result.getString());
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
