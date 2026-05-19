package dev.ethan.waypointer.diana;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DianaRareMobShareParserTest {

    @Test
    void parsesSkyHanniLabeledRareMobShare() {
        var parsed = DianaRareMobShareParser.parse(
                "\u00A79Party \u00A78> \u00A7b[MVP\u00A79+\u00A7b] _088\u00A7f: \u00A7rx: 86, y: 73, z: -29 I dug up an inquisitor come over here!");

        assertTrue(parsed.isPresent());
        assertEquals("_088", parsed.get().playerName());
        assertEquals("Minos Inquisitor", parsed.get().mobName());
        assertEquals(86, parsed.get().x());
        assertEquals(73, parsed.get().y());
        assertEquals(-29, parsed.get().z());
    }

    @Test
    void parsesSkyHanniMobNameSuffix() {
        var parsed = DianaRareMobShareParser.parse(
                "\u00A79Party \u00A78> \u00A76[MVP\u00A70++\u00A76] scaryron\u00A7f: \u00A7rx: -67, y: 75, z: 116 | Minos Inquisitor spawned at [ Mountain ]!");

        assertTrue(parsed.isPresent());
        assertEquals("scaryron", parsed.get().playerName());
        assertEquals("Minos Inquisitor", parsed.get().mobName());
        assertEquals(-67, parsed.get().x());
        assertEquals(75, parsed.get().y());
        assertEquals(116, parsed.get().z());
    }

    @Test
    void parsesInquisitorSentenceShare() {
        var parsed = DianaRareMobShareParser.parse(
                "\u00A79Party \u00A78> UserName\u00A7f: \u00A7rA MINOS INQUISITOR has spawned near [Foraging Island ] at Coords 1 2 -3");

        assertTrue(parsed.isPresent());
        assertEquals("UserName", parsed.get().playerName());
        assertEquals("Minos Inquisitor", parsed.get().mobName());
        assertEquals(1, parsed.get().x());
        assertEquals(2, parsed.get().y());
        assertEquals(-3, parsed.get().z());
    }

    @Test
    void normalizesKnownDianaMobNames() {
        var parsed = DianaRareMobShareParser.parse(
                "Party > Babbur: x: -10, y: 77, z: 42 | Siamese Lynxes spawned nearby!");

        assertTrue(parsed.isPresent());
        assertEquals("Siamese Lynx", parsed.get().mobName());
    }

    @Test
    void parsesSboRareMobAliases() {
        var parsed = DianaRareMobShareParser.parse(
                "Party > Babbur: x: -10, y: 77, z: 42 | inq");

        assertTrue(parsed.isPresent());
        assertEquals("Minos Inquisitor", parsed.get().mobName());
    }

    @Test
    void ignoresGenericCoordinateMessagesWithoutDianaMobContext() {
        var parsed = DianaRareMobShareParser.parse(
                "Party > Babbur: x: -10, y: 77, z: 42");

        assertFalse(parsed.isPresent());
    }
}
