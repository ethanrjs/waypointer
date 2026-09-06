package com.babbur.waypointer.crystal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetalDetectorSolverTest {
    private static final CrystalHollowsPosition CENTRE = new CrystalHollowsPosition(700, 100, 400);

    @Test
    void firstFreshPacketAfterObservedStandstillSolvesWithoutARepeat() {
        var solver = new MetalDetectorSolver();
        var position = new MetalDetectorSolver.PositionStability();
        for (int tick = 0; tick <= 5; tick++) position.tick(663, 79, 426, tick * 50_000_000L);
        assertTrue(position.stableAt(663, 79, 426));
        assertTrue(solver.candidates().isEmpty()); // Ticks alone never reuse a cached distance.
        solver.accept(CENTRE, 663, 79, 426, 1, java.util.List.of(), position.stableAt(663, 79, 426));
        assertEquals(java.util.List.of(new CrystalHollowsPosition(662, 78, 426)), solver.candidates());
    }

    @Test
    void positionStabilityRequiresTimeAndTicksAndResetsAfterMovementOrSneaking() {
        var position = new MetalDetectorSolver.PositionStability();
        position.tick(663, 79, 426, 0);
        for (int tick = 1; tick <= 5; tick++) position.tick(663, 79, 426, 1);
        assertFalse(position.stableAt(663, 79, 426)); // Catch-up ticks are not elapsed standstill.
        position.tick(663, 79, 426, 250_000_000L);
        assertTrue(position.stableAt(663.0001, 79, 426));
        assertFalse(position.stableAt(663.01, 79, 426)); // Movement between tick and packet.
        position.tick(663.01, 79, 426, 300_000_000L);
        assertFalse(position.stableAt(663.01, 79, 426));
        for (int tick = 1; tick <= 5; tick++) position.tick(663.01, 79, 426, 300_000_000L + tick * 50_000_000L);
        assertTrue(position.stableAt(663.01, 79, 426));
        position.tick(663.01, 78.73, 426, 600_000_000L);
        assertFalse(position.stableAt(663.01, 78.73, 426));
        position.reset();
        assertFalse(position.stableAt(663.01, 78.73, 426));
        position.tick(Double.NaN, 79, 426, 900_000_000L);
        assertFalse(position.stableAt(663.01, 79, 426));
    }

    @Test
    void movingToANewPositionDoesNotApplyTheLastDistanceAfterSettling() {
        var solver = new MetalDetectorSolver();
        var position = new MetalDetectorSolver.PositionStability();
        solver.accept(CENTRE, 663, 79, 426, 1);
        for (int tick = 0; tick <= 5; tick++) position.tick(664, 79, 426, tick * 50_000_000L);
        assertTrue(position.stableAt(664, 79, 426));
        assertTrue(solver.candidates().isEmpty());
        solver.accept(CENTRE, 664, 79, 426, 2, java.util.List.of(), position.stableAt(664, 79, 426));
        assertEquals(java.util.List.of(new CrystalHollowsPosition(662, 78, 426)), solver.candidates());
    }

    @Test
    void waitsForStableReadingsAndNarrowsRoundedDistances() {
        MetalDetectorSolver solver = new MetalDetectorSolver();
        CrystalHollowsPosition chest = new CrystalHollowsPosition(662, 78, 426);
        double distance = roundedDistance(chest, 680.5, 80, 420.5);
        solver.accept(CENTRE, 680.5, 80, 420.5, distance);
        assertTrue(solver.candidates().isEmpty());
        solver.accept(CENTRE, 680.5, 80, 420.5, distance);
        assertTrue(solver.candidates().contains(chest));
        readTwice(solver, chest, 663.5, 80, 422.5);
        assertEquals(java.util.List.of(chest), solver.candidates());
        assertTrue(solver.solved());
        assertEquals(42, solver.knownPositions().size());

        CrystalHollowsPosition next = new CrystalHollowsPosition(738, 78, 374);
        readTwice(solver, next, 739.5, 80, 374.5);
        assertEquals(java.util.List.of(next), solver.candidates());
        solver.reset();
        assertTrue(solver.candidates().isEmpty());
        solver.accept(CENTRE, 739.5, 80, 374.5, roundedDistance(next, 739.5, 80, 374.5));
        assertTrue(solver.candidates().isEmpty());
    }

    @Test
    void usesRoundedChestTopDistanceAndSolvesWithoutAKeeperAnchor() {
        var chest = new CrystalHollowsPosition(0, 0, 0);
        var solver = new MetalDetectorSolver();
        observedTwice(solver, null, 1.049, 1, 0, 1.0, chest);
        assertEquals(java.util.List.of(chest), solver.candidates());
        assertTrue(solver.solved());
        solver.reset();
        observedTwice(solver, null, 1.051, 1, 0, 1.0, chest);
        assertTrue(solver.candidates().isEmpty());
        observedTwice(solver, null, 1.051, 1, 0, 1.1, chest);
        assertEquals(java.util.List.of(chest), solver.candidates());
    }

    @Test
    void acceptsTinyPositionDriftButWaitsAfterMeaningfulMovement() {
        var solver = new MetalDetectorSolver();
        var chest = new CrystalHollowsPosition(662, 78, 426);
        solver.accept(CENTRE, 663, 79, 426, 1);
        solver.accept(CENTRE, 663.0001, 79, 426, 1);
        assertEquals(java.util.List.of(chest), solver.candidates());
        solver.reset();
        solver.accept(CENTRE, 663, 79, 426, 1);
        solver.accept(CENTRE, 663.01, 79, 426, 1);
        assertTrue(solver.candidates().isEmpty());
        solver.accept(CENTRE, 663.01, 79, 426, 1);
        assertEquals(java.util.List.of(chest), solver.candidates());
    }

    @Test
    void visibilitySupplementsKnownPositionsWithoutRemovingHiddenMatches() {
        var solver = new MetalDetectorSolver();
        var hidden = new CrystalHollowsPosition(662, 78, 426);
        var visible = new CrystalHollowsPosition(664, 78, 426);
        observedTwice(solver, CENTRE, 663, 79, 426, 1, visible);
        assertTrue(solver.candidates().containsAll(java.util.List.of(hidden, visible)));
        assertFalse(solver.solved());
        observedTwice(solver, CENTRE, 662, 79, 426, 0, visible);
        assertEquals(java.util.List.of(hidden), solver.candidates());
        assertTrue(solver.solved());
    }

    @Test
    void newlyVisibleChestsMustSatisfyEarlierReadings() {
        var solver = new MetalDetectorSolver();
        var first = new CrystalHollowsPosition(0, 0, 0);
        var late = new CrystalHollowsPosition(2, 0, 0);
        observedTwice(solver, null, 0, 1, 1, 1, first);
        observedTwice(solver, null, 1, 1, 0, 1, first, late);
        assertEquals(java.util.List.of(first), solver.candidates());
    }

    @Test
    void missingOrIncorrectKeeperAnchorStillTriangulatesTreasure() {
        var chest = new CrystalHollowsPosition(606, 66, 265);
        for (var centre : java.util.Arrays.asList(null, new CrystalHollowsPosition(700, 120, 400))) {
            var solver = new MetalDetectorSolver();
            observedTwice(solver, centre, 601.5, 68, 261.5,
                    roundedDistance(chest, 601.5, 68, 261.5));
            assertTrue(solver.candidates().contains(chest));
            assertTrue(solver.candidates().size() > 1);
            observedTwice(solver, centre, 608.5, 69, 264.5,
                    roundedDistance(chest, 608.5, 69, 264.5));
            observedTwice(solver, centre, 605.5, 70, 268.5,
                    roundedDistance(chest, 605.5, 70, 268.5));
            assertEquals(java.util.List.of(chest), solver.candidates());
            assertTrue(solver.solved());
        }
    }

    private static void observedTwice(MetalDetectorSolver solver, CrystalHollowsPosition centre,
                                       double x, double y, double z, double distance,
                                       CrystalHollowsPosition... chests) {
        solver.accept(centre, x, y, z, distance, java.util.List.of(chests));
        solver.accept(centre, x, y, z, distance, java.util.List.of(chests));
    }

    @Test
    void parsesActionBarAndRejectsInvalidInput() {
        assertEquals(97.9, MetalDetectorSolver.distance("§3§lTREASURE: §b97.9m"));
        assertEquals(12.3, MetalDetectorSolver.distance("§3§lTREASURE: §b12.3m"));
        assertTrue(Double.isNaN(MetalDetectorSolver.distance("TREASURE: -1m")));
        assertTrue(Double.isNaN(MetalDetectorSolver.distance("Health: 100")));
        MetalDetectorSolver solver = new MetalDetectorSolver();
        solver.accept(null, 0, 0, 0, 10);
        solver.accept(CENTRE, 0, 0, 0, Double.NaN);
        assertTrue(solver.candidates().isEmpty());
    }

    private static void readTwice(MetalDetectorSolver solver, CrystalHollowsPosition chest,
                                  double x, double y, double z) {
        double distance = roundedDistance(chest, x, y, z);
        solver.accept(CENTRE, x, y, z, distance);
        solver.accept(CENTRE, x, y, z, distance);
    }

    private static double roundedDistance(CrystalHollowsPosition chest, double x, double y, double z) {
        double dx = chest.x() - x, dy = chest.y() + 1 - y, dz = chest.z() - z;
        return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 10) / 10.0;
    }
}
