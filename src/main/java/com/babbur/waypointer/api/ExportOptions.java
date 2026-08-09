package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointCodec;

import java.util.Objects;

public final class ExportOptions {

    private final ExportTarget target;
    private final boolean includeNames;
    private final boolean includeColors;
    private final boolean includeRadii;
    private final boolean includeWaypointFlags;
    private final boolean includeGroupMeta;
    private final boolean includeZone;
    private final String label;

    private ExportOptions(Builder builder) {
        this.target = Objects.requireNonNull(builder.target, "target");
        this.includeNames = builder.includeNames;
        this.includeColors = builder.includeColors;
        this.includeRadii = builder.includeRadii;
        this.includeWaypointFlags = builder.includeWaypointFlags;
        this.includeGroupMeta = builder.includeGroupMeta;
        this.includeZone = builder.includeZone;
        this.label = WaypointCodec.Options.sanitizeLabel(builder.label);
    }

    public static ExportOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ExportTarget target() {
        return target;
    }

    public boolean includeNames() {
        return includeNames;
    }

    public boolean includeColors() {
        return includeColors;
    }

    public boolean includeRadii() {
        return includeRadii;
    }

    public boolean includeWaypointFlags() {
        return includeWaypointFlags;
    }

    public boolean includeGroupMeta() {
        return includeGroupMeta;
    }

    /**
     * Whether exported routes keep the island they were recorded on. When
     * false the island is stripped and importers place the route on whatever
     * island the recipient is standing on.
     */
    public boolean includeZone() {
        return includeZone;
    }

    public String label() {
        return label;
    }

    WaypointCodec.Options toCodecOptions() {
        return WaypointCodec.Options.builder()
                .includeNames(includeNames)
                .includeColors(includeColors)
                .includeRadii(includeRadii)
                .includeWaypointFlags(includeWaypointFlags)
                .includeGroupMeta(includeGroupMeta)
                .includeZone(includeZone)
                .label(label)
                .build();
    }

    public static final class Builder {
        private ExportTarget target = ExportTarget.WAYPOINTER;
        private boolean includeNames = true;
        private boolean includeColors = true;
        private boolean includeRadii = true;
        private boolean includeWaypointFlags = true;
        private boolean includeGroupMeta = true;
        private boolean includeZone = true;
        private String label = "";

        private Builder() {
        }

        public Builder target(ExportTarget target) {
            this.target = Objects.requireNonNull(target, "target");
            return this;
        }

        public Builder includeNames(boolean includeNames) {
            this.includeNames = includeNames;
            return this;
        }

        public Builder includeColors(boolean includeColors) {
            this.includeColors = includeColors;
            return this;
        }

        public Builder includeRadii(boolean includeRadii) {
            this.includeRadii = includeRadii;
            return this;
        }

        public Builder includeWaypointFlags(boolean includeWaypointFlags) {
            this.includeWaypointFlags = includeWaypointFlags;
            return this;
        }

        public Builder includeGroupMeta(boolean includeGroupMeta) {
            this.includeGroupMeta = includeGroupMeta;
            return this;
        }

        public Builder includeZone(boolean includeZone) {
            this.includeZone = includeZone;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public ExportOptions build() {
            return new ExportOptions(this);
        }
    }
}
