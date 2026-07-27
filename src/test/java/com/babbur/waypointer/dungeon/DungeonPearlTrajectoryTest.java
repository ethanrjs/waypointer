package com.babbur.waypointer.dungeon;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonPearlTrajectoryTest {

    @Test
    void reachableTargetProducesACompleteLowArc() {
        Vec3 start = new Vec3(0.0, 70.0, 0.0);
        Vec3 target = new Vec3(20.0, 72.0, 0.0);

        List<Vec3> points = DungeonPearlTrajectory.points(start, target);

        assertEquals(start, points.getFirst());
        assertEquals(target, points.getLast());
        assertTrue(points.size() >= 9);
        Vec3 middle = points.get(points.size() / 2);
        assertTrue(middle.y > 71.0);
    }

    @Test
    void unreachableTargetFallsBackToAVisibleGuide() {
        Vec3 start = new Vec3(1.0, 64.0, 2.0);
        Vec3 target = new Vec3(200.0, 120.0, 2.0);

        List<Vec3> points = DungeonPearlTrajectory.points(start, target);

        assertEquals(start, points.getFirst());
        assertEquals(target, points.getLast());
        assertEquals(17, points.size());
        assertTrue(points.get(8).y > 92.0);
    }
}
