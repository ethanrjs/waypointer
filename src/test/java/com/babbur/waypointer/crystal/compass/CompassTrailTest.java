package com.babbur.waypointer.crystal.compass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.crystal.CrystalHollowsZone;
import org.junit.jupiter.api.Test;

class CompassTrailTest {

    @Test
    void completesTwentyPointCycleWhenTrailRepeats() {
        CompassTrail trail = new CompassTrail(new Vec3d(500, 100, 500), 1_000,
                CrystalHollowsZone.JUNGLE);
        Vec3d first = new Vec3d(500.3, 101.5, 500.2);
        for (int index = 0; index < 20; index++) {
            trail.onParticle(first.add(new Vec3d(index * 0.49, 0, 0)), 1_100 + index * 40L);
        }
        trail.onParticle(first, 2_000);
        assertEquals(CompassTrail.State.COMPLETE, trail.state());
        assertEquals(first, trail.points().getFirst());
        assertEquals(first.add(new Vec3d(19 * 0.49, 0, 0)), trail.points().getLast());
        assertEquals(20, trail.ray().orElseThrow().pointCount());
    }

    @Test
    void ignoresAnotherPlayersDiscontinuousParticles() {
        CompassTrail trail = new CompassTrail(new Vec3d(500, 100, 500), 1_000,
                CrystalHollowsZone.JUNGLE);
        Vec3d first = new Vec3d(500, 101.5, 500);
        trail.onParticle(first, 1_100);
        trail.onParticle(first.add(new Vec3d(3, 0, 0)), 1_150);
        trail.onParticle(first.add(new Vec3d(0.49, 0, 0)), 1_200);
        assertEquals(2, trail.points().size());
        assertEquals(CompassTrail.State.CHAINING, trail.state());
    }

    @Test
    void watchdogCompletesLongEnoughChain() {
        CompassTrail trail = chain(8, 0.49);
        for (int tick = 0; tick < CompassTrail.WATCHDOG_TICKS; tick++) trail.tick(2_000);
        assertEquals(CompassTrail.State.COMPLETE, trail.state());
    }

    @Test
    void reportsNoParticlesAndInProgressTimeouts() {
        CompassTrail empty = new CompassTrail(new Vec3d(500, 100, 500), 1_000,
                CrystalHollowsZone.JUNGLE);
        empty.tick(6_000);
        assertEquals(CompassTrail.Failure.NO_PARTICLES, empty.failure());

        CompassTrail partial = chain(2, 0.49);
        partial.tick(6_001);
        assertEquals(CompassTrail.State.FAILED, partial.state());
        assertEquals(CompassTrail.Failure.TIMEOUT, partial.failure());
    }

    @Test
    void rejectsTrailShorterThanThreeBlocks() {
        CompassTrail shortTrail = chain(6, 0.49);
        shortTrail.onParticle(shortTrail.points().getFirst(), 2_000);
        assertEquals(CompassTrail.State.FAILED, shortTrail.state());
        assertEquals(CompassTrail.Failure.TOO_SHORT, shortTrail.failure());
        assertTrue(shortTrail.ray().isEmpty());
    }

    private static CompassTrail chain(int points, double spacing) {
        CompassTrail trail = new CompassTrail(new Vec3d(500, 100, 500), 1_000,
                CrystalHollowsZone.JUNGLE);
        Vec3d first = new Vec3d(500, 101.5, 500);
        for (int index = 0; index < points; index++) {
            trail.onParticle(first.add(new Vec3d(index * spacing, 0, 0)), 1_100 + index * 40L);
        }
        return trail;
    }
}
