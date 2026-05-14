package dev.ethan.waypointer.placement;

import dev.ethan.waypointer.config.WaypointerConfig;

import java.util.Objects;

/**
 * Converts a player's precise world position into the block coordinates used
 * for player-relative waypoint creation.
 *
 * <p>The setting lives in config, but all add flows need the same interpretation.
 * Player Y is the bottom of the collision box: full blocks put it on an integer
 * boundary, while slabs, carpets, and other partial blocks put it inside the
 * supporting block's Y coordinate. When placing below the player, resolve the
 * supporting block directly instead of subtracting after flooring.
 */
public final class PlayerWaypointPlacement {

    private static final double FEET_BOUNDARY_EPSILON = 1.0E-5;

    private PlayerWaypointPlacement() {
    }

    public static BlockPosition fromPlayer(double x, double y, double z, WaypointerConfig config) {
        Objects.requireNonNull(config, "config");

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
