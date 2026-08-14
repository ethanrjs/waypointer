package com.babbur.waypointer.update;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNotificationMessageTest {

    @Test
    void detailsTextIsUnderlinedAndOpensTheModrinthVersionsPage() {
        Component message = UpdateNotificationMessage.create(update());
        assertEquals(4, message.getSiblings().size());

        Component details = message.getSiblings().get(3);
        assertTrue(details.getStyle().isUnderlined());
        ClickEvent.OpenUrl openUrl = assertInstanceOf(
                ClickEvent.OpenUrl.class, details.getStyle().getClickEvent());
        assertEquals(UpdateNotificationMessage.MODRINTH_VERSIONS, openUrl.uri());

        HoverEvent.ShowText showText = assertInstanceOf(
                HoverEvent.ShowText.class, details.getStyle().getHoverEvent());
        assertNotNull(showText.value());
    }

    @Test
    void hoverUsesReleaseValuesAndRequestedColors() {
        Component message = UpdateNotificationMessage.create(update());
        HoverEvent.ShowText showText = assertInstanceOf(
                HoverEvent.ShowText.class,
                message.getSiblings().get(3).getStyle().getHoverEvent());
        Component hover = showText.value();

        Component currentLine = hover.getSiblings().get(0);
        TranslatableContents currentText = assertInstanceOf(
                TranslatableContents.class, currentLine.getContents());
        assertEquals("waypointer.update.your_version", currentText.getKey());
        assertEquals("1.8.7", ((Component) currentText.getArgs()[0]).getString());
        assertEquals("(Aug 10, 2026)", ((Component) currentText.getArgs()[1]).getString());

        Component latestLine = hover.getSiblings().get(2);
        TranslatableContents latestText = assertInstanceOf(
                TranslatableContents.class, latestLine.getContents());
        assertEquals("waypointer.update.latest_version", latestText.getKey());
        assertEquals("1.9.0", ((Component) latestText.getArgs()[0]).getString());
        Component latestDate = (Component) latestText.getArgs()[1];
        assertEquals("(Dec 19, 2026)", latestDate.getString());
        assertEquals(color(ChatFormatting.YELLOW),
                latestDate.getSiblings().get(0).getStyle().getColor());

        Component behindLine = hover.getSiblings().get(4);
        TranslatableContents behindText = assertInstanceOf(
                TranslatableContents.class, behindLine.getContents());
        assertEquals("21", ((Component) behindText.getArgs()[0]).getString());
        assertEquals(color(ChatFormatting.GREEN),
                hover.getSiblings().get(6).getStyle().getColor());
    }

    private static TextColor color(ChatFormatting formatting) {
        return TextColor.fromLegacyFormat(formatting);
    }

    private static AvailableUpdate update() {
        return new AvailableUpdate(
                "1.8.7",
                Instant.parse("2026-08-10T00:00:33Z"),
                "1.9.0",
                Instant.parse("2026-12-19T12:00:00Z"),
                21);
    }
}
