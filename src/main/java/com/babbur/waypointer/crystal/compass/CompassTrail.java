package com.babbur.waypointer.crystal.compass;

import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Particle-chain state for a single Wishing Compass use. */
public final class CompassTrail {

    public static final double MAX_PARTICLE_GAP = 0.75;
    public static final double MAX_FIRST_DISTANCE = 9.0;
    public static final long TIMEOUT_MILLIS = 5_000L;
    public static final int WATCHDOG_TICKS = 40;
    public static final int WATCHDOG_MIN_POINTS = 5;
    public static final double MIN_TRAIL_LENGTH = 3.0;

    public enum State {
        WAITING_FIRST,
        CHAINING,
        COMPLETE,
        FAILED
    }

    public enum Failure {
        NONE,
        NO_PARTICLES,
        TIMEOUT,
        TOO_SHORT
    }

    private final Vec3d usePosition;
    private final long useMillis;
    private final CrystalHollowsZone zone;
    private final List<Vec3d> points = new ArrayList<>();
    private State state = State.WAITING_FIRST;
    private Failure failure = Failure.NONE;
    private Vec3d first;
    private Vec3d previous;
    private int ticksSinceParticle;
    private CompassRay ray;

    public CompassTrail(Vec3d usePosition, long useMillis, CrystalHollowsZone zone) {
        this.usePosition = usePosition;
        this.useMillis = useMillis;
        this.zone = zone;
    }

    public State state() { return state; }
    public Failure failure() { return failure; }
    public List<Vec3d> points() { return Collections.unmodifiableList(points); }
    public Optional<CompassRay> ray() { return Optional.ofNullable(ray); }

    public void onParticle(Vec3d particle, long nowMillis) {
        if (state == State.COMPLETE || state == State.FAILED) return;
        if (nowMillis - useMillis >= TIMEOUT_MILLIS) {
            finishAfterTimeout();
            return;
        }
        if (state == State.WAITING_FIRST) {
            if (particle.distanceTo(usePosition) > MAX_FIRST_DISTANCE) return;
            first = particle;
            previous = particle;
            points.add(particle);
            ticksSinceParticle = 0;
            state = State.CHAINING;
            return;
        }

        if (particle.distanceTo(previous) <= MAX_PARTICLE_GAP) {
            points.add(particle);
            previous = particle;
            ticksSinceParticle = 0;
        } else if (particle.distanceTo(first) <= MAX_PARTICLE_GAP) {
            complete();
        }
    }

    /** Advances the 40-client-tick watchdog and wall-clock timeout. */
    public void tick(long nowMillis) {
        if (state == State.COMPLETE || state == State.FAILED) return;
        if (nowMillis - useMillis >= TIMEOUT_MILLIS) {
            finishAfterTimeout();
            return;
        }
        if (state != State.CHAINING) return;
        ticksSinceParticle++;
        if (points.size() >= WATCHDOG_MIN_POINTS && ticksSinceParticle >= WATCHDOG_TICKS) {
            complete();
        }
    }

    private void finishAfterTimeout() {
        if (state == State.CHAINING && points.size() >= WATCHDOG_MIN_POINTS) {
            complete();
            return;
        }
        fail(points.isEmpty() ? Failure.NO_PARTICLES : Failure.TIMEOUT);
    }

    private void complete() {
        if (first == null || previous == null || first.distanceTo(previous) < MIN_TRAIL_LENGTH) {
            fail(Failure.TOO_SHORT);
            return;
        }
        CompassMath.RayFit fit = CompassMath.fitRay(points);
        ray = new CompassRay(fit.origin(), fit.direction(), points.size(),
                first.distanceTo(previous), usePosition.x(), usePosition.y(), usePosition.z(),
                useMillis, zone);
        state = State.COMPLETE;
    }

    private void fail(Failure reason) {
        failure = reason;
        state = State.FAILED;
    }
}
