package com.babbur.waypointer.render;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.crystal.MetalDetectorController;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.SequenceRoleColor;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointVisibility;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

public final class TracerRenderer implements HudElement {

    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "tracers");

    private static final float TEMP_FOCUS_TRACER_ALPHA_FLOOR = 0.5f;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final DungeonConfig dungeonConfig;
    private final double[] waypointBoxBoundsScratch = new double[6];
    private final WorldScreenProjector projector = new WorldScreenProjector();
    private final double[] screenScratch = new double[2];

    public TracerRenderer(ActiveGroupManager manager, WaypointerConfig config) {
        this(manager, config, null);
    }

    public TracerRenderer(ActiveGroupManager manager, WaypointerConfig config,
                          DungeonConfig dungeonConfig) {
        this.manager = manager;
        this.config = config;
        this.dungeonConfig = dungeonConfig;
    }

    public void install() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ID, this);
    }

    private Waypoint straightTracerTarget(WaypointGroup group, boolean tempFocus,
                                          LocalPlayer player, double nearHideDistanceSq,
                                          boolean recordDecision, boolean outgoing) {
        if (MetalDetectorController.isDetectorGroup(group) && !tracersEnabled(group, config, dungeonConfig)) return null;
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        if (outgoing && (fade == null || !fade.active()
                || group.isWaypointDisabled(fade.outgoing())
                || WaypointWorldRenderer.shouldForceHideReachedWaypoint(
                        fade.outgoing(), group.currentIndex(), group.get(fade.outgoing())))) return null;
        if (!tempFocus && !tracersEnabled(group, config, dungeonConfig)) {
            if (recordDecision) RenderDiagnostics.recordNoStraightTracer(group, "tracer disabled");
            return null;
        }
        if (RenderDiagnostics.shouldSuppressStraightTracer(group)) {
            if (recordDecision) RenderDiagnostics.recordStraightTracerSuppressed(group);
            return null;
        }
        if (!tempFocus
                && config.hideTracerOnStaticRoutes()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            if (recordDecision) {
                RenderDiagnostics.recordNoStraightTracer(group, "static-route tracers hidden");
            }
            return null;
        }
        Waypoint target = outgoing ? group.get(fade.outgoing()) : group.current();
        if (!tempFocus
                && !group.temp()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && !isDungeonRoomRoute(group)
                && player != null) {
            double maxDistance = config.maxStaticWaypointRenderDistance();
            target = nearestStaticTracerTarget(
                    group, player.getX(), player.getY(), player.getZ(), nearHideDistanceSq,
                    config.hideReachedStaticWaypointsUntilCycleComplete(),
                    maxDistance > 0.0 ? maxDistance * maxDistance : 0.0);
        }
        if (target != null && CompassMarkerState.arrived(target)) return null;
        if (target == null) {
            if (recordDecision) RenderDiagnostics.recordNoStraightTracer(group, "no current target");
            return null;
        }
        if (shouldHideNearPlayer(target, player, nearHideDistanceSq)) {
            if (recordDecision) {
                RenderDiagnostics.recordNoStraightTracer(group, "straight tracer hidden near player");
            }
            return null;
        }
        return target;
    }

    private static boolean isDungeonRoomRoute(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    static Waypoint nearestStaticTracerTarget(WaypointGroup group,
                                              double playerX, double playerY, double playerZ,
                                              double nearHideDistanceSq,
                                              boolean hideReached,
                                              double maxDistanceSq) {
        if (group == null || group.loadMode() != WaypointGroup.LoadMode.STATIC) return null;

        Waypoint nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (int index = 0; index < group.size(); index++) {
            if (group.isWaypointDisabled(index)) continue;
            if (hideReached && group.isStaticWaypointReached(index)) continue;
            Waypoint waypoint = group.get(index);
            if (CompassMarkerState.arrived(waypoint)) continue;
            double dx = waypoint.centerX() - playerX;
            double dy = waypoint.centerY() - playerY;
            double dz = waypoint.centerZ() - playerZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (nearHideDistanceSq > 0.0 && distanceSq <= nearHideDistanceSq) continue;
            if (maxDistanceSq > 0.0 && distanceSq > maxDistanceSq) continue;
            if (distanceSq < nearestDistanceSq) {
                nearest = waypoint;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
        var groups = manager.activeGroups();
        for (WaypointGroup group : groups) WaypointSkipFade.observe(group, config);
        if (groups.isEmpty()) return;
        boolean tempFocus = manager.tempWaypointFocusActive();
        if (!tempFocus && groups.stream().noneMatch(
                group -> tracersEnabled(group, config, dungeonConfig))) {
            RenderDiagnostics.recordNoStraightTracer(groups, "tracer disabled");
            return;
        }

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
        if (alpha <= 0.0f) {
            RenderDiagnostics.recordNoStraightTracer(groups, "tracer opacity is zero");
            return;
        }
        double nearHideDistanceSq = nearHideDistanceSq();
        boolean matchWaypoint = config.matchTracerToWaypointColor();
        int overrideColor = config.tracerColor();
        double thickness = config.tracerThickness();

        for (WaypointGroup group : groups) {
            for (int pass = 0; pass < 2; pass++) {
                Waypoint target = straightTracerTarget(
                        group, tempFocus, player, nearHideDistanceSq, pass == 0, pass == 1);
                if (target == null) continue;
                WaypointWorldRenderer.populateWaypointRenderAnchor(
                        mc.level, target, waypointBoxBoundsScratch);
                double targetX = waypointBoxBoundsScratch[0];
                double targetY = waypointBoxBoundsScratch[1];
                double targetZ = waypointBoxBoundsScratch[2];
                boolean projected = projector.project(targetX, targetY, targetZ,
                        screenW, screenH, screenScratch);
                if (projected && !insideViewport(screenScratch[0], screenScratch[1], screenW, screenH)) {
                    projectDirectionToEdge(screenScratch[0] - fromX, screenScratch[1] - fromY,
                            screenW, screenH, screenScratch);
                } else if (!projected) {
                    projectOffscreenTarget(
                            camera, targetX, targetY, targetZ,
                            screenW, screenH, screenScratch);
                }

                int color = matchWaypoint ? resolvedTargetColor(group, target) : overrideColor;
                int argb = RenderHelpers.withAlpha(0xFF000000 | (color & 0xFFFFFF),
                        alpha * tracerFadeAlpha(group, pass == 1));
                RenderHelpers.drawScreenLine(g, fromX, fromY,
                        screenScratch[0], screenScratch[1], argb, thickness, config.renderAntialiasing());
                RenderDiagnostics.recordStraightTracerSubmitted(group);
            }
        }
    }

    private static float tracerFadeAlpha(WaypointGroup group, boolean outgoing) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        return fade == null ? 1 : fade.tracerAlpha(outgoing ? fade.outgoing() : group.currentIndex());
    }

    static boolean tracersEnabled(WaypointGroup group, WaypointerConfig config,
                                  DungeonConfig dungeonConfig) {
        if (MetalDetectorController.isDetectorGroup(group)
                && (group.size() != 1 || group.loadMode() != WaypointGroup.LoadMode.SEQUENCE)) return false;
        if (group != null
                && !group.temp()
                && group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                && dungeonConfig != null) {
            return dungeonConfig.showDungeonTracers();
        }
        return config != null && config.showTracer();
    }

    private int resolvedTargetColor(WaypointGroup group, Waypoint target) {
        return SequenceRoleColor.resolve(
                group,
                group == null ? -1 : target == group.current() ? group.currentIndex()
                        : WaypointSkipFade.get(group) != null ? WaypointSkipFade.get(group).outgoing()
                        : group.currentIndex(),
                config.colorSequenceWaypointsByRole(),
                config.sequencePreviousWaypointColor(),
                config.sequenceCurrentWaypointColor(),
                config.sequenceNextWaypointColor(),
                target == null ? 0 : target.color());
    }

    private static void projectOffscreenTarget(Camera camera,
                                               double targetX, double targetY, double targetZ,
                                               int screenW, int screenH,
                                               double[] out) {
        Vec3 cameraPos = camera.position();
        Vector3fc left = camera.leftVector();
        Vector3fc up = camera.upVector();

        double dx = targetX - cameraPos.x;
        double dy = targetY - cameraPos.y;
        double dz = targetZ - cameraPos.z;

        double screenDirX = -(dx * left.x() + dy * left.y() + dz * left.z());
        double screenDirY = -(dx * up.x() + dy * up.y() + dz * up.z());
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        projectOffscreenDirection(screenDirX, screenDirY, distance, screenW, screenH, out);
    }

    static void projectOffscreenDirection(double screenDirX, double screenDirY, double distance,
                                          int screenW, int screenH, double[] out) {
        if (Math.hypot(screenDirX, screenDirY) <= distance * 1.0e-5) {
            screenDirX = 0.0;
            screenDirY = 1.0;
        }
        projectDirectionToEdge(screenDirX, screenDirY, screenW, screenH, out);
    }

    static boolean insideViewport(double x, double y, int screenW, int screenH) {
        return x >= 0.0 && x <= screenW && y >= 0.0 && y <= screenH;
    }

    static void projectDirectionToEdge(double screenDirX, double screenDirY,
                                       int screenW, int screenH, double[] out) {
        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        double magnitude = Math.max(Math.abs(screenDirX), Math.abs(screenDirY));
        if (!Double.isFinite(magnitude) || magnitude == 0.0 || screenW <= 0 || screenH <= 0) {
            out[0] = centerX;
            out[1] = screenH;
            return;
        }
        screenDirX /= magnitude;
        screenDirY /= magnitude;
        double scale = Math.max(Math.abs(screenDirX) / centerX, Math.abs(screenDirY) / centerY);
        out[0] = Mth.clamp(centerX + screenDirX / scale, 0.0, screenW);
        out[1] = Mth.clamp(centerY + screenDirY / scale, 0.0, screenH);
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

}
