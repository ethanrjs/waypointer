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
        return fit(width, height, requestedSize, corner, navigation, 1, false, 0, 0);
    }

    public static CrystalHollowsMinimapLayout fit(int width, int height, int requestedSize, int corner,
                                                 boolean navigation, double scale, boolean customPosition,
                                                 double xNorm, double yNorm) {
        scale = Double.isFinite(scale) ? Math.clamp(scale, 0.5, 2.0) : 1.0;
        int footer = navigation ? FOOTER : PADDING;
        int size = Math.min((int) Math.round(Math.clamp(requestedSize, 96, 224) * scale),
                Math.min(width - PADDING * 2 - 16, height - HEADER - footer - 44));
        if (size < Math.round(80 * Math.min(scale, 1))) return null;
        int panelWidth = size + PADDING * 2;
        int panelHeight = size + HEADER + footer;
        int x = placementX(width, panelWidth, customPosition ? xNorm : corner & 1);
        int y = placementY(height, panelHeight, customPosition ? yNorm : (corner & 2) / 2);
        return new CrystalHollowsMinimapLayout(x, y, size, navigation);
    }

    public static int placementX(int width, int panelWidth, double normalized) {
        return placement(width - panelWidth - 16, normalized);
    }

    public static int placementY(int height, int panelHeight, double normalized) {
        return placement(height - panelHeight - 44, normalized);
    }

    public static double normalizeX(int width, int panelWidth, double x) {
        return normalize(width - panelWidth - 16, x);
    }

    public static double normalizeY(int height, int panelHeight, double y) {
        return normalize(height - panelHeight - 44, y);
    }

    private static int placement(int available, double normalized) {
        return 8 + (int) Math.round(Math.max(0, available)
                * (Double.isFinite(normalized) ? Math.clamp(normalized, 0, 1) : 0));
    }

    private static double normalize(int available, double position) {
        return available > 0 && Double.isFinite(position) ? Math.clamp((position - 8) / available, 0, 1) : 0;
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

    public int marker(double coordinate) { return Math.round(markerCenter(coordinate) - 0.5f); }

    /** Schematic quadrants meet at the Nucleus; retain its exact center for moving markers. */
    public float markerCenter(double coordinate) {
        if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Non-finite map coordinate");
        double center = CrystalHollowsGeometry.NUCLEUS_CENTRE_X;
        double fraction = coordinate <= center
                ? (coordinate - CrystalHollowsGeometry.MIN_XZ) / (center - CrystalHollowsGeometry.MIN_XZ)
                : 1 + (coordinate - center) / (CrystalHollowsGeometry.MAX_XZ - center);
        return (float) Math.clamp(0.5 + fraction * (size - 1) / 2.0, 5.5, size - 5.5);
    }

    /** Try each side of a marker; crowded labels yield to the map's symbols. */
    public Rect label(int markerX, int markerY, int width, java.util.List<Rect> occupied) {
        return label(markerX, markerY, width, occupied, 8);
    }

    public Rect label(int markerX, int markerY, int width, java.util.List<Rect> occupied, int clearance) {
        for (Rect candidate : new Rect[]{
                new Rect(markerX + clearance, markerY - 4, width, 9),
                new Rect(markerX - clearance + 1 - width, markerY - 4, width, 9),
                new Rect(markerX - width / 2, markerY + clearance - 1, width, 9),
                new Rect(markerX - width / 2, markerY - clearance - 7, width, 9)}) {
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
