package dev.ethan.waypointer.api;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.core.Waypoint;

/**
 * Immutable input for creating a Waypointer waypoint through the public API.
 *
 * <p>Use {@link #at(int, int, int)} for concise call sites, or
 * {@link #builder()} when a mod fills values from its own data model.
 */
public record WaypointSpec(
        int x,
        int y,
        int z,
        String name,
        int color,
        int flags,
        double radius,
        String source) {

    public WaypointSpec {
        name = name == null ? "" : name;
        color &= 0xFFFFFF;
        radius = Math.max(0.0, radius);
        source = source == null ? "" : source.trim();
    }

    public static WaypointSpec at(int x, int y, int z) {
        return builder().position(x, y, z).build();
    }

    /**
     * Start building a waypoint input.
     *
     * <pre>{@code
     * WaypointSpec waypoint = WaypointSpec.builder()
     *         .position(125, 72, -34)
     *         .name("Burrow")
     *         .color(0xFFD166)
     *         .build();
     * }</pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    public WaypointSpec name(String newName) {
        return new WaypointSpec(x, y, z, newName, color, flags, radius, source);
    }

    public WaypointSpec color(int newColor) {
        return new WaypointSpec(x, y, z, name, newColor, flags, radius, source);
    }

    public WaypointSpec flags(int newFlags) {
        return new WaypointSpec(x, y, z, name, color, newFlags, radius, source);
    }

    public WaypointSpec radius(double newRadius) {
        return new WaypointSpec(x, y, z, name, color, flags, newRadius, source);
    }

    public WaypointSpec source(String newSource) {
        return new WaypointSpec(x, y, z, name, color, flags, radius, newSource);
    }

    Waypoint toWaypoint() {
        return new Waypoint(x, y, z, name, color, flags, radius);
    }

    Waypoint toTempWaypoint() {
        String label = name.isBlank() ? WaypointCodec.Options.sanitizeLabel(source) : name;
        return new Waypoint(x, y, z, label, color, flags, radius)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L);
    }

    public static final class Builder {
        private int x;
        private int y;
        private int z;
        private String name = "";
        private int color = Waypoint.DEFAULT_COLOR;
        private int flags;
        private double radius;
        private String source = "";

        private Builder() {
        }

        /** Set the block coordinates for the waypoint. */
        public Builder position(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** Label shown by Waypointer. Empty or {@code null} means no label. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** RGB color as {@code 0xRRGGBB}. Alpha is handled by Waypointer. */
        public Builder color(int color) {
            this.color = color;
            return this;
        }

        /**
         * Waypoint render flags. Prefer Waypointer's constants when available
         * rather than hardcoding bit values.
         */
        public Builder flags(int flags) {
            this.flags = flags;
            return this;
        }

        /** Custom reach radius in blocks. Zero uses the route default. */
        public Builder radius(double radius) {
            this.radius = radius;
            return this;
        }

        /**
         * Short source name for temp waypoints, used to label markers such as
         * {@code "Example Mod"} or {@code "Party Chat"}.
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public WaypointSpec build() {
            return new WaypointSpec(x, y, z, name, color, flags, radius, source);
        }
    }
}
