package com.babbur.waypointer.screen.preview;

public final class RoutePreviewProjection {

    public static final double PITCH_DEGREES = 35.264;
    public static final double PITCH_RADIANS = Math.toRadians(PITCH_DEGREES);
    public static final int PADDING = 12;
    public static final double SINGLE_WAYPOINT_MAX_PIXELS = 48.0;
    public static final double MIN_HOVER_TARGET_PIXELS = 6.0;
    public static final double MIN_NORMAL_MARKER_PHYSICAL_PIXELS = 10.0;
    public static final double MIN_SMALL_MARKER_PHYSICAL_PIXELS = 4.0;
    public static final double PIP_SAFE_HALF_DEPTH_PHYSICAL_PIXELS = 900.0;

    public record Projected(double x, double y, double depth) {}

    private RoutePreviewProjection() {}

    public static double rotationSafeScale(RoutePreviewScene scene, int width, int height) {
        return rotationSafeScale(scene, width, height, true);
    }

    public static double viewportSafeScale(RoutePreviewScene scene, int width, int height) {
        return rotationSafeScale(scene, width, height, false);
    }

    private static double rotationSafeScale(RoutePreviewScene scene, int width, int height,
                                            boolean capSingleWaypoint) {
        if (scene == null || scene.markers().isEmpty()) return 1.0;
        double maxRadial = 0.0;
        double maxAbsY = 0.0;
        for (RoutePreviewScene.Marker marker : scene.markers()) {
            RoutePreviewScene.Box box = marker.box();
            for (int xi = 0; xi < 2; xi++) {
                double x = xi == 0 ? box.minX() : box.maxX();
                for (int yi = 0; yi < 2; yi++) {
                    double y = yi == 0 ? box.minY() : box.maxY();
                    for (int zi = 0; zi < 2; zi++) {
                        double z = zi == 0 ? box.minZ() : box.maxZ();
                        maxRadial = Math.max(maxRadial, Math.hypot(x, z));
                        maxAbsY = Math.max(maxAbsY, Math.abs(y));
                    }
                }
            }
        }

        double availableW = Math.max(1.0, width - PADDING * 2.0);
        double availableH = Math.max(1.0, height - PADDING * 2.0);
        double halfW = Math.max(1.0e-9, maxRadial);
        double halfH = Math.max(1.0e-9,
                Math.cos(PITCH_RADIANS) * maxAbsY
                        + Math.sin(PITCH_RADIANS) * maxRadial);
        double scale = Math.min(availableW / (halfW * 2.0), availableH / (halfH * 2.0));

        if (capSingleWaypoint && scene.markers().size() == 1) {
            RoutePreviewScene.Box box = scene.markers().getFirst().box();
            double radialSize = Math.hypot(box.width(), box.depth());
            double maxEnvelope = Math.max(
                    radialSize,
                    Math.cos(PITCH_RADIANS) * box.height()
                            + Math.sin(PITCH_RADIANS) * radialSize);
            scale = Math.min(scale, SINGLE_WAYPOINT_MAX_PIXELS / Math.max(1.0e-9, maxEnvelope));
        }
        return Double.isFinite(scale) && scale > 0.0 ? scale : 1.0;
    }

    public static double rotationSafeDepthEnvelope(RoutePreviewScene scene) {
        if (scene == null || scene.markers().isEmpty()) return 0.0;
        double maxDepth = 0.0;
        double sinPitch = Math.sin(PITCH_RADIANS);
        double cosPitch = Math.cos(PITCH_RADIANS);
        for (RoutePreviewScene.Marker marker : scene.markers()) {
            RoutePreviewScene.Box box = marker.box();
            for (int xi = 0; xi < 2; xi++) {
                double x = xi == 0 ? box.minX() : box.maxX();
                for (int yi = 0; yi < 2; yi++) {
                    double y = yi == 0 ? box.minY() : box.maxY();
                    for (int zi = 0; zi < 2; zi++) {
                        double z = zi == 0 ? box.minZ() : box.maxZ();
                        double radial = Math.hypot(x, z);
                        maxDepth = Math.max(maxDepth,
                                cosPitch * radial + sinPitch * Math.abs(y));
                    }
                }
            }
        }
        return maxDepth;
    }

    public static double depthSafeScale(double depthEnvelope, int guiScale) {
        if (!Double.isFinite(depthEnvelope) || depthEnvelope <= 1.0e-9) {
            return Double.POSITIVE_INFINITY;
        }
        return PIP_SAFE_HALF_DEPTH_PHYSICAL_PIXELS
                / (depthEnvelope * Math.max(1, guiScale));
    }

    public static Projected project(double x, double y, double z, double yawRadians,
                                    double scale, double centerX, double centerY) {
        Basis basis = basis(yawRadians);
        double viewX = x * basis.rightX + z * basis.rightZ;
        double viewY = x * basis.upX + y * basis.upY + z * basis.upZ;
        double depth = x * basis.forwardX + y * basis.forwardY + z * basis.forwardZ;
        return new Projected(centerX + viewX * scale, centerY - viewY * scale, depth);
    }

    public static double markerDisplayScale(RoutePreviewScene.Marker marker,
                                            double scale, int guiScale) {
        if (marker == null || !Double.isFinite(scale) || scale <= 0.0) return 1.0;
        RoutePreviewScene.Box box = marker.box();
        double largestEdge = Math.max(box.width(), Math.max(box.height(), box.depth()));
        if (!Double.isFinite(largestEdge) || largestEdge <= 1.0e-9) return 1.0;
        double minimum = marker.small()
                ? MIN_SMALL_MARKER_PHYSICAL_PIXELS
                : MIN_NORMAL_MARKER_PHYSICAL_PIXELS;
        double physicalEdge = largestEdge * scale * Math.max(1, guiScale);
        return Math.max(1.0, minimum / Math.max(1.0e-9, physicalEdge));
    }

    public static int pick(RoutePreviewScene scene, double mouseX, double mouseY,
                           int x, int y, int width, int height,
                           double yawRadians, double scale) {
        return pick(scene, mouseX, mouseY, x, y, width, height, yawRadians, scale, 1);
    }

    public static int pick(RoutePreviewScene scene, double mouseX, double mouseY,
                           int x, int y, int width, int height,
                           double yawRadians, double scale, int guiScale) {
        if (scene == null || scene.markers().isEmpty()
                || !Double.isFinite(scale) || scale <= 0.0) return -1;
        double screenRight = (mouseX - (x + width * 0.5)) / scale;
        double screenUp = -(mouseY - (y + height * 0.5)) / scale;
        Basis basis = basis(yawRadians);
        double extent = 1.0e12;
        double originX = basis.rightX * screenRight + basis.upX * screenUp
                + basis.forwardX * extent;
        double originY = basis.upY * screenUp + basis.forwardY * extent;
        double originZ = basis.rightZ * screenRight + basis.upZ * screenUp
                + basis.forwardZ * extent;

        int best = -1;
        double bestT = Double.POSITIVE_INFINITY;
        for (int i = 0; i < scene.markers().size(); i++) {
            RoutePreviewScene.Marker marker = scene.markers().get(i);
            double hit = rayBox(originX, originY, originZ,
                    -basis.forwardX, -basis.forwardY, -basis.forwardZ,
                    marker.box(), markerDisplayScale(marker, scale, guiScale));
            if (hit >= 0.0 && hit < bestT) {
                bestT = hit;
                best = i;
            }
        }
        if (best >= 0) return best;

        double centerX = x + width * 0.5;
        double centerY = y + height * 0.5;
        double nearestDepth = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < scene.markers().size(); i++) {
            RoutePreviewScene.Marker marker = scene.markers().get(i);
            RoutePreviewScene.Box box = marker.box();
            double displayScale = markerDisplayScale(marker, scale, guiScale);
            double centerBoxX = box.centerX();
            double centerBoxY = box.centerY();
            double centerBoxZ = box.centerZ();
            double minScreenX = Double.POSITIVE_INFINITY;
            double minScreenY = Double.POSITIVE_INFINITY;
            double maxScreenX = Double.NEGATIVE_INFINITY;
            double maxScreenY = Double.NEGATIVE_INFINITY;
            double frontDepth = Double.NEGATIVE_INFINITY;
            for (int xi = 0; xi < 2; xi++) {
                double px = centerBoxX + ((xi == 0 ? box.minX() : box.maxX()) - centerBoxX)
                        * displayScale;
                for (int yi = 0; yi < 2; yi++) {
                    double py = centerBoxY + ((yi == 0 ? box.minY() : box.maxY()) - centerBoxY)
                            * displayScale;
                    for (int zi = 0; zi < 2; zi++) {
                        double pz = centerBoxZ + ((zi == 0 ? box.minZ() : box.maxZ()) - centerBoxZ)
                                * displayScale;
                        double viewX = px * basis.rightX + pz * basis.rightZ;
                        double viewY = px * basis.upX + py * basis.upY + pz * basis.upZ;
                        double screenX = centerX + viewX * scale;
                        double screenY = centerY - viewY * scale;
                        minScreenX = Math.min(minScreenX, screenX);
                        minScreenY = Math.min(minScreenY, screenY);
                        maxScreenX = Math.max(maxScreenX, screenX);
                        maxScreenY = Math.max(maxScreenY, screenY);
                        frontDepth = Math.max(frontDepth,
                                px * basis.forwardX + py * basis.forwardY
                                        + pz * basis.forwardZ);
                    }
                }
            }
            double expandX = Math.max(0.0,
                    (MIN_HOVER_TARGET_PIXELS - (maxScreenX - minScreenX)) * 0.5);
            double expandY = Math.max(0.0,
                    (MIN_HOVER_TARGET_PIXELS - (maxScreenY - minScreenY)) * 0.5);
            // Cubes use exact ray tests; only billboard markers use rectangular hitboxes.
            if (!scene.simplified() && expandX == 0.0 && expandY == 0.0) continue;
            if (mouseX >= minScreenX - expandX && mouseX <= maxScreenX + expandX
                    && mouseY >= minScreenY - expandY && mouseY <= maxScreenY + expandY
                    && frontDepth > nearestDepth) {
                nearestDepth = frontDepth;
                best = i;
            }
        }
        return best;
    }

    public static double[] projectedEnvelope(RoutePreviewScene.Box box, double yawRadians) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int xi = 0; xi < 2; xi++) {
            double x = xi == 0 ? box.minX() : box.maxX();
            for (int yi = 0; yi < 2; yi++) {
                double y = yi == 0 ? box.minY() : box.maxY();
                for (int zi = 0; zi < 2; zi++) {
                    double z = zi == 0 ? box.minZ() : box.maxZ();
                    Projected point = project(x, y, z, yawRadians, 1.0, 0.0, 0.0);
                    minX = Math.min(minX, point.x());
                    minY = Math.min(minY, point.y());
                    maxX = Math.max(maxX, point.x());
                    maxY = Math.max(maxY, point.y());
                }
            }
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private static double rayBox(double ox, double oy, double oz,
                                 double dx, double dy, double dz,
                                 RoutePreviewScene.Box box, double displayScale) {
        double cx = box.centerX();
        double cy = box.centerY();
        double cz = box.centerZ();
        double minX = cx + (box.minX() - cx) * displayScale;
        double minY = cy + (box.minY() - cy) * displayScale;
        double minZ = cz + (box.minZ() - cz) * displayScale;
        double maxX = cx + (box.maxX() - cx) * displayScale;
        double maxY = cy + (box.maxY() - cy) * displayScale;
        double maxZ = cz + (box.maxZ() - cz) * displayScale;
        double near = 0.0;
        double far = Double.POSITIVE_INFINITY;
        if (Math.abs(dx) < 1.0e-12) {
            if (ox < minX || ox > maxX) return -1.0;
        } else {
            double a = (minX - ox) / dx;
            double b = (maxX - ox) / dx;
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return -1.0;
        }
        if (Math.abs(dy) < 1.0e-12) {
            if (oy < minY || oy > maxY) return -1.0;
        } else {
            double a = (minY - oy) / dy;
            double b = (maxY - oy) / dy;
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return -1.0;
        }
        if (Math.abs(dz) < 1.0e-12) {
            if (oz < minZ || oz > maxZ) return -1.0;
        } else {
            double a = (minZ - oz) / dz;
            double b = (maxZ - oz) / dz;
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return -1.0;
        }
        return near;
    }

    private static Basis basis(double yaw) {
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(PITCH_RADIANS);
        double cosPitch = Math.cos(PITCH_RADIANS);
        return new Basis(
                cosYaw, sinYaw,
                sinPitch * sinYaw, cosPitch, -sinPitch * cosYaw,
                -cosPitch * sinYaw, sinPitch, cosPitch * cosYaw);
    }

    private record Basis(double rightX, double rightZ,
                         double upX, double upY, double upZ,
                         double forwardX, double forwardY, double forwardZ) {}
}
