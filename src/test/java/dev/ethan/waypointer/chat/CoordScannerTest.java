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
}
