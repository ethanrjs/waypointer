package com.babbur.waypointer.crystal.compass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class WishingCompassSolverTest {

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(new Vec3d(754, 137, 239), new Vec3d(760, 134, 266),
                    new Vec3d(735, 98, 451), Crystal.JADE, false, false,
                    CrystalHollowsZone.MITHRIL_DEPOSITS),
            new Fixture(new Vec3d(454, 87, 776), new Vec3d(439, 85, 777),
                    new Vec3d(377, 87, 550), Crystal.AMBER, false, false,
                    CrystalHollowsZone.GOBLIN_HOLDOUT),
            new Fixture(new Vec3d(570, 120, 565), new Vec3d(591, 136, 579),
                    new Vec3d(604, 124, 681), Crystal.SAPPHIRE, false, false,
                    CrystalHollowsZone.PRECURSOR_REMNANTS),
            new Fixture(new Vec3d(454, 122, 459), new Vec3d(438, 126, 468),
                    new Vec3d(343, 72, 424), Crystal.AMETHYST, true, false,
                    CrystalHollowsZone.JUNGLE),
            new Fixture(new Vec3d(462, 58, 550), new Vec3d(449, 53, 556),
                    new Vec3d(737, 56, 444), Crystal.TOPAZ, false, false,
                    CrystalHollowsZone.MAGMA_FIELDS));

    @Test
    void triangulatesFiveCapturedFixturesWithinTwoBlocks() {
        for (Fixture fixture : FIXTURES) {
            WishingCompassSolver solver = new WishingCompassSolver(() -> 0L);
            solver.setTargetContext(contextWithMissing(fixture.missingCrystal()),
                    fixture.hasJungleKey(), fixture.hasKingsScent());
            capture(solver, fixture.firstUse(), fixture.solution(), fixture.zone(), 1_000);
            capture(solver, fixture.secondUse(), fixture.solution(), fixture.zone(), 2_000);
            WishingCompassSolver.SolveResult result = solver.lastResult()
                    .orElseThrow(() -> new AssertionError(fixture + " events=" + events(solver)));
            assertTrue(result.solution().distanceTo(fixture.solution()) < 2.0,
                    fixture + " -> " + result.solution());
            assertEquals(fixture.missingCrystal(), crystalFor(result.targets().iterator().next()));
        }
    }

    @Test
    void nextSearchNeverPairsWithThePreviousSolvedRays() {
        WishingCompassSolver solver = solverWithAllMissing();
        Vec3d oldTarget = new Vec3d(343, 72, 424);
        capture(solver, new Vec3d(400, 100, 400), oldTarget, CrystalHollowsZone.JUNGLE, 1_000);
        capture(solver, new Vec3d(420, 100, 400), oldTarget, CrystalHollowsZone.JUNGLE, 2_000);
        assertTrue(solver.lastResult().isPresent());
        Vec3d nextTarget = new Vec3d(300, 100, 300);
        capture(solver, new Vec3d(400, 100, 400), nextTarget, CrystalHollowsZone.JUNGLE, 3_000);
        assertEquals(1, solver.completedRays().size());
        assertTrue(solver.lastResult().isEmpty());
        assertEquals(WishingCompassSolver.State.NEED_SECOND_USE, solver.state());
        capture(solver, new Vec3d(420, 100, 400), nextTarget, CrystalHollowsZone.JUNGLE, 4_000);
        assertTrue(solver.lastResult().orElseThrow().solution().distanceTo(nextTarget) < 2);
    }

    @Test
    void tooCloseUsesDoNotFormPair() {
        WishingCompassSolver solver = solverWithAllMissing();
        Vec3d target = new Vec3d(343, 72, 424);
        capture(solver, new Vec3d(400, 100, 400), target, CrystalHollowsZone.JUNGLE, 1_000);
        solver.drainOutcomes();
        capture(solver, new Vec3d(401, 100, 400), target, CrystalHollowsZone.JUNGLE, 2_000);
        assertTrue(events(solver).contains(WishingCompassSolver.SolverEvent.NEED_SECOND_USE));
        assertTrue(solver.lastResult().isEmpty());
    }

    @Test
    void parallelTrailsAskPlayerToMoveSideways() {
        WishingCompassSolver solver = solverWithAllMissing();
        Vec3d direction = new Vec3d(1, 0, 0);
        captureDirection(solver, new Vec3d(300, 100, 300), direction,
                CrystalHollowsZone.JUNGLE, 1_000);
        solver.drainOutcomes();
        captureDirection(solver, new Vec3d(300, 100, 320), direction,
                CrystalHollowsZone.JUNGLE, 2_000);
        assertTrue(events(solver).contains(WishingCompassSolver.SolverEvent.NEARLY_PARALLEL));
    }

    @Test
    void raysFromDifferentZonesDoNotPair() {
        WishingCompassSolver solver = solverWithAllMissing();
        Vec3d target = new Vec3d(343, 72, 424);
        capture(solver, new Vec3d(400, 100, 400), target, CrystalHollowsZone.JUNGLE, 1_000);
        solver.drainOutcomes();
        capture(solver, new Vec3d(420, 100, 400), target,
                CrystalHollowsZone.GOBLIN_HOLDOUT, 2_000);
        assertTrue(events(solver).contains(WishingCompassSolver.SolverEvent.NEED_SECOND_USE));
    }

    private static void capture(WishingCompassSolver solver, Vec3d use, Vec3d target,
                                CrystalHollowsZone zone, long start) {
        Vec3d first = use.add(new Vec3d(0.3, 1.5, 0.2));
        captureDirection(solver, use, target.subtract(first).normalize(), zone, start);
    }

    private static void captureDirection(WishingCompassSolver solver, Vec3d use, Vec3d direction,
                                         CrystalHollowsZone zone, long start) {
        solver.onUse(use.x(), use.y(), use.z(), zone, start);
        Vec3d first = use.add(new Vec3d(0.3, 1.5, 0.2));
        for (int index = 0; index < 20; index++) {
            Vec3d point = first.add(direction.scale(index * 0.49));
            solver.onParticle(point.x(), point.y(), point.z(), start + 100 + index * 40L);
        }
        solver.onParticle(first.x(), first.y(), first.z(), start + 950);
    }

    private static List<WishingCompassSolver.SolverEvent> events(WishingCompassSolver solver) {
        return solver.drainOutcomes().stream().map(WishingCompassSolver.Outcome::event).toList();
    }

    private static WishingCompassSolver solverWithAllMissing() {
        WishingCompassSolver solver = new WishingCompassSolver(() -> 0L);
        solver.setTargetContext(new EnumMap<>(Crystal.class), true, true);
        return solver;
    }

    private static EnumMap<Crystal, CrystalState> contextWithMissing(Crystal missing) {
        EnumMap<Crystal, CrystalState> states = new EnumMap<>(Crystal.class);
        for (Crystal crystal : Crystal.values()) states.put(crystal, CrystalState.COLLECTED);
        states.put(missing, CrystalState.MISSING);
        return states;
    }

    private static Crystal crystalFor(WishingCompassTarget target) {
        return switch (target) {
            case MINES_OF_DIVAN -> Crystal.JADE;
            case GOBLIN_KING, GOBLIN_QUEEN -> Crystal.AMBER;
            case JUNGLE_TEMPLE, ODAWA -> Crystal.AMETHYST;
            case BAL -> Crystal.TOPAZ;
            case PRECURSOR_CITY -> Crystal.SAPPHIRE;
            case CRYSTAL_NUCLEUS -> throw new IllegalArgumentException("not a structure crystal");
        };
    }

    private record Fixture(Vec3d firstUse, Vec3d secondUse, Vec3d solution,
                           Crystal missingCrystal, boolean hasJungleKey, boolean hasKingsScent,
                           CrystalHollowsZone zone) {}
}
