package com.babbur.waypointer.crystal.compass;

import com.babbur.waypointer.crystal.CrystalHollowsGeometry;
import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** Deterministic Wishing Compass capture and triangulation state machine. */
public final class WishingCompassSolver {

    public static final double MIN_USE_DISTANCE = 8.0;
    public static final double MIN_PAIR_ANGLE_DEGREES = 3.0;
    public static final double MAX_SOLUTION_GAP = 25.0;
    public static final double HIGH_CONFIDENCE_GAP = 8.0;
    public static final int MAX_RAYS = 4;

    public enum State {
        IDLE,
        WAITING_PARTICLES,
        NEED_SECOND_USE,
        SOLVED,
        FAILED
    }

    public enum SolverEvent {
        USE_RECORDED,
        NUCLEUS_WARNING,
        TOO_CLOSE,
        RAY_CAPTURED,
        NEED_SECOND_USE,
        NEARLY_PARALLEL,
        INVALID_BEHIND_RAY,
        INVALID_OUTSIDE_HOLLOWS,
        INVALID_LARGE_GAP,
        NO_PARTICLES,
        TIMEOUT,
        TOO_SHORT,
        NO_TARGET,
        SOLVED
    }

    public enum SolutionConfidence {
        HIGH,
        LOW
    }

    public record SolveResult(
            Vec3d solution,
            double gap,
            SolutionConfidence confidence,
            Set<WishingCompassTarget> targets,
            CompassRay firstRay,
            CompassRay secondRay) {

        public SolveResult {
            targets = targets.isEmpty()
                    ? Set.of()
                    : Set.copyOf(EnumSet.copyOf(targets));
        }
    }

    public record Outcome(SolverEvent event, CompassRay ray, SolveResult result) {
        public Outcome {
            Objects.requireNonNull(event, "event");
        }
    }

    private record CapturedRay(CompassRay ray, Set<WishingCompassTarget> candidates,
                               Map<Crystal, CrystalState> crystals) {}
    private record Pair(CapturedRay first, CapturedRay second, double angle) {}

    private final LongSupplier clock;
    private final List<CapturedRay> rays = new ArrayList<>();
    private final ArrayDeque<Outcome> outcomes = new ArrayDeque<>();
    private final EnumMap<Crystal, CrystalState> targetCrystals = new EnumMap<>(Crystal.class);
    private EnumSet<WishingCompassTarget> targetCandidates =
            EnumSet.allOf(WishingCompassTarget.class);
    private CompassTrail pending;
    private boolean pendingCountsForSolving;
    private State state = State.IDLE;
    private SolveResult lastResult;

    public WishingCompassSolver(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public State state() { return state; }
    public Optional<SolveResult> lastResult() { return Optional.ofNullable(lastResult); }
    public List<CompassRay> completedRays() {
        return rays.stream().map(CapturedRay::ray).toList();
    }

    public void setTargetContext(Map<Crystal, CrystalState> crystals,
                                 boolean hasJungleKey, boolean hasKingsScent) {
        targetCrystals.clear();
        if (crystals != null) targetCrystals.putAll(crystals);
        targetCandidates = CompassTargetResolver.candidates(
                targetCrystals, hasJungleKey, hasKingsScent);
    }

    public Outcome onUse(double x, double y, double z, CrystalHollowsZone zone) {
        return onUse(x, y, z, zone, clock.getAsLong());
    }

    public Outcome onUse(double x, double y, double z, CrystalHollowsZone zone, long nowMillis) {
        Vec3d use = new Vec3d(x, y, z);
        pending = new CompassTrail(use, nowMillis, zone);
        pendingCountsForSolving = zone != CrystalHollowsZone.CRYSTAL_NUCLEUS;
        state = State.WAITING_PARTICLES;
        SolverEvent event;
        if (!pendingCountsForSolving) {
            event = SolverEvent.NUCLEUS_WARNING;
        } else if (!rays.isEmpty()
                && rays.getLast().ray().usePosition().distanceTo(use) < MIN_USE_DISTANCE) {
            event = SolverEvent.TOO_CLOSE;
        } else {
            event = SolverEvent.USE_RECORDED;
        }
        return emit(event, null, null);
    }

    public void onParticle(double x, double y, double z, long nowMillis) {
        if (pending == null) return;
        CompassTrail.State before = pending.state();
        pending.onParticle(new Vec3d(x, y, z), nowMillis);
        finishPendingIfTerminal(before);
    }

    public void onParticle(double x, double y, double z) {
        onParticle(x, y, z, clock.getAsLong());
    }

    public void tick(long nowMillis) {
        if (pending == null) return;
        CompassTrail.State before = pending.state();
        pending.tick(nowMillis);
        finishPendingIfTerminal(before);
    }

    public void tick() {
        tick(clock.getAsLong());
    }

    public Outcome serverNoTarget() {
        pending = null;
        state = State.FAILED;
        return emit(SolverEvent.NO_TARGET, null, null);
    }

    public List<Outcome> drainOutcomes() {
        List<Outcome> drained = new ArrayList<>(outcomes);
        outcomes.clear();
        return List.copyOf(drained);
    }

    public void reset() {
        pending = null;
        rays.clear();
        outcomes.clear();
        lastResult = null;
        state = State.IDLE;
    }

    private void finishPendingIfTerminal(CompassTrail.State before) {
        if (pending == null || before == pending.state()) return;
        if (pending.state() == CompassTrail.State.FAILED) {
            SolverEvent event = switch (pending.failure()) {
                case NO_PARTICLES -> SolverEvent.NO_PARTICLES;
                case TIMEOUT -> SolverEvent.TIMEOUT;
                case TOO_SHORT -> SolverEvent.TOO_SHORT;
                case NONE -> throw new IllegalStateException("failed trail has no failure reason");
            };
            pending = null;
            state = State.FAILED;
            emit(event, null, null);
            return;
        }
        if (pending.state() != CompassTrail.State.COMPLETE) return;

        CompassRay ray = pending.ray().orElseThrow();
        pending = null;
        emit(SolverEvent.RAY_CAPTURED, ray, null);
        if (!pendingCountsForSolving) {
            state = State.IDLE;
            return;
        }
        CapturedRay captured = new CapturedRay(ray, Set.copyOf(targetCandidates),
                Map.copyOf(targetCrystals));
        rays.add(captured);
        if (rays.size() > MAX_RAYS) rays.removeFirst();
        solveBestPair();
    }

    private void solveBestPair() {
        Pair best = null;
        for (int firstIndex = 0; firstIndex < rays.size(); firstIndex++) {
            CapturedRay first = rays.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < rays.size(); secondIndex++) {
                CapturedRay second = rays.get(secondIndex);
                if (!pairEligible(first, second)) continue;
                double angle = CompassMath.angleDegrees(first.ray(), second.ray());
                if (best == null || angle > best.angle()) best = new Pair(first, second, angle);
            }
        }
        if (best == null) {
            state = State.NEED_SECOND_USE;
            emit(SolverEvent.NEED_SECOND_USE, rays.getLast().ray(), null);
            return;
        }
        Optional<CompassMath.ClosestPoints> closest =
                CompassMath.closestPoints(best.first().ray(), best.second().ray());
        if (closest.isEmpty()) {
            state = State.NEED_SECOND_USE;
            emit(SolverEvent.NEARLY_PARALLEL, rays.getLast().ray(), null);
            return;
        }
        CompassMath.ClosestPoints points = closest.orElseThrow();
        // Two captured regression pairs are below three degrees despite intersecting with
        // essentially zero spread. Preserve those stable, high-confidence intersections while
        // still rejecting low-angle pairs whose closest points disagree.
        if (best.angle() < MIN_PAIR_ANGLE_DEGREES && points.gap() > HIGH_CONFIDENCE_GAP) {
            state = State.NEED_SECOND_USE;
            emit(SolverEvent.NEARLY_PARALLEL, rays.getLast().ray(), null);
            return;
        }
        Vec3d solution = points.midpoint();
        if (best.first().ray().direction().dot(solution.subtract(best.first().ray().origin())) <= 0.0
                || best.second().ray().direction().dot(
                        solution.subtract(best.second().ray().origin())) <= 0.0) {
            state = State.FAILED;
            emit(SolverEvent.INVALID_BEHIND_RAY, rays.getLast().ray(), null);
            return;
        }
        if (!CrystalHollowsGeometry.insideHollows(solution.x(), solution.y(), solution.z())) {
            state = State.FAILED;
            emit(SolverEvent.INVALID_OUTSIDE_HOLLOWS, rays.getLast().ray(), null);
            return;
        }
        double gap = points.gap();
        if (gap > MAX_SOLUTION_GAP) {
            state = State.FAILED;
            emit(SolverEvent.INVALID_LARGE_GAP, rays.getLast().ray(), null);
            return;
        }
        EnumSet<WishingCompassTarget> resolved = CompassTargetResolver.resolve(
                solution, best.second().ray().zone(), best.second().crystals(),
                best.second().candidates());
        SolutionConfidence confidence = gap <= HIGH_CONFIDENCE_GAP
                ? SolutionConfidence.HIGH
                : SolutionConfidence.LOW;
        lastResult = new SolveResult(solution, gap, confidence, resolved,
                best.first().ray(), best.second().ray());
        state = State.SOLVED;
        emit(SolverEvent.SOLVED, rays.getLast().ray(), lastResult);
    }

    private static boolean pairEligible(CapturedRay first, CapturedRay second) {
        return first.ray().zone() == second.ray().zone()
                && first.candidates().equals(second.candidates())
                && first.ray().usePosition().distanceTo(second.ray().usePosition())
                        >= MIN_USE_DISTANCE;
    }

    private Outcome emit(SolverEvent event, CompassRay ray, SolveResult result) {
        Outcome outcome = new Outcome(event, ray, result);
        outcomes.add(outcome);
        return outcome;
    }
}
