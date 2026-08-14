package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static com.babbur.waypointer.screen.GuiTokens.*;

public final class ColorPickerScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 252;
    private static final int PANEL_H_WITH_OPTION = 280;
    private static final int SV_SIZE = 140;
    private static final int HUE_W   = 18;

    private static final AtomicLong INSTANCE_SEQ = new AtomicLong();

    private final Screen parent;
    private final Component pickerTitle;
    private final IntConsumer onPicked;
    private final WaypointColorPickerState waypointState;

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

    private enum Drag { NONE, SV, HUE }
    private Drag drag = Drag.NONE;

    public ColorPickerScreen(Screen parent, String title, int initialRgb, IntConsumer onPicked) {
        this(parent, Component.literal(title), initialRgb, onPicked);
    }

    public ColorPickerScreen(Screen parent, Component title, int initialRgb, IntConsumer onPicked) {
        this(parent, title, initialRgb, onPicked, null);
    }

    private ColorPickerScreen(Screen parent, Component title, int initialRgb,
                              IntConsumer onPicked, WaypointColorPickerState waypointState) {
        super(title);
        this.parent = parent;
        this.pickerTitle = title;
        this.onPicked = onPicked;
        this.waypointState = waypointState;
        float[] hsv = rgbToHsv(initialRgb & 0xFFFFFF);
        this.hue = hsv[0];
        this.sat = hsv[1];
        this.value = hsv[2];
    }

    public static void open(Screen parent, String title, int initialRgb, IntConsumer onPicked) {
        open(parent, Component.literal(title), initialRgb, onPicked);
    }

    public static void open(Screen parent, Component title, int initialRgb, IntConsumer onPicked) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ColorPickerScreen(parent, title, initialRgb, onPicked));
    }

    static void openWaypoint(Screen parent, Component title, int initialRgb,
                             WaypointColorPickerState state, IntConsumer onPicked) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ColorPickerScreen(parent, title, initialRgb, onPicked, state));
    }

    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelH = panelHeight();
        int panelY = (height - panelH) / 2;

        svX = panelX + PAD_OUTER;
        svY = panelY + 32;
        hueX = svX + SV_SIZE + GAP;
        hueY = svY;
        swatchX = hueX + HUE_W + GAP;
        swatchY = svY;

        int hexY = svY + SV_SIZE + GAP;
        hexBox = new EditBox(font, svX, hexY, SV_SIZE, BTN_H,
                Component.translatable("waypointer.screen.color_picker.hex"));
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
            }
        });
        addRenderableWidget(hexBox);

        if (showsApplyToSubwaypoints()) {
            int optionY = hexY + BTN_H + GAP_TIGHT;
            Component optionLabel = Component.translatable(
                    "waypointer.screen.color_picker.apply_to_subwaypoints");
            addRenderableWidget(styledCheckbox(
                    svX, optionY, BTN_H, optionLabel,
                    waypointState.applyToSubwaypoints(),
                    waypointState::setApplyToSubwaypoints,
                    Tooltip.create(Component.translatable(
                            "waypointer.screen.color_picker.apply_to_subwaypoints.tooltip"))));
        }

        int footerY = panelY + panelH - BTN_H - PAD_OUTER;
        int btnW = 70;
        addRenderableWidget(styledButton(
                panelX + PANEL_W - PAD_OUTER - btnW * 2 - GAP,
                footerY, btnW, BTN_H,
                Component.translatable("gui.cancel"), b -> onClose(), null));
        addRenderableWidget(styledButton(
                panelX + PANEL_W - PAD_OUTER - btnW,
                footerY, btnW, BTN_H,
                Component.translatable("waypointer.screen.color_picker.save"), b -> {
                    onPicked.accept(currentRgb());
                    onClose();
                }, null));

        ensureTextures();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);

        int panelX = (width - PANEL_W) / 2;
        int panelH = panelHeight();
        int panelY = (height - panelH) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, SURFACE);

        g.text(font, pickerTitle, panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);

        drawSvSquare(g);
        drawHueSlider(g);
        drawSwatch(g);

        if (showsApplyToSubwaypoints()) {
            int labelX = svX + BTN_H + GAP_TIGHT;
            int labelY = opticalTextY(svY + SV_SIZE + GAP + BTN_H + GAP_TIGHT, BTN_H);
            Component label = Component.translatable(
                    "waypointer.screen.color_picker.apply_to_subwaypoints");
            String clipped = font.plainSubstrByWidth(
                    label.getString(), panelX + PANEL_W - PAD_OUTER - labelX);
            g.text(font, clipped, labelX, labelY, TEXT, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    static int panelHeight(boolean showApplyToSubwaypoints) {
        return showApplyToSubwaypoints ? PANEL_H_WITH_OPTION : PANEL_H;
    }

    private int panelHeight() {
        return panelHeight(showsApplyToSubwaypoints());
    }

    private boolean showsApplyToSubwaypoints() {
        return waypointState != null && waypointState.applyToSubwaypointsVisible();
    }

    private void drawSvSquare(GuiGraphicsExtractor g) {
        if (svTex == null) return;
        if (Math.abs(hue - svTexBakedHue) > 0.01f) {
            bakeSvTexture(hue);
            svTexBakedHue = hue;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, svTexId, svX, svY, 0f, 0f, SV_SIZE, SV_SIZE, SV_SIZE, SV_SIZE);

        int mx = svX + Math.round(sat * (SV_SIZE - 1));
        int my = svY + Math.round((1f - value) * (SV_SIZE - 1));
        g.fill(mx - 4, my, mx + 5, my + 1, 0xFF000000);
        g.fill(mx, my - 4, mx + 1, my + 5, 0xFF000000);
        g.fill(mx - 3, my, mx + 4, my + 1, 0xFFFFFFFF);
        g.fill(mx, my - 3, mx + 1, my + 4, 0xFFFFFFFF);
    }

    private void drawHueSlider(GuiGraphicsExtractor g) {
        if (hueTex == null) return;
        g.blit(RenderPipelines.GUI_TEXTURED, hueTexId, hueX, hueY, 0f, 0f, HUE_W, SV_SIZE, HUE_W, SV_SIZE);

        int hy = hueY + Math.round(hue / 360f * (SV_SIZE - 1));
        g.fill(hueX - 2, hy,     hueX + HUE_W + 2, hy + 1, 0xFF000000);
        g.fill(hueX - 2, hy + 1, hueX + HUE_W + 2, hy + 2, 0xFFFFFFFF);
    }

    private void drawSwatch(GuiGraphicsExtractor g) {
        int rgb = currentRgb();
        int sw = 48, sh = 48;
        g.fill(swatchX - 1, swatchY - 1, swatchX + sw + 1, swatchY + sh + 1, 0xFF000000);
        g.fill(swatchX, swatchY, swatchX + sw, swatchY + sh, 0xFF000000 | rgb);
        String hex = String.format("#%06X", rgb);
        g.text(font, hex, swatchX, swatchY + sh + 4, TEXT_DIM, false);
    }


    private void ensureTextures() {
        if (svTex != null && hueTex != null) return;

        long seq = INSTANCE_SEQ.incrementAndGet();
        svTexId  = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "picker_sv_" + seq);
        hueTexId = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "picker_hue_" + seq);

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

    private static int packAbgr(int alpha, int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >>  8) & 0xFF;
        int b =  rgb        & 0xFF;
        return (alpha << 24) | (b << 16) | (g << 8) | r;
    }

    @Override
    public void removed() {
        if (svTexId != null)  Minecraft.getInstance().getTextureManager().release(svTexId);
        if (hueTexId != null) Minecraft.getInstance().getTextureManager().release(hueTexId);
        svTex = null;
        hueTex = null;
        svTexId = null;
        hueTexId = null;
        super.removed();
    }


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
        MinecraftCompat.setScreen(minecraft, parent);
    }

    private int currentRgb() {
        return hsvToRgb(hue, sat, value);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static double clamp01(double v) {
        return MathUtil.clamp(v, 0d, 1d);
    }


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
        return MathUtil.clampByte(v);
    }
}
