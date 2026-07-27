package com.babbur.waypointer.dungeon;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Low-arc Ender Pearl launch guide between a route's throw and landing. */
public final class DungeonPearlTrajectory {

    private static final double SPEED = 1.5;
    private static final double GRAVITY = 0.03;
    private static final int MAX_SEGMENTS = 64;

    private DungeonPearlTrajectory() {}

    public static List<Vec3> points(Vec3 start, Vec3 target) {
        if (start == null || target == null) return List.of();
        double dx = target.x - start.x;
        double dz = target.z - start.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0E-4) return List.of(start, target);

        double dy = target.y - start.y;
        double speedSq = SPEED * SPEED;
        double discriminant = speedSq * speedSq
                - GRAVITY * (GRAVITY * horizontal * horizontal + 2.0 * dy * speedSq);
        if (discriminant < 0.0) return fallbackArc(start, target);

        double tangent = (speedSq - Math.sqrt(discriminant)) / (GRAVITY * horizontal);
        double angle = Math.atan(tangent);
        double horizontalSpeed = SPEED * Math.cos(angle);
        if (horizontalSpeed <= 1.0E-4) return fallbackArc(start, target);

        double flightTime = horizontal / horizontalSpeed;
        int segments = Math.max(8, Math.min(MAX_SEGMENTS, (int) Math.ceil(flightTime * 2.0)));
        double ux = dx / horizontal;
        double uz = dz / horizontal;
        List<Vec3> points = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            double fraction = i / (double) segments;
            double time = flightTime * fraction;
            double travelled = horizontalSpeed * time;
            double y = start.y + SPEED * Math.sin(angle) * time
                    - 0.5 * GRAVITY * time * time;
            points.add(new Vec3(
                    start.x + ux * travelled,
                    y,
                    start.z + uz * travelled));
        }
        points.set(points.size() - 1, target);
        return List.copyOf(points);
    }

    private static List<Vec3> fallbackArc(Vec3 start, Vec3 target) {
        double height = Math.max(2.0, Math.hypot(target.x - start.x, target.z - start.z) * 0.25);
        List<Vec3> points = new ArrayList<>(17);
        for (int i = 0; i <= 16; i++) {
            double t = i / 16.0;
            double lift = 4.0 * height * t * (1.0 - t);
            points.add(new Vec3(
                    start.x + (target.x - start.x) * t,
                    start.y + (target.y - start.y) * t + lift,
                    start.z + (target.z - start.z) * t));
        }
        return List.copyOf(points);
    }
}
