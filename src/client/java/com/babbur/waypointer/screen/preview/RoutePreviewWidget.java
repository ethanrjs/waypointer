package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.screen.GuiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class RoutePreviewWidget extends AbstractWidget {

    public static final int HEADER_HEIGHT = 32;
    static final int SINGLE_LINE_HEADER_HEIGHT = 22;
    public static final int NAV_BUTTON_W = 18;
    public static final int NAV_BUTTON_INSET = 4;
    public static final int NAV_BUTTON_Y_OFFSET = 6;
    public static final int ZOOM_BUTTON_W = 34;
    public static final int ZOOM_BUTTON_H = 14;
    public static final int ZOOM_BUTTON_INSET = 4;

    private static final int NAV_RESERVE = NAV_BUTTON_INSET + NAV_BUTTON_W + 4;

    private static final int LABEL_PAD = 4;
    private static final int BORDER = 0xFF20252B;
    private static final int BACKGROUND = 0xD0121519;
    private static final int VIEWPORT = 0x30000000;
    private static final int RULE = 0x24FFFFFF;
    private static final int TEXT = GuiTokens.TEXT;
    private static final int TEXT_DIM = GuiTokens.TEXT_DIM;
    private static final int TEXT_MUTED = GuiTokens.TEXT_MUTED;
    private static final int NOTICE = GuiTokens.WARNING;

    private final RoutePreviewOrbit orbit;
    private final RoutePreviewZoom zoom;
    private final RoutePreviewPaintResource paintResources = new RoutePreviewPaintResource();
    private final RoutePreviewAvailability availability = new RoutePreviewAvailability();
    private RoutePreviewScene scene;
    private String routeName;
    private String routeCounter;
    private boolean navigationVisible;
    private int hoveredIndex = -1;
    private double currentYaw = Math.toRadians(RoutePreviewOrbit.START_YAW_DEGREES);
    private double fitScale = 1.0;
    private double depthEnvelope;
    private int fitWidth = -1;
    private int fitHeight = -1;
    private int lastMouseX;
    private int lastMouseY;
    private boolean hoverPickingEnabled = true;
    private boolean headerDetailVisible = true;
    private boolean selfOcclusion = true;
    private double preferredPixelsPerBlock = Double.NaN;

    public RoutePreviewWidget(int x, int y, int width, int height,
                              RoutePreviewScene scene, String routeCounter,
                              RoutePreviewOrbit orbit, RoutePreviewZoom zoom) {
        super(x, y, width, height, Component.empty());
        availability.beginScene(scene.routeId());
        this.scene = scene.preparePaintResource(paintResources);
        this.routeName = this.scene.routeName();
        this.routeCounter = routeCounter;
        this.orbit = orbit;
        this.zoom = zoom;
        this.depthEnvelope = RoutePreviewProjection.rotationSafeDepthEnvelope(this.scene);
        this.active = false;
    }

    public void setScene(RoutePreviewScene nextScene, String nextCounter) {
        availability.beginScene(nextScene.routeId());
        this.scene = nextScene.preparePaintResource(paintResources);
        this.routeName = this.scene.routeName();
        this.routeCounter = nextCounter;
        this.hoveredIndex = -1;
        this.zoom.reset();
        this.depthEnvelope = RoutePreviewProjection.rotationSafeDepthEnvelope(this.scene);
        this.fitWidth = -1;
        this.fitHeight = -1;
    }

    public void setRouteCounter(String nextCounter) {
        this.routeCounter = nextCounter;
    }

    public void setRouteName(String nextName) {
        this.routeName = nextName == null || nextName.isBlank() ? scene.routeName() : nextName;
    }

    public void setNavigationVisible(boolean shown) {
        this.navigationVisible = shown;
    }

    public void setHoverPickingEnabled(boolean enabled) {
        this.hoverPickingEnabled = enabled;
        if (!enabled) hoveredIndex = -1;
    }

    public void setHeaderDetailVisible(boolean visible) {
        headerDetailVisible = visible;
    }

    public void setSelfOcclusion(boolean enabled) {
        selfOcclusion = enabled;
    }

    int headerHeight() {
        return headerDetailVisible ? HEADER_HEIGHT : SINGLE_LINE_HEADER_HEIGHT;
    }

    public void setPreferredPixelsPerBlock(double pixels) {
        preferredPixelsPerBlock = Double.isFinite(pixels) && pixels > 0.0
                ? pixels : Double.NaN;
        fitWidth = -1;
        fitHeight = -1;
    }

    public void setPreviewVisible(boolean shown) {
        if (!shown) orbit.update(System.nanoTime(), false);
        this.visible = shown;
        this.active = false;
    }

    public void releaseResources() {
        paintResources.close();
        availability.reset();
    }

    public void advancePaintResourceFrame() {
        paintResources.advanceFrame();
    }

    public void pauseOrbit() {
        orbit.update(System.nanoTime(), false);
    }

    public int hoveredWaypointIndex() {
        return hoveredIndex;
    }

    public boolean zoomed() {
        return zoom.factor() != RoutePreviewZoom.DEFAULT_FACTOR;
    }

    public String zoomLabel() {
        return zoomFactorText(zoom.factor());
    }

    public void resetZoom() {
        zoom.reset();
    }

    public boolean scrollZoom(double mouseX, double mouseY, double verticalAmount) {
        int headerHeight = headerHeight();
        int renderX = getX() + 2;
        int renderY = getY() + headerHeight;
        int renderW = Math.max(1, getWidth() - 4);
        int renderH = Math.max(1, getHeight() - headerHeight - 2);
        if (!visible
                || !contains(mouseX, mouseY, renderX, renderY, renderW, renderH)
                || !Double.isFinite(verticalAmount)
                || verticalAmount == 0.0) {
            return false;
        }
        zoom.scroll(verticalAmount);
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                            float partial) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + getWidth();
        int y2 = y1 + getHeight();
        g.fill(x1, y1, x2, y2, BORDER);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, BACKGROUND);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, RULE);

        var font = Minecraft.getInstance().font;
        int headerHeight = headerHeight();
        int headerWidth = headerTextWidth(getWidth(), navigationVisible);
        String clippedName = font.plainSubstrByWidth(routeName, headerWidth);
        int[] lineY = headerLineY(y1, headerHeight, font.lineHeight, headerDetailVisible);
        g.text(font, clippedName, x1 + (getWidth() - font.width(clippedName)) / 2,
                lineY[0], TEXT, false);
        if (headerDetailVisible) {
            String waypointText = font.plainSubstrByWidth(
                    waypointCountText(scene.markers().size()), headerWidth);
            if (routeCounter == null || routeCounter.isBlank()) {
                g.text(font, waypointText,
                        x1 + (getWidth() - font.width(waypointText)) / 2,
                        lineY[1], TEXT_DIM, false);
            } else {
                int detailLeft = x1 + (navigationVisible ? NAV_RESERVE : 4);
                int detailRight = x2 - (navigationVisible ? NAV_RESERVE : 4);
                g.text(font, waypointText, detailLeft, lineY[1], TEXT_DIM, false);
                String clippedCounter = font.plainSubstrByWidth(routeCounter, headerWidth / 2);
                g.text(font, clippedCounter, detailRight - font.width(clippedCounter),
                        lineY[1], TEXT_DIM, false);
            }
        }
        g.fill(x1 + 1, y1 + headerHeight - 1, x2 - 1, y1 + headerHeight, RULE);

        int renderX = x1 + 2;
        int renderY = y1 + headerHeight;
        int renderW = Math.max(1, getWidth() - 4);
        int renderH = Math.max(1, getHeight() - headerHeight - 2);
        g.fill(renderX, renderY, renderX + renderW, renderY + renderH, VIEWPORT);
        if (fitWidth != renderW || fitHeight != renderH) {
            double safeScale = RoutePreviewProjection.viewportSafeScale(
                    scene, renderW, renderH);
            fitScale = Double.isFinite(preferredPixelsPerBlock)
                    ? Math.min(preferredPixelsPerBlock, safeScale)
                    : RoutePreviewProjection.rotationSafeScale(scene, renderW, renderH);
            fitWidth = renderW;
            fitHeight = renderH;
        }
        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double scale = Math.min(
                fitScale * zoom.factor(),
                RoutePreviewProjection.depthSafeScale(depthEnvelope, guiScale));
        boolean pointerInside = hoverPickingEnabled
                && contains(mouseX, mouseY, renderX, renderY, renderW, renderH);
        boolean paused = shouldPauseOrbit(pointerInside, hoveredIndex, visible,
                Minecraft.getInstance().isWindowActive());
        currentYaw = orbit.update(System.nanoTime(), !paused);
        hoveredIndex = pointerInside
                ? RoutePreviewProjection.pick(scene, mouseX, mouseY,
                        renderX, renderY, renderW, renderH, currentYaw, scale, guiScale)
                : -1;
        if (availability.unavailable()) {
            drawPlaceholder(g, renderY, renderH,
                    Component.translatable(
                            "waypointer.screen.export.preview.unavailable").getString(),
                    Component.translatable(
                            "waypointer.screen.export.preview.unavailable.detail").getString(),
                    NOTICE);
        } else if (scene.markers().isEmpty()) {
            drawPlaceholder(g, renderY, renderH,
                    Component.translatable(
                            "waypointer.screen.export.preview.empty").getString(),
                    Component.translatable(
                            "waypointer.screen.export.preview.empty.detail").getString(),
                    TEXT_DIM);
        } else {
            ScreenRectangle scissor = new ScreenRectangle(renderX, renderY, renderW, renderH);
            RoutePreviewGuiBridge.submit(g, RoutePreviewRenderState.create(
                    scene, (float) currentYaw, hoveredWaypointIndex(),
                    renderX, renderY, renderX + renderW, renderY + renderH,
                    (float) scale, guiScale, selfOcclusion, availability, scissor));
        }

        g.nextStratum();
        drawNoticesAndLabel(g, renderX, renderY, renderW, renderH);
    }

    private void drawNoticesAndLabel(GuiGraphicsExtractor g, int renderX, int renderY,
                                     int renderW, int renderH) {
        var font = Minecraft.getInstance().font;
        int stripY = renderY + renderH - font.lineHeight - 4;

        int hintWidth = 0;
        if (showsScrollHint()) {
            String hint = Component.translatable(
                    "waypointer.screen.export.preview.hint").getString();
            String clipped = font.plainSubstrByWidth(hint, Math.max(0, renderW - 8));
            hintWidth = font.width(clipped);
            g.text(font, clipped, renderX + renderW - 4 - hintWidth, stripY, TEXT_MUTED, false);
        }

        int noticeWidth = Math.max(0, renderW - 8 - (hintWidth == 0 ? 0 : hintWidth + 8));
        int noticeY = stripY;
        if (scene.paintUnavailable()) {
            String notice = Component.translatable(
                    "waypointer.screen.export.preview.paint_unavailable").getString();
            g.text(font, font.plainSubstrByWidth(notice, noticeWidth),
                    renderX + 4, noticeY, NOTICE, false);
            noticeY -= font.lineHeight + 2;
        }
        if (scene.simplified()) {
            String notice = Component.translatable(
                    "waypointer.screen.export.preview.simplified").getString();
            g.text(font, font.plainSubstrByWidth(notice, noticeWidth),
                    renderX + 4, noticeY, NOTICE, false);
        }

        int hovered = hoveredWaypointIndex();
        if (hovered < 0 || hovered >= scene.markers().size()) return;
        RoutePreviewScene.Marker marker = scene.markers().get(hovered);
        List<String> lines = labelLines(marker);
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, font.width(line));
        int labelW = Math.max(1, Math.min(renderW - 8, widest + LABEL_PAD * 2));
        int labelH = lines.size() * (font.lineHeight + 1) + LABEL_PAD * 2 - 1;
        int labelX = Math.clamp(lastMouseX + 8, renderX + 4,
                Math.max(renderX + 4, renderX + renderW - labelW - 4));
        int labelY = Math.clamp(lastMouseY + 8, renderY + 4,
                Math.max(renderY + 4,
                        renderY + renderH - labelH - ZOOM_BUTTON_H - ZOOM_BUTTON_INSET * 2));
        g.fill(labelX, labelY, labelX + labelW, labelY + labelH,
                GuiTokens.OVERLAY);
        g.fill(labelX, labelY, labelX + 2, labelY + labelH,
                GuiTokens.ACCENT);
        int textY = labelY + LABEL_PAD;
        for (int i = 0; i < lines.size(); i++) {
            String clipped = font.plainSubstrByWidth(lines.get(i), labelW - LABEL_PAD * 2);
            g.text(font, clipped, labelX + LABEL_PAD, textY,
                    i == 0 ? TEXT : TEXT_DIM, false);
            textY += font.lineHeight + 1;
        }
    }

    static List<String> labelLines(RoutePreviewScene.Marker marker) {
        List<String> lines = new ArrayList<>(3);
        if (marker.name() != null && !marker.name().isBlank()) lines.add(marker.name());
        lines.add("Waypoint " + marker.displayIndex());
        lines.add(marker.coordinateText());
        return lines;
    }

    static String waypointCountText(int count) {
        int safeCount = Math.max(0, count);
        return "(" + safeCount + " waypoint" + (safeCount == 1 ? "" : "s") + ")";
    }

    static int[] headerLineY(
            int top, int headerHeight, int lineHeight, boolean detailVisible) {
        if (!detailVisible) {
            return new int[]{top + (headerHeight - lineHeight) / 2};
        }
        int gap = 3;
        int blockHeight = lineHeight * 2 + gap;
        int blockTop = top + Math.max(0, (headerHeight - blockHeight) / 2);
        return new int[]{blockTop, blockTop + lineHeight + gap};
    }

    static int headerTextWidth(int widgetWidth, boolean navigationVisible) {
        int reserve = navigationVisible ? NAV_RESERVE : 4;
        return Math.max(0, widgetWidth - reserve * 2);
    }

    public static String zoomFactorText(double factor) {
        return String.format(java.util.Locale.ROOT, "%.1f", factor);
    }

    static boolean shouldPauseOrbit(boolean pointerInside, int previousHoveredIndex,
                                    boolean visible, boolean windowActive) {
        return (pointerInside && previousHoveredIndex >= 0) || !visible || !windowActive;
    }

    private boolean showsScrollHint() {
        return hoverPickingEnabled
                && !scene.markers().isEmpty()
                && hoveredIndex < 0
                && !availability.unavailable()
                && zoom.factor() == RoutePreviewZoom.DEFAULT_FACTOR
                && contains(lastMouseX, lastMouseY,
                        getX() + 2, getY() + headerHeight(),
                        Math.max(1, getWidth() - 4),
                        Math.max(1, getHeight() - headerHeight() - 2));
    }

    private void drawPlaceholder(GuiGraphicsExtractor g, int renderY, int renderH,
                                 String headline, String detail, int headlineColor) {
        var font = Minecraft.getInstance().font;
        int top = renderY + (renderH - (font.lineHeight * 2 + 3)) / 2;
        drawCentered(g, headline, top, headlineColor);
        drawCentered(g, detail, top + font.lineHeight + 3, TEXT_MUTED);
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int y, int color) {
        var font = Minecraft.getInstance().font;
        String clipped = font.plainSubstrByWidth(text, Math.max(0, getWidth() - 16));
        g.text(font, clipped, getX() + (getWidth() - font.width(clipped)) / 2, y, color, false);
    }

    private static boolean contains(double mouseX, double mouseY,
                                    int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
