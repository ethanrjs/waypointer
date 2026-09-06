package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteProgress;
import com.babbur.waypointer.core.SequenceRoleColor;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.WaypointVisibility;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.text.AmpersandFormatting;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.IntConsumer;

class WaypointWorldRenderer {

    protected static final Identifier LABEL_HUD_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "waypoint_labels");

    protected static final double LABEL_ANCHOR_LIFT = 1.6;

    protected static final int NAME_ARGB = 0xFFFFFFFF;

    protected static final int DISTANCE_ARGB = 0xFFCCCCCC;

    protected static final int LABEL_BACKDROP_ARGB = 0xB0000000;

    protected static final float SEQUENCE_CONTEXT_ALPHA = 0.35f;

    protected static final int BACKDROP_PAD_X = 2;

    protected static final int BACKDROP_PAD_Y = 1;

    protected static final int DISTANCE_ROW_GAP = 1;
    protected static final int SCREEN_CULL_MARGIN = 64;
    protected static final double LABEL_SCALE_REFERENCE_DEPTH = 24.0;
    protected static final double LABEL_SCALE_BASELINE_FOV_DEGREES = 70.0;
    protected static final float LABEL_SCALE_MIN = 0.25f;
    protected static final float LABEL_SCALE_MAX = 4.0f;
    protected static final double SMALL_SUBWAYPOINT_SIZE = 1.0 / 16.0;
    protected static final double BLOCK_SHAPE_EPSILON = 1.0E-6;
    protected static final double ROUTE_LINE_CAMERA_CLEARANCE = 0.25;
    protected static final double ROUTE_LINE_CAMERA_SCREEN_OFFSET = 0.12;
    protected static final int EDIT_MODE_SUBTITLE_ARGB = 0xFF55FFFF;
    protected static final String EDIT_MODE_SUBTITLE_BASE_TEXT = "EDIT MODE";
    protected static final int LINE_OF_SIGHT_SAMPLE_COUNT = 9;
    protected static final int BOX_MIN_X = 0;
    protected static final int BOX_MIN_Y = 1;
    protected static final int BOX_MIN_Z = 2;
    protected static final int BOX_MAX_X = 3;
    protected static final int BOX_MAX_Y = 4;
    protected static final int BOX_MAX_Z = 5;
    protected static final float DUNGEON_ENTRY_PATH_ALPHA = 0.9f;

    protected static final int DISTANCE_CACHE_MAX = 4096;
    protected static final int HUD_LINE_CULL_MARGIN = 64;
    protected static final String[] DISTANCE_CACHE;
    static {
        DISTANCE_CACHE = new String[DISTANCE_CACHE_MAX];
        for (int i = 0; i < DISTANCE_CACHE_MAX; i++) DISTANCE_CACHE[i] = i + "m";
    }
    protected static final int[] BOX_EDGE_A = {0, 1, 3, 2, 4, 5, 7, 6, 0, 1, 2, 3};
    protected static final int[] BOX_EDGE_B = {1, 3, 2, 0, 5, 7, 6, 4, 4, 5, 6, 7};

    protected static final int INDEX_LABEL_CACHE_MAX = 256;

    protected static final int NAME_TRANSLATION_CACHE_MAX = 1024;
    protected final ActiveGroupManager manager;
    protected final WaypointerConfig config;
    protected final DungeonConfig dungeonConfig;

    protected final WaypointVisibilityCache depthVisibility = new WaypointVisibilityCache();
    protected final double[] waypointBoxBoundsScratch = new double[6];
    private final LinkedHashSet<WaypointPaint> activePaintScratch = new LinkedHashSet<>();
    private float beamRotationAnimationTime = Float.NaN;
    private float beamRotationCos;
    private float beamRotationSin;
    private final DungeonEntryPathController dungeonEntryPathController =
            new DungeonEntryPathController();
    private PreparedDungeonEntryPaths preparedDungeonEntryPaths;
    boolean trustGpuDepthTest;

    private record PreparedDungeonEntryPaths(
            long frameToken,
            LevelRenderContext context,
            List<DungeonEntryPathController.Submission> submissions) {
        private PreparedDungeonEntryPaths {
            submissions = submissions == null ? List.of() : List.copyOf(submissions);
        }

        private boolean belongsTo(long token, LevelRenderContext expectedContext) {
            return frameToken == token && context == expectedContext;
        }
    }

    public WaypointWorldRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, null);
    }

    public WaypointWorldRenderer(ActiveGroupManager manager, WaypointerConfig config,
                            DungeonConfig dungeonConfig) {
        this.manager = manager;
        this.config = config;
        this.dungeonConfig = dungeonConfig;
    }


    public static AABB waypointBoxBounds(ClientLevel level, Waypoint waypoint) {
        if (waypoint == null) return null;
        double[] bounds = new double[6];
        populateWaypointBoxBounds(level, waypoint, bounds);
        return new AABB(
                bounds[BOX_MIN_X], bounds[BOX_MIN_Y], bounds[BOX_MIN_Z],
                bounds[BOX_MAX_X], bounds[BOX_MAX_Y], bounds[BOX_MAX_Z]);
    }

    static void populateWaypointRenderAnchor(ClientLevel level, Waypoint waypoint,
                                              double[] out) {
        populateWaypointBoxBounds(level, waypoint, out);
        double centerX = (out[BOX_MIN_X] + out[BOX_MAX_X]) * 0.5;
        double centerY = (out[BOX_MIN_Y] + out[BOX_MAX_Y]) * 0.5;
        double centerZ = (out[BOX_MIN_Z] + out[BOX_MAX_Z]) * 0.5;
        out[0] = centerX;
        out[1] = centerY;
        out[2] = centerZ;
    }

    // Keep 100% opacity fully opaque.
    protected static final float FILLED_ALPHA_SCALE = 1.0f;
    protected static final float PAINTED_ALPHA_SCALE = 1.0f;
    protected static final float BEAM_ALPHA_SCALE = 0.18f;
    protected static final float BEAM_HALF_WIDTH = 0.12f;
    protected static final float BEACON_TEXTURE_SCALE_THRESHOLD = 96.0f;
    protected static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
    protected static final int BEACON_GLOW_BASE_ALPHA_ARGB = 0x20000000;
    protected static final int DEFAULT_MIN_BUILD_Y = -64;
    protected static final int DEFAULT_MAX_BUILD_Y = 320;

    void onWorldRender(LevelRenderContext ctx) {
        WaypointerRenderPipelines.setAntialiasing(config.renderAntialiasing());
        var groups = manager.activeGroups();
        prepareSkipFades(groups);
        boolean irisHudFallbackActive = IrisShaderFallback.shouldUse(config);
        long frameToken = RenderDiagnostics.beginFrame(groups, config, irisHudFallbackActive);
        clearPreparedDungeonEntryPaths();

        // Retained mode emits through its own sink and stable origin.
        if (com.babbur.waypointer.render.gpu.OverlayRenderer.ownsWorldGeometry()) {
            prepareDungeonEntryPathsForRetained(
                    ctx, frameToken, groups, irisHudFallbackActive);
            return;
        }
        // Keep textures alive for queued 26.2 draws.
        reserveActivePaints(groups);
        if (groups.isEmpty()) return;

        PoseStack ps = ctx.poseStack();
        if (ps == null) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = MinecraftCompat.mainCamera(mc.gameRenderer);
        if (!camera.isInitialized()) return;
        emitWorldGeometry(ctx, ps, GeometrySink.legacy(ctx, ps), camera.position(),
                irisHudFallbackActive);
    }

    /** Emits world geometry relative to the supplied origin. */
    boolean emitWorldGeometry(LevelRenderContext ctx, PoseStack ps, GeometrySink sink,
                              Vec3 origin, boolean irisHudFallbackActive) {
        var groups = manager.activeGroups();
        if (groups.isEmpty()) {
            clearPreparedDungeonEntryPaths();
            return false;
        }

        WaypointerConfig.BoxStyle style = config.effectiveBoxStyle();
        boolean drawLines = worldBoxOutlinesEnabled(style, irisHudFallbackActive);
        boolean drawGlobalFill = boxStyleDrawsRgbFill(style);
        WaypointPaint defaultPaint = config.waypointPainterDefaultPaint();
        boolean drawPaint = config.enableFeatureBloat() && hasPaintedGroup(groups, defaultPaint);
        boolean drawFill = drawGlobalFill
                || style == WaypointerConfig.BoxStyle.PAINT
                || hasFilledSubwaypoint(groups)
                || drawPaint;
        boolean drawBeams = effectiveBeaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF;
        boolean drawTexturedBeams = drawBeams && config.useBeaconBeamTextures();
        boolean drawFlatBeams = drawBeams && !drawTexturedBeams;
        boolean drawRouteLines = config.showRouteLines()
                || dungeonConfig != null && dungeonConfig.showDungeonRouteLines();
        boolean drawDungeonEntryPaths = config.showDungeonEntryPathToFirstWaypoint();
        if (!drawDungeonEntryPaths) clearPreparedDungeonEntryPaths();
        if (!drawLines && !drawFill && !drawPaint && !drawBeams && !drawRouteLines
                && !drawDungeonEntryPaths) return false;
        if (!worldRenderOpacityAllowsAnything(
                config.beaconOpacity(), config.waypointOutlineOpacity(), drawLines,
                drawRouteLines, drawDungeonEntryPaths)) return false;
        boolean hasDepthCheckedWaypoints = hasDepthCheckedWaypoint(groups);
        boolean hasThroughWallWaypoints = hasThroughWallWaypoint(groups) || drawDungeonEntryPaths;
        if (!hasDepthCheckedWaypoints && !hasThroughWallWaypoints) return false;

        if (ps == null || sink == null || origin == null) return false;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = MinecraftCompat.mainCamera(renderer);
        if (!camera.isInitialized()) return false;
        Vec3 camPos = camera.position();
        prepareDepthVisibilityCache(level, camPos);
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();

        ps.pushPose();
        try {
            ps.translate(-origin.x, -origin.y, -origin.z);

        // Starting a fill batch invalidates the shared line consumer, so flush fills first.
        if (drawTexturedBeams && hasThroughWallWaypoints) {
            RenderType beamType = WaypointerRenderPipelines.beaconBeamThroughWalls();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            sink.submit(beamType, (texturedBeams, submittedPose) -> sink.dynamic(() -> {
                for (WaypointGroup g : groups) {
                    emitBeaconBeams(submittedPose, texturedBeams, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                            false, mc, level, true);
                }
            }));
        }
        if (drawTexturedBeams && hasDepthCheckedWaypoints) {
            RenderType beamType = WaypointerRenderPipelines.beaconBeamDepthTested();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            sink.submit(beamType, (texturedBeams, submittedPose) -> sink.dynamic(() -> {
                for (WaypointGroup g : groups) {
                    emitBeaconBeams(submittedPose, texturedBeams, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                            true, mc, level, true);
                }
            }));
        }
        boolean emitStatic = sink.staticGeometryNeeded();
        boolean emitFading = hasSkipFades(groups);
        boolean retainCameraIndependentGeometry = sink.retainsStaticGeometry();
        if ((emitStatic || emitFading) && drawPaint && hasThroughWallWaypoints) {
            for (WaypointGroup g : groups) {
                WaypointPaint paint = effectivePaint(g, defaultPaint);
                if (paint == null || usesSequenceRoleColorPaintFallback(
                        g, defaultPaint, config.colorSequenceWaypointsByRole())) continue;
                WaypointPaintTextureCache.Entry paintTexture =
                        WaypointPaintTextureCache.getRetained(paint);
                if (paintTexture == null) continue;
                sink.submit(paintTexture.throughWalls(),
                        (quads, submittedPose) -> emitGroupGeometry(sink, g, () -> emitPaintedBoxes(
                                submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                false, mc, retainCameraIndependentGeometry)));
            }
        }
        if ((emitStatic || emitFading) && drawPaint && hasDepthCheckedWaypoints) {
            for (WaypointGroup g : groups) {
                WaypointPaint paint = effectivePaint(g, defaultPaint);
                if (paint == null || usesSequenceRoleColorPaintFallback(
                        g, defaultPaint, config.colorSequenceWaypointsByRole())) continue;
                WaypointPaintTextureCache.Entry paintTexture =
                        WaypointPaintTextureCache.getRetained(paint);
                if (paintTexture == null) continue;
                sink.submit(paintTexture.depthTested(),
                        (quads, submittedPose) -> emitGroupGeometry(sink, g, () -> emitPaintedBoxes(
                                submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc, retainCameraIndependentGeometry)));
            }
        }
        if ((emitStatic || emitFading) && (drawFlatBeams || drawFill) && hasThroughWallWaypoints) {
            RenderType quadType = WaypointerRenderPipelines.quadsThroughWalls();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            sink.submit(quadType, (quads, submittedPose) -> {
                if (drawFlatBeams) {
                    for (WaypointGroup g : groups) {
                        emitGroupGeometry(sink, g, () -> emitBeaconBeams(submittedPose, quads, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                                false, mc, level, false));
                    }
                }
                if (drawFill) {
                    for (WaypointGroup g : groups) {
                        boolean paintFallback = config.enableFeatureBloat() && (usesSequenceRoleColorPaintFallback(
                                g, defaultPaint, config.colorSequenceWaypointsByRole())
                                || usesRgbPaintFallback(g, defaultPaint));
                        if (config.enableFeatureBloat() && !paintFallback && !shouldEmitRgbFill(g, defaultPaint)) continue;
                        emitGroupGeometry(sink, g, () -> emitFilledBoxes(submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                drawGlobalFill || style == WaypointerConfig.BoxStyle.PAINT
                                        || paintFallback,
                                false, mc));
                    }
                }
            });
        }
        if ((emitStatic || emitFading) && (drawFlatBeams || drawFill) && hasDepthCheckedWaypoints) {
            RenderType quadType = WaypointerRenderPipelines.quadsDepthTested();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            sink.submit(quadType, (quads, submittedPose) -> {
                if (drawFlatBeams) {
                    for (WaypointGroup g : groups) {
                        emitGroupGeometry(sink, g, () -> emitBeaconBeams(submittedPose, quads, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                                true, mc, level, false));
                    }
                }
                if (drawFill) {
                    for (WaypointGroup g : groups) {
                        boolean paintFallback = config.enableFeatureBloat() && (usesSequenceRoleColorPaintFallback(
                                g, defaultPaint, config.colorSequenceWaypointsByRole())
                                || usesRgbPaintFallback(g, defaultPaint));
                        if (config.enableFeatureBloat() && !paintFallback && !shouldEmitRgbFill(g, defaultPaint)) continue;
                        emitGroupGeometry(sink, g, () -> emitFilledBoxes(submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                drawGlobalFill || style == WaypointerConfig.BoxStyle.PAINT
                                        || paintFallback,
                                true, mc));
                    }
                }
            });
        }
        if (((emitStatic || emitFading) && drawLines || drawRouteLines || drawDungeonEntryPaths)
                && hasThroughWallWaypoints) {
            RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
            List<DungeonEntryPathController.Submission> dungeonEntryPaths = drawDungeonEntryPaths
                    ? dungeonEntryPathsForEmission(ctx, groups, playerPos, level)
                    : List.of();
            boolean submitted = sink.submit(lineType, (lines, submittedPose) -> {
                if (drawDungeonEntryPaths) {
                    sink.dynamic(() -> emitDungeonEntryPaths(submittedPose, lines, dungeonEntryPaths));
                }
                if (drawRouteLines) {
                    sink.dynamic(() -> {
                        for (WaypointGroup g : groups) {
                            if (!routeLinesEnabled(g, config, dungeonConfig)) continue;
                            emitRouteLines(submittedPose, lines, g, camPos, playerPos,
                                    maxStaticDistanceSq, nearHideDistanceSq,
                                    false, mc, level);
                        }
                    });
                }
                if ((emitStatic || emitFading) && drawLines) {
                    for (WaypointGroup g : groups) {
                        emitGroupGeometry(sink, g, () -> emitLineBoxes(submittedPose, lines, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                false, mc));
                    }
                }
            });
            for (DungeonEntryPathController.Submission path : dungeonEntryPaths) {
                RenderDiagnostics.recordDungeonPathSubmission(
                        path.group(), submitted && isDrawableDungeonEntryPath(path.points()));
            }
        }
        if (((emitStatic || emitFading) && drawLines || drawRouteLines) && hasDepthCheckedWaypoints) {
            RenderType lineType = WaypointerRenderPipelines.linesDepthTested();
            sink.submit(lineType, (lines, submittedPose) -> {
                if (drawRouteLines) {
                    sink.dynamic(() -> {
                        for (WaypointGroup g : groups) {
                            if (!routeLinesEnabled(g, config, dungeonConfig)) continue;
                            emitRouteLines(submittedPose, lines, g, camPos, playerPos,
                                    maxStaticDistanceSq, nearHideDistanceSq,
                                    true, mc, level);
                        }
                    });
                }
                if ((emitStatic || emitFading) && drawLines) {
                    for (WaypointGroup g : groups) {
                        emitGroupGeometry(sink, g, () -> emitLineBoxes(submittedPose, lines, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc));
                    }
                }
            });
        }

        } finally {
            ps.popPose();
        }
        return true;
    }

    private void emitDungeonEntryPaths(PoseStack ps, VertexConsumer lines,
                                       Iterable<DungeonEntryPathController.Submission> paths) {
        float alpha = DUNGEON_ENTRY_PATH_ALPHA;
        float width = effectiveOutlineThickness();
        int color = config.dungeonEntryPathColor();
        for (DungeonEntryPathController.Submission path : paths) {
            List<Vec3> points = path.points();
            for (int index = 1; index < points.size(); index++) {
                Vec3 a = points.get(index - 1);
                Vec3 b = points.get(index);
                RenderHelpers.emitLine(lines, ps,
                        (float) a.x, (float) a.y, (float) a.z,
                        (float) b.x, (float) b.y, (float) b.z,
                        color, alpha, width);
            }
        }
    }

    private List<DungeonEntryPathController.Submission> prepareDungeonEntryPaths(
            Iterable<WaypointGroup> groups, Vec3 playerPos, ClientLevel level) {
        DungeonEntryPathController.PreparedPaths prepared =
                dungeonEntryPathController.prepare(
                        groups, playerPos, level,
                        config.showDungeonEntryPathToFollowingWaypoints());
        for (DungeonEntryPathController.LookupDiagnostics lookup : prepared.lookups()) {
            RenderDiagnostics.recordPathLookup(
                    lookup.group(), lookup.result(),
                    lookup.cacheHit(), lookup.cacheAgeNanos());
        }
        return prepared.submissions();
    }

    private void prepareDungeonEntryPathsForRetained(
            LevelRenderContext ctx, long frameToken, List<WaypointGroup> groups,
            boolean irisHudFallbackActive) {
        // Empty capture falls back to a straight tracer without rerunning the pathfinder.
        preparedDungeonEntryPaths = new PreparedDungeonEntryPaths(
                frameToken, ctx, List.of());
        if (ctx == null || groups == null || groups.isEmpty()
                || !config.showDungeonEntryPathToFirstWaypoint()) return;

        boolean drawLines = worldBoxOutlinesEnabled(config.effectiveBoxStyle(), irisHudFallbackActive);
        boolean drawRouteLines = config.showRouteLines()
                || dungeonConfig != null && dungeonConfig.showDungeonRouteLines();
        if (!worldRenderOpacityAllowsAnything(
                config.beaconOpacity(), config.waypointOutlineOpacity(), drawLines,
                drawRouteLines, true)) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Camera camera = MinecraftCompat.mainCamera(mc.gameRenderer);
        if (camera == null || !camera.isInitialized()) return;

        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        List<DungeonEntryPathController.Submission> submissions =
                prepareDungeonEntryPaths(groups, playerPos, level);
        preparedDungeonEntryPaths = new PreparedDungeonEntryPaths(
                frameToken, ctx, submissions);
        for (DungeonEntryPathController.Submission path : submissions) {
            RenderDiagnostics.recordPreparedDungeonPath(
                    path.group(), isDrawableDungeonEntryPath(path.points()));
        }
    }

    private List<DungeonEntryPathController.Submission> dungeonEntryPathsForEmission(
            LevelRenderContext ctx, Iterable<WaypointGroup> groups,
            Vec3 playerPos, ClientLevel level) {
        PreparedDungeonEntryPaths pending = preparedDungeonEntryPaths;
        clearPreparedDungeonEntryPaths();
        if (com.babbur.waypointer.render.gpu.OverlayRenderer.ownsWorldGeometry()) {
            if (pending == null
                    || !pending.belongsTo(RenderDiagnostics.currentFrameToken(), ctx)) {
                return List.of();
            }
            return pending.submissions();
        }
        return prepareDungeonEntryPaths(groups, playerPos, level);
    }

    private void clearPreparedDungeonEntryPaths() {
        preparedDungeonEntryPaths = null;
        RenderDiagnostics.clearPreparedDungeonPaths();
    }

    static boolean isDrawableDungeonEntryPath(List<Vec3> points) {
        return points != null && points.size() >= 2;
    }

    static boolean routeLinesEnabled(WaypointGroup group, WaypointerConfig config,
                                     DungeonConfig dungeonConfig) {
        if (isDungeonRoomRoute(group) && dungeonConfig != null) {
            return dungeonConfig.showDungeonRouteLines();
        }
        return config != null && config.showRouteLines();
    }

    static boolean isDungeonRoomRoute(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    private void emitRouteLines(PoseStack ps, VertexConsumer lines, WaypointGroup g,
                                Vec3 camPos, Vec3 playerPos,
                                double maxStaticDistanceSq, double nearHideDistanceSq,
                                boolean depthCheckedPass, Minecraft mc, ClientLevel level) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float alpha = 0.85f;
        float width = effectiveOutlineThickness();
        int color = config.routeLineColor();
        boolean useEtherwarpHeight = config.useEtherwarpHeight();
        float crouchingEyeHeight = mc.player == null
                ? 0.0f : mc.player.getEyeHeight(Pose.CROUCHING);
        var cameraUp = MinecraftCompat.mainCamera(mc.gameRenderer).upVector();
        Vec3 screenDown = new Vec3(-cameraUp.x(), -cameraUp.y(), -cameraUp.z());

        RouteLineSegmentConsumer emitSegment = (fromIndex, toIndex) -> {
            Waypoint a = g.get(fromIndex);
            Waypoint b = g.get(toIndex);
            if (!routeSegmentHasDepthVisibility(a, b, depthCheckedPass, mc, level)) return;
            Vec3 start = routeLineStart(
                    level, a, useEtherwarpHeight, crouchingEyeHeight,
                    waypointBoxBoundsScratch);
            populateWaypointRenderAnchor(level, b, waypointBoxBoundsScratch);
            Vec3 end = new Vec3(
                    waypointBoxBoundsScratch[0],
                    waypointBoxBoundsScratch[1],
                    waypointBoxBoundsScratch[2]);
            start = clipLineStartOutsideCamera(
                    start, end, camPos, screenDown,
                    ROUTE_LINE_CAMERA_CLEARANCE, ROUTE_LINE_CAMERA_SCREEN_OFFSET);
            RenderHelpers.emitLine(lines, ps,
                    (float) start.x, (float) start.y, (float) start.z,
                    (float) end.x, (float) end.y, (float) end.z,
                    color, alpha, width);
        };
        if (isDungeonRoomRoute(g)) {
            forEachFocusedDungeonRouteLineSegment(
                    g, depthCheckedPass, i -> true, emitSegment);
            return;
        }
        forEachRouteLineSegment(
                g,
                depthCheckedPass,
                config.sequenceVisibility(),
                config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> shouldRenderRouteLineEndpoint(g, i, currentIdx, showCompleted,
                        camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq),
                emitSegment);
    }

    static Vec3 routeLineStart(Waypoint waypoint, boolean useEtherwarpHeight,
                               float crouchingEyeHeight) {
        return routeLineStart(null, waypoint, useEtherwarpHeight, crouchingEyeHeight,
                new double[6]);
    }

    private static Vec3 routeLineStart(ClientLevel level, Waypoint waypoint,
                                       boolean useEtherwarpHeight,
                                       float crouchingEyeHeight, double[] scratch) {
        populateWaypointRenderAnchor(level, waypoint, scratch);
        double y = scratch[1];
        if (useEtherwarpHeight) y += 0.5 + Math.max(0.0f, crouchingEyeHeight);
        return new Vec3(scratch[0], y, scratch[2]);
    }

    static Vec3 clipLineStartOutsideCamera(Vec3 start, Vec3 end, Vec3 camera,
                                           Vec3 screenDown, double clearance,
                                           double screenOffset) {
        if (clearance <= 0.0 || start.distanceToSqr(camera) >= clearance * clearance) {
            return start;
        }

        Vec3 direction = end.subtract(start);
        double lengthSquared = direction.lengthSqr();
        if (lengthSquared <= 1.0E-12) return end;

        Vec3 cameraToStart = start.subtract(camera);
        double along = cameraToStart.dot(direction);
        double discriminant = along * along - lengthSquared
                * (cameraToStart.lengthSqr() - clearance * clearance);
        if (discriminant <= 0.0) return end;

        double exitFraction = (-along + Math.sqrt(discriminant)) / lengthSquared;
        if (exitFraction >= 1.0) return end;
        Vec3 clipped = start.add(direction.scale(Math.max(0.0, exitFraction)));
        if (screenOffset <= 0.0) return clipped;
        if (clipped.distanceToSqr(end) <= screenOffset * screenOffset) return end;

        Vec3 unitDirection = direction.scale(1.0 / Math.sqrt(lengthSquared));
        Vec3 perpendicularDown = screenDown.subtract(
                unitDirection.scale(screenDown.dot(unitDirection)));
        double perpendicularLengthSquared = perpendicularDown.lengthSqr();
        if (perpendicularLengthSquared <= 1.0E-12) {
            Vec3 fallbackAxis = Math.abs(unitDirection.x) < 0.9
                    ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 0.0, 1.0);
            perpendicularDown = fallbackAxis.subtract(
                    unitDirection.scale(fallbackAxis.dot(unitDirection)));
            perpendicularLengthSquared = perpendicularDown.lengthSqr();
        }
        return clipped.add(perpendicularDown.scale(
                screenOffset / Math.sqrt(perpendicularLengthSquared)));
    }

    static void forEachRouteLineSegment(WaypointGroup group,
                                        boolean depthCheckedPass,
                                        IntPredicate endpointVisible,
                                        RouteLineSegmentConsumer consumer) {
        forEachRouteLineSegment(group, depthCheckedPass, SequenceVisibility.DEFAULT,
                false, endpointVisible, consumer);
    }

    static void forEachRouteLineSegment(WaypointGroup group,
                                        boolean depthCheckedPass,
                                        SequenceVisibility visibility,
                                        boolean keepSubwaypointsVisibleUntilNextWaypoint,
                                        IntPredicate endpointVisible,
                                        RouteLineSegmentConsumer consumer) {
        int[] previous = { -1 };
        group.forEachVisibleIndex(visibility, keepSubwaypointsVisibleUntilNextWaypoint, i -> {
            if (group.isSubwaypoint(i)) return;
            if (endpointVisible != null && !endpointVisible.test(i)) return;
            if (previous[0] >= 0) {
                Waypoint a = group.get(previous[0]);
                Waypoint b = group.get(i);
                if (routeSegmentMatchesDepthPass(a, b, depthCheckedPass)) {
                    consumer.accept(previous[0], i);
                }
            }
            previous[0] = i;
        });
    }

    static void forEachFocusedDungeonRouteLineSegment(WaypointGroup group,
                                                      boolean depthCheckedPass,
                                                      IntPredicate endpointVisible,
                                                      RouteLineSegmentConsumer consumer) {
        if (group == null || consumer == null) return;
        int to = group.currentIndex();
        int from = focusedDungeonRoutePreviousIndex(group);
        if (from < 0 || to < 0 || to >= group.size()) return;
        if (endpointVisible != null
                && (!endpointVisible.test(from) || !endpointVisible.test(to))) {
            return;
        }
        Waypoint a = group.get(from);
        Waypoint b = group.get(to);
        if (routeSegmentMatchesDepthPass(a, b, depthCheckedPass)) {
            consumer.accept(from, to);
        }
    }

    static int focusedDungeonRoutePreviousIndex(WaypointGroup group) {
        if (group == null) return -1;
        int current = group.currentIndex();
        if (current <= 0 || current >= group.size()) return -1;
        if (group.isSubwaypoint(current)) {
            int previous = current - 1;
            while (previous >= 0
                    && (group.isWaypointDisabled(previous)
                    || group.get(previous).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET))) {
                previous--;
            }
            return previous;
        }

        int previous = current - 1;
        while (previous >= 0
                && (group.isWaypointDisabled(previous)
                || group.get(previous).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET))) {
            previous--;
        }
        if (previous >= 0
                && group.isSubwaypoint(previous)
                && group.get(previous).hasFlag(Waypoint.DUNGEON_COMPLETION_FLAGS)) {
            return previous;
        }
        int activeParent = group.activeSubwaypointParentIndex();
        if (activeParent >= 0 && group.isWaypointEnabled(activeParent)) return activeParent;
        return group.previousEnabledMainIndexBefore(current);
    }

    static boolean isFocusedDungeonRouteLabel(WaypointGroup group, int index) {
        return group != null
                && (index == group.currentIndex()
                || index == focusedDungeonRoutePreviousIndex(group));
    }

    private boolean shouldRenderRouteLineEndpoint(WaypointGroup group, int index, int currentIdx,
                                                  boolean showCompleted, Vec3 camPos,
                                                  Vec3 playerPos, double maxStaticDistanceSq,
                                                  double nearHideDistanceSq) {
        if (index < 0 || index >= group.size()) return false;
        if (group.isWaypointDisabled(index)) return false;
        if (shouldHideStaticReached(group, index)) return false;

        Waypoint waypoint = group.get(index);
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)) return false;
        if (shouldHideNearPlayer(waypoint, playerPos, nearHideDistanceSq)) return false;
        if (isStaticBeyondDistanceLimit(group, waypoint, camPos, maxStaticDistanceSq)) return false;

        State state = stateFor(group, index, currentIdx);
        return !shouldHideCompletedSequenceWaypoint(group, index, currentIdx, state,
                showCompleted, waypoint);
    }

    private boolean routeSegmentHasDepthVisibility(Waypoint a, Waypoint b,
                                                   boolean depthCheckedPass,
                                                   Minecraft mc, ClientLevel level) {
        if (!depthCheckedPass) return true;
        if (a.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)
                && !shouldRenderDepthCheckedWaypoint(mc, level, a)) {
            return false;
        }
        return !b.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)
                || shouldRenderDepthCheckedWaypoint(mc, level, b);
    }

    @FunctionalInterface
    interface RouteLineSegmentConsumer {
        void accept(int fromIndex, int toIndex);
    }

    boolean shouldRenderWaypointWorld(WaypointGroup group, int index, int currentIdx,
                                              boolean showCompleted, Vec3 camPos,
                                              Vec3 playerPos, double maxStaticDistanceSq,
                                              double nearHideDistanceSq,
                                              boolean depthCheckedPass, Minecraft mc,
                                              ClientLevel level) {
        if (!shouldRenderRouteLineEndpoint(group, index, currentIdx, showCompleted,
                camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq)) return false;
        Waypoint waypoint = group.get(index);
        if (waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED) != depthCheckedPass) return false;
        if (depthCheckedPass && !shouldRenderDepthCheckedWaypoint(mc, level, waypoint)) {
            return false;
        }
        return true;
    }

    static boolean hasDepthCheckedWaypoint(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            for (Waypoint waypoint : group.waypoints()) {
                if (waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)) return true;
            }
        }
        return false;
    }

    static boolean hasThroughWallWaypoint(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            for (Waypoint waypoint : group.waypoints()) {
                if (!waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)) return true;
            }
        }
        return false;
    }

    static boolean routeSegmentMatchesDepthPass(Waypoint a, Waypoint b,
                                                boolean depthCheckedPass) {
        boolean segmentDepthChecked = a.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)
                || b.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);
        return segmentDepthChecked == depthCheckedPass;
    }

    private boolean shouldRenderDepthCheckedWaypoint(Minecraft mc, ClientLevel level,
                                                     Waypoint waypoint) {
        if (mc == null || level == null || waypoint == null) {
            return false;
        }
        if (trustGpuDepthTest) return true;
        return depthVisibility.getOrCompute(waypoint,
                () -> hasLineOfSightToWaypointBox(mc, level, waypoint));
    }

    boolean shouldRenderProjectedDepthCheckedWaypoint(Minecraft mc,
                                                              ClientLevel level,
                                                              Waypoint waypoint,
                                                              double sx,
                                                              double sy,
                                                              int screenW,
                                                              int screenH) {
        if (mc == null || level == null || waypoint == null) return false;
        if (!isOnScreen(sx, sy, screenW, screenH)) return false;
        return shouldRenderDepthCheckedWaypoint(mc, level, waypoint);
    }

    /** Hashes the cached line-of-sight results that affect retained geometry. */
    long depthVisibilityFingerprint(Iterable<WaypointGroup> groups, Minecraft mc,
                                    ClientLevel level, Vec3 camPos) {
        if (mc == null || level == null || camPos == null
                || !staticGeometryEnabledFor(groups)) return 0L;
        prepareDepthVisibilityCache(level, camPos);
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        long[] hash = {0x9E3779B97F4A7C15L};
        boolean allVisibleCandidates = staticBoxGeometryEnabledFor(groups)
                || effectiveBeaconBeamMode() == WaypointerConfig.BeaconBeamMode.ALL_VISIBLE;
        for (WaypointGroup g : groups) {
            int currentIdx = g.currentIndex();
            boolean showCompleted = config.showCompleted();
            if (allVisibleCandidates) {
                forEachFadingVisibleIndex(g, i ->
                                mixDepthVisibilityCandidate(hash, g, i, currentIdx,
                                        showCompleted, mc, level, camPos, playerPos,
                                        maxStaticDistanceSq, nearHideDistanceSq));
            } else if (effectiveBeaconBeamMode() == WaypointerConfig.BeaconBeamMode.CURRENT) {
                mixDepthVisibilityCandidate(hash, g, currentBeamIndex(g), currentIdx,
                        showCompleted, mc, level, camPos, playerPos,
                        maxStaticDistanceSq, nearHideDistanceSq);
            }
        }
        return hash[0];
    }

    private void mixDepthVisibilityCandidate(
            long[] hash, WaypointGroup group, int index, int currentIdx,
            boolean showCompleted, Minecraft mc, ClientLevel level, Vec3 camPos, Vec3 playerPos,
            double maxStaticDistanceSq, double nearHideDistanceSq) {
        if (index < 0 || index >= group.size()) return;
        Waypoint waypoint = group.get(index);
        if (!waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)) return;
        if (!shouldRenderRouteLineEndpoint(group, index, currentIdx, showCompleted,
                camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq)) return;
        boolean visible = shouldRenderDepthCheckedWaypoint(mc, level, waypoint);
        long mixed = hash[0] ^ (index * 0x9E3779B97F4A7C15L) ^ (visible ? 0x1L : 0x2L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash[0] = mixed ^ (mixed >>> 27);
    }

    /** Hashes distance and near-hide decisions that affect retained geometry. */
    long worldVisibilityFingerprint(Iterable<WaypointGroup> groups, Vec3 camPos, Vec3 playerPos) {
        if (camPos == null || !staticGeometryEnabledFor(groups)) return 0L;
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        long[] hash = {mixFingerprint(0xA0761D6478BD642FL, effectiveBeaconBeamMode().ordinal())};
        boolean allVisibleCandidates = staticBoxGeometryEnabledFor(groups)
                || effectiveBeaconBeamMode() == WaypointerConfig.BeaconBeamMode.ALL_VISIBLE;
        for (WaypointGroup group : groups) {
            int currentIdx = group.currentIndex();
            boolean showCompleted = config.showCompleted();
            if (allVisibleCandidates) {
                forEachFadingVisibleIndex(group, i ->
                                mixWorldVisibilityCandidate(hash, group, i, currentIdx,
                                        showCompleted, camPos, playerPos,
                                        maxStaticDistanceSq, nearHideDistanceSq));
            } else if (effectiveBeaconBeamMode() == WaypointerConfig.BeaconBeamMode.CURRENT) {
                mixWorldVisibilityCandidate(hash, group, currentBeamIndex(group), currentIdx,
                        showCompleted, camPos, playerPos,
                        maxStaticDistanceSq, nearHideDistanceSq);
            }
        }
        return hash[0];
    }

    private void mixWorldVisibilityCandidate(
            long[] hash, WaypointGroup group, int index, int currentIdx,
            boolean showCompleted, Vec3 camPos, Vec3 playerPos,
            double maxStaticDistanceSq, double nearHideDistanceSq) {
        if (index < 0 || index >= group.size()) return;
        boolean visible = shouldRenderRouteLineEndpoint(
                group, index, currentIdx, showCompleted, camPos, playerPos,
                maxStaticDistanceSq, nearHideDistanceSq);
        hash[0] = mixFingerprint(hash[0], index * 2L + (visible ? 1L : 0L));
    }

    int reserveActivePaints(Iterable<WaypointGroup> groups) {
        if (!config.enableFeatureBloat() || groups == null || config.beaconOpacity() <= 0.0) {
            WaypointPaintTextureCache.resetRetainedReservation();
            return 0;
        }
        WaypointPaint defaultPaint = config.waypointPainterDefaultPaint();
        activePaintScratch.clear();
        for (WaypointGroup group : groups) {
            if (group == null || group.isEmpty()) continue;
            if (usesSequenceRoleColorPaintFallback(
                    group, defaultPaint, config.colorSequenceWaypointsByRole())) continue;
            WaypointPaint paint = effectivePaint(group, defaultPaint);
            if (paint != null) activePaintScratch.add(paint);
        }
        return WaypointPaintTextureCache.reserveForActivePaints(activePaintScratch);
    }

    /** Hashes level-dependent marker bounds. */
    long blockShapeFingerprint(Iterable<WaypointGroup> groups, ClientLevel level,
                               Vec3 camPos, Vec3 playerPos) {
        if (groups == null || level == null || camPos == null
                || !staticBoxGeometryEnabledFor(groups)) return 0L;
        long[] hash = {0xD6E8FEB86659FD93L};
        int[] shapedWaypoints = {0};
        double[] bounds = waypointBoxBoundsScratch;
        for (WaypointGroup group : groups) {
            int currentIdx = group.currentIndex();
            boolean showCompleted = config.showCompleted();
            double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
            double nearHideDistanceSq = nearHideDistanceSq();
            forEachFadingVisibleIndex(group, i -> {
                Waypoint waypoint = group.get(i);
                if (!usesBlockShapeBounds(waypoint)
                        || !shouldRenderRouteLineEndpoint(
                                group, i, currentIdx, showCompleted, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq)) return;
                populateWaypointBoxBounds(level, waypoint, bounds);
                hash[0] = mixFingerprint(hash[0], i);
                for (double bound : bounds) {
                    hash[0] = mixFingerprint(hash[0], Double.doubleToLongBits(bound));
                }
                shapedWaypoints[0]++;
            });
        }
        return mixFingerprint(hash[0], shapedWaypoints[0]);
    }

    private boolean staticGeometryEnabledFor(Iterable<WaypointGroup> groups) {
        if (groups == null) return false;
        boolean visibleBoxes = staticBoxGeometryEnabledFor(groups);
        boolean visibleFlatBeams = effectiveBeaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF
                && !config.useBeaconBeamTextures()
                && config.beaconOpacity() > 0.0;
        return visibleBoxes || visibleFlatBeams;
    }

    boolean staticBoxGeometryEnabledFor(Iterable<WaypointGroup> groups) {
        if (groups == null) return false;
        WaypointerConfig.BoxStyle style = config.effectiveBoxStyle();
        boolean visibleOutlines = boxStyleDrawsOutline(style)
                && config.waypointOutlineOpacity() > 0.0;
        boolean visibleBoxes = (boxStyleDrawsRgbFill(style)
                || style == WaypointerConfig.BoxStyle.PAINT
                || config.enableFeatureBloat() && hasPaintedGroup(groups, config.waypointPainterDefaultPaint())
                || hasFilledSubwaypoint(groups))
                && config.beaconOpacity() > 0.0;
        return visibleOutlines || visibleBoxes;
    }

    private static long mixFingerprint(long hash, long value) {
        long mixed = hash ^ value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    void prepareDepthVisibilityCache(ClientLevel level, Vec3 cameraPosition) {
        long gameTime = level == null ? Long.MIN_VALUE : level.getGameTime();
        double cameraX = cameraPosition == null ? Double.NaN : cameraPosition.x;
        double cameraY = cameraPosition == null ? Double.NaN : cameraPosition.y;
        double cameraZ = cameraPosition == null ? Double.NaN : cameraPosition.z;
        depthVisibility.beginFrame(level, gameTime, cameraX, cameraY, cameraZ);
    }

    private static boolean hasLineOfSightToWaypointBox(Minecraft mc, ClientLevel level,
                                                       Waypoint waypoint) {
        if (mc == null || level == null || waypoint == null) return false;
        AABB bounds = waypointBoxBounds(level, waypoint);
        if (bounds == null) return false;
        for (int sample = 0; sample < LINE_OF_SIGHT_SAMPLE_COUNT; sample++) {
            if (hasLineOfSightToPoint(mc, level, waypoint,
                    lineOfSightSamplePoint(bounds, sample))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineOfSightToPoint(Minecraft mc, ClientLevel level,
                                                 Waypoint waypoint, Vec3 target) {
        if (target == null) return false;
        Vec3 from = MinecraftCompat.mainCamera(mc.gameRenderer).position();
        HitResult hit = level.clip(new ClipContext(from, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                mc.getCameraEntity()));
        if (hit == null || hit.getType() == HitResult.Type.MISS) return true;
        if (hit instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(new BlockPos(
                        waypoint.x(), waypoint.y(), waypoint.z()))) {
            return true;
        }

        return lineOfSightHitReachesSample(hit.getLocation(), target);
    }

    static boolean lineOfSightHitReachesSample(Vec3 hitLocation, Vec3 target) {
        return hitLocation != null
                && target != null
                && hitLocation.distanceToSqr(target) <= 1.0E-4;
    }

    static Vec3 lineOfSightSamplePoint(AABB bounds, int sampleIndex) {
        if (sampleIndex == 0) {
            return new Vec3(
                    (bounds.minX + bounds.maxX) * 0.5,
                    (bounds.minY + bounds.maxY) * 0.5,
                    (bounds.minZ + bounds.maxZ) * 0.5);
        }
        int corner = Math.floorMod(sampleIndex - 1, 8);
        return new Vec3(
                (corner & 1) == 0 ? bounds.minX : bounds.maxX,
                (corner & 2) == 0 ? bounds.minY : bounds.maxY,
                (corner & 4) == 0 ? bounds.minZ : bounds.maxZ);
    }

    static boolean isOnScreen(double sx, double sy, int screenW, int screenH) {
        return Double.isFinite(sx)
                && Double.isFinite(sy)
                && screenW > 0
                && screenH > 0
                && sx >= 0.0
                && sx <= screenW
                && sy >= 0.0
                && sy <= screenH;
    }

    private static boolean hasFilledSubwaypoint(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            for (Waypoint waypoint : group.waypoints()) {
                if (isFilledSubwaypoint(waypoint)) return true;
            }
        }
        return false;
    }

    static boolean hasPaintedGroup(Iterable<WaypointGroup> groups) {
        return hasPaintedGroup(groups, null);
    }

    static boolean hasPaintedGroup(Iterable<WaypointGroup> groups, WaypointPaint defaultPaint) {
        for (WaypointGroup group : groups) {
            if (effectivePaint(group, defaultPaint) != null && !group.isEmpty()) return true;
        }
        return false;
    }

    static WaypointPaint effectivePaint(WaypointGroup group, WaypointPaint defaultPaint) {
        WaypointPaint happySnowmanPaint = HappySnowmanSession.facePaint();
        if (happySnowmanPaint != null) return happySnowmanPaint;
        if (group == null || !group.paintEnabled()) return null;
        return group.paint() != null ? group.paint() : defaultPaint;
    }

    static boolean shouldEmitRgbFill(WaypointGroup group, WaypointPaint defaultPaint) {
        return effectivePaint(group, defaultPaint) == null;
    }

    static boolean usesRgbPaintFallback(WaypointGroup group, WaypointPaint defaultPaint) {
        WaypointPaint paint = effectivePaint(group, defaultPaint);
        return paint != null && !WaypointPaintTextureCache.isRetained(paint);
    }

    static boolean usesSequenceRoleColorPaintFallback(
            WaypointGroup group, WaypointPaint defaultPaint, boolean roleColorsEnabled) {
        return roleColorsEnabled
                && group != null
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && effectivePaint(group, defaultPaint) != null;
    }

    private static boolean isSmallSubwaypoint(Waypoint waypoint) {
        return waypoint != null
                && waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
    }

    private static boolean isFilledSubwaypoint(Waypoint waypoint) {
        return waypoint != null
                && waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT);
    }

    private static void populateWaypointBoxBounds(ClientLevel level, Waypoint waypoint,
                                                   double[] bounds) {
        if (tryPopulateBlockShapeBounds(level, waypoint, bounds)) return;
        if (isSmallSubwaypoint(waypoint)) {
            setSmallSubwaypointBounds(waypoint, bounds);
            return;
        }
        setFullBlockBounds(waypoint, bounds);
    }

    private static boolean tryPopulateBlockShapeBounds(ClientLevel level, Waypoint waypoint,
                                                       double[] bounds) {
        if (level == null || !usesBlockShapeBounds(waypoint)) return false;

        BlockPos pos = new BlockPos(waypoint.x(), waypoint.y(), waypoint.z());
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) return false;

        AABB shapeBounds = blockShapeWaypointBounds(waypoint, shape.bounds());
        if (shapeBounds == null) return false;

        setAabbBounds(shapeBounds, bounds);
        return true;
    }

    static boolean usesBlockShapeBounds(Waypoint waypoint) {
        if (waypoint == null) return false;
        if (isSmallSubwaypoint(waypoint)) return false;
        if (waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT)
                || waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_MINE)) return true;
        return waypoint.isSubwaypoint();
    }

    static AABB blockShapeWaypointBounds(Waypoint waypoint, AABB localBounds) {
        if (!usesBlockShapeBounds(waypoint)) return null;
        if (!hasUsableShapeBounds(localBounds) || isFullBlockShapeBounds(localBounds)) {
            return null;
        }
        return new AABB(
                waypoint.x() + localBounds.minX,
                waypoint.y() + localBounds.minY,
                waypoint.z() + localBounds.minZ,
                waypoint.x() + localBounds.maxX,
                waypoint.y() + localBounds.maxY,
                waypoint.z() + localBounds.maxZ);
    }

    static AABB subwaypointShapeBounds(Waypoint waypoint, AABB localBounds) {
        if (waypoint == null || !waypoint.isSubwaypoint()) return null;
        return blockShapeWaypointBounds(waypoint, localBounds);
    }

    private static boolean hasUsableShapeBounds(AABB localBounds) {
        return localBounds != null
                && Double.isFinite(localBounds.minX)
                && Double.isFinite(localBounds.minY)
                && Double.isFinite(localBounds.minZ)
                && Double.isFinite(localBounds.maxX)
                && Double.isFinite(localBounds.maxY)
                && Double.isFinite(localBounds.maxZ)
                && localBounds.maxX > localBounds.minX
                && localBounds.maxY > localBounds.minY
                && localBounds.maxZ > localBounds.minZ;
    }

    private static boolean isFullBlockShapeBounds(AABB localBounds) {
        return Math.abs(localBounds.minX) <= BLOCK_SHAPE_EPSILON
                && Math.abs(localBounds.minY) <= BLOCK_SHAPE_EPSILON
                && Math.abs(localBounds.minZ) <= BLOCK_SHAPE_EPSILON
                && Math.abs(localBounds.maxX - 1.0) <= BLOCK_SHAPE_EPSILON
                && Math.abs(localBounds.maxY - 1.0) <= BLOCK_SHAPE_EPSILON
                && Math.abs(localBounds.maxZ - 1.0) <= BLOCK_SHAPE_EPSILON;
    }

    private static void setAabbBounds(AABB source, double[] bounds) {
        bounds[BOX_MIN_X] = source.minX;
        bounds[BOX_MIN_Y] = source.minY;
        bounds[BOX_MIN_Z] = source.minZ;
        bounds[BOX_MAX_X] = source.maxX;
        bounds[BOX_MAX_Y] = source.maxY;
        bounds[BOX_MAX_Z] = source.maxZ;
    }

    private static void setSmallSubwaypointBounds(Waypoint waypoint, double[] bounds) {
        bounds[BOX_MIN_X] = waypoint.preciseX() / (double) Waypoint.PRECISE_SCALE;
        bounds[BOX_MIN_Y] = waypoint.preciseY() / (double) Waypoint.PRECISE_SCALE;
        bounds[BOX_MIN_Z] = waypoint.preciseZ() / (double) Waypoint.PRECISE_SCALE;
        bounds[BOX_MAX_X] = bounds[BOX_MIN_X] + SMALL_SUBWAYPOINT_SIZE;
        bounds[BOX_MAX_Y] = bounds[BOX_MIN_Y] + SMALL_SUBWAYPOINT_SIZE;
        bounds[BOX_MAX_Z] = bounds[BOX_MIN_Z] + SMALL_SUBWAYPOINT_SIZE;
    }

    private static void setFullBlockBounds(Waypoint waypoint, double[] bounds) {
        bounds[BOX_MIN_X] = waypoint.x();
        bounds[BOX_MIN_Y] = waypoint.y();
        bounds[BOX_MIN_Z] = waypoint.z();
        bounds[BOX_MAX_X] = waypoint.x() + 1.0;
        bounds[BOX_MAX_Y] = waypoint.y() + 1.0;
        bounds[BOX_MAX_Z] = waypoint.z() + 1.0;
    }

    void populateVisualWaypointBoxBounds(ClientLevel level, Waypoint waypoint,
                                                  double[] bounds) {
        populateWaypointBoxBounds(level, waypoint, bounds);
        scaleBoundsAroundCenter(bounds, config.waypointMarkerScale());
    }

    static void scaleBoundsAroundCenter(double[] bounds, double scale) {
        if (bounds == null || bounds.length < 6 || !Double.isFinite(scale)) return;
        double safeScale = Math.clamp(scale, 0.25, 3.0);
        double centerX = (bounds[BOX_MIN_X] + bounds[BOX_MAX_X]) * 0.5;
        double centerY = (bounds[BOX_MIN_Y] + bounds[BOX_MAX_Y]) * 0.5;
        double centerZ = (bounds[BOX_MIN_Z] + bounds[BOX_MAX_Z]) * 0.5;
        double halfX = (bounds[BOX_MAX_X] - bounds[BOX_MIN_X]) * safeScale * 0.5;
        double halfY = (bounds[BOX_MAX_Y] - bounds[BOX_MIN_Y]) * safeScale * 0.5;
        double halfZ = (bounds[BOX_MAX_Z] - bounds[BOX_MIN_Z]) * safeScale * 0.5;
        bounds[BOX_MIN_X] = centerX - halfX;
        bounds[BOX_MIN_Y] = centerY - halfY;
        bounds[BOX_MIN_Z] = centerZ - halfZ;
        bounds[BOX_MAX_X] = centerX + halfX;
        bounds[BOX_MAX_Y] = centerY + halfY;
        bounds[BOX_MAX_Z] = centerZ + halfZ;
    }

    private void emitLineBoxes(PoseStack ps, VertexConsumer lines, ClientLevel level, WaypointGroup g,
                               Vec3 camPos, Vec3 playerPos,
                               double maxStaticDistanceSq, double nearHideDistanceSq,
                               boolean depthCheckedPass, Minecraft mc) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float outlineThickness = effectiveOutlineThickness();

        forEachFadingVisibleIndex(g,
                i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level)) {
                return;
            }

            Waypoint w = g.get(i);
            State state = stateFor(g, i, currentIdx);
            float alpha = alphaFor(g, i, state) * (float) config.waypointOutlineOpacity();
            populateVisualWaypointBoxBounds(level, w, waypointBoxBoundsScratch);
            float x1 = (float) waypointBoxBoundsScratch[BOX_MIN_X];
            float y1 = (float) waypointBoxBoundsScratch[BOX_MIN_Y];
            float z1 = (float) waypointBoxBoundsScratch[BOX_MIN_Z];
            float x2 = (float) waypointBoxBoundsScratch[BOX_MAX_X];
            float y2 = (float) waypointBoxBoundsScratch[BOX_MAX_Y];
            float z2 = (float) waypointBoxBoundsScratch[BOX_MAX_Z];
            RenderHelpers.emitLineBox(lines, ps, x1, y1, z1, x2, y2, z2,
                    config.resolvedWaypointOutlineColor(
                            resolvedWaypointColor(g, i, w.color())), alpha, outlineThickness);
        });
    }

    private void emitFilledBoxes(PoseStack ps, VertexConsumer quads, ClientLevel level, WaypointGroup g,
                                 Vec3 camPos, Vec3 playerPos,
                                 double maxStaticDistanceSq, double nearHideDistanceSq,
                                 boolean fillAllWaypoints, boolean depthCheckedPass,
                                 Minecraft mc) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        forEachFadingVisibleIndex(g,
                i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level)) {
                return;
            }

            Waypoint w = g.get(i);
            if (!fillAllWaypoints && !isFilledSubwaypoint(w)) return;

            State state = stateFor(g, i, currentIdx);
            float alpha = alphaFor(g, i, state) * beaconOpacity;
            populateVisualWaypointBoxBounds(level, w, waypointBoxBoundsScratch);
            float x1 = (float) waypointBoxBoundsScratch[BOX_MIN_X];
            float y1 = (float) waypointBoxBoundsScratch[BOX_MIN_Y];
            float z1 = (float) waypointBoxBoundsScratch[BOX_MIN_Z];
            float x2 = (float) waypointBoxBoundsScratch[BOX_MAX_X];
            float y2 = (float) waypointBoxBoundsScratch[BOX_MAX_Y];
            float z2 = (float) waypointBoxBoundsScratch[BOX_MAX_Z];
            RenderHelpers.emitFilledBox(quads, ps, x1, y1, z1, x2, y2, z2,
                    resolvedWaypointColor(g, i, w.color()), alpha * FILLED_ALPHA_SCALE);
        });
    }

    private void emitPaintedBoxes(PoseStack ps, VertexConsumer quads,
                                  ClientLevel level, WaypointGroup group,
                                   Vec3 camPos, Vec3 playerPos,
                                    double maxStaticDistanceSq, double nearHideDistanceSq,
                                    boolean depthCheckedPass, Minecraft mc,
                                    boolean retainCameraIndependentGeometry) {
        int currentIndex = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        forEachFadingVisibleIndex(group, i -> {
            if (!shouldRenderWaypointWorld(group, i, currentIndex, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level)) {
                return;
            }

            Waypoint waypoint = group.get(i);
            State state = stateFor(group, i, currentIndex);
            float alpha = alphaFor(group, i, state) * beaconOpacity * PAINTED_ALPHA_SCALE;
            populateVisualWaypointBoxBounds(level, waypoint, waypointBoxBoundsScratch);
            float x1 = (float) waypointBoxBoundsScratch[BOX_MIN_X];
            float y1 = (float) waypointBoxBoundsScratch[BOX_MIN_Y];
            float z1 = (float) waypointBoxBoundsScratch[BOX_MIN_Z];
            float x2 = (float) waypointBoxBoundsScratch[BOX_MAX_X];
            float y2 = (float) waypointBoxBoundsScratch[BOX_MAX_Y];
            float z2 = (float) waypointBoxBoundsScratch[BOX_MAX_Z];
            if (retainCameraIndependentGeometry) {
                RenderHelpers.emitTexturedBoxAllFaces(
                        quads, ps, x1, y1, z1, x2, y2, z2, alpha);
            } else {
                RenderHelpers.emitTexturedBox(
                        quads, ps, x1, y1, z1, x2, y2, z2,
                        alpha, camPos.x, camPos.y, camPos.z);
            }
        });
    }

    WaypointerConfig.BeaconBeamMode effectiveBeaconBeamMode() {
        WaypointerConfig.BeaconBeamMode mode = config.beaconBeamMode();
        return mode == WaypointerConfig.BeaconBeamMode.OFF && manager.tempWaypointFocusActive()
                ? WaypointerConfig.BeaconBeamMode.CURRENT : mode;
    }

    private void emitBeaconBeams(PoseStack ps, VertexConsumer quads, WaypointGroup g,
                                  Vec3 camPos, Vec3 playerPos,
                                  double maxStaticDistanceSq, double nearHideDistanceSq,
                                  int minY, int maxY, boolean depthCheckedPass,
                                  Minecraft mc, ClientLevel level,
                                  boolean texturedBeams) {
        WaypointerConfig.BeaconBeamMode mode = effectiveBeaconBeamMode();
        if (mode == WaypointerConfig.BeaconBeamMode.OFF || g.isEmpty()) return;

        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();

        if (mode == WaypointerConfig.BeaconBeamMode.CURRENT) {
            int beamIndex = currentBeamIndex(g);
            WaypointSkipFade fade = WaypointSkipFade.get(g);
            if (fade != null && fade.active()) {
                fade.outgoingNormallyVisible = beamIndex == fade.outgoing() && showCompleted;
            }
            emitBeaconBeamIfVisible(ps, quads, g, beamIndex, currentIdx,
                    showCompleted, camPos, playerPos, maxStaticDistanceSq,
                    nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level,
                    texturedBeams);
            if (fade != null && fade.active() && fade.outgoing() != beamIndex
                    && config.showCurrentSequenceWaypoint()) {
                fade.outgoingNormallyVisible = false;
                emitBeaconBeamIfVisible(ps, quads, g, fade.outgoing(), currentIdx,
                        showCompleted, camPos, playerPos, maxStaticDistanceSq,
                        nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level,
                        texturedBeams);
            }
            return;
        }

        forEachFadingVisibleIndex(g,
                i -> emitBeaconBeamIfVisible(ps, quads, g, i,
                        currentIdx, showCompleted, camPos, playerPos, maxStaticDistanceSq,
                        nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level,
                        texturedBeams));
    }

    private void emitBeaconBeamIfVisible(PoseStack ps, VertexConsumer quads,
                                         WaypointGroup g, int i, int currentIdx,
                                         boolean showCompleted, Vec3 camPos,
                                           Vec3 playerPos, double maxStaticDistanceSq,
                                           double nearHideDistanceSq, int minY, int maxY,
                                           boolean depthCheckedPass, Minecraft mc,
                                           ClientLevel level,
                                           boolean texturedBeams) {
        if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                depthCheckedPass, mc, level)) {
            return;
        }

        Waypoint w = g.get(i);
        State state = stateFor(g, i, currentIdx);
        WaypointSkipFade fade = WaypointSkipFade.get(g);
        float factor = fade == null ? alphaFor(g, state)
                : fade.beamAlpha(i, alphaFor(g, state),
                        !fade.isOutgoing(i) || fade.outgoingNormallyVisible);
        float alpha = factor * (float) config.beaconOpacity();
        if (!texturedBeams) {
            alpha *= BEAM_ALPHA_SCALE;
        }
        if (alpha <= 0.0f) return;

        float y1 = config.beaconBeamExtendsBelowWaypoint() ? minY : w.y();
        float y2 = beaconBeamTop(w.y(), y1, maxY);
        int waypointColor = resolvedWaypointColor(g, i, w.color());
        if (texturedBeams) {
            emitTexturedBeaconBeam(
                    quads, ps, w, waypointColor, y1, y2, alpha, mc, camPos);
        } else {
            RenderHelpers.emitVerticalColumn(quads, ps,
                    (float) w.centerX(), y1, (float) w.centerZ(),
                    y2, BEAM_HALF_WIDTH, waypointColor, alpha);
        }
    }

    private void emitTexturedBeaconBeam(VertexConsumer consumer, PoseStack ps,
                                        Waypoint waypoint, int waypointColor, float y1, float y2,
                                        float alpha, Minecraft mc, Vec3 camPos) {
        float height = y2 - y1;
        if (height <= 0.0f) return;

        float animationTime = mc.level == null
                ? 0.0f
                : beaconAnimationTime(mc.level.getGameTime(),
                        mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        updateBeamRotation(animationTime);
        float radiusScale = beaconTextureRadiusScale(
                waypoint, camPos, mc.player != null && mc.player.isScoping());
        int coreColor = RenderHelpers.withAlpha(0xFF000000 | waypointColor, alpha);
        int glowColor = RenderHelpers.withAlpha(BEACON_GLOW_BASE_ALPHA_ARGB | waypointColor, alpha);

        PoseStack.Pose pose = ps.last();
        float cx = (float) waypoint.centerX();
        float cz = (float) waypoint.centerZ();

        float r = BeaconRenderer.SOLID_BEAM_RADIUS * radiusScale;
        float sr = beamRotationSin * r;
        float cr = beamRotationCos * r;
        emitBeaconTexturePart(pose, consumer, coreColor, cx, cz, y1, height, r,
                sr, cr,
                cr, -sr,
                -cr, sr,
                -sr, -cr,
                animationTime, true);

        float glow = BeaconRenderer.BEAM_GLOW_RADIUS * radiusScale;
        emitBeaconTexturePart(pose, consumer, glowColor, cx, cz, y1, height, glow,
                -glow, -glow,
                glow, -glow,
                glow, glow,
                -glow, glow,
                animationTime, false);
    }

    private void updateBeamRotation(float animationTime) {
        if (animationTime == beamRotationAnimationTime) return;
        beamRotationAnimationTime = animationTime;
        double radians = Math.toRadians(beaconRotationDegrees(animationTime));
        beamRotationCos = (float) Math.cos(radians);
        beamRotationSin = (float) Math.sin(radians);
    }

    static float beaconAnimationTime(long gameTime, float partialTick) {
        return Math.floorMod(gameTime, 40L) + partialTick;
    }

    static float beaconRotationDegrees(float animationTime) {
        return animationTime * 2.25f - 45.0f;
    }

    static float beaconBeamTop(float waypointY, float startY, int maxBuildY) {
        return Math.max(startY + 1.0f,
                Math.max(maxBuildY, waypointY + BeaconRenderer.MAX_RENDER_Y));
    }

    static float beaconTextureRadiusScale(Waypoint waypoint, Vec3 camPos,
                                          boolean playerScoping) {
        if (playerScoping || camPos == null) return 1.0f;
        double dx = waypoint.centerX() - camPos.x;
        double dz = waypoint.centerZ() - camPos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.max(1.0, horizontalDistance / BEACON_TEXTURE_SCALE_THRESHOLD);
    }

    private static void emitBeaconTexturePart(PoseStack.Pose pose,
                                              VertexConsumer consumer,
                                              int argb, float cx, float cz,
                                              float startY, float height,
                                              float uvRadius,
                                              float x1, float z1,
                                              float x2, float z2,
                                              float x3, float z3,
                                              float x4, float z4,
                                              float animationTime,
                                              boolean core) {
        if (height <= 0.0f) return;
        float scrollDirection = -animationTime;
        float textureOffset = Mth.frac(scrollDirection * 0.2F - Mth.floor(scrollDirection * 0.1F));
        float vTop = -1.0F + textureOffset;
        float uvScale = core ? 0.5F / uvRadius : 1.0F;
        float vBottom = height * uvScale + vTop;
        float endY = startY + height;

        emitBeaconTextureQuad(pose, consumer, argb, startY, endY,
                cx + x1, cz + z1, cx + x2, cz + z2, 0.0F, 1.0F, vBottom, vTop);
        emitBeaconTextureQuad(pose, consumer, argb, startY, endY,
                cx + x3, cz + z3, cx + x4, cz + z4, 0.0F, 1.0F, vBottom, vTop);
        emitBeaconTextureQuad(pose, consumer, argb, startY, endY,
                cx + x2, cz + z2, cx + x3, cz + z3, 0.0F, 1.0F, vBottom, vTop);
        emitBeaconTextureQuad(pose, consumer, argb, startY, endY,
                cx + x4, cz + z4, cx + x1, cz + z1, 0.0F, 1.0F, vBottom, vTop);
    }

    private static void emitBeaconTextureQuad(PoseStack.Pose pose,
                                              VertexConsumer consumer,
                                              int argb,
                                              float startY, float endY,
                                              float x1, float z1,
                                              float x2, float z2,
                                              float u1, float u2,
                                              float vBottom, float vTop) {
        addBeaconTextureVertex(pose, consumer, argb, x1, endY, z1, u1, vBottom);
        addBeaconTextureVertex(pose, consumer, argb, x1, startY, z1, u1, vTop);
        addBeaconTextureVertex(pose, consumer, argb, x2, startY, z2, u2, vTop);
        addBeaconTextureVertex(pose, consumer, argb, x2, endY, z2, u2, vBottom);
    }

    private static void addBeaconTextureVertex(PoseStack.Pose pose,
                                               VertexConsumer consumer,
                                               int argb,
                                               float x, float y, float z,
                                               float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT_LIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static int currentBeamIndex(WaypointGroup g) {
        if (g.isEmpty()) return -1;
        if (g.isComplete()) return g.lastMainIndex();
        return g.currentMainIndex();
    }

    private static int beamMinY(Minecraft mc) {
        return mc.level == null ? DEFAULT_MIN_BUILD_Y : mc.level.getMinY();
    }

    private static int beamMaxY(Minecraft mc) {
        return mc.level == null ? DEFAULT_MAX_BUILD_Y : mc.level.getMaxY();
    }


    static boolean worldRenderOpacityAllowsAnything(double fillOpacity, double outlineOpacity,
                                                     boolean drawLines, boolean drawRouteLines,
                                                     boolean drawDungeonEntryPaths) {
        return fillOpacity > 0.0
                || drawLines && outlineOpacity > 0.0
                || drawRouteLines
                || drawDungeonEntryPaths;
    }


    static WaypointerConfig.BoxStyle hudFallbackBoxStyle(WaypointerConfig.BoxStyle style) {
        return style == WaypointerConfig.BoxStyle.FILLED
                || style == WaypointerConfig.BoxStyle.PAINT
                ? WaypointerConfig.BoxStyle.OUTLINED
                : style;
    }

    static boolean boxStyleDrawsRgbFill(WaypointerConfig.BoxStyle style) {
        return style == WaypointerConfig.BoxStyle.FILLED
                || style == WaypointerConfig.BoxStyle.FILLED_OUTLINED;
    }

    static boolean boxStyleDrawsOutline(WaypointerConfig.BoxStyle style) {
        return style == WaypointerConfig.BoxStyle.OUTLINED
                || style == WaypointerConfig.BoxStyle.FILLED_OUTLINED;
    }

    static boolean worldBoxOutlinesEnabled(WaypointerConfig.BoxStyle style,
                                           boolean irisHudFallbackActive) {
        return boxStyleDrawsOutline(style) && !irisHudFallbackActive;
    }

    protected int resolvedWaypointColor(
            WaypointGroup group, int waypointIndex, int fallbackColor) {
        return SequenceRoleColor.resolve(
                group,
                waypointIndex,
                config.colorSequenceWaypointsByRole(),
                config.sequencePreviousWaypointColor(),
                config.sequenceCurrentWaypointColor(),
                config.sequenceNextWaypointColor(),
                fallbackColor);
    }


    static State stateFor(WaypointGroup group, int i, int currentIdx) {
        return stateFor(group, i, currentIdx, group.activeSubwaypointParentIndex());
    }

    static State stateFor(WaypointGroup group, int i, int currentIdx, int activeSubwayParent) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) return State.CURRENT;
        if (group.isSubwaypoint(i)) {
            if (i == currentIdx) return State.CURRENT;
            int parent = group.parentMainIndex(i);
            if (parent == activeSubwayParent) return State.CURRENT;
            if (parent < currentIdx) return State.COMPLETED;
            if (parent == currentIdx) return State.CURRENT;
            return State.UPCOMING;
        }
        if (i == activeSubwayParent) return State.CURRENT;
        if (i < currentIdx) return State.COMPLETED;
        if (i == currentIdx) return State.CURRENT;
        return State.UPCOMING;
    }

    boolean shouldHideCompletedSequenceWaypoint(WaypointGroup group, int index,
                                                int currentIdx, State state,
                                                boolean showCompleted, Waypoint waypoint) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        if (fade != null && fade.isOutgoing(index)
                && !shouldForceHideReachedWaypoint(index, currentIdx, waypoint)) return false;
        return shouldHideCompletedSequenceWaypoint(index, currentIdx, state, showCompleted, waypoint);
    }

    boolean shouldHideCompletedSequenceWaypoint(int index,
                                                int currentIdx,
                                                State state,
                                                boolean showCompleted,
                                                Waypoint waypoint) {
        if (shouldForceHideReachedWaypoint(index, currentIdx, waypoint)) return true;
        if (state != State.COMPLETED) return false;
        return !showCompleted;
    }

    static boolean shouldForceHideReachedWaypoint(int index, int currentIdx, Waypoint waypoint) {
        return waypoint != null
                && waypoint.hasFlag(Waypoint.FLAG_HIDE_BEACON)
                && index < currentIdx;
    }

    boolean shouldHideStaticReached(WaypointGroup group, int index) {
        return !CompassMarkerState.arrived(group.get(index))
                && config.hideReachedStaticWaypointsUntilCycleComplete()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && group.isStaticWaypointReached(index);
    }

    double nearHideDistanceSq() {
        return config.hideWaypointsNearPlayer()
                ? WaypointVisibility.squaredRadius(config.hideWaypointsNearRadius())
                : 0.0;
    }

    double labelNearHideDistanceSq() {
        return config.hideWaypointLabelsNearPlayer()
                ? WaypointVisibility.squaredRadius(config.hideWaypointLabelsNearRadius())
                : 0.0;
    }

    static boolean shouldHideNearPlayer(Waypoint waypoint, Vec3 playerPos,
                                                double nearHideDistanceSq) {
        return playerPos != null
                && WaypointVisibility.isHiddenNearPlayer(
                        waypoint, playerPos.x, playerPos.y, playerPos.z, nearHideDistanceSq);
    }

    static boolean isNearScreen(double sx, double sy, int screenW, int screenH) {
        return sx >= -SCREEN_CULL_MARGIN
                && sx <= screenW + SCREEN_CULL_MARGIN
                && sy >= -SCREEN_CULL_MARGIN
                && sy <= screenH + SCREEN_CULL_MARGIN;
    }

    static double squaredDistanceLimit(double distance) {
        return distance <= 0.0 ? 0.0 : distance * distance;
    }

    static boolean isStaticBeyondDistanceLimit(WaypointGroup group, Waypoint waypoint,
                                                       Vec3 camPos, double maxStaticDistanceSq) {
        if (maxStaticDistanceSq <= 0.0 || group.loadMode() != WaypointGroup.LoadMode.STATIC) {
            return false;
        }

        double dx = waypoint.centerX() - camPos.x;
        double dy = waypoint.centerY() - camPos.y;
        double dz = waypoint.centerZ() - camPos.z;
        return isStaticBeyondDistanceLimit(group, dx * dx + dy * dy + dz * dz,
                maxStaticDistanceSq);
    }

    static boolean isStaticBeyondDistanceLimit(WaypointGroup group,
                                                       double distanceSq,
                                                       double maxStaticDistanceSq) {
        return maxStaticDistanceSq > 0.0
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && distanceSq > maxStaticDistanceSq;
    }

    protected void prepareSkipFades(List<WaypointGroup> groups) {
        for (WaypointGroup group : groups) WaypointSkipFade.observe(group, config);
    }

    private static boolean hasSkipFades(List<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            WaypointSkipFade fade = WaypointSkipFade.get(group);
            if (fade != null && fade.active()) return true;
        }
        return false;
    }

    private static void emitGroupGeometry(GeometrySink sink, WaypointGroup group, Runnable body) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        if (fade != null && fade.active()) sink.dynamic(body);
        else if (sink.staticGeometryNeeded()) body.run();
    }

    protected void forEachFadingVisibleIndex(WaypointGroup group, IntConsumer action) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        if (fade == null || !fade.active() || !config.showCurrentSequenceWaypoint()) {
            group.forEachVisibleIndex(config.sequenceVisibility(),
                    config.keepSubwaypointsVisibleUntilNextWaypoint(), action);
            return;
        }
        fade.outgoingNormallyVisible = false;
        group.forEachVisibleIndex(config.sequenceVisibility(),
                config.keepSubwaypointsVisibleUntilNextWaypoint(), index -> {
                    if (fade.isOutgoing(index)) fade.outgoingNormallyVisible = true;
                    action.accept(index);
                });
        if (!fade.outgoingNormallyVisible && group.isWaypointEnabled(fade.outgoing())) {
            action.accept(fade.outgoing());
        }
    }

    float alphaFor(WaypointGroup group, int index, State state) {
        float normal = alphaFor(group, state);
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        return fade == null ? normal : fade.alpha(index, normal,
                !fade.isOutgoing(index) || fade.outgoingNormallyVisible);
    }

    float alphaFor(WaypointGroup group, State state) {
        return roleAlpha(group, state, config.dimSequenceContextWaypoints());
    }

    static float roleAlpha(WaypointGroup group, State state, boolean dimContext) {
        if (dimContext
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && state != State.CURRENT) {
            return Math.min(state.alpha, SEQUENCE_CONTEXT_ALPHA);
        }
        return state.alpha;
    }


    private float effectiveOutlineThickness() {
        return (float) config.waypointOutlineThickness();
    }


    enum State {
        COMPLETED(0.25f),
        CURRENT(1.0f),
        UPCOMING(0.65f);

        final float alpha;
        State(float a) { this.alpha = a; }
    }

}
