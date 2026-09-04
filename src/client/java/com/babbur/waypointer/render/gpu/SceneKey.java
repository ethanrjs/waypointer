package com.babbur.waypointer.render.gpu;

/** Fingerprint and local origin for retained static geometry. */
public record SceneKey(long hash, int originX, int originY, int originZ,
                       Object levelIdentity) {

    public static final SceneKey NONE = new SceneKey(0L, 0, 0, 0, null);

    public static final class Builder {
        private long state = 0x9E3779B97F4A7C15L;
        private int originX;
        private int originY;
        private int originZ;
        private Object levelIdentity;

        public Builder mix(long value) {
            state = mix64(state ^ value);
            return this;
        }

        public Builder mix(int value) {
            return mix((long) value);
        }

        public Builder mix(boolean value) {
            return mix(value ? 0x1L : 0x2L);
        }

        public Builder mix(double value) {
            return mix(Double.doubleToLongBits(value));
        }

        public Builder mixEnum(Enum<?> value) {
            return mix(value == null ? -1 : value.ordinal());
        }

        public Builder levelIdentity(Object value) {
            levelIdentity = value;
            return this;
        }

        public Builder origin(int x, int y, int z) {
            originX = x;
            originY = y;
            originZ = z;
            return mix(x).mix(y).mix(z);
        }

        public SceneKey finish() {
            return new SceneKey(mix64(state), originX, originY, originZ, levelIdentity);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static int originFor(double coordinate) {
        return (int) Math.floor(coordinate / 16.0) * 16;
    }

    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SceneKey key
                && hash == key.hash
                && originX == key.originX
                && originY == key.originY
                && originZ == key.originZ
                && levelIdentity == key.levelIdentity;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(hash);
        result = 31 * result + originX;
        result = 31 * result + originY;
        result = 31 * result + originZ;
        return 31 * result + System.identityHashCode(levelIdentity);
    }
}
