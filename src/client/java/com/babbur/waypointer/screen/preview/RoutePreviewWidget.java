package com.babbur.waypointer.screen.preview;

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

/** Passive native-PiP route preview with exact hover picking and native-font labels. */
public final class RoutePreviewWidget extends AbstractWidget {

    private static final int HEADER_HEIGHT = 32;
    private static final int LABEL_PAD = 4;
    private static final int BORDER = 0xFF20252B;
    private static final int BACKGROUND = 0xD0121519;
    private static final int TEXT = 0xFFE6E9EC;
    private static final int TEXT_DIM = 0xFFAAB2BA;
    private static final int NOTICE = 0xFFFFB060;

    private final RoutePreviewOrbit orbit;
    private final RoutePreviewZoom zoom;
    private RoutePreviewScene scene;
    private String routeCounter;
    private int hoveredIndex = -1;
    private double currentYaw = Math.toRadians(RoutePreviewOrbit.START_YAW_DEGREES);
    private double fitScale = 1.0;
    private double depthEnvelope;
    private int fitWidth = -1;
    private int fitHeight = -1;
    private int lastMouseX;
    private int lastMouseY;

    public RoutePreviewWidget(int x, int y, int width, int height,
                              RoutePreviewScene scene, String routeCounter,
                              RoutePreviewOrbit orbit, RoutePreviewZoom zoom) {
        super(x, y, width, height, Component.empty());
        this.scene = scene;
        this.routeCounter = routeCounter;
        this.orbit = orbit;
        this.zoom = zoom;
        this.depthEnvelope = RoutePreviewProjection.rotationSafeDepthEnvelope(scene);
        this.active = false;
        RoutePreviewAvailability.beginScene(scene.routeId());
    }

    public void setScene(RoutePreviewScene nextScene, String nextCounter) {
        this.scene = nextScene;
        this.routeCounter = nextCounter;
        this.hoveredIndex = -1;
        this.zoom.reset();
        this.depthEnvelope = RoutePreviewProjection.rotationSafeDepthEnvelope(nextScene);
        this.fitWidth = -1;
        this.fitHeight = -1;
        RoutePreviewAvailability.beginScene(nextScene.routeId());
    }

    public void setRouteCounter(String nextCounter) {
        this.routeCounter = nextCounter;
    }

    public void setPreviewVisible(boolean shown) {
        if (!shown) orbit.update(System.nanoTime(), false);
        this.visible = shown;
        this.active = false;
    }

    public void releaseResources() {
        RoutePreviewPaintResource.close();
        RoutePreviewAvailability.reset();
    }

    public void pauseOrbit() {
        orbit.update(System.nanoTime(), false);
    }

    public int hoveredWaypointIndex() {
        return hoveredIndex;
    }

    /** Zooms around the fixed preview center without making this widget selectable. */
    public boolean scrollZoom(double mouseX, double mouseY, double verticalAmount) {
        int renderX = getX() + 2;
        int renderY = getY() + HEADER_HEIGHT;
        int renderW = Math.max(1, getWidth() - 4);
        int renderH = Math.max(1, getHeight() - HEADER_HEIGHT - 2);
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
        RoutePreviewPaintResource.advanceFrame();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + getWidth();
        int y2 = y1 + getHeight();
        g.fill(x1, y1, x2, y2, BORDER);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, BACKGROUND);

        var font = Minecraft.getInstance().font;
        int headerWidth = Math.max(0, getWidth() - 52);
        String clippedName = font.plainSubstrByWidth(scene.routeName(), headerWidth);
        String detail = waypointCountText(scene.markers().size())
                + (routeCounter.isBlank() ? "" : " · " + routeCounter);
        String clippedDetail = font.plainSubstrByWidth(detail, headerWidth);
        g.text(font, clippedName, x1 + (getWidth() - font.width(clippedName)) / 2,
                y1 + 5, TEXT, false);
        g.text(font, clippedDetail, x1 + (getWidth() - font.width(clippedDetail)) / 2,
                y1 + 17, TEXT_DIM, false);

        int renderX = x1 + 2;
        int renderY = y1 + HEADER_HEIGHT;
        int renderW = Math.max(1, getWidth() - 4);
        int renderH = Math.max(1, getHeight() - HEADER_HEIGHT - 2);
        if (fitWidth != renderW || fitHeight != renderH) {
            fitScale = RoutePreviewProjection.rotationSafeScale(scene, renderW, renderH);
            fitWidth = renderW;
            fitHeight = renderH;
        }
        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double scale = Math.min(
                fitScale * zoom.factor(),
                RoutePreviewProjection.depthSafeScale(depthEnvelope, guiScale));
        boolean pointerInside = contains(mouseX, mouseY, renderX, renderY, renderW, renderH);
        hoveredIndex = pointerInside
                ? RoutePreviewProjection.pick(scene, mouseX, mouseY,
                        renderX, renderY, renderW, renderH, currentYaw, scale)
                : -1;
        boolean paused = hoveredIndex >= 0
                || !visible || !Minecraft.getInstance().isWindowActive();
        currentYaw = orbit.update(System.nanoTime(), !paused);
        hoveredIndex = pointerInside
                ? RoutePreviewProjection.pick(scene, mouseX, mouseY,
                        renderX, renderY, renderW, renderH, currentYaw, scale)
                : -1;
        if (RoutePreviewAvailability.unavailable()) {
            drawCentered(g, Component.translatable(
                    "waypointer.screen.export.preview.unavailable").getString(),
                    renderY + renderH / 2 - 4, NOTICE);
        } else if (scene.markers().isEmpty()) {
            drawCentered(g, Component.translatable(
                    "waypointer.screen.export.preview.empty").getString(),
                    renderY + renderH / 2 - 4, TEXT_DIM);
        } else {
            ScreenRectangle scissor = new ScreenRectangle(renderX, renderY, renderW, renderH);
            RoutePreviewGuiBridge.submit(g, RoutePreviewRenderState.create(
                    scene, (float) currentYaw, hoveredWaypointIndex(),
                    renderX, renderY, renderX + renderW, renderY + renderH,
                    (float) scale, guiScale, scissor));
        }

        g.nextStratum();
        drawNoticesAndLabel(g, renderX, renderY, renderW, renderH);
    }

    private void drawNoticesAndLabel(GuiGraphicsExtractor g, int renderX, int renderY,
                                     int renderW, int renderH) {
        var font = Minecraft.getInstance().font;
        int noticeY = renderY + 3;
        if (scene.simplified()) {
            String notice = Component.translatable(
                    "waypointer.screen.export.preview.simplified").getString();
            g.text(font, font.plainSubstrByWidth(notice, renderW - 8),
                    renderX + 4, noticeY, NOTICE, false);
            noticeY += font.lineHeight + 2;
        }
        if (scene.paintUnavailable()) {
            String notice = Component.translatable(
                    "waypointer.screen.export.preview.paint_unavailable").getString();
            g.text(font, font.plainSubstrByWidth(notice, renderW - 8),
                    renderX + 4, noticeY, NOTICE, false);
        }

        int hovered = hoveredWaypointIndex();
        if (hovered < 0 || hovered >= scene.markers().size()) return;
        RoutePreviewScene.Marker marker = scene.markers().get(hovered);
        List<String> lines = labelLines(marker);
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, font.width(line));
        int labelW = Math.min(renderW - 8, widest + LABEL_PAD * 2);
        int labelH = lines.size() * (font.lineHeight + 1) + LABEL_PAD * 2 - 1;
        int desiredX = lastMouseX + 8;
        int desiredY = lastMouseY + 8;
        int labelX = Math.clamp(desiredX, renderX + 4, renderX + renderW - labelW - 4);
        int labelY = Math.clamp(desiredY, renderY + 4, renderY + renderH - labelH - 4);
        g.fill(labelX, labelY, labelX + labelW, labelY + labelH, 0xE0101317);
        g.fill(labelX, labelY, labelX + 1, labelY + labelH, 0xFF4FB3C4);
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
        // Passive previews are not part of focus or narration traversal.
    }
}
