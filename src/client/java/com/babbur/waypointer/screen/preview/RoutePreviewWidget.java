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

/**
 * Passive native-PiP route preview with exact hover picking and native-font labels.
 *
 * <p>The widget is two bands: a header that names the route being previewed and
 * a viewport that draws it. They are separated by a hairline rather than by
 * spacing alone, because the PiP render bleeds to the edge of its scissor and
 * without the rule the route appeared to float into the title.
 *
 * <p>Route navigation ({@code <} / {@code >}) and the zoom reset live in the
 * header and the viewport's bottom-right corner respectively, but they are
 * owned by the screen -- this widget is not focusable and takes no clicks, so
 * anything clickable has to be a sibling. What it does own is the reserve those
 * siblings need: {@link #setNavigationVisible(boolean)} tells the header how
 * much room to leave, so a single-route preview gets its full width back
 * instead of permanently budgeting for arrows that are not there.
 */
public final class RoutePreviewWidget extends AbstractWidget {

    /** Header band: two text lines, tall enough to hold a {@code BTN_H} nav button. */
    public static final int HEADER_HEIGHT = 32;
    /** Nav button geometry, shared with the screen that places the buttons. */
    public static final int NAV_BUTTON_W = 18;
    public static final int NAV_BUTTON_INSET = 4;
    public static final int NAV_BUTTON_Y_OFFSET = 6;
    /** Zoom-reset button geometry, anchored to the viewport's bottom-right. */
    public static final int ZOOM_BUTTON_W = 34;
    public static final int ZOOM_BUTTON_H = 14;
    public static final int ZOOM_BUTTON_INSET = 4;

    /** Horizontal room a header line must leave for one nav button. */
    private static final int NAV_RESERVE = NAV_BUTTON_INSET + NAV_BUTTON_W + 4;

    private static final int LABEL_PAD = 4;
    private static final int BORDER = 0xFF20252B;
    private static final int BACKGROUND = 0xD0121519;
    /** Darkens the viewport so the render band reads as a separate surface. */
    private static final int VIEWPORT = 0x30000000;
    /** Hairline under the header and the 1px top sheen on the outer frame. */
    private static final int RULE = 0x24FFFFFF;
    private static final int TEXT = 0xFFE6E9EC;
    private static final int TEXT_DIM = 0xFFAAB2BA;
    private static final int TEXT_MUTED = 0xFF7A828A;
    private static final int NOTICE = 0xFFFFB060;

    private final RoutePreviewOrbit orbit;
    private final RoutePreviewZoom zoom;
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

    public RoutePreviewWidget(int x, int y, int width, int height,
                              RoutePreviewScene scene, String routeCounter,
                              RoutePreviewOrbit orbit, RoutePreviewZoom zoom) {
        super(x, y, width, height, Component.empty());
        this.scene = scene;
        this.routeName = scene.routeName();
        this.routeCounter = routeCounter;
        this.orbit = orbit;
        this.zoom = zoom;
        this.depthEnvelope = RoutePreviewProjection.rotationSafeDepthEnvelope(scene);
        this.active = false;
        RoutePreviewAvailability.beginScene(scene.routeId());
    }

    public void setScene(RoutePreviewScene nextScene, String nextCounter) {
        this.scene = nextScene;
        this.routeName = nextScene.routeName();
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

    public void setRouteName(String nextName) {
        this.routeName = nextName == null || nextName.isBlank() ? scene.routeName() : nextName;
    }

    /** Tells the header whether to reserve room for the screen's route arrows. */
    public void setNavigationVisible(boolean shown) {
        this.navigationVisible = shown;
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

    /** True once the wheel has moved the view off its default framing. */
    public boolean zoomed() {
        return zoom.factor() != RoutePreviewZoom.DEFAULT_FACTOR;
    }

    /** Current zoom rendered for the reset control, e.g. {@code "1.4"}. */
    public String zoomLabel() {
        return zoomFactorText(zoom.factor());
    }

    public void resetZoom() {
        zoom.reset();
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
        // Same 1px sheen the shared control frame uses, so the preview reads as
        // part of the same surface family as the buttons around it.
        g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, RULE);

        var font = Minecraft.getInstance().font;
        int headerWidth = headerTextWidth(getWidth(), navigationVisible);
        String clippedName = font.plainSubstrByWidth(routeName, headerWidth);
        String clippedDetail = font.plainSubstrByWidth(
                headerDetailText(scene.markers().size(), routeCounter), headerWidth);
        g.text(font, clippedName, x1 + (getWidth() - font.width(clippedName)) / 2,
                y1 + 6, TEXT, false);
        g.text(font, clippedDetail, x1 + (getWidth() - font.width(clippedDetail)) / 2,
                y1 + 17, TEXT_DIM, false);
        g.fill(x1 + 1, y1 + HEADER_HEIGHT - 1, x2 - 1, y1 + HEADER_HEIGHT, RULE);

        int renderX = x1 + 2;
        int renderY = y1 + HEADER_HEIGHT;
        int renderW = Math.max(1, getWidth() - 4);
        int renderH = Math.max(1, getHeight() - HEADER_HEIGHT - 2);
        g.fill(renderX, renderY, renderX + renderW, renderY + renderH, VIEWPORT);
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
                        renderX, renderY, renderW, renderH, currentYaw, scale, guiScale)
                : -1;
        boolean paused = hoveredIndex >= 0
                || !visible || !Minecraft.getInstance().isWindowActive();
        currentYaw = orbit.update(System.nanoTime(), !paused);
        hoveredIndex = pointerInside
                ? RoutePreviewProjection.pick(scene, mouseX, mouseY,
                        renderX, renderY, renderW, renderH, currentYaw, scale, guiScale)
                : -1;
        if (RoutePreviewAvailability.unavailable()) {
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
                    (float) scale, guiScale, scissor));
        }

        g.nextStratum();
        drawNoticesAndLabel(g, renderX, renderY, renderW, renderH);
    }

    /**
     * The viewport's bottom strip plus the hover label.
     *
     * <p>Notices used to sit at the top-left, directly under the header rule and
     * in the same corner the route itself drifts through as it orbits. They live
     * along the bottom now, opposite the zoom control, where nothing is drawn
     * except at the extremes of a zoom-in. The scroll hint is deliberately
     * hover-only: an always-on instruction line is clutter for the 99% of visits
     * that never zoom, but there is otherwise nothing to suggest the wheel does
     * anything here.
     */
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
        // Keep clear of the bottom strip: the notices and the screen's zoom
        // control live down there, and a hover label parked over either of them
        // hides state the player did not dismiss. Both bounds are floored
        // against the top inset so a preview squeezed to nothing still places
        // the label instead of failing the clamp.
        int labelX = Math.clamp(lastMouseX + 8, renderX + 4,
                Math.max(renderX + 4, renderX + renderW - labelW - 4));
        int labelY = Math.clamp(lastMouseY + 8, renderY + 4,
                Math.max(renderY + 4,
                        renderY + renderH - labelH - ZOOM_BUTTON_H - ZOOM_BUTTON_INSET * 2));
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

    /** Second header line: how many waypoints, and which of the selected routes this is. */
    static String headerDetailText(int markerCount, String routeCounter) {
        String counter = routeCounter == null ? "" : routeCounter;
        return waypointCountText(markerCount)
                + (counter.isBlank() ? "" : " · " + counter);
    }

    /**
     * Width a centered header line may use.
     *
     * <p>The header text is centered on the whole widget, so it has to give up
     * the arrow reserve on <em>both</em> sides even though only the wide one
     * matters -- otherwise a long route name grows into the left arrow while
     * looking centered. When the screen hides the arrows the whole width comes
     * back, which is the common single-route case.
     */
    static int headerTextWidth(int widgetWidth, boolean navigationVisible) {
        int reserve = navigationVisible ? NAV_RESERVE : 4;
        return Math.max(0, widgetWidth - reserve * 2);
    }

    /** Zoom shown on the reset control, e.g. {@code 1.4} for 1.4x. */
    public static String zoomFactorText(double factor) {
        return String.format(java.util.Locale.ROOT, "%.1f", factor);
    }

    /** True while the wheel would actually do something the user has not found yet. */
    private boolean showsScrollHint() {
        return !scene.markers().isEmpty()
                && hoveredIndex < 0
                && !RoutePreviewAvailability.unavailable()
                && zoom.factor() == RoutePreviewZoom.DEFAULT_FACTOR
                && contains(lastMouseX, lastMouseY,
                        getX() + 2, getY() + HEADER_HEIGHT,
                        Math.max(1, getWidth() - 4),
                        Math.max(1, getHeight() - HEADER_HEIGHT - 2));
    }

    /**
     * The two empty-viewport states. Both get a headline plus a dim second line,
     * because on their own "3D preview unavailable" reads like the export
     * failed -- the detail line is there to say the code is fine.
     */
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
        // Passive previews are not part of focus or narration traversal.
    }
}
