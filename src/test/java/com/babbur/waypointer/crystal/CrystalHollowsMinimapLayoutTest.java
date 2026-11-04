package com.babbur.waypointer.crystal;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrystalHollowsMinimapLayoutTest {
    @Test
    void labelsMoveAwayFromTheEdgeAndYieldToOccupiedSpace() {
        var map = new CrystalHollowsMinimapLayout(8, 8, 144);
        int x = map.mapX() + map.marker(823), y = map.mapY() + map.marker(500);
        assertEquals(138, map.marker(823));
        assertEquals(5, map.marker(201));
        var label = map.label(x, y, 12, List.of());
        assertNotNull(label);
        assertTrue(label.x() + label.width() < x);
        var fullMap = new CrystalHollowsMinimapLayout.Rect(map.mapX(), map.mapY(), map.size(), map.size());
        assertNull(map.label(x, y, 12, List.of(fullMap)));
        assertNull(map.label(x, y, 500, List.of()));
        int centerX = map.mapX() + 70, centerY = map.mapY() + 70;
        var marker = new CrystalHollowsMinimapLayout.Rect(centerX - 5, centerY - 5, 11, 11);
        assertEquals(centerX + 8, map.label(centerX, centerY, 12, List.of(marker)).x());
    }

    @Test
    void targetLabelsKeepTheirEntirePlateOutsideNavigationBrackets() {
        var map = new CrystalHollowsMinimapLayout(8, 8, 96);
        int x = map.mapX() + 48, y = map.mapY() + 48;
        var target = new CrystalHollowsMinimapLayout.Rect(x - 8, y - 8, 17, 17);
        var occupied = new ArrayList<CrystalHollowsMinimapLayout.Rect>();
        occupied.add(target);
        for (int side = 0; side < 4; side++) {
            var label = map.label(x, y, 12, occupied, 11);
            assertNotNull(label, "Each side should leave room for the navigation brackets");
            var plate = new CrystalHollowsMinimapLayout.Rect(label.x() - 2, label.y() - 1, 16, 11);
            assertFalse(plate.overlaps(target));
            occupied.add(plate);
        }
        assertNull(map.label(x, y, 12, occupied, 11));
    }

    @Test
    void projectsNorthWestToOriginAndClampsTheExclusiveSouthEastBoundary() {
        var map = new CrystalHollowsMinimapLayout(8, 8, 144);
        assertEquals(0, map.project(201));
        assertEquals(143, map.project(823.999));
        assertEquals(72, map.project(513.5));
        assertEquals(0, map.project(-100));
        assertEquals(143, map.project(900));
        assertTrue(CrystalHollowsMinimapLayout.contains(201, 823.999));
        assertFalse(CrystalHollowsMinimapLayout.contains(824, 201));
        assertFalse(CrystalHollowsMinimapLayout.contains(Double.NaN, 201));
        assertThrows(IllegalArgumentException.class, () -> map.project(Double.NaN));
    }

    @Test
    void allCornersFitSmallAndLargeWindowsWithoutCoveringTheHotbar() {
        for (int width : new int[]{320, 640, 1280}) {
            for (int height : new int[]{178, 180, 360, 720}) {
                for (boolean navigation : new boolean[]{false, true}) {
                    for (int corner = 0; corner < 4; corner++) {
                        var map = CrystalHollowsMinimapLayout.fit(width, height, 224, corner, navigation);
                        assertNotNull(map);
                        assertTrue(map.x() >= 8 && map.y() >= 8);
                        assertTrue(map.x() + map.width() <= width - 8);
                        assertTrue(map.y() + map.height() <= height - 36);
                        assertEquals((corner & 1) == 0 ? 8 : width - map.width() - 8, map.x());
                        assertEquals((corner & 2) == 0 ? 8 : height - map.height() - 36, map.y());
                    }
                }
            }
        }
        assertEquals(96, CrystalHollowsMinimapLayout.fit(320, 178, 96, 0).size());
        assertNull(CrystalHollowsMinimapLayout.fit(100, 100, 144, 0));
    }

    @Test
    void nucleusSharesTheExactMapCenterAtOddAndEvenSizesAndFooterKeepsTheBottomAnchor() {
        for (int size = 80; size <= 224; size++) {
            var map = new CrystalHollowsMinimapLayout(8, 8, size);
            var nucleus = map.nucleus();
            assertEquals(map.mapX() + size / 2.0, nucleus.x() + nucleus.width() / 2.0);
            assertEquals(map.mapY() + size / 2.0, nucleus.y() + nucleus.height() / 2.0);
            assertEquals(nucleus.x() - map.mapX(), map.mapX() + size - nucleus.x() - nucleus.width());
            assertEquals(nucleus.y() - map.mapY(), map.mapY() + size - nucleus.y() - nucleus.height());
            assertEquals(nucleus.width(), nucleus.height());
            assertEquals(size / 2.0f, map.markerCenter(CrystalHollowsGeometry.NUCLEUS_CENTRE_X));
            assertEquals(size / 2.0f, map.markerCenter(CrystalHollowsGeometry.NUCLEUS_CENTRE_Z));
            assertEquals(5.5f, map.markerCenter(CrystalHollowsGeometry.MIN_XZ));
            assertEquals(size - 5.5f, map.markerCenter(CrystalHollowsGeometry.MAX_XZ));
        }
        var idle = CrystalHollowsMinimapLayout.fit(640, 360, 144, 3, false);
        var navigation = CrystalHollowsMinimapLayout.fit(640, 360, 144, 3, true);
        assertEquals(idle.y() + idle.height(), navigation.y() + navigation.height());
        assertEquals(CrystalHollowsMinimapLayout.PADDING, idle.height() - idle.size() - CrystalHollowsMinimapLayout.HEADER);
        assertTrue(idle.height() < navigation.height());
    }

    @Test
    void defaultScaleKeepsExistingAnchorsAndCustomPlacementSurvivesWindowChanges() {
        for (int width : new int[]{100, 320, 640, 1280}) {
            for (int height : new int[]{100, 178, 360, 720}) {
                for (boolean navigation : new boolean[]{false, true}) {
                    for (int corner = 0; corner < 4; corner++) {
                        assertEquals(CrystalHollowsMinimapLayout.fit(width, height, 224, corner, navigation),
                                CrystalHollowsMinimapLayout.fit(width, height, 224, corner, navigation,
                                        1, false, 0.3, 0.7));
                    }
                    for (double scale : new double[]{0.5, 0.55, 1, 1.95, 2}) {
                        for (double position : new double[]{-1, 0, 0.3, 0.5, 1, 2, Double.NaN}) {
                            var map = CrystalHollowsMinimapLayout.fit(width, height, 144, 0, navigation,
                                    scale, true, position, position);
                            if (map == null) continue;
                            assertTrue(map.x() >= 8 && map.y() >= 8);
                            assertTrue(map.x() + map.width() <= width - 8);
                            assertTrue(map.y() + map.height() <= height - 36);
                            assertEquals(map.x(), CrystalHollowsMinimapLayout.placementX(width, map.width(),
                                    CrystalHollowsMinimapLayout.normalizeX(width, map.width(), map.x())));
                            assertEquals(map.y(), CrystalHollowsMinimapLayout.placementY(height, map.height(),
                                    CrystalHollowsMinimapLayout.normalizeY(height, map.height(), map.y())));
                        }
                    }
                }
            }
        }
        assertEquals(72, CrystalHollowsMinimapLayout.fit(1280, 720, 144, 0, false,
                0.5, true, 0.5, 0.5).size());
        assertEquals(288, CrystalHollowsMinimapLayout.fit(1280, 720, 144, 0, false,
                2, true, 0.5, 0.5).size());
        assertEquals(144, CrystalHollowsMinimapLayout.fit(1280, 720, 144, 0, false,
                Double.NaN, false, 0, 0).size());
        assertEquals(0, CrystalHollowsMinimapLayout.normalizeX(100, 100, 50));
        assertEquals(0, CrystalHollowsMinimapLayout.normalizeY(100, 100, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new CrystalHollowsMinimapLayout(8, 8, 144).markerCenter(Double.NaN));
    }
}
