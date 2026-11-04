package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;

public record WaypointSpec(
        int x,
        int y,
        int z,
        String name,
        int color,
        int flags,
        double radius,
        String source,
        int preciseX,
        int preciseY,
        int preciseZ) {

    public WaypointSpec {
        name = name == null ? "" : name;
        color &= 0xFFFFFF;
        radius = Waypoint.normalizeCustomRadius(radius);
        source = source == null ? "" : source.trim();
        x = Waypoint.blockCoordinateFromPrecise(preciseX);
        y = Waypoint.blockCoordinateFromPrecise(preciseY);
        z = Waypoint.blockCoordinateFromPrecise(preciseZ);
    }

    public WaypointSpec(int x, int y, int z, String name, int color, int flags,
                        double radius, String source) {
        this(x, y, z, name, color, flags, radius, source,
                Waypoint.preciseBlockCenter(x),
                Waypoint.preciseBlockCenter(y),
                Waypoint.preciseBlockCenter(z));
    }

    public static WaypointSpec at(int x, int y, int z) {
        return builder().position(x, y, z).build();
    }

    public static WaypointSpec atPreciseSixteenths(int preciseX, int preciseY, int preciseZ) {
        return builder().precisePositionSixteenths(preciseX, preciseY, preciseZ).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public WaypointSpec name(String newName) {
        return new WaypointSpec(x, y, z, newName, color, flags, radius, source,
                preciseX, preciseY, preciseZ);
    }

    public WaypointSpec color(int newColor) {
        return new WaypointSpec(x, y, z, name, newColor, flags, radius, source,
                preciseX, preciseY, preciseZ);
    }

    public WaypointSpec flags(int newFlags) {
        return new WaypointSpec(x, y, z, name, color, newFlags, radius, source,
                preciseX, preciseY, preciseZ);
    }

    public WaypointSpec radius(double newRadius) {
        return new WaypointSpec(x, y, z, name, color, flags, newRadius, source,
                preciseX, preciseY, preciseZ);
    }

    public WaypointSpec source(String newSource) {
        return new WaypointSpec(x, y, z, name, color, flags, radius, newSource,
                preciseX, preciseY, preciseZ);
    }

    Waypoint toWaypoint() {
        validatePersistentName();
        return new Waypoint(x, y, z, name, color, flags, radius,
                Waypoint.TEMP_NONE, 0L, preciseX, preciseY, preciseZ);
    }

    void validatePersistentName() {
        WaypointCodec.validateRouteDisplayName(name, "waypoint name");
    }

    Waypoint toTempWaypoint() {
        String label = name.isBlank() ? WaypointCodec.Options.sanitizeLabel(source) : name;
        return new Waypoint(x, y, z, label, color, flags, radius,
                Waypoint.TEMP_NONE, 0L, preciseX, preciseY, preciseZ)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L);
    }

    public static final class Builder {
        private int x;
        private int y;
        private int z;
        private int preciseX = Waypoint.preciseBlockCenter(0);
        private int preciseY = Waypoint.preciseBlockCenter(0);
        private int preciseZ = Waypoint.preciseBlockCenter(0);
        private String name = "";
        private int color = Waypoint.DEFAULT_COLOR;
        private int flags;
        private double radius;
        private String source = "";

        private Builder() {
        }

        public Builder position(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.preciseX = Waypoint.preciseBlockCenter(x);
            this.preciseY = Waypoint.preciseBlockCenter(y);
            this.preciseZ = Waypoint.preciseBlockCenter(z);
            return this;
        }

        public Builder precisePositionSixteenths(int preciseX, int preciseY, int preciseZ) {
            this.preciseX = preciseX;
            this.preciseY = preciseY;
            this.preciseZ = preciseZ;
            this.x = Waypoint.blockCoordinateFromPrecise(preciseX);
            this.y = Waypoint.blockCoordinateFromPrecise(preciseY);
            this.z = Waypoint.blockCoordinateFromPrecise(preciseZ);
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder flags(int flags) {
            this.flags = flags;
            return this;
        }

        public Builder radius(double radius) {
            this.radius = radius;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public WaypointSpec build() {
            return new WaypointSpec(x, y, z, name, color, flags, radius, source,
                    preciseX, preciseY, preciseZ);
        }
    }
}
