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
import org.joml.Vector3fc;

/**
 * Draws every active waypoint as an outlined cube (world-space) plus a 2D label
 * anchored over it (screen-space).
 *
 * State-driven coloring:
 *   - Completed (i < currentIndex): dim alpha, hidden if its FLAG_HIDE_BEACON is set.
 *   - Current   (i == currentIndex): full alpha, label always visible.
 *   - Upcoming  (i >  currentIndex): mid alpha.
 *
 * <p><b>Why two render paths?</b> Minecraft 1.21.9 reworked world-space text to go
 * through an {@code OrderedSubmitNodeCollector} queue, and neither the old
 * {@code Font#drawInBatch} path nor the new {@code queue.submitText} call is
 * reliably producing pixels in our harness. Rather than keep chasing the
 * world-space path, labels now render as 2D HUD text: we project each waypoint's
 * world anchor to the screen via {@link GameRenderer#projectPointToScreen(Vec3)}
 * and draw the label at that pixel position. Always facing the player is then
 * automatic -- the text is literally in screen space -- and the vanilla GUI
 * font pipeline handles glyph batching the way we know works.
 *
 * <p>Cube outlines stay on the world-space path because our custom line pipeline
 * already uploads its own vertex buffers and is working correctly.
 *
 * <p>Load-mode aware: {@code STATIC} groups render every waypoint, {@code SEQUENCE}
 * groups render only the prev/current/next triple (delegated to
 * {@link WaypointGroup#forEachVisibleIndex}). The
 * {@link WaypointerConfig#windowedRendering() windowedRendering} flag forces
 * every group onto the prev/current/next window to cut label clutter on
 * dense static routes without changing their navigation behavior.
 */
public final class WaypointRenderer implements HudElement {

    /** Namespace/path for the Fabric HUD layer this renderer installs. */
    private static final Identifier LABEL_HUD_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "waypoint_labels");

    /**
     * Vertical lift (blocks) above the waypoint's bottom corner where the label's
     * world-space anchor sits. Matches the old billboarded placement so user-facing
     * positioning is unchanged from the previous renderer.
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

    /** Horizontal padding (screen pixels) added to the backdrop around the text. */
    private static final int BACKDROP_PAD_X = 2;

    /** Vertical padding (screen pixels) added to the backdrop around the text. */
    private static final int BACKDROP_PAD_Y = 1;

    /** Gap between the name row and the distance row below it. */
    private static final int DISTANCE_ROW_GAP = 1;

    /**
     * Cap on the pre-baked distance table. 0..127m covers every common
     * on-screen case (beyond that the label usually isn't readable anyway),
     * and the 128-slot array is cheap to hold in memory. Cache misses fall
     * back to a shared {@link StringBuilder} rather than {@code Integer.toString}
     * so we still avoid a per-frame allocation for the long tail.
     */
    private static final int DISTANCE_CACHE_MAX = 128;
    private static final String[] DISTANCE_CACHE;
    static {
        DISTANCE_CACHE = new String[DISTANCE_CACHE_MAX];
        for (int i = 0; i < DISTANCE_CACHE_MAX; i++) DISTANCE_CACHE[i] = i + "m";
    }

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    /**
     * Reusable scratch buffer for the fallback distance formatter. Safe because
     * {@link #render} only runs on the client/render thread; never escape this
     * reference from a render frame.
     */
    private final StringBuilder distanceScratch = new StringBuilder(8);

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

    private void onWorldRender(WorldRenderContext ctx) {
        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return;

        WaypointerConfig.BoxStyle style = config.boxStyle();
        boolean drawLines = style != WaypointerConfig.BoxStyle.FILLED;
        boolean drawFill  = style != WaypointerConfig.BoxStyle.OUTLINED;
        if (!drawLines && !drawFill) return;

        PoseStack ps = ctx.matrices();
        Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();

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
        if (drawFill) {
            RenderType quadType = WaypointerRenderPipelines.quadsThroughWalls();
            VertexConsumer quads = buffers.getBuffer(quadType);
            for (WaypointGroup g : groups) {
                emitBoxes(ps, null, quads, g);
            }
            RenderHelpers.endBatch(buffers, quadType);
        }
        if (drawLines) {
            RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
            VertexConsumer lines = buffers.getBuffer(lineType);
            for (WaypointGroup g : groups) {
                emitBoxes(ps, lines, null, g);
            }
            RenderHelpers.endBatch(buffers, lineType);
        }

        ps.popPose();
    }

    private void emitBoxes(PoseStack ps, VertexConsumer lines, VertexConsumer quads, WaypointGroup g) {
        int currentIdx = g.currentIndex();
        boolean showCompleted = config.showCompleted();
        float beaconOpacity = (float) config.beaconOpacity();
        boolean windowed = config.windowedRendering();

        g.forEachVisibleIndex(i -> {
            Waypoint w = g.get(i);
            State state = stateFor(i, currentIdx);
            if (state == State.COMPLETED && (!showCompleted || w.hasFlag(Waypoint.FLAG_HIDE_BEACON))) return;

            float alpha = state.alpha * beaconOpacity;
            float x = w.x(), y = w.y(), z = w.z();
            if (quads != null) {
                RenderHelpers.emitFilledBox(quads, ps, x, y, z, x + 1f, y + 1f, z + 1f,
                        w.color(), alpha * FILLED_ALPHA_SCALE);
            }
            if (lines != null) {
                RenderHelpers.emitLineBox(lines, ps, x, y, z, x + 1f, y + 1f, z + 1f, w.color(), alpha);
            }
        }, windowed);
    }

    // ---- HUD path: 2D labels projected from world anchors --------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker tick) {
        if (!config.showWaypointNames()) return;
        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = renderer.getMainCamera();
        if (!camera.isInitialized()) return;

        Font font = mc.font;
        Vec3 camPos = camera.position();
        Vector3fc forward = camera.forwardVector();
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();

        for (WaypointGroup group : groups) {
            drawGroupLabels(g, font, renderer, camPos, forward, screenW, screenH, group);
        }
    }

    private void drawGroupLabels(GuiGraphics g, Font font, GameRenderer renderer,
                                 Vec3 camPos, Vector3fc forward, int screenW, int screenH,
                                 WaypointGroup group) {
        int currentIdx = group.currentIndex();
        boolean showCompleted = config.showCompleted();
        boolean windowed = config.windowedRendering();

        group.forEachVisibleIndex(i -> {
            Waypoint w = group.get(i);
            State state = stateFor(i, currentIdx);
            if (state == State.COMPLETED && (!showCompleted || w.hasFlag(Waypoint.FLAG_HIDE_BEACON))) return;
            if (w.hasFlag(Waypoint.FLAG_HIDE_NAME)) return;

            double ax = w.x() + 0.5;
            double ay = w.y() + LABEL_ANCHOR_LIFT;
            double az = w.z() + 0.5;
            double rx = ax - camPos.x, ry = ay - camPos.y, rz = az - camPos.z;

            // Behind-camera rejection. GameRenderer#projectPointToScreen uses
            // Matrix4f#transformProject which still divides by w even when w is
            // negative, so points behind the camera get reflected into valid
            // NDC and would draw a phantom label on the wrong side of the view.
            if (rx * forward.x() + ry * forward.y() + rz * forward.z() <= 0) return;

            Vec3 ndc = renderer.projectPointToScreen(new Vec3(ax, ay, az));
            if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y)) return;

            int sx = (int) Math.round((ndc.x * 0.5 + 0.5) * screenW);
            int sy = (int) Math.round((0.5 - ndc.y * 0.5) * screenH);

            String name = labelFor(group, i, w, state);
            int distance = (int) Math.sqrt(rx * rx + ry * ry + rz * rz);

            drawCenteredLabel(g, font, name, sx, sy, NAME_ARGB);
            drawCenteredLabel(g, font, distanceString(distance),
                    sx, sy + font.lineHeight + DISTANCE_ROW_GAP, DISTANCE_ARGB);
        }, windowed);
    }

    /**
     * Format a distance as {@code "<n>m"} without allocating for the common case.
     * 0..127m hits the pre-baked table; beyond that we reuse a single
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
     * <p>Computes {@code font.width(text)} once and threads it through: the
     * backdrop, the half-width, and the {@code drawString} call all reused the
     * same value, saving two redundant glyph-table lookups per label.
     */
    private void drawCenteredLabel(GuiGraphics g, Font font, String text,
                                   int cx, int top, int argb) {
        int width = font.width(text);
        int halfWidth = width / 2;
        int left = cx - halfWidth;

        if (config.showLabelBackdrop()) {
            int backdropTop = top - BACKDROP_PAD_Y;
            int backdropBottom = top + font.lineHeight - 1 + BACKDROP_PAD_Y;
            g.fill(left - BACKDROP_PAD_X, backdropTop,
                    left + width + BACKDROP_PAD_X, backdropBottom,
                    LABEL_BACKDROP_ARGB);
        }
        // drawString's shadow flag stays on in both modes -- without the backdrop the
        // drop shadow is doing all the work keeping text readable against bright biomes.
        g.drawString(font, text, left, top, argb, true);
    }

    private static String labelFor(WaypointGroup g, int i, Waypoint w, State state) {
        if (w.hasName()) return w.name();
        return state == State.CURRENT
                ? "Next (" + (i + 1) + "/" + g.size() + ")"
                : "#" + (i + 1);
    }

    private static State stateFor(int i, int currentIdx) {
        if (i < currentIdx) return State.COMPLETED;
        if (i == currentIdx) return State.CURRENT;
        return State.UPCOMING;
    }

    private enum State {
        COMPLETED(0.25f),
        CURRENT(1.0f),
        UPCOMING(0.65f);

        final float alpha;
        State(float a) { this.alpha = a; }
    }
}
