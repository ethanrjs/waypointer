package com.babbur.waypointer.crystal;

/** North-up projection of the whole lobby; X runs right and Z runs down. */
public record CrystalHollowsMinimapLayout(int x, int y, int size, boolean navigation) {
    public static final int PADDING = 6;
    public static final int HEADER = 20;
    public static final int FOOTER = 18;

    public CrystalHollowsMinimapLayout(int x, int y, int size) {
        this(x, y, size, false);
    }

    public static CrystalHollowsMinimapLayout fit(int width, int height, int requestedSize, int corner) {
        return fit(width, height, requestedSize, corner, false);
    }

    public static CrystalHollowsMinimapLayout fit(int width, int height, int requestedSize, int corner,
                                                 boolean navigation) {
        int footer = navigation ? FOOTER : PADDING;
        int size = Math.min(Math.clamp(requestedSize, 96, 224),
                Math.min(width - PADDING * 2 - 16, height - HEADER - footer - 44));
        if (size < 80) return null;
        int x = (corner & 1) == 0 ? 8 : width - size - PADDING * 2 - 8;
        int y = (corner & 2) == 0 ? 8 : height - size - HEADER - footer - 36;
        return new CrystalHollowsMinimapLayout(x, y, size, navigation);
    }

    public int mapX() { return x + PADDING; }
    public int mapY() { return y + HEADER; }
    public int width() { return size + PADDING * 2; }
    public int height() { return size + HEADER + (navigation ? FOOTER : PADDING); }

    /** Match the map's odd/even size so the schematic platform shares its exact center. */
    public Rect nucleus() {
        int cx = project(CrystalHollowsGeometry.NUCLEUS_CENTRE_X);
        int cy = project(CrystalHollowsGeometry.NUCLEUS_CENTRE_Z);
        int radius = Math.min(Math.min(cx - project(462), project(564) - cx),
                Math.min(cy - project(461), project(565) - cy));
        int diameter = radius * 2 + (size & 1);
        return new Rect(mapX() + (size - diameter) / 2, mapY() + (size - diameter) / 2,
                diameter, diameter);
    }

    public record Rect(int x, int y, int width, int height) {
        public boolean overlaps(Rect other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }

    public int marker(double coordinate) { return Math.clamp(project(coordinate), 5, size - 6); }

    /** Try each side of a marker; crowded labels yield to the map's symbols. */
    public Rect label(int markerX, int markerY, int width, java.util.List<Rect> occupied) {
        for (Rect candidate : new Rect[]{
                new Rect(markerX + 8, markerY - 4, width, 9),
                new Rect(markerX - 7 - width, markerY - 4, width, 9),
                new Rect(markerX - width / 2, markerY + 7, width, 9),
                new Rect(markerX - width / 2, markerY - 15, width, 9)}) {
            if (candidate.x < mapX() + 2 || candidate.y < mapY() + 2
                    || candidate.x + width > mapX() + size - 2
                    || candidate.y + candidate.height > mapY() + size - 2) continue;
            Rect padded = new Rect(candidate.x - 2, candidate.y - 1, width + 4, 11);
            if (occupied.stream().noneMatch(padded::overlaps)) return candidate;
        }
        return null;
    }

    public int project(double coordinate) {
        if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Non-finite map coordinate");
        double fraction = (coordinate - CrystalHollowsGeometry.MIN_XZ)
                / (CrystalHollowsGeometry.MAX_XZ - CrystalHollowsGeometry.MIN_XZ);
        return (int) Math.round(Math.clamp(fraction, 0, 1) * (size - 1));
    }

    public static boolean contains(double x, double z) {
        return x >= CrystalHollowsGeometry.MIN_XZ && x < CrystalHollowsGeometry.MAX_XZ
                && z >= CrystalHollowsGeometry.MIN_XZ && z < CrystalHollowsGeometry.MAX_XZ;
    }
}
