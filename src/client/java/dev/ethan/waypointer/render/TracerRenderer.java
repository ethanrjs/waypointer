package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a thick line from the player's crosshair to the
 * {@link WaypointGroup#current()} waypoint of every active group.
 *
 * The line's origin is pushed a small distance forward along the camera's look
 * vector. Starting at raw {@code camera.position()} would put the near end behind
 * the near clip plane (or visually on top of the crosshair as a single pixel),
 * which is why the old implementation dropped the origin below the crosshair and
 * the user saw the tracer "start at the feet" from certain angles. Offsetting
 * along the look vector makes the line visibly emerge from the crosshair
 * regardless of view pitch.
 *
 * Shares {@link WaypointerRenderPipelines#linesThroughWalls()} with
 * {@link WaypointRenderer} so the tracer pierces terrain like the beacons do --
 * without that, the tracer vanishes the moment you look at a wall between you
 * and the current waypoint.
 */
public final class TracerRenderer {

    /**
     * Distance (in blocks) to push the tracer origin forward along the camera's
     * look vector. Large enough that the near end isn't clipped by the camera
     * near plane; small enough that the line still visually anchors to the
     * crosshair rather than floating in space ahead of the player.
     */
    private static final float CROSSHAIR_FORWARD = 0.4f;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    public TracerRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        WorldRenderEvents.END_MAIN.register(this::onRender);
    }

    private void onRender(WorldRenderContext ctx) {
        if (!config.showTracer()) return;
        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;

        PoseStack ps = ctx.matrices();
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = cam.position();
        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return;

        RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
        VertexConsumer lines = buffers.getBuffer(lineType);

        ps.pushPose();
        ps.translate(-camPos.x, -camPos.y, -camPos.z);

        // Push slightly forward along the player's view vector so the start point sits
        // at the crosshair regardless of pitch, and so the near-plane doesn't clip the
        // line. Using the LocalPlayer entity (rather than Camera) keeps us on a stable
        // Mojmap API that's present in every 1.21.x build of Minecraft.
        LocalPlayer player = Minecraft.getInstance().player;
        Vec3 look = player == null ? FORWARD : player.getViewVector(1.0f);
        // Keep the tracer origin in primitives so the per-group loop doesn't touch
        // the Vec3#add allocation path for each active group.
        float fromX = (float) (camPos.x + look.x * CROSSHAIR_FORWARD);
        float fromY = (float) (camPos.y + look.y * CROSSHAIR_FORWARD);
        float fromZ = (float) (camPos.z + look.z * CROSSHAIR_FORWARD);
        float alpha = (float) config.tracerOpacity();
        int overrideColor = config.tracerColor();
        boolean useOverride = overrideColor >= 0;

        for (WaypointGroup g : groups) {
            Waypoint target = g.current();
            if (target == null) continue;
            int color = useOverride ? overrideColor : target.color();
            RenderHelpers.emitLine(lines, ps,
                    fromX, fromY, fromZ,
                    target.x() + 0.5f, target.y() + 0.5f, target.z() + 0.5f,
                    color, alpha);
        }

        ps.popPose();
        RenderHelpers.endBatch(buffers, lineType);
    }

    // Fallback look vector when the player isn't available (e.g. during a brief world
    // load frame). Cached so we don't alloc a fresh Vec3 on every such frame.
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);
}
