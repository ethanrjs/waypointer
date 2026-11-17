package com.babbur.waypointer.crystal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Jasper glass of one Fairy Grotto, as block offsets from a route's pivot. Grottos are one of
 * 18 fixed rooms placed anywhere in the Crystal Hollows, so a route recorded in one grotto is found
 * again by matching this glass against the glass loaded around the player, in any of four turns.
 * Mined glass simply stops voting; a match needs only part of the layout.
 *
 * <p>A layout travels with its route as short text (see {@link #toText()}), so shared grotto
 * routes place themselves on the receiver's side too.
 */
public final class FairyGrottoLayout {

    public static final int MIN_CELLS = 12;
    public static final int MAX_CELLS = 96;
    static final int REACH_XZ = 63;
    static final int REACH_Y = 31;
    private static final int SPAN_XZ = REACH_XZ * 2 + 1;
    private static final int SPAN_Y = REACH_Y * 2 + 1;
    private static final String TEXT_PREFIX = "1.";
    private static final int MAX_TEXT = 1024;
    static final int MIN_MATCH = 8;
    static final double MATCH_FRACTION = 0.35;
    static final double NEAR_TIE = 0.85;
    private static final int SAMPLE = 32;
    private static final int CANDIDATES_PER_TURN = 12;

    public record Match(StructurePlacement placement, int matched) {}

    private final int[] cells;

    private FairyGrottoLayout(int[] cells) {
        this.cells = cells;
    }

    /**
     * Layout from flat {@code x,y,z} offsets, kept in one canonical order. Null when there are too
     * few or too many distinct cells, or a cell is out of reach of the pivot.
     */
    public static FairyGrottoLayout ofOffsets(int[] offsets) {
        if (offsets == null || offsets.length % 3 != 0) return null;
        long[] indices = new long[offsets.length / 3];
        for (int i = 0; i < indices.length; i++) {
            int dx = offsets[i * 3];
            int dy = offsets[i * 3 + 1];
            int dz = offsets[i * 3 + 2];
            if (Math.abs(dx) > REACH_XZ || Math.abs(dy) > REACH_Y || Math.abs(dz) > REACH_XZ) return null;
            indices[i] = index(dx, dy, dz);
        }
        long[] unique = Arrays.stream(indices).sorted().distinct().toArray();
        if (unique.length < MIN_CELLS || unique.length > MAX_CELLS) return null;
        int[] cells = new int[unique.length * 3];
        for (int i = 0; i < unique.length; i++) {
            long index = unique[i];
            cells[i * 3] = (int) (index % SPAN_XZ) - REACH_XZ;
            cells[i * 3 + 2] = (int) (index / SPAN_XZ % SPAN_XZ) - REACH_XZ;
            cells[i * 3 + 1] = (int) (index / SPAN_XZ / SPAN_XZ) - REACH_Y;
        }
        return new FairyGrottoLayout(cells);
    }

    /**
     * Layout of the given world glass blocks around a pivot. Past {@link #MAX_CELLS} it keeps an
     * even spread from nearest to farthest, so mining one corner of the grotto never removes the
     * whole layout.
     */
    public static FairyGrottoLayout capture(CrystalHollowsPosition pivot, Collection<Long> worldGlass) {
        if (pivot == null || worldGlass == null) return null;
        List<long[]> sorted = new ArrayList<>();
        for (long packed : worldGlass) {
            long dx = unpackX(packed) - pivot.x();
            long dy = unpackY(packed) - pivot.y();
            long dz = unpackZ(packed) - pivot.z();
            if (Math.abs(dx) > REACH_XZ || Math.abs(dy) > REACH_Y || Math.abs(dz) > REACH_XZ) continue;
            sorted.add(new long[] { dx, dy, dz, dx * dx + dy * dy + dz * dz });
        }
        sorted.sort(Comparator.<long[]>comparingLong(c -> c[3])
                .thenComparingLong(c -> c[0]).thenComparingLong(c -> c[1]).thenComparingLong(c -> c[2]));
        int count = Math.min(sorted.size(), MAX_CELLS);
        int[] offsets = new int[count * 3];
        for (int i = 0; i < count; i++) {
            long[] cell = sorted.get((int) ((long) i * sorted.size() / count));
            offsets[i * 3] = (int) cell[0];
            offsets[i * 3 + 1] = (int) cell[1];
            offsets[i * 3 + 2] = (int) cell[2];
        }
        return ofOffsets(offsets);
    }

    /**
     * Text form saved and shared with the route: {@code "1."} then unpadded base64url of the
     * cells' box indices in ascending order, each written as a uvarint gap from the previous one
     * (the first from -1). Box index = {@code ((dy+31)*127 + (dz+63))*127 + (dx+63)}.
     */
    public String toText() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long previous = -1;
        for (int i = 0; i < cells.length; i += 3) {
            long index = index(cells[i], cells[i + 1], cells[i + 2]);
            writeUvarint(bytes, index - previous - 1);
            previous = index;
        }
        return TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    /** Parses {@link #toText()}; null for anything else. */
    public static FairyGrottoLayout fromText(String text) {
        if (text == null || text.length() > MAX_TEXT || !text.startsWith(TEXT_PREFIX)) return null;
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(text.substring(TEXT_PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        List<Integer> offsets = new ArrayList<>();
        long previous = -1;
        int at = 0;
        while (at < bytes.length) {
            long gap = 0;
            int shift = 0;
            int b;
            do {
                if (at >= bytes.length || shift > 28) return null;
                b = bytes[at++] & 0xFF;
                gap |= (long) (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            long index = previous + 1 + gap;
            if (index >= (long) SPAN_XZ * SPAN_XZ * SPAN_Y) return null;
            offsets.add((int) (index % SPAN_XZ) - REACH_XZ);
            offsets.add((int) (index / SPAN_XZ / SPAN_XZ) - REACH_Y);
            offsets.add((int) (index / SPAN_XZ % SPAN_XZ) - REACH_XZ);
            previous = index;
        }
        FairyGrottoLayout layout = ofOffsets(offsets.stream().mapToInt(Integer::intValue).toArray());
        return layout != null && layout.toText().equals(text) ? layout : null;
    }

    /** {@code text} if it is a valid layout text, else the empty string. */
    public static String normalizeText(String text) {
        return fromText(text) == null ? "" : text;
    }

    private static long index(int dx, int dy, int dz) {
        return ((long) (dy + REACH_Y) * SPAN_XZ + (dz + REACH_XZ)) * SPAN_XZ + (dx + REACH_XZ);
    }

    private static void writeUvarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    public int size() {
        return cells.length / 3;
    }

    public int[] offsets() {
        return cells.clone();
    }

    int needed() {
        return Math.max(MIN_MATCH, (int) Math.ceil(size() * MATCH_FRACTION));
    }

    /** Layout cells present in {@code worldGlass} when placed at {@code placement}. */
    public int matches(StructurePlacement placement, Set<Long> worldGlass) {
        int found = 0;
        for (int i = 0; i < cells.length; i += 3) {
            CrystalHollowsPosition world = placement.blockToWorld(cells[i], cells[i + 1], cells[i + 2]);
            if (worldGlass.contains(pack(world.x(), world.y(), world.z()))) found++;
        }
        return found;
    }

    /**
     * Best placement of this layout in {@code worldGlass}. When two grottos of the same room are in
     * range, the one whose pivot is nearest {@code near} wins.
     */
    public Optional<Match> match(Set<Long> worldGlass, CrystalHollowsPosition near) {
        if (worldGlass == null || worldGlass.size() < MIN_MATCH) return Optional.empty();
        int[] world = new int[worldGlass.size() * 3];
        int w = 0;
        for (long packed : worldGlass) {
            world[w++] = unpackX(packed);
            world[w++] = unpackY(packed);
            world[w++] = unpackZ(packed);
        }
        int step = Math.max(1, size() / SAMPLE);
        int sampled = (size() + step - 1) / step;
        int minVotes = Math.max(2, (int) Math.ceil(sampled * MATCH_FRACTION));

        List<Match> accepted = new ArrayList<>();
        for (int turns = 0; turns < 4; turns++) {
            Map<Long, Integer> votes = new HashMap<>();
            for (int i = 0; i < size(); i += step) {
                int[] turned = StructurePlacement.turnBlock(cells[i * 3], cells[i * 3 + 2], turns);
                int dy = cells[i * 3 + 1];
                for (int j = 0; j < world.length; j += 3) {
                    votes.merge(pack(world[j] - turned[0], world[j + 1] - dy, world[j + 2] - turned[1]),
                            1, Integer::sum);
                }
            }
            int turn = turns;
            votes.entrySet().stream()
                    .filter(entry -> entry.getValue() >= minVotes)
                    .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                    .limit(CANDIDATES_PER_TURN)
                    .forEach(entry -> {
                        long pivot = entry.getKey();
                        StructurePlacement placement = new StructurePlacement(new CrystalHollowsPosition(
                                unpackX(pivot), unpackY(pivot), unpackZ(pivot)), turn);
                        int matched = matches(placement, worldGlass);
                        if (matched >= needed()) accepted.add(new Match(placement, matched));
                    });
        }
        if (accepted.isEmpty()) return Optional.empty();
        int best = accepted.stream().mapToInt(Match::matched).max().orElse(0);
        return accepted.stream()
                .filter(match -> match.matched() >= best * NEAR_TIE)
                .min(Comparator.<Match>comparingDouble(match -> near == null ? 0.0
                                : match.placement().pivot().distanceSquared(near))
                        .thenComparing(Comparator.comparingInt(Match::matched).reversed())
                        .thenComparingInt(match -> match.placement().quarterTurns()));
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    static int unpackX(long packed) {
        return signExtend((int) ((packed >>> 42) & 0x1FFFFF));
    }

    static int unpackY(long packed) {
        return signExtend((int) ((packed >>> 21) & 0x1FFFFF));
    }

    static int unpackZ(long packed) {
        return signExtend((int) (packed & 0x1FFFFF));
    }

    private static int signExtend(int value) {
        return (value << 11) >> 11;
    }
}
