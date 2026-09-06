package com.babbur.waypointer.crystal;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MetalDetectorSolver {
    private static final Pattern DISTANCE = Pattern.compile("\\bTREASURE:\\s*(\\d{1,3}(?:\\.\\d)?)m\\b");
    private static final int[][] CHESTS = {
        {-38,-22,26}, {38,-22,-26}, {-40,-22,18}, {-41,-20,22}, {-5,-21,16},
        {40,-22,-30}, {-42,-20,-28}, {-43,-22,-40}, {42,-19,-41}, {43,-21,-16},
        {-1,-22,-20}, {6,-21,28}, {7,-21,11}, {7,-21,22}, {-12,-21,-44},
        {12,-22,31}, {12,-22,-22}, {12,-21,7}, {12,-21,-43}, {-14,-21,43},
        {-14,-21,22}, {-17,-21,20}, {-20,-22,0}, {1,-21,20}, {19,-22,29},
        {20,-22,0}, {20,-21,-26}, {-23,-22,40}, {22,-21,-14}, {-24,-22,12},
        {23,-22,26}, {23,-22,-39}, {24,-22,27}, {25,-22,17}, {29,-21,-44},
        {-31,-21,-12}, {-31,-21,-40}, {30,-21,-25}, {-32,-21,-40}, {-36,-20,42},
        {-37,-21,-14}, {-37,-21,-22}
    };

    private CrystalHollowsPosition centre;
    private Reading previous;
    private List<CrystalHollowsPosition> knownPositions = List.of();
    private final List<Reading> readings = new ArrayList<>();
    private List<CrystalHollowsPosition> candidates = List.of();

    public static double distance(String message) {
        var match = DISTANCE.matcher(CrystalHollowsSidebar.stripFormatting(message));
        return match.find() ? Double.parseDouble(match.group(1)) : Double.NaN;
    }

    public List<CrystalHollowsPosition> candidates() { return candidates; }
    public List<CrystalHollowsPosition> knownPositions() { return knownPositions; }
    public boolean solved() { return candidates.size() == 1; }

    public void reset() {
        centre = null;
        previous = null;
        candidates = List.of();
        knownPositions = List.of();
        readings.clear();
    }

    public void accept(CrystalHollowsPosition centre, double x, double y, double z, double distance) {
        accept(centre, x, y, z, distance, List.of());
    }

    public void accept(CrystalHollowsPosition centre, double x, double y, double z, double distance,
                       Collection<CrystalHollowsPosition> visibleChests) {
        accept(centre, x, y, z, distance, visibleChests, false);
    }

    public void accept(CrystalHollowsPosition centre, double x, double y, double z, double distance,
                       Collection<CrystalHollowsPosition> visibleChests, boolean positionStable) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Double.isFinite(distance) || distance < 0 || distance > 200) {
            reset();
            return;
        }
        if (!Objects.equals(this.centre, centre)) {
            reset();
            this.centre = centre;
            if (centre != null) {
                List<CrystalHollowsPosition> positions = new ArrayList<>(CHESTS.length);
                for (int[] offset : CHESTS) positions.add(new CrystalHollowsPosition(
                        centre.x() + offset[0], centre.y() + offset[1], centre.z() + offset[2]));
                knownPositions = List.copyOf(positions);
            }
        }
        Reading reading = new Reading(x, y, z, distance);
        // A fresh packet after observed standstill need not wait for another packet.
        boolean stable = positionStable || previous != null && reading.stableWith(previous);
        previous = reading;
        if (!stable) return;
        var matches = new LinkedHashSet<CrystalHollowsPosition>();
        for (CrystalHollowsPosition candidate : candidates) {
            if (reading.matches(candidate)) matches.add(candidate);
        }
        if (matches.isEmpty()) {
            // A contradictory reading starts the next treasure search.
            readings.clear();
            for (CrystalHollowsPosition position : knownPositions) {
                if (reading.matches(position)) matches.add(position);
            }
            if (matches.isEmpty()) addDistanceShell(reading, matches);
        }
        // ponytail: after 64 readings, only narrow existing candidates; reset starts a fresh search.
        // Keeping every retained reading prevents a newly visible chest undoing earlier evidence.
        if (readings.size() < 64) {
            for (CrystalHollowsPosition chest : visibleChests) {
                if (chest != null && reading.matches(chest)
                        && readings.stream().allMatch(old -> old.matches(chest))) matches.add(chest);
            }
            if (!readings.contains(reading)) readings.add(reading);
        }
        candidates = List.copyOf(matches);
    }

    // Divan treasure occupies Y 65..75. Search the rounded-distance shell, not the
    // entire volume, when no keeper anchor is loaded (or its template does not match).
    private static void addDistanceShell(Reading reading, Collection<CrystalHollowsPosition> matches) {
        double lower = Math.max(0, reading.distance - 0.05);
        double upper = reading.distance + 0.05;
        for (int y = 65; y <= 75; y++) {
            double dy = y + 1 - reading.y;
            for (int x = (int) Math.ceil(reading.x - upper); x <= Math.floor(reading.x + upper); x++) {
                double dx = x - reading.x;
                double remainder = upper * upper - dx * dx - dy * dy;
                if (remainder < 0) continue;
                double far = Math.sqrt(remainder);
                double near = Math.sqrt(Math.max(0, lower * lower - dx * dx - dy * dy));
                addZRange(reading, matches, x, y, reading.z - far, reading.z - near);
                addZRange(reading, matches, x, y, reading.z + near, reading.z + far);
            }
        }
    }

    private static void addZRange(Reading reading, Collection<CrystalHollowsPosition> matches,
                                  int x, int y, double min, double max) {
        for (int z = (int) Math.ceil(min); z <= Math.floor(max); z++) {
            var position = new CrystalHollowsPosition(x, y, z);
            if (reading.matches(position)) matches.add(position);
        }
    }

    private record Reading(double x, double y, double z, double distance) {
        boolean stableWith(Reading other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return distance == other.distance && dx * dx + dy * dy + dz * dz <= 1.0e-6;
        }
        boolean matches(CrystalHollowsPosition chest) {
            double dx = chest.x() - x;
            double dy = chest.y() + 1 - y;
            double dz = chest.z() - z;
            return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 10) / 10.0 == distance;
        }
    }

    static final class PositionStability {
        private double x, y, z;
        private int ticks = -1;
        private long stationarySince;
        private boolean stable;

        void reset() { ticks = -1; stable = false; }

        void tick(double x, double y, double z, long now) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                reset();
            } else if (ticks < 0 || !samePosition(x, y, z)) {
                this.x = x;
                this.y = y;
                this.z = z;
                ticks = 0;
                stationarySince = now;
                stable = false;
            } else {
                if (ticks < 5) ticks++;
                stable = ticks >= 5 && now - stationarySince >= 250_000_000L;
            }
        }

        boolean stableAt(double x, double y, double z) {
            return stable && samePosition(x, y, z);
        }

        private boolean samePosition(double x, double y, double z) {
            double dx = this.x - x, dy = this.y - y, dz = this.z - z;
            return dx * dx + dy * dy + dz * dz <= 1.0e-6;
        }
    }
}
