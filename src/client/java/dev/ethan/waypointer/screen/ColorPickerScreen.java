package dev.ethan.waypointer.screen;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ethan.waypointer.Waypointer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Modal colour picker. HSV saturation/value square on the left, hue slider on the
 * right, hex input under both, and a preview swatch + Save/Cancel footer.
 *
 * <p>Re-used by the gradient-endpoint swatches in {@link GroupEditScreen} and by
 * per-waypoint colour overrides. A single picker component means both flows feel
 * identical, and there is one place to fix bugs.
 *
 * <p>Editing model: the picker keeps its own working HSV state so dragging never
 * lerps through quantization artefacts from integer RGB round-trips. Only on
 * "Save" does the caller receive the final 0xRRGGBB value; "Cancel" is a true
 * no-op.
 *
 * <h3>Performance</h3>
 * The SV square and hue slider used to be drawn with ~20k {@code g.fill()} calls
 * per frame (one per pixel), which tanked framerate noticeably. They now render
 * to {@link DynamicTexture}s and are blitted as a single quad each. The SV
 * texture regenerates only when hue changes; the hue texture is built once and
 * reused. Both are released on {@link #removed()}.
 */
public final class ColorPickerScreen extends Screen {

    // Panel sizing is fixed so the layout stays predictable across GUI scales; the
    // parent is centered on the screen and the picker grid is centered in the panel.
    // PANEL_H was 220 before, which caused the hex field (bottom=200 rel) and the
    // footer buttons (top=192 rel) to overlap by ~8px. Bumped so there is a clean
    // gap between the hex row and the footer.
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 252;
    private static final int SV_SIZE = 140;
    private static final int HUE_W   = 18;

    // ResourceLocations for the picker's two dynamic textures. A per-instance
    // suffix keeps two simultaneous pickers (e.g. a quickly swapped-open one)
    // from sharing a texture slot; in practice only one exists at a time, but
    // paying the few-bytes cost here is cheaper than chasing a stale-texture bug.
    private static final AtomicLong INSTANCE_SEQ = new AtomicLong();

    private final Screen parent;
    private final String title;
    private final IntConsumer onPicked;

    // Working HSV state. Kept in floats so mid-drag updates are smooth even when
    // the resulting RGB would snap the SV position back into the visible box.
    private float hue;       // [0, 360)
    private float sat;       // [0, 1]
    private float value;     // [0, 1]

    private EditBox hexBox;
    private int svX, svY, hueX, hueY, swatchX, swatchY;

    private DynamicTexture svTex;
    private DynamicTexture hueTex;
    private Identifier svTexId;
    private Identifier hueTexId;
    private float svTexBakedHue = -1f; // hue used when svTex was last filled; triggers re-upload when drifted

    // Whether a drag started on the SV square vs the hue slider. Without this, a
    // drag that starts on the SV box and wanders out would silently switch to
    // editing the hue, which feels broken.
    private enum Drag { NONE, SV, HUE }
    private Drag drag = Drag.NONE;

    public ColorPickerScreen(Screen parent, String title, int initialRgb, IntConsumer onPicked) {
        super(Component.literal(title));
        this.parent = parent;
        this.title = title;
        this.onPicked = onPicked;
        float[] hsv = rgbToHsv(initialRgb & 0xFFFFFF);
        this.hue = hsv[0];
        this.sat = hsv[1];
        this.value = hsv[2];
    }

    /** Convenience opener so call sites read like "pick a colour → here's what to do". */
    public static void open(Screen parent, String title, int initialRgb, IntConsumer onPicked) {
        Minecraft.getInstance().setScreen(new ColorPickerScreen(parent, title, initialRgb, onPicked));
    }

    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        svX = panelX + PAD_OUTER;
        svY = panelY + 32;
        hueX = svX + SV_SIZE + GAP;
        hueY = svY;
        swatchX = hueX + HUE_W + GAP;
        swatchY = svY;

        int hexY = svY + SV_SIZE + GAP;
        hexBox = new EditBox(font, svX, hexY, SV_SIZE, BTN_H, Component.literal("Hex"));
        hexBox.setMaxLength(7);
        hexBox.setValue(String.format("#%06X", currentRgb()));
        hexBox.setResponder(v -> {
            String cleaned = v.startsWith("#") ? v.substring(1) : v;
            if (cleaned.length() != 6) return;
            try {
                int rgb = Integer.parseInt(cleaned, 16);
                float[] hsv = rgbToHsv(rgb);
                hue = hsv[0];
                sat = hsv[1];
                value = hsv[2];
            } catch (NumberFormatException ignored) {
                // Swallowed: partial edits are allowed without clobbering the swatch.
            }
        });
        addRenderableWidget(hexBox);

        // Footer row lives below the hex field with a visible gap. The old layout
        // overlapped them because PANEL_H was too tight; PANEL_H was bumped and
        // the footer is anchored to the bottom of the panel.
        int footerY = panelY + PANEL_H - BTN_H - PAD_OUTER;
        int btnW = 70;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + PANEL_W - PAD_OUTER - btnW * 2 - GAP, footerY, btnW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            onPicked.accept(currentRgb());
            onClose();
        }).bounds(panelX + PANEL_W - PAD_OUTER - btnW, footerY, btnW, BTN_H).build());

        ensureTextures();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Dim the world behind so the picker reads as a modal; matches the other
        // Waypointer screens which use SURFACE as their backdrop.
        g.fill(0, 0, width, height, 0x80000000);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, SURFACE);

        g.drawString(font, Component.literal(title), panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);

        drawSvSquare(g);
        drawHueSlider(g);
        drawSwatch(g);

        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * SV square blitted from a cached {@link DynamicTexture}; the pixel grid is
     * regenerated only when the hue changes. The crosshair marks the current
     * (sat, value) sample.
     */
    private void drawSvSquare(GuiGraphics g) {
        if (svTex == null) return;
        if (Math.abs(hue - svTexBakedHue) > 0.01f) {
            bakeSvTexture(hue);
            svTexBakedHue = hue;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, svTexId, svX, svY, 0f, 0f, SV_SIZE, SV_SIZE, SV_SIZE, SV_SIZE);

        int mx = svX + Math.round(sat * (SV_SIZE - 1));
        int my = svY + Math.round((1f - value) * (SV_SIZE - 1));
        // White crosshair with black outline keeps the cursor visible on both
        // dark and bright parts of the SV field.
        g.fill(mx - 4, my, mx + 5, my + 1, 0xFF000000);
        g.fill(mx, my - 4, mx + 1, my + 5, 0xFF000000);
        g.fill(mx - 3, my, mx + 4, my + 1, 0xFFFFFFFF);
        g.fill(mx, my - 3, mx + 1, my + 4, 0xFFFFFFFF);
    }

    private void drawHueSlider(GuiGraphics g) {
        if (hueTex == null) return;
        g.blit(RenderPipelines.GUI_TEXTURED, hueTexId, hueX, hueY, 0f, 0f, HUE_W, SV_SIZE, HUE_W, SV_SIZE);

        int hy = hueY + Math.round(hue / 360f * (SV_SIZE - 1));
        g.fill(hueX - 2, hy,     hueX + HUE_W + 2, hy + 1, 0xFF000000);
        g.fill(hueX - 2, hy + 1, hueX + HUE_W + 2, hy + 2, 0xFFFFFFFF);
    }

    private void drawSwatch(GuiGraphics g) {
        int rgb = currentRgb();
        int sw = 48, sh = 48;
        // Border ring so a fully-white or fully-black pick is still visible.
        g.fill(swatchX - 1, swatchY - 1, swatchX + sw + 1, swatchY + sh + 1, 0xFF000000);
        g.fill(swatchX, swatchY, swatchX + sw, swatchY + sh, 0xFF000000 | rgb);
        String hex = String.format("#%06X", rgb);
        g.drawString(font, hex, swatchX, swatchY + sh + 4, TEXT_DIM, false);
    }

    // --- texture management -------------------------------------------------------------------

    private void ensureTextures() {
        if (svTex != null && hueTex != null) return;

        long seq = INSTANCE_SEQ.incrementAndGet();
        svTexId  = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "picker_sv_" + seq);
        hueTexId = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "picker_hue_" + seq);

        // Both textures live in RGBA; setPixelABGR expects 0xAABBGGRR-packed ints,
        // which matches what the NativeImage format wants even though the naming
        // is confusing.
        svTex = new DynamicTexture(() -> "waypointer_picker_sv", SV_SIZE, SV_SIZE, false);
        hueTex = new DynamicTexture(() -> "waypointer_picker_hue", HUE_W, SV_SIZE, false);

        Minecraft.getInstance().getTextureManager().register(svTexId, svTex);
        Minecraft.getInstance().getTextureManager().register(hueTexId, hueTex);

        bakeHueTexture();
        bakeSvTexture(hue);
        svTexBakedHue = hue;
    }

    private void bakeSvTexture(float h) {
        NativeImage img = svTex.getPixels();
        if (img == null) return;
        for (int py = 0; py < SV_SIZE; py++) {
            float v = 1f - (float) py / (SV_SIZE - 1);
            for (int px = 0; px < SV_SIZE; px++) {
                float s = (float) px / (SV_SIZE - 1);
                int rgb = hsvToRgb(h, s, v);
                img.setPixelABGR(px, py, packAbgr(0xFF, rgb));
            }
        }
        svTex.upload();
    }

    private void bakeHueTexture() {
        NativeImage img = hueTex.getPixels();
        if (img == null) return;
        for (int py = 0; py < SV_SIZE; py++) {
            float h = 360f * py / (SV_SIZE - 1);
            int rgb = hsvToRgb(h, 1f, 1f);
            int abgr = packAbgr(0xFF, rgb);
            for (int px = 0; px < HUE_W; px++) {
                img.setPixelABGR(px, py, abgr);
            }
        }
        hueTex.upload();
    }

    /** Pack a 0xAARRGGBB + alpha into NativeImage's 0xAABBGGRR byte order. */
    private static int packAbgr(int alpha, int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >>  8) & 0xFF;
        int b =  rgb        & 0xFF;
        return (alpha << 24) | (b << 16) | (g << 8) | r;
    }

    @Override
    public void removed() {
        // The texture manager takes ownership of DynamicTextures registered with
        // it; release() both unbinds the identifier and closes the underlying
        // NativeImage, so we don't leak GPU memory when the picker closes.
        if (svTexId != null)  Minecraft.getInstance().getTextureManager().release(svTexId);
        if (hueTexId != null) Minecraft.getInstance().getTextureManager().release(hueTexId);
        svTex = null;
        hueTex = null;
        svTexId = null;
        hueTexId = null;
        super.removed();
    }

    // --- input --------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (event.button() == 0) {
            if (inside(mx, my, svX, svY, SV_SIZE, SV_SIZE)) {
                drag = Drag.SV;
                updateSv(mx, my);
                return true;
            }
            if (inside(mx, my, hueX, hueY, HUE_W, SV_SIZE)) {
                drag = Drag.HUE;
                updateHue(my);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x(), my = event.y();
        if (drag == Drag.SV)  { updateSv(mx, my); return true; }
        if (drag == Drag.HUE) { updateHue(my);    return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        drag = Drag.NONE;
        return super.mouseReleased(event);
    }

    private void updateSv(double mx, double my) {
        float s = (float) clamp01((mx - svX) / (double) (SV_SIZE - 1));
        float v = 1f - (float) clamp01((my - svY) / (double) (SV_SIZE - 1));
        sat = s;
        value = v;
        syncHex();
    }

    private void updateHue(double my) {
        hue = 360f * (float) clamp01((my - hueY) / (double) (SV_SIZE - 1));
        if (hue >= 360f) hue = 359.9999f;
        syncHex();
    }

    private void syncHex() {
        if (hexBox != null) hexBox.setValue(String.format("#%06X", currentRgb()));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private int currentRgb() {
        return hsvToRgb(hue, sat, value);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static double clamp01(double v) {
        return Math.max(0d, Math.min(1d, v));
    }

    // HSV ↔ RGB kept local (and not pushed into GradientColorizer) because the
    // picker is the only code path that needs HSV; every other site thinks in
    // HSL. Using the same model Photoshop / web pickers use makes the widget
    // feel familiar.

    /** H in [0,360), S/V in [0,1]. Returns 0xRRGGBB. */
    public static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs((hp % 2f) - 1f));
        float r, g, b;
        if (hp < 1)      { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        float m = v - c;
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (clamp255(ri) << 16) | (clamp255(gi) << 8) | clamp255(bi);
    }

    public static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >>  8) & 0xFF) / 255f;
        float b = ( rgb        & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta < 1e-6f)      h = 0f;
        else if (max == r)      h = 60f * (((g - b) / delta) % 6f);
        else if (max == g)      h = 60f * (((b - r) / delta) + 2f);
        else                    h = 60f * (((r - g) / delta) + 4f);
        if (h < 0f) h += 360f;
        float s = max < 1e-6f ? 0f : delta / max;
        return new float[] { h, s, max };
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
