package dev.ethan.waypointer.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
