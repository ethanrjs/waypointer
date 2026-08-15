package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiTokensTest {

    @Test
    void checkboxCheckmarkPreservesTheSuppliedTextureCanvas() throws IOException {
        try (var stream = GuiTokensTest.class.getResourceAsStream(
                "/assets/waypointer/textures/gui/checkmark.png")) {
            assertNotNull(stream);
            var image = ImageIO.read(stream);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
        }
    }

    @Test
    void opticalGeometryCentersVisiblePixelGlyphs() {
        assertEquals(16, GuiTokens.opticalTextY(10, 20));
        assertEquals(14, GuiTokens.opticalInfoButtonY(16));
    }

    @Test
    void coloredMessagesCarryTheTokenAsATextColor() {
        var colored = GuiTokens.colored(
                net.minecraft.network.chat.Component.literal("Copied"), GuiTokens.SUCCESS);
        assertEquals("Copied", colored.getString());
        assertNotNull(colored.getStyle().getColor());
        assertEquals(GuiTokens.SUCCESS & 0xFFFFFF, colored.getStyle().getColor().getValue());
    }

    @Test
    void solidTriangleLabelsUseTheSharedPixelDirections() {
        assertEquals(GuiTokens.Direction.UP, GuiTokens.directionForLabel("\u25b2"));
        assertEquals(GuiTokens.Direction.DOWN, GuiTokens.directionForLabel("\u25bc"));
        assertEquals(GuiTokens.Direction.LEFT, GuiTokens.directionForLabel("\u25c0"));
        assertEquals(GuiTokens.Direction.RIGHT, GuiTokens.directionForLabel("\u25b6"));
        assertEquals(null, GuiTokens.directionForLabel("Done"));
    }

    @Test
    void footerPlacementsReserveRightButtonLaneWhenPrimaryActionsFit() {
        List<GuiTokens.ButtonSpec> left = List.of(
                fixed("New", 80),
                fixed("Edit", 60));
        GuiTokens.ButtonSpec right = fixed("Done", 100);

        List<GuiTokens.FooterPlacement> placements = GuiTokens.footerPlacements(
                400, 200, left, right, null, 16, 16);

        assertEquals(3, placements.size());
        assertPlacement(placements.get(0), "New", 16, 200, 80);
        assertPlacement(placements.get(1), "Edit", 104, 200, 60);
        assertPlacement(placements.get(2), "Done", 284, 200, 100);
        assertTrue(placements.get(1).x() + placements.get(1).width()
                <= placements.get(2).x() - GuiTokens.GAP_SECTION);
    }

    @Test
    void footerPlacementsWrapOverflowAbovePrimaryRow() {
        List<GuiTokens.ButtonSpec> left = List.of(
                fixed("One", 80),
                fixed("Two", 80),
                fixed("Three", 80),
                fixed("Four", 80));
        GuiTokens.ButtonSpec right = fixed("Done", 90);

        List<GuiTokens.FooterPlacement> placements = GuiTokens.footerPlacements(
                320, 200, left, right, null, 16, 16);

        assertEquals(5, placements.size());
        assertPlacement(placements.get(0), "One", 16, 200, 80);
        assertPlacement(placements.get(1), "Two", 104, 200, 80);
        assertPlacement(placements.get(2), "Done", 214, 200, 90);
        assertPlacement(placements.get(3), "Three", 16, 172, 80);
        assertPlacement(placements.get(4), "Four", 104, 172, 80);
        assertTrue(placements.get(1).x() + placements.get(1).width()
                <= placements.get(2).x() - GuiTokens.GAP_SECTION);
    }

    @Test
    void footerHeightExpandsOnlyWhenWrappingIsRequired() {
        List<GuiTokens.ButtonSpec> left = List.of(fixed("One", 80), fixed("Two", 80));
        GuiTokens.ButtonSpec right = fixed("Done", 100);

        assertEquals(GuiTokens.FOOTER_H,
                GuiTokens.footerHeight(400, left, right, null));
        assertEquals(GuiTokens.FOOTER_H + GuiTokens.BTN_H + GuiTokens.GAP,
                GuiTokens.footerHeight(260, left, right, null));
    }

    private static GuiTokens.ButtonSpec fixed(String label, int width) {
        return new GuiTokens.ButtonSpec(label, width, () -> {});
    }

    private static void assertPlacement(GuiTokens.FooterPlacement placement,
                                        String label, int x, int y, int width) {
        assertEquals(label, placement.spec().label());
        assertEquals(x, placement.x());
        assertEquals(y, placement.y());
        assertEquals(width, placement.width());
    }
}
