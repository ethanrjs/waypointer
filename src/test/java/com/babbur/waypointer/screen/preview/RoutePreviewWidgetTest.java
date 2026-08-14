package com.babbur.waypointer.screen.preview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePreviewWidgetTest {

    @Test
    void hoverLabelUsesOptionalNameWaypointNumberAndParenthesizedCoordinates() {
        RoutePreviewScene.Marker named = marker("Crystal", "#4");
        RoutePreviewScene.Marker unnamed = marker("", "#3.2");

        assertEquals(List.of("Crystal", "Waypoint #4", "(60, 98, 46)"),
                RoutePreviewWidget.labelLines(named));
        assertEquals(List.of("Waypoint #3.2", "(60, 98, 46)"),
                RoutePreviewWidget.labelLines(unnamed));
    }

    @Test
    void headingWaypointCountUsesTwoLineDetailCopy() {
        assertEquals("(1 waypoint)", RoutePreviewWidget.waypointCountText(1));
        assertEquals("(33 waypoints)", RoutePreviewWidget.waypointCountText(33));
    }

    @Test
    void headerGivesBackTheArrowReserveWhenThereIsOnlyOneRoute() {
        assertEquals(240 - 52, RoutePreviewWidget.headerTextWidth(240, true));
        assertEquals(240 - 8, RoutePreviewWidget.headerTextWidth(240, false));
        assertEquals(0, RoutePreviewWidget.headerTextWidth(20, true));
    }

    @Test
    void headerCentersOneOrTwoLinesWithThreePixelsOfLeading() {
        assertEquals(List.of(15), asList(RoutePreviewWidget.headerLineY(10, 20, 9, false)));
        assertEquals(List.of(19, 31), asList(
                RoutePreviewWidget.headerLineY(10, 40, 9, true)));
    }

    private static List<Integer> asList(int[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    @Test
    void settingsPreviewUsesACompactTitleOnlyHeader() {
        RoutePreviewWidget widget = new RoutePreviewWidget(
                0, 0, 240, 112, RoutePreviewScene.empty(), "",
                new RoutePreviewOrbit(), new RoutePreviewZoom());

        assertEquals(RoutePreviewWidget.HEADER_HEIGHT, widget.headerHeight());
        widget.setHeaderDetailVisible(false);
        assertEquals(RoutePreviewWidget.SINGLE_LINE_HEADER_HEIGHT, widget.headerHeight());
    }

    @Test
    void zoomControlReportsOneDecimalAndHidesAtTheDefaultFraming() {
        RoutePreviewZoom zoom = new RoutePreviewZoom();
        RoutePreviewWidget widget = new RoutePreviewWidget(
                0, 0, 240, 160, RoutePreviewScene.empty(), "",
                new RoutePreviewOrbit(), zoom);

        assertFalse(widget.zoomed());
        assertTrue(widget.scrollZoom(120, 80, 2.0));
        assertTrue(widget.zoomed());
        assertEquals("1.5", widget.zoomLabel());

        widget.resetZoom();
        assertFalse(widget.zoomed());
        assertEquals(RoutePreviewZoom.DEFAULT_FACTOR, zoom.factor(), 0.0);
    }

    @Test
    void previewDoesNotAcceptFocusOrClicks() {
        RoutePreviewWidget widget = new RoutePreviewWidget(
                0, 0, 240, 160, RoutePreviewScene.empty(), "",
                new RoutePreviewOrbit(), new RoutePreviewZoom());

        assertFalse(widget.active);
        assertFalse(widget.mouseClicked(null, false));
        assertNull(widget.nextFocusPath(null));
    }

    @Test
    void wheelZoomDoesNotActivateOrFocusThePreview() {
        RoutePreviewZoom zoom = new RoutePreviewZoom();
        RoutePreviewWidget widget = new RoutePreviewWidget(
                0, 0, 240, 160, RoutePreviewScene.empty(), "",
                new RoutePreviewOrbit(), zoom);

        assertTrue(widget.scrollZoom(120, 80, 1.0));
        assertEquals(RoutePreviewZoom.DEFAULT_FACTOR * RoutePreviewZoom.STEP_FACTOR,
                zoom.factor(), 1.0e-12);
        assertFalse(widget.scrollZoom(120, 16, 1.0));
        assertFalse(widget.scrollZoom(241, 80, 1.0));
        assertFalse(widget.scrollZoom(120, 80, 0.0));
        assertFalse(widget.active);
        assertNull(widget.nextFocusPath(null));

        widget.setScene(RoutePreviewScene.empty(), "");
        assertEquals(RoutePreviewZoom.DEFAULT_FACTOR, zoom.factor(), 0.0);
    }

    @Test
    void orbitPauseUsesThePreviousHoverSoPickingRunsOnlyOncePerFrame() {
        assertTrue(RoutePreviewWidget.shouldPauseOrbit(true, 2, true, true));
        assertFalse(RoutePreviewWidget.shouldPauseOrbit(true, -1, true, true));
        assertTrue(RoutePreviewWidget.shouldPauseOrbit(false, -1, false, true));
        assertTrue(RoutePreviewWidget.shouldPauseOrbit(false, -1, true, false));
    }

    private static RoutePreviewScene.Marker marker(String name, String displayIndex) {
        return new RoutePreviewScene.Marker(
                3, name, displayIndex, "Step 3 of 5", "(60, 98, 46)",
                0xFFFFFF, 0, false, false, 2,
                new RoutePreviewScene.Box(0, 0, 0, 1, 1, 1));
    }
}
