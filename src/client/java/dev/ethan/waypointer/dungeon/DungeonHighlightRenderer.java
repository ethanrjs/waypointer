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
        if (waypoints.isEmpty() && !config.drawRoomBounds()) return;

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
        emitFilledBatch(ps, buffers, room, waypoints);
        emitLineBatch(ps, buffers, room, waypoints);

        ps.popPose();
    }

    private void emitFilledBatch(PoseStack ps, MultiBufferSource buffers,
                                 DungeonRoom room, List<DungeonWaypoint> waypoints) {
        RenderType type = WaypointerRenderPipelines.quadsThroughWalls();
        VertexConsumer quads = buffers.getBuffer(type);
        boolean any = false;
        for (DungeonWaypoint wp : waypoints) {
            if (!shouldRender(wp, room)) continue;
            float parentAlpha = parentAlpha(wp, room);
            float childAlpha = parentAlpha == FOUND_ALPHA ? FOUND_ALPHA : CHILD_ALPHA;
            if (config.showSecretWaypoints()) {
                int[] world = DungeonMapMath.relativeToActual(
                        room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                        wp.x(), wp.y(), wp.z());
                emitFill(quads, ps, world[0], world[1], world[2], wp.color(), parentAlpha);
                any = true;
            }
            if (config.showHighlights()) {
                for (DungeonHighlight h : wp.highlights()) {
                    if (h.style() == DungeonHighlightStyle.OUTLINE) continue;
                    int[] world = DungeonMapMath.relativeToActual(
                            room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                            h.x(), h.y(), h.z());
                    int color = h.hasOwnColor() ? h.color() : wp.color();
                    emitFill(quads, ps, world[0], world[1], world[2], color, childAlpha);
                    any = true;
                }
            }
        }
        if (any) RenderHelpers.endBatch(buffers, type);
    }

    private void emitLineBatch(PoseStack ps, MultiBufferSource buffers,
                               DungeonRoom room, List<DungeonWaypoint> waypoints) {
        RenderType type = WaypointerRenderPipelines.linesThroughWalls();
        VertexConsumer lines = buffers.getBuffer(type);
        boolean any = false;
        if (config.drawRoomBounds()) {
            emitRoomBounds(lines, ps, room);
            any = true;
        }
        for (DungeonWaypoint wp : waypoints) {
            if (!shouldRender(wp, room)) continue;
            float parentAlpha = parentAlpha(wp, room);
            float childAlpha = parentAlpha == FOUND_ALPHA ? FOUND_ALPHA : CHILD_ALPHA;
            if (config.showSecretWaypoints()) {
                int[] world = DungeonMapMath.relativeToActual(
                        room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                        wp.x(), wp.y(), wp.z());
                emitOutline(lines, ps, world[0], world[1], world[2], wp.color(), parentAlpha);
                any = true;
            }
            if (config.showHighlights()) {
                for (DungeonHighlight h : wp.highlights()) {
                    if (h.style() == DungeonHighlightStyle.FILLED) continue;
                    int[] world = DungeonMapMath.relativeToActual(
                            room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                            h.x(), h.y(), h.z());
                    int color = h.hasOwnColor() ? h.color() : wp.color();
                    emitOutline(lines, ps, world[0], world[1], world[2], color, childAlpha);
                    any = true;
                }
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

        for (DungeonWaypoint wp : waypoints) {
            if (!shouldRender(wp, room)) continue;
            String name = wp.hasName() ? wp.name() : wp.category().id;
            if (session.status(room, wp) == DungeonRouteSession.Status.CURRENT) {
                name = "Next: " + name;
            }
            int[] world = DungeonMapMath.relativeToActual(
                    room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                    wp.x(), wp.y(), wp.z());
            double ax = world[0] + 0.5;
            double ay = world[1] + LABEL_LIFT;
            double az = world[2] + 0.5;
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

    private boolean shouldRender(DungeonWaypoint waypoint, DungeonRoom room) {
        DungeonRouteSession.Status status = session.status(room, waypoint);
        if (status == DungeonRouteSession.Status.FOUND && !config.showFoundSecrets()) return false;
        return !"ACTIVE".equalsIgnoreCase(config.routeRenderMode())
                || status == DungeonRouteSession.Status.CURRENT;
    }

    private float parentAlpha(DungeonWaypoint waypoint, DungeonRoom room) {
        return switch (session.status(room, waypoint)) {
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
}
