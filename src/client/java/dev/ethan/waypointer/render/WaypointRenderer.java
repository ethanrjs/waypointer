package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.RouteProgress;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.WaypointVisibility;
import dev.ethan.waypointer.text.AmpersandFormatting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;

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
    private static final double SMALL_SUBWAYPOINT_INSET = (1.0 - SMALL_SUBWAYPOINT_SIZE) * 0.5;

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
    private static final Comparator<LabelCandidate> LABEL_NEAREST_FIRST =
            Comparator.comparingDouble(c -> c.distanceSquared);

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    /**
     * Reusable scratch buffer for the fallback distance formatter. Safe because
     * {@link #render} only runs on the client/render thread; never escape this
     * reference from a render frame.
     */
    private final StringBuilder distanceScratch = new StringBuilder(8);
    private final String[] indexLabelCache = new String[INDEX_LABEL_CACHE_MAX];
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
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        WorldRenderEvents.END_MAIN.register(this::onWorldRender);
        // Attaching before CHAT inherits chat's render condition, which means the
        // labels respect the "hide GUI" (F1) toggle the same way chat does. That
        // matches player expectation for any in-world HUD overlay.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, LABEL_HUD_ID, this);
    }

    // ---- world-space path: cube outlines -------------------------------------------------

    /**
     * Max alpha for the filled faces of a waypoint cube. The line outline is
     * drawn at the state's full alpha, but if we fill at the same alpha the
     * cube becomes an opaque block that obscures the world behind it. 35% was
     * picked by eye: dense enough to read as a coloured volume against bright
     * biomes, translucent enough that you can still see through it.
     */
    private static final float FILLED_ALPHA_SCALE = 0.35f;
    private static final float BEAM_ALPHA_SCALE = 0.18f;
    private static final float BEAM_HALF_WIDTH = 0.12f;
    private static final int DEFAULT_MIN_BUILD_Y = -64;
    private static final int DEFAULT_MAX_BUILD_Y = 320;

    /*[[AI-FN-DOC
Function:
onWorldRender
Purpose:
Render world-space waypoint geometry, beacon beams, optional route connector lines, and per-subwaypoint fill overrides for active groups.
Why this exists:
Waypoint boxes and connector lines must be drawn in the world render pass so they align with block positions and can use through-walls pipelines.
When to use:
Registered by install as the WorldRenderEvents.END_MAIN callback. Do not call directly from HUD label rendering.
Inputs:
ctx is Fabric's world render context and may contain null matrices or consumers during unusual render states.
Outputs:
No return value. Emits vertices to render buffers when there is visible geometry.
Side effects:
Reads active groups, config, camera/player state, writes to render buffers, and flushes render batches.
Failure modes:
Returns early for Iris HUD fallback, empty groups, absent buffers/matrices, or disabled geometry. Route lines can still draw when waypoint opacity is zero.
Important invariants:
Fills and lines must use separate buffer cycles where required; route connector lines share the line pipeline but remain independent from box outline enablement. A filled subwaypoint can force the fill pass without changing the global box style.
Internal logic:
Gather draw flags and render context, include filled subwaypoint overrides in the fill decision, translate to camera-relative coordinates, emit fill/beam quads when enabled, then emit connector and/or box line geometry when enabled.
Pseudocode:
if Iris fallback active, return
get active groups and draw flags, including per-subwaypoint filled overrides
if nothing to draw, return
get buffers and pose stack
compute camera/player positions and culling thresholds
push pose and translate by negative camera
if beams or fills enabled, emit quads and flush
if connector lines or box lines enabled, emit line geometry and flush
pop pose
Implementation notes:
Route lines are checked separately from beaconOpacity so users can turn box opacity down without accidentally disabling connector topology.
AI self-check:
Verify drawRouteLines participates in the line batch and does not force boxes to render.
]]*/
    private void onWorldRender(WorldRenderContext ctx) {
        if (IrisShaderFallback.shouldUse(config)) return;

        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        WaypointerConfig.BoxStyle style = config.boxStyle();
        boolean drawLines = style != WaypointerConfig.BoxStyle.FILLED;
        boolean drawGlobalFill = style != WaypointerConfig.BoxStyle.OUTLINED;
        boolean drawFill  = drawGlobalFill || hasFilledSubwaypoint(groups);
        boolean drawBeams = config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF;
        boolean drawRouteLines = config.showRouteLines();
        if (!drawLines && !drawFill && !drawBeams && !drawRouteLines) return;
        if (config.beaconOpacity() <= 0.0 && !drawRouteLines) return;

        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return;

        PoseStack ps = ctx.matrices();
        if (ps == null) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
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
        if (drawBeams || drawFill) {
            RenderType quadType = WaypointerRenderPipelines.quadsThroughWalls();
            VertexConsumer quads = buffers.getBuffer(quadType);
            int minY = beamMinY(mc);
            int maxY = beamMaxY(mc);
            if (drawBeams) {
                for (WaypointGroup g : groups) {
                    emitBeaconBeams(ps, quads, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, minY, maxY);
                }
            }
            if (drawFill) {
                for (WaypointGroup g : groups) {
                    emitFilledBoxes(ps, quads, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq, drawGlobalFill);
                }
            }
            RenderHelpers.endBatch(buffers, quadType);
        }
        if (drawLines || drawRouteLines) {
            RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
            VertexConsumer lines = buffers.getBuffer(lineType);
            if (drawRouteLines) {
                for (WaypointGroup g : groups) {
                    emitRouteLines(ps, lines, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq);
                }
            }
            if (drawLines) {
                for (WaypointGroup g : groups) {
                    emitLineBoxes(ps, lines, g, camPos, playerPos,
                            maxStaticDistanceSq, nearHideDistanceSq);
                }
            }
            RenderHelpers.endBatch(buffers, lineType);
        }

        ps.popPose();
    }

    /*[[AI-FN-DOC
Function:
emitRouteLines
Purpose:
Render connector segments between the centers of currently visible waypoints in one group.
Why this exists:
The route topology line is separate from box outlines and tracers: it shows how the visible route context connects without pointing from the player crosshair.
When to use:
Use from the world render pass when config.showRouteLines() is true. Do not use for HUD fallback or crosshair tracer rendering.
Inputs:
ps is the translated pose stack; lines is the active line vertex consumer; g is the waypoint group; camPos and playerPos are current camera/player positions; maxStaticDistanceSq and nearHideDistanceSq are precomputed culling thresholds.
Outputs:
No return value. Appends line vertices to the current render batch.
Side effects:
Writes vertices into the line buffer. Does not mutate route state.
Failure modes:
Empty or one-point visible routes emit nothing. Segments with hidden/cull-filtered endpoints are skipped.
Important invariants:
Connector endpoints must be waypoint centers, use the configured route line color, and respect the same visibility filters as box outlines.
Internal logic:
Iterate visible indices in route order, keep the previous renderable index, and emit a segment from previous center to current center when both endpoints pass visibility filters.
Pseudocode:
currentIdx = group.currentIndex
showCompleted = config.showCompleted
previous = -1
for each visible index:
  if index is not connector-renderable, continue
  if previous exists, draw center-to-center line from previous waypoint to current waypoint
  previous = index
Implementation notes:
Skipping non-renderable endpoints avoids connector lines pointing into markers the player has intentionally hidden through distance, near-hide, completed-hide, or static reached-hide settings.
AI self-check:
Verify this method uses routeLineColor(), existing outline thickness, and the same per-waypoint visibility helpers as boxes.
]]*/
    private void emitRouteLines(PoseStack ps, VertexConsumer lines, WaypointGroup g,
                                Vec3 camPos, Vec3 playerPos,
                                double maxStaticDistanceSq, double nearHideDistanceSq) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float alpha = 0.85f;
        float width = (float) config.waypointOutlineThickness();
        int color = config.routeLineColor();
        int[] previous = { -1 };

        g.forEachVisibleIndex(i -> {
            if (!shouldRenderWaypointWorld(g, i, currentIdx, showCompleted,
                    camPos, playerPos, maxStaticDistanceSq, nearHideDistanceSq)) {
                return;
            }
            if (previous[0] >= 0) {
                Waypoint a = g.get(previous[0]);
                Waypoint b = g.get(i);
                RenderHelpers.emitLine(lines, ps,
                        (float) a.centerX(), (float) a.centerY(), (float) a.centerZ(),
                        (float) b.centerX(), (float) b.centerY(), (float) b.centerZ(),
                        color, alpha, width);
            }
            previous[0] = i;
        });
    }

    /*[[AI-FN-DOC
Function:
shouldRenderWaypointWorld
Purpose:
Decide whether a waypoint endpoint should participate in world-space box or connector rendering.
Why this exists:
Route connector lines need to share the same visibility rules as waypoint boxes so hidden waypoints do not leave stray floating segments behind.
When to use:
Use inside world-space waypoint render helpers before emitting geometry tied to a specific waypoint index. Do not use for label-only near-hide because labels intentionally have separate rules.
Inputs:
group is the route; index is a waypoint list index; currentIdx is the group's current target index; showCompleted is the config flag; camPos/playerPos are current positions; maxStaticDistanceSq and nearHideDistanceSq are culling thresholds.
Outputs:
Returns true when the waypoint should render in the world pass, false when any visibility rule hides it.
Side effects:
None.
Failure modes:
Out-of-range indices return false. Null player positions simply disable near-hide filtering.
Important invariants:
Static reached hiding, player near-hide, static distance culling, and completed sequence hiding must match existing box rendering behavior.
Internal logic:
Validate index, fetch waypoint, evaluate each existing visibility helper, then return the final render decision.
Pseudocode:
if index out of bounds, return false
if static reached hide applies, return false
if near-hide applies, return false
if static distance limit applies, return false
state = stateFor group/index/current
if completed sequence hide applies, return false
return true
Implementation notes:
This helper intentionally does not inspect label-near-hide because route connector lines are world geometry, not HUD labels.
AI self-check:
Confirm emitRouteLines and future world helpers can share this without changing label behavior.
]]*/
    private boolean shouldRenderWaypointWorld(WaypointGroup group, int index, int currentIdx,
                                              boolean showCompleted, Vec3 camPos,
                                              Vec3 playerPos, double maxStaticDistanceSq,
                                              double nearHideDistanceSq) {
        if (index < 0 || index >= group.size()) return false;
        if (shouldHideStaticReached(group, index)) return false;

        Waypoint waypoint = group.get(index);
        if (shouldHideNearPlayer(waypoint, playerPos, nearHideDistanceSq)) return false;
        if (isStaticBeyondDistanceLimit(group, waypoint, camPos, maxStaticDistanceSq)) return false;

        State state = stateFor(group, index, currentIdx);
        return !shouldHideCompletedSequenceWaypoint(group, index, currentIdx, state,
                showCompleted, waypoint);
    }

    /*[[AI-FN-DOC
Function:
hasFilledSubwaypoint
Purpose:
Detect whether any active route contains a subwaypoint that explicitly requests filled rendering.
Why this exists:
The global OUTLINED box style normally skips the fill render pass, but per-subwaypoint filled markers need that pass even when the rest of the route stays outlined.
When to use:
Use from the world render setup before deciding whether to allocate the quad buffer. Do not use for deciding if a specific waypoint should fill; emitFilledBoxes handles that per waypoint.
Inputs:
groups is the active group iterable already selected for rendering.
Outputs:
Returns true if any waypoint in any group has FLAG_FILLED_SUBWAYPOINT.
Side effects:
None.
Failure modes:
Null groups are not expected; empty groups return false through normal iteration.
Important invariants:
This helper only gates whether the fill pass exists. Visibility, distance, near-hide, and completed-hide filters still run later per waypoint.
Internal logic:
Loop through every group and waypoint, returning true on the first filled subwaypoint flag.
Pseudocode:
for group in groups:
  for each waypoint:
    if waypoint has filled subwaypoint flag return true
return false
Implementation notes:
The scan is cheap relative to rendering and avoids running a quad pass for routes that do not need per-subwaypoint fill.
AI self-check:
Verify the actual fill emitter still skips unflagged waypoints when global fill is off.
]]*/
    private static boolean hasFilledSubwaypoint(Iterable<WaypointGroup> groups) {
        for (WaypointGroup group : groups) {
            for (Waypoint waypoint : group.waypoints()) {
                if (isFilledSubwaypoint(waypoint)) return true;
            }
        }
        return false;
    }

    /*[[AI-FN-DOC
Function:
isSmallSubwaypoint
Purpose:
Determine whether a waypoint should render with the tiny centered subwaypoint marker bounds.
Why this exists:
The small style is meaningful only for subwaypoints, and render code should not shrink a main waypoint just because stale flags exist in old or hand-edited data.
When to use:
Use anywhere marker bounds are computed for world or HUD fallback rendering.
Inputs:
waypoint is the waypoint being rendered and may be null only from defensive callers.
Outputs:
Returns true when the waypoint is non-null, structurally a subwaypoint, and has FLAG_SMALL_SUBWAYPOINT.
Side effects:
None.
Failure modes:
Null returns false.
Important invariants:
Main waypoints always keep full block bounds even if the small flag appears in legacy data.
Internal logic:
Check non-null, isSubwaypoint, and hasFlag for the small style bit.
Pseudocode:
return waypoint not null and waypoint.isSubwaypoint and waypoint has FLAG_SMALL_SUBWAYPOINT
Implementation notes:
The structural check mirrors the GUI, which only exposes these toggles on subwaypoint rows.
AI self-check:
Verify normal route markers cannot shrink accidentally.
]]*/
    private static boolean isSmallSubwaypoint(Waypoint waypoint) {
        return waypoint != null
                && waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
    }

    /*[[AI-FN-DOC
Function:
isFilledSubwaypoint
Purpose:
Determine whether a waypoint asks for per-subwaypoint filled-box rendering.
Why this exists:
The fill flag is only intended for subwaypoints, so renderer decisions should ignore stale filled flags on main waypoints.
When to use:
Use when deciding whether to force a fill pass or fill a specific waypoint outside global filled box styles.
Inputs:
waypoint is the waypoint being considered and may be null only from defensive callers.
Outputs:
Returns true when the waypoint is non-null, structurally a subwaypoint, and has FLAG_FILLED_SUBWAYPOINT.
Side effects:
None.
Failure modes:
Null returns false.
Important invariants:
Per-waypoint fill never applies to main waypoints.
Internal logic:
Check non-null, subwaypoint structure, and the filled style flag.
Pseudocode:
return waypoint not null and waypoint.isSubwaypoint and waypoint has FLAG_FILLED_SUBWAYPOINT
Implementation notes:
Keeping this guard centralized lets storage retain raw flags while rendering remains conservative.
AI self-check:
Verify hasFilledSubwaypoint and emitFilledBoxes both call this helper.
]]*/
    private static boolean isFilledSubwaypoint(Waypoint waypoint) {
        return waypoint != null
                && waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT);
    }

    /*[[AI-FN-DOC
Function:
waypointBoxMin
Purpose:
Calculate the minimum coordinate for a waypoint marker box on one axis.
Why this exists:
Small subwaypoints render as a centered 1/16-block cube and can now have sixteenth-block centers, while all other waypoints keep full block bounds.
When to use:
Use for x, y, or z box minimums before emitting world vertices or projecting HUD fallback corners.
Inputs:
blockCoordinate is the integer waypoint coordinate for one axis; centerCoordinate is the precise world-space center for that axis; waypoint is the marker whose style flags determine the bounds.
Outputs:
Returns a double minimum coordinate in world space.
Side effects:
None.
Failure modes:
None. Null waypoints are treated as normal full-size boxes through isSmallSubwaypoint.
Important invariants:
Small boxes remain centered around centerCoordinate and keep exactly SMALL_SUBWAYPOINT_SIZE length.
Internal logic:
Return centerCoordinate minus half the small size for small subwaypoints, otherwise the raw block coordinate.
Pseudocode:
if isSmallSubwaypoint waypoint return centerCoordinate - SMALL_SUBWAYPOINT_SIZE / 2
return blockCoordinate
Implementation notes:
Passing centerCoordinate keeps the helper axis-agnostic while allowing small waypoints to preserve sub-block precision.
AI self-check:
Verify waypointBoxMax uses the complementary center math so size is exactly 1/16.
]]*/
    private static double waypointBoxMin(int blockCoordinate, double centerCoordinate, Waypoint waypoint) {
        return isSmallSubwaypoint(waypoint)
                ? centerCoordinate - SMALL_SUBWAYPOINT_SIZE * 0.5
                : blockCoordinate;
    }

    /*[[AI-FN-DOC
Function:
waypointBoxMax
Purpose:
Calculate the maximum coordinate for a waypoint marker box on one axis.
Why this exists:
Small subwaypoints need matching max bounds so their rendered cube is one sixteenth of normal size around the stored precise center.
When to use:
Use with waypointBoxMin for every axis when emitting or projecting marker boxes.
Inputs:
blockCoordinate is the integer waypoint coordinate for one axis; centerCoordinate is the precise world-space center for that axis; waypoint is the marker whose style flags determine the bounds.
Outputs:
Returns a double maximum coordinate in world space.
Side effects:
None.
Failure modes:
None. Null waypoints are treated as normal full-size boxes through isSmallSubwaypoint.
Important invariants:
For small subwaypoints, max minus min equals SMALL_SUBWAYPOINT_SIZE.
Internal logic:
Return centerCoordinate plus half the small size for small subwaypoints, otherwise blockCoordinate plus one.
Pseudocode:
if isSmallSubwaypoint waypoint return centerCoordinate + SMALL_SUBWAYPOINT_SIZE / 2
return blockCoordinate + 1
Implementation notes:
The helper keeps world and HUD fallback boxes visually identical while respecting precise small-waypoint centers.
AI self-check:
Verify full-size markers still cover the original [coord, coord + 1] block range.
]]*/
    private static double waypointBoxMax(int blockCoordinate, double centerCoordinate, Waypoint waypoint) {
        return isSmallSubwaypoint(waypoint)
                ? centerCoordinate + SMALL_SUBWAYPOINT_SIZE * 0.5
                : blockCoordinate + 1.0;
    }

    /*[[AI-FN-DOC
Function:
emitLineBoxes
Purpose:
Emit world-space outline boxes for every visible waypoint in a group, using precise small bounds for styled subwaypoints.
Why this exists:
Outlined marker geometry needs to honor route visibility, completed/near-hide rules, and the new per-subwaypoint small marker flag.
When to use:
Use from onWorldRender when the global box style includes outlines.
Inputs:
ps is the translated pose stack; lines is the line vertex consumer; g is the route group; camPos/playerPos are current positions; maxStaticDistanceSq and nearHideDistanceSq are precomputed culling thresholds.
Outputs:
No return value. Appends line-box vertices to the line buffer.
Side effects:
Writes vertices into the active render buffer.
Failure modes:
Hidden, culled, completed-hidden, or near-hidden waypoints emit nothing.
Important invariants:
Normal waypoints render as one-block boxes; small subwaypoints render as 1/16-block boxes centered on the waypoint's precise center.
Internal logic:
Iterate visible indices, apply existing visibility filters, compute alpha and styled box bounds, then emit a line box.
Pseudocode:
for each visible index:
  skip static reached, near-hidden, distance-hidden, or completed-hidden waypoint
  alpha = state alpha times opacity
  bounds = waypointBoxMin/Max for x,y,z
  emit line box with bounds
Implementation notes:
Bounds are calculated through shared helpers so HUD fallback, fill boxes, line boxes, and precise reposition previews stay aligned.
AI self-check:
Verify normal markers still cover a full block and small markers honor precise centers.
]]*/
    private void emitLineBoxes(PoseStack ps, VertexConsumer lines, WaypointGroup g,
                               Vec3 camPos, Vec3 playerPos,
                               double maxStaticDistanceSq, double nearHideDistanceSq) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();
        float outlineThickness = (float) config.waypointOutlineThickness();

        g.forEachVisibleIndex(i -> {
            if (shouldHideStaticReached(g, i)) return;

            Waypoint w = g.get(i);
            if (shouldHideNearPlayer(w, playerPos, nearHideDistanceSq)) return;
            if (isStaticBeyondDistanceLimit(g, w, camPos, maxStaticDistanceSq)) return;

            State state = stateFor(g, i, currentIdx);
            if (shouldHideCompletedSequenceWaypoint(g, i, currentIdx, state, showCompleted, w)) return;

            float alpha = alphaFor(g, state) * beaconOpacity;
            float x1 = (float) waypointBoxMin(w.x(), w.centerX(), w);
            float y1 = (float) waypointBoxMin(w.y(), w.centerY(), w);
            float z1 = (float) waypointBoxMin(w.z(), w.centerZ(), w);
            float x2 = (float) waypointBoxMax(w.x(), w.centerX(), w);
            float y2 = (float) waypointBoxMax(w.y(), w.centerY(), w);
            float z2 = (float) waypointBoxMax(w.z(), w.centerZ(), w);
            RenderHelpers.emitLineBox(lines, ps, x1, y1, z1, x2, y2, z2,
                    w.color(), alpha, outlineThickness);
        });
    }

    /*[[AI-FN-DOC
Function:
emitFilledBoxes
Purpose:
Emit world-space filled boxes for globally filled markers or subwaypoints with the filled override.
Why this exists:
Filled subwaypoint styling should work even when the user's global box style is outlined, without filling every other waypoint.
When to use:
Use from onWorldRender whenever the global style asks for fill or any active subwaypoint has FLAG_FILLED_SUBWAYPOINT.
Inputs:
ps is the translated pose stack; quads is the quad vertex consumer; g is the route group; camPos/playerPos are current positions; maxStaticDistanceSq and nearHideDistanceSq are precomputed culling thresholds; fillAllWaypoints is true when the global box style fills every waypoint.
Outputs:
No return value. Appends filled-box quad vertices to the quad buffer.
Side effects:
Writes vertices into the active render buffer.
Failure modes:
Hidden or culled waypoints emit nothing. When fillAllWaypoints is false, unfilled subwaypoints and normal waypoints are skipped.
Important invariants:
Per-subwaypoint fill does not change global box style, and small filled subwaypoints use the same precise 1/16-block bounds as their outline.
Internal logic:
Iterate visible indices, apply visibility filters, skip unfilled waypoints when not globally filling, compute alpha and styled precise bounds, then emit a filled box.
Pseudocode:
for each visible index:
  skip static reached, near-hidden, distance-hidden waypoint
  if not fillAllWaypoints and waypoint is not filled subwaypoint, continue
  skip completed-hidden waypoint
  alpha = state alpha times opacity
  bounds = waypointBoxMin/Max for x,y,z
  emit filled box with bounds
Implementation notes:
The filled flag is checked before state classification where possible to avoid extra work for unfilled waypoints in outlined mode.
AI self-check:
Verify global FILLED and FILLED_OUTLINED styles still fill all visible waypoints, and small filled waypoints align to their outline.
]]*/
    private void emitFilledBoxes(PoseStack ps, VertexConsumer quads, WaypointGroup g,
                                 Vec3 camPos, Vec3 playerPos,
                                 double maxStaticDistanceSq, double nearHideDistanceSq,
                                 boolean fillAllWaypoints) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        g.forEachVisibleIndex(i -> {
            if (shouldHideStaticReached(g, i)) return;

            Waypoint w = g.get(i);
            if (shouldHideNearPlayer(w, playerPos, nearHideDistanceSq)) return;
            if (isStaticBeyondDistanceLimit(g, w, camPos, maxStaticDistanceSq)) return;
            if (!fillAllWaypoints && !isFilledSubwaypoint(w)) return;

            State state = stateFor(g, i, currentIdx);
            if (shouldHideCompletedSequenceWaypoint(g, i, currentIdx, state, showCompleted, w)) return;

            float alpha = alphaFor(g, state) * beaconOpacity;
            float x1 = (float) waypointBoxMin(w.x(), w.centerX(), w);
            float y1 = (float) waypointBoxMin(w.y(), w.centerY(), w);
            float z1 = (float) waypointBoxMin(w.z(), w.centerZ(), w);
            float x2 = (float) waypointBoxMax(w.x(), w.centerX(), w);
            float y2 = (float) waypointBoxMax(w.y(), w.centerY(), w);
            float z2 = (float) waypointBoxMax(w.z(), w.centerZ(), w);
            RenderHelpers.emitFilledBox(quads, ps, x1, y1, z1, x2, y2, z2,
                    w.color(), alpha * FILLED_ALPHA_SCALE);
        });
    }

    private void emitBeaconBeams(PoseStack ps, VertexConsumer quads, WaypointGroup g,
                                 Vec3 camPos, Vec3 playerPos,
                                 double maxStaticDistanceSq, double nearHideDistanceSq,
                                 int minY, int maxY) {
        WaypointerConfig.BeaconBeamMode mode = config.beaconBeamMode();
        if (mode == WaypointerConfig.BeaconBeamMode.OFF || g.isEmpty()) return;

        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();

        if (mode == WaypointerConfig.BeaconBeamMode.CURRENT) {
            int beamIndex = currentBeamIndex(g);
            emitBeaconBeamIfVisible(ps, quads, g, beamIndex, currentIdx,
                    showCompleted, camPos, playerPos, maxStaticDistanceSq,
                    nearHideDistanceSq, minY, maxY);
            return;
        }

        g.forEachVisibleIndex(i -> emitBeaconBeamIfVisible(ps, quads, g, i,
                currentIdx, showCompleted, camPos, playerPos, maxStaticDistanceSq,
                nearHideDistanceSq, minY, maxY));
    }

    private void emitBeaconBeamIfVisible(PoseStack ps, VertexConsumer quads,
                                         WaypointGroup g, int i, int currentIdx,
                                         boolean showCompleted, Vec3 camPos,
                                         Vec3 playerPos, double maxStaticDistanceSq,
                                         double nearHideDistanceSq, int minY, int maxY) {
        if (i < 0 || i >= g.size()) return;
        if (shouldHideStaticReached(g, i)) return;

        Waypoint w = g.get(i);
        if (shouldHideNearPlayer(w, playerPos, nearHideDistanceSq)) return;
        if (isStaticBeyondDistanceLimit(g, w, camPos, maxStaticDistanceSq)) return;

        State state = stateFor(g, i, currentIdx);
        if (shouldHideCompletedSequenceWaypoint(g, i, currentIdx, state, showCompleted, w)) return;

        float alpha = alphaFor(g, state) * (float) config.beaconOpacity() * BEAM_ALPHA_SCALE;
        if (alpha <= 0.0f) return;

        float y1 = config.beaconBeamExtendsBelowWaypoint() ? minY : w.y();
        float y2 = Math.max(y1 + 1.0f, maxY);
        RenderHelpers.emitVerticalColumn(quads, ps,
                (float) w.centerX(), y1, (float) w.centerZ(),
                y2, BEAM_HALF_WIDTH, w.color(), alpha);
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
    public void render(GuiGraphics g, DeltaTracker tick) {
        boolean showNames = config.showWaypointNames();
        boolean showRouteProgress = config.showRouteProgress();
        boolean showDistances = config.showWaypointDistances();
        boolean drawHudFallback = IrisShaderFallback.shouldUse(config);
        if (!showNames && !showRouteProgress && !showDistances && !drawHudFallback) return;

        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = renderer.getMainCamera();
        if (!camera.isInitialized()) return;

        Font font = mc.font;
        Vec3 camPos = camera.position();
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        labelProjector.prepare(renderer, camera);
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int labelBudget = config.maxWaypointLabels();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        double nearHideDistanceSq = nearHideDistanceSq();
        double labelNearHideDistanceSq = labelNearHideDistanceSq();
        clearLabelCandidates();
        labelCandidateCount = 0;

        if (drawHudFallback && config.beaconOpacity() > 0.0) {
            drawHudFallbackBoxes(g, camPos, playerPos, screenW, screenH, groups,
                    maxStaticDistanceSq, nearHideDistanceSq);
        }

        if (showNames || showRouteProgress || showDistances) {
            for (WaypointGroup group : groups) {
                drawGroupLabels(g, font, camPos, playerPos, screenW, screenH, group,
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

    private void drawHudFallbackBoxes(GuiGraphics g, Vec3 camPos, Vec3 playerPos, int screenW,
                                      int screenH, Iterable<WaypointGroup> groups,
                                      double maxStaticDistanceSq, double nearHideDistanceSq) {
        WaypointerConfig.BoxStyle style = config.boxStyle();
        if (style == WaypointerConfig.BoxStyle.FILLED) {
            // The HUD fallback cannot faithfully preserve translucent 3D faces
            // after projection. Draw an outline anyway so FILLED users still get
            // a visible shader-safe marker instead of losing boxes entirely.
            style = WaypointerConfig.BoxStyle.OUTLINED;
        }
        if (style == WaypointerConfig.BoxStyle.OUTLINED
                || style == WaypointerConfig.BoxStyle.FILLED_OUTLINED) {
            for (WaypointGroup group : groups) {
                drawHudFallbackGroupBoxes(g, camPos, playerPos, screenW, screenH,
                        group, maxStaticDistanceSq, nearHideDistanceSq);
            }
        }
    }

    private void drawHudFallbackGroupBoxes(GuiGraphics g, Vec3 camPos, Vec3 playerPos, int screenW,
                                           int screenH, WaypointGroup group,
                                           double maxStaticDistanceSq, double nearHideDistanceSq) {
        int currentIdx = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        group.forEachVisibleIndex(i -> {
            if (shouldHideStaticReached(group, i)) return;

            Waypoint waypoint = group.get(i);
            if (shouldHideNearPlayer(waypoint, playerPos, nearHideDistanceSq)) return;
            if (isStaticBeyondDistanceLimit(group, waypoint, camPos, maxStaticDistanceSq)) return;

            State state = stateFor(group, i, currentIdx);
            if (shouldHideCompletedSequenceWaypoint(
                    group, i, currentIdx, state, showCompleted, waypoint)) {
                return;
            }

            float alpha = alphaFor(group, state) * beaconOpacity;
            int argb = RenderHelpers.withAlpha(0xFF000000 | (waypoint.color() & 0xFFFFFF), alpha);
            if (!projectBoxCorners(waypoint, screenW, screenH)) return;

            double outlineThickness = config.waypointOutlineThickness();
            for (int edge = 0; edge < BOX_EDGE_A.length; edge++) {
                int a = BOX_EDGE_A[edge];
                int b = BOX_EDGE_B[edge];
                if (!boxCornerVisible[a] || !boxCornerVisible[b]) continue;
                drawFastScreenLine(g,
                        boxScreenScratch[a * 2], boxScreenScratch[a * 2 + 1],
                        boxScreenScratch[b * 2], boxScreenScratch[b * 2 + 1],
                        argb, outlineThickness);
            }
        });
    }

    /*[[AI-FN-DOC
Function:
projectBoxCorners
Purpose:
Project a waypoint marker box's eight corners for the Iris HUD fallback outline renderer.
Why this exists:
The HUD fallback draws screen-space box edges and needs the same full-size or small-subwaypoint bounds as the world renderer.
When to use:
Use from drawHudFallbackGroupBoxes before drawing projected box edges.
Inputs:
waypoint is the marker to project; screenW and screenH are current GUI dimensions.
Outputs:
Returns true when at least part of the projected box is inside the padded screen bounds. Updates boxScreenScratch and boxCornerVisible arrays.
Side effects:
Mutates reusable projection scratch fields on the renderer instance.
Failure modes:
Returns false when all corners fail projection or the projected bounds are outside the cull margin.
Important invariants:
Small subwaypoints project a centered 1/16-block cube, matching world-space outline/fill geometry.
Internal logic:
Reset projected min/max bounds, compute styled min/max world coordinates, project all eight corners, and compare projected bounds to a screen margin.
Pseudocode:
reset projected bounds
x/y/z min = waypointBoxMin
x/y/z max = waypointBoxMax
project eight box corners
if no finite projected corner return false
return projected bounds intersect screen plus margin
Implementation notes:
The helper intentionally projects all corners even if some fail so partially visible boxes still draw their visible edges.
AI self-check:
Verify world and HUD fallback marker sizes cannot diverge.
]]*/
    private boolean projectBoxCorners(Waypoint waypoint, int screenW, int screenH) {
        projectedBoxMinX = Double.POSITIVE_INFINITY;
        projectedBoxMinY = Double.POSITIVE_INFINITY;
        projectedBoxMaxX = Double.NEGATIVE_INFINITY;
        projectedBoxMaxY = Double.NEGATIVE_INFINITY;

        double x1 = waypointBoxMin(waypoint.x(), waypoint.centerX(), waypoint);
        double y1 = waypointBoxMin(waypoint.y(), waypoint.centerY(), waypoint);
        double z1 = waypointBoxMin(waypoint.z(), waypoint.centerZ(), waypoint);
        double x2 = waypointBoxMax(waypoint.x(), waypoint.centerX(), waypoint);
        double y2 = waypointBoxMax(waypoint.y(), waypoint.centerY(), waypoint);
        double z2 = waypointBoxMax(waypoint.z(), waypoint.centerZ(), waypoint);

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

        private void drawGroupLabels(GuiGraphics g, Font font, Vec3 camPos, Vec3 playerPos,
                                 int screenW, int screenH, WaypointGroup group,
                                 boolean showNames, boolean showRouteProgress, boolean showDistances,
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
        String routeProgressText = showRouteProgress ? routeProgressText(group) : null;

        group.forEachVisibleIndex(
                                i -> {
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

            if (labelBudget > 0) {
                LabelCandidate candidate = nextLabelCandidate();
                candidate.set(group, i, w, routeProgressText, hasSubwaypoints,
                        sx, sy, distanceSq, nameColor, alpha, labelScale);
                return;
            }

            double rowY = sy;
            if (showNames) {
                String name = labelFor(group, i, w, hasSubwaypoints);
                drawCenteredLabel(g, font, name, sx, rowY,
                        RenderHelpers.withAlpha(nameColor, alpha), alpha, labelScale);
                rowY += labelRowAdvance(font, labelScale);
            }
            if (showRouteProgress) {
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

    private void drawBudgetedLabels(GuiGraphics g, Font font, int count,
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

    private void drawCandidateLabel(GuiGraphics g, Font font, LabelCandidate candidate,
                                    boolean showNames, boolean showRouteProgress,
                                    boolean showDistances) {
        double rowY = candidate.screenY;
        if (showNames) {
            String name = labelFor(candidate.group, candidate.index, candidate.waypoint,
                    candidate.hasSubwaypoints);
            drawCenteredLabel(g, font, name, candidate.screenX, rowY,
                    RenderHelpers.withAlpha(candidate.nameColor, candidate.alpha),
                    candidate.alpha, candidate.scale);
            rowY += labelRowAdvance(font, candidate.scale);
        }
        if (showRouteProgress) {
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

        private static float labelScaleForDepth(double depth, float fovDegrees,
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
    private void drawCenteredLabel(GuiGraphics g, Font font, String text,
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
        // drawString's shadow flag stays on in both modes -- without the backdrop the
        // drop shadow is doing all the work keeping text readable against bright biomes.
        g.drawString(font, text, 0, 0, argb, true);
        g.pose().popMatrix();
    }

    private static String routeProgressText(WaypointGroup group) {
        return formatProgressPercent(RouteProgress.snapshot(group).percentComplete);
    }

    private static String formatProgressPercent(double percent) {
        double safePercent = Double.isFinite(percent)
                ? Math.max(0.0, Math.min(100.0, percent))
                : 0.0;
        long tenths = Math.round(safePercent * 10.0);
        return (tenths / 10) + "." + (tenths % 10) + "%";
    }

    private String labelFor(WaypointGroup g, int i, Waypoint w,
                            boolean hasSubwaypoints) {
        if (w.hasName()) return AmpersandFormatting.translate(w.name());
        if (g.isSubwaypoint(i) || (hasSubwaypoints && g.loadMode() == WaypointGroup.LoadMode.STATIC)) {
            return g.displayIndexLabel(i);
        }
        if (g.loadMode() == WaypointGroup.LoadMode.STATIC) return indexLabel(i + 1);

        int mainOrdinal = hasSubwaypoints ? g.mainOrdinal(i) : i + 1;
        return indexLabel(mainOrdinal);
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

    /*[[AI-FN-DOC
Function:
stateFor
Purpose:
Classify a waypoint as completed, current, or upcoming for sequence rendering.
Why this exists:
Boxes, labels, beams, and connector endpoint filtering all need a consistent route-state interpretation.
When to use:
Use from rendering visibility and alpha decisions. Do not use for mutating route progress.
Inputs:
group is the route; i is the waypoint index being classified; currentIdx is the group's current target index.
Outputs:
Returns a State enum value describing render status.
Side effects:
None.
Failure modes:
Out-of-range indices are not validated here; callers are expected to pass valid indices from group iteration.
Important invariants:
Static routes always render waypoints as current. Exact subwaypoint current targets must classify as current.
Internal logic:
Return current for static routes, then handle subwaypoint parent/current relationships, active visual holds, completed indices, and upcoming indices.
Pseudocode:
if group load mode is static, return CURRENT
activeParent = group.activeSubwaypointParentIndex
if index is subwaypoint:
  if index equals currentIdx, return CURRENT
  parent = parent main index
  if parent equals activeParent or currentIdx, return CURRENT
  if parent is before currentIdx, return COMPLETED
  return UPCOMING
if index equals active parent, return CURRENT
if index before currentIdx, return COMPLETED
if index equals currentIdx, return CURRENT
return UPCOMING
Implementation notes:
The exact subwaypoint check is required for /wp skipto decimal targets to highlight the chosen child instead of only its parent.
AI self-check:
Verify the child current case runs before parent-based classification.
]]*/
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

    /*[[AI-FN-DOC
Function:
shouldHideCompletedSequenceWaypoint
Purpose:
Decide whether a sequence waypoint classified as completed should be hidden from world rendering.
Why this exists:
The renderer needs one consistent gate for completed sequence markers so boxes, beams, labels, tracers, and connector endpoints agree.
When to use:
Use during per-waypoint render filtering after stateFor has classified the waypoint. Do not use for static route reached markers.
Inputs:
group is the waypoint group being rendered; index is the waypoint index; currentIdx is the current route target; state is the classified waypoint state; showCompleted is the config value; waypoint is the waypoint data.
Outputs:
Returns true when the completed waypoint should be skipped entirely.
Side effects:
None.
Failure modes:
Null waypoint is not expected because callers iterate concrete group entries.
Important invariants:
Only State.COMPLETED can be hidden by this setting. Current targets and active subwaypoint holds must remain visible because stateFor classifies them as CURRENT.
Internal logic:
Ignore non-completed states, always respect the per-waypoint hide beacon flag for completed waypoints, otherwise hide completed waypoints whenever showCompleted is false.
Pseudocode:
if state is not COMPLETED return false
if waypoint has hide-beacon flag return true
return not showCompleted
Implementation notes:
The group, index, and currentIdx parameters remain for call-site clarity and future route-state decisions even though the current rule no longer needs contextual previous-waypoint exceptions.
AI self-check:
Verify the previous completed waypoint is hidden when Show completed waypoints is off and current/held waypoints are unaffected.
]]*/
    private boolean shouldHideCompletedSequenceWaypoint(WaypointGroup group,
                                                        int index,
                                                        int currentIdx,
                                                        State state,
                                                        boolean showCompleted,
                                                        Waypoint waypoint) {
        if (state != State.COMPLETED) return false;
        if (waypoint.hasFlag(Waypoint.FLAG_HIDE_BEACON)) return true;
        return !showCompleted;
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

    /*[[AI-FN-DOC
Function:
isStaticBeyondDistanceLimit.
Purpose:
Decide whether a static-route waypoint is far enough from the camera to skip rendering.
Why this exists:
Static overlays can contain many points, so distance culling keeps the world render pass light while sequence routes remain unaffected.
When to use:
Use before rendering waypoint-linked world or label elements for static routes. Do not use for route progression, which has separate reach logic.
Inputs:
group is the waypoint group; waypoint is the marker being checked; camPos is the camera position; maxStaticDistanceSq is the squared static render distance limit.
Outputs:
Returns true when the waypoint should be culled for static distance, false otherwise.
Side effects:
None.
Failure modes:
None expected for finite camera positions; disabled limits and non-static routes return false.
Important invariants:
Distance must be measured from waypoint.centerX/Y/Z so precise small waypoint render filters match their actual marker position.
Internal logic:
Return false when static culling is disabled or the group is not static, otherwise compute squared distance from camera to waypoint center and delegate to the scalar overload.
Pseudocode:
if maxStaticDistanceSq <= 0 or group load mode is not STATIC, return false
dx = waypoint.centerX - cam x
dy = waypoint.centerY - cam y
dz = waypoint.centerZ - cam z
return scalar isStaticBeyondDistanceLimit(group, distanceSq, maxStaticDistanceSq)
Implementation notes:
Using the center methods preserves old behavior for block-centered waypoints because their default precise center is x/y/z + 0.5.
AI self-check:
Verify static route culling is unchanged for normal waypoints and aligned for precise small waypoints.
]]*/
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

    static void drawScreenLine(GuiGraphics g, double x1, double y1,
                               double x2, double y2, int argb, double thickness) {
        drawFastScreenLine(g, x1, y1, x2, y2, argb, thickness);
    }

    private static void drawFastScreenLine(GuiGraphics g, double x1, double y1,
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

        double step = Math.max(1.0, scaledThickness * 0.75);
        int samples = Math.max(1, (int) Math.ceil(length / step));
        int radius = Math.max(0, (int) Math.floor(scaledThickness * 0.5));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, argb);
        }
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
