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
 * world plus the optional catalog identity that {@code DungeonRoomData}
 * attaches after matching room-core hashes or authored fingerprints.
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
        List<Long> segments,
        String roomId,
        String roomName) {

    public DungeonRoom {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(shape, "shape");
        if (direction == null) direction = Direction.NW;
        segments = segments == null ? List.of() : List.copyOf(segments);
        roomId = roomId == null ? "" : roomId;
        roomName = roomName == null ? "" : roomName;
    }

    public DungeonRoom(DungeonRoomType type, DungeonRoomShape shape, Direction direction,
                       int physicalCornerX, int physicalCornerZ, List<Long> segments) {
        this(type, shape, direction, physicalCornerX, physicalCornerZ, segments, "", "");
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

    public boolean hasRoomId() {
        return !roomId.isBlank();
    }

    public DungeonRoom withDefinition(String id, String name) {
        return new DungeonRoom(type, shape, direction, physicalCornerX, physicalCornerZ,
                segments, id, name);
    }

    /**
     * Human-readable fallback while named-room fingerprinting is still absent.
     * This deliberately says "1x2 Room" rather than inventing a named room like
     * "Lava Ravine"; real names require block-skeleton matching against a
     * curated room dataset.
     */
    public String displayName() {
        if (!roomName.isBlank()) return roomName;
        if (type != DungeonRoomType.ROOM) return friendlyType(type);
        return friendlyShape(shape) + " Room";
    }

    private static String friendlyType(DungeonRoomType type) {
        return switch (type) {
            case ENTRANCE -> "Entrance Room";
            case ROOM -> "Room";
            case PUZZLE -> "Puzzle Room";
            case TRAP -> "Trap Room";
            case MINIBOSS -> "Miniboss Room";
            case FAIRY -> "Fairy Room";
            case BLOOD -> "Blood Room";
            case UNKNOWN -> "Unknown Room";
        };
    }

    private static String friendlyShape(DungeonRoomShape shape) {
        return switch (shape) {
            case ONE_BY_ONE -> "1x1";
            case ONE_BY_TWO -> "1x2";
            case ONE_BY_THREE -> "1x3";
            case ONE_BY_FOUR -> "1x4";
            case TWO_BY_TWO -> "2x2";
            case L_SHAPE -> "L-shaped";
            case UNKNOWN -> "Unknown";
        };
    }
}
