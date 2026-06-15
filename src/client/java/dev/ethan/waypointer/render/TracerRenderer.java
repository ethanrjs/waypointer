package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.WaypointVisibility;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

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
 * {@link WaypointRenderer} so the tracer pierces terrain; without that, the
 * tracer vanishes the moment you look at a wall between you and the current
 * waypoint.
 */
public final class TracerRenderer implements HudElement {

    private static final Identifier HUD_FALLBACK_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "iris_tracer_fallback");

    /**
     * Distance (in blocks) to push the tracer origin forward along the camera's
     * look vector. Large enough that the near end isn't clipped by the camera
     * near plane; small enough that the line still visually anchors to the
     * crosshair rather than floating in space ahead of the player.
     */
    private static final float CROSSHAIR_FORWARD = 0.4f;
    /** When temp-waypoint focus forces a tracer, opacity 0 would hide it entirely. */
    private static final float TEMP_FOCUS_TRACER_ALPHA_FLOOR = 0.5f;
    private static final float DEG_TO_RAD = (float) Math.PI / 180.0f;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final float[] tracerOriginDelta = new float[3];
    private final WorldScreenProjector projector = new WorldScreenProjector();
    private final double[] screenScratch = new double[2];

    public TracerRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        WorldRenderEvents.END_MAIN.register(this::onRender);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_FALLBACK_ID, this);
    }

    /*[[AI-FN-DOC
Function:
onRender.
Purpose:
Render world-space crosshair tracers from the camera toward each active group's current waypoint.
Why this exists:
The normal render path draws through-wall tracer lines in world space when shader fallback is not needed.
When to use:
Registered with WorldRenderEvents.END_MAIN by install. Do not call manually from HUD fallback rendering.
Inputs:
ctx is the Fabric world render context supplying matrices, buffer consumers, camera state, and tick timing.
Outputs:
No return value. Emits tracer line vertices when eligible targets exist.
Side effects:
Writes line vertices into the render buffer and ends the line batch.
Failure modes:
Missing matrices, buffers, no active groups, disabled tracer config, zero opacity, or hidden targets cause early returns or skips.
Important invariants:
Tracer endpoints must use waypoint.centerX/Y/Z so precise small waypoints are targeted at the rendered marker rather than the containing block center.
Internal logic:
Skip fallback mode and disabled states, prepare camera-relative rendering, compute the crosshair origin, iterate active groups, filter hidden targets, and emit lines to target centers.
Pseudocode:
if Iris fallback active, return
if no groups or tracer disabled without temp focus, return
compute alpha and bail when invisible
resolve matrices, camera, buffers
translate pose by negative camera position
compute crosshair origin delta
for each active group:
  skip static route if configured
  target = group.current
  skip null or near-hidden target
  lazily get line buffer
  emit line from crosshair origin to target center
end batch if used
Implementation notes:
Using center methods preserves existing behavior for block waypoints because their precise centers default to x/y/z + 0.5.
AI self-check:
Verify temp-focus tracer still renders even when normal tracer config is off.
]]*/
    private void onRender(WorldRenderContext ctx) {
        if (IrisShaderFallback.shouldUse(config)) return;

        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;
        boolean tempFocus = manager.tempWaypointFocusActive();
        if (!tempFocus && !config.showTracer()) return;
        float alpha = (float) config.tracerOpacity();
        if (tempFocus) {
            alpha = Math.max(alpha, TEMP_FOCUS_TRACER_ALPHA_FLOOR);
        }
        if (alpha <= 0.0f) return;

        PoseStack ps = ctx.matrices();
        if (ps == null) return;
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.position();
        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return;

        ps.pushPose();
        ps.translate(-camPos.x, -camPos.y, -camPos.z);

        // Push slightly forward along the camera vector so the start point sits at
        // the crosshair regardless of pitch, and so the near-plane doesn't clip it.
        LocalPlayer player = mc.player;
        writeTracerOriginDelta(mc, cam, player, tracerOriginDelta);
        // Keep the tracer origin in primitives so the per-group loop doesn't touch
        // the Vec3#add allocation path for each active group.
        float fromX = (float) camPos.x + tracerOriginDelta[0];
        float fromY = (float) camPos.y + tracerOriginDelta[1];
        float fromZ = (float) camPos.z + tracerOriginDelta[2];
        double nearHideDistanceSq = nearHideDistanceSq();
        // Matching the tracer to the live waypoint colour means gradient groups
        // draw a tracer whose hue advances with progress, and manually-coloured
        // checkpoints light their tracer in the same tint. The flat-override path
        // is still available for users who prefer one tracer colour across groups.
        boolean matchWaypoint = config.matchTracerToWaypointColor();
        int overrideColor = config.tracerColor();
        float thickness = (float) config.tracerThickness();
        RenderType lineType = null;
        VertexConsumer lines = null;

        for (WaypointGroup g : groups) {
            if (!tempFocus
                    && config.hideTracerOnStaticRoutes()
                    && g.loadMode() == WaypointGroup.LoadMode.STATIC) {
                continue;
            }
            Waypoint target = g.current();
            if (target == null) continue;
            if (shouldHideNearPlayer(target, player, nearHideDistanceSq)) continue;
            if (lines == null) {
                lineType = WaypointerRenderPipelines.linesThroughWalls();
                lines = buffers.getBuffer(lineType);
            }
            int color = matchWaypoint ? target.color() : overrideColor;
            RenderHelpers.emitLine(lines, ps,
                    fromX, fromY, fromZ,
                    (float) target.centerX(), (float) target.centerY(), (float) target.centerZ(),
                    color, alpha, thickness);
        }

        ps.popPose();
        if (lineType != null) {
            RenderHelpers.endBatch(buffers, lineType);
        }
    }

    @Override
    /*[[AI-FN-DOC
Function:
render.
Purpose:
Render HUD-space tracer fallback lines when shader conditions prevent reliable world-space tracer drawing.
Why this exists:
Some shader configurations hide or distort the world-space tracer, so the HUD fallback projects the target to screen space and draws a 2D line.
When to use:
Called by Fabric's HUD element pipeline after install attaches this renderer. Do not use for the normal world render path.
Inputs:
g is the GUI graphics context; tick is the delta tracker supplied by the HUD pipeline.
Outputs:
No return value. Draws screen-space tracer lines when fallback mode and target filters allow.
Side effects:
Draws 2D line geometry to the HUD.
Failure modes:
Disabled fallback, missing active groups, disabled tracer config, uninitialized camera, zero opacity, or hidden targets cause early return or skips.
Important invariants:
Projection must use waypoint.centerX/Y/Z so HUD fallback points at precise small waypoints consistently with the world renderer.
Internal logic:
Validate fallback and visibility settings, prepare the projector, iterate active groups, project target centers or compute offscreen target positions, then draw screen lines.
Pseudocode:
if fallback not active, return
if no groups or tracer disabled without temp focus, return
resolve player, renderer, camera
if camera uninitialized, return
prepare projector and screen center
for each active group:
  skip configured static routes
  target = group.current
  skip null or near-hidden target
  if center projection fails, project offscreen target
  draw centered HUD line to target screen point
Implementation notes:
The tick parameter is unused because camera/projector state already comes from Minecraft's current renderer.
AI self-check:
Verify the HUD fallback and world renderer use the same endpoint center.
]]*/
    public void render(GuiGraphics g, DeltaTracker tick) {
        if (!IrisShaderFallback.shouldUse(config)) return;

        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;
        boolean tempFocus = manager.tempWaypointFocusActive();
        if (!tempFocus && !config.showTracer()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = renderer.getMainCamera();
        if (!camera.isInitialized()) return;

        projector.prepare(renderer, camera);
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        double fromX = screenW / 2.0;
        double fromY = screenH / 2.0;
        float alpha = (float) config.tracerOpacity();
        if (tempFocus) {
            alpha = Math.max(alpha, TEMP_FOCUS_TRACER_ALPHA_FLOOR);
        }
        if (alpha <= 0.0f) return;
        double nearHideDistanceSq = nearHideDistanceSq();
        boolean matchWaypoint = config.matchTracerToWaypointColor();
        int overrideColor = config.tracerColor();
        double thickness = config.tracerThickness();

        for (WaypointGroup group : groups) {
            if (!tempFocus
                    && config.hideTracerOnStaticRoutes()
                    && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
                continue;
            }

            Waypoint target = group.current();
            if (target == null) continue;
            if (shouldHideNearPlayer(target, player, nearHideDistanceSq)) continue;
            if (!projector.project(target.centerX(), target.centerY(), target.centerZ(),
                    screenW, screenH, screenScratch)) {
                projectOffscreenTarget(camera, target, screenW, screenH, screenScratch);
            }

            int color = matchWaypoint ? target.color() : overrideColor;
            int argb = RenderHelpers.withAlpha(0xFF000000 | (color & 0xFFFFFF), alpha);
            WaypointRenderer.drawScreenLine(g, fromX, fromY,
                    screenScratch[0], screenScratch[1], argb, thickness);
        }
    }

    /*[[AI-FN-DOC
Function:
projectOffscreenTarget.
Purpose:
Estimate a screen-edge endpoint for a target that cannot be directly projected by the HUD fallback.
Why this exists:
When the waypoint is behind or outside the camera frustum, the fallback tracer should still point in the correct screen direction instead of disappearing abruptly.
When to use:
Use only from the HUD fallback when WorldScreenProjector.project returns false.
Inputs:
camera supplies position and orientation vectors; target is the waypoint to point toward; screenW/screenH are GUI dimensions; out is a double array with at least two slots for x/y output.
Outputs:
Writes the screen-edge x/y coordinate into out[0] and out[1].
Side effects:
Mutates the supplied out array.
Failure modes:
Degenerate direction vectors fall back to a vertical direction; non-finite or non-positive intersection distances fall back to one screen unit.
Important invariants:
Direction must be computed from target.centerX/Y/Z so precise small waypoint fallback tracers point toward the actual marker.
Internal logic:
Compute world delta from camera to target center, project that direction onto camera left/up vectors, normalize it to the nearest screen edge, and write the result.
Pseudocode:
cameraPos = camera.position
dx/dy/dz = target center minus camera position
screenDirX = negative dot delta with camera left
screenDirY = negative dot delta with camera up
if direction nearly zero, set screenDirY to 1
compute center screen point
find smallest positive t to an edge
if t invalid, set t to 1
out = center + direction * t
Implementation notes:
This is directional only; it does not need exact depth, just a stable edge hint.
AI self-check:
Verify target centers are used instead of block coordinates.
]]*/
    private static void projectOffscreenTarget(Camera camera, Waypoint target,
                                               int screenW, int screenH,
                                               double[] out) {
        Vec3 cameraPos = camera.position();
        Vector3fc left = camera.leftVector();
        Vector3fc up = camera.upVector();

        double dx = target.centerX() - cameraPos.x;
        double dy = target.centerY() - cameraPos.y;
        double dz = target.centerZ() - cameraPos.z;

        double screenDirX = -(dx * left.x() + dy * left.y() + dz * left.z());
        double screenDirY = -(dx * up.x() + dy * up.y() + dz * up.z());
        if (screenDirX * screenDirX + screenDirY * screenDirY < 1.0e-6) {
            screenDirY = 1.0;
        }

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        double t = Double.POSITIVE_INFINITY;
        if (screenDirX > 0.0) t = Math.min(t, (screenW - centerX) / screenDirX);
        else if (screenDirX < 0.0) t = Math.min(t, -centerX / screenDirX);
        if (screenDirY > 0.0) t = Math.min(t, (screenH - centerY) / screenDirY);
        else if (screenDirY < 0.0) t = Math.min(t, -centerY / screenDirY);
        if (!Double.isFinite(t) || t <= 0.0) t = 1.0;

        out[0] = centerX + screenDirX * t;
        out[1] = centerY + screenDirY * t;
    }

    private double nearHideDistanceSq() {
        return config.hideWaypointsNearPlayer()
                ? WaypointVisibility.squaredRadius(config.hideWaypointsNearRadius())
                : 0.0;
    }

    private static boolean shouldHideNearPlayer(Waypoint waypoint, LocalPlayer player,
                                                double nearHideDistanceSq) {
        return player != null
                && WaypointVisibility.isHiddenNearPlayer(
                        waypoint, player.getX(), player.getY(), player.getZ(), nearHideDistanceSq);
    }

    private static void writeTracerOriginDelta(Minecraft mc, Camera cam, LocalPlayer player,
                                               float[] out) {
        Vector3fc left = cam.leftVector();
        Vector3fc up = cam.upVector();
        Vector3fc forward = cam.forwardVector();

        out[0] = forward.x() * CROSSHAIR_FORWARD;
        out[1] = forward.y() * CROSSHAIR_FORWARD;
        out[2] = forward.z() * CROSSHAIR_FORWARD;
        if (player == null || !mc.options.bobView().get()) return;

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        ClientAvatarState avatar = player.avatarState();
        float walkPhase = avatar.getBackwardsInterpolatedWalkDistance(partialTick);
        float bob = avatar.getInterpolatedBob(partialTick);

        float bobX = Mth.sin(walkPhase * Mth.PI) * bob * 0.5f;
        float bobY = -Math.abs(Mth.cos(walkPhase * Mth.PI) * bob);
        float roll = Mth.sin(walkPhase * Mth.PI) * bob * 3.0f * DEG_TO_RAD;
        float pitch = Math.abs(Mth.cos(walkPhase * Mth.PI - 0.2f) * bob) * 5.0f * DEG_TO_RAD;

        // Vanilla view bob applies camera-local translation plus small roll/pitch
        // rotations. Solve the inverse transform for a point that should appear
        // directly under the crosshair after those bob transforms run.
        float localX = -bobX;
        float localY = -bobY;
        float localZ = -CROSSHAIR_FORWARD;

        float cosRoll = Mth.cos(-roll);
        float sinRoll = Mth.sin(-roll);
        float rolledX = localX * cosRoll - localY * sinRoll;
        float rolledY = localX * sinRoll + localY * cosRoll;

        float cosPitch = Mth.cos(-pitch);
        float sinPitch = Mth.sin(-pitch);
        float pitchedY = rolledY * cosPitch - localZ * sinPitch;
        float pitchedZ = rolledY * sinPitch + localZ * cosPitch;

        out[0] = -left.x() * rolledX + up.x() * pitchedY - forward.x() * pitchedZ;
        out[1] = -left.y() * rolledX + up.y() * pitchedY - forward.y() * pitchedZ;
        out[2] = -left.z() * rolledX + up.z() * pitchedY - forward.z() * pitchedZ;
    }

}
