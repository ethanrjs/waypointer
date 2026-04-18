package dev.ethan.waypointer.color;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GradientColorizerTest {

    @Test
    void hslToRgb_pureRed() {
        int rgb = GradientColorizer.hslToRgb(0f, 1f, 0.5f);
        assertEquals(0xFF0000, rgb);
    }

    @Test
    void hslToRgb_pureGreen() {
        int rgb = GradientColorizer.hslToRgb(120f, 1f, 0.5f);
        assertEquals(0x00FF00, rgb);
    }

    @Test
    void hslToRgb_pureBlue() {
        int rgb = GradientColorizer.hslToRgb(240f, 1f, 0.5f);
        assertEquals(0x0000FF, rgb);
    }

    @Test
    void apply_singleWaypointUsesStartHue() {
        WaypointGroup g = WaypointGroup.create("one", "z");
        g.add(Waypoint.at(0, 0, 0));
        // gradient with one element -> t=0 -> HUE_START (cyan-ish)
        int rgb = g.get(0).color();
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        assertTrue(b > r, "cyan should have more blue than red, got 0x" + Integer.toHexString(rgb));
    }

    @Test
    void apply_skipsLockedEntries() {
        WaypointGroup g = WaypointGroup.create("g", "z");
        int sentinel = 0x123456;
        g.add(Waypoint.at(0, 0, 0).withColor(sentinel).withFlags(Waypoint.FLAG_LOCKED_COLOR));
        g.add(Waypoint.at(1, 0, 0));
        g.add(Waypoint.at(2, 0, 0));
        assertEquals(sentinel, g.get(0).color());
    }

    @Test
    void apply_spreadsColorsMonotonicallyInHue() {
        WaypointGroup g = WaypointGroup.create("g", "z");
        for (int i = 0; i < 5; i++) g.add(Waypoint.at(i, 0, 0));
        // With HUE_START=180 and HUE_END=360, later waypoints should shift toward red.
        int firstR = (g.get(0).color() >> 16) & 0xFF;
        int lastR  = (g.get(4).color() >> 16) & 0xFF;
        assertTrue(lastR > firstR, "red channel should grow from start to end of gradient");
    }

    @Test
    void reorder_recomputesGradient() {
        // The plan promises "recomputes on reorder" -- verify the colors of positions
        // 0 and N-1 stay anchored to the gradient endpoints regardless of which
        // waypoints physically occupy them.
        WaypointGroup g = WaypointGroup.create("g", "z");
        for (int i = 0; i < 4; i++) g.add(Waypoint.at(i, 0, 0));

        int frontBefore = g.get(0).color();
        int backBefore  = g.get(3).color();

        g.move(0, 3); // swap front to back

        // The front slot is now occupied by a different waypoint, but its color
        // must equal what the front-of-gradient color was before.
        assertEquals(frontBefore, g.get(0).color(),
                "front slot color must follow the gradient, not the moved waypoint");
        assertEquals(backBefore, g.get(3).color(),
                "back slot color must follow the gradient, not the moved waypoint");
    }

    @Test
    void manualMode_doesNotRecolorOnReorder() {
        WaypointGroup g = WaypointGroup.create("g", "z");
        g.add(Waypoint.at(0, 0, 0).withColor(0xAABBCC));
        g.add(Waypoint.at(1, 0, 0).withColor(0x112233));
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);

        // Reapply explicit colors after switching modes (setGradientMode(AUTO)
        // would have rewritten them; MANUAL leaves them as-is).
        g.set(0, g.get(0).withColor(0xAABBCC));
        g.set(1, g.get(1).withColor(0x112233));

        g.move(0, 1);

        assertEquals(0xAABBCC, g.get(1).color(), "MANUAL mode must preserve per-waypoint colors across reorder");
        assertEquals(0x112233, g.get(0).color(), "MANUAL mode must preserve per-waypoint colors across reorder");
    }
}
