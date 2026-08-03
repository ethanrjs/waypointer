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
import com.babbur.waypointer.dungeon.DungeonPearlTrajectory;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.text.AmpersandFormatting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
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

/**
 * Draws every active waypoint as an outlined cube (world-space) plus a 2D label
 * anchored over it (screen-space).
 *
 * State-driven coloring in SEQUENCE mode:
 *   - Completed (i < currentIndex): dim alpha, hidden if its FLAG_HIDE_BEACON is set.
 *   - Current   (i == currentIndex): full alpha, label always visible.
 *   - Upcoming  (i >  currentIndex): mid alpha.
 *
 * STATIC groups intentionally draw every waypoint as current/full-alpha. Their
 * purpose is to act as a persistent map overlay, so proximity progress should
 * not make markers fade just because the player walked near them.
 *
 * <p><b>Why two render paths?</b> Minecraft 1.21.9 reworked world-space text to go
 * through an {@code OrderedSubmitNodeCollector} queue, and neither the old
 * {@code Font#drawInBatch} path nor the new {@code queue.submitText} call is
 * reliably producing pixels in our harness. Rather than keep chasing the
 * world-space path, labels now render as 2D HUD text: we project each waypoint's
 * world anchor with the same interpolated FOV the world pass uses and draw the
 * label at that pixel position. Always facing the player is then automatic --
 * the text is literally in screen space -- and the vanilla GUI font pipeline
 * handles glyph batching the way we know works.
 *
 * <p>Cube outlines stay on the world-space path because our custom line pipeline
 * already uploads its own vertex buffers and is working correctly.
 *
 * <p>Load-mode aware: {@code STATIC} groups render every waypoint, {@code SEQUENCE}
 * groups render only the prev/current/next triple (delegated to
 * {@link WaypointGroup#forEachVisibleIndex}).
 */
public final class WaypointRenderer implements HudElement {

    /** Namespace/path for the Fabric HUD layer this renderer installs. */
    private static final Identifier LABEL_HUD_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "waypoint_labels");

    /**
     * Baseline vertical lift (blocks) above the waypoint's bottom corner where
     * the label's world-space anchor sits. Matches the old billboarded placement
     * so the default placement is unchanged from the previous renderer; users
     * who want the label higher (e.g. to stop it covering the marker at long
     * range) layer {@link WaypointerConfig#labelHeightOffset()} on top of this.
     */
    private static final double LABEL_ANCHOR_LIFT = 1.6;

    /** Opaque ARGB for the waypoint name -- readable against every biome. */
    private static final int NAME_ARGB = 0xFFFFFFFF;

    /** Slightly dimmer than the name so the distance row reads as secondary info. */
    private static final int DISTANCE_ARGB = 0xFFCCCCCC;

    /**
     * ~70% black backdrop drawn behind each label. Vanilla nametags use ~25%
     * opacity but that disappears against bright sky; 70% stays legible against
     * every environment we've tested without looking opaque.
     */
    private static final int LABEL_BACKDROP_ARGB = 0xB0000000;

    /** Alpha used for sequence-mode context points around the active target. */
    private static final float SEQUENCE_CONTEXT_ALPHA = 0.35f;

    /** Horizontal padding (screen pixels) added to the backdrop around the text. */
    private static final int BACKDROP_PAD_X = 2;

    /** Vertical padding (screen pixels) added to the backdrop around the text. */
    private static final int BACKDROP_PAD_Y = 1;

    /** Gap between the name row and the distance row below it. */
    private static final int DISTANCE_ROW_GAP = 1;
    private static final int SCREEN_CULL_MARGIN = 64;
    private static final double LABEL_SCALE_REFERENCE_DEPTH = 24.0;
    private static final double LABEL_SCALE_BASELINE_FOV_DEGREES = 70.0;
    private static final float LABEL_SCALE_MIN = 0.25f;
    private static final float LABEL_SCALE_MAX = 4.0f;
    private static final double SMALL_SUBWAYPOINT_SIZE = 1.0 / 16.0;
    private static final double BLOCK_SHAPE_EPSILON = 1.0E-6;
    private static final int EDIT_MODE_SUBTITLE_ARGB = 0xFF55FFFF;
    private static final String EDIT_MODE_SUBTITLE_BASE_TEXT = "EDIT MODE";
    private static final int LINE_OF_SIGHT_SAMPLE_COUNT = 9;
    private static final int BOX_MIN_X = 0;
    private static final int BOX_MIN_Y = 1;
    private static final int BOX_MIN_Z = 2;
    private static final int BOX_MAX_X = 3;
    private static final int BOX_MAX_Y = 4;
    private static final int BOX_MAX_Z = 5;
    private static final float DUNGEON_ENTRY_PATH_ALPHA = 0.9f;
    private static final long DUNGEON_ENTRY_REPATH_INTERVAL_NANOS = 250_000_000L;
    private static final int DUNGEON_ENTRY_PATH_CACHE_SIZE = 8;
    private static final int DUNGEON_ENTRY_MAX_EXPANSIONS = 8_000;
    private static final int DUNGEON_ENTRY_SEARCH_PADDING = 48;

    /**
     * Cap on the pre-baked distance table. 0..4095m covers dense imported route
     * overlays without allocating one distance string per visible label per
     * frame. The array is still tiny compared with a single route import.
     */
    private static final int DISTANCE_CACHE_MAX = 4096;
    private static final int HUD_LINE_CULL_MARGIN = 64;
    private static final String[] DISTANCE_CACHE;
    static {
        DISTANCE_CACHE = new String[DISTANCE_CACHE_MAX];
        for (int i = 0; i < DISTANCE_CACHE_MAX; i++) DISTANCE_CACHE[i] = i + "m";
    }
    private static final int[] BOX_EDGE_A = {0, 1, 3, 2, 4, 5, 7, 6, 0, 1, 2, 3};
    private static final int[] BOX_EDGE_B = {1, 3, 2, 0, 5, 7, 6, 4, 4, 5, 6, 7};

    /**
     * Bounded cache for generated numeric labels like "#34". User-named
     * waypoints already return their stored string; this only avoids rebuilding
     * unnamed labels every render frame.
     */
    private static final int INDEX_LABEL_CACHE_MAX = 256;

    /**
     * Bounded LRU for {@code &}-formatted name translations. Names without
     * codes take {@link AmpersandFormatting}'s same-instance fast path, but
     * Skyblock-style names ({@code &e&lMineshaft}) are the norm on imported
     * routes and used to allocate a fresh translated string per label per
     * frame.
     */
    private static final int NAME_TRANSLATION_CACHE_MAX = 1024;
    private static final Comparator<LabelCandidate> LABEL_NEAREST_FIRST =
            Comparator.comparingDouble(c -> c.distanceSquared);

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final DungeonConfig dungeonConfig;

    /**
     * Reusable scratch buffer for the fallback distance formatter. Safe because
     * {@link #render} only runs on the client/render thread; never escape this
     * reference from a render frame.
     */
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
    private final double[] waypointBoxBoundsScratch = new double[6];
    private final boolean[] boxCornerVisible = new boolean[8];
    private double projectedBoxMinX;
    private double projectedBoxMinY;
    private double projectedBoxMaxX;
    private double projectedBoxMaxY;
    private final ArrayList<LabelCandidate> labelCandidates = new ArrayList<>();
    private int labelCandidateCount;
    // Shared per-tick beam-core rotation; see updateBeamRotation.
    private float beamRotationAnimationTime = Float.NaN;
    private float beamRotationCos;
    private float beamRotationSin;
    private ClientLevel dungeonEntryPathLevel;
    private final Map<DungeonEntryPathTarget, DungeonEntryPath> dungeonEntryPathCache =
            new LinkedHashMap<>(DUNGEON_ENTRY_PATH_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<DungeonEntryPathTarget, DungeonEntryPath> eldest) {
                    return size() > DUNGEON_ENTRY_PATH_CACHE_SIZE;
                }
            };

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, null);
    }

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config,
                            DungeonConfig dungeonConfig) {
        this.manager = manager;
        this.config = config;
        this.dungeonConfig = dungeonConfig;
    }

    public void install() {
        LevelRenderEvents.COLLECT_SUBMITS.register(this::onWorldRender);
        // Attaching before CHAT inherits chat's render condition, which means the
        // labels respect the "hide GUI" (F1) toggle the same way chat does. That
        // matches player expectation for any in-world HUD overlay.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, LABEL_HUD_ID, this);
    }

    public static AABB waypointBoxBounds(ClientLevel level, Waypoint waypoint) {
        if (waypoint == null) return null;
        double[] bounds = new double[6];
        populateWaypointBoxBounds(level, waypoint, bounds);
        return new AABB(
                bounds[BOX_MIN_X], bounds[BOX_MIN_Y], bounds[BOX_MIN_Z],
                bounds[BOX_MAX_X], bounds[BOX_MAX_Y], bounds[BOX_MAX_Z]);
    }

    // ---- world-space path: cube outlines -------------------------------------------------

    /** The opacity setting is authoritative: 100% must produce opaque faces. */
    private static final float FILLED_ALPHA_SCALE = 1.0f;
    private static final float PAINTED_ALPHA_SCALE = 1.0f;
    private static final float BEAM_ALPHA_SCALE = 0.18f;
    private static final float BEAM_HALF_WIDTH = 0.12f;
    private static final float BEACON_TEXTURE_SCALE_THRESHOLD = 96.0f;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
    private static final int BEACON_GLOW_BASE_ALPHA_ARGB = 0x20000000;
    private static final int DEFAULT_MIN_BUILD_Y = -64;
    private static final int DEFAULT_MAX_BUILD_Y = 320;

    private void onWorldRender(LevelRenderContext ctx) {
        var groups = manager.activeGroups();
        boolean irisHudFallbackActive = IrisShaderFallback.shouldUse(config);
        RenderDiagnostics.beginFrame(groups, config, irisHudFallbackActive);

        if (groups.isEmpty()) return;

        WaypointerConfig.BoxStyle style = config.boxStyle();
        boolean drawLines = worldBoxOutlinesEnabled(style, irisHudFallbackActive);
        boolean drawGlobalFill = style != WaypointerConfig.BoxStyle.OUTLINED;
        boolean drawFill  = drawGlobalFill || hasFilledSubwaypoint(groups);
        WaypointPaint defaultPaint = config.waypointPainterDefaultPaint();
        boolean drawPaint = hasPaintedGroup(groups, defaultPaint);
        boolean drawBeams = config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF;
        boolean drawTexturedBeams = drawBeams && config.useBeaconBeamTextures();
        boolean drawFlatBeams = drawBeams && !drawTexturedBeams;
        boolean drawRouteLines = config.showRouteLines()
                || dungeonConfig != null && dungeonConfig.showDungeonRouteLines();
        boolean drawPearlTrajectories = dungeonConfig != null
                && dungeonConfig.showPearlTrajectories();
        boolean drawDungeonEntryPaths = config.showDungeonEntryPathToFirstWaypoint();
        if (!drawLines && !drawFill && !drawPaint && !drawBeams && !drawRouteLines
                && !drawPearlTrajectories && !drawDungeonEntryPaths) return;
        if (config.beaconOpacity() <= 0.0 && !drawRouteLines
                && !drawPearlTrajectories && !drawDungeonEntryPaths) return;
        boolean hasDepthCheckedWaypoints = hasDepthCheckedWaypoint(groups);
        boolean hasThroughWallWaypoints = hasThroughWallWaypoint(groups) || drawDungeonEntryPaths;
        if (!hasDepthCheckedWaypoints && !hasThroughWallWaypoints) return;

        PoseStack ps = ctx.poseStack();
        if (ps == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = MinecraftCompat.mainCamera(renderer);
        if (!camera.isInitialized()) return;
        Vec3 camPos = camera.position();
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        if (hasDepthCheckedWaypoints) {
            labelProjector.prepare(renderer, camera);
        }
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();

        ps.pushPose();
        ps.translate(-camPos.x, -camPos.y, -camPos.z);

        // Fills and lines MUST run as two separate getBuffer/endBatch cycles.
        // MultiBufferSource.BufferSource routes every non-fixed RenderType
        // through a single shared BufferBuilder, so calling getBuffer(quads)
        // while still holding a lines VertexConsumer silently endBatches the
        // lines builder -- and the next addVertex on the stale reference
        // throws "Not building!" (crash seen on FILLED_OUTLINED in 1.2.0).
        //
        // We intentionally flush fills before starting the line batch so the
        // outline renders on top of its translucent fill in FILLED_OUTLINED
        // mode and stays crisp.
        if (drawTexturedBeams && hasThroughWallWaypoints) {
            RenderType beamType = WaypointerRenderPipelines.beaconBeamThroughWalls();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            RenderSubmission.submit(ctx, ps, beamType, (texturedBeams, submittedPose) -> {
                for (WaypointGroup g : groups) {
                    emitBeaconBeams(submittedPose, texturedBeams, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                            false, mc, level, screenW, screenH, true);
                }
            });
        }
        if (drawTexturedBeams && hasDepthCheckedWaypoints) {
            RenderType beamType = WaypointerRenderPipelines.beaconBeamDepthTested();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            RenderSubmission.submit(ctx, ps, beamType, (texturedBeams, submittedPose) -> {
                for (WaypointGroup g : groups) {
                    emitBeaconBeams(submittedPose, texturedBeams, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                            true, mc, level, screenW, screenH, true);
                }
            });
        }
        if (drawPaint && hasThroughWallWaypoints) {
            for (WaypointGroup g : groups) {
                WaypointPaint paint = effectivePaint(g, defaultPaint);
                if (paint == null) continue;
                WaypointPaintTextureCache.Entry paintTexture =
                        WaypointPaintTextureCache.get(paint);
                RenderSubmission.submit(ctx, ps, paintTexture.throughWalls(),
                        (quads, submittedPose) -> emitPaintedBoxes(
                                submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                false, mc, screenW, screenH));
            }
        }
        if (drawPaint && hasDepthCheckedWaypoints) {
            for (WaypointGroup g : groups) {
                WaypointPaint paint = effectivePaint(g, defaultPaint);
                if (paint == null) continue;
                WaypointPaintTextureCache.Entry paintTexture =
                        WaypointPaintTextureCache.get(paint);
                RenderSubmission.submit(ctx, ps, paintTexture.depthTested(),
                        (quads, submittedPose) -> emitPaintedBoxes(
                                submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc, screenW, screenH));
            }
        }
        if ((drawFlatBeams || drawFill) && hasThroughWallWaypoints) {
            RenderType quadType = WaypointerRenderPipelines.quadsThroughWalls();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            RenderSubmission.submit(ctx, ps, quadType, (quads, submittedPose) -> {
                if (drawFlatBeams) {
                    for (WaypointGroup g : groups) {
                        emitBeaconBeams(submittedPose, quads, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                                false, mc, level, screenW, screenH, false);
                    }
                }
                if (drawFill) {
                    for (WaypointGroup g : groups) {
                        if (effectivePaint(g, defaultPaint) != null) continue;
                        emitFilledBoxes(submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq, drawGlobalFill,
                                false, mc, screenW, screenH);
                    }
                }
            });
        }
        if ((drawFlatBeams || drawFill) && hasDepthCheckedWaypoints) {
            RenderType quadType = WaypointerRenderPipelines.quadsDepthTested();
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            RenderSubmission.submit(ctx, ps, quadType, (quads, submittedPose) -> {
                if (drawFlatBeams) {
                    for (WaypointGroup g : groups) {
                        emitBeaconBeams(submittedPose, quads, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq, minY, maxY,
                                true, mc, level, screenW, screenH, false);
                    }
                }
                if (drawFill) {
                    for (WaypointGroup g : groups) {
                        if (effectivePaint(g, defaultPaint) != null) continue;
                        emitFilledBoxes(submittedPose, quads, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq, drawGlobalFill,
                                true, mc, screenW, screenH);
                    }
                }
            });
        }
        if ((drawLines || drawRouteLines || drawPearlTrajectories || drawDungeonEntryPaths)
                && hasThroughWallWaypoints) {
            RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
            List<DungeonEntryPathSubmission> dungeonEntryPaths = drawDungeonEntryPaths
                    ? prepareDungeonEntryPaths(groups, playerPos, level)
                    : List.of();
            boolean submitted = RenderSubmission.submit(ctx, ps, lineType, (lines, submittedPose) -> {
                if (drawDungeonEntryPaths) {
                    emitDungeonEntryPaths(submittedPose, lines, dungeonEntryPaths);
                }
                if (drawPearlTrajectories) {
                    for (WaypointGroup group : groups) {
                        emitPearlTrajectory(submittedPose, lines, group);
                    }
                }
                if (drawRouteLines) {
                    for (WaypointGroup g : groups) {
                        if (!routeLinesEnabled(g, config, dungeonConfig)) continue;
                        emitRouteLines(submittedPose, lines, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                false, mc, level, screenW, screenH);
                    }
                }
                if (drawLines) {
                    for (WaypointGroup g : groups) {
                        emitLineBoxes(submittedPose, lines, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                false, mc, screenW, screenH);
                    }
                }
            });
            for (DungeonEntryPathSubmission path : dungeonEntryPaths) {
                RenderDiagnostics.recordDungeonPathSubmission(
                        path.group(), submitted && isDrawableDungeonEntryPath(path.points()));
            }
        }
        if ((drawLines || drawRouteLines) && hasDepthCheckedWaypoints) {
            RenderType lineType = WaypointerRenderPipelines.linesDepthTested();
            RenderSubmission.submit(ctx, ps, lineType, (lines, submittedPose) -> {
                if (drawRouteLines) {
                    for (WaypointGroup g : groups) {
                        if (!routeLinesEnabled(g, config, dungeonConfig)) continue;
                        emitRouteLines(submittedPose, lines, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc, level, screenW, screenH);
                    }
                }
                if (drawLines) {
                    for (WaypointGroup g : groups) {
                        emitLineBoxes(submittedPose, lines, level, g, camPos, playerPos,
                                maxStaticDistanceSq, nearHideDistanceSq,
                                true, mc, screenW, screenH);
                    }
                }
            });
        }

        ps.popPose();
    }

    private void emitDungeonEntryPaths(PoseStack ps, VertexConsumer lines,
                                       Iterable<DungeonEntryPathSubmission> paths) {
        float alpha = DUNGEON_ENTRY_PATH_ALPHA;
        float width = effectiveOutlineThickness();
        int color = config.dungeonEntryPathColor();
        for (DungeonEntryPathSubmission path : paths) {
            List<Vec3> points = path.points();
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1);
                Vec3 b = points.get(i);
                RenderHelpers.emitLine(lines, ps,
                        (float) a.x, (float) a.y, (float) a.z,
                        (float) b.x, (float) b.y, (float) b.z,
                        color, alpha, width);
            }
        }
    }

    private List<DungeonEntryPathSubmission> prepareDungeonEntryPaths(
            Iterable<WaypointGroup> groups, Vec3 playerPos, ClientLevel level) {
        if (playerPos == null || level == null) return List.of();

        List<DungeonEntryPathSubmission> paths = new ArrayList<>();
        for (WaypointGroup group : groups) {
            if (!shouldRenderDungeonEntryPath(group,
                    config.showDungeonEntryPathToFollowingWaypoints())) continue;

            Waypoint target = group.current();
            if (target == null) continue;
            DungeonEntryPathLookup lookup = dungeonEntryPathPoints(level, playerPos, target);
            RenderDiagnostics.recordPathLookup(
                    group, lookup.result(), lookup.cacheHit(), lookup.cacheAgeNanos());
            paths.add(new DungeonEntryPathSubmission(group, lookup.result().points()));
        }
        return paths;
    }

    private DungeonEntryPathLookup dungeonEntryPathPoints(ClientLevel level, Vec3 playerPos,
                                                          Waypoint target) {
        BlockPos start = GroundPathfinder.floorPos(playerPos);
        DungeonEntryPathTarget targetKey = DungeonEntryPathTarget.from(target);
        long now = System.nanoTime();
        if (level != dungeonEntryPathLevel) {
            dungeonEntryPathLevel = level;
            dungeonEntryPathCache.clear();
        }

        DungeonEntryPath cached = dungeonEntryPathCache.get(targetKey);
        if (cached != null
                && shouldReuseDungeonEntryPath(cached.start(), cached.computedAtNanos(), start, now)) {
            GroundPathfinder.moveLineStart(cached.result().points(), playerPos);
            return new DungeonEntryPathLookup(
                    cached.result(), true, Math.max(0L, now - cached.computedAtNanos()));
        }

        long startedAtNanos = System.nanoTime();
        GroundPathfinder.PathResult result;
        try {
            result = GroundPathfinder.findPathResult(
                    level,
                    playerPos,
                    target,
                    GroundPathfinder.NO_DISTANCE_LIMIT,
                    DUNGEON_ENTRY_MAX_EXPANSIONS,
                    DUNGEON_ENTRY_SEARCH_PADDING,
                    DUNGEON_ENTRY_SEARCH_PADDING);
        } catch (RuntimeException error) {
            Waypointer.LOGGER.warn("Dungeon entry path calculation failed; using tracer fallback", error);
            result = new GroundPathfinder.PathResult(List.of(), new GroundPathfinder.Diagnostics(
                    start,
                    GroundPathfinder.targetBlock(target),
                    null,
                    null,
                    GroundPathfinder.FailureReason.CALCULATION_FAILED,
                    0,
                    DUNGEON_ENTRY_MAX_EXPANSIONS,
                    Math.max(0L, System.nanoTime() - startedAtNanos)));
        }
        dungeonEntryPathCache.put(targetKey, new DungeonEntryPath(start, result, now));
        return new DungeonEntryPathLookup(result, false, 0L);
    }

    static boolean shouldReuseDungeonEntryPath(BlockPos cachedStart, long computedAtNanos,
                                               BlockPos start, long nowNanos) {
        return Objects.equals(start, cachedStart)
                && nowNanos - computedAtNanos < DUNGEON_ENTRY_REPATH_INTERVAL_NANOS;
    }

    private record DungeonEntryPathTarget(BlockPos block, double centerX, double centerY, double centerZ) {
        static DungeonEntryPathTarget from(Waypoint target) {
            return new DungeonEntryPathTarget(
                    GroundPathfinder.targetBlock(target),
                    target.centerX(),
                    target.centerY(),
                    target.centerZ());
        }
    }

    static boolean isDrawableDungeonEntryPath(List<Vec3> points) {
        return points != null && points.size() >= 2;
    }

    private record DungeonEntryPath(BlockPos start, GroundPathfinder.PathResult result,
                                    long computedAtNanos) {
    }

    private record DungeonEntryPathLookup(GroundPathfinder.PathResult result,
                                          boolean cacheHit, long cacheAgeNanos) {
    }

    private record DungeonEntryPathSubmission(WaypointGroup group, List<Vec3> points) {
    }

    static boolean shouldRenderDungeonEntryPath(WaypointGroup group, boolean includeFollowingWaypoints) {
        int currentIndex = group == null ? -1 : group.currentIndex();
        return group != null
                && !group.temp()
                && !group.isComplete()
                && (currentIndex == 0 || includeFollowingWaypoints)
                && group.size() > 0
                && !group.isSubwaypoint(currentIndex)
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    private void emitPearlTrajectory(PoseStack ps, VertexConsumer lines, WaypointGroup group) {
        if (!isDungeonRoomRoute(group) || group.isComplete()) return;
        int launchIndex = group.currentIndex();
        int targetIndex = launchIndex + 1;
        if (launchIndex < 0 || targetIndex >= group.size()) return;
        Waypoint launch = group.get(launchIndex);
        Waypoint target = group.get(targetIndex);
        if (!launch.hasFlag(Waypoint.FLAG_DUNGEON_PEARL)
                || !target.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)) {
            return;
        }

        Vec3 start = new Vec3(launch.centerX(), launch.y() + 1.62, launch.centerZ());
        Vec3 end = new Vec3(target.centerX(), target.y() + 0.2, target.centerZ());
        List<Vec3> points = DungeonPearlTrajectory.points(start, end);
        float width = effectiveOutlineThickness();
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            RenderHelpers.emitLine(lines, ps,
                    (float) a.x, (float) a.y, (float) a.z,
                    (float) b.x, (float) b.y, (float) b.z,
                    launch.color(), 0.9f, width);
        }
    }

    static boolean routeLinesEnabled(WaypointGroup group, WaypointerConfig config,
                                     DungeonConfig dungeonConfig) {
        if (isDungeonRoomRoute(group) && dungeonConfig != null) {
            return dungeonConfig.showDungeonRouteLines();
        }
        return config != null && config.showRouteLines();
    }

    private static boolean isDungeonRoomRoute(WaypointGroup group) {
        return group != null
                && !group.temp()
                && DungeonRoomData.definition(group.zoneId()) != null;
    }

    private void emitRouteLines(PoseStack ps, VertexConsumer lines, WaypointGroup g,
                                Vec3 camPos, Vec3 playerPos,
                                double maxStaticDistanceSq, double nearHideDistanceSq,
                                boolean depthCheckedPass, Minecraft mc, ClientLevel level,
                                int screenW, int screenH) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float alpha = 0.85f;
        float width = effectiveOutlineThickness();
        int color = config.routeLineColor();

        RouteLineSegmentConsumer emitSegment = (fromIndex, toIndex) -> {
            Waypoint a = g.get(fromIndex);
            Waypoint b = g.get(toIndex);
            if (!routeSegmentHasDepthVisibility(a, b, depthCheckedPass, mc, level)) return;
            RenderHelpers.emitLine(lines, ps,
                    (float) a.centerX(), (float) a.centerY(), (float) a.centerZ(),
                    (float) b.centerX(), (float) b.centerY(), (float) b.centerZ(),
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
                config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> shouldRenderRouteLineEndpoint(g, i, currentIdx, showCompleted,
                        camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq),
                emitSegment);
    }

    static void forEachRouteLineSegment(WaypointGroup group,
                                        boolean depthCheckedPass,
                                        IntPredicate endpointVisible,
                                        RouteLineSegmentConsumer consumer) {
        forEachRouteLineSegment(group, depthCheckedPass, false, endpointVisible, consumer);
    }

    private static void forEachRouteLineSegment(WaypointGroup group,
                                                boolean depthCheckedPass,
                                                boolean keepSubwaypointsVisibleUntilNextWaypoint,
                                                IntPredicate endpointVisible,
                                                RouteLineSegmentConsumer consumer) {
        int[] previous = { -1 };
        group.forEachVisibleIndex(keepSubwaypointsVisibleUntilNextWaypoint, i -> {
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
                    && group.get(previous).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)) {
                previous--;
            }
            return previous;
        }

        int previous = current - 1;
        while (previous >= 0
                && group.get(previous).hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET)) {
            previous--;
        }
        if (previous >= 0 && group.isSubwaypoint(previous)) return previous;
        int activeParent = group.activeSubwaypointParentIndex();
        return activeParent >= 0 ? activeParent : group.previousMainIndexBefore(current);
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

    private boolean shouldRenderWaypointWorld(WaypointGroup group, int index, int currentIdx,
                                              boolean showCompleted, Vec3 camPos,
                                              Vec3 playerPos, double maxStaticDistanceSq,
                                              double nearHideDistanceSq,
                                              boolean depthCheckedPass, Minecraft mc,
                                              ClientLevel level, int screenW, int screenH) {
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
        return hasLineOfSightToWaypointBox(mc, level, waypoint);
    }

    private static boolean shouldRenderProjectedDepthCheckedWaypoint(Minecraft mc,
                                                                    ClientLevel level,
                                                                    Waypoint waypoint,
                                                                    double sx,
                                                                    double sy,
                                                                    int screenW,
                                                                    int screenH) {
        if (mc == null || level == null || waypoint == null) return false;
        if (!isOnScreen(sx, sy, screenW, screenH)) return false;
        return hasLineOfSightToWaypointBox(mc, level, waypoint);
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

    private void emitLineBoxes(PoseStack ps, VertexConsumer lines, ClientLevel level, WaypointGroup g,
                               Vec3 camPos, Vec3 playerPos,
                               double maxStaticDistanceSq, double nearHideDistanceSq,
                               boolean depthCheckedPass, Minecraft mc, int screenW, int screenH) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();
        float outlineThickness = effectiveOutlineThickness();

        g.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level, screenW, screenH)) {
                return;
            }

            Waypoint w = g.get(i);
            State state = stateFor(g, i, currentIdx);
            float alpha = alphaFor(g, state) * beaconOpacity;
            populateWaypointBoxBounds(level, w, waypointBoxBoundsScratch);
            float x1 = (float) waypointBoxBoundsScratch[BOX_MIN_X];
            float y1 = (float) waypointBoxBoundsScratch[BOX_MIN_Y];
            float z1 = (float) waypointBoxBoundsScratch[BOX_MIN_Z];
            float x2 = (float) waypointBoxBoundsScratch[BOX_MAX_X];
            float y2 = (float) waypointBoxBoundsScratch[BOX_MAX_Y];
            float z2 = (float) waypointBoxBoundsScratch[BOX_MAX_Z];
            RenderHelpers.emitLineBox(lines, ps, x1, y1, z1, x2, y2, z2,
                    w.color(), alpha, outlineThickness);
        });
    }

    private void emitFilledBoxes(PoseStack ps, VertexConsumer quads, ClientLevel level, WaypointGroup g,
                                 Vec3 camPos, Vec3 playerPos,
                                 double maxStaticDistanceSq, double nearHideDistanceSq,
                                 boolean fillAllWaypoints, boolean depthCheckedPass,
                                 Minecraft mc, int screenW, int screenH) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        g.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level, screenW, screenH)) {
                return;
            }

            Waypoint w = g.get(i);
            if (!fillAllWaypoints && !isFilledSubwaypoint(w)) return;

            State state = stateFor(g, i, currentIdx);
            float alpha = alphaFor(g, state) * beaconOpacity;
            populateWaypointBoxBounds(level, w, waypointBoxBoundsScratch);
            float x1 = (float) waypointBoxBoundsScratch[BOX_MIN_X];
            float y1 = (float) waypointBoxBoundsScratch[BOX_MIN_Y];
            float z1 = (float) waypointBoxBoundsScratch[BOX_MIN_Z];
            float x2 = (float) waypointBoxBoundsScratch[BOX_MAX_X];
            float y2 = (float) waypointBoxBoundsScratch[BOX_MAX_Y];
            float z2 = (float) waypointBoxBoundsScratch[BOX_MAX_Z];
            RenderHelpers.emitFilledBox(quads, ps, x1, y1, z1, x2, y2, z2,
                    w.color(), alpha * FILLED_ALPHA_SCALE);
        });
    }

    private void emitPaintedBoxes(PoseStack ps, VertexConsumer quads,
                                  ClientLevel level, WaypointGroup group,
                                  Vec3 camPos, Vec3 playerPos,
                                  double maxStaticDistanceSq, double nearHideDistanceSq,
                                  boolean depthCheckedPass, Minecraft mc,
                                  int screenW, int screenH) {
        int currentIndex = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        group.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(), i -> {
            if (!shouldRenderWaypointWorld(group, i, currentIndex, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthCheckedPass, mc, level, screenW, screenH)) {
                return;
            }

            Waypoint waypoint = group.get(i);
            State state = stateFor(group, i, currentIndex);
            float alpha = alphaFor(group, state) * beaconOpacity * PAINTED_ALPHA_SCALE;
            populateWaypointBoxBounds(level, waypoint, waypointBoxBoundsScratch);
            RenderHelpers.emitTexturedBox(quads, ps,
                    (float) waypointBoxBoundsScratch[BOX_MIN_X],
                    (float) waypointBoxBoundsScratch[BOX_MIN_Y],
                    (float) waypointBoxBoundsScratch[BOX_MIN_Z],
                    (float) waypointBoxBoundsScratch[BOX_MAX_X],
                    (float) waypointBoxBoundsScratch[BOX_MAX_Y],
                    (float) waypointBoxBoundsScratch[BOX_MAX_Z],
                    alpha, camPos.x, camPos.y, camPos.z);
        });
    }

    private void emitBeaconBeams(PoseStack ps, VertexConsumer quads, WaypointGroup g,
                                 Vec3 camPos, Vec3 playerPos,
                                 double maxStaticDistanceSq, double nearHideDistanceSq,
                                 int minY, int maxY, boolean depthCheckedPass,
                                 Minecraft mc, ClientLevel level, int screenW, int screenH,
                                 boolean texturedBeams) {
        WaypointerConfig.BeaconBeamMode mode = config.beaconBeamMode();
        if (mode == WaypointerConfig.BeaconBeamMode.OFF || g.isEmpty()) return;

        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();

        if (mode == WaypointerConfig.BeaconBeamMode.CURRENT) {
            int beamIndex = currentBeamIndex(g);
            emitBeaconBeamIfVisible(ps, quads, g, beamIndex, currentIdx,
                    showCompleted, camPos, playerPos, maxStaticDistanceSq,
                    nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level,
                    screenW, screenH, texturedBeams);
            return;
        }

        g.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> emitBeaconBeamIfVisible(ps, quads, g, i,
                        currentIdx, showCompleted, camPos, playerPos, maxStaticDistanceSq,
                        nearHideDistanceSq, minY, maxY, depthCheckedPass, mc, level,
                        screenW, screenH, texturedBeams));
    }

    private void emitBeaconBeamIfVisible(PoseStack ps, VertexConsumer quads,
                                         WaypointGroup g, int i, int currentIdx,
                                         boolean showCompleted, Vec3 camPos,
                                          Vec3 playerPos, double maxStaticDistanceSq,
                                          double nearHideDistanceSq, int minY, int maxY,
                                          boolean depthCheckedPass, Minecraft mc,
                                          ClientLevel level, int screenW, int screenH,
                                          boolean texturedBeams) {
        if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                depthCheckedPass, mc, level, screenW, screenH)) {
            return;
        }

        Waypoint w = g.get(i);
        State state = stateFor(g, i, currentIdx);
        float alpha = alphaFor(g, state) * (float) config.beaconOpacity();
        if (!texturedBeams) {
            alpha *= BEAM_ALPHA_SCALE;
        }
        if (alpha <= 0.0f) return;

        float y1 = config.beaconBeamExtendsBelowWaypoint() ? minY : w.y();
        float y2 = Math.max(y1 + 1.0f, maxY);
        if (texturedBeams) {
            emitTexturedBeaconBeam(quads, ps, w, y1, y2, alpha, mc, camPos);
        } else {
            RenderHelpers.emitVerticalColumn(quads, ps,
                    (float) w.centerX(), y1, (float) w.centerZ(),
                    y2, BEAM_HALF_WIDTH, w.color(), alpha);
        }
    }

    private void emitTexturedBeaconBeam(VertexConsumer consumer, PoseStack ps,
                                        Waypoint waypoint, float y1, float y2,
                                        float alpha, Minecraft mc, Vec3 camPos) {
        float height = y2 - y1;
        if (height <= 0.0f) return;

        float animationTime = mc.level == null
                ? 0.0f
                : (float) Math.floorMod(mc.level.getGameTime(), 40L);
        updateBeamRotation(animationTime);
        float radiusScale = beaconTextureRadiusScale(waypoint, camPos);
        int coreColor = RenderHelpers.withAlpha(0xFF000000 | (waypoint.color() & 0xFFFFFF), alpha);
        int glowColor = RenderHelpers.withAlpha(BEACON_GLOW_BASE_ALPHA_ARGB | (waypoint.color() & 0xFFFFFF), alpha);

        PoseStack.Pose pose = ps.last();
        float cx = (float) waypoint.centerX();
        float cz = (float) waypoint.centerZ();

        // Rotated core diamond: unrotated corners (0,r),(r,0),(-r,0),(0,-r)
        // mapped through the shared per-tick rotation.
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

    /**
     * The beam core's spin angle depends only on the game tick, so the
     * rotation is shared by every beam in the frame. Computing it once here
     * replaces the previous per-waypoint PoseStack push + quaternion, which
     * dense routes paid thousands of times per frame.
     */
    private void updateBeamRotation(float animationTime) {
        if (animationTime == beamRotationAnimationTime) return;
        beamRotationAnimationTime = animationTime;
        double radians = Math.toRadians(animationTime * 2.25f - 45.0f);
        beamRotationCos = (float) Math.cos(radians);
        beamRotationSin = (float) Math.sin(radians);
    }

    private static float beaconTextureRadiusScale(Waypoint waypoint, Vec3 camPos) {
        if (camPos == null) return 1.0f;
        double dx = waypoint.centerX() - camPos.x;
        double dz = waypoint.centerZ() - camPos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.max(1.0, horizontalDistance / BEACON_TEXTURE_SCALE_THRESHOLD);
    }

    /**
     * Emit one beam part (core or glow) as four vertical quads. Corner offsets
     * arrive pre-rotated and relative to {@code (cx, cz)}; coordinates are
     * world-space so no per-waypoint pose transform is needed.
     */
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

    // ---- HUD path: 2D labels projected from world anchors --------------------------------

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
        labelCandidateCount = 0;

        if (drawIrisHudBoxes && config.beaconOpacity() > 0.0) {
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

    static WaypointerConfig.BoxStyle hudFallbackBoxStyle(WaypointerConfig.BoxStyle style) {
        return style == WaypointerConfig.BoxStyle.FILLED
                ? WaypointerConfig.BoxStyle.OUTLINED
                : style;
    }

    static boolean worldBoxOutlinesEnabled(WaypointerConfig.BoxStyle style,
                                           boolean irisHudFallbackActive) {
        return style != WaypointerConfig.BoxStyle.FILLED && !irisHudFallbackActive;
    }

    private void drawIrisHudGroupBoxes(GuiGraphicsExtractor g, Minecraft mc, ClientLevel level,
                                       Vec3 camPos, Vec3 playerPos, int screenW, int screenH,
                                       WaypointGroup group, double maxStaticDistanceSq,
                                       double nearHideDistanceSq) {
        int currentIdx = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        group.forEachVisibleIndex(config.keepSubwaypointsVisibleUntilNextWaypoint(),
                i -> {
            Waypoint waypoint = group.get(i);
            boolean depthChecked = waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);
            if (!shouldRenderWaypointWorld(group, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq,
                    depthChecked, mc, level, screenW, screenH)) {
                return;
            }
            State state = stateFor(group, i, currentIdx);
            float alpha = alphaFor(group, state) * beaconOpacity;
            int argb = RenderHelpers.withAlpha(0xFF000000 | (waypoint.color() & 0xFFFFFF), alpha);
            if (!projectBoxCorners(level, waypoint, screenW, screenH)) return;

            double outlineThickness = config.waypointOutlineThickness();
            for (int edge = 0; edge < BOX_EDGE_A.length; edge++) {
                int a = BOX_EDGE_A[edge];
                int b = BOX_EDGE_B[edge];
                if (!boxCornerVisible[a] || !boxCornerVisible[b]) continue;
                drawConfiguredScreenLine(g,
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

        populateWaypointBoxBounds(level, waypoint, waypointBoxBoundsScratch);
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
        // Hoist out of the per-waypoint lambda so a long route doesn't pay
        // a getter call (and a dirty-flag-eligible call site) per label.
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
            if (shouldHideCompletedSequenceWaypoint(group, i, currentIdx, state, showCompleted, w)) return;
            if (w.hasFlag(Waypoint.FLAG_HIDE_NAME)) return;

            double ax = w.centerX();
            double ay = w.centerY() - 0.5 + labelLift;
            double az = w.centerZ();
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

    /**
     * Keep the nearest {@code count} labels in slots [0, count) without sorting
     * the full candidate list. Dense static overlays can produce thousands of
     * candidates per frame while the user only wants, say, the nearest 50 labels.
     */
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

    /**
     * Format a distance as {@code "<n>m"} without allocating for the common case.
     * 0..4095m hits the pre-baked table; beyond that we reuse a single
     * {@link StringBuilder} instead of {@code (distance + "m")} which would
     * create a throwaway {@code StringBuilder} + {@code String} per label per
     * frame. Acceptable because this renderer runs strictly on the render
     * thread.
     */
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
        if (depth <= 0.0 || !Double.isFinite(depth)) {
            return (float) clampLabelScale(baseScale * LABEL_SCALE_MIN);
        }

        double currentFov = Math.max(1.0, Math.min(179.0, fovDegrees));
        double fovScale = Math.tan(Math.toRadians(LABEL_SCALE_BASELINE_FOV_DEGREES) * 0.5)
                / Math.tan(Math.toRadians(currentFov) * 0.5);
        double scale = fovScale * LABEL_SCALE_REFERENCE_DEPTH / depth;
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
     * Draw a line of text horizontally centered on {@code (cx, top)} with a
     * translucent backdrop sized to the glyph run. Kept inlined here (rather than
     * in RenderHelpers) because the padding/backdrop decisions are label-specific.
     *
     * <p>The projected anchor stays fractional until draw time. Sprinting animates
     * FOV, which moves labels by sub-pixel amounts; rounding the projection before
     * drawing made that smooth FOV change look like 1px snaps.
     *
     * <p>Computes {@code font.width(text)} once and threads it through: the
     * backdrop, the half-width, and the {@code drawString} call all reused the
     * same value, saving two redundant glyph-table lookups per label.
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
        // Plain names return the same instance from translate(); skip the map.
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

    private static State stateFor(WaypointGroup group, int i, int currentIdx) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) return State.CURRENT;
        int activeSubwayParent = group.activeSubwaypointParentIndex();
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

    private boolean shouldHideCompletedSequenceWaypoint(WaypointGroup group,
                                                        int index,
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

    private boolean shouldHideStaticReached(WaypointGroup group, int index) {
        return config.hideReachedStaticWaypointsUntilCycleComplete()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && group.isStaticWaypointReached(index);
    }

    private double nearHideDistanceSq() {
        return config.hideWaypointsNearPlayer()
                ? WaypointVisibility.squaredRadius(config.hideWaypointsNearRadius())
                : 0.0;
    }

        private double labelNearHideDistanceSq() {
        return config.hideWaypointLabelsNearPlayer()
                ? WaypointVisibility.squaredRadius(config.hideWaypointLabelsNearRadius())
                : 0.0;
    }

    private static boolean shouldHideNearPlayer(Waypoint waypoint, Vec3 playerPos,
                                                double nearHideDistanceSq) {
        return playerPos != null
                && WaypointVisibility.isHiddenNearPlayer(
                        waypoint, playerPos.x, playerPos.y, playerPos.z, nearHideDistanceSq);
    }

    private static boolean isNearScreen(double sx, double sy, int screenW, int screenH) {
        return sx >= -SCREEN_CULL_MARGIN
                && sx <= screenW + SCREEN_CULL_MARGIN
                && sy >= -SCREEN_CULL_MARGIN
                && sy <= screenH + SCREEN_CULL_MARGIN;
    }

    private static double squaredDistanceLimit(double distance) {
        return distance <= 0.0 ? 0.0 : distance * distance;
    }

    private static boolean isStaticBeyondDistanceLimit(WaypointGroup group, Waypoint waypoint,
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

    private static boolean isStaticBeyondDistanceLimit(WaypointGroup group,
                                                       double distanceSq,
                                                       double maxStaticDistanceSq) {
        return maxStaticDistanceSq > 0.0
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && distanceSq > maxStaticDistanceSq;
    }

    private float alphaFor(WaypointGroup group, State state) {
        if (config.dimSequenceContextWaypoints()
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && state != State.CURRENT) {
            return Math.min(state.alpha, SEQUENCE_CONTEXT_ALPHA);
        }
        return state.alpha;
    }

    static void drawScreenLine(GuiGraphicsExtractor g, double x1, double y1,
                               double x2, double y2, int argb, double thickness) {
        drawFastScreenLine(g, x1, y1, x2, y2, argb,
                crispHudLineThickness(thickness));
    }

    private void drawConfiguredScreenLine(GuiGraphicsExtractor g, double x1, double y1,
                                          double x2, double y2, int argb,
                                          double thickness) {
        drawFastScreenLine(g, x1, y1, x2, y2, argb,
                crispHudLineThickness(thickness));
    }

    private float effectiveOutlineThickness() {
        return (float) config.waypointOutlineThickness();
    }

    static double crispHudLineThickness(double thickness) {
        return Math.max(1.0, Math.floor(thickness));
    }

    private static void drawFastScreenLine(GuiGraphicsExtractor g, double x1, double y1,
                                           double x2, double y2, int argb,
                                           double thickness) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double scaledThickness = Math.max(1.0 / guiScale, thickness / guiScale);
        double margin = HUD_LINE_CULL_MARGIN / guiScale;
        double minX = -margin;
        double minY = -margin;
        double maxX = g.guiWidth() + margin;
        double maxY = g.guiHeight() + margin;
        int out1 = outCode(x1, y1, minX, minY, maxX, maxY);
        int out2 = outCode(x2, y2, minX, minY, maxX, maxY);
        while ((out1 | out2) != 0) {
            if ((out1 & out2) != 0) return;

            int outside = out1 != 0 ? out1 : out2;
            double x;
            double y;
            if ((outside & 8) != 0) {
                x = x1 + (x2 - x1) * (maxY - y1) / (y2 - y1);
                y = maxY;
            } else if ((outside & 4) != 0) {
                x = x1 + (x2 - x1) * (minY - y1) / (y2 - y1);
                y = minY;
            } else if ((outside & 2) != 0) {
                y = y1 + (y2 - y1) * (maxX - x1) / (x2 - x1);
                x = maxX;
            } else {
                y = y1 + (y2 - y1) * (minX - x1) / (x2 - x1);
                x = minX;
            }

            if (outside == out1) {
                x1 = x;
                y1 = y;
                out1 = outCode(x1, y1, minX, minY, maxX, maxY);
            } else {
                x2 = x;
                y2 = y;
                out2 = outCode(x2, y2, minX, minY, maxX, maxY);
            }
        }

        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5) return;

        int samples = screenLineSampleCount(dx, dy);
        int radius = Math.max(0, (int) Math.floor(scaledThickness * 0.5));
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            if (x == lastX && y == lastY) continue;
            g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, argb);
            lastX = x;
            lastY = y;
        }
    }

    static int screenLineSampleCount(double dx, double dy) {
        return Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
    }

    private static int outCode(double x, double y, double minX, double minY,
                               double maxX, double maxY) {
        int code = 0;
        if (x < minX) code |= 1;
        else if (x > maxX) code |= 2;
        if (y < minY) code |= 4;
        else if (y > maxY) code |= 8;
        return code;
    }

    private enum State {
        COMPLETED(0.25f),
        CURRENT(1.0f),
        UPCOMING(0.65f);

        final float alpha;
        State(float a) { this.alpha = a; }
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
