package com.babbur.waypointer.screen;

import com.babbur.waypointer.dungeon.DungeonWaypointType;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconsTest {

    @Test
    void enumOrderMatchesTheDrawnIconSheet() throws Exception {
        List<String> drawn = Files.readAllLines(Path.of("tools/icons/icon-names.txt")).stream()
                .filter(line -> !line.isBlank()).toList();
        List<String> code = Arrays.stream(Icons.Icon.values())
                .map(icon -> icon.name().toLowerCase(Locale.ROOT)).toList();
        assertEquals(drawn, code, "Rebuild with tools/icons/build_icons.py and keep Icons.Icon in the same order");
    }

    @Test
    void atlasHoldsEveryIconInSixteenPixelCells() throws Exception {
        var image = ImageIO.read(Path.of("src/main/resources/assets/waypointer/textures/gui/icons.png").toFile());
        int rows = (Icons.Icon.values().length + 15) / 16;
        assertEquals(16 * Icons.SIZE, image.getWidth());
        assertEquals(rows * Icons.SIZE, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
        for (Icons.Icon icon : Icons.Icon.values()) {
            int u = (icon.ordinal() % 16) * Icons.SIZE;
            int v = (icon.ordinal() / 16) * Icons.SIZE;
            boolean drawnPixel = false;
            for (int y = 0; y < Icons.SIZE && !drawnPixel; y++) {
                for (int x = 0; x < Icons.SIZE && !drawnPixel; x++) {
                    drawnPixel = (image.getRGB(u + x, v + y) >>> 24) != 0;
                }
            }
            assertTrue(drawnPixel, icon + " has no pixels");
        }
    }

    @Test
    void everyDungeonTypeHasItsOwnIcon() {
        Set<Icons.Icon> seen = new HashSet<>();
        for (DungeonWaypointType type : DungeonWaypointType.values()) {
            assertTrue(seen.add(Icons.forDungeonType(type)), type + " shares an icon");
        }
        assertEquals(Icons.Icon.TAG, Icons.forDungeonType(null));
    }
}
