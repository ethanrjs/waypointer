package com.babbur.waypointer.render;

import com.babbur.waypointer.render.gpu.OverlayRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.SequenceRoleColor;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.WaypointVisibility;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

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
    static final int MAX_DASHES_PER_CONNECTOR = 2048;

    static final int MAX_DASHES_PER_PASS = 16_384;
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

    protected final ActiveGroupManager manager;
    protected final WaypointerConfig config;
    protected final DungeonConfig dungeonConfig;

    protected final WaypointVisibilityCache depthVisibility = new WaypointVisibilityCache();
    private final ViewCuller viewCuller = new ViewCuller();
    protected final double[] waypointBoxBoundsScratch = new double[6];
    private final LinkedHashSet<WaypointPaint> activePaintScratch = new LinkedHashSet<>();
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

        if (OverlayRenderer.ownsWorldGeometry()) {
            prepareDungeonEntryPathsForRetained(
                    ctx, frameToken, groups, irisHudFallbackActive);
            return;
        }

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

    /** Which world geometry passes run this frame, across every active route. */
    record Gates(boolean paint, boolean lines, boolean fill, boolean texturedBeams, boolean flatBeams,
                 boolean routeLines, boolean dungeonEntryPaths, boolean depthChecked, boolean throughWall) {
        long fingerprint() {
            long bits = 0;
            boolean[] all = {paint, lines, fill, texturedBeams, flatBeams, routeLines, dungeonEntryPaths,
                    depthChecked, throughWall};
            for (int i = 0; i < all.length; i++) if (all[i]) bits |= 1L << i;
            return bits;
        }
    }

    Gates gates(List<WaypointGroup> groups, boolean irisHudFallbackActive) {
        boolean drawPaint = anyPainted(groups);
        boolean drawLines = anyStyle(groups, c -> worldBoxOutlinesEnabled(c.effectiveBoxStyle(), irisHudFallbackActive)
                && c.waypointOutlineOpacity() > 0);
        boolean drawFill = anyStyle(groups, c -> c.beaconOpacity() > 0
                && (boxStyleDrawsRgbFill(c.effectiveBoxStyle()) || c.effectiveBoxStyle() == WaypointerConfig.BoxStyle.PAINT))
                || hasFilledSubwaypoint(groups) || drawPaint;
        boolean drawTexturedBeams = anyStyle(groups, c -> effectiveBeaconBeamMode(c) != WaypointerConfig.BeaconBeamMode.OFF
                && c.useBeaconBeamTextures() && c.beaconOpacity() > 0);
        boolean drawFlatBeams = anyStyle(groups, c -> effectiveBeaconBeamMode(c) != WaypointerConfig.BeaconBeamMode.OFF
                && !c.useBeaconBeamTextures() && c.beaconOpacity() > 0);
        return new Gates(drawPaint, drawLines, drawFill, drawTexturedBeams, drawFlatBeams,
                anyRouteLines(groups), config.showDungeonEntryPathToFirstWaypoint(),
                anyLineOfSight(groups), anyThroughWall(groups));
    }

    boolean emitWorldGeometry(LevelRenderContext ctx, PoseStack ps, GeometrySink sink,
                              Vec3 origin, boolean irisHudFallbackActive) {
        var groups = manager.activeGroups();
        if (groups.isEmpty()) {
            clearPreparedDungeonEntryPaths();
            return false;
        }

        Gates gates = gates(groups, irisHudFallbackActive);
        boolean drawPaint = gates.paint();
        boolean drawLines = gates.lines();
        boolean drawFill = gates.fill();
        boolean drawTexturedBeams = gates.texturedBeams();
        boolean drawFlatBeams = gates.flatBeams();
        boolean drawBeams = drawTexturedBeams || drawFlatBeams;
        boolean drawRouteLines = gates.routeLines();
        boolean drawDungeonEntryPaths = gates.dungeonEntryPaths();
        if (!drawDungeonEntryPaths) clearPreparedDungeonEntryPaths();
        if (!drawLines && !drawFill && !drawPaint && !drawBeams && !drawRouteLines
                && !drawDungeonEntryPaths) return false;
        boolean hasDepthCheckedWaypoints = gates.depthChecked();
        boolean hasThroughWallWaypoints = gates.throughWall();
        if (!hasDepthCheckedWaypoints && !hasThroughWallWaypoints && !drawDungeonEntryPaths) return false;

        if (ps == null || sink == null || origin == null) return false;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = MinecraftCompat.mainCamera(renderer);
        if (!camera.isInitialized()) return false;
        Vec3 camPos = camera.position();
        prepareDepthVisibilityCache(level, camPos);
        viewCuller.prepare(renderer, camPos);
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();

        ps.pushPose();
        try {
            ps.translate(-origin.x, -origin.y, -origin.z);

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
                for (WaypointPaint paint : paintsFor(g)) {
                    WaypointPaintTextureCache.Entry paintTexture =
                            WaypointPaintTextureCache.getRetained(paint);
                    if (paintTexture == null) continue;
                    sink.submit(paintTexture.throughWalls(),
                            (quads, submittedPose) -> emitGroupGeometry(sink, g, () -> emitPaintedBoxes(
                                    submittedPose, quads, level, g, paint, camPos, playerPos,
                                    maxStaticDistanceSq, nearHideDistanceSq,
                                    false, mc, retainCameraIndependentGeometry)));
                }
            }
        }
        if ((emitStatic || emitFading) && drawPaint && hasDepthCheckedWaypoints) {
            for (WaypointGroup g : groups) {
                for (WaypointPaint paint : paintsFor(g)) {
                    WaypointPaintTextureCache.Entry paintTexture =
                            WaypointPaintTextureCache.getRetained(paint);
                    if (paintTexture == null) continue;
                    sink.submit(paintTexture.depthTested(),
                            (quads, submittedPose) -> emitGroupGeometry(sink, g, () -> emitPaintedBoxes(
                                    submittedPose, quads, level, g, paint, camPos, playerPos,
                                    maxStaticDistanceSq, nearHideDistanceSq,
                                    true, mc, retainCameraIndependentGeometry)));
                }
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
                        emitGroupGeometry(sink, g, () -> emitFilledBoxes(submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
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
                        emitGroupGeometry(sink, g, () -> emitFilledBoxes(submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc));
                    }
                }
            });
        }
        if (((emitStatic || emitFading) && drawLines || drawRouteLines) && hasThroughWallWaypoints) {
            RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
            sink.submit(lineType, (lines, submittedPose) -> {
                if (drawRouteLines) {
                    emitRouteLinePass(sink, submittedPose, lines, groups, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, false, mc, level);
                }
                if ((emitStatic || emitFading) && drawLines) {
                    for (WaypointGroup g : groups) {
                        emitGroupGeometry(sink, g, () -> emitLineBoxes(submittedPose, lines, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                false, mc));
                    }
                }
            });
        }
        boolean depthTestedLines = ((emitStatic || emitFading) && drawLines || drawRouteLines)
                && hasDepthCheckedWaypoints;
        if (depthTestedLines || drawDungeonEntryPaths) {
            RenderType lineType = WaypointerRenderPipelines.linesDepthTested();
            List<DungeonEntryPathController.Submission> dungeonEntryPaths = drawDungeonEntryPaths
                    ? dungeonEntryPathsForEmission(ctx, groups, playerPos, level)
                    : List.of();
            boolean submitted = sink.submit(lineType, (lines, submittedPose) -> {
                if (drawDungeonEntryPaths) {
                    sink.dynamic(() -> emitDungeonEntryPaths(submittedPose, lines, dungeonEntryPaths));
                }
                if (!depthTestedLines) return;
                if (drawRouteLines) {
                    emitRouteLinePass(sink, submittedPose, lines, groups, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, true, mc, level);
                }
                if ((emitStatic || emitFading) && drawLines) {
                    for (WaypointGroup g : groups) {
                        emitGroupGeometry(sink, g, () -> emitLineBoxes(submittedPose, lines, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc));
                    }
                }
            });
            for (DungeonEntryPathController.Submission path : dungeonEntryPaths) {
                RenderDiagnostics.recordDungeonPathSubmission(
                        path.group(), submitted && isDrawableDungeonEntryPath(path.points()));
            }
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
        if (OverlayRenderer.ownsWorldGeometry()) {
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
        return routeLinesEnabled(group, null, config, dungeonConfig);
    }

    static boolean routeLinesEnabled(WaypointGroup group, Waypoint point, WaypointerConfig config,
                                      DungeonConfig dungeonConfig) {
        boolean explicit = config != null && (config.namedStyle(config.preferredStyleId()).values().containsKey("showRouteLines")
                || group != null && (config.namedStyle(group.stylePresetId()).values().containsKey("showRouteLines")
                    || group.styleOverride().values().containsKey("showRouteLines"))
                || point != null && point.styleOverride().values().containsKey("showRouteLines"));
        if (!explicit && isDungeonRoomRoute(group) && dungeonConfig != null) {
            return dungeonConfig.showDungeonRouteLines();
        }
        return config != null && WaypointStyles.resolve(config, group, point).showRouteLines();
    }

    private boolean anyRouteLines(List<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            if (routeLinesPossible(group)) return true;
        }
        return false;
    }

    private boolean routeLinesPossible(WaypointGroup group) {
        if (routeLinesEnabled(group, null, config, dungeonConfig)) return true;
        if (!group.hasWaypointStyleOverrides()) return false;
        // Per-point styles: the answer only changes with the points, the route's resolved style or
        // the dungeon route-line setting, so the whole-route walk runs only then.
        WaypointerConfig routeStyle = styleFor(group, null);
        boolean dungeonLines = dungeonConfig != null && dungeonConfig.showDungeonRouteLines();
        long content = group.contentVersion();
        RouteLinesCache cached = routeStyle != config ? routeLinesCache.get(group) : null;
        if (cached != null && cached.content() == content && cached.routeStyle() == routeStyle
                && cached.dungeonLines() == dungeonLines) return cached.possible();
        boolean possible = false;
        for (Waypoint waypoint : group.waypoints()) {
            if (!waypoint.styleOverride().isEmpty()
                    && routeLinesEnabled(group, waypoint, config, dungeonConfig)) {
                possible = true;
                break;
            }
        }
        if (routeStyle != config) {
            routeLinesCache.put(group, new RouteLinesCache(content, routeStyle, dungeonLines, possible));
        }
        return possible;
    }

    private record RouteLinesCache(long content, WaypointerConfig routeStyle, boolean dungeonLines,
                                   boolean possible) {}

    private final java.util.Map<WaypointGroup, RouteLinesCache> routeLinesCache = new java.util.WeakHashMap<>();

    static boolean isDungeonRoomRoute(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.usesDungeonSteps();
    }

    /** Which connectors one emission covers when a retained sink splits them. */
    enum RouteLinePart { ALL, NEAR, FAR }

    /** Retained connectors are rebuilt when the camera enters another cell this size (blocks). */
    public static final int ROUTE_LINE_CELL = 8;
    /**
     * A connector whose start is this close to the camera cell's centre may need clipping
     * around the camera somewhere in the cell, so it stays per-frame; every farther connector
     * is unclipped anywhere in the cell and can be retained.
     */
    static final double ROUTE_LINE_NEAR_CELL_DISTANCE_SQ = square(
            ROUTE_LINE_CAMERA_CLEARANCE + ROUTE_LINE_CELL * Math.sqrt(3) / 2 + 1.0);

    private static double square(double value) {
        return value * value;
    }

    public static int routeLineCell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), ROUTE_LINE_CELL);
    }

    private static Vec3 routeLineCellCenter(Vec3 camera) {
        return new Vec3((routeLineCell(camera.x) + 0.5) * ROUTE_LINE_CELL,
                (routeLineCell(camera.y) + 0.5) * ROUTE_LINE_CELL,
                (routeLineCell(camera.z) + 0.5) * ROUTE_LINE_CELL);
    }

    private final java.util.Map<WaypointGroup, Object> routeLineScopes = new java.util.WeakHashMap<>();

    /** The retained-geometry scope holding a route's far connector lines (stable per route). */
    public Object routeLineScope(WaypointGroup group) {
        return routeLineScopes.computeIfAbsent(group, g -> new Object());
    }

    /**
     * One pass's connector lines. A retained sink keeps each route's far connectors as static
     * geometry (rebuilt with the route or when the camera changes cell) and emits only the few
     * near the camera, which need per-frame clipping, every frame. During a skip fade a route's
     * lines stay per-frame, as before.
     */
    private void emitRouteLinePass(GeometrySink sink, PoseStack ps, VertexConsumer lines,
                                   List<WaypointGroup> groups, Vec3 camPos, Vec3 playerPos,
                                   double maxStaticDistanceSq, double nearHideDistanceSq,
                                   boolean depthCheckedPass, Minecraft mc, ClientLevel level) {
        boolean retained = sink.retainsStaticGeometry();
        Vec3 cellCenter = routeLineCellCenter(camPos);
        sink.dynamic(() -> {
            DashBudget dashes = new DashBudget(MAX_DASHES_PER_PASS);
            for (WaypointGroup g : groups) {
                RouteLinePart part = !retained || routeFading(g) ? RouteLinePart.ALL : RouteLinePart.NEAR;
                emitRouteLines(ps, lines, g, camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                        depthCheckedPass, mc, level, dashes, part, cellCenter);
            }
        });
        if (!retained) return;
        for (WaypointGroup g : groups) {
            if (routeFading(g)) continue;
            Object scope = routeLineScope(g);
            if (!sink.staticGeometryNeeded(scope)) continue;
            sink.staticGroup(scope, () -> emitRouteLines(ps, lines, g, camPos, playerPos,
                    maxStaticDistanceSq, nearHideDistanceSq, depthCheckedPass, mc, level,
                    new DashBudget(MAX_DASHES_PER_PASS), RouteLinePart.FAR, cellCenter));
        }
    }

    private static boolean routeFading(WaypointGroup group) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        return fade != null && fade.active();
    }

    private void emitRouteLines(PoseStack ps, VertexConsumer lines, WaypointGroup g,
                                Vec3 camPos, Vec3 playerPos,
                                double maxStaticDistanceSq, double nearHideDistanceSq,
                                boolean depthCheckedPass, Minecraft mc, ClientLevel level,
                                DashBudget dashes, RouteLinePart part, Vec3 cellCenter) {
        if (!routeLinesPossible(g)) return;
        boolean segmentStyles = g.hasWaypointStyleOverrides();
        int currentIdx = g.currentIndex();
        WaypointerConfig routeStyle = styleFor(g, null);
        boolean showCompleted = routeStyle.showCompleted();
        float crouchingEyeHeight = mc.player == null
                ? 0.0f : mc.player.getEyeHeight(Pose.CROUCHING);
        var cameraUp = MinecraftCompat.mainCamera(mc.gameRenderer).upVector();
        Vec3 screenDown = new Vec3(-cameraUp.x(), -cameraUp.y(), -cameraUp.z());

        RouteLineSegmentConsumer emitSegment = (fromIndex, toIndex) -> {
            Waypoint a = g.get(fromIndex);
            Waypoint b = g.get(toIndex);
            WaypointerConfig style = styleFor(g, a);
            if (segmentStyles && !routeLinesEnabled(g, a, config, dungeonConfig)) return;
            if (!routeSegmentHasDepthVisibility(g, a, b, depthCheckedPass, mc, level)) return;
            Vec3 start = routeLineStart(
                    level, a, style.useEtherwarpHeight(), crouchingEyeHeight,
                    waypointBoxBoundsScratch);
            populateWaypointRenderAnchor(level, b, waypointBoxBoundsScratch);
            Vec3 end = new Vec3(
                    waypointBoxBoundsScratch[0],
                    waypointBoxBoundsScratch[1],
                    waypointBoxBoundsScratch[2]);

            if (part != RouteLinePart.ALL) {
                boolean near = start.distanceToSqr(cellCenter) <= ROUTE_LINE_NEAR_CELL_DISTANCE_SQ;
                if (near != (part == RouteLinePart.NEAR)) return;
            }
            // Retained connectors are drawn from any direction, so only per-frame ones are culled.
            if (part != RouteLinePart.FAR
                    && !viewCuller.mayBeVisible(Math.min(start.x, end.x), Math.min(start.y, end.y), Math.min(start.z, end.z),
                    Math.max(start.x, end.x), Math.max(start.y, end.y), Math.max(start.z, end.z))) return;
            if (part != RouteLinePart.FAR) start = clipLineStartOutsideCamera(
                    start, end, camPos, screenDown,
                    ROUTE_LINE_CAMERA_CLEARANCE, ROUTE_LINE_CAMERA_SCREEN_OFFSET);
            boolean walkingPath = isStepPathPoint(g, fromIndex) && isStepPathPoint(g, toIndex);
            emitStyledConnector(lines, ps, start, end, style, dashes,
                    walkingPath ? b.color() & 0xFFFFFF : style.routeLineColor());
        };
        if (isDungeonRoomRoute(g)) {
            forEachFocusedDungeonRouteLineSegment(
                    g, depthCheckedPass, waypoint -> lineOfSight(g, waypoint),
                    i -> withinRouteDistance(styleFor(g, g.get(i)), g.get(i), camPos), emitSegment);
            return;
        }
        forEachRouteLineSegment(
                g,
                depthCheckedPass,
                waypoint -> lineOfSight(g, waypoint),
                routeStyle.sequenceVisibility(),
                routeStyle.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> shouldRenderRouteLineEndpoint(g, i, currentIdx, showCompleted,
                        camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq),
                emitSegment);
    }

    public static Vec3 routeLineStart(Waypoint waypoint, boolean useEtherwarpHeight,
                               float crouchingEyeHeight) {
        return routeLineStart(null, waypoint, useEtherwarpHeight, crouchingEyeHeight,
                new double[6]);
    }

    public static Vec3 routeLineStart(ClientLevel level, Waypoint waypoint,
                                       boolean useEtherwarpHeight,
                                       float crouchingEyeHeight, double[] scratch) {
        populateWaypointRenderAnchor(level, waypoint, scratch);
        double y = scratch[1];
        if (useEtherwarpHeight) y += 0.5 + Math.max(0.0f, crouchingEyeHeight);
        return new Vec3(scratch[0], y, scratch[2]);
    }

    public static Vec3 clipLineStartOutsideCamera(Vec3 start, Vec3 end, Vec3 camera,
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

    public static void forEachRouteLineSegment(WaypointGroup group,
                                        boolean depthCheckedPass,
                                        IntPredicate endpointVisible,
                                        RouteLineSegmentConsumer consumer) {
        forEachRouteLineSegment(group, depthCheckedPass, FLAG_LINE_OF_SIGHT, SequenceVisibility.DEFAULT,
                false, endpointVisible, consumer);
    }

    public static void forEachRouteLineSegment(WaypointGroup group,
                                        boolean depthCheckedPass,
                                        SequenceVisibility visibility,
                                        boolean keepSubwaypointsVisibleUntilNextWaypoint,
                                        IntPredicate endpointVisible,
                                        RouteLineSegmentConsumer consumer) {
        forEachRouteLineSegment(group, depthCheckedPass, FLAG_LINE_OF_SIGHT, visibility,
                keepSubwaypointsVisibleUntilNextWaypoint, endpointVisible, consumer);
    }

    public static void forEachRouteLineSegment(WaypointGroup group,
                                        boolean depthCheckedPass,
                                        Predicate<Waypoint> lineOfSight,
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
                if (routeSegmentMatchesDepthPass(a, b, depthCheckedPass, lineOfSight)) {
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
        forEachFocusedDungeonRouteLineSegment(group, depthCheckedPass, FLAG_LINE_OF_SIGHT,
                endpointVisible, consumer);
    }

    static void forEachFocusedDungeonRouteLineSegment(WaypointGroup group,
                                                      boolean depthCheckedPass,
                                                      Predicate<Waypoint> lineOfSight,
                                                      IntPredicate endpointVisible,
                                                      RouteLineSegmentConsumer consumer) {
        if (group == null || consumer == null) return;
        if (forEachStepPathSegment(group, depthCheckedPass, lineOfSight, endpointVisible,
                consumer)) {
            return;
        }
        int to = group.currentIndex();
        int from = focusedDungeonRoutePreviousIndex(group);
        if (from < 0 || to < 0 || to >= group.size()) return;
        if (endpointVisible != null
                && (!endpointVisible.test(from) || !endpointVisible.test(to))) {
            return;
        }
        Waypoint a = group.get(from);
        Waypoint b = group.get(to);
        if (routeSegmentMatchesDepthPass(a, b, depthCheckedPass, lineOfSight)) {
            consumer.accept(from, to);
        }
    }

    static boolean forEachStepPathSegment(WaypointGroup group, boolean depthCheckedPass,
                                          Predicate<Waypoint> lineOfSight,
                                          IntPredicate endpointVisible,
                                          RouteLineSegmentConsumer consumer) {
        int start = group.currentStepStart();
        if (start < 0) return false;
        int end = group.childEndExclusive(start);
        int first = end;
        int points = 0;
        while (first - 1 > start && (isStepPathPoint(group, first - 1)
                || group.isWaypointDisabled(first - 1))) {
            first--;
            if (isStepPathPoint(group, first)) points++;
        }
        if (points < 2) return false;
        int previous = -1;
        for (int i = first; i < end; i++) {
            if (!isStepPathPoint(group, i)) continue;
            if (previous >= 0
                    && (endpointVisible == null
                    || endpointVisible.test(previous) && endpointVisible.test(i))
                    && routeSegmentMatchesDepthPass(group.get(previous), group.get(i),
                    depthCheckedPass, lineOfSight)) {
                consumer.accept(previous, i);
            }
            previous = i;
        }
        return true;
    }

    static boolean isStepPathPoint(WaypointGroup group, int index) {
        if (!group.isSubwaypoint(index) || group.isWaypointDisabled(index)) return false;
        Waypoint waypoint = group.get(index);
        return (waypoint.flags() & Waypoint.DUNGEON_COMPLETION_FLAGS) == 0
                && !waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET);
    }

    static int focusedDungeonRoutePreviousIndex(WaypointGroup group) {
        if (group == null) return -1;
        int current = group.currentIndex();
        if (current <= 0 || current >= group.size()) return -1;
        if (group.isSubwaypoint(current)) {
            int previous = current - 1;
            while (previous >= 0
                    && (group.isWaypointDisabled(previous)
                    || group.get(previous).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)
                    || group.isSubwaypoint(previous)
                            && !group.get(previous).hasFlag(Waypoint.DUNGEON_COMPLETION_FLAGS))) {
                previous--;
            }
            return previous;
        }

        int previous = current - 1;
        while (previous >= 0
                && (group.isWaypointDisabled(previous)
                || group.get(previous).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)
                || group.isSubwaypoint(previous)
                        && !group.get(previous).hasFlag(Waypoint.DUNGEON_COMPLETION_FLAGS))) {
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
                || index == focusedDungeonRoutePreviousIndex(group)
                || isNamedOpenStepMember(group, index));
    }

    static boolean isNamedOpenStepMember(WaypointGroup group, int index) {
        int start = group.currentStepStart();
        if (start < 0 || index < start || index >= group.childEndExclusive(start)) return false;
        if (index < group.currentIndex() || group.isStepMemberDone(index)
                || group.isWaypointDisabled(index)) return false;
        Waypoint waypoint = group.get(index);
        return waypoint.hasName() && !waypoint.hasFlag(Waypoint.FLAG_HIDE_NAME);
    }

    private boolean shouldRenderRouteLineEndpoint(WaypointGroup group, int index, int currentIdx,
                                                  boolean showCompleted, Vec3 camPos,
                                                  Vec3 playerPos, double maxStaticDistanceSq,
                                                  double nearHideDistanceSq) {
        if (index < 0 || index >= group.size()) return false;
        if (group.isWaypointDisabled(index)) return false;
        if (shouldHideStaticReached(group, index)) return false;

        Waypoint waypoint = group.get(index);
        WaypointerConfig style = styleFor(group, waypoint);
        maxStaticDistanceSq = squaredDistanceLimit(style.maxStaticWaypointRenderDistance());
        nearHideDistanceSq = style.hideWaypointsNearPlayer()
                ? WaypointVisibility.squaredRadius(style.hideWaypointsNearRadius()) : 0;
        showCompleted = style.showCompleted();
        if (waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)) return false;
        if (!withinRouteDistance(style, waypoint, camPos)) return false;
        if (shouldHideNearPlayer(waypoint, playerPos, nearHideDistanceSq)) return false;
        if (isStaticBeyondDistanceLimit(group, waypoint, camPos, maxStaticDistanceSq)) return false;

        State state = stateFor(group, index, currentIdx);
        return !shouldHideCompletedSequenceWaypoint(group, index, currentIdx, state,
                showCompleted, waypoint);
    }

    private boolean routeSegmentHasDepthVisibility(WaypointGroup group, Waypoint a, Waypoint b,
                                                   boolean depthCheckedPass,
                                                   Minecraft mc, ClientLevel level) {
        if (!depthCheckedPass) return true;
        if (lineOfSight(group, a) && !shouldRenderDepthCheckedWaypoint(mc, level, a)) {
            return false;
        }
        return !lineOfSight(group, b) || shouldRenderDepthCheckedWaypoint(mc, level, b);
    }

    @FunctionalInterface
    public interface RouteLineSegmentConsumer {
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
        if (lineOfSight(group, waypoint) != depthCheckedPass) return false;
        if (depthCheckedPass && !shouldRenderDepthCheckedWaypoint(mc, level, waypoint)) {
            return false;
        }
        return true;
    }

    boolean anyLineOfSight(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            if (group.anyWaypointHasFlag(Waypoint.FLAG_DEPTH_CHECKED)) return true;
            if (anyWaypointStyle(group, WaypointerConfig::lineOfSightOnly)) return true;
        }
        return false;
    }

    boolean anyThroughWall(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            if (group.everyWaypointHasFlag(Waypoint.FLAG_DEPTH_CHECKED)) continue;
            if (!group.hasWaypointStyleOverrides()) {
                if (!styleFor(group, null).lineOfSightOnly()) return true;
                continue;
            }
            Boolean shared = null;
            for (Waypoint waypoint : group.waypoints()) {
                if (waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)) continue;
                boolean los;
                if (waypoint.styleOverride().isEmpty()) {
                    if (shared == null) shared = styleFor(group, waypoint).lineOfSightOnly();
                    los = shared;
                } else {
                    los = styleFor(group, waypoint).lineOfSightOnly();
                }
                if (!los) return true;
            }
        }
        return false;
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
        return routeSegmentMatchesDepthPass(a, b, depthCheckedPass, FLAG_LINE_OF_SIGHT);
    }

    static boolean routeSegmentMatchesDepthPass(Waypoint a, Waypoint b, boolean depthCheckedPass,
                                                Predicate<Waypoint> lineOfSight) {
        boolean segmentDepthChecked = lineOfSight.test(a) || lineOfSight.test(b);
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

    long depthVisibilityFingerprint(Iterable<WaypointGroup> groups, Minecraft mc,
                                    ClientLevel level, Vec3 camPos) {
        if (mc == null || level == null || camPos == null
                || !staticGeometryEnabledFor(groups)) return 0L;
        prepareDepthVisibilityCache(level, camPos);
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        long[] hash = {0x9E3779B97F4A7C15L};
        for (WaypointGroup g : groups) {
            if (!g.anyWaypointHasFlag(Waypoint.FLAG_DEPTH_CHECKED)
                    && !anyWaypointStyle(g, WaypointerConfig::lineOfSightOnly)) continue;
            int currentIdx = g.currentIndex();
            boolean showCompleted = config.showCompleted();
            forEachFadingVisibleIndex(g, i ->
                    mixDepthVisibilityCandidate(hash, g, i, currentIdx,
                            showCompleted, mc, level, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq));
            if (currentBeamIndex(g) >= 0 && currentBeamIndex(g) < g.size()
                    && effectiveBeaconBeamMode(styleFor(g, g.get(currentBeamIndex(g)))) == WaypointerConfig.BeaconBeamMode.CURRENT) {
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
        if (!lineOfSight(group, waypoint)) return;
        if (!shouldRenderRouteLineEndpoint(group, index, currentIdx, showCompleted,
                camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq)) return;
        boolean visible = shouldRenderDepthCheckedWaypoint(mc, level, waypoint);
        long mixed = hash[0] ^ (index * 0x9E3779B97F4A7C15L) ^ (visible ? 0x1L : 0x2L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash[0] = mixed ^ (mixed >>> 27);
    }

    long worldVisibilityFingerprint(Iterable<WaypointGroup> groups, Vec3 camPos, Vec3 playerPos) {
        if (camPos == null || !staticGeometryEnabledFor(groups)) return 0L;
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        long[] hash = {mixFingerprint(0xA0761D6478BD642FL, effectiveBeaconBeamMode().ordinal())};
        for (WaypointGroup group : groups) {
            if (!visibilityDependsOnPosition(group)) continue;
            int currentIdx = group.currentIndex();
            boolean showCompleted = config.showCompleted();
            forEachFadingVisibleIndex(group, i ->
                    mixWorldVisibilityCandidate(hash, group, i, currentIdx,
                            showCompleted, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq));
            if (currentBeamIndex(group) >= 0 && currentBeamIndex(group) < group.size()
                    && effectiveBeaconBeamMode(styleFor(group, group.get(currentBeamIndex(group)))) == WaypointerConfig.BeaconBeamMode.CURRENT) {
                mixWorldVisibilityCandidate(hash, group, currentBeamIndex(group), currentIdx,
                        showCompleted, camPos, playerPos,
                        maxStaticDistanceSq, nearHideDistanceSq);
            }
        }
        return hash[0];
    }

    private boolean visibilityDependsOnPosition(WaypointGroup group) {
        if (group.hasWaypointStyleOverrides() || group.usesDungeonSteps()) return true;
        WaypointerConfig style = styleFor(group, null);
        return style.routeMinRenderDistance() > 0 || style.routeMaxRenderDistance() > 0
                || style.hideWaypointsNearPlayer()
                || group.loadMode() == WaypointGroup.LoadMode.STATIC && style.maxStaticWaypointRenderDistance() > 0;
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
        if (groups == null || !anyStyle(groups, c -> c.beaconOpacity() > 0.0)) {
            WaypointPaintTextureCache.resetRetainedReservation();
            return 0;
        }
        activePaintScratch.clear();
        for (WaypointGroup group : groups) {
            if (group == null || group.isEmpty()) continue;
            boolean needsTexture = anyWaypointStyle(group, style -> style.beaconOpacity() > 0
                    && !(style.colorSequenceWaypointsByRole()
                            && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE));
            if (needsTexture) activePaintScratch.addAll(paintsFor(group));
        }
        return WaypointPaintTextureCache.reserveForActivePaints(activePaintScratch);
    }

    long blockShapeFingerprint(Iterable<WaypointGroup> groups, ClientLevel level,
                               Vec3 camPos, Vec3 playerPos) {
        if (groups == null || level == null || camPos == null
                || !staticBoxGeometryEnabledFor(groups)) return 0L;
        long[] hash = {0xD6E8FEB86659FD93L};
        int[] shapedWaypoints = {0};
        double[] bounds = waypointBoxBoundsScratch;
        for (WaypointGroup group : groups) {
            if (!group.anyWaypointHasFlag(BLOCK_SHAPE_FLAGS)) continue;
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
        boolean visibleFlatBeams = anyStyle(groups, c -> effectiveBeaconBeamMode(c) != WaypointerConfig.BeaconBeamMode.OFF
                && !c.useBeaconBeamTextures() && c.beaconOpacity() > 0.0);
        return visibleBoxes || visibleFlatBeams;
    }

    boolean staticBoxGeometryEnabledFor(Iterable<WaypointGroup> groups) {
        if (groups == null) return false;
        boolean visibleOutlines = anyStyle(groups, c -> boxStyleDrawsOutline(c.effectiveBoxStyle())
                && c.waypointOutlineOpacity() > 0.0);
        boolean hasPaint = anyPainted(groups);
        boolean hasFilledSub = hasFilledSubwaypoint(groups);
        boolean visibleBoxes = anyStyle(groups, c -> (boxStyleDrawsRgbFill(c.effectiveBoxStyle())
                || c.effectiveBoxStyle() == WaypointerConfig.BoxStyle.PAINT || hasPaint || hasFilledSub)
                && c.beaconOpacity() > 0.0);
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
            if (group.anySubwaypointHasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT)) return true;
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

    private boolean anyPainted(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            if (!paintsFor(group).isEmpty()) return true;
        }
        return false;
    }

    static boolean isPaintStyle(WaypointerConfig style) {
        return style.effectiveBoxStyle() == WaypointerConfig.BoxStyle.PAINT;
    }

    private List<WaypointPaint> paintsFor(WaypointGroup group) {
        if (group == null || group.isEmpty()) return List.of();
        if (!group.hasWaypointStyleOverrides()) {
            WaypointPaint paint = paintFor(group, null);
            return paint == null ? List.of() : List.of(paint);
        }
        // A frame asks for a route's paints several times (textures, fingerprints, geometry), and
        // with any per-point style that meant a whole-route walk each time. The answer only
        // changes with the route's points, its paint settings, its resolved style or the snowman
        // face, so it is kept until one of those does.
        WaypointerConfig routeStyle = styleFor(group, null);
        WaypointPaint snowman = HappySnowmanSession.facePaint();
        long content = group.contentVersion();
        boolean cacheable = routeStyle != config; // a resolved snapshot resolves to itself
        PaintsCache cached = cacheable ? paintsCache.get(group) : null;
        if (cached != null && cached.content == content && cached.paint == group.paint()
                && cached.paintEnabled == group.paintEnabled() && cached.routeStyle == routeStyle
                && cached.snowman == snowman) {
            return cached.paints;
        }
        List<WaypointPaint> paints = new ArrayList<>(2);
        for (Waypoint waypoint : group.waypoints()) {
            WaypointPaint paint = paintFor(group, waypoint);
            if (paint != null && !paints.contains(paint)) paints.add(paint);
        }
        List<WaypointPaint> result = List.copyOf(paints);
        if (cacheable) {
            paintsCache.put(group, new PaintsCache(content, group.paint(), group.paintEnabled(),
                    routeStyle, snowman, result));
        }
        return result;
    }

    private record PaintsCache(long content, WaypointPaint paint, boolean paintEnabled,
                               WaypointerConfig routeStyle, WaypointPaint snowman,
                               List<WaypointPaint> paints) {}

    private final java.util.Map<WaypointGroup, PaintsCache> paintsCache = new java.util.WeakHashMap<>();

    private WaypointPaint paintFor(WaypointGroup group, Waypoint waypoint) {
        WaypointerConfig style = styleFor(group, waypoint);
        return effectivePaint(group, waypoint, style.waypointPainterDefaultPaint(), isPaintStyle(style));
    }

    static WaypointPaint effectivePaint(WaypointGroup group, WaypointPaint defaultPaint) {
        return effectivePaint(group, defaultPaint, false);
    }

    static WaypointPaint effectivePaint(WaypointGroup group, WaypointPaint defaultPaint, boolean paintStyle) {
        return effectivePaint(group, null, defaultPaint, paintStyle);
    }

    static WaypointPaint effectivePaint(WaypointGroup group, Waypoint waypoint,
                                        WaypointPaint styledPaint, boolean paintStyle) {
        WaypointPaint happySnowmanPaint = HappySnowmanSession.facePaint();
        if (happySnowmanPaint != null) return happySnowmanPaint;
        if (group == null) return null;
        if (styledPaint != null && waypoint != null
                && waypoint.styleOverride().values().containsKey(WaypointStyles.PAINT)) return styledPaint;
        if (!group.paintEnabled() && !paintStyle) return null;
        WaypointPaint paint = group.paint() != null ? group.paint() : styledPaint;
        return paint != null ? paint : paintStyle ? WaypointPaint.missing() : null;
    }

    static boolean shouldEmitRgbFill(WaypointGroup group, WaypointPaint defaultPaint) {
        return shouldEmitRgbFill(group, defaultPaint, false);
    }

    static boolean shouldEmitRgbFill(WaypointGroup group, WaypointPaint defaultPaint, boolean paintStyle) {
        return effectivePaint(group, defaultPaint, paintStyle) == null;
    }

    static boolean usesRgbPaintFallback(WaypointGroup group, WaypointPaint defaultPaint) {
        return usesRgbPaintFallback(group, defaultPaint, false);
    }

    static boolean usesRgbPaintFallback(WaypointGroup group, WaypointPaint defaultPaint, boolean paintStyle) {
        WaypointPaint paint = effectivePaint(group, defaultPaint, paintStyle);
        return paint != null && !WaypointPaintTextureCache.isRetained(paint);
    }

    static boolean usesSequenceRoleColorPaintFallback(
            WaypointGroup group, WaypointPaint defaultPaint, boolean roleColorsEnabled) {
        return usesSequenceRoleColorPaintFallback(group, defaultPaint, roleColorsEnabled, false);
    }

    static boolean usesSequenceRoleColorPaintFallback(
            WaypointGroup group, WaypointPaint defaultPaint, boolean roleColorsEnabled, boolean paintStyle) {
        return roleColorsEnabled
                && group != null
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && effectivePaint(group, defaultPaint, paintStyle) != null;
    }

    private static boolean isSmallSubwaypoint(Waypoint waypoint) {
        return waypoint != null
                && waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
    }

    public static boolean isFilledSubwaypoint(Waypoint waypoint) {
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

    private static final int BLOCK_SHAPE_FLAGS =
            Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_SKIP_ON_MINE | Waypoint.FLAG_SUBWAYPOINT;

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

    protected void populateVisualWaypointBoxBounds(ClientLevel level, WaypointGroup group,
                                                    Waypoint waypoint, double[] bounds) {
        populateWaypointBoxBounds(level, waypoint, bounds);
        scaleBoundsAroundCenter(bounds, styleFor(group, waypoint).waypointMarkerScale());
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

        forEachFadingVisibleIndex(g,
                i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level)) {
                return;
            }

            Waypoint w = g.get(i);
            WaypointerConfig config = styleFor(g, w);
            if (!boxStyleDrawsOutline(config.effectiveBoxStyle())) return;
            State state = stateFor(g, i, currentIdx);
            float alpha = alphaFor(g, i, state);
            if (alpha * config.waypointOutlineOpacity() <= 0) return;
            populateVisualWaypointBoxBounds(level, g, w, waypointBoxBoundsScratch);
            emitStyledMarker(lines, ps, waypointBoxBoundsScratch,
                    resolvedWaypointColor(g, i, w.color()), alpha, config, true);
        });
    }

    private void emitFilledBoxes(PoseStack ps, VertexConsumer quads, ClientLevel level, WaypointGroup g,
                                 Vec3 camPos, Vec3 playerPos,
                                 double maxStaticDistanceSq, double nearHideDistanceSq,
                                 boolean depthCheckedPass,
                                 Minecraft mc) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();

        forEachFadingVisibleIndex(g,
                i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level)) {
                return;
            }

            Waypoint w = g.get(i);
            WaypointerConfig config = styleFor(g, w);
            WaypointPaint paint = paintFor(g, w);
            boolean paintFallback = paint != null && (!WaypointPaintTextureCache.isRetained(paint)
                    || config.colorSequenceWaypointsByRole() && g.loadMode() == WaypointGroup.LoadMode.SEQUENCE);
            if (!paintFallback && paint != null) return;
            if (!paintFallback && !boxStyleDrawsRgbFill(config.effectiveBoxStyle())
                    && config.effectiveBoxStyle() != WaypointerConfig.BoxStyle.PAINT
                    && !isFilledSubwaypoint(w)) return;

            State state = stateFor(g, i, currentIdx);
            float alpha = alphaFor(g, i, state);
            if (alpha * config.beaconOpacity() <= 0) return;
            populateVisualWaypointBoxBounds(level, g, w, waypointBoxBoundsScratch);
            emitStyledMarker(quads, ps, waypointBoxBoundsScratch,
                    resolvedWaypointColor(g, i, w.color()), alpha, config, false);
        });
    }

    private void emitPaintedBoxes(PoseStack ps, VertexConsumer quads,
                                  ClientLevel level, WaypointGroup group, WaypointPaint only,
                                   Vec3 camPos, Vec3 playerPos,
                                    double maxStaticDistanceSq, double nearHideDistanceSq,
                                    boolean depthCheckedPass, Minecraft mc,
                                    boolean retainCameraIndependentGeometry) {
        int currentIndex = group.currentIndex();
        boolean showCompleted = config.showCompleted();

        forEachFadingVisibleIndex(group, i -> {
            if (!shouldRenderWaypointWorld(group, i, currentIndex, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level)) {
                return;
            }

            Waypoint waypoint = group.get(i);
            WaypointerConfig config = styleFor(group, waypoint);
            if (config.colorSequenceWaypointsByRole()
                    && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE) return;
            if (group.hasWaypointStyleOverrides() && !only.equals(paintFor(group, waypoint))) return;
            State state = stateFor(group, i, currentIndex);
            float alpha = alphaFor(group, i, state) * (float) config.beaconOpacity() * PAINTED_ALPHA_SCALE;
            if (alpha <= 0) return;
            populateVisualWaypointBoxBounds(level, group, waypoint, waypointBoxBoundsScratch);
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
        return effectiveBeaconBeamMode(config);
    }

    private WaypointerConfig.BeaconBeamMode effectiveBeaconBeamMode(WaypointerConfig config) {
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
        if (g.isEmpty()) return;
        int currentIdx = g.currentIndex();
        boolean showCompleted = styleFor(g, null).showCompleted();
        int beamIndex = currentBeamIndex(g);
        if (!g.hasWaypointStyleOverrides()) {
            // Every point shares the route's beam settings, so OFF draws nothing and CURRENT can
            // only draw the current point and a fading outgoing one: don't walk the whole route.
            WaypointerConfig routeStyle = styleFor(g, null);
            WaypointerConfig.BeaconBeamMode routeMode = effectiveBeaconBeamMode(routeStyle);
            if (routeMode == WaypointerConfig.BeaconBeamMode.OFF
                    || routeStyle.useBeaconBeamTextures() != texturedBeams) return;
            WaypointSkipFade fade = WaypointSkipFade.get(g);
            // While a skip fade runs, the full walk below also records whether the outgoing point
            // was normally visible, which marker drawing reads; fades last a fraction of a second.
            boolean fading = fade != null && fade.active() && routeStyle.showCurrentSequenceWaypoint();
            if (routeMode == WaypointerConfig.BeaconBeamMode.CURRENT && !fading) {
                if (beamIndex >= 0 && beamIndex < g.size()) {
                    emitBeaconBeamIfVisible(ps, quads, g, beamIndex,
                            currentIdx, showCompleted, camPos, playerPos, maxStaticDistanceSq,
                            nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level, texturedBeams);
                }
                return;
            }
        }
        boolean[] currentVisited = {false};
        forEachFadingVisibleIndex(g, i -> {
            if (i == beamIndex) currentVisited[0] = true;
            emitBeaconBeamIfVisible(ps, quads, g, i,
                    currentIdx, showCompleted, camPos, playerPos, maxStaticDistanceSq,
                    nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level, texturedBeams);
        });
        if (!currentVisited[0] && beamIndex >= 0 && beamIndex < g.size()
                && effectiveBeaconBeamMode(styleFor(g, g.get(beamIndex))) == WaypointerConfig.BeaconBeamMode.CURRENT) {
            emitBeaconBeamIfVisible(ps, quads, g, beamIndex,
                    currentIdx, showCompleted, camPos, playerPos, maxStaticDistanceSq,
                    nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level, texturedBeams);
        }
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
        WaypointerConfig config = styleFor(g, w);
        WaypointerConfig.BeaconBeamMode mode = effectiveBeaconBeamMode(config);
        if (mode == WaypointerConfig.BeaconBeamMode.OFF || config.useBeaconBeamTextures() != texturedBeams) return;
        State state = stateFor(g, i, currentIdx);
        WaypointSkipFade fade = WaypointSkipFade.get(g);
        if (mode == WaypointerConfig.BeaconBeamMode.CURRENT && i != currentBeamIndex(g)
                && (fade == null || !fade.isOutgoing(i) || !config.showCurrentSequenceWaypoint())) return;
        boolean outgoingBeamNormallyVisible = fade != null && fade.outgoingNormallyVisible;
        if (mode == WaypointerConfig.BeaconBeamMode.CURRENT && fade != null && fade.active()) {
            outgoingBeamNormallyVisible = currentBeamIndex(g) == fade.outgoing() && config.showCompleted();
        }
        float factor = fade == null ? styleStateAlpha(config, g, state)
                : fade.beamAlpha(i, styleStateAlpha(config, g, state),
                        !fade.isOutgoing(i) || outgoingBeamNormallyVisible);
        float alpha = factor * (float) config.beaconOpacity();
        if (alpha <= 0.0f) return;

        float y1 = config.beaconBeamExtendsBelowWaypoint() ? minY : w.y();
        float y2 = beaconBeamTop(w.y(), y1, maxY);
        int waypointColor = resolvedWaypointColor(g, i, w.color());
        float animationTime = mc.level == null
                ? 0.0f
                : beaconAnimationTime(mc.level.getGameTime(),
                        mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), config.beaconMotionSpeed());
        float radiusScale = beaconTextureRadiusScale(
                w, camPos, mc.player != null && mc.player.isScoping());

        double reach = BeaconRenderer.BEAM_GLOW_RADIUS * radiusScale * config.beaconWidthScale();
        if (texturedBeams && !viewCuller.mayBeVisible(w.centerX() - reach, y1, w.centerZ() - reach,
                w.centerX() + reach, y2, w.centerZ() + reach)) return;
        emitStyledBeacon(quads, ps, (float) w.centerX(), (float) w.centerZ(),
                y1, y2, waypointColor, alpha, animationTime, radiusScale, config);
    }

    public static void emitStyledBeacon(VertexConsumer consumer, PoseStack ps,
                                         float cx, float cz, float y1, float y2, int waypointColor,
                                         float alpha, float animationTime, float radiusScale,
                                         WaypointerConfig config) {
        float height = y2 - y1;
        if (height <= 0.0f || alpha <= 0) return;
        if (!config.useBeaconBeamTextures()) {
            RenderHelpers.emitVerticalColumn(consumer, ps, cx, y1, cz, y2,
                    BEAM_HALF_WIDTH * (float) config.beaconWidthScale(), waypointColor, alpha * BEAM_ALPHA_SCALE);
            return;
        }
        radiusScale *= (float) config.beaconWidthScale();
        int coreColor = RenderHelpers.withAlpha(0xFF000000 | waypointColor, alpha);
        int glowColor = RenderHelpers.withAlpha(BEACON_GLOW_BASE_ALPHA_ARGB | waypointColor, alpha);

        PoseStack.Pose pose = ps.last();
        float r = BeaconRenderer.SOLID_BEAM_RADIUS * radiusScale;
        double radians = Math.toRadians(beaconRotationDegrees(animationTime));
        float sr = (float) Math.sin(radians) * r;
        float cr = (float) Math.cos(radians) * r;
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

    public static float beaconAnimationTime(long gameTime, float partialTick) {
        return Math.floorMod(gameTime, 40L) + partialTick;
    }

    public static float beaconAnimationTime(long gameTime, float partialTick, double speed) {
        if (speed == 1) return beaconAnimationTime(gameTime, partialTick);
        return (float) (((gameTime + (double) partialTick) * speed) % 40);
    }

    static float beaconRotationDegrees(float animationTime) {
        return animationTime * 2.25f - 45.0f;
    }

    public static float beaconBeamTop(float waypointY, float startY, int maxBuildY) {
        return Math.max(startY + 1.0f,
                Math.max(maxBuildY, waypointY + BeaconRenderer.MAX_RENDER_Y));
    }

    public static float beaconTextureRadiusScale(Waypoint waypoint, Vec3 camPos,
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

    public static int currentBeamIndex(WaypointGroup g) {
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
        Waypoint waypoint = group == null || waypointIndex < 0 || waypointIndex >= group.size()
                ? null : group.get(waypointIndex);
        WaypointerConfig config = styleFor(group, waypoint);
        if (waypoint != null) fallbackColor = WaypointStyles.waypointColor(this.config, group, waypoint);
        return SequenceRoleColor.resolve(
                group,
                waypointIndex,
                config.colorSequenceWaypointsByRole(),
                config.sequencePreviousWaypointColor(),
                config.sequenceCurrentWaypointColor(),
                config.sequenceNextWaypointColor(),
                fallbackColor);
    }

    public static State stateFor(WaypointGroup group, int i, int currentIdx) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC || group.showAllWaypoints()) {
            return State.CURRENT;
        }
        return stateFor(group, i, currentIdx, group.activeSubwaypointParentIndex());
    }

    public static State stateFor(WaypointGroup group, int i, int currentIdx, int activeSubwayParent) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC || group.showAllWaypoints()) {
            return State.CURRENT;
        }
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
        if (fade != null && (fade.isOutgoing(index) || fade.fadingOut().contains(index))
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

    public static boolean shouldForceHideReachedWaypoint(int index, int currentIdx, Waypoint waypoint) {
        return waypoint != null
                && waypoint.hasFlag(Waypoint.FLAG_HIDE_BEACON)
                && index < currentIdx;
    }

    boolean shouldHideStaticReached(WaypointGroup group, int index) {
        WaypointerConfig config = styleFor(group, group.get(index));
        return !CompassMarkerState.navigationStopped(group.get(index))
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
        else if (sink.staticGeometryNeeded(group)) sink.staticGroup(group, body);
    }

    protected void forEachFadingVisibleIndex(WaypointGroup group, IntConsumer action) {
        WaypointerConfig config = styleFor(group, null);
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
        for (int index : fade.fadingOut()) {
            if (group.isWaypointEnabled(index)) action.accept(index);
        }
    }

    float alphaFor(WaypointGroup group, int index, State state) {
        float normal = styleStateAlpha(styleFor(group, group.get(index)), group, state);
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        return fade == null ? normal : fade.alpha(index, normal,
                !fade.isOutgoing(index) || fade.outgoingNormallyVisible);
    }

    float alphaFor(WaypointGroup group, State state) {
        return styleStateAlpha(styleFor(group, null), group, state);
    }

    public static float styleStateAlpha(WaypointerConfig config, WaypointGroup group, int index) {
        return styleStateAlpha(config, group, stateFor(group, index, group.currentIndex()));
    }

    public static float styleStateAlpha(WaypointerConfig config, WaypointGroup group, State state) {
        float alpha = (float) switch (state) {
            case CURRENT -> config.currentWaypointOpacity();
            case COMPLETED -> config.completedWaypointOpacity();
            case UPCOMING -> config.upcomingWaypointOpacity();
        };
        if (config.dimSequenceContextWaypoints() && group != null
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE && state != State.CURRENT) {

            alpha = (float) config.sequenceContextOpacity();
        }
        return alpha;
    }

    protected WaypointerConfig styleFor(WaypointGroup group, Waypoint waypoint) {
        return WaypointStyles.resolve(config, group, waypoint);
    }

    boolean lineOfSight(WaypointGroup group, Waypoint waypoint) {
        return waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED) || styleFor(group, waypoint).lineOfSightOnly();
    }

    static final Predicate<Waypoint> FLAG_LINE_OF_SIGHT = waypoint -> waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);

    private boolean anyStyle(Iterable<WaypointGroup> groups, Predicate<WaypointerConfig> predicate) {
        for (WaypointGroup group : groups) {
            if (anyWaypointStyle(group, predicate)) return true;
        }
        return false;
    }

    private boolean anyWaypointStyle(WaypointGroup group, Predicate<WaypointerConfig> predicate) {
        if (!group.hasWaypointStyleOverrides()) {
            return !group.isEmpty() && predicate.test(styleFor(group, null));
        }
        boolean sharedChecked = false;
        for (Waypoint waypoint : group.waypoints()) {
            if (waypoint.styleOverride().isEmpty()) {
                if (sharedChecked) continue;
                sharedChecked = true;
            }
            if (predicate.test(styleFor(group, waypoint))) return true;
        }
        return false;
    }

    public static boolean withinRouteDistance(WaypointerConfig config, Waypoint waypoint, Vec3 camera) {
        if (camera == null) return true;
        double min = config.routeMinRenderDistance();
        double max = config.routeMaxRenderDistance();
        if (min <= 0 && max <= 0) return true;
        double dx = waypoint.centerX() - camera.x;
        double dy = waypoint.centerY() - camera.y;
        double dz = waypoint.centerZ() - camera.z;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        return (min <= 0 || distanceSq >= min * min) && (max <= 0 || distanceSq <= max * max);
    }

    public static void emitStyledConnector(VertexConsumer vertices, PoseStack pose,
                                            Vec3 start, Vec3 end, WaypointerConfig config) {
        emitStyledConnector(vertices, pose, start, end, config, new DashBudget(MAX_DASHES_PER_CONNECTOR));
    }

    static void emitStyledConnector(VertexConsumer vertices, PoseStack pose,
                                    Vec3 start, Vec3 end, WaypointerConfig config, DashBudget dashes) {
        emitStyledConnector(vertices, pose, start, end, config, dashes, config.routeLineColor());
    }

    static void emitStyledConnector(VertexConsumer vertices, PoseStack pose,
                                    Vec3 start, Vec3 end, WaypointerConfig config, DashBudget dashes,
                                    int color) {
        float width = (float) (config.routeLineWidth() > 0
                ? config.routeLineWidth() : config.waypointOutlineThickness());
        float alpha = 0.85f * (float) config.routeLineOpacity();
        if (alpha <= 0) return;
        double length = start.distanceTo(end);
        int count = config.routeLineDashed() && length > 0
                ? dashes.take((int) Math.min(MAX_DASHES_PER_CONNECTOR, Math.ceil(length))) : 0;
        if (count == 0) {
            RenderHelpers.emitLine(vertices, pose,
                    (float) start.x, (float) start.y, (float) start.z,
                    (float) end.x, (float) end.y, (float) end.z,
                    color, alpha, width);
            return;
        }

        double period = Math.max(1, length / count);
        Vec3 direction = end.subtract(start).scale(1 / length);
        for (int dash = 0; dash < count; dash++) {
            double offset = dash * period;
            Vec3 a = start.add(direction.scale(offset));
            Vec3 b = start.add(direction.scale(Math.min(length, offset + period * 0.6)));
            RenderHelpers.emitLine(vertices, pose,
                    (float) a.x, (float) a.y, (float) a.z,
                    (float) b.x, (float) b.y, (float) b.z,
                    color, alpha, width);
        }
    }

    static final class DashBudget {
        private int remaining;

        DashBudget(int dashes) {
            remaining = dashes;
        }

        int take(int wanted) {
            int granted = Math.min(wanted, remaining);
            remaining -= granted;
            return granted;
        }
    }

    public static void emitStyledMarker(VertexConsumer vertices, PoseStack pose, double[] bounds,
                                         int color, float alpha, WaypointerConfig config, boolean outlinePass) {
        if (alpha * (outlinePass ? config.waypointOutlineOpacity() : config.beaconOpacity()) <= 0) return;
        float x1 = (float) bounds[BOX_MIN_X], y1 = (float) bounds[BOX_MIN_Y], z1 = (float) bounds[BOX_MIN_Z];
        float x2 = (float) bounds[BOX_MAX_X], y2 = (float) bounds[BOX_MAX_Y], z2 = (float) bounds[BOX_MAX_Z];
        if (outlinePass) {
            RenderHelpers.emitLineBox(vertices, pose, x1, y1, z1, x2, y2, z2,
                    config.resolvedWaypointOutlineColor(color), alpha * (float) config.waypointOutlineOpacity(),
                    (float) config.waypointOutlineThickness());
        } else {
            RenderHelpers.emitFilledBox(vertices, pose, x1, y1, z1, x2, y2, z2,
                    color, alpha * (float) config.beaconOpacity() * FILLED_ALPHA_SCALE);
        }
    }

    static float roleAlpha(WaypointGroup group, State state, boolean dimContext) {
        if (dimContext
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && state != State.CURRENT) {
            return SEQUENCE_CONTEXT_ALPHA;
        }
        return state.alpha;
    }

    private float effectiveOutlineThickness() {
        return (float) config.waypointOutlineThickness();
    }

    public enum State {
        COMPLETED(0.25f),
        CURRENT(1.0f),
        UPCOMING(0.65f);

        final float alpha;
        State(float a) { this.alpha = a; }
    }

}
