package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.Comparator;
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

        assertFooterActionsFit(placements, left, right, 400, 200, 16);
        assertTrue(placements.stream().allMatch(placement -> placement.y() == 200),
                "actions that fit must stay on the primary row");
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

        assertFooterActionsFit(placements, left, right, 320, 200, 16);
        assertTrue(placements.stream().anyMatch(placement -> placement.y() < 200),
                "overflow must remain available above the primary row");
        assertTrue(placements.stream().anyMatch(placement ->
                placement.spec() != right && placement.y() == 200),
                "the primary row should still contain the actions that fit");
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

    private static void assertFooterActionsFit(
            List<GuiTokens.FooterPlacement> placements, List<GuiTokens.ButtonSpec> left,
            GuiTokens.ButtonSpec right, int screenWidth, int footerY, int inset) {
        assertEquals(left.size() + 1, placements.size());
        assertEquals(left, placements.stream()
                .filter(placement -> placement.spec() != right)
                .sorted(Comparator.comparingInt(GuiTokens.FooterPlacement::y).reversed()
                        .thenComparingInt(GuiTokens.FooterPlacement::x))
                .map(GuiTokens.FooterPlacement::spec).toList(),
                "actions must keep their order across primary and overflow rows");
        GuiTokens.FooterPlacement done = placements.stream()
                .filter(placement -> placement.spec() == right).findFirst().orElseThrow();
        assertEquals(footerY, done.y());
        for (GuiTokens.FooterPlacement placement : placements) {
            assertEquals(placement.spec().width(), placement.width(),
                    "fitting actions must keep their requested hit-target width");
            assertTrue(placement.x() >= inset);
            assertTrue(placement.x() + placement.width() <= screenWidth - inset);
            assertTrue(placement.y() >= 0 && placement.y() <= footerY);
            if (placement.spec() != right && placement.y() == footerY) {
                assertTrue(placement.x() + placement.width()
                        <= done.x() - GuiTokens.GAP_SECTION,
                        "the primary actions must leave a separate Done lane");
            }
        }
        for (int first = 0; first < placements.size(); first++) {
            GuiTokens.FooterPlacement a = placements.get(first);
            for (int second = first + 1; second < placements.size(); second++) {
                GuiTokens.FooterPlacement b = placements.get(second);
                assertTrue(a.x() + a.width() <= b.x() || b.x() + b.width() <= a.x()
                        || a.y() + GuiTokens.BTN_H <= b.y()
                        || b.y() + GuiTokens.BTN_H <= a.y(),
                        a.spec().label() + " overlaps " + b.spec().label());
            }
        }
    }
}
