package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteProgress;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.WaypointVisibility;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.text.AmpersandFormatting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

public final class WaypointRenderer extends WaypointWorldRenderer implements HudElement {

    private static final Comparator<LabelCandidate> LABEL_NEAREST_FIRST =
            Comparator.comparingDouble(candidate -> candidate.distanceSquared);

    private final StringBuilder distanceScratch = new StringBuilder(8);
    private final String[] indexLabelCache = new String[INDEX_LABEL_CACHE_MAX];
    private final Map<String, String> nameTranslationCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > NAME_TRANSLATION_CACHE_MAX;
                }
            };
    private final WorldScreenProjector labelProjector = new WorldScreenProjector();
    private final double[] labelScreenScratch = new double[2];
    private final double[] boxScreenScratch = new double[16];
    private final boolean[] boxCornerVisible = new boolean[8];
    private double projectedBoxMinX;
    private double projectedBoxMinY;
    private double projectedBoxMaxX;
    private double projectedBoxMaxY;
    private final ArrayList<LabelCandidate> labelCandidates = new ArrayList<>();
    private int labelCandidateCount;

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, null);
    }

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config,
                            DungeonConfig dungeonConfig) {
        super(manager, config, dungeonConfig);
    }

    public void install() {
        WorldOverlayCompat.register(this::onWorldRender);
        // CHAT placement makes labels follow the F1 hide-GUI state.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, LABEL_HUD_ID, this);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
        boolean showNames = config.showWaypointNames();
        boolean showRouteProgress = config.showRouteProgress();
        boolean showDistances = config.showWaypointDistances();
        boolean drawIrisHudBoxes = IrisShaderFallback.shouldUse(config);
        boolean drawEditModeSubtitle = config.showEditModeSubtitle()
                && WaypointRepositionMode.isEditModeEnabled();
        if (!showNames && !showRouteProgress && !showDistances && !drawIrisHudBoxes
                && !drawEditModeSubtitle) return;

        var groups = manager.activeGroups();

        Minecraft mc = Minecraft.getInstance();
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = MinecraftCompat.mainCamera(renderer);
        if (!camera.isInitialized()) return;

        Font font = mc.font;
        ClientLevel level = mc.level;
        Vec3 camPos = camera.position();
        prepareDepthVisibilityCache(level, camPos);
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        labelProjector.prepare(renderer, camera);
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        if (drawEditModeSubtitle) {
            drawEditModeSubtitle(g, font, screenW, screenH);
        }
        if (groups.isEmpty()) return;
        int labelBudget = config.maxWaypointLabels();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        double labelNearHideDistanceSq = labelNearHideDistanceSq();
        clearLabelCandidates();

        if (drawIrisHudBoxes && config.waypointOutlineOpacity() > 0.0) {
            drawIrisHudBoxes(g, mc, level, camPos, playerPos, screenW, screenH, groups,
                    maxStaticDistanceSq, nearHideDistanceSq);
        }

        if (showNames || showRouteProgress || showDistances) {
            for (WaypointGroup group : groups) {
                drawGroupLabels(g, font, mc, level, camPos, playerPos, screenW, screenH, group,
                        showNames, showRouteProgress, showDistances, labelBudget,
                        maxStaticDistanceSq, nearHideDistanceSq, labelNearHideDistanceSq);
            }
            if (labelBudget > 0 && labelCandidateCount > 0) {
                drawBudgetedLabels(g, font, Math.min(labelBudget, labelCandidateCount),
                        showNames, showRouteProgress, showDistances);
            }
        }
        clearLabelCandidates();
    }

    private static void drawEditModeSubtitle(GuiGraphicsExtractor g, Font font,
                                             int screenW, int screenH) {
        String subtitle = editModeSubtitleText();
        int x = Math.max(0, (screenW - font.width(subtitle)) / 2);
        int y = Math.max(0, screenH / 2 + 14);
        g.text(font, subtitle, x, y, EDIT_MODE_SUBTITLE_ARGB, true);
    }

    static String editModeSubtitleText() {
        return EDIT_MODE_SUBTITLE_BASE_TEXT + " (exit: /wp editmode)";
    }

    private void drawIrisHudBoxes(GuiGraphicsExtractor g, Minecraft mc, ClientLevel level,
                                  Vec3 camPos, Vec3 playerPos, int screenW, int screenH,
                                  Iterable<WaypointGroup> groups,
                                  double maxStaticDistanceSq, double nearHideDistanceSq) {
        WaypointerConfig.BoxStyle style = hudFallbackBoxStyle(config.boxStyle());
        if (style == WaypointerConfig.BoxStyle.OUTLINED
                || style == WaypointerConfig.BoxStyle.FILLED_OUTLINED) {
            for (WaypointGroup group : groups) {
                drawIrisHudGroupBoxes(g, mc, level, camPos, playerPos, screenW, screenH,
                        group, maxStaticDistanceSq, nearHideDistanceSq);
            }
        }
    }

    private void drawIrisHudGroupBoxes(GuiGraphicsExtractor g, Minecraft mc, ClientLevel level,
                                       Vec3 camPos, Vec3 playerPos, int screenW, int screenH,
                                       WaypointGroup group, double maxStaticDistanceSq,
                                       double nearHideDistanceSq) {
        int currentIdx = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        float outlineOpacity = (float) config.waypointOutlineOpacity();

        group.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> {
            Waypoint waypoint = group.get(i);
            boolean depthChecked = waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);
            if (!shouldRenderWaypointWorld(group, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthChecked, mc, level)) {
                return;
            }
            State state = stateFor(group, i, currentIdx);
            float alpha = alphaFor(group, state) * outlineOpacity;
            int outlineColor = config.resolvedWaypointOutlineColor(waypoint.color());
            int argb = RenderHelpers.withAlpha(0xFF000000 | outlineColor, alpha);
            if (!projectBoxCorners(level, waypoint, screenW, screenH)) return;

            double outlineThickness = config.waypointOutlineThickness();
            for (int edge = 0; edge < BOX_EDGE_A.length; edge++) {
                int a = BOX_EDGE_A[edge];
                int b = BOX_EDGE_B[edge];
                if (!boxCornerVisible[a] || !boxCornerVisible[b]) continue;
                RenderHelpers.drawScreenLine(g,
                        boxScreenScratch[a * 2], boxScreenScratch[a * 2 + 1],
                        boxScreenScratch[b * 2], boxScreenScratch[b * 2 + 1],
                        argb, outlineThickness);
            }
        });
    }

    private boolean projectBoxCorners(ClientLevel level, Waypoint waypoint, int screenW, int screenH) {
        projectedBoxMinX = Double.POSITIVE_INFINITY;
        projectedBoxMinY = Double.POSITIVE_INFINITY;
        projectedBoxMaxX = Double.NEGATIVE_INFINITY;
        projectedBoxMaxY = Double.NEGATIVE_INFINITY;

        populateVisualWaypointBoxBounds(level, waypoint, waypointBoxBoundsScratch);
        double x1 = waypointBoxBoundsScratch[BOX_MIN_X];
        double y1 = waypointBoxBoundsScratch[BOX_MIN_Y];
        double z1 = waypointBoxBoundsScratch[BOX_MIN_Z];
        double x2 = waypointBoxBoundsScratch[BOX_MAX_X];
        double y2 = waypointBoxBoundsScratch[BOX_MAX_Y];
        double z2 = waypointBoxBoundsScratch[BOX_MAX_Z];

        projectBoxCorner(0, x1, y1, z1, screenW, screenH);
        projectBoxCorner(1, x2, y1, z1, screenW, screenH);
        projectBoxCorner(2, x1, y1, z2, screenW, screenH);
        projectBoxCorner(3, x2, y1, z2, screenW, screenH);
        projectBoxCorner(4, x1, y2, z1, screenW, screenH);
        projectBoxCorner(5, x2, y2, z1, screenW, screenH);
        projectBoxCorner(6, x1, y2, z2, screenW, screenH);
        projectBoxCorner(7, x2, y2, z2, screenW, screenH);

        if (!Double.isFinite(projectedBoxMinX)) return false;
        double margin = HUD_LINE_CULL_MARGIN / Minecraft.getInstance().getWindow().getGuiScale();
        return projectedBoxMaxX >= -margin
                && projectedBoxMinX <= screenW + margin
                && projectedBoxMaxY >= -margin
                && projectedBoxMinY <= screenH + margin;
    }

    private void projectBoxCorner(int index, double x, double y, double z,
                                  int screenW, int screenH) {
        int offset = index * 2;
        boxCornerVisible[index] = labelProjector.project(x, y, z, screenW, screenH, labelScreenScratch);
        if (!boxCornerVisible[index]) return;

        boxScreenScratch[offset] = labelScreenScratch[0];
        boxScreenScratch[offset + 1] = labelScreenScratch[1];
        projectedBoxMinX = Math.min(projectedBoxMinX, labelScreenScratch[0]);
        projectedBoxMinY = Math.min(projectedBoxMinY, labelScreenScratch[1]);
        projectedBoxMaxX = Math.max(projectedBoxMaxX, labelScreenScratch[0]);
        projectedBoxMaxY = Math.max(projectedBoxMaxY, labelScreenScratch[1]);
    }

    private void drawGroupLabels(GuiGraphicsExtractor g, Font font, Minecraft mc, ClientLevel level,
                                 Vec3 camPos, Vec3 playerPos, int screenW, int screenH,
                                 WaypointGroup group, boolean showNames,
                                 boolean showRouteProgress, boolean showDistances,
                                 int labelBudget, double maxStaticDistanceSq,
                                 double nearHideDistanceSq, double labelNearHideDistanceSq) {
        int currentIdx = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        double labelLift = LABEL_ANCHOR_LIFT + config.labelHeightOffset();
        boolean colorizeNames = config.matchWaypointTextToWaypointColor();
        boolean scaleLabelsWithDistance = config.scaleWaypointTextWithDistance();
        double configuredLabelScale = config.labelScale();
        boolean hasSubwaypoints = group.hasSubwaypoints();
        boolean showRouteProgressForGroup = showRouteProgress && !group.temp();
        String routeProgressText = showRouteProgressForGroup ? routeProgressText(group) : null;
        boolean dungeonRoomRoute = isDungeonRoomRoute(group);

        group.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> {
            if (dungeonRoomRoute && !isFocusedDungeonRouteLabel(group, i)) return;
            if (shouldHideStaticReached(group, i)) return;

            Waypoint w = group.get(i);
            if (shouldHideNearPlayer(w, playerPos, nearHideDistanceSq)) return;
            if (shouldHideNearPlayer(w, playerPos, labelNearHideDistanceSq)) return;
            State state = stateFor(group, i, currentIdx);
            if (shouldHideCompletedSequenceWaypoint(i, currentIdx, state, showCompleted, w)) return;
            if (w.hasFlag(Waypoint.FLAG_HIDE_NAME)) return;

            populateWaypointRenderAnchor(level, w, waypointBoxBoundsScratch);
            double ax = waypointBoxBoundsScratch[0];
            double ay = waypointBoxBoundsScratch[1] - 0.5 + labelLift;
            double az = waypointBoxBoundsScratch[2];
            double rx = ax - camPos.x, ry = ay - camPos.y, rz = az - camPos.z;
            double distanceSq = rx * rx + ry * ry + rz * rz;
            if (isStaticBeyondDistanceLimit(group, distanceSq, maxStaticDistanceSq)) {
                return;
            }

            if (!labelProjector.project(ax, ay, az, screenW, screenH, labelScreenScratch)) {
                return;
            }
            double sx = labelScreenScratch[0];
            double sy = labelScreenScratch[1];
            if (w.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)
                    && !shouldRenderProjectedDepthCheckedWaypoint(
                            mc, level, w, sx, sy, screenW, screenH)) {
                return;
            }
            if (!isNearScreen(sx, sy, screenW, screenH)) return;

            float labelScale = labelScaleForDepth(
                    labelProjector.depth(ax, ay, az),
                    labelProjector.fovDegrees(),
                    scaleLabelsWithDistance,
                    configuredLabelScale);
            float alpha = alphaFor(group, state);
            int nameColor = colorizeNames && showNames
                    ? 0xFF000000 | (w.color() & 0xFFFFFF)
                    : NAME_ARGB;
            boolean showWaypointName = showNames
                    && (!dungeonRoomRoute || shouldShowDungeonWaypointName(w));
            if (!showWaypointName && !showRouteProgressForGroup && !showDistances) return;

            if (labelBudget > 0) {
                LabelCandidate candidate = nextLabelCandidate();
                candidate.set(group, i, w, routeProgressText, hasSubwaypoints,
                        sx, sy, distanceSq, nameColor, alpha, labelScale);
                return;
            }

            double rowY = sy;
            if (showWaypointName) {
                String name = labelFor(group, i, w, hasSubwaypoints);
                drawCenteredLabel(g, font, name, sx, rowY,
                        RenderHelpers.withAlpha(nameColor, alpha), alpha, labelScale);
                rowY += labelRowAdvance(font, labelScale);
            }
            if (showRouteProgressForGroup) {
                drawCenteredLabel(g, font, routeProgressText, sx, rowY,
                        RenderHelpers.withAlpha(DISTANCE_ARGB, alpha), alpha, labelScale);
                rowY += labelRowAdvance(font, labelScale);
            }
            if (showDistances) {
                double distance = Math.sqrt(distanceSq);
                String distanceText = distanceString((int) distance);
                drawCenteredLabel(g, font, distanceText, sx, rowY,
                        RenderHelpers.withAlpha(DISTANCE_ARGB, alpha), alpha, labelScale);
            }
        });
    }

    private void drawBudgetedLabels(GuiGraphicsExtractor g, Font font, int count,
                                    boolean showNames, boolean showRouteProgress,
                                    boolean showDistances) {
        if (count <= 0) return;
        if (count < labelCandidateCount) {
            selectNearestLabels(count);
            labelCandidates.subList(0, count).sort(LABEL_NEAREST_FIRST);
        } else {
            labelCandidates.subList(0, labelCandidateCount).sort(LABEL_NEAREST_FIRST);
        }
        for (int i = 0; i < count; i++) {
            drawCandidateLabel(g, font, labelCandidates.get(i),
                    showNames, showRouteProgress, showDistances);
        }
    }

    private void drawCandidateLabel(GuiGraphicsExtractor g, Font font, LabelCandidate candidate,
                                    boolean showNames, boolean showRouteProgress,
                                    boolean showDistances) {
        double rowY = candidate.screenY;
        if (showNames
                && (!isDungeonRoomRoute(candidate.group)
                || shouldShowDungeonWaypointName(candidate.waypoint))) {
            String name = labelFor(candidate.group, candidate.index, candidate.waypoint,
                    candidate.hasSubwaypoints);
            drawCenteredLabel(g, font, name, candidate.screenX, rowY,
                    RenderHelpers.withAlpha(candidate.nameColor, candidate.alpha),
                    candidate.alpha, candidate.scale);
            rowY += labelRowAdvance(font, candidate.scale);
        }
        if (showRouteProgress && candidate.routeProgressText != null) {
            drawCenteredLabel(g, font, candidate.routeProgressText, candidate.screenX, rowY,
                    RenderHelpers.withAlpha(DISTANCE_ARGB, candidate.alpha),
                    candidate.alpha, candidate.scale);
            rowY += labelRowAdvance(font, candidate.scale);
        }
        if (showDistances) {
            String distance = distanceString((int) Math.sqrt(candidate.distanceSquared));
            drawCenteredLabel(g, font, distance, candidate.screenX, rowY,
                    RenderHelpers.withAlpha(DISTANCE_ARGB, candidate.alpha),
                    candidate.alpha, candidate.scale);
        }
    }

    private void clearLabelCandidates() {
        for (int i = 0; i < labelCandidateCount; i++) {
            labelCandidates.get(i).clear();
        }
        labelCandidateCount = 0;
    }

    private void selectNearestLabels(int count) {
        int target = count - 1;
        int left = 0;
        int right = labelCandidateCount - 1;
        while (left < right) {
            int pivot = partitionLabels(left, right, (left + right) >>> 1);
            if (pivot == target) return;
            if (pivot > target) right = pivot - 1;
            else left = pivot + 1;
        }
    }

    private int partitionLabels(int left, int right, int pivotIndex) {
        double pivotDistance = labelCandidates.get(pivotIndex).distanceSquared;
        swapLabelCandidates(pivotIndex, right);
        int store = left;
        for (int i = left; i < right; i++) {
            if (labelCandidates.get(i).distanceSquared < pivotDistance) {
                swapLabelCandidates(store++, i);
            }
        }
        swapLabelCandidates(store, right);
        return store;
    }

    private void swapLabelCandidates(int a, int b) {
        if (a == b) return;
        LabelCandidate tmp = labelCandidates.get(a);
        labelCandidates.set(a, labelCandidates.get(b));
        labelCandidates.set(b, tmp);
    }

    private LabelCandidate nextLabelCandidate() {
        if (labelCandidateCount == labelCandidates.size()) {
            labelCandidates.add(new LabelCandidate());
        }
        return labelCandidates.get(labelCandidateCount++);
    }

    private String distanceString(int distance) {
        if (distance >= 0 && distance < DISTANCE_CACHE_MAX) return DISTANCE_CACHE[distance];
        distanceScratch.setLength(0);
        distanceScratch.append(distance).append('m');
        return distanceScratch.toString();
    }

        static float labelScaleForDepth(double depth, float fovDegrees,
                                        boolean enabled, double userScale) {
        double baseScale = clampLabelScale(userScale);
        if (!enabled) return (float) baseScale;
        if (depth <= 0.0 || !Double.isFinite(depth)) return (float) baseScale;

        double currentFov = Math.max(1.0, Math.min(179.0, fovDegrees));
        double fovScale = Math.tan(Math.toRadians(LABEL_SCALE_BASELINE_FOV_DEGREES) * 0.5)
                / Math.tan(Math.toRadians(currentFov) * 0.5);
        // Distance scaling may make far labels smaller, but the user's configured
        // scale remains the maximum so walking into a waypoint cannot make text huge.
        double scale = Math.min(1.0, fovScale * LABEL_SCALE_REFERENCE_DEPTH / depth);
        return (float) clampLabelScale(baseScale * scale);
    }

        private static double clampLabelScale(double scale) {
        double safe = Double.isFinite(scale) ? scale : 1.0;
        return Math.max(LABEL_SCALE_MIN, Math.min(LABEL_SCALE_MAX, safe));
    }

    private static double labelRowAdvance(Font font, float scale) {
        return (font.lineHeight + DISTANCE_ROW_GAP) * scale;
    }

    /**
     * Draws centered text with a label backdrop. The fractional anchor prevents
     * visible one-pixel jumps during FOV animation.
     */
    private void drawCenteredLabel(GuiGraphicsExtractor g, Font font, String text,
                                   double cx, double top, int argb, float alpha,
                                   float scale) {
        int width = font.width(text);
        double left = cx - (width * scale) / 2.0;
        int drawX = (int) Math.floor(left);
        int drawY = (int) Math.floor(top);
        float subpixelX = (float) (left - drawX);
        float subpixelY = (float) (top - drawY);

        g.pose().pushMatrix();
        g.pose().translate(drawX + subpixelX, drawY + subpixelY);
        if (scale != 1.0f) {
            g.pose().scale(scale, scale);
        }
        if (config.showLabelBackdrop()) {
            g.fill(-BACKDROP_PAD_X, -BACKDROP_PAD_Y,
                    width + BACKDROP_PAD_X, font.lineHeight - 1 + BACKDROP_PAD_Y,
                    RenderHelpers.withAlpha(LABEL_BACKDROP_ARGB, alpha));
        }
        g.text(font, text, 0, 0, argb, config.showLabelTextShadow());
        g.pose().popMatrix();
    }

    private static String routeProgressText(WaypointGroup group) {
        return formatProgressPercent(RouteProgress.snapshot(group).percentComplete);
    }

    static String formatProgressPercent(double percent) {
        double safePercent = Double.isFinite(percent)
                ? Math.max(0.0, Math.min(100.0, percent))
                : 0.0;
        long tenths = Math.round(safePercent * 10.0);
        return (tenths / 10) + "." + (tenths % 10) + "%";
    }

    private String labelFor(WaypointGroup g, int i, Waypoint w,
                            boolean hasSubwaypoints) {
        if (w.hasName()) return translatedName(w.name());
        if (g.isSubwaypoint(i) || (hasSubwaypoints && g.loadMode() == WaypointGroup.LoadMode.STATIC)) {
            return g.displayIndexLabel(i);
        }
        if (g.loadMode() == WaypointGroup.LoadMode.STATIC) return indexLabel(i + 1);

        int mainOrdinal = hasSubwaypoints ? g.mainOrdinal(i) : i + 1;
        return indexLabel(mainOrdinal);
    }

    static boolean shouldShowDungeonWaypointName(Waypoint waypoint) {
        return waypoint != null && waypoint.hasName();
    }

    private String translatedName(String raw) {
        if (raw.indexOf('&') < 0) return raw;
        String cached = nameTranslationCache.get(raw);
        if (cached == null) {
            cached = AmpersandFormatting.translate(raw);
            nameTranslationCache.put(raw, cached);
        }
        return cached;
    }

    private String indexLabel(int number) {
        if (number <= 0 || number >= INDEX_LABEL_CACHE_MAX) return "#" + number;

        String cached = indexLabelCache[number];
        if (cached == null) {
            cached = "#" + number;
            indexLabelCache[number] = cached;
        }
        return cached;
    }

    static void drawScreenLine(GuiGraphicsExtractor g, double x1, double y1,
                               double x2, double y2, int argb, double thickness) {
        RenderHelpers.drawScreenLine(g, x1, y1, x2, y2, argb, thickness);
    }

    static double crispHudLineThickness(double thickness) {
        return RenderHelpers.crispHudLineThickness(thickness);
    }

    static int screenLineSampleCount(double dx, double dy) {
        return RenderHelpers.screenLineSampleCount(dx, dy);
    }

    private static final class LabelCandidate {
        WaypointGroup group;
        Waypoint waypoint;
        String routeProgressText;
        int index;
        boolean hasSubwaypoints;
        double screenX;
        double screenY;
        double distanceSquared;
        int nameColor;
        float alpha;
        float scale;

        void set(WaypointGroup group, int index, Waypoint waypoint, String routeProgressText,
                 boolean hasSubwaypoints, double screenX,
                 double screenY, double distanceSquared, int nameColor, float alpha,
                 float scale) {
            this.group = group;
            this.index = index;
            this.waypoint = waypoint;
            this.routeProgressText = routeProgressText;
            this.hasSubwaypoints = hasSubwaypoints;
            this.screenX = screenX;
            this.screenY = screenY;
            this.distanceSquared = distanceSquared;
            this.nameColor = nameColor;
            this.alpha = alpha;
            this.scale = scale;
        }

        void clear() {
            group = null;
            waypoint = null;
            routeProgressText = null;
            index = 0;
            hasSubwaypoints = false;
            screenX = 0.0;
            screenY = 0.0;
            distanceSquared = 0.0;
            nameColor = 0;
            alpha = 0.0f;
            scale = 0.0f;
        }
    }
}
