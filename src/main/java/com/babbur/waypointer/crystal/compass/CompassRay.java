package com.babbur.waypointer.crystal.compass;

import com.babbur.waypointer.crystal.CrystalHollowsZone;
import java.util.Objects;

/** A fitted Wishing Compass particle ray and the use that produced it. */
public record CompassRay(
        Vec3d origin,
        Vec3d direction,
        int pointCount,
        double lengthBlocks,
        double useX,
        double useY,
        double useZ,
        long useMillis,
        CrystalHollowsZone zone) {

    public CompassRay {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        direction = direction.normalize();
    }

    public Vec3d usePosition() {
        return new Vec3d(useX, useY, useZ);
    }
}
