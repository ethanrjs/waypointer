package dev.ethan.waypointer.dungeon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.render.RenderHelpers;
import dev.ethan.waypointer.render.WaypointerRenderPipelines;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Draws every dungeon waypoint and its child highlights in the world,
 * transformed from room-local to world coordinates against the currently
 * detected room.
 *
 * <p>Two render paths, mirroring {@code WaypointRenderer}:
 *
 * <ul>
 *   <li>3D outlines / fills via Fabric's end-main world render event.</li>
 *   <li>2D labels via {@link HudElementRegistry} so they always face the
 *       camera and stay legible against any biome backdrop.</li>
 * </ul>
 *
 * <p>Highlights are <i>visually distinct</i> from their parent waypoint:
 * the parent renders at {@link #PARENT_ALPHA}, children render at
 * {@link #CHILD_ALPHA}. The colour difference plus the smaller alpha makes
 * it clear at a glance which cube is "go here" and which cubes are
 * "interact with these blocks". This is the visible side of issue #9's
 * one-to-many waypoint-to-highlights relationship.
 */
public final class DungeonHighlightRenderer implements HudElement {

    private static final Identifier LABEL_HUD_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "dungeon_waypoint_labels");

    private static final float PARENT_ALPHA = 1.0f;
    private static final float CHILD_ALPHA = 0.85f;
    private static final float FOUND_ALPHA = 0.25f;
    private static final float UPCOMING_ALPHA = 0.55f;
    private static final float FILLED_ALPHA_SCALE = 0.30f;

    /** How high above a parent waypoint cube the 2D label anchors. Same lift as the main renderer. */
    private static final double LABEL_LIFT = 1.6;

    /** ARGB for waypoint name text. Slightly cooler than pure white so it reads as "secondary" relative to main waypoints. */
    private static final int NAME_ARGB = 0xFFEAF8FF;
    private static final int LABEL_BACKDROP_ARGB = 0xB0000000;
    private static final int BACKDROP_PAD_X = 2;
    private static final int BACKDROP_PAD_Y = 1;

    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final DungeonRouteSession session;
    private final ArrayList<RenderBox> renderBoxes = new ArrayList<>();
    private final int[] labelWorldScratch = new int[3];
    private int renderBoxCount;

    public DungeonHighlightRenderer(DungeonStateTracker tracker, DungeonConfig config,
                                    DungeonRouteSession session) {
        this.tracker = tracker;
        this.config = config;
        this.session = session;
    }

    public void install() {
        WorldRenderEvents.END_MAIN.register(this::onWorldRender);
        // Attach BEFORE the chat layer so the F1 hide-GUI toggle hides the
        // labels in the same way the main renderer does -- consistent UX
        // for the player.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, LABEL_HUD_ID, this);
    }

    // ---- world-space outlines + fills ---------------------------------

    private void onWorldRender(WorldRenderContext ctx) {
        if (!config.enabled()) return;
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;

        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);
        boolean drawRoomBounds = config.drawRoomBounds();
        if (waypoints.isEmpty() && !drawRoomBounds) return;
        collectRenderBoxes(room, waypoints);
        if (renderBoxCount == 0 && !drawRoomBounds) return;

        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return;

        PoseStack ps = ctx.matrices();
        if (ps == null) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // Two-pass batching: fills first, lines second. The shared
        // BufferSource invalidates one VertexConsumer when the next
        // RenderType is bound, so we have to flush each pipeline on its
        // own getBuffer/endBatch cycle. This is the same constraint the
        // main waypoint renderer respects -- see comment in
        // WaypointRenderer#onWorldRender for the full background.
        emitFilledBatch(ps, buffers);
        emitLineBatch(ps, buffers, room, drawRoomBounds);

        ps.popPose();
    }

    private void collectRenderBoxes(DungeonRoom room, List<DungeonWaypoint> waypoints) {
        renderBoxCount = 0;
        boolean showSecrets = config.showSecretWaypoints();
        boolean showHighlights = config.showHighlights();
        boolean showFound = config.showFoundSecrets();
        boolean activeOnly = "ACTIVE".equalsIgnoreCase(config.routeRenderMode());
        if (!showSecrets && !showHighlights) return;

        for (DungeonWaypoint wp : waypoints) {
            DungeonRouteSession.Status status = session.status(room, wp);
            if (!shouldRender(status, showFound, activeOnly)) continue;

            float parentAlpha = parentAlpha(status);
            float childAlpha = parentAlpha == FOUND_ALPHA
                    ? FOUND_ALPHA
                    : Math.min(parentAlpha, CHILD_ALPHA);
            if (showSecrets) {
                addRenderBox(room, wp.x(), wp.y(), wp.z(), wp.color(),
                        parentAlpha, DungeonHighlightStyle.OUTLINE_FILLED);
            }
            if (showHighlights) {
                for (DungeonHighlight h : wp.highlights()) {
                    int color = h.hasOwnColor() ? h.color() : wp.color();
                    addRenderBox(room, h.x(), h.y(), h.z(), color, childAlpha, h.style());
                }
            }
        }
    }

    private void addRenderBox(DungeonRoom room, int rx, int ry, int rz,
                              int color, float alpha, DungeonHighlightStyle style) {
        RenderBox box = nextRenderBox();
        DungeonMapMath.relativeToActual(room.direction(), room.physicalCornerX(),
                room.physicalCornerZ(), rx, ry, rz, box.world);
        box.color = color;
        box.alpha = alpha;
        box.style = style;
    }

    private RenderBox nextRenderBox() {
        if (renderBoxCount == renderBoxes.size()) {
            renderBoxes.add(new RenderBox());
        }
        return renderBoxes.get(renderBoxCount++);
    }

    private void emitFilledBatch(PoseStack ps, MultiBufferSource buffers) {
        RenderType type = WaypointerRenderPipelines.quadsThroughWalls();
        VertexConsumer quads = buffers.getBuffer(type);
        boolean any = false;
        for (int i = 0; i < renderBoxCount; i++) {
            RenderBox box = renderBoxes.get(i);
            if (box.style != DungeonHighlightStyle.OUTLINE) {
                emitFill(quads, ps, box.world[0], box.world[1], box.world[2],
                        box.color, box.alpha);
                any = true;
            }
        }
        if (any) RenderHelpers.endBatch(buffers, type);
    }

    private void emitLineBatch(PoseStack ps, MultiBufferSource buffers,
                               DungeonRoom room, boolean drawRoomBounds) {
        RenderType type = WaypointerRenderPipelines.linesThroughWalls();
        VertexConsumer lines = buffers.getBuffer(type);
        boolean any = false;
        if (drawRoomBounds) {
            emitRoomBounds(lines, ps, room);
            any = true;
        }
        for (int i = 0; i < renderBoxCount; i++) {
            RenderBox box = renderBoxes.get(i);
            if (box.style != DungeonHighlightStyle.FILLED) {
                emitOutline(lines, ps, box.world[0], box.world[1], box.world[2],
                        box.color, box.alpha);
                any = true;
            }
        }
        if (any) RenderHelpers.endBatch(buffers, type);
    }

    private static void emitOutline(VertexConsumer lines, PoseStack ps, int x, int y, int z, int color, float alpha) {
        RenderHelpers.emitLineBox(lines, ps, x, y, z, x + 1f, y + 1f, z + 1f, color, alpha);
    }

    private static void emitFill(VertexConsumer quads, PoseStack ps, int x, int y, int z, int color, float alpha) {
        RenderHelpers.emitFilledBox(quads, ps, x, y, z, x + 1f, y + 1f, z + 1f, color, alpha * FILLED_ALPHA_SCALE);
    }

    private static void emitRoomBounds(VertexConsumer lines, PoseStack ps, DungeonRoom room) {
        // Lift the bounds box slightly off the floor so it doesn't z-fight
        // with the actual floor blocks in the dungeon.
        float y0 = 65f, y1 = 80f;
        for (Long packed : room.segments()) {
            int sx = DungeonRoom.segmentX(packed);
            int sz = DungeonRoom.segmentZ(packed);
            RenderHelpers.emitLineBox(lines, ps,
                    sx, y0, sz,
                    sx + DungeonMapMath.SEGMENT_BLOCKS, y1, sz + DungeonMapMath.SEGMENT_BLOCKS,
                    0xFFFF80, 0.6f);
        }
    }

    // ---- 2D labels ----------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker tick) {
        if (!config.enabled() || !config.showSecretWaypoints()) return;
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;
        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);
        if (waypoints.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = renderer.getMainCamera();
        if (!camera.isInitialized()) return;
        Font font = mc.font;
        Vec3 cam = camera.position();
        Vector3fc fwd = camera.forwardVector();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        boolean showFound = config.showFoundSecrets();
        boolean activeOnly = "ACTIVE".equalsIgnoreCase(config.routeRenderMode());

        for (DungeonWaypoint wp : waypoints) {
            DungeonRouteSession.Status status = session.status(room, wp);
            if (!shouldRender(status, showFound, activeOnly)) continue;
            String name = wp.hasName() ? wp.name() : wp.category().id;
            if (status == DungeonRouteSession.Status.CURRENT) {
                name = "Next: " + name;
            }
            DungeonMapMath.relativeToActual(room.direction(), room.physicalCornerX(),
                    room.physicalCornerZ(), wp.x(), wp.y(), wp.z(), labelWorldScratch);
            double ax = labelWorldScratch[0] + 0.5;
            double ay = labelWorldScratch[1] + LABEL_LIFT;
            double az = labelWorldScratch[2] + 0.5;
            double rx = ax - cam.x, ry = ay - cam.y, rz = az - cam.z;
            // Behind-camera cull -- the projection helper still divides by w
            // for points behind the eye, so we have to gate on the dot
            // product against the forward vector ourselves.
            if (rx * fwd.x() + ry * fwd.y() + rz * fwd.z() <= 0) continue;
            Vec3 ndc = renderer.projectPointToScreen(new Vec3(ax, ay, az));
            if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y)) continue;
            int sx = (int) Math.round((ndc.x * 0.5 + 0.5) * sw);
            int sy = (int) Math.round((0.5 - ndc.y * 0.5) * sh);
            drawCenteredLabel(g, font, name, sx, sy);
        }
    }

    private static boolean shouldRender(DungeonRouteSession.Status status,
                                        boolean showFound, boolean activeOnly) {
        if (status == DungeonRouteSession.Status.FOUND && !showFound) return false;
        return !activeOnly || status == DungeonRouteSession.Status.CURRENT;
    }

    private static float parentAlpha(DungeonRouteSession.Status status) {
        return switch (status) {
            case FOUND -> FOUND_ALPHA;
            case CURRENT -> PARENT_ALPHA;
            case UPCOMING -> UPCOMING_ALPHA;
        };
    }

    private void drawCenteredLabel(GuiGraphics g, Font font, String text, int cx, int top) {
        int width = font.width(text);
        int left = cx - width / 2;
        g.fill(left - BACKDROP_PAD_X, top - BACKDROP_PAD_Y,
                left + width + BACKDROP_PAD_X, top + font.lineHeight - 1 + BACKDROP_PAD_Y,
                LABEL_BACKDROP_ARGB);
        g.drawString(font, text, left, top, NAME_ARGB, true);
    }

    private static final class RenderBox {
        final int[] world = new int[3];
        int color;
        float alpha;
        DungeonHighlightStyle style;
    }
}
