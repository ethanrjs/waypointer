package com.babbur.waypointer.update;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class UpdateNotificationMessage {

    static final URI MODRINTH_VERSIONS =
            URI.create("https://modrinth.com/mod/waypointer/versions");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("MMM d, uuuu", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private UpdateNotificationMessage() {
    }

    static Component create(AvailableUpdate update) {
        Component hover = hoverDetails(update);
        Component detailsLink = Component.translatable("waypointer.update.hover_for_details")
                .withStyle(Style.EMPTY
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(MODRINTH_VERSIONS))
                        .withHoverEvent(new HoverEvent.ShowText(hover)));

        return Component.empty()
                .append(Component.literal("[Waypointer] ").withStyle(ChatFormatting.AQUA))
                .append(Component.translatable("waypointer.update.available")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(detailsLink);
    }

    private static Component hoverDetails(AvailableUpdate update) {
        MutableComponent hover = Component.empty()
                .append(versionLine(
                        "waypointer.update.your_version",
                        update.currentVersion(),
                        DATE_FORMAT.format(update.currentPublishedAt()),
                        ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(versionLine(
                        "waypointer.update.latest_version",
                        update.latestVersion(),
                        DATE_FORMAT.format(update.latestPublishedAt()),
                        ChatFormatting.YELLOW))
                .append(Component.literal("\n\n"))
                .append(Component.translatable(
                                "waypointer.update.versions_behind",
                                Component.literal(Integer.toString(update.versionsBehind()))
                                        .withStyle(ChatFormatting.YELLOW))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.translatable("waypointer.update.open_modrinth")
                        .withStyle(ChatFormatting.GREEN));
        return hover;
    }

    private static Component versionLine(
            String key, String version, String date, ChatFormatting dateColor) {
        Component dateWithParentheses = Component.literal("(")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(date).withStyle(dateColor))
                .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
        return Component.translatable(
                        key,
                        Component.literal(version).withStyle(ChatFormatting.AQUA),
                        dateWithParentheses)
                .withStyle(ChatFormatting.GRAY);
    }
}
