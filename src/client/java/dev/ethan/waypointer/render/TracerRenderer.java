package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.compat.MinecraftCompat;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.WaypointVisibility;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
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
        LevelRenderEvents.COLLECT_SUBMITS.register(this::onRender);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_FALLBACK_ID, this);
    }

    private void onRender(LevelRenderContext ctx) {
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

        PoseStack ps = ctx.poseStack();
        if (ps == null) return;
        Minecraft mc = Minecraft.getInstance();
        Camera cam = MinecraftCompat.mainCamera(mc.gameRenderer);
        Vec3 camPos = cam.position();

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
        float renderAlpha = alpha;
        RenderType lineType = WaypointerRenderPipelines.linesThroughWalls();
        RenderSubmission.submit(ctx, ps, lineType, (lines, submittedPose) -> {
            for (WaypointGroup g : groups) {
                if (config.showDungeonEntryPathToFirstWaypoint()
                        && WaypointRenderer.shouldRenderDungeonEntryPath(g,
                        config.showDungeonEntryPathToFollowingWaypoints())) {
                    continue;
                }
                if (!tempFocus
                        && config.hideTracerOnStaticRoutes()
                        && g.loadMode() == WaypointGroup.LoadMode.STATIC) {
                    continue;
                }
                Waypoint target = g.current();
                if (target == null) continue;
                if (shouldHideNearPlayer(target, player, nearHideDistanceSq)) continue;
                int color = matchWaypoint ? target.color() : overrideColor;
                RenderHelpers.emitLine(lines, submittedPose,
                        fromX, fromY, fromZ,
                        (float) target.centerX(), (float) target.centerY(), (float) target.centerZ(),
                        color, renderAlpha, thickness);
            }
        });

        ps.popPose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
        if (!IrisShaderFallback.shouldUse(config)) return;

        var groups = manager.activeGroups();
        if (groups.isEmpty()) return;
        boolean tempFocus = manager.tempWaypointFocusActive();
        if (!tempFocus && !config.showTracer()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        GameRenderer renderer = mc.gameRenderer;
        Camera camera = MinecraftCompat.mainCamera(renderer);
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
            if (config.showDungeonEntryPathToFirstWaypoint()
                    && WaypointRenderer.shouldRenderDungeonEntryPath(group,
                    config.showDungeonEntryPathToFollowingWaypoints())) {
                continue;
            }
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
