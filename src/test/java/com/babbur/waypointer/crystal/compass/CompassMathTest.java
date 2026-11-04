package com.babbur.waypointer.crystal.compass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompassMathTest {

    @Test
    void fitsNoisyCollinearPointsWithinHalfDegree() {
        Vec3d expected = new Vec3d(0.8, -0.2, 0.56).normalize();
        List<Vec3d> points = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            Vec3d base = new Vec3d(400, 120, 500).add(expected.scale(index * 0.49));
            double noise = (index % 3 - 1) * 0.05;
            points.add(base.add(new Vec3d(noise, noise * 0.5, -noise * 0.25)));
        }
        CompassMath.RayFit fit = CompassMath.fitRay(points);
        double angle = Math.toDegrees(Math.acos(Math.clamp(fit.direction().dot(expected), -1, 1)));
        assertTrue(angle < 0.5, "angle=" + angle);
    }

    @Test
    void findsKnownClosestPointsOnSkewLines() {
        CompassRay xAxis = ray(new Vec3d(0, 0, 0), new Vec3d(1, 0, 0));
        CompassRay zAxisAbove = ray(new Vec3d(2, 3, -4), new Vec3d(0, 0, 1));
        CompassMath.ClosestPoints points = CompassMath.closestPoints(xAxis, zAxisAbove).orElseThrow();
        assertEquals(new Vec3d(2, 0, 0), points.onFirst());
        assertEquals(new Vec3d(2, 3, 0), points.onSecond());
        assertEquals(3.0, points.gap(), 1.0e-9);
        assertEquals(new Vec3d(2, 1.5, 0), points.midpoint());
    }

    @Test
    void identifiesParallelLinesAndMeasuresAngle() {
        CompassRay first = ray(new Vec3d(0, 0, 0), new Vec3d(1, 0, 0));
        CompassRay parallel = ray(new Vec3d(0, 1, 0), new Vec3d(1, 0, 0));
        CompassRay perpendicular = ray(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1));
        assertTrue(CompassMath.closestPoints(first, parallel).isEmpty());
        assertEquals(0.0, CompassMath.angleDegrees(first, parallel), 1.0e-9);
        assertEquals(90.0, CompassMath.angleDegrees(first, perpendicular), 1.0e-9);
    }

    private static CompassRay ray(Vec3d origin, Vec3d direction) {
        return new CompassRay(origin, direction, 20, 9.3, origin.x(), origin.y(), origin.z(),
                0, CrystalHollowsZone.JUNGLE);
    }
}
