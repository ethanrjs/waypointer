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
    void eachLevelSavesAndClearsItsOwnPaint() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Route", "crystal_hollows");
        route.add(Waypoint.at(1, 2, 3));
        route.add(Waypoint.at(4, 5, 6));
        manager.add(route);
        WaypointerConfig config = new WaypointerConfig();
        WaypointPaint global = WaypointPaint.solid(0x111111);
        WaypointPaint zone = WaypointPaint.solid(0x222222);
        WaypointPaint routePaint = WaypointPaint.solid(0x333333);
        WaypointPaint point = WaypointPaint.solid(0x444444);
        PaintScope zoneScope = PaintScope.zone("crystal_hollows");
        PaintScope routeScope = PaintScope.route(route);
        PaintScope pointScope = PaintScope.waypoint(route, 1);

        PaintScope.GLOBAL.apply(config, manager, global);
        assertEquals(global, pointScope.shownPaint(config));
        assertNull(zoneScope.ownPaint(config));

        zoneScope.apply(config, manager, zone);
        assertEquals(zone, zoneScope.ownPaint(config));
        assertEquals(zone, routeScope.shownPaint(config));

        routeScope.apply(config, manager, routePaint);
        assertEquals(routePaint, pointScope.shownPaint(config));

        pointScope.apply(config, manager, point);
        assertEquals(point, pointScope.ownPaint(config));
        assertEquals(routePaint, PaintScope.waypoint(route, 0).shownPaint(config),
                "the other waypoint keeps the route's paint");

        pointScope.clear(config, manager);
        routeScope.clear(config, manager);
        assertNull(pointScope.ownPaint(config));
        assertEquals(zone, pointScope.shownPaint(config));
        zoneScope.clear(config, manager);
        assertEquals(global, pointScope.shownPaint(config));
        PaintScope.GLOBAL.clear(config, manager);
        assertNull(pointScope.shownPaint(config));
    }

    @Test
    void paintCodesRoundTripAndRejectGarbage() {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i % 16);
        WaypointPaint paint = new WaypointPaint(WaypointPaint.defaultPalette(0xABCDEF), pixels);

        assertEquals(paint, WaypointPaint.fromCode(paint.toCode()));
        assertNull(WaypointPaint.fromCode(""));
        assertNull(WaypointPaint.fromCode(paint.toCode().substring(1)));
        assertNull(WaypointPaint.fromCode("z" + paint.toCode().substring(1)));
    }

    @Test
    void fillRecolorsOnlyTheTouchingAreaOfOneColor() {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (int y = 0; y < WaypointPaint.SIZE; y++) {
            pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.EAST, 8, y)] = 1;
        }

        assertEquals(8 * 16, WaypointPainterScreen.floodFill(pixels, WaypointPaint.Face.EAST, 2, 2, 5));
        assertEquals(5, pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.EAST, 0, 15)]);
        assertEquals(1, pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.EAST, 8, 4)], "the wall stays");
        assertEquals(0, pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.EAST, 9, 4)], "the far side stays");
        assertEquals(0, pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.WEST, 2, 2)], "other faces stay");
        assertEquals(0, WaypointPainterScreen.floodFill(pixels, WaypointPaint.Face.EAST, 2, 2, 5),
                "filling with the same color changes nothing");
    }

    @Test
    void fillCanCoverAnEntireFaceFromEveryStartingCell() {
        for (int y = 0; y < WaypointPaint.SIZE; y++) {
            for (int x = 0; x < WaypointPaint.SIZE; x++) {
                byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
                assertEquals(WaypointPaint.FACE_PIXELS,
                        WaypointPainterScreen.floodFill(pixels, WaypointPaint.Face.NORTH, x, y, 3));
            }
        }
    }

    @Test
    void oneFaceFillPaintsTheSameCellsOnEverySide() {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.NORTH, 0, 1)] = 2;
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.NORTH, 1, 0)] = 2;

        assertTrue(WaypointPainterScreen.fillOnEveryFace(pixels, 0, 0, 7));
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            assertEquals(7, pixels[WaypointPaint.pixelOffset(face, 0, 0)]);
            assertEquals(0, pixels[WaypointPaint.pixelOffset(face, 1, 1)], "the corner was walled off");
        }
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
