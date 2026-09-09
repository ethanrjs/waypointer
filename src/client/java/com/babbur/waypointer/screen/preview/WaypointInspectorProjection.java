package com.babbur.waypointer.screen.preview;

import org.joml.Matrix4f;

/** Perspective camera for a sample waypoint; no minimum marker size or overview enlargement. */
public record WaypointInspectorProjection(double distance, double yaw, double pitch,
                                          double verticalFov, int height, double offsetX, double offsetY) {
    public WaypointInspectorProjection(double distance, double yaw, double pitch, double verticalFov, int height) {
        this(distance, yaw, pitch, verticalFov, height, 0, 0);
    }
    public record Point(double x, double y, double depth) {}

    public Point project(double x, double y, double z) {
        Point view = view(x, y, z);
        double focal = height / (2 * Math.tan(Math.toRadians(verticalFov) / 2));
        double safeDepth = Math.max(0.05, view.depth());
        return new Point(offsetX + view.x() * focal / safeDepth, offsetY - view.y() * focal / safeDepth, view.depth());
    }

    /** Camera-space coordinates; the GPU receives z=-depth and performs the perspective divide. */
    public Point view(double x, double y, double z) {
        double sideways = Math.cos(yaw) * x + Math.sin(yaw) * z;
        double forward = -Math.sin(yaw) * x + Math.cos(yaw) * z;
        double up = Math.cos(pitch) * y - Math.sin(pitch) * forward;
        double depth = distance + Math.sin(pitch) * y + Math.cos(pitch) * forward;
        return new Point(sideways, up, depth);
    }

    public Matrix4f panProjection(Matrix4f projection, int width) {
        return projection.m20(projection.m20() - (float) (2 * offsetX / width))
                .m21(projection.m21() + (float) (2 * offsetY / height));
    }
}
