package com.babbur.waypointer.diana;

import com.babbur.waypointer.crystal.compass.Vec3d;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DianaBurrowsTest {
    private static final Vec3d START = new Vec3d(-40.5, 76, -30.5);
    private static final Vec3d TARGET = new Vec3d(110.5, 72.5, 90.5);

    @Test
    void reconstructsKnownCubicEndpointsAcrossPitchAndHeading() {
        for (double pitch : new double[]{-1.4, -0.6, 0, 0.8, 1.4}) {
            for (double yaw : new double[]{0, 1.2, 3.4}) {
                DianaSpadeSolver solver = new DianaSpadeSolver();
                solver.use(START, 1_000);
                for (int i = 0; i < 40; i++) solver.accept(trail(i * 0.004, pitch, yaw), 1_000 + i * 25);
                assertNotNull(solver.estimate(), "pitch=" + pitch + ", yaw=" + yaw);
                assertTrue(solver.estimate().distanceTo(TARGET) < 0.0001);
            }
        }
    }

    @Test
    void rejectsInsufficientForeignTimedOutCorruptAndMissingSamples() {
        DianaSpadeSolver solver = new DianaSpadeSolver();
        for (int i = 0; i < 40; i++) solver.accept(trail(i * 0.004, 0, 0), 1_000 + i * 25);
        assertNull(solver.estimate()); // No local spade cast.
        solver.use(START, 1_000);
        for (int i = 0; i < 40; i++) solver.accept(trail(i * 0.004, 0, 0).add(new Vec3d(30, 0, 0)), 1_000 + i * 25);
        assertNull(solver.estimate()); // Another player's trail starts too far away.
        solver.reset();
        solver.use(START, 1_000);
        for (int i = 0; i < 40; i++) solver.accept(trail(i * 0.004, 0, 0), 5_000 + i * 25);
        assertNull(solver.estimate());
        solver.use(START, 7_000);
        for (int i = 0; i < 7; i++) solver.accept(trail(i * 0.004, 0, 0), 7_000 + i * 25);
        assertNull(solver.estimate());
        solver.reset();
        solver.use(START, 1_000);
        for (int i = 0; i < 25; i++) {
            if (i == 9) continue;
            solver.accept(trail(i * 0.004, 0, 0), 1_000 + i * 25);
        }
        assertNull(solver.estimate()); // Lost packets cannot silently change the sample index.
        solver.reset();
        solver.use(START, 1_000);
        for (int i = 0; i < 40; i++) {
            Vec3d sample = trail(i * 0.004, 0, 0);
            solver.accept(i % 3 == 1 ? sample.add(new Vec3d(0, 0.3, 0)) : sample, 1_000 + i * 25);
        }
        assertNull(solver.estimate());
        solver.accept(new Vec3d(Double.NaN, 70, 0), 2_001);
        assertNull(solver.estimate());
    }

    @Test
    void requiresWholeBurrowSignatureAndFloorsNegativeCoordinates() {
        DianaBurrows state = new DianaBurrows();
        state.particle(DianaBurrows.Particle.CRIT, -20.5, 71, -30.5, 3, 0.01f, 0, 0.1f, 0.5f, 1_000);
        state.particle(DianaBurrows.Particle.CRIT, -20.5, 71, -30.5, 1, 0.01f, 0.5f, 0.1f, 0.5f, 1_000);
        state.particle(DianaBurrows.Particle.CRIT, -20.5, 71, -30.5, 3, 0, 0.5f, 0.1f, 0.5f, 1_000);
        assertTrue(state.markers().isEmpty());
        nearby(state, DianaBurrows.Particle.CRIT, -20.5, 71, -30.5, 1_000);
        assertEquals(List.of(new DianaBurrows.Marker(new DianaBurrows.Block(-21, 70, -31), DianaBurrows.Kind.MOB)), state.markers());
        assertEquals(DianaBurrows.Kind.START, DianaBurrows.classify(DianaBurrows.Particle.ENCHANTED_HIT, 4, .01f, .5f, .1f, .5f));
        assertEquals(DianaBurrows.Kind.TREASURE, DianaBurrows.classify(DianaBurrows.Particle.DRIPPING_LAVA, 2, .01f, .35f, .1f, .35f));
    }

    @Test
    void confirmationReplacesEstimateAndDigMessagesRespectBothDigStages() {
        DianaBurrows state = new DianaBurrows();
        state.use(START, 1_000);
        for (int i = 0; i < 40; i++) {
            Vec3d p = trail(i * 0.004, 0, 0);
            state.particle(DianaBurrows.Particle.DRIPPING_LAVA, p.x(), p.y(), p.z(), 2, -.5f, 0, 0, 0, 1_000 + i * 25);
        }
        assertEquals(DianaBurrows.Kind.ESTIMATE, state.markers().getFirst().kind());
        nearby(state, DianaBurrows.Particle.CRIT, 110.5, 73, 90.5, 2_000);
        assertEquals(1, state.markers().size());
        assertEquals(DianaBurrows.Kind.MOB, state.markers().getFirst().kind());
        state.click(new DianaBurrows.Block(110, 72, 90), 2_100);
        state.message("You dug out a Minos Hunter!", 2_200);
        assertEquals(1, state.markers().size());
        state.message("You dug out a Griffin Burrow! (2/4)", 2_300);
        assertTrue(state.markers().isEmpty());
        nearby(state, DianaBurrows.Particle.CRIT, 110.5, 73, 90.5, 2_400);
        assertTrue(state.markers().isEmpty()); // Trailing particles cannot resurrect it.
        nearby(state, DianaBurrows.Particle.CRIT, 110.5, 73, 90.5, 5_000);
        assertEquals(1, state.markers().size());
        state.particle(DianaBurrows.Particle.LARGE_SMOKE, 110.5, 73, 90.5, 8, .01f, 0, 0, 0, 5_100);
        assertTrue(state.markers().isEmpty());
    }

    @Test
    void expiryResetAndStaleClicksCannotLeakMarkersAcrossSessions() {
        DianaBurrows state = new DianaBurrows();
        nearby(state, DianaBurrows.Particle.CRIT, 110.5, 73, 90.5, 1_000);
        state.click(new DianaBurrows.Block(110, 72, 90), 1_100);
        state.message("You finished the Griffin burrow chain!", 32_000);
        assertEquals(1, state.markers().size());
        state.expire(121_001);
        assertEquals(1, state.markers().size()); // Undug burrows survive traversing the Hub.
        state.expire(1_801_001);
        assertTrue(state.markers().isEmpty());
        nearby(state, DianaBurrows.Particle.CRIT, 110.5, 73, 90.5, 1_810_000);
        state.reset();
        assertTrue(state.markers().isEmpty());
    }

    @Test
    void rejectedLateLavaPacketsCannotExtendTheEstimateLifetime() {
        DianaBurrows state = new DianaBurrows();
        state.use(START, 1_000);
        for (int i = 0; i < 40; i++) {
            Vec3d p = trail(i * 0.004, 0, 0);
            state.particle(DianaBurrows.Particle.DRIPPING_LAVA, p.x(), p.y(), p.z(), 2, -.5f, 0, 0, 0, 1_000 + i * 25);
        }
        assertEquals(1, state.markers().size());
        Vec3d p = trail(0.16, 0, 0);
        state.particle(DianaBurrows.Particle.DRIPPING_LAVA, p.x(), p.y(), p.z(), 2, -.5f, 0, 0, 0, 31_900);
        state.expire(32_000);
        assertTrue(state.markers().isEmpty());
    }

    @Test
    void serverProgressClearsEstimateEvenWhenTheClickWasNotObserved() {
        for (String message : List.of("You dug out a Griffin Burrow! (2/4)",
                "You finished the Griffin burrow chain!")) {
            DianaBurrows state = new DianaBurrows();
            state.use(START, 1_000);
            for (int i = 0; i < 40; i++) {
                Vec3d p = trail(i * 0.004, 0, 0);
                state.particle(DianaBurrows.Particle.DRIPPING_LAVA, p.x(), p.y(), p.z(),
                        2, -.5f, 0, 0, 0, 1_000 + i * 25);
            }
            assertEquals(DianaBurrows.Kind.ESTIMATE, state.markers().getFirst().kind());
            state.message(message, 2_000);
            assertTrue(state.markers().isEmpty());
            Vec3d late = trail(0.16, 0, 0);
            state.particle(DianaBurrows.Particle.DRIPPING_LAVA, late.x(), late.y(), late.z(),
                    2, -.5f, 0, 0, 0, 2_025);
            assertTrue(state.markers().isEmpty()); // The cast was cleared as well.
        }
    }

    private static void nearby(DianaBurrows state, DianaBurrows.Particle type, double x, double y, double z, long now) {
        state.particle(type, x, y, z, 3, .01f, .5f, .1f, .5f, now);
    }

    /** Independent Bezier fixture: known start, initial control point and endpoint. */
    private static Vec3d trail(double t, double pitch, double yaw) {
        Vec3d first = START.add(new Vec3d(4 * Math.cos(pitch) * Math.cos(yaw),
                3 - 4 * Math.sin(pitch), 4 * Math.cos(pitch) * Math.sin(yaw)));
        Vec3d second = new Vec3d(60, 110, 65);
        double s = 1 - t;
        return START.scale(s * s * s).add(first.scale(3 * s * s * t))
                .add(second.scale(3 * s * t * t)).add(TARGET.scale(t * t * t));
    }
}
