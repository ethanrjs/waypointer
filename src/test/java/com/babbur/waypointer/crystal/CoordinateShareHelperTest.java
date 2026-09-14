package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateShareHelperTest {
    @Test
    void requestsUseWholeAliasesAndImmediateQuestionMarks() {
        for (String text : List.of("temple?", "temple?please", "where is city", "divan coords",
                "fairy grotto location", "precursor?", "where is queen", "den?", "bal?", "khazad?",
                "king?", "yolkar?", "xalx?", "corleone?", "odawa?", "dragon?", "guardian?")) {
            assertFalse(CrystalHollowsChatParser.requestedStructures(text).isEmpty(), text);
        }
        for (String text : List.of("temple", "temple ?", "fairy tale", "where is kingdom",
                "balanced coords", "cityscape location", "anywhere temple", "coordinates of temple")) {
            assertTrue(CrystalHollowsChatParser.requestedStructures(text).isEmpty(), text);
        }
        assertEquals(List.of(CrystalHollowsStructure.FAIRY_GROTTO),
                CrystalHollowsChatParser.requestedStructures("fairy grotto location"));
    }

    @Test
    void onlyActiveEnabledPlayerRequestsReceiveCapturedKnownCoordinates() throws Exception {
        var config = new WaypointerConfig();
        config.setCrystalHollowsChatDetection(false);
        config.setChatCoordDetection(false);
        config.setCrystalHollowsRemoteSharing(false);
        var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), config, null);
        var lobby = new CrystalHollowsLobbyState("mini196BN", 1, 6);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        var first = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO,
                300, 100, 300, SightingConfidence.MANUAL, "manual:player", 1);
        var second = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO,
                700, 100, 700, SightingConfidence.MANUAL, "manual:player", 2);
        lobby.merge(first);
        lobby.merge(second);
        var original = Component.literal("[MVP+] OtherPlayer: fairy grotto location").withStyle(ChatFormatting.GOLD);
        var result = tracker.addCoordinateShareActions(original, false);
        assertEquals(original.getStyle(), result.getStyle());
        assertEquals("[MVP+] OtherPlayer: fairy grotto location", original.getString());
        var buttons = result.getSiblings().stream().filter(part -> part.getStyle().getClickEvent() != null).toList();
        assertEquals(2, buttons.size());
        assertEquals("/wpch sendcoords fairy_grotto 300 100 300",
                assertInstanceOf(ClickEvent.RunCommand.class, buttons.get(0).getStyle().getClickEvent()).command());
        assertEquals("/wpch sendcoords fairy_grotto 700 100 700",
                assertInstanceOf(ClickEvent.RunCommand.class, buttons.get(1).getStyle().getClickEvent()).command());
        assertTrue(buttons.get(0).getString().contains("1"));
        assertTrue(buttons.get(1).getString().contains("2"));
        for (String text : List.of("OtherPlayer: temple?", "[NPC] Odawa: grotto?", "Grotto: hello", "OtherPlayer: grotto")) {
            var unknown = Component.literal(text);
            assertSame(unknown, tracker.addCoordinateShareActions(unknown, false), text);
        }
        assertSame(original, tracker.addCoordinateShareActions(original, true));
        config.setCoordinateShareHelper(false);
        assertSame(original, tracker.addCoordinateShareActions(original, false));
        config.setCoordinateShareHelper(true);
        set(tracker, "active", false);
        assertSame(original, tracker.addCoordinateShareActions(original, false));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
