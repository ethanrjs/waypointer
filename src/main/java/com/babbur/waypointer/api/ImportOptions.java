package com.babbur.waypointer.api;

public record ImportOptions(boolean targetCurrentZoneWhenUnknown) {

    public static ImportOptions defaults() {
        return new ImportOptions(false);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean targetCurrentZoneWhenUnknown;

        private Builder() {
        }

        public Builder targetCurrentZoneWhenUnknown(boolean on) {
            this.targetCurrentZoneWhenUnknown = on;
            return this;
        }

        public ImportOptions build() {
            return new ImportOptions(targetCurrentZoneWhenUnknown);
        }
    }
}
