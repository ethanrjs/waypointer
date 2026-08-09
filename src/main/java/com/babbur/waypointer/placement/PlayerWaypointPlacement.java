package com.babbur.waypointer.placement;

import com.babbur.waypointer.config.WaypointerConfig;

import java.util.Objects;

/** Converts a player position to the block used for a new waypoint. */
public final class PlayerWaypointPlacement {

    private static final double FEET_BOUNDARY_EPSILON = 1.0E-5;

    private PlayerWaypointPlacement() {
    }

    public static BlockPosition fromPlayer(double x, double y, double z, WaypointerConfig config) {
        Objects.requireNonNull(config, "config");

        // Subtract before flooring so partial blocks select their support block.
        double placementY = config.placeNewWaypointsBelowPlayer()
                ? y - FEET_BOUNDARY_EPSILON
                : y;

        return new BlockPosition(
                (int) Math.floor(x),
                (int) Math.floor(placementY),
                (int) Math.floor(z));
    }

    public record BlockPosition(int x, int y, int z) {}
}
