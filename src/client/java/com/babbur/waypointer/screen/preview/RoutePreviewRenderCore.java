package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.render.RenderHelpers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;

public final class RoutePreviewRenderCore {

    private static final int FOCUS_CYAN = 0x4FB3C4;
    static final float MIN_CONNECTOR_WIDTH_PHYSICAL_PIXELS = 1.5f;
    static final float MIN_OUTLINE_WIDTH_PHYSICAL_PIXELS = 1.0f;

    @FunctionalInterface
    public interface Emitter {
        void emit(RoutePreviewRenderState state, PoseStack poseStack, VertexConsumer vertices);
    }

    private RoutePreviewRenderCore() {}

    public static void applyView(RoutePreviewRenderState state, PoseStack poseStack) {
        poseStack.scale(1.0f, -1.0f, 1.0f);
        poseStack.mulPose(new Quaternionf()
                .rotateX((float) RoutePreviewProjection.PITCH_RADIANS)
                .rotateY(state.yawRadians()));
    }

    public static void emitDepth(RoutePreviewRenderState state, PoseStack ps,
                                 VertexConsumer vertices) {
        RoutePreviewScene scene = state.scene();
        Basis billboardBasis = scene.simplified() ? basis(state.yawRadians()) : null;
        for (int i = 0; i < scene.markers().size(); i++) {
            RoutePreviewScene.Marker marker = scene.markers().get(i);
            if (scene.simplified() && i != state.hoveredWaypointIndex()) {
                emitBillboard(vertices, ps, marker, billboardBasis, marker.color(), 0.0f,
                        state.scale(), state.guiScale());
            } else {
                emitBox(vertices, ps, marker.box(), marker.color(), 0.0f,
                        displayScale(marker, state));
            }
        }
    }

    public static void emitSurfaces(RoutePreviewRenderState state, PoseStack ps,
                                    VertexConsumer vertices) {
        RoutePreviewScene scene = state.scene();
        boolean paintedPass = scene.paint() != null && !scene.simplified();
        Basis viewBasis = basis(state.yawRadians());
        double cameraDistance = 1.0e12;
        double cameraX = viewBasis.forwardX * cameraDistance;
        double cameraY = viewBasis.forwardY * cameraDistance;
        double cameraZ = viewBasis.forwardZ * cameraDistance;
        for (int i = 0; i < scene.markers().size(); i++) {
            RoutePreviewScene.Marker marker = scene.markers().get(i);
            boolean hovered = i == state.hoveredWaypointIndex();
            int color = hovered ? brighten(marker.color(), 0.20f) : marker.color();
            float alpha = hovered ? 1.0f : scene.opacity();
            if (scene.simplified() && !hovered) {
                emitBillboard(vertices, ps, marker, viewBasis, color, alpha,
                        state.scale(), state.guiScale());
                continue;
            }
            if (paintedPass) {
                RoutePreviewScene.Box box = marker.box();
                double displayScale = displayScale(marker, state);
                double cx = box.centerX(), cy = box.centerY(), cz = box.centerZ();
                RenderHelpers.emitTexturedBox(vertices, ps,
                        (float) (cx + (box.minX() - cx) * displayScale),
                        (float) (cy + (box.minY() - cy) * displayScale),
                        (float) (cz + (box.minZ() - cz) * displayScale),
                        (float) (cx + (box.maxX() - cx) * displayScale),
                        (float) (cy + (box.maxY() - cy) * displayScale),
                        (float) (cz + (box.maxZ() - cz) * displayScale),
                        alpha, cameraX, cameraY, cameraZ,
                        RoutePreviewPaintResource.ATLAS_WIDTH,
                        RoutePreviewPaintResource.ATLAS_HEIGHT,
                        RoutePreviewPaintResource.PADDING);
                continue;
            }
            if (drawFill(scene, marker) || hovered) {
                emitBox(vertices, ps, marker.box(), color, alpha, displayScale(marker, state));
            }
        }
    }

    public static void emitConnectors(RoutePreviewRenderState state, PoseStack ps,
                                      VertexConsumer vertices) {
        Basis viewBasis = basis(state.yawRadians());
        for (RoutePreviewScene.Connector connector : state.scene().connectors()) {
            emitRibbon(vertices, ps, viewBasis,
                    connector.x1(), connector.y1(), connector.z1(),
                    connector.x2(), connector.y2(), connector.z2(),
                    state.scene().routeLineColor(), 1.0f,
                    physicalLineWidth(state.scene().outlineWidth(),
                            MIN_CONNECTOR_WIDTH_PHYSICAL_PIXELS),
                    state.scale(), state.guiScale());
        }
    }

    public static void emitPaintHover(RoutePreviewRenderState state, PoseStack ps,
                                      VertexConsumer vertices) {
        RoutePreviewScene scene = state.scene();
        int index = state.hoveredWaypointIndex();
        if (scene.paint() == null || scene.simplified()
                || index < 0 || index >= scene.markers().size()) return;
        RoutePreviewScene.Marker marker = scene.markers().get(index);
        emitBox(vertices, ps, marker.box(), 0xFFFFFF, 0.20f, displayScale(marker, state));
    }

    public static void emitOutlines(RoutePreviewRenderState state, PoseStack ps,
                                    VertexConsumer vertices) {
        RoutePreviewScene scene = state.scene();
        Basis viewBasis = basis(state.yawRadians());
        for (int i = 0; i < scene.markers().size(); i++) {
            if (scene.simplified() && i != state.hoveredWaypointIndex()) continue;
            RoutePreviewScene.Marker marker = scene.markers().get(i);
            boolean hovered = i == state.hoveredWaypointIndex();
            if (!hovered && !drawOutline(scene)) continue;
            RoutePreviewScene.Box box = marker.box();
            int outlineColor = scene.outlineMatchesWaypointColor()
                    ? marker.color() : scene.outlineColor();
            emitBoxRibbons(vertices, ps, viewBasis, box,
                    hovered ? FOCUS_CYAN : outlineColor,
                    hovered ? 1.0f : scene.outlineOpacity(),
                    physicalLineWidth(scene.outlineWidth(),
                            MIN_OUTLINE_WIDTH_PHYSICAL_PIXELS),
                    state.scale(), state.guiScale(), displayScale(marker, state));
        }
    }

    static float physicalLineWidth(float requested, float minimum) {
        return Float.isFinite(requested) ? Math.max(minimum, requested) : minimum;
    }

    private static boolean drawFill(RoutePreviewScene scene, RoutePreviewScene.Marker marker) {
        return switch (scene.boxStyle()) {
            case FILLED, FILLED_OUTLINED -> true;
            case PAINT -> scene.paint() == null;
            case OUTLINED -> (marker.flags() & Waypoint.FLAG_FILLED_SUBWAYPOINT) != 0;
        };
    }

    private static boolean drawOutline(RoutePreviewScene scene) {
        return scene.boxStyle() == WaypointerConfig.BoxStyle.OUTLINED
                || scene.boxStyle() == WaypointerConfig.BoxStyle.FILLED_OUTLINED;
    }

    private static void emitBox(VertexConsumer vertices, PoseStack ps,
                                RoutePreviewScene.Box box, int color, float alpha,
                                double displayScale) {
        double cx = box.centerX(), cy = box.centerY(), cz = box.centerZ();
        RenderHelpers.emitFilledBox(vertices, ps,
                (float) (cx + (box.minX() - cx) * displayScale),
                (float) (cy + (box.minY() - cy) * displayScale),
                (float) (cz + (box.minZ() - cz) * displayScale),
                (float) (cx + (box.maxX() - cx) * displayScale),
                (float) (cy + (box.maxY() - cy) * displayScale),
                (float) (cz + (box.maxZ() - cz) * displayScale), color, alpha);
    }

    private static void emitBillboard(VertexConsumer vertices, PoseStack ps,
                                      RoutePreviewScene.Marker marker, Basis basis,
                                      int color, float alpha, double scale, int guiScale) {
        RoutePreviewScene.Box box = marker.box();
        double displayScale = RoutePreviewProjection.markerDisplayScale(marker, scale, guiScale);
        double halfW = (Math.abs(basis.rightX) * box.width()
                + Math.abs(basis.rightZ) * box.depth()) * displayScale * 0.5;
        double halfH = (Math.abs(basis.upX) * box.width()
                + Math.abs(basis.upY) * box.height()
                + Math.abs(basis.upZ) * box.depth()) * displayScale * 0.5;
        double cx = box.centerX();
        double cy = box.centerY();
        double cz = box.centerZ();
        RenderHelpers.emitFilledQuad(vertices, ps,
                (float) (cx - basis.rightX * halfW - basis.upX * halfH),
                (float) (cy - basis.upY * halfH),
                (float) (cz - basis.rightZ * halfW - basis.upZ * halfH),
                (float) (cx + basis.rightX * halfW - basis.upX * halfH),
                (float) (cy - basis.upY * halfH),
                (float) (cz + basis.rightZ * halfW - basis.upZ * halfH),
                (float) (cx + basis.rightX * halfW + basis.upX * halfH),
                (float) (cy + basis.upY * halfH),
                (float) (cz + basis.rightZ * halfW + basis.upZ * halfH),
                (float) (cx - basis.rightX * halfW + basis.upX * halfH),
                (float) (cy + basis.upY * halfH),
                 (float) (cz - basis.rightZ * halfW + basis.upZ * halfH), color, alpha);
    }

    private static void emitBoxRibbons(VertexConsumer vertices, PoseStack ps, Basis basis,
                                       RoutePreviewScene.Box box, int color, float alpha,
                                       float widthPixels, double scale, int guiScale,
                                       double displayScale) {
        double cx = box.centerX(), cy = box.centerY(), cz = box.centerZ();
        double x1 = cx + (box.minX() - cx) * displayScale;
        double y1 = cy + (box.minY() - cy) * displayScale;
        double z1 = cz + (box.minZ() - cz) * displayScale;
        double x2 = cx + (box.maxX() - cx) * displayScale;
        double y2 = cy + (box.maxY() - cy) * displayScale;
        double z2 = cz + (box.maxZ() - cz) * displayScale;

        emitRibbon(vertices, ps, basis, x1, y1, z1, x2, y1, z1,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x2, y1, z1, x2, y1, z2,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x2, y1, z2, x1, y1, z2,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x1, y1, z2, x1, y1, z1,
                color, alpha, widthPixels, scale, guiScale);

        emitRibbon(vertices, ps, basis, x1, y2, z1, x2, y2, z1,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x2, y2, z1, x2, y2, z2,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x2, y2, z2, x1, y2, z2,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x1, y2, z2, x1, y2, z1,
                color, alpha, widthPixels, scale, guiScale);

        emitRibbon(vertices, ps, basis, x1, y1, z1, x1, y2, z1,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x2, y1, z1, x2, y2, z1,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x2, y1, z2, x2, y2, z2,
                color, alpha, widthPixels, scale, guiScale);
        emitRibbon(vertices, ps, basis, x1, y1, z2, x1, y2, z2,
                color, alpha, widthPixels, scale, guiScale);
    }

    private static double displayScale(RoutePreviewScene.Marker marker,
                                       RoutePreviewRenderState state) {
        return RoutePreviewProjection.markerDisplayScale(
                marker, state.scale(), state.guiScale());
    }

    /** Uses a filled ribbon because the line shader measures against the main framebuffer. */
    private static void emitRibbon(VertexConsumer vertices, PoseStack ps, Basis basis,
                                   double x1, double y1, double z1,
                                   double x2, double y2, double z2,
                                   int color, float alpha, float widthPixels,
                                   double scale, int guiScale) {
        double safeScale = Math.max(1.0e-9, scale);
        double halfWidth = physicalLineWidth(
                widthPixels, MIN_CONNECTOR_WIDTH_PHYSICAL_PIXELS)
                / (2.0 * safeScale * Math.max(1, guiScale));
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double screenDx = dx * basis.rightX + dz * basis.rightZ;
        double screenDy = dx * basis.upX + dy * basis.upY + dz * basis.upZ;
        double screenLength = Math.hypot(screenDx, screenDy);

        if (screenLength < 1.0e-9) {
            double cx = (x1 + x2) * 0.5;
            double cy = (y1 + y2) * 0.5;
            double cz = (z1 + z2) * 0.5;
            RenderHelpers.emitFilledQuad(vertices, ps,
                    (float) (cx - basis.rightX * halfWidth - basis.upX * halfWidth),
                    (float) (cy - basis.upY * halfWidth),
                    (float) (cz - basis.rightZ * halfWidth - basis.upZ * halfWidth),
                    (float) (cx + basis.rightX * halfWidth - basis.upX * halfWidth),
                    (float) (cy - basis.upY * halfWidth),
                    (float) (cz + basis.rightZ * halfWidth - basis.upZ * halfWidth),
                    (float) (cx + basis.rightX * halfWidth + basis.upX * halfWidth),
                    (float) (cy + basis.upY * halfWidth),
                    (float) (cz + basis.rightZ * halfWidth + basis.upZ * halfWidth),
                    (float) (cx - basis.rightX * halfWidth + basis.upX * halfWidth),
                    (float) (cy + basis.upY * halfWidth),
                    (float) (cz - basis.rightZ * halfWidth + basis.upZ * halfWidth),
                    color, alpha);
            return;
        }

        double alongX = screenDx / screenLength;
        double alongY = screenDy / screenLength;
        double capX = (basis.rightX * alongX + basis.upX * alongY) * halfWidth;
        double capY = basis.upY * alongY * halfWidth;
        double capZ = (basis.rightZ * alongX + basis.upZ * alongY) * halfWidth;
        double perpScreenX = -alongY;
        double perpScreenY = alongX;
        double offsetX = (basis.rightX * perpScreenX + basis.upX * perpScreenY) * halfWidth;
        double offsetY = basis.upY * perpScreenY * halfWidth;
        double offsetZ = (basis.rightZ * perpScreenX + basis.upZ * perpScreenY) * halfWidth;

        double startX = x1 - capX;
        double startY = y1 - capY;
        double startZ = z1 - capZ;
        double endX = x2 + capX;
        double endY = y2 + capY;
        double endZ = z2 + capZ;
        RenderHelpers.emitFilledQuad(vertices, ps,
                (float) (startX + offsetX), (float) (startY + offsetY),
                (float) (startZ + offsetZ),
                (float) (startX - offsetX), (float) (startY - offsetY),
                (float) (startZ - offsetZ),
                (float) (endX - offsetX), (float) (endY - offsetY),
                (float) (endZ - offsetZ),
                (float) (endX + offsetX), (float) (endY + offsetY),
                (float) (endZ + offsetZ), color, alpha);
    }

    private static Basis basis(double yaw) {
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(RoutePreviewProjection.PITCH_RADIANS);
        double cosPitch = Math.cos(RoutePreviewProjection.PITCH_RADIANS);
        return new Basis(
                cosYaw, sinYaw,
                sinPitch * sinYaw, cosPitch, -sinPitch * cosYaw,
                -cosPitch * sinYaw, sinPitch, cosPitch * cosYaw);
    }

    private static int brighten(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private record Basis(double rightX, double rightZ,
                         double upX, double upY, double upZ,
                         double forwardX, double forwardY, double forwardZ) {}
}
