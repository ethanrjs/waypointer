package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LabelTextTest {

    @Test
    void hexTagsColourTheTextAndAreNotDrawn() {
        Component label = LabelText.styled("<#077FFF>Waypoint 1");

        assertEquals("Waypoint 1", label.getString());
        assertEquals(List.of(0x077FFF), colours(label));
    }

    @Test
    void ampersandHexAndLegacyCodesWorkTogether() {
        Component label = LabelText.styled("&#FF3040Red &aGreen&r plain");

        assertEquals("Red Green plain", label.getString());
        assertEquals(List.of(0xFF3040, 0x55FF55), colours(label));
    }

    @Test
    void malformedTagsStayAsTyped() {
        assertEquals("<#GGGGGG>x <#12345>y", LabelText.styled("<#GGGGGG>x <#12345>y").getString());
    }

    @Test
    void editorLightsUpPlaceholdersAndKeepsEveryCharacter() {
        String value = "<#077FFF>Go {distance}";
        Style[] styles = LabelText.editorStyles(value, 0x4FB3C4);

        assertEquals(value.length(), styles.length);
        assertEquals(TextColor.fromRgb(0x077FFF), styles[0].getColor(), "the tag shows its own colour");
        assertEquals(TextColor.fromRgb(0x077FFF), styles[value.indexOf("Go")].getColor());
        assertEquals(TextColor.fromRgb(0x4FB3C4), styles[value.indexOf("{distance}")].getColor());
        assertEquals(TextColor.fromRgb(0xE47B7B), LabelText.editorStyles("{distanc}", 0x4FB3C4)[0].getColor(),
                "a misspelled placeholder is red");
        Style[] typing = LabelText.editorStyles("Go {dist", 0x4FB3C4);
        assertEquals(TextColor.fromRgb(0xE47B7B), typing[typing.length - 1].getColor(),
                "a placeholder still being typed is red until it is complete");
        assertNull(LabelText.editorStyles("Go home", 0x4FB3C4)[0].getColor());
    }

    @Test
    void otherLabelRowsFollowTheLabelColourOnlyWhenAsked() {
        WaypointerConfig config = new WaypointerConfig();
        int name = 0xFF123456;

        assertEquals(name, WaypointRenderer.labelRowColor(config, true, name));
        assertEquals(WaypointWorldRenderer.DISTANCE_ARGB, WaypointRenderer.labelRowColor(config, false, name));
        config.setLabelColorForAllText(true);
        assertEquals(name, WaypointRenderer.labelRowColor(config, false, name));
    }

    private static List<Integer> colours(Component label) {
        List<Integer> out = new ArrayList<>();
        label.visit((style, text) -> {
            if (!text.isEmpty() && style.getColor() != null) out.add(style.getColor().getValue());
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }
}
