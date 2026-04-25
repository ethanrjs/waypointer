package dev.ethan.waypointer.dungeon;

import java.util.List;
import java.util.Objects;

/**
 * The dungeon room the player is currently standing in, as identified by
 * Waypointer at runtime: type, shape, the canonical-frame physical corner,
 * the orientation that maps room-local coordinates back into the world, and
 * the NW-corner positions of the 32x32 segments that make up the footprint.
 *
 * <p>This is intentionally a "thin" room descriptor -- it carries only the
 * geometry needed to project room-local secret coordinates back into the
 * world. The named room (e.g. "Waterfall", "Three Chests") is NOT identified
 * here; that's the job of a future block-fingerprint matcher (see
 * {@code Room.checkBlock} in Skyblocker for the algorithm we'd port). Until
 * that lands, dungeon waypoint data is keyed by shape + segment topology
 * rather than by named room.
 *
 * <p>Segments are encoded as {@code packed = (x << 32) | (z & 0xFFFFFFFF)}
 * to avoid allocating a list of pairs; the helpers
 * {@link #packSegment}/{@link #segmentX}/{@link #segmentZ} do the round trip.
 */
public record DungeonRoom(
        DungeonRoomType type,
        DungeonRoomShape shape,
        Direction direction,
        int physicalCornerX,
        int physicalCornerZ,
        List<Long> segments) {

    public DungeonRoom {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(shape, "shape");
        if (direction == null) direction = Direction.NW;
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public static long packSegment(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    public static int segmentX(long packed) { return (int) (packed >> 32); }
    public static int segmentZ(long packed) { return (int) packed; }

    /**
     * Stable identity key, used by the state tracker to detect "we just walked
     * into a different room" without needing per-field equality. Not meant to
     * be human-readable.
     */
    public String identityKey() {
        return type.name() + ":" + shape.name() + ":" + direction.name()
                + ":" + physicalCornerX + "," + physicalCornerZ
                + ":n=" + segments.size();
    }
}
