package dev.ethan.waypointer.placement;

import dev.ethan.waypointer.config.WaypointerConfig;

import java.util.Objects;

/**
 * Converts a player's precise world position into the block coordinates used
 * for player-relative waypoint creation.
 *
 * <p>The setting lives in config, but all add flows need the same interpretation:
 * floor the player's current block and, by default, target the block below the
 * player's feet so markers are visible on the floor instead of inside the player.
 */
public final class PlayerWaypointPlacement {

    private PlayerWaypointPlacement() {
    }

    public static BlockPosition fromPlayer(double x, double y, double z, WaypointerConfig config) {
        Objects.requireNonNull(config, "config");

        int blockY = (int) Math.floor(y);
        if (config.placeNewWaypointsBelowPlayer()) {
            blockY -= 1;
        }

        return new BlockPosition(
                (int) Math.floor(x),
                blockY,
                (int) Math.floor(z));
    }

    public record BlockPosition(int x, int y, int z) {}
}
