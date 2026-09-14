package com.babbur.waypointer.render;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.crystal.CompassMarkerState;
import com.babbur.waypointer.crystal.CrystalHollowsStructureFolder;
import com.babbur.waypointer.crystal.MetalDetectorController;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointStyles;
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
    private final double[] lineStartScratch = new double[2];

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
                                          LocalPlayer player, Vec3 cameraPosition,
                                          boolean recordDecision, boolean outgoing) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        if (outgoing && (fade == null || !fade.active()
                || group.isWaypointDisabled(fade.outgoing())
                || WaypointWorldRenderer.shouldForceHideReachedWaypoint(
                        fade.outgoing(), group.currentIndex(), group.get(fade.outgoing())))) return null;
        if (RenderDiagnostics.shouldSuppressStraightTracer(group)) {
            if (recordDecision) RenderDiagnostics.recordStraightTracerSuppressed(group);
            return null;
        }
        Waypoint target = outgoing ? group.get(fade.outgoing()) : group.current();
        if (!tempFocus
                && !group.temp()
                && group.loadMode() == WaypointGroup.LoadMode.STATIC
                && !isDungeonRoomRoute(group)
                && player != null) {
            target = nearestStaticTracerTarget(
                    group, player.getX(), player.getY(), player.getZ(), 0,
                    config.hideReachedStaticWaypointsUntilCycleComplete(), 0,
                    waypoint -> {
                        WaypointerConfig style = WaypointStyles.resolve(config, group, waypoint);
                        double dx = waypoint.centerX() - player.getX();
                        double dy = waypoint.centerY() - player.getY();
                        double dz = waypoint.centerZ() - player.getZ();
                        double max = style.maxStaticWaypointRenderDistance();
                        return tracersEnabled(group, style, dungeonConfig)
                                && !style.hideTracerOnStaticRoutes()
                                && !shouldHideNearPlayer(waypoint, player, nearHideDistanceSq(style))
                                && WaypointWorldRenderer.withinRouteDistance(style, waypoint, cameraPosition)
                                && withinRouteDistance(dx, dy, dz, 0, max);
                    });
        }
        WaypointerConfig options = WaypointStyles.resolve(config, group, target);
        if (MetalDetectorController.isDetectorGroup(group) && !tracersEnabled(group, options, dungeonConfig)) return null;
        if (!tempFocus && (!tracersEnabled(group, options, dungeonConfig)
                || options.hideTracerOnStaticRoutes() && group.loadMode() == WaypointGroup.LoadMode.STATIC)) return null;
        if (target != null && ((CompassMarkerState.arrived(target) || CompassMarkerState.regionExited(target)
                        || !tempFocus && CompassMarkerState.defocused(target))
                || !crystalHollowsTracerAllowed(group, target, options))) return null;
        if (target == null) {
            if (recordDecision) RenderDiagnostics.recordNoStraightTracer(group, "no current target");
            return null;
        }
        if (!WaypointWorldRenderer.withinRouteDistance(options, target, cameraPosition)) return null;
        if (shouldHideNearPlayer(target, player, nearHideDistanceSq(options))) {
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
        return nearestStaticTracerTarget(group, playerX, playerY, playerZ,
                nearHideDistanceSq, hideReached, maxDistanceSq, waypoint -> true);
    }

    private static Waypoint nearestStaticTracerTarget(WaypointGroup group,
                                              double playerX, double playerY, double playerZ,
                                              double nearHideDistanceSq, boolean hideReached,
                                              double maxDistanceSq,
                                              java.util.function.Predicate<Waypoint> eligible) {
        if (group == null || group.loadMode() != WaypointGroup.LoadMode.STATIC) return null;

        Waypoint nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (int index = 0; index < group.size(); index++) {
            if (group.isWaypointDisabled(index)) continue;
            if (hideReached && group.isStaticWaypointReached(index)) continue;
            Waypoint waypoint = group.get(index);
            if (CompassMarkerState.navigationStopped(waypoint) || !eligible.test(waypoint)) continue;
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
        boolean focusActive = manager.tempWaypointFocusActive();
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
        for (WaypointGroup group : groups) {
            boolean tempFocus = focusActive && group.temp() && group.focusedVisibleIndex() >= 0;
            for (int pass = 0; pass < 2; pass++) {
                Waypoint target = straightTracerTarget(
                        group, tempFocus, player, camera.position(), pass == 0, pass == 1);
                if (target == null) continue;
                WaypointerConfig options = WaypointStyles.resolve(config, group, target);
                float alpha = (float) options.tracerOpacity();
                if (tempFocus) alpha = Math.max(alpha, TEMP_FOCUS_TRACER_ALPHA_FLOOR);
                if (alpha <= 0) continue;
                WaypointWorldRenderer.populateWaypointRenderAnchor(
                        mc.level, target, waypointBoxBoundsScratch);
                double targetX = waypointBoxBoundsScratch[0];
                double targetY = waypointBoxBoundsScratch[1];
                double targetZ = waypointBoxBoundsScratch[2];
                boolean projected = projector.project(targetX, targetY, targetZ,
                        screenW, screenH, screenScratch);
                boolean offscreen = !projected || !insideViewport(screenScratch[0], screenScratch[1], screenW, screenH);
                if (projected && !insideViewport(screenScratch[0], screenScratch[1], screenW, screenH)) {
                    projectDirectionToEdge(screenScratch[0] - fromX, screenScratch[1] - fromY,
                            screenW, screenH, screenScratch);
                } else if (!projected) {
                    projectOffscreenTarget(
                            camera, targetX, targetY, targetZ,
                            screenW, screenH, screenScratch);
                }

                int color = options.matchTracerToWaypointColor() ? resolvedTargetColor(group, target, options) : options.tracerColor();
                int argb = RenderHelpers.withAlpha(0xFF000000 | (color & 0xFFFFFF),
                        alpha * tracerFadeAlpha(group, pass == 1));
                drawStyledTracer(g, fromX, fromY, screenScratch[0], screenScratch[1], argb, options, offscreen, lineStartScratch);
                RenderDiagnostics.recordStraightTracerSubmitted(group);
            }
        }
    }

    /** Shared with the appearance inspector; scratch holds the two-coordinate line start. */
    public static void drawStyledTracer(GuiGraphicsExtractor g, double fromX, double fromY,
                                       double toX, double toY, int argb, WaypointerConfig options,
                                       boolean offscreen, double[] scratch) {
        if (lineStart(fromX, fromY, toX, toY, options.tracerCenterGap(), scratch)) {
            RenderHelpers.drawScreenLine(g, scratch[0], scratch[1], toX, toY,
                    argb, options.tracerThickness(), options.renderAntialiasing());
        }
        if (offscreen && options.tracerEdgeArrow()) drawEdgeArrow(g, fromX, fromY, toX, toY, argb, options);
    }

    static boolean withinRouteDistance(double dx, double dy, double dz, double min, double max) {
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        return Double.isFinite(distance) && distance >= min && (max <= 0 || distance <= max);
    }

    static boolean lineStart(double fromX, double fromY, double toX, double toY,
                             double gap, double[] out) {
        double dx = toX - fromX, dy = toY - fromY;
        double length = Math.hypot(dx, dy);
        if (!Double.isFinite(length) || !Double.isFinite(gap) || length <= Math.max(0, gap)) return false;
        double fraction = Math.max(0, gap) / length;
        out[0] = fromX + dx * fraction;
        out[1] = fromY + dy * fraction;
        return true;
    }

    private static void drawEdgeArrow(GuiGraphicsExtractor g, double centerX, double centerY,
                                       double tipX, double tipY, int argb, WaypointerConfig options) {
        double dx = tipX - centerX, dy = tipY - centerY;
        double length = Math.hypot(dx, dy);
        if (!Double.isFinite(length) || length <= 0) return;
        dx /= length;
        dy /= length;
        double size = Math.min(7, length / 2);
        // Inset the tip so both wings remain visible even at viewport corners.
        tipX -= dx * size;
        tipY -= dy * size;
        for (int side = -1; side <= 1; side += 2) {
            RenderHelpers.drawScreenLine(g, tipX, tipY,
                    tipX - dx * size + side * dy * size * 0.5,
                    tipY - dy * size - side * dx * size * 0.5,
                    argb, options.tracerThickness(), options.renderAntialiasing());
        }
    }

    private static float tracerFadeAlpha(WaypointGroup group, boolean outgoing) {
        WaypointSkipFade fade = WaypointSkipFade.get(group);
        return fade == null ? 1 : fade.tracerAlpha(outgoing ? fade.outgoing() : group.currentIndex());
    }

    static boolean crystalHollowsTracerAllowed(WaypointGroup group, Waypoint target,
                                                WaypointerConfig config) {
        if (config == null) return true;
        if (CompassMarkerState.isTarget(target)) return config.crystalHollowsCompassTracers();
        return config.crystalHollowsTracers()
                || !(MetalDetectorController.isDetectorGroup(group)
                    || group != null && group.runtimeOnly()
                        && group.id().startsWith(CrystalHollowsStructureFolder.GROUP_PREFIX));
    }

    public static boolean tracersEnabled(WaypointGroup group, WaypointerConfig config,
                                  DungeonConfig dungeonConfig) {
        if (!crystalHollowsTracerAllowed(group, group == null ? null : group.current(), config)) return false;
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

    private int resolvedTargetColor(WaypointGroup group, Waypoint target, WaypointerConfig options) {
        return SequenceRoleColor.resolve(
                group,
                group == null ? -1 : target == group.current() ? group.currentIndex()
                        : WaypointSkipFade.get(group) != null ? WaypointSkipFade.get(group).outgoing()
                        : group.currentIndex(),
                options.colorSequenceWaypointsByRole(),
                options.sequencePreviousWaypointColor(),
                options.sequenceCurrentWaypointColor(),
                options.sequenceNextWaypointColor(),
                target == null ? 0 : WaypointStyles.waypointColor(config, group, target));
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

    public static void projectOffscreenDirection(double screenDirX, double screenDirY, double distance,
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

    public static void projectDirectionToEdge(double screenDirX, double screenDirY,
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

    private static double nearHideDistanceSq(WaypointerConfig config) {
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
