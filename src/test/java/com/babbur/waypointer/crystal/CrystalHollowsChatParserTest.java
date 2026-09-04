package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.crystal.CrystalHollowsChatParser.CrystalUpdate;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser.SharedCoordinate;
import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrystalHollowsChatParserTest {

    @Test
    void parsesCompactDsmAndSbeEntries() {
        List<SharedCoordinate> entries = CrystalHollowsChatParser.parseSharedCoordinates(
                "$SBECHWP:Khazad-dûm@-292,63,281\\nFairy Grotto@-216,110,400");
        assertEquals(2, entries.size());
        assertEquals(new SharedCoordinate(CrystalHollowsStructure.KHAZAD_DUM,
                292, 63, 281, "dsm_sbe"), entries.get(0));
        assertEquals(CrystalHollowsStructure.FAIRY_GROTTO, entries.get(1).structure());
        assertEquals(216, entries.get(1).x());
    }

    @Test
    void parsesNamedSkytilsSkyblockerAndDsmPlainFormats() {
        assertSingle("Jungle Temple: 512 100 512", CrystalHollowsStructure.JUNGLE_TEMPLE);
        assertSingle("Jungle Temple: 512, 100, 512", CrystalHollowsStructure.JUNGLE_TEMPLE);
        assertSingle("[Skyblocker]  Jungle Temple: 512, 100, 512",
                CrystalHollowsStructure.JUNGLE_TEMPLE);
        assertSingle("Mines of Divan @ 512, 100, 512", CrystalHollowsStructure.MINES_OF_DIVAN);
        assertSingle("Mines of Divan @ 512 100 512", CrystalHollowsStructure.MINES_OF_DIVAN);
    }

    @Test
    void parsesLabeledAndGenericFormatsOnlyWithAliases() {
        assertSingle("x: 512, y: 100, z: 512 | Jungle Temple",
                CrystalHollowsStructure.JUNGLE_TEMPLE);
        assertSingle("gqd here at 512 100 512", CrystalHollowsStructure.GOBLIN_QUEENS_DEN);
        assertTrue(CrystalHollowsChatParser.parseSharedCoordinates(
                "lots of loot at 512 100 512").isEmpty());
        List<SharedCoordinate> unlabeled = CrystalHollowsChatParser.parseSharedCoordinates(
                "x: 512, y: 100, z: 512");
        assertTrue(unlabeled.isEmpty());
    }

    @Test
    void rejectsCoordinatesOutsideHollowsBounds() {
        assertTrue(CrystalHollowsChatParser.parseSharedCoordinates(
                "Jungle Temple: 200 100 512").isEmpty());
        assertTrue(CrystalHollowsChatParser.parseSharedCoordinates(
                "Jungle Temple: 512 190 512").isEmpty());
        assertTrue(CrystalHollowsChatParser.parseSharedCoordinates(
                "Jungle Temple: 512 100 825").isEmpty());
    }

    @Test
    void aliasMatchingUsesWholeWordsAndLongestNames() {
        assertEquals(CrystalHollowsStructure.KEY_GUARDIAN,
                CrystalHollowsChatParser.structureFromText("key guardian"));
        assertEquals(CrystalHollowsStructure.LOST_PRECURSOR_CITY,
                CrystalHollowsChatParser.structureFromText("precursor city"));
        assertNull(CrystalHollowsChatParser.structureFromText("lots of keys were lost"));
    }

    @Test
    void parsesNpcPrefixes() {
        assertEquals(CrystalHollowsStructure.KING_YOLKAR,
                CrystalHollowsChatParser.parseNpcDialogue("[NPC] King Yolkar: Hello!")
                        .orElseThrow().structure());
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN,
                CrystalHollowsChatParser.parseNpcDialogue("[NPC] Keeper of Gold: Bring tools")
                        .orElseThrow().structure());
        assertTrue(CrystalHollowsChatParser.parseNpcDialogue("Player: hello").isEmpty());
    }

    @Test
    void parsesCrystalStateLines() {
        assertUpdate("✦ You placed the Amber Crystal!", Crystal.AMBER, CrystalState.PLACED);
        assertUpdate("✦ You reclaimed the Amethyst Crystal!", Crystal.AMETHYST,
                CrystalState.COLLECTED);
        assertUpdate("Keeper: You haven't placed the Jade Crystal yet!", Crystal.JADE,
                CrystalState.COLLECTED);
        assertUpdate("Keeper: You have already placed the Sapphire Crystal!", Crystal.SAPPHIRE,
                CrystalState.PLACED);
        assertUpdate("   Topaz Crystal", Crystal.TOPAZ, CrystalState.COLLECTED);
        CrystalUpdate reset = CrystalHollowsChatParser.parseCrystalState(
                "  CRYSTAL NUCLEUS LOOT BUNDLE (1/1)").orElseThrow();
        assertTrue(reset.resetAll());
        CrystalUpdate jade = CrystalHollowsChatParser.parseCrystalState(
                "[NPC] Keeper of Diamond: You found all of the items! Behold... the Jade Crystal!")
                .orElseThrow();
        assertEquals(Crystal.JADE, jade.crystal());
        assertTrue(CrystalHollowsChatParser.parseCrystalState("nothing relevant").isEmpty());
    }

    @Test
    void detectsCompassMessagesAndDelayTriggers() {
        assertEquals(CrystalHollowsChatParser.CompassServerMessage.USE_CONFIRMED,
                CrystalHollowsChatParser.parseCompassServerMessage(
                        "Your Wishing Compass shattered into pieces!").orElseThrow());
        assertEquals(CrystalHollowsChatParser.CompassServerMessage.NO_TARGET,
                CrystalHollowsChatParser.parseCompassServerMessage(
                        "The Wishing Compass can't seem to locate anything!").orElseThrow());
        assertTrue(CrystalHollowsChatParser.isDelayTrigger("☠ You were killed by Bal"));
        assertTrue(CrystalHollowsChatParser.isDelayTrigger("Warping..."));
        assertFalse(CrystalHollowsChatParser.isDelayTrigger("Player: Warp me?"));
    }

    @Test
    void emittedShareRoundTrips() {
        String share = CrystalHollowsChatParser.formatShare(
                CrystalHollowsStructure.ODAWA, 349, 110, 390);
        assertEquals("Odawa: 349 110 390", share);
        assertEquals(CrystalHollowsStructure.ODAWA,
                CrystalHollowsChatParser.parseSharedCoordinates(share).getFirst().structure());
    }

    @Test
    void recognizesOnlyPlayerPrefixedChatForShares() {
        CrystalHollowsChatParser.PlayerChat chat = CrystalHollowsChatParser.playerChat(
                "[MVP+] Some_Player: Jungle Temple: 512 100 512").orElseThrow();
        assertEquals("Some_Player", chat.sender());
        assertEquals("Jungle Temple: 512 100 512", chat.body());
        assertTrue(CrystalHollowsChatParser.playerChat(
                "Jungle Temple: 512 100 512").isEmpty());
    }

    private static void assertSingle(String text, CrystalHollowsStructure expected) {
        List<SharedCoordinate> parsed = CrystalHollowsChatParser.parseSharedCoordinates(text);
        assertEquals(1, parsed.size(), text);
        assertEquals(expected, parsed.getFirst().structure(), text);
        assertEquals(512, parsed.getFirst().x(), text);
        assertEquals(100, parsed.getFirst().y(), text);
        assertEquals(512, parsed.getFirst().z(), text);
    }

    private static void assertUpdate(String text, Crystal crystal, CrystalState state) {
        CrystalUpdate update = CrystalHollowsChatParser.parseCrystalState(text).orElseThrow();
        assertEquals(crystal, update.crystal());
        assertEquals(state, update.state());
        assertFalse(update.resetAll());
    }
}
