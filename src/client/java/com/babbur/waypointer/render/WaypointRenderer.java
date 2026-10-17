package com.babbur.waypointer.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteProgress;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.WaypointVisibility;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.crystal.CrystalHollowsStructureFolder;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.text.AmpersandFormatting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
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
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;

public final class WaypointRenderer extends WaypointWorldRenderer implements HudElement {

    private static final Comparator<LabelCandidate> LABEL_NEAREST_FIRST =
            Comparator.comparingDouble((LabelCandidate candidate) -> candidate.distanceSquared)
                    .thenComparingInt(candidate -> candidate.collectionOrder);

    private static final String[] INDEX_LABELS = new String[INDEX_LABEL_CACHE_MAX];

    private final WorldScreenProjector labelProjector = new WorldScreenProjector();
    private final double[] labelScreenScratch = new double[2];
    private final double[] boxScreenScratch = new double[16];
    private final boolean[] boxCornerVisible = new boolean[8];
    private double projectedBoxMinX;
    private double projectedBoxMinY;
    private double projectedBoxMaxX;
    private double projectedBoxMaxY;
    private final ArrayList<LabelCandidate> labelCandidates = new ArrayList<>();
    private final ArrayList<LabelCandidate> drawnLabelCandidates = new ArrayList<>();
    private final Object2IntOpenCustomHashMap<LabelCandidate> labelPositions = newLabelPositionIndex();
    private int labelCandidateCount;

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, null);
    }

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config,
                            DungeonConfig dungeonConfig) {
        super(manager, config, dungeonConfig);
    }

    /** Emits world geometry into a supplied sink. */
    public boolean emitWorldGeometryFor(LevelRenderContext ctx, PoseStack poseStack,
                                        GeometrySink sink, net.minecraft.world.phys.Vec3 origin,
                                        boolean gpuDepthTestOwnsOcclusion) {
        boolean previous = trustGpuDepthTest;
        trustGpuDepthTest = gpuDepthTestOwnsOcclusion;
        try {
            return emitWorldGeometry(ctx, poseStack, sink, origin, false);
        } finally {
            trustGpuDepthTest = previous;
        }
    }

    public long depthVisibilityFingerprintFor(List<WaypointGroup> groups,
                                              net.minecraft.world.phys.Vec3 cameraPosition) {
        Minecraft mc = Minecraft.getInstance();
        return depthVisibilityFingerprint(groups, mc, mc.level, cameraPosition);
    }

    public long blockShapeFingerprintFor(List<WaypointGroup> groups) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.phys.Vec3 cameraPosition =
                MinecraftCompat.mainCamera(mc.gameRenderer).position();
        net.minecraft.world.phys.Vec3 playerPosition =
                mc.player == null ? null : mc.player.position();
        return blockShapeFingerprint(groups, mc.level, cameraPosition, playerPosition);
    }

    public long worldVisibilityFingerprintFor(List<WaypointGroup> groups,
                                              net.minecraft.world.phys.Vec3 cameraPosition,
                                              net.minecraft.world.phys.Vec3 playerPosition) {
        return worldVisibilityFingerprint(groups, cameraPosition, playerPosition);
    }

    public int reserveActivePaintsFor(List<WaypointGroup> groups) {
        return reserveActivePaints(groups);
    }

    public void install() {
        WorldOverlayCompat.register(this::onWorldRender);
        // CHAT placement makes labels follow the F1 hide-GUI state.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, LABEL_HUD_ID, this);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
        boolean drawIrisHudBoxes = IrisShaderFallback.shouldUse(config)
                && !com.babbur.waypointer.render.gpu.OverlayRenderer.ownsWorldGeometry();
        boolean drawEditModeSubtitle = config.showEditModeSubtitle()
                && WaypointRepositionMode.isEditModeEnabled();


        var groups = manager.activeGroups();
        prepareSkipFades(groups);

        Minecraft mc = Minecraft.getInstance();
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = MinecraftCompat.mainCamera(renderer);
        if (!camera.isInitialized()) return;

        Font font = mc.font;
        ClientLevel level = mc.level;
        Vec3 camPos = camera.position();
        prepareDepthVisibilityCache(level, camPos);
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        labelProjector.prepare(renderer, camera, drawIrisHudBoxes || config.labelFollowCameraEffects());
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        if (drawEditModeSubtitle) {
            drawEditModeSubtitle(g, font, screenW, screenH);
        }
        if (groups.isEmpty()) return;
        int labelBudget = config.maxWaypointLabels();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        clearLabelCandidates();

        if (drawIrisHudBoxes) {
            drawIrisHudBoxes(g, mc, level, camPos, playerPos, screenW, screenH, groups,
                    maxStaticDistanceSq, nearHideDistanceSq);
        }

        {
            if (drawIrisHudBoxes && !config.labelFollowCameraEffects()) {
                labelProjector.prepare(renderer, camera, false);
            }
            for (WaypointGroup group : groups) {
                drawGroupLabels(g, font, mc, level, camPos, playerPos, screenW, screenH, group);
            }
            if (labelCandidateCount > 0) {
                drawBudgetedLabels(g, font, labelBudget > 0 ? Math.min(labelBudget, labelCandidateCount) : labelCandidateCount);
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
        for (WaypointGroup group : groups) {
            drawIrisHudGroupBoxes(g, mc, level, camPos, playerPos, screenW, screenH,
                    group, maxStaticDistanceSq, nearHideDistanceSq);
        }
    }

    private void drawIrisHudGroupBoxes(GuiGraphicsExtractor g, Minecraft mc, ClientLevel level,
                                       Vec3 camPos, Vec3 playerPos, int screenW, int screenH,
                                       WaypointGroup group, double maxStaticDistanceSq,
                                       double nearHideDistanceSq) {
        int currentIdx = group.currentIndex();


        forEachFadingVisibleIndex(group,
                i -> {
            Waypoint waypoint = group.get(i);
            WaypointerConfig appearance = WaypointStyles.resolve(config, group, waypoint);
            WaypointerConfig.BoxStyle style = hudFallbackBoxStyle(appearance.effectiveBoxStyle());
            if (style != WaypointerConfig.BoxStyle.OUTLINED && style != WaypointerConfig.BoxStyle.FILLED_OUTLINED) return;
            float outlineOpacity = (float) appearance.waypointOutlineOpacity();
            if (outlineOpacity <= 0) return;
            if (!withinRouteDistance(appearance, waypoint, camPos)) return;
            boolean depthChecked = lineOfSight(group, waypoint);
            if (!shouldRenderWaypointWorld(group, i, currentIdx, appearance.showCompleted(),
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthChecked, mc, level)) {
                return;
            }
            State state = stateFor(group, i, currentIdx);
            float alpha = alphaFor(group, i, state) * outlineOpacity;
            int outlineColor = appearance.resolvedWaypointOutlineColor(
                    resolvedWaypointColor(group, i, waypoint.color()));
            int argb = RenderHelpers.withAlpha(0xFF000000 | outlineColor, alpha);
            if (!projectBoxCorners(level, group, waypoint, screenW, screenH)) return;

            double outlineThickness = appearance.waypointOutlineThickness();
            for (int edge = 0; edge < BOX_EDGE_A.length; edge++) {
                int a = BOX_EDGE_A[edge];
                int b = BOX_EDGE_B[edge];
                if (!boxCornerVisible[a] || !boxCornerVisible[b]) continue;
                RenderHelpers.drawScreenLine(g,
                        boxScreenScratch[a * 2], boxScreenScratch[a * 2 + 1],
                        boxScreenScratch[b * 2], boxScreenScratch[b * 2 + 1],
                        argb, outlineThickness, appearance.renderAntialiasing());
            }
        });
    }

    private boolean projectBoxCorners(ClientLevel level, WaypointGroup group, Waypoint waypoint, int screenW, int screenH) {
        projectedBoxMinX = Double.POSITIVE_INFINITY;
        projectedBoxMinY = Double.POSITIVE_INFINITY;
        projectedBoxMaxX = Double.NEGATIVE_INFINITY;
        projectedBoxMaxY = Double.NEGATIVE_INFINITY;

        populateVisualWaypointBoxBounds(level, group, waypoint, waypointBoxBoundsScratch);
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
                                 WaypointGroup group) {
        int currentIdx = group.currentIndex();
        boolean hasSubwaypoints = group.hasSubwaypoints();
        String[] progressText = {null};
        boolean dungeonRoomRoute = isDungeonRoomRoute(group);
        int firstCandidate = labelCandidateCount;

        forEachFadingVisibleIndex(group,
                i -> {
            if (dungeonRoomRoute && !isFocusedDungeonRouteLabel(group, i)
                    && (WaypointSkipFade.get(group) == null
                    || !WaypointSkipFade.get(group).isOutgoing(i))) return;
            if (shouldHideStaticReached(group, i)) return;

            Waypoint w = group.get(i);
            WaypointerConfig appearance = WaypointStyles.resolve(config, group, w);
            if (!appearance.showLabels()) return;
            if (!appearance.showWaypointNames() && !appearance.showWaypointDistances()
                    && !appearance.showRouteProgress() && !appearance.labelShowRouteName()
                    && !appearance.labelShowCoordinates() && !appearance.showWaypointRoleBadge()
                    && "NONE".equals(appearance.waypointIcon())) return;
            double labelLift = LABEL_ANCHOR_LIFT + appearance.labelHeightOffset();
            boolean colorizeNames = appearance.matchWaypointTextToWaypointColor();
            boolean scaleLabelsWithDistance = appearance.scaleWaypointTextWithDistance();
            double configuredLabelScale = appearance.labelScale();
            if (shouldHideNearPlayer(w, playerPos, appearance.hideWaypointsNearPlayer()
                    ? WaypointVisibility.squaredRadius(appearance.hideWaypointsNearRadius()) : 0)) return;
            if (shouldHideNearPlayer(w, playerPos, appearance.hideWaypointLabelsNearPlayer()
                    ? WaypointVisibility.squaredRadius(appearance.hideWaypointLabelsNearRadius()) : 0)) return;
            State state = stateFor(group, i, currentIdx);
            if (shouldHideCompletedSequenceWaypoint(group, i, currentIdx, state, appearance.showCompleted(), w)) return;
            if (w.hasFlag(Waypoint.FLAG_HIDE_NAME)) return;

            populateWaypointRenderAnchor(level, w, waypointBoxBoundsScratch);
            double ax = waypointBoxBoundsScratch[0];
            double ay = waypointBoxBoundsScratch[1] - 0.5 + labelLift;
            double az = waypointBoxBoundsScratch[2];
            double rx = ax - camPos.x, ry = ay - camPos.y, rz = az - camPos.z;
            double distanceSq = rx * rx + ry * ry + rz * rz;
            if (!withinRouteDistance(appearance, w, camPos)) return;
            double labelMax = appearance.labelMaxDistance();
            if (labelMax > 0 && distanceSq > labelMax * labelMax) return;
            if (isStaticBeyondDistanceLimit(group, distanceSq, squaredDistanceLimit(appearance.maxStaticWaypointRenderDistance()))) {
                return;
            }

            if (!labelProjector.project(ax, ay, az, screenW, screenH, labelScreenScratch)) {
                return;
            }
            double sx = labelScreenScratch[0];
            double sy = labelScreenScratch[1];
            if (lineOfSight(group, w)
                    && !shouldRenderProjectedDepthCheckedWaypoint(
                            mc, level, w, sx, sy, screenW, screenH)) {
                return;
            }
            if (!WaypointLabelAppearance.nearScreen(sx, sy, screenW, screenH)) return;

            float labelScale = labelScaleForDepth(
                    labelProjector.depth(ax, ay, az),
                    labelProjector.fovDegrees(),
                    scaleLabelsWithDistance,
                    configuredLabelScale);
            float stateAlpha = alphaFor(group, i, state);
            float alpha = stateAlpha * (float) appearance.labelOpacity();
            if (!labelAlphaVisible(alpha) && (!appearance.showLabelBackdrop()
                    || !labelAlphaVisible(stateAlpha * (float) appearance.labelBackdropOpacity()))) return;
            int nameColor = colorizeNames && appearance.showWaypointNames()
                    ? 0xFF000000 | resolvedWaypointColor(group, i, w.color())
                    : NAME_ARGB;
            LabelCandidate candidate = nextLabelCandidate();
            candidate.collectionOrder = labelCandidateCount - 1;
            if (appearance.showRouteProgress() && !group.temp() && progressText[0] == null
                    && group.loadMode() == WaypointGroup.LoadMode.STATIC) progressText[0] = routeProgressText(group);
            candidate.set(group, i, w, appearance.showRouteProgress() && !group.temp()
                            ? progressText[0] != null ? progressText[0] : routeProgressText(group, i) : null,
                    hasSubwaypoints, sx, sy, distanceSq, nameColor, alpha, labelScale);
            candidate.appearance = appearance;
            candidate.backgroundAlpha = stateAlpha * (float) appearance.labelBackdropOpacity();
            candidate.focused = WaypointLabelAppearance.focused(sx, sy, screenW * .5, screenH * .5);
            candidate.current = state == State.CURRENT;
            candidate.detail = WaypointLabelAppearance.showDetail(appearance, distanceSq, candidate.focused);
            prepareCandidateRows(candidate);
            if (candidate.rows.isEmpty() && ("NONE".equals(appearance.waypointIcon()) || !labelAlphaVisible(alpha))) {
                candidate.clear();
                labelCandidateCount--;
            }
        });
        if (group.loadMode() == WaypointGroup.LoadMode.SEQUENCE) {
            labelCandidateCount = retainDistinctSequenceLabels(labelCandidates, firstCandidate, labelCandidateCount, labelPositions);
        }
    }

    static Object2IntOpenCustomHashMap<LabelCandidate> newLabelPositionIndex() {
        var positions = new Object2IntOpenCustomHashMap<LabelCandidate>(new Hash.Strategy<>() {
            @Override public int hashCode(LabelCandidate label) {
                if (label == null) return 0;
                int hash = System.identityHashCode(label.group);
                hash = 31 * hash + Double.hashCode(label.waypoint.centerX());
                hash = 31 * hash + Double.hashCode(label.waypoint.centerY());
                return 31 * hash + Double.hashCode(label.waypoint.centerZ());
            }

            @Override public boolean equals(LabelCandidate a, LabelCandidate b) {
                return a == b || a != null && b != null && a.group == b.group
                        && a.waypoint.centerX() == b.waypoint.centerX()
                        && a.waypoint.centerY() == b.waypoint.centerY()
                        && a.waypoint.centerZ() == b.waypoint.centerZ();
            }
        });
        positions.defaultReturnValue(-1);
        return positions;
    }

    static int retainDistinctSequenceLabels(List<LabelCandidate> labels, int start, int count,
                                           Object2IntOpenCustomHashMap<LabelCandidate> positions) {
        positions.clear();
        for (int i = start; i < count; i++) {
            LabelCandidate candidate = labels.get(i);
            int j = positions.getInt(candidate);
            if (j < 0) {
                positions.put(candidate, i);
            } else {
                LabelCandidate other = labels.get(j);
                int current = candidate.group.currentIndex();
                int distance = Math.abs(candidate.index - current);
                int otherDistance = Math.abs(other.index - current);
                if (distance < otherDistance || distance == otherDistance && candidate.index < other.index) {
                    positions.removeInt(other);
                    positions.put(candidate, j);
                    labels.set(j, candidate);
                    labels.set(i, other);
                }
                LabelCandidate removed = labels.get(i);
                labels.set(i, labels.get(--count));
                labels.set(count, removed);
                removed.clear();
                i--;
            }
        }
        positions.clear();
        return count;
    }

    static int retainDistinctCompassLabels(List<LabelCandidate> labels, int count) {
        for (int i = 0; i < count; i++) {
            LabelCandidate target = labels.get(i);
            if (!CompassMarkerState.isTarget(target.waypoint)) continue;
            // Only the visible generated copy replaces the compass navigation label.
            for (int j = 0; j < count; j++) {
                LabelCandidate structure = labels.get(j);
                if (!structure.group.runtimeOnly()
                        || !structure.group.id().startsWith(CrystalHollowsStructureFolder.GROUP_PREFIX)
                        || target.waypoint.centerX() != structure.waypoint.centerX()
                        || target.waypoint.centerY() != structure.waypoint.centerY()
                        || target.waypoint.centerZ() != structure.waypoint.centerZ()) continue;
                structure.focused |= target.focused;
                labels.set(i, labels.get(--count));
                labels.set(count, target);
                target.clear();
                i--;
                break;
            }
        }
        return count;
    }

    private void drawBudgetedLabels(GuiGraphicsExtractor g, Font font, int count) {
        labelCandidateCount = retainDistinctCompassLabels(labelCandidates, labelCandidateCount);
        count = Math.min(count, labelCandidateCount);
        if (count <= 0) return;
        boolean overlap = false;
        for (int i = 0; i < labelCandidateCount; i++) overlap |= labelCandidates.get(i).appearance.preventLabelOverlap();
        if (overlap) {
            for (int i = 0; i < labelCandidateCount; i++) prepareCandidateBounds(font, labelCandidates.get(i));
            labelCandidates.subList(0, labelCandidateCount).sort(Comparator
                    .comparingInt((LabelCandidate c) -> WaypointLabelAppearance.priority(c.current, c.focused))
                    .thenComparingDouble(c -> c.distanceSquared));
        } else {
            if (count < labelCandidateCount) selectNearestLabels(count);
            labelCandidates.subList(0, count).sort(LABEL_NEAREST_FIRST);
        }
        selectLabelsForDrawing(labelCandidates, labelCandidateCount, count, overlap, drawnLabelCandidates);
        for (LabelCandidate candidate : drawnLabelCandidates) drawCandidateLabel(g, font, candidate);
    }

    static void selectLabelsForDrawing(List<LabelCandidate> candidates, int candidateCount, int budget,
                                       boolean preventOverlap, List<LabelCandidate> selected) {
        selected.clear();
        for (int i = 0; i < candidateCount && selected.size() < budget; i++) {
            LabelCandidate candidate = candidates.get(i);
            if (preventOverlap) acceptLabel(candidate, selected);
            else selected.add(candidate);
        }
        // Selection keeps its distance/route priority; painting puts nearer labels on top.
        selected.sort(LABEL_NEAREST_FIRST.reversed());
    }

    static boolean acceptLabel(LabelCandidate candidate, List<LabelCandidate> drawn) {
        if (candidate.appearance.preventLabelOverlap()) {
            for (int i = 0; i < drawn.size(); i++) {
                LabelCandidate other = drawn.get(i);
                if (WaypointLabelAppearance.overlaps(candidate.left, candidate.top, candidate.right, candidate.bottom,
                        other.left, other.top, other.right, other.bottom)) return false;
            }
        }
        drawn.add(candidate);
        return true;
    }

    private void prepareCandidateRows(LabelCandidate c) {
        WaypointerConfig a = c.appearance;
        String role = labelRole(c.group, c.index, c.waypoint, stateFor(c.group, c.index, c.group.currentIndex()));
        appendLabelRows(c.rows, a, c.group, c.index, c.waypoint, c.detail, role, Math.sqrt(c.distanceSquared), c.routeProgressText);
    }

    private void prepareCandidateBounds(Font font, LabelCandidate c) {
        WaypointerConfig a = c.appearance;
        int textWidth = 0;
        for (String row : c.rows) textWidth = Math.max(textWidth, font.width(row));
        double width = WaypointLabelAppearance.labelWidth(textWidth, !c.rows.isEmpty(), !"NONE".equals(a.waypointIcon()), c.scale);
        c.left = c.screenX - width / 2 - 2;
        c.right = c.screenX + width / 2 + 2;
        c.top = c.screenY - ("NONE".equals(a.waypointIcon()) ? 0 : 12 * c.scale) - 2;
        c.bottom = c.screenY + c.rows.size() * labelRowAdvance(font, c.scale) + 2;
    }

    private void drawCandidateLabel(GuiGraphicsExtractor g, Font font, LabelCandidate c) {
        WaypointLabelAppearance.drawIcon(g, c.appearance.waypointIcon(), c.screenX, c.screenY - 8 * c.scale,
                c.scale, RenderHelpers.withAlpha(c.nameColor, c.alpha));
        double rowY = c.screenY;
        for (int i = 0; i < c.rows.size(); i++) {
            drawCenteredLabel(g, font, c.rows.get(i), c.screenX, rowY,
                    RenderHelpers.withAlpha(i == 0 && hasNameRow(c.appearance, c.group, c.waypoint) ? c.nameColor : DISTANCE_ARGB, c.alpha),
                    c.backgroundAlpha, c.scale, c.appearance);
            rowY += labelRowAdvance(font, c.scale);
        }
    }

    private void clearLabelCandidates() {
        drawnLabelCandidates.clear();
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
        LabelCandidate pivot = labelCandidates.get(pivotIndex);
        swapLabelCandidates(pivotIndex, right);
        int store = left;
        for (int i = left; i < right; i++) {
            if (LABEL_NEAREST_FIRST.compare(labelCandidates.get(i), pivot) < 0) {
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

    public static float labelScaleForDepth(double depth, float fovDegrees,
                                        boolean enabled, double userScale) {
        double baseScale = clampLabelScale(userScale);
        if (!enabled) return (float) baseScale;
        if (depth <= 0.0 || !Double.isFinite(depth)) return (float) baseScale;

        double currentFov = Math.max(1.0, Math.min(179.0, fovDegrees));
        double fovScale = Math.tan(Math.toRadians(LABEL_SCALE_BASELINE_FOV_DEGREES) * 0.5)
                / Math.tan(Math.toRadians(currentFov) * 0.5);
        // Cap distance scaling at the user's size to avoid huge nearby labels.
        double scale = Math.min(1.0, fovScale * LABEL_SCALE_REFERENCE_DEPTH / depth);
        return (float) clampLabelScale(baseScale * scale);
    }

        private static double clampLabelScale(double scale) {
        double safe = Double.isFinite(scale) ? scale : 1.0;
        return Math.max(LABEL_SCALE_MIN, Math.min(LABEL_SCALE_MAX, safe));
    }

    public static boolean labelAlphaVisible(float alpha) {
        return alpha >= 4.0f / 255.0f;
    }

    private static double labelRowAdvance(Font font, float scale) {
        return (font.lineHeight + DISTANCE_ROW_GAP) * scale;
    }

    // Fractional centering avoids one-pixel jumps during FOV animation.
    public static void drawCenteredLabel(GuiGraphicsExtractor g, Font font, String text,
                                   double cx, double top, int argb, float alpha,
                                   float scale, WaypointerConfig appearance) {
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
        if (appearance.showLabelBackdrop()) {
            g.fill(-BACKDROP_PAD_X, -BACKDROP_PAD_Y,
                    width + BACKDROP_PAD_X, font.lineHeight - 1 + BACKDROP_PAD_Y,
                    RenderHelpers.withAlpha(LABEL_BACKDROP_ARGB, alpha));
        }
        if (labelAlphaVisible((argb >>> 24) / 255.0f)) g.text(font, text, 0, 0, argb, appearance.showLabelTextShadow());
        g.pose().popMatrix();
    }

    public static String routeProgressText(WaypointGroup group) {
        return formatProgressPercent(RouteProgress.snapshot(group).percentComplete);
    }

    /** Sequence context labels describe their own stage, rather than repeating the current stage. */
    public static String routeProgressText(WaypointGroup group, int index) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC || index == group.currentIndex()) {
            return routeProgressText(group);
        }
        int stage = group.isSubwaypoint(index) ? group.parentMainIndex(index) : index;
        int total = 0, preceding = 0;
        for (int i = 0; i < group.size(); i++) {
            if (group.isSubwaypoint(i) || !group.isWaypointEnabled(i)) continue;
            total++;
            if (i < stage) preceding++;
        }
        return formatProgressPercent(total == 0 ? 0 : preceding * 100.0 / total);
    }

    static String formatProgressPercent(double percent) {
        double safePercent = Double.isFinite(percent)
                ? Math.max(0.0, Math.min(100.0, percent))
                : 0.0;
        long tenths = Math.round(safePercent * 10.0);
        return (tenths / 10) + "." + (tenths % 10) + "%";
    }

    public static String labelRole(WaypointGroup group, int index, Waypoint waypoint, State state) {
        if (waypoint.isSubwaypoint()) return "sub";
        if (state == State.CURRENT) return "current";
        if (state == State.COMPLETED) return "completed";
        return index == group.nextMainIndexAfter(group.currentIndex()) ? "next" : "upcoming";
    }

    public static List<String> labelRows(WaypointerConfig appearance, WaypointGroup group, int index,
                                         Waypoint waypoint, boolean detail, String role, double distance,
                                         String progressText) {
        List<String> rows = new ArrayList<>(6);
        appendLabelRows(rows, appearance, group, index, waypoint, detail, role, distance, progressText);
        return rows;
    }

    private static void appendLabelRows(List<String> rows, WaypointerConfig appearance, WaypointGroup group,
                                        int index, Waypoint waypoint, boolean detail, String role,
                                        double distance, String progressText) {
        if (hasNameRow(appearance, group, waypoint)) {
            String label = labelFor(group, index, waypoint, group.hasSubwaypoints());
            if (label.indexOf('{') >= 0) {
                label = label.replace("{distance}", distanceLabel((int) distance))
                        .replace("{index}", Integer.toString(group.mainOrdinal(index)))
                        .replace("{total}", Integer.toString(group.mainWaypointCount()))
                        .replace("{route}", AmpersandFormatting.translate(group.name()));
            }
            rows.add(label);
        }
        if (appearance.showWaypointRoleBadge()) rows.add(net.minecraft.client.resources.language.I18n.get("waypointer.label.role." + role));
        if (detail) {
            if (appearance.labelShowRouteName()) rows.add(group.name());
            if (appearance.showRouteProgress() && progressText != null) rows.add(progressText);
            if (appearance.showWaypointDistances()) rows.add(distanceLabel((int) distance));
            if (appearance.labelShowCoordinates()) rows.add(waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z());
        }
    }

    public static boolean hasNameRow(WaypointerConfig appearance, WaypointGroup group, Waypoint waypoint) {
        return appearance.showWaypointNames() && (!isDungeonRoomRoute(group) || shouldShowDungeonWaypointName(waypoint));
    }

    public static String labelFor(WaypointGroup g, int i, Waypoint w, boolean hasSubwaypoints) {
        if (w.hasName()) return AmpersandFormatting.translate(w.name());
        if (g.isSubwaypoint(i) || (hasSubwaypoints && g.loadMode() == WaypointGroup.LoadMode.STATIC)) {
            return g.displayIndexLabel(i);
        }
        if (g.loadMode() == WaypointGroup.LoadMode.STATIC) return indexLabel(i + 1);
        return indexLabel(hasSubwaypoints ? g.mainOrdinal(i) : i + 1);
    }

    static String distanceLabel(int distance) {
        return distance >= 0 && distance < DISTANCE_CACHE_MAX ? DISTANCE_CACHE[distance] : distance + "m";
    }

    private static String indexLabel(int number) {
        if (number < 0 || number >= INDEX_LABELS.length) return "#" + number;
        String label = INDEX_LABELS[number];
        if (label == null) INDEX_LABELS[number] = label = "#" + number;
        return label;
    }

    static boolean shouldShowDungeonWaypointName(Waypoint waypoint) {
        return waypoint != null && waypoint.hasName();
    }

    static final class LabelCandidate {
        WaypointerConfig appearance;
        final ArrayList<String> rows = new ArrayList<>(6);
        boolean focused, current, detail;
        double left, top, right, bottom;
        float backgroundAlpha;
        WaypointGroup group;
        Waypoint waypoint;
        String routeProgressText;
        int index;
        int collectionOrder;
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
            appearance = null;
            rows.clear();
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
