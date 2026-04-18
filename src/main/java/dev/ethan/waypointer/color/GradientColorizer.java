package dev.ethan.waypointer.color;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;

/**
 * Paints a {@link WaypointGroup}'s waypoints with an evenly-spaced HSL gradient
 * between the group's configured start and end colours.
 *
 * <p>We interpolate in HSL rather than RGB because RGB interpolation between two
 * saturated colours tends to pass through a desaturated muddy midpoint (cyan →
 * red in RGB goes through gray; in HSL it sweeps through blue/purple). HSL keeps
 * the intermediate waypoints looking like real colours.
 *
 * <p>Hue is interpolated the "short way" around the wheel so a gradient from red
 * (0°) to magenta (300°) goes 0 → 330 → 300 instead of 0 → 60 → 120 → ... →
 * 300. That matches what a user sees in a hue slider and avoids surprising
 * rainbow sweeps for palettes that should be subtle.
 *
 * <p>Waypoints flagged with {@link Waypoint#FLAG_LOCKED_COLOR} are skipped so users
 * can force a specific color on a critical waypoint without it being overwritten.
 */
public final class GradientColorizer {

    private GradientColorizer() {}

    /** Rewrites every unlocked waypoint in the group with a gradient-interpolated color. */
    public static void apply(WaypointGroup group) {
        int n = group.size();
        if (n == 0) return;

        float[] start = rgbToHsl(group.gradientStartColor());
        float[] end   = rgbToHsl(group.gradientEndColor());

        for (int i = 0; i < n; i++) {
            Waypoint w = group.get(i);
            if (w.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) continue;
            float t = n == 1 ? 0f : (float) i / (n - 1);
            float h = lerpHueShortWay(start[0], end[0], t);
            float s = lerp(start[1], end[1], t);
            float l = lerp(start[2], end[2], t);
            int rgb = hslToRgb(h, s, l);
            if (rgb != w.color()) group.set(i, w.withColor(rgb));
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Interpolate two hues in [0, 360) along the shorter arc of the colour wheel.
     * Without this, a gradient from red (0°) to magenta (300°) would sweep the
     * long way round through green and blue.
     */
    private static float lerpHueShortWay(float a, float b, float t) {
        float diff = b - a;
        if (diff > 180f)  diff -= 360f;
        if (diff < -180f) diff += 360f;
        float h = a + diff * t;
        if (h < 0f)    h += 360f;
        if (h >= 360f) h -= 360f;
        return h;
    }

    /** H in [0,360), S and L in [0,1]. Returns 0xRRGGBB. */
    public static int hslToRgb(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs((hp % 2f) - 1f));
        float r, g, b;
        if (hp < 1)      { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        float m = l - c / 2f;
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (clamp(ri) << 16) | (clamp(gi) << 8) | clamp(bi);
    }

    /** RGB (0xRRGGBB) → {H (0..360), S (0..1), L (0..1)}. */
    public static float[] rgbToHsl(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >>  8) & 0xFF) / 255f;
        float b = ( rgb        & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) * 0.5f;
        float delta = max - min;

        float h, s;
        if (delta < 1e-6f) {
            h = 0f;
            s = 0f;
        } else {
            s = l > 0.5f ? delta / (2f - max - min) : delta / (max + min);
            if (max == r)      h = ((g - b) / delta) + (g < b ? 6f : 0f);
            else if (max == g) h = ((b - r) / delta) + 2f;
            else               h = ((r - g) / delta) + 4f;
            h *= 60f;
        }
        return new float[] { h, s, l };
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
