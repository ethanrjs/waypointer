package com.babbur.waypointer.chat;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CoordScannerTest {

    @Test
    void parses_basic_space_separated_triple() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Meet me at 100 64 -200 by the spawn");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 64, -200), coords.get(0));
    }

    @Test
    void parses_comma_separated_triple() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("[Coords] -42, 80, 17");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(-42, 80, 17), coords.get(0));
    }

    @Test
    void parses_mixed_separators() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Boss spawns at 250, 70 -310 every minute");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(250, 70, -310), coords.get(0));
    }

    @Test
    void parses_explicit_slash_separated_triple() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Boss spawns at 250/70/-310 every minute");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(250, 70, -310), coords.get(0));
    }

    @Test
    void rejects_fraction_like_mixed_slash_separator() {
        assertTrue(CoordScanner.scan("3 99/100").isEmpty(),
                "progress/fraction text must not be detected as a coordinate");
        assertTrue(CoordScanner.scan("3, 99/100").isEmpty());
        assertTrue(CoordScanner.scan("3/99 100").isEmpty());
    }

    @Test
    void rejects_y_out_of_minecraft_range() {
        assertTrue(CoordScanner.scan("Score: 5000 9999 250").isEmpty(),
                "y=9999 must be rejected as out of Minecraft Y range");
        assertTrue(CoordScanner.scan("Players online: 50 500 75").isEmpty(),
                "y=500 must be rejected as out of Minecraft Y range");
    }

    @Test
    void rejects_horizontal_magnitudes_too_large() {
        assertTrue(CoordScanner.scan("Top players: 50000 50 60000").isEmpty());
    }

    @Test
    void does_not_match_inside_decimals_or_versions() {
        assertTrue(CoordScanner.scan("Server v26.1.2 running").isEmpty());
        assertTrue(CoordScanner.scan("Latency 12.5 14.3 11.1 ms").isEmpty());
    }

    @Test
    void caps_matches_per_message() {
        StringBuilder spam = new StringBuilder();
        for (int i = 0; i < 20; i++) spam.append("at ").append(i).append(" 70 ").append(i + 1).append(" ");

        List<CoordScanner.Coord> coords = CoordScanner.scan(spam.toString());

        assertEquals(CoordScanner.MAX_MATCHES_PER_MESSAGE, coords.size(),
                "Scanner must cap chips per message to keep chat readable");
    }

    @Test
    void empty_or_null_input_is_safe() {
        assertTrue(CoordScanner.scan("").isEmpty());
        assertTrue(CoordScanner.scan(null).isEmpty());
    }

    @Test
    void allows_multiple_coords_when_legitimate() {
        List<CoordScanner.Coord> coords = CoordScanner.scan(
                "Route: 10 70 20 -> 30 70 40 -> 50 70 60");
        assertEquals(3, coords.size());
        assertEquals(new CoordScanner.Coord(10, 70, 20),  coords.get(0));
        assertEquals(new CoordScanner.Coord(30, 70, 40),  coords.get(1));
        assertEquals(new CoordScanner.Coord(50, 70, 60),  coords.get(2));
    }

    @Test
    void supports_negative_world_coordinates() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Found at -1234, 12, -5678");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(-1234, 12, -5678), coords.get(0));
    }

    @Test
    void rejects_thousands_separated_coin_amounts() {
        assertTrue(
                CoordScanner.scan("You have just received 1,145,926 coins as interest in your co-op bank account!").isEmpty(),
                "bank-interest coin counts must not be decorated as coordinates");
        assertTrue(CoordScanner.scan("Sold items for 12,345,678 coins").isEmpty());
        assertTrue(CoordScanner.scan("Jackpot: 1,000,000 coins!").isEmpty());
    }

    @Test
    void still_accepts_comma_space_coords_near_thousands_shape() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Meet at 100, 145, 926");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 145, 926), coords.get(0));
    }

    @Test
    void rejects_thousands_separated_number_mixed_in_sentence() {
        assertTrue(CoordScanner.scan("Cleared dungeon, earned 2,500,400 xp today").isEmpty());
    }

    @Test
    void parses_labeled_format_with_spaces() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Meet me at x: 100, y: 64, z: -200 by spawn");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 64, -200), coords.get(0));
    }

    @Test
    void parses_labeled_format_uppercase_no_spaces() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("Boss at X:100,Y:64,Z:-200 right now");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 64, -200), coords.get(0));
    }

    @Test
    void parses_labeled_format_negative_values() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("warp here -- x: -1234, y: 12, z: -5678");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(-1234, 12, -5678), coords.get(0));
    }

    @Test
    void parses_labeled_format_with_extra_padding_around_punctuation() {
        List<CoordScanner.Coord> coords = CoordScanner.scan("x : 100 , y : 64 , z : -200");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 64, -200), coords.get(0));
    }

    @Test
    void labeled_format_returns_match_spanning_full_callout() {
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions("see x: 100, y: 64, z: -200 ok");
        assertEquals(1, matches.size());
        CoordScanner.Match match = matches.get(0);
        assertEquals(4, match.start(), "match should start at the leading 'x'");
        assertEquals(26, match.end(), "match should end after the final '0' of -200");
    }

    @Test
    void labeled_format_rejects_decimals() {
        assertTrue(CoordScanner.scan("x: 100.5, y: 64, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, y: 64.0, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, y: 64, z: -200.5").isEmpty());
    }

    @Test
    void labeled_format_rejects_thousands_separated_x_value() {
        assertTrue(CoordScanner.scan("x: 1,000, y: 64, z: 0").isEmpty());
    }

    @Test
    void labeled_format_rejects_y_out_of_minecraft_range() {
        assertTrue(CoordScanner.scan("x: 100, y: 9999, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, y: -500, z: -200").isEmpty());
    }

    @Test
    void labeled_format_rejects_horizontal_magnitudes_too_large() {
        assertTrue(CoordScanner.scan("x: 50000, y: 64, z: 60000").isEmpty());
    }

    @Test
    void labeled_format_does_not_match_when_axis_letter_is_inside_a_word() {
        assertTrue(CoordScanner.scan("Trax: 100, y: 64, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("max: 100, y: 64, z: -200").isEmpty());
    }

    @Test
    void labeled_format_requires_correct_axis_order() {
        assertTrue(CoordScanner.scan("y: 64, x: 100, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, x: 64, x: -200").isEmpty());
    }

    @Test
    void labeled_and_bare_formats_coexist_in_one_message() {
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions(
                "x: 100, y: 64, z: -200 vs the older 250, 70, -310 callout");
        assertEquals(2, matches.size());
        assertEquals(new CoordScanner.Coord(100, 64, -200), matches.get(0).coord());
        assertEquals(new CoordScanner.Coord(250, 70, -310), matches.get(1).coord());
        assertTrue(matches.get(0).start() < matches.get(1).start(),
                "matches must be returned in left-to-right chat order");
    }

    @Test
    void chatTempSenderNameUsesLastUsernameBeforeColon() {
        String message = "[334] MVP++ Babbur: x: 1, y: 1, z: 1";
        int coordStart = message.indexOf("x:");

        assertEquals("Babbur", ChatCoordDetector.senderNameForChatTemp(message, coordStart));
    }

    @Test
    void chatTempSenderNameReturnsEmptyWithoutChatColon() {
        String message = "Warp to x: 1, y: 1, z: 1";
        int coordStart = message.indexOf("x:");

        assertEquals("", ChatCoordDetector.senderNameForChatTemp(message, coordStart));
    }

    @Test
    void chatTempSenderNameIgnoresAxisColonsWhenBareCoordsFollowLabeled() {
        String message = "Player: x: 100, y: 64, z: -200 also 50 70 100";
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions(message);
        assertEquals(2, matches.size());
        int bareStart = matches.get(1).start();

        assertEquals("Player", ChatCoordDetector.senderNameForChatTemp(message, bareStart));
    }

    @Test
    void chatTempSenderLabelUsesRankAndUsernameStylesWithoutDependingOnEmblem() {
        MutableComponent message = Component.empty()
                .append(Component.literal("[CHAT] [334] \u16DD ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[MVP++] ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Babbur").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("1 2 3"));
        String flat = message.getString();

        assertEquals(ChatFormatting.YELLOW + "From "
                        + ChatFormatting.GOLD + "[MVP++] "
                        + ChatFormatting.LIGHT_PURPLE + "Babbur",
                ChatCoordDetector.senderLabelForChatTemp(message, flat, flat.indexOf("1 2 3")));
    }

    @Test
    void chatTempSenderLabelKeepsRawFormattingCodeBeforeRankPrefix() {
        Component message = Component.literal(
                "[CHAT] [313] \u2600 \u00A76[MVP\u00A7d++\u00A76] pushhsuq\u00A7f: x: -592, y: 113, z: 6");
        String flat = message.getString();

        assertEquals(ChatFormatting.YELLOW + "From "
                        + "\u00A76[MVP\u00A7d++\u00A76] pushhsuq\u00A7f",
                ChatCoordDetector.senderLabelForChatTemp(message, flat, flat.indexOf("x:")));
    }

    @Test
    void chatTempSenderLabelFallsBackToUsernameWhenNoRankIsPresent() {
        MutableComponent message = Component.empty()
                .append(Component.literal("[CHAT] [334] \u2736 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Babbur").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("1 2 3"));
        String flat = message.getString();

        assertEquals(ChatFormatting.YELLOW + "From "
                        + ChatFormatting.LIGHT_PURPLE + "Babbur",
                ChatCoordDetector.senderLabelForChatTemp(message, flat, flat.indexOf("1 2 3")));
    }

    @Test
    void chatCoordBlockButtonIsOffByDefaultAndCanBeDisabled() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
        ChatCoordDetector detector = new ChatCoordDetector(config, new ActiveGroupManager());
        Component message = Component.literal("Babbur: x: 1, y: 2, z: 3");
        assertFalse(config.showChatCoordBlockButton());
        Component out = invokeCoordDetector(detector, message);
        assertEquals(message.getString(), out.getString());
        assertTrue(runs(out).stream().anyMatch(run ->
                run.style().getClickEvent() instanceof ClickEvent.RunCommand command
                        && command.command().startsWith("/waypointer chattemp ")));
        config.setShowChatCoordBlockButton(true);
        assertTrue(invokeCoordDetector(detector, message).getString().endsWith(" [Block]"));
        config.setShowChatCoordBlockButton(false);
        assertEquals(message.getString(), invokeCoordDetector(detector, message).getString());
    }

    @Test
    void chatCoordHighlightAddsExplicitBlockActionAfterCoords() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
        config.setShowChatCoordBlockButton(true);
        config.setAutoAddChatTempWaypoints(false);
        Component out = invokeCoordDetector(
                Component.literal("[CHAT] [334] [MVP++] Babbur: x: 1, y: 2, z: 3"),
                config);

        assertEquals("[CHAT] [334] [MVP++] Babbur: x: 1, y: 2, z: 3 [Block]", out.getString());

        StyledRun blockRun = runs(out).stream()
                .filter(run -> run.text().equals(" [Block]"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing block action run"));
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), blockRun.style().getColor());
        assertFalse(blockRun.style().isBold());
        ClickEvent clickEvent = blockRun.style().getClickEvent();
        ClickEvent.RunCommand runCommand = assertInstanceOf(ClickEvent.RunCommand.class, clickEvent);
        assertEquals("/waypointer blacklist add Babbur", runCommand.command());
        assertNotNull(blockRun.style().getHoverEvent());
    }

    @Test
    void autoAddChatTempWaypointsSuppressesDuplicateMessageDelivery() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
        config.setAutoAddChatTempWaypoints(true);
        ActiveGroupManager manager = new ActiveGroupManager();
        ChatCoordDetector detector = new ChatCoordDetector(config, manager);
        Component message = Component.literal("[CHAT] [334] [MVP++] Babbur: x: 1, y: 2, z: 3");

        invokeCoordDetector(detector, message);
        invokeCoordDetector(detector, message);

        assertEquals(1, manager.getOrCreateTempGroup().size(),
                "replayed identical chat delivery should not create duplicate temp waypoints");
    }

    @Test
    void autoAddChatTempWaypointsKeepsDifferentChatLinesWithSameCoords() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
        config.setAutoAddChatTempWaypoints(true);
        ActiveGroupManager manager = new ActiveGroupManager();
        ChatCoordDetector detector = new ChatCoordDetector(config, manager);

        invokeCoordDetector(detector,
                Component.literal("[CHAT] [334] [MVP++] Babbur: x: 1, y: 2, z: 3"));
        invokeCoordDetector(detector,
                Component.literal("[CHAT] [334] [MVP++] Babbur: still x: 1, y: 2, z: 3"));

        assertEquals(2, manager.getOrCreateTempGroup().size(),
                "a later distinct chat line with the same coordinates should remain actionable");
    }

    private static Component invokeCoordDetector(Component message, WaypointerConfig config) throws Exception {
        ChatCoordDetector detector = new ChatCoordDetector(config, new ActiveGroupManager());
        return invokeCoordDetector(detector, message);
    }

    private static Component invokeCoordDetector(ChatCoordDetector detector, Component message)
            throws Exception {
        Method onMessage = ChatCoordDetector.class.getDeclaredMethod("onMessage", Component.class, boolean.class);
        onMessage.setAccessible(true);
        return (Component) onMessage.invoke(detector, message, false);
    }

    private static List<StyledRun> runs(Component component) {
        List<StyledRun> runs = new ArrayList<>();
        component.visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
            if (!text.isEmpty()) runs.add(new StyledRun(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    private record StyledRun(String text, Style style) {
    }
}
