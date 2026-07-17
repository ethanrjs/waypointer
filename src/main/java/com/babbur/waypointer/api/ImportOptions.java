package com.babbur.waypointer.api;

/**
 * Options for importing waypoint share strings through the public API.
 */
public record ImportOptions(boolean targetCurrentZoneWhenUnknown) {

    /** Default import behavior: keep unknown-zone routes in the unknown bucket. */
    public static ImportOptions defaults() {
        return new ImportOptions(false);
    }

    /** Start building import options. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean targetCurrentZoneWhenUnknown;

        private Builder() {
        }

        /**
         * When true, routes imported with Waypointer's {@code unknown} zone are
         * moved to the current detected zone.
         */
        public Builder targetCurrentZoneWhenUnknown(boolean on) {
            this.targetCurrentZoneWhenUnknown = on;
            return this;
        }

        public ImportOptions build() {
            return new ImportOptions(targetCurrentZoneWhenUnknown);
        }
    }
}
