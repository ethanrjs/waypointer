package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.config.WaypointerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointPainterScreenTest {

    @Test
    void painterOffersDirectFileImport() {
        assertEquals("Import from file", WaypointPainterScreen.IMPORT_FILE_LABEL);
    }

    @Test
    void applyAllPersistsAnInheritedPaintForFutureRoutesAndWaypoints() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup existing = WaypointGroup.create("Existing", "hub");
        existing.add(Waypoint.at(1, 2, 3));
        manager.add(existing);
        WaypointerConfig config = new WaypointerConfig();
        WaypointPaint paint = WaypointPaint.solid(0x123456);

        assertEquals(1, WaypointPainterScreen.applyToAllGroups(manager, config, paint));
        assertEquals(paint, existing.paint());
        assertEquals(paint, config.waypointPainterDefaultPaint());

        WaypointGroup future = WaypointGroup.create("Future", "hub");
        future.add(Waypoint.at(4, 5, 6));
        assertTrue(future.paintEnabled());
        assertNull(future.paint(), "future routes inherit the saved all-waypoint paint");
    }

    @Test
    void oneFaceSnapshotRepeatsWithoutDestroyingTheSixFaceDesign() {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.NORTH, 3, 4)] = 7;
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.SOUTH, 3, 4)] = 2;

        byte[] repeated = WaypointPainterScreen.repeatedFaceCopy(
                pixels, WaypointPaint.Face.NORTH);

        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            assertEquals(7, Byte.toUnsignedInt(
                    repeated[WaypointPaint.pixelOffset(face, 3, 4)]));
        }
        assertEquals(2, Byte.toUnsignedInt(
                pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.SOUTH, 3, 4)]));
    }

    @Test
    void atlasNavigationCrossesAdjacentFacesAndRejectsEmptySlots() {
        assertEquals(WaypointPaint.Face.UP,
                WaypointPainterScreen.faceAtAtlasPixel(16, 15));
        assertEquals(WaypointPaint.Face.WEST,
                WaypointPainterScreen.faceAtAtlasPixel(15, 16));
        assertEquals(WaypointPaint.Face.NORTH,
                WaypointPainterScreen.faceAtAtlasPixel(16, 16));
        assertNull(WaypointPainterScreen.faceAtAtlasPixel(0, 0));
    }

    @Test
    void imageImportRejectsOversizedMetadataBeforeDecode() {
        assertTrue(WaypointPainterScreen.acceptsImageDimensions(4096, 2048));
        assertFalse(WaypointPainterScreen.acceptsImageDimensions(8193, 1));
        assertFalse(WaypointPainterScreen.acceptsImageDimensions(4096, 2049));
        assertFalse(WaypointPainterScreen.acceptsImageDimensions(0, 16));
    }
}
