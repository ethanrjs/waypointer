package dev.ethan.waypointer.color;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;

/**
 * Paints a {@link WaypointGroup}'s waypoints with an evenly-spaced HSL gradient.
 *
 * Hue sweeps from cyan (~180 deg) through purple to red (~360 deg) across the list,
 * so the "next" waypoint is always visually the coolest color and the final one
 * stands out as hot. This matches how most players already think about route
 * progress: start calm, end urgent.
 *
 * Waypoints flagged with {@link Waypoint#FLAG_LOCKED_COLOR} are skipped so users
 * can force a specific color on a critical waypoint without it being overwritten.
 */
public final class GradientColorizer {

    public static final float HUE_START = 180f; // cyan
    public static final float HUE_END   = 360f; // wraps back to red

    private GradientColorizer() {}

    /** Rewrites every unlocked waypoint in the group with a gradient-interpolated color. */
    public static void apply(WaypointGroup group) {
        int n = group.size();
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            Waypoint w = group.get(i);
            if (w.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) continue;
            float t = n == 1 ? 0f : (float) i / (n - 1);
            float hue = lerp(HUE_START, HUE_END, t) % 360f;
            int rgb = hslToRgb(hue, 0.85f, 0.55f);
            if (rgb != w.color()) group.set(i, w.withColor(rgb));
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
