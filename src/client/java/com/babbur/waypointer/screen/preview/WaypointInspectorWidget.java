package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.render.WaypointSkipFade;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.RenderHelpers;
import com.babbur.waypointer.render.WaypointLabelAppearance;
import com.babbur.waypointer.render.WaypointRenderer;
import com.babbur.waypointer.render.TracerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.world.phys.Vec3;
import java.util.function.Supplier;
import java.util.function.IntSupplier;
import static org.lwjgl.glfw.GLFW.*;

/** A controlled perspective sample using the live emitters and live label layout. */
public final class WaypointInspectorWidget extends AbstractWidget {
    private static final String[] ROLE_KEYS = {"current", "next", "completed", "static"};
    private final Supplier<WaypointerConfig> effectiveConfig;
    private final Supplier<WaypointGroup> group;
    private final Supplier<Waypoint> waypoint;
    private final RoutePreviewPaintResource paintResources = new RoutePreviewPaintResource();
    private final RoutePreviewPaintResource contextPaintResources = new RoutePreviewPaintResource();
    private final RoutePreviewAvailability availability = new RoutePreviewAvailability();
    private double distance = 8, yaw = 0, pitch = Math.toRadians(-6);
    private int selectedRole;
    private Supplier<WaypointerConfig> contextConfig;
    private IntSupplier contextColor;
    private WaypointGroup previewRoute;
    private Waypoint cachedSourceShape;
    private String cachedSourceName;
    private boolean checker;
    private double panX, panY = .22;
    private final double[] tracerScratch = new double[2];
    private IntSupplier waypointIndex;
    private WaypointAppearancePreviewPart highlight;
    private Component highlightCaption = Component.empty();
    private boolean highlightedLabel;
    private static final int GUIDE = 0xE8F0C76A;

    public WaypointInspectorWidget(int x, int y, int w, int h, Supplier<WaypointerConfig> effectiveConfig,
                                    Supplier<WaypointGroup> group, Supplier<Waypoint> waypoint) {
        super(x, y, w, h, Component.translatable("waypointer.appearance.inspector"));
        this.effectiveConfig = effectiveConfig;
        this.group = group;
        this.waypoint = waypoint;
        this.selectedRole = WaypointInspectorSequence.initialTab(group.get());
        this.distance = h < 180 ? 1.8 : 8;
        if (h < 180) { panX = .15; panY = .1; }
    }

    public void releaseResources() { paintResources.close(); contextPaintResources.close(); }
    public void setWaypointIndexSupplier(IntSupplier waypointIndex) { this.waypointIndex = waypointIndex; }
    public void setContextAppearance(Supplier<WaypointerConfig> config, IntSupplier color) { contextConfig = config; contextColor = color; }
    public void selectStaticMode() { selectedRole = WaypointInspectorSequence.STATIC_TAB; }
    public void setHighlight(WaypointAppearancePreviewPart part, Component caption) {
        highlight = part;
        highlightCaption = caption == null ? Component.empty() : caption;
    }
    public WaypointAppearancePreviewPart highlight() { return highlight; }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        WaypointerConfig cfg = effectiveConfig.get();
        if (cfg == null) return;
        Minecraft mc = Minecraft.getInstance();
        Waypoint point = waypoint.get();
        if (point == null) point = new Waypoint(0, 0, 0, "Waypoint", cfg.defaultWaypointColor(), 0, 0);
        WaypointGroup route = group.get();
        if (route == null) {
            route = new WaypointGroup("appearance-inspector", "Route", "");
            route.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
            route.add(point);
        }
        WaypointGroup sourceRoute = route;
        Waypoint shape = point.withColor(0).withStyleOverride(WaypointStyle.EMPTY);
        boolean staticMode = selectedRole == WaypointInspectorSequence.STATIC_TAB;
        if (previewRoute == null || !shape.equals(cachedSourceShape) || !sourceRoute.name().equals(cachedSourceName)
                || (previewRoute.loadMode() == WaypointGroup.LoadMode.STATIC) != staticMode) {
            previewRoute = WaypointInspectorSequence.group(point, sourceRoute, selectedRole);
            cachedSourceShape = shape; cachedSourceName = sourceRoute.name();
        } else previewRoute.setCurrentIndex(WaypointInspectorSequence.currentIndex(point, selectedRole));
        route = previewRoute;
        WaypointSkipFade.observe(route, cfg, i -> WaypointInspectorSequence.configFor(i, cfg,
                contextConfig == null ? null : contextConfig.get()));
        int top = getY() + 23, viewHeight = Math.max(1, height - 59);
        int left = getX() + 1, viewWidth = Math.max(1, width - 2);
        g.fill(getX(), getY(), (getX() + getWidth()), (getY() + getHeight()), 0xFF161A20);
        for (int i = 0; i < WaypointInspectorSequence.TAB_COUNT; i++) {
            int tabX = getX() + i * width / WaypointInspectorSequence.TAB_COUNT, tabEnd = getX() + (i + 1) * width / WaypointInspectorSequence.TAB_COUNT;
            if (selectedRole == i) g.fill(tabX + 1, getY() + 1, tabEnd - 1, top - 1, 0xFF263E55);
            String label = i == WaypointInspectorSequence.STATIC_TAB
                    ? Component.translatableWithFallback("waypointer.appearance.inspector.static", "Static").getString()
                    : Component.translatable("waypointer.label.role." + ROLE_KEYS[i]).getString();
            g.text(mc.font, mc.font.plainSubstrByWidth(label, Math.max(1, width / WaypointInspectorSequence.TAB_COUNT - 8)), tabX + 4, getY() + 7, 0xFFE4EAF2, false);
        }
        g.enableScissor(left, top, left + viewWidth, top + viewHeight);
        if (checker) {
            for (int y = top; y < top + viewHeight; y += 12) for (int x = left; x < left + viewWidth; x += 12)
                g.fill(x, y, Math.min(x + 12, left + viewWidth), Math.min(y + 12, top + viewHeight),
                        (((x - left) / 12 + (y - top) / 12) & 1) == 0 ? 0xFF292D34 : 0xFF343941);
        } else {
            for (int row = 0; row < viewHeight; row++) {
                int shade = Math.clamp(155 - row * 45 / Math.max(1, viewHeight), 0, 255);
                g.fill(left, top + row, left + viewWidth, top + row + 1, 0xFF000000 | (shade - 42) << 16 | (shade - 17) << 8 | shade);
            }
        }
        double fov = mc.options.fov().get();
        AABB world = WaypointRenderer.waypointBoxBounds(mc.level, point);
        AABB local = world.move(-world.getCenter().x, -world.getCenter().y, -world.getCenter().z);
        double scale = cfg.waypointMarkerScale();
        local = new AABB(local.minX * scale, local.minY * scale, local.minZ * scale,
                local.maxX * scale, local.maxY * scale, local.maxZ * scale);
        // Keep this orbiting sample camera outside the marker; it is not a walk-inside preview.
        distance = Math.max(distance, Math.sqrt(local.maxX * local.maxX + local.maxY * local.maxY + local.maxZ * local.maxZ) + .1);
        boolean[] visible = WaypointInspectorSequence.visible(route, cfg);
        var centeredCamera = new WaypointInspectorProjection(distance, yaw, pitch, fov, viewHeight);
        var camera = new WaypointInspectorProjection(distance, yaw, pitch, fov, viewHeight,
                panX * viewWidth - WaypointInspectorSequence.horizontalCenter(centeredCamera, visible), panY * viewHeight);
        paintResources.advanceFrame();
        contextPaintResources.advanceFrame();
        availability.beginScene(route.id());
        RoutePreviewPaintResource.Entry paint = null, contextPaint = null;
        WaypointerConfig context = contextConfig == null ? cfg : contextConfig.get();
        if (!availability.unavailable()) {
            try {
                var editedPaint = RoutePreviewScene.effectivePaint(sourceRoute, cfg);
                var inheritedPaint = RoutePreviewScene.effectivePaint(sourceRoute, context);
                paint = paintResources.activate(route.id(), editedPaint);
                contextPaint = java.util.Objects.equals(editedPaint, inheritedPaint) ? paint
                        : contextPaintResources.activate(route.id() + ":context", inheritedPaint);
            } catch (RuntimeException paintFailure) {
                availability.markUnavailable();
                com.babbur.waypointer.Waypointer.LOGGER.error("Could not prepare waypoint inspector paint", paintFailure);
            }
        }
        List<WaypointInspectorSample> samples = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            boolean representative = i == WaypointInspectorSequence.EDITED_INDEX;
            WaypointerConfig sampleConfig = WaypointInspectorSequence.configFor(i, cfg, context);
            Waypoint samplePoint = representative ? point : route.get(i).withColor(contextColor == null ? point.color() : contextColor.getAsInt());
            double sampleScale = sampleConfig.waypointMarkerScale();
            AABB sampleBounds = representative ? local : new AABB(-.5 * sampleScale, -.5 * sampleScale, -.5 * sampleScale, .5 * sampleScale, .5 * sampleScale, .5 * sampleScale);
            var role = WaypointInspectorSequence.state(route, i);
            int color = samplePoint.color();
            if (sampleConfig.colorSequenceWaypointsByRole() && route.loadMode() == WaypointGroup.LoadMode.SEQUENCE) color = switch (role) {
                case CURRENT -> sampleConfig.sequenceCurrentWaypointColor();
                case UPCOMING -> sampleConfig.sequenceNextWaypointColor();
                case COMPLETED -> sampleConfig.sequencePreviousWaypointColor();
            };
            samples.add(new WaypointInspectorSample(sampleConfig, route, samplePoint, sampleBounds, role, color, camera,
                    WaypointRenderer.beaconAnimationTime(mc.level == null ? System.nanoTime() / 50_000_000 : mc.level.getGameTime(), partialTick, sampleConfig.beaconMotionSpeed()), representative ? paint : contextPaint,
                    representative ? world.getCenter() : new Vec3(samplePoint.centerX(), samplePoint.centerY(), samplePoint.centerZ()),
                    mc.level == null ? -64 : mc.level.getMinY(), mc.level == null ? 320 : mc.level.getMaxY(),
                    WaypointInspectorSequence.POSITIONS.get(i), i, visible[i]));
        }
        var scene = new WaypointInspectorScene(List.copyOf(samples), !checker);
        if (!availability.unavailable()) RoutePreviewGuiBridge.submit(g, RoutePreviewRenderState.create(RoutePreviewScene.inspector(scene), 0, -1,
                left, top, left + viewWidth, top + viewHeight, 1, mc.getWindow().getGuiScale(), false,
                availability, new ScreenRectangle(left, top, viewWidth, viewHeight)));
        g.nextStratum();
        double cx = left + viewWidth / 2.0, cy = top + viewHeight / 2.0;
        g.fill((int) cx - 3, (int) cy, (int) cx + 4, (int) cy + 1, 0xBBDCE3E7);
        g.fill((int) cx, (int) cy - 3, (int) cx + 1, (int) cy + 4, 0xBBDCE3E7);
        var tracerFade = com.babbur.waypointer.render.WaypointSkipFade.get(route);
        for (int pass = 0; pass < 2; pass++) {
            WaypointInspectorSample tracer;
            if (pass == 0) {
                tracer = WaypointInspectorTracer.target(samples, sourceRoute, cfg,
                        com.babbur.waypointer.WaypointerClient.dungeonConfig());
            } else {
                if (tracerFade == null || !tracerFade.active()) continue;
                int outgoing = tracerFade.outgoing();
                tracer = samples.get(outgoing);
                if (!route.isWaypointEnabled(outgoing)
                        || WaypointRenderer.shouldForceHideReachedWaypoint(outgoing, route.currentIndex(), tracer.waypoint())
                        || !WaypointInspectorTracer.eligible(tracer, sourceRoute,
                            com.babbur.waypointer.WaypointerClient.dungeonConfig(), false)) continue;
            }
            if (tracer == null) continue;
            var tracerConfig = tracer.config();
            float tracerAlpha = (float) tracerConfig.tracerOpacity()
                    * (tracerFade == null ? 1 : tracerFade.tracerAlpha(tracer.index()));
            if (tracerAlpha <= 0) continue;
            boolean offscreen = WaypointInspectorTracer.project(camera, tracer.position(), viewWidth, viewHeight, tracerScratch);
            double targetX = left + tracerScratch[0], targetY = top + tracerScratch[1];
            TracerRenderer.drawStyledTracer(g, cx, cy, targetX, targetY,
                    RenderHelpers.withAlpha(0xFF000000 | (tracerConfig.matchTracerToWaypointColor() ? tracer.color() : tracerConfig.tracerColor()), tracerAlpha),
                    tracerConfig, offscreen, tracerScratch);
        }
        highlightedLabel = false;
        drawLabels(g, samples, sourceRoute, cx, cy);
        drawHighlight(g, samples, cx, cy);
        if (!scene.edited().visible()) centered(g, "waypointer.appearance.inspector.hidden", top + 5);
        if (availability.unavailable()) centered(g, "waypointer.screen.export.preview.unavailable", top + viewHeight / 2);
        g.disableScissor();
        String distanceText = String.format(Locale.ROOT, "%.1f m  ·  %.0f°", distance, fov);
        g.text(mc.font, distanceText, getX() + 5, (getY() + getHeight()) - 30, 0xFFDBE3EB, false);
        String bg = Component.translatable("waypointer.appearance.inspector." + (checker ? "checker" : "scene")).getString();
        g.text(mc.font, bg, (getX() + getWidth()) - mc.font.width(bg) - 5, (getY() + getHeight()) - 30, 0xFF83B8E8, false);
        if (highlight == null) centered(g, "waypointer.appearance.inspector.hint", (getY() + getHeight()) - 15);
        else {
            boolean off = switch (highlight) {
                case TRACERS -> !cfg.showTracer();
                case BEAMS -> cfg.beaconBeamMode() == WaypointerConfig.BeaconBeamMode.OFF;
                case CONNECTORS -> !cfg.showRouteLines();
                case LABELS -> !highlightedLabel;
                default -> false;
            };
            String caption = Component.translatableWithFallback("waypointer.appearance.inspector.highlight" + (off ? ".off" : ""),
                    off ? "%s (off; preview guide)" : "%s (preview guide)", highlightCaption).getString();
            caption = mc.font.plainSubstrByWidth(caption, Math.max(1, width - 10));
            g.text(mc.font, caption, getX() + (width - mc.font.width(caption)) / 2, getY() + height - 15, GUIDE, false);
            if (highlight == WaypointAppearancePreviewPart.DISTANCE) guideRect(g, getX() + 3, getY() + height - 33,
                    getX() + mc.font.width(distanceText) + 8, getY() + height - 19);
        }
    }

    private record Label(WaypointInspectorSample sample, List<String> rows, boolean hasNameRow, double x, double y, double distance,
                         float scale, boolean focused, double left, double top, double right, double bottom) {}

    private void drawLabels(GuiGraphicsExtractor g, List<WaypointInspectorSample> samples, WaypointGroup sourceRoute, double cx, double cy) {
        var font = Minecraft.getInstance().font;
        var globalConfig = samples.getFirst().config();
        List<Label> candidates = new ArrayList<>(5);
        for (var sample : samples) {
            var cfg = sample.config();
            if (!sample.labelVisible() || sample.waypoint().hasFlag(Waypoint.FLAG_HIDE_NAME)
                    || cfg.hideWaypointLabelsNearPlayer() && cfg.hideWaypointLabelsNearRadius() > 0
                        && sample.playerPosition().distanceToSqr(sample.pointPosition()) <= cfg.hideWaypointLabelsNearRadius() * cfg.hideWaypointLabelsNearRadius()) continue;
            Vec3 anchorPosition = sample.position().add(0, 1.1 + cfg.labelHeightOffset(), 0);
            var anchor = sample.camera().project(anchorPosition.x, anchorPosition.y, anchorPosition.z);
            if (anchor.depth() <= .05) continue;
            double x = cx + anchor.x(), y = cy + anchor.y();
            if (!WaypointLabelAppearance.nearScreen(x - getX() - 1, y - getY() - 23,
                    Math.max(1, width - 2), Math.max(1, height - 59))) continue;
            float scale = WaypointRenderer.labelScaleForDepth(anchor.depth(), (float) sample.camera().verticalFov(), cfg.scaleWaypointTextWithDistance(), cfg.labelScale());
            boolean focused = WaypointLabelAppearance.focused(x, y, cx, cy);
            double labelDistanceSquared = sample.cameraPosition().distanceToSqr(anchorPosition);
            double labelDistance = Math.sqrt(labelDistanceSquared);
            if (sample.group().loadMode() == WaypointGroup.LoadMode.STATIC && cfg.maxStaticWaypointRenderDistance() > 0
                    && labelDistance > cfg.maxStaticWaypointRenderDistance()) continue;
            boolean detail = WaypointLabelAppearance.showDetail(cfg, labelDistanceSquared, focused);
            boolean edited = sample.index() == WaypointInspectorSequence.EDITED_INDEX;
            WaypointGroup labelRoute = edited ? sourceRoute : sample.group();
            int index = edited && waypointIndex != null ? Math.max(0, waypointIndex.getAsInt()) : sample.index();
            String role = WaypointRenderer.labelRole(sample.group(), sample.index(), sample.waypoint(), sample.role());
            var rows = WaypointRenderer.labelRows(cfg, labelRoute, index, sample.waypoint(), detail, role, labelDistance,
                    cfg.showRouteProgress() && !sourceRoute.temp() ? WaypointRenderer.routeProgressText(sample.group()) : null);
            float alpha = sample.alpha() * (float) cfg.labelOpacity();
            if (rows.isEmpty() && ("NONE".equals(cfg.waypointIcon()) || !WaypointRenderer.labelAlphaVisible(alpha))) continue;
            if (!WaypointRenderer.labelAlphaVisible(alpha) && (!cfg.showLabelBackdrop()
                    || !WaypointRenderer.labelAlphaVisible(sample.alpha() * (float) cfg.labelBackdropOpacity()))) continue;
            int textWidth = 0;
            for (String row : rows) textWidth = Math.max(textWidth, font.width(row));
            double labelWidth = WaypointLabelAppearance.labelWidth(textWidth, !rows.isEmpty(), !"NONE".equals(cfg.waypointIcon()), scale);
            candidates.add(new Label(sample, rows, WaypointRenderer.hasNameRow(cfg, labelRoute, sample.waypoint()), x, y, labelDistance, scale, focused, x - labelWidth / 2 - 2,
                    y - ("NONE".equals(cfg.waypointIcon()) ? 0 : 12 * scale) - 2, x + labelWidth / 2 + 2,
                    y + rows.size() * (font.lineHeight + 1) * scale + 2));
        }
        int budget = globalConfig.maxWaypointLabels() == 0 ? candidates.size() : globalConfig.maxWaypointLabels();
        boolean preventOverlap = candidates.stream().anyMatch(label -> label.sample().config().preventLabelOverlap());
        if (preventOverlap) candidates.sort(Comparator.comparingInt((Label l) -> WaypointLabelAppearance.priority(l.sample().role() == WaypointRenderer.State.CURRENT, l.focused())).thenComparingDouble(Label::distance));
        else candidates.sort(Comparator.comparingDouble(Label::distance));
        List<Label> drawn = new ArrayList<>(5);
        for (var label : candidates) {
            if (drawn.size() >= budget) break;
            var cfg = label.sample().config();
            if (cfg.preventLabelOverlap() && drawn.stream().anyMatch(other -> WaypointLabelAppearance.overlaps(label.left(), label.top(), label.right(), label.bottom(),
                    other.left(), other.top(), other.right(), other.bottom()))) continue;
            var sample = label.sample();
            float alpha = sample.alpha() * (float) cfg.labelOpacity();
            int nameColor = 0xFF000000 | (cfg.matchWaypointTextToWaypointColor() && cfg.showWaypointNames() ? sample.color() : 0xFFFFFF);
            WaypointLabelAppearance.drawIcon(g, cfg.waypointIcon(), label.x(), label.y() - 8 * label.scale(), label.scale(), RenderHelpers.withAlpha(nameColor, alpha));
            double y = label.y();
            for (int row = 0; row < label.rows().size(); row++) {
                WaypointRenderer.drawCenteredLabel(g, font, label.rows().get(row), label.x(), y,
                        RenderHelpers.withAlpha(row == 0 && label.hasNameRow() ? nameColor : 0xFFCCCCCC, alpha),
                        sample.alpha() * (float) cfg.labelBackdropOpacity(), label.scale(), cfg);
                y += (font.lineHeight + 1) * label.scale();
            }
            drawn.add(label);
            if (highlight == WaypointAppearancePreviewPart.LABELS) {
                guideRect(g, label.left(), label.top(), label.right(), label.bottom());
                highlightedLabel = true;
            }
        }
    }

    private void drawHighlight(GuiGraphicsExtractor g, List<WaypointInspectorSample> samples, double cx, double cy) {
        if (highlight == null) return;
        var edited = samples.get(WaypointInspectorSequence.EDITED_INDEX);
        var current = samples.get(edited.group().currentIndex());
        var camera = edited.camera();
        switch (highlight) {
            case MARKER -> highlightMarker(g, edited, cx, cy);
            case STATES, PROGRESSION -> {
                for (var sample : samples) if (sample.sequenceVisible()) highlightMarker(g, sample, cx, cy);
                if (highlight == WaypointAppearancePreviewPart.PROGRESSION) highlightConnectors(g, samples, cx, cy);
            }
            case DISTANCE -> {
                var target = camera.project(edited.position().x, edited.position().y, edited.position().z);
                guideLine(g, cx, cy, cx + target.x(), cy + target.y());
                highlightMarker(g, edited, cx, cy);
            }
            case TRACERS -> {
                var target = camera.project(current.position().x, current.position().y, current.position().z);
                guideLine(g, cx, cy, cx + target.x(), cy + target.y());
                guideRect(g, cx - 5, cy - 5, cx + 5, cy + 5);
            }
            case BEAMS -> {
                var base = camera.project(current.position().x, current.position().y, current.position().z);
                var top = camera.project(current.position().x, current.position().y + 8, current.position().z);
                double topY = Math.max(getY() + 28, cy + top.y());
                double topX = cx + top.x();
                guideLine(g, cx + base.x() - 3, cy + base.y(), topX - 3, topY);
                guideLine(g, cx + base.x() + 3, cy + base.y(), topX + 3, topY);
                guideRect(g, topX - 5, topY - 1, topX + 5, topY + 1);
            }
            case CONNECTORS -> highlightConnectors(g, samples, cx, cy);
            case LABELS -> {
                if (!highlightedLabel) {
                    var anchor = camera.project(edited.position().x, edited.position().y + 1.1 + edited.config().labelHeightOffset(), edited.position().z);
                    guideRect(g, cx + anchor.x() - 35, cy + anchor.y() - 2, cx + anchor.x() + 35, cy + anchor.y() + 18);
                }
            }
        }
    }

    private void highlightConnectors(GuiGraphicsExtractor g, List<WaypointInspectorSample> samples, double cx, double cy) {
        var player = Minecraft.getInstance().player;
        float eyeHeight = player == null ? 1.27f : player.getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING);
        for (int i = 1; i < samples.size(); i++) {
            var a = samples.get(i - 1); var b = samples.get(i);
            if (!a.sequenceVisible() || !b.sequenceVisible()) continue;
            var origin = WaypointRenderer.routeLineStart(a.waypoint(), a.config().useEtherwarpHeight(), eyeHeight)
                    .subtract(a.worldCenter()).add(a.position());
            var start = a.camera().project(origin.x, origin.y, origin.z);
            var end = b.camera().project(b.position().x, b.position().y, b.position().z);
            if (start.depth() > .05 && end.depth() > .05) guideLine(g, cx + start.x(), cy + start.y(), cx + end.x(), cy + end.y());
        }
    }

    private void highlightMarker(GuiGraphicsExtractor g, WaypointInspectorSample sample, double cx, double cy) {
        var box = sample.bounds();
        double left = Double.POSITIVE_INFINITY, top = left, right = Double.NEGATIVE_INFINITY, bottom = right;
        for (int i = 0; i < 8; i++) {
            var p = sample.camera().project(sample.position().x + ((i & 1) == 0 ? box.minX : box.maxX),
                    sample.position().y + ((i & 2) == 0 ? box.minY : box.maxY),
                    sample.position().z + ((i & 4) == 0 ? box.minZ : box.maxZ));
            if (p.depth() <= .05) continue;
            left = Math.min(left, cx + p.x()); right = Math.max(right, cx + p.x());
            top = Math.min(top, cy + p.y()); bottom = Math.max(bottom, cy + p.y());
        }
        if (Double.isFinite(left)) guideRect(g, left - 3, top - 3, right + 3, bottom + 3);
    }

    /** Dashed guides are annotations, not an enabled feature or a replacement for production geometry. */
    private static void guideLine(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2) {
        if (!Double.isFinite(x1 + y1 + x2 + y2)) return;
        int steps = Math.clamp((int) Math.ceil(Math.hypot(x2 - x1, y2 - y1) / 5), 1, 240);
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(x1 + (x2 - x1) * i / steps), y = (int) Math.round(y1 + (y2 - y1) * i / steps);
            g.fill(x - 1, y - 1, x + 1, y + 1, GUIDE);
        }
    }
    private static void guideRect(GuiGraphicsExtractor g, double left, double top, double right, double bottom) {
        if (!Double.isFinite(left + top + right + bottom)) return;
        int l = (int) Math.floor(left), t = (int) Math.floor(top), r = (int) Math.ceil(right), b = (int) Math.ceil(bottom);
        g.fill(l, t, r, t + 1, GUIDE); g.fill(l, b - 1, r, b, GUIDE);
        g.fill(l, t, l + 1, b, GUIDE); g.fill(r - 1, t, r, b, GUIDE);
    }

    private void centered(GuiGraphicsExtractor g, String key, int y) {
        var font = Minecraft.getInstance().font;
        String text = font.plainSubstrByWidth(Component.translatable(key).getString(), Math.max(1, width - 10));
        g.text(font, text, getX() + (width - font.width(text)) / 2, y, 0xFFC4CDD8, false);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!isMouseOver(event.x(), event.y())) return false;
        if (event.button() == 1) return true;
        if (event.button() != 0) return false;
        if (event.y() < getY() + 23) selectedRole = Math.clamp((int) ((event.x() - getX()) * WaypointInspectorSequence.TAB_COUNT / width), 0, WaypointInspectorSequence.TAB_COUNT - 1);
        else if (event.y() >= (getY() + getHeight()) - 36) checker = !checker;
        return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 1) {
            panX = Math.clamp(panX + dx / Math.max(1, width), -1, 1);
            panY = Math.clamp(panY + dy / Math.max(1, height - 59), -1, 1);
            return true;
        }
        if (event.button() != 0) return false;
        yaw += dx * .012;
        pitch = Math.clamp(pitch + dy * .012, -1.2, 1.2);
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!isMouseOver(x, y)) return false;
        distance = Math.clamp(distance * Math.pow(1.15, -vertical), 1.5, 512);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!isFocused()) return false;
        switch (event.key()) {
            case GLFW_KEY_LEFT -> yaw -= .15;
            case GLFW_KEY_RIGHT -> yaw += .15;
            case GLFW_KEY_UP -> distance = Math.max(1.5, distance / 1.15);
            case GLFW_KEY_DOWN -> distance = Math.min(512, distance * 1.15);
            case GLFW_KEY_SPACE, GLFW_KEY_ENTER -> selectedRole = (selectedRole + 1) % WaypointInspectorSequence.TAB_COUNT;
            case GLFW_KEY_B -> checker = !checker;
            default -> { return false; }
        }
        return true;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
