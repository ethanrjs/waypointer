package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
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
import java.util.Arrays;
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

    /**
     * Cap on the pre-baked distance table. 0..4095m covers dense imported route
     * overlays without allocating one distance string per visible label per
     * frame. The array is still tiny compared with a single route import.
     */
    private static final int DISTANCE_CACHE_MAX = 4096;
    private static final String[] DISTANCE_CACHE;
    static {
        DISTANCE_CACHE = new String[DISTANCE_CACHE_MAX];
        for (int i = 0; i < DISTANCE_CACHE_MAX; i++) DISTANCE_CACHE[i] = i + "m";
    }

    /**
     * Bounded cache for generated labels like "#34" and "Next (34/120)".
     * User-named waypoints already return their stored string; this only avoids
     * rebuilding unnamed labels every render frame.
     */
    private static final int INDEX_LABEL_CACHE_MAX = 256;
    private static final int NEXT_LABEL_CACHE_SIZE = 128;
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
    private final int[] nextLabelIndexes = new int[NEXT_LABEL_CACHE_SIZE];
    private final int[] nextLabelSizes = new int[NEXT_LABEL_CACHE_SIZE];
    private final String[] nextLabelCache = new String[NEXT_LABEL_CACHE_SIZE];
    private final WorldScreenProjector labelProjector = new WorldScreenProjector();
    private final double[] labelScreenScratch = new double[2];
    private final ArrayList<LabelCandidate> labelCandidates = new ArrayList<>();
    private int labelCandidateCount;

    public WaypointRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
        Arrays.fill(nextLabelIndexes, -1);
        Arrays.fill(nextLabelSizes, -1);
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

    private void onWorldRender(WorldRenderContext ctx) {
        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        WaypointerConfig.BoxStyle style = config.boxStyle();
        boolean drawLines = style != WaypointerConfig.BoxStyle.FILLED;
        boolean drawFill  = style != WaypointerConfig.BoxStyle.OUTLINED;
        boolean drawBeams = config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF;
        if (!drawLines && !drawFill && !drawBeams) return;

        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return;

        PoseStack ps = ctx.matrices();
        if (ps == null) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());

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
                    emitBeaconBeams(ps, quads, g, camPos, maxStaticDistanceSq, minY, maxY);
                }
            }
            if (drawFill) {
                for (WaypointGroup g : groups) {
                    emitFilledBoxes(ps, quads, g, camPos, maxStaticDistanceSq);
                }
            }
            RenderHelpers.endBatch(buffers, quadType);
        }
        if (drawLines) {
            RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
            VertexConsumer lines = buffers.getBuffer(lineType);
            for (WaypointGroup g : groups) {
                emitLineBoxes(ps, lines, g, camPos, maxStaticDistanceSq);
            }
            RenderHelpers.endBatch(buffers, lineType);
        }

        ps.popPose();
    }

    private void emitLineBoxes(PoseStack ps, VertexConsumer lines, WaypointGroup g,
                               Vec3 camPos, double maxStaticDistanceSq) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        g.forEachVisibleIndex(i -> {
            if (shouldHideStaticReached(g, i)) return;

            Waypoint w = g.get(i);
            if (isStaticBeyondDistanceLimit(g, w, camPos, maxStaticDistanceSq)) return;

            State state = stateFor(g, i, currentIdx);
            if (state == State.COMPLETED && (!showCompleted || w.hasFlag(Waypoint.FLAG_HIDE_BEACON))) return;

            float alpha = alphaFor(g, state) * beaconOpacity;
            float x = w.x(), y = w.y(), z = w.z();
            RenderHelpers.emitLineBox(lines, ps, x, y, z, x + 1f, y + 1f, z + 1f, w.color(), alpha);
        });
    }

    private void emitFilledBoxes(PoseStack ps, VertexConsumer quads, WaypointGroup g,
                                 Vec3 camPos, double maxStaticDistanceSq) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();

        g.forEachVisibleIndex(i -> {
            if (shouldHideStaticReached(g, i)) return;

            Waypoint w = g.get(i);
            if (isStaticBeyondDistanceLimit(g, w, camPos, maxStaticDistanceSq)) return;

            State state = stateFor(g, i, currentIdx);
            if (state == State.COMPLETED && (!showCompleted || w.hasFlag(Waypoint.FLAG_HIDE_BEACON))) return;

            float alpha = alphaFor(g, state) * beaconOpacity;
            float x = w.x(), y = w.y(), z = w.z();
            RenderHelpers.emitFilledBox(quads, ps, x, y, z, x + 1f, y + 1f, z + 1f,
                    w.color(), alpha * FILLED_ALPHA_SCALE);
        });
    }

    private void emitBeaconBeams(PoseStack ps, VertexConsumer quads, WaypointGroup g,
                                 Vec3 camPos, double maxStaticDistanceSq,
                                 int minY, int maxY) {
        WaypointerConfig.BeaconBeamMode mode = config.beaconBeamMode();
        if (mode == WaypointerConfig.BeaconBeamMode.OFF || g.isEmpty()) return;

        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();

        if (mode == WaypointerConfig.BeaconBeamMode.CURRENT) {
            int beamIndex = currentBeamIndex(g);
            emitBeaconBeamIfVisible(ps, quads, g, beamIndex, currentIdx,
                    showCompleted, camPos, maxStaticDistanceSq, minY, maxY);
            return;
        }

        g.forEachVisibleIndex(i -> emitBeaconBeamIfVisible(ps, quads, g, i,
                currentIdx, showCompleted, camPos, maxStaticDistanceSq, minY, maxY));
    }

    private void emitBeaconBeamIfVisible(PoseStack ps, VertexConsumer quads,
                                         WaypointGroup g, int i, int currentIdx,
                                         boolean showCompleted, Vec3 camPos,
                                         double maxStaticDistanceSq, int minY, int maxY) {
        if (i < 0 || i >= g.size()) return;
        if (shouldHideStaticReached(g, i)) return;

        Waypoint w = g.get(i);
        if (isStaticBeyondDistanceLimit(g, w, camPos, maxStaticDistanceSq)) return;

        State state = stateFor(g, i, currentIdx);
        if (state == State.COMPLETED && (!showCompleted || w.hasFlag(Waypoint.FLAG_HIDE_BEACON))) return;

        float alpha = alphaFor(g, state) * (float) config.beaconOpacity() * BEAM_ALPHA_SCALE;
        if (alpha <= 0.0f) return;

        float y1 = config.beaconBeamExtendsBelowWaypoint() ? minY : w.y();
        float y2 = Math.max(y1 + 1.0f, maxY);
        RenderHelpers.emitVerticalColumn(quads, ps,
                w.x() + 0.5f, y1, w.z() + 0.5f,
                y2, BEAM_HALF_WIDTH, w.color(), alpha);
    }

    private static int currentBeamIndex(WaypointGroup g) {
        if (g.isEmpty()) return -1;
        if (g.isComplete()) return g.size() - 1;
        return Math.max(0, Math.min(g.currentIndex(), g.size() - 1));
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
        boolean showDistances = config.showWaypointDistances();
        if (!showNames && !showDistances) return;

        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = renderer.getMainCamera();
        if (!camera.isInitialized()) return;

        Font font = mc.font;
        Vec3 camPos = camera.position();
        labelProjector.prepare(renderer, camera);
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int labelBudget = config.maxWaypointLabels();
        double maxStaticDistanceSq = squaredDistanceLimit(config.maxStaticWaypointRenderDistance());
        labelCandidateCount = 0;

        for (WaypointGroup group : groups) {
            drawGroupLabels(g, font, camPos, screenW, screenH, group,
                    showNames, showDistances, labelBudget, maxStaticDistanceSq);
        }
        if (labelBudget > 0 && labelCandidateCount > 0) {
            drawBudgetedLabels(g, font, Math.min(labelBudget, labelCandidateCount));
        }
    }

    private void drawGroupLabels(GuiGraphics g, Font font, Vec3 camPos,
                                 int screenW, int screenH, WaypointGroup group,
                                 boolean showNames, boolean showDistances,
                                 int labelBudget, double maxStaticDistanceSq) {
        int currentIdx = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        // Hoist out of the per-waypoint lambda so a long route doesn't pay
        // a getter call (and a dirty-flag-eligible call site) per label.
        double labelLift = LABEL_ANCHOR_LIFT + config.labelHeightOffset();
        boolean colorizeNames = config.matchWaypointTextToWaypointColor();

        group.forEachVisibleIndex(i -> {
            if (shouldHideStaticReached(group, i)) return;

            Waypoint w = group.get(i);
            State state = stateFor(group, i, currentIdx);
            if (state == State.COMPLETED && (!showCompleted || w.hasFlag(Waypoint.FLAG_HIDE_BEACON))) return;
            if (w.hasFlag(Waypoint.FLAG_HIDE_NAME)) return;

            double ax = w.x() + 0.5;
            double ay = w.y() + labelLift;
            double az = w.z() + 0.5;
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

            String name = showNames ? labelFor(group, i, w, state) : null;
            String distanceText = showDistances
                    ? distanceString((int) Math.sqrt(distanceSq))
                    : null;
            float alpha = alphaFor(group, state);
            int nameColor = colorizeNames && showNames
                    ? 0xFF000000 | (w.color() & 0xFFFFFF)
                    : NAME_ARGB;

            if (labelBudget > 0) {
                LabelCandidate candidate = nextLabelCandidate();
                candidate.set(name, distanceText, sx, sy, distanceSq, nameColor, alpha);
                return;
            }

            double rowY = sy;
            if (showNames) {
                drawCenteredLabel(g, font, name, sx, rowY, withAlpha(nameColor, alpha), alpha);
                rowY += font.lineHeight + DISTANCE_ROW_GAP;
            }
            if (showDistances) {
                drawCenteredLabel(g, font, distanceText, sx, rowY,
                        withAlpha(DISTANCE_ARGB, alpha), alpha);
            }
        });
    }

    private void drawBudgetedLabels(GuiGraphics g, Font font, int count) {
        labelCandidates.subList(0, labelCandidateCount).sort(LABEL_NEAREST_FIRST);
        for (int i = 0; i < count; i++) {
            LabelCandidate candidate = labelCandidates.get(i);
            double rowY = candidate.screenY;
            if (candidate.name != null) {
                drawCenteredLabel(g, font, candidate.name, candidate.screenX, rowY,
                        withAlpha(candidate.nameColor, candidate.alpha), candidate.alpha);
                rowY += font.lineHeight + DISTANCE_ROW_GAP;
            }
            if (candidate.distance != null) {
                drawCenteredLabel(g, font, candidate.distance, candidate.screenX, rowY,
                        withAlpha(DISTANCE_ARGB, candidate.alpha), candidate.alpha);
            }
        }
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
                                   double cx, double top, int argb, float alpha) {
        int width = font.width(text);
        double left = cx - width / 2.0;
        int drawX = (int) Math.floor(left);
        int drawY = (int) Math.floor(top);
        float subpixelX = (float) (left - drawX);
        float subpixelY = (float) (top - drawY);

        g.pose().pushMatrix();
        g.pose().translate(subpixelX, subpixelY);
        if (config.showLabelBackdrop()) {
            int backdropTop = drawY - BACKDROP_PAD_Y;
            int backdropBottom = drawY + font.lineHeight - 1 + BACKDROP_PAD_Y;
            g.fill(drawX - BACKDROP_PAD_X, backdropTop,
                    drawX + width + BACKDROP_PAD_X, backdropBottom,
                    withAlpha(LABEL_BACKDROP_ARGB, alpha));
        }
        // drawString's shadow flag stays on in both modes -- without the backdrop the
        // drop shadow is doing all the work keeping text readable against bright biomes.
        g.drawString(font, text, drawX, drawY, argb, true);
        g.pose().popMatrix();
    }

    private String labelFor(WaypointGroup g, int i, Waypoint w, State state) {
        if (w.hasName()) return w.name();
        if (g.loadMode() == WaypointGroup.LoadMode.STATIC) {
            return indexLabel(i);
        }
        return state == State.CURRENT
                ? nextLabel(i, g.size())
                : indexLabel(i);
    }

    private String indexLabel(int i) {
        int number = i + 1;
        if (number <= 0 || number >= INDEX_LABEL_CACHE_MAX) return "#" + number;

        String cached = indexLabelCache[number];
        if (cached == null) {
            cached = "#" + number;
            indexLabelCache[number] = cached;
        }
        return cached;
    }

    private String nextLabel(int i, int size) {
        int number = i + 1;
        int slot = (31 * number + size) & (NEXT_LABEL_CACHE_SIZE - 1);
        if (nextLabelIndexes[slot] == number && nextLabelSizes[slot] == size) {
            return nextLabelCache[slot];
        }

        String label = "Next (" + number + "/" + size + ")";
        nextLabelIndexes[slot] = number;
        nextLabelSizes[slot] = size;
        nextLabelCache[slot] = label;
        return label;
    }

    private static State stateFor(WaypointGroup group, int i, int currentIdx) {
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) return State.CURRENT;
        if (i < currentIdx) return State.COMPLETED;
        if (i == currentIdx) return State.CURRENT;
        return State.UPCOMING;
    }

    private boolean shouldHideStaticReached(WaypointGroup group, int index) {
        return config.hideReachedStaticWaypointsUntilCycleComplete()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && group.isStaticWaypointReached(index);
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

        double dx = waypoint.x() + 0.5 - camPos.x;
        double dy = waypoint.y() + 0.5 - camPos.y;
        double dz = waypoint.z() + 0.5 - camPos.z;
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

    private static int withAlpha(int argb, float alphaScale) {
        float clamped = Math.max(0.0f, Math.min(1.0f, alphaScale));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamped);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private enum State {
        COMPLETED(0.25f),
        CURRENT(1.0f),
        UPCOMING(0.65f);

        final float alpha;
        State(float a) { this.alpha = a; }
    }

    private static final class LabelCandidate {
        String name;
        String distance;
        double screenX;
        double screenY;
        double distanceSquared;
        int nameColor;
        float alpha;

        void set(String name, String distance, double screenX, double screenY,
                 double distanceSquared, int nameColor, float alpha) {
            this.name = name;
            this.distance = distance;
            this.screenX = screenX;
            this.screenY = screenY;
            this.distanceSquared = distanceSquared;
            this.nameColor = nameColor;
            this.alpha = alpha;
        }
    }
}
