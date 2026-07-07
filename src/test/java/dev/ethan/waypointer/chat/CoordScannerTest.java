package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
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
        // "x, y z" or "x y, z" must both work -- chat is messy.
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
        // Middle value > MAX_Y is the cheapest way to catch leaderboard-style triples
        // where the second number is a score rather than an altitude.
        assertTrue(CoordScanner.scan("Score: 5000 9999 250").isEmpty(),
                "y=9999 must be rejected as out of Minecraft Y range");
        assertTrue(CoordScanner.scan("Players online: 50 500 75").isEmpty(),
                "y=500 must be rejected as out of Minecraft Y range");
    }

    @Test
    void rejects_horizontal_magnitudes_too_large() {
        // 50,000 blocks out is well beyond Skyblock's coordinate space; almost
        // certainly something like a leaderboard score line.
        assertTrue(CoordScanner.scan("Top players: 50000 50 60000").isEmpty());
    }

    @Test
    void does_not_match_inside_decimals_or_versions() {
        assertTrue(CoordScanner.scan("Server v1.21.11 running").isEmpty());
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
        // Regression for issue #3: bank interest messages like
        // "You have just received 1,145,926 coins" were being matched as (1, 145, 926).
        assertTrue(
                CoordScanner.scan("You have just received 1,145,926 coins as interest in your co-op bank account!").isEmpty(),
                "bank-interest coin counts must not be decorated as coordinates");
        assertTrue(CoordScanner.scan("Sold items for 12,345,678 coins").isEmpty());
        assertTrue(CoordScanner.scan("Jackpot: 1,000,000 coins!").isEmpty());
    }

    @Test
    void still_accepts_comma_space_coords_near_thousands_shape() {
        // Comma+space separators must keep working even though the numeric shape
        // (three groups of three digits) overlaps with thousands-separated numbers.
        List<CoordScanner.Coord> coords = CoordScanner.scan("Meet at 100, 145, 926");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 145, 926), coords.get(0));
    }

    @Test
    void rejects_thousands_separated_number_mixed_in_sentence() {
        // Even when surrounded by other text, the bare-comma thousands shape
        // should not leak a coord chip into the chat line.
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
        // Players sometimes type "x : 100 , y : 64 , z : -200" -- the colon and
        // comma whitespace must be tolerant in both directions.
        List<CoordScanner.Coord> coords = CoordScanner.scan("x : 100 , y : 64 , z : -200");
        assertEquals(1, coords.size());
        assertEquals(new CoordScanner.Coord(100, 64, -200), coords.get(0));
    }

    @Test
    void labeled_format_returns_match_spanning_full_callout() {
        // The detector recolors the matched range -- it must cover the entire
        // "x: ..., z: ..." span so the click chip wraps the whole callout, not
        // just the trailing number.
        List<CoordScanner.Match> matches = CoordScanner.scanWithPositions("see x: 100, y: 64, z: -200 ok");
        assertEquals(1, matches.size());
        CoordScanner.Match match = matches.get(0);
        assertEquals(4, match.start(), "match should start at the leading 'x'");
        assertEquals(26, match.end(), "match should end after the final '0' of -200");
    }

    @Test
    void labeled_format_rejects_decimals() {
        // Matches the bare scanner: integers only. The trailing lookahead rejects
        // any axis whose number is followed by '.', and decimals on x/y are
        // rejected because the inner '.' breaks the required ',' separator.
        assertTrue(CoordScanner.scan("x: 100.5, y: 64, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, y: 64.0, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, y: 64, z: -200.5").isEmpty());
    }

    @Test
    void labeled_format_rejects_thousands_separated_x_value() {
        // Regression for issue #3: even with axis labels, a thousands-separated
        // number must not be coerced into a coord (the user meant x=1000, but
        // we'd rather miss the callout than parse it wrong).
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
        // "Trax: 100..." -- the lookbehind must keep the regex from latching
        // onto arbitrary words ending in x/y/z.
        assertTrue(CoordScanner.scan("Trax: 100, y: 64, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("max: 100, y: 64, z: -200").isEmpty());
    }

    @Test
    void labeled_format_requires_correct_axis_order() {
        // Reordered or duplicated labels are not the canonical callout shape;
        // staying strict avoids creative false positives from build-mode chat.
        assertTrue(CoordScanner.scan("y: 64, x: 100, z: -200").isEmpty());
        assertTrue(CoordScanner.scan("x: 100, x: 64, x: -200").isEmpty());
    }

    @Test
    void labeled_and_bare_formats_coexist_in_one_message() {
        // A player echoing both styles in the same line should produce two chips
        // rendered in chat order.
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
    void chatCoordHighlightAddsExplicitBlockActionAfterCoords() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
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
    /*[[AI-FN-DOC
Function:
autoAddChatTempWaypointsSuppressesDuplicateMessageDelivery
Purpose:
Verify that receiving the exact same auto-add chat coordinate message twice creates only one temporary waypoint.
Why this exists:
Minecraft/chat hooks can deliver duplicate message events, and auto-add should not spam duplicate temp markers for the same chat line.
When to use:
Run when changing ChatCoordDetector duplicate suppression, temp waypoint creation, or temp group lookup behavior. Do not use it to test distinct-message behavior.
Inputs:
No parameters. The test creates config, manager, detector, and one literal chat component containing coordinates.
Outputs:
No return value. Assertions require the current temp group to contain exactly one waypoint after the duplicate deliveries.
Side effects:
Mutates an in-memory ActiveGroupManager by invoking the detector. Does not write files, network, or real chat state.
Failure modes:
If duplicate suppression regresses or temp bucket lookup changes incorrectly, the temp group size will be greater than one.
Important invariants:
The temp bucket is per-zone and retrieved with getOrCreateTempGroup. The same Component/message content should be treated as duplicate delivery.
Internal logic:
Enable auto-add, create detector, invoke it twice with the same message, then assert the temp bucket size is one.
Pseudocode:
Create config and enable auto-add.
Create manager and detector.
Create one coordinate chat message.
Invoke detector with message.
Invoke detector with the same message again.
Assert manager.getOrCreateTempGroup().size is 1.
Implementation notes:
The assertion uses the no-arg temp bucket because source names are waypoint metadata, not group selectors.
AI self-check:
Verify the test uses identical message input, checks the manager state after both invokes, and avoids undocumented callback functions.
]]*/
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
    /*[[AI-FN-DOC
Function:
autoAddChatTempWaypointsKeepsDifferentChatLinesWithSameCoords
Purpose:
Verify that different chat messages with the same coordinates still create separate temporary waypoints.
Why this exists:
Duplicate suppression should target repeated delivery of the same chat line, not collapse distinct user messages that happen to share coordinates.
When to use:
Run when changing ChatCoordDetector duplicate keys, auto-add behavior, or temp group lookup. Do not use it to test exact duplicate suppression.
Inputs:
No parameters. The test creates config, manager, detector, and two literal coordinate chat components with different text.
Outputs:
No return value. Assertions require the current temp group to contain two waypoints.
Side effects:
Mutates an in-memory ActiveGroupManager by invoking the detector twice. Does not write files, network, or real chat state.
Failure modes:
If duplicate suppression keys only on coordinates or temp bucket lookup breaks, the temp group size will not be two.
Important invariants:
Messages with distinct text are distinct auto-add events even when x/y/z match. The temp group remains per-zone, not per-source.
Internal logic:
Enable auto-add, invoke the detector with two distinct messages sharing coordinates, and assert the temp bucket size is two.
Pseudocode:
Create config and enable auto-add.
Create manager and detector.
Invoke detector with first coordinate message.
Invoke detector with second distinct coordinate message using the same coordinates.
Assert manager.getOrCreateTempGroup().size is 2.
Implementation notes:
Using the no-arg temp bucket reflects the cleaned-up manager API; source/message text affects waypoint metadata and duplicate keys, not bucket selection.
AI self-check:
Verify the two messages differ, coordinates match, the final size assertion targets the temp bucket, and no undocumented callbacks are introduced.
]]*/
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
