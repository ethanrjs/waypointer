package com.babbur.waypointer.core;

/**
 * A single point in the world rendered by the mod.
 *
 * <p>Immutable by design: edits produce a new instance. This keeps the tick loop
 * safe to iterate while the UI mutates a group, and makes undo trivial to add later.
 *
 * <p>Color is 0xRRGGBB; alpha is controlled per-render by the renderer based on state
 * (completed / current / upcoming).
 *
 * <p>customRadius is in blocks. 0 means "use the group's defaultRadius".
 *
 * <p>Temporary waypoints carry a {@code tempMode} + {@code expiresAtMillis}:
 * <ul>
 *   <li>{@link #TEMP_NONE} -- a normal, persisted waypoint.
 *   <li>{@link #TEMP_TIME} -- removed once {@link System#currentTimeMillis()} passes {@code expiresAtMillis}.
 *   <li>{@link #TEMP_UNTIL_REACHED} -- removed by the proximity tracker when it advances past this waypoint.
 *   <li>{@link #TEMP_UNTIL_LEAVE} -- removed when the player leaves the server.
 * </ul>
 * All three temp modes are wiped on disconnect (Storage deliberately skips them
 * during save) so nothing ephemeral accumulates in the user's config file.
 */
public record Waypoint(
        int x,
        int y,
        int z,
        String name,
        int color,
        int flags,
        double customRadius,
        int tempMode,
        long expiresAtMillis,
        int preciseX,
        int preciseY,
        int preciseZ) {

    public static final int FLAG_HIDE_BEACON  = 1;
    public static final int FLAG_HIDE_NAME    = 1 << 1;
    public static final int FLAG_THROUGH_WALL = 1 << 2;
    public static final int FLAG_LOCKED_COLOR = 1 << 3; // excluded from gradient auto-recolor
    /** Structural flag: this waypoint is a one-level child of the nearest previous main waypoint. */
    public static final int FLAG_SUBWAYPOINT  = 1 << 4;
    /** Visual flag: subwaypoint renders as a 1/16 block cube centered in its block. */
    public static final int FLAG_SMALL_SUBWAYPOINT = 1 << 5;
    /** Visual flag: subwaypoint renders filled even when the global box style is outlined. */
    public static final int FLAG_FILLED_SUBWAYPOINT = 1 << 6;
    /** Visibility flag: subwaypoint hides once its parent main waypoint has been activated. */
    public static final int FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED = 1 << 7;
    /** Visual flag: this waypoint renders only through the normal depth buffer when it is in view. */
    public static final int FLAG_DEPTH_CHECKED = 1 << 8;
    /** Dungeon-room behavior flag: standing on this waypoint's block explicitly advances/skips it. */
    public static final int FLAG_SKIP_ON_STAND = 1 << 9;
    /** Dungeon-room behavior flag: interacting with this waypoint's block explicitly advances/skips it. */
    public static final int FLAG_SKIP_ON_INTERACT = 1 << 10;
    /** Dungeon-room behavior flag: mining this waypoint's block explicitly advances/skips it. */
    public static final int FLAG_SKIP_ON_MINE = 1 << 11;
    /** Dungeon metadata: completing this action completes one secret stage. */
    public static final int FLAG_DUNGEON_SECRET = 1 << 12;
    /** Dungeon metadata: this action is an Etherwarp landing. */
    public static final int FLAG_DUNGEON_ETHERWARP = 1 << 13;
    /** Dungeon metadata: this action is a Dungeonbreaker block. */
    public static final int FLAG_DUNGEON_DUNGEONBREAKER = 1 << 14;
    /** Dungeon metadata: this action uses Superboom TNT. */
    public static final int FLAG_DUNGEON_SUPERBOOM = 1 << 15;
    /** Dungeon metadata: this action launches an Ender Pearl. */
    public static final int FLAG_DUNGEON_PEARL = 1 << 16;
    /** Render-only helper paired with the previous pearl action as its landing target. */
    public static final int FLAG_DUNGEON_PEARL_TARGET = 1 << 17;
    /** Dungeon metadata: this secret completes when its item is picked up. */
    public static final int FLAG_DUNGEON_ITEM = 1 << 18;
    /** Dungeon metadata: this secret completes when its bat dies. */
    public static final int FLAG_DUNGEON_BAT = 1 << 19;
    /** Any event-driven completion flag used by sequential dungeon actions. */
    public static final int DUNGEON_COMPLETION_FLAGS = FLAG_SKIP_ON_STAND
            | FLAG_SKIP_ON_INTERACT
            | FLAG_SKIP_ON_MINE;
    /** Dungeon flags that must survive route sharing and persistence. */
    public static final int DUNGEON_METADATA_FLAGS = FLAG_DUNGEON_SECRET
            | FLAG_DUNGEON_ETHERWARP
            | FLAG_DUNGEON_DUNGEONBREAKER
            | FLAG_DUNGEON_SUPERBOOM
            | FLAG_DUNGEON_PEARL
            | FLAG_DUNGEON_PEARL_TARGET
            | FLAG_DUNGEON_ITEM
            | FLAG_DUNGEON_BAT;
    /** Visual flags that only make sense while {@link #FLAG_SUBWAYPOINT} is present. */
    public static final int SUBWAYPOINT_STYLE_FLAGS = FLAG_SMALL_SUBWAYPOINT
            | FLAG_FILLED_SUBWAYPOINT
            | FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED;
    /** Flags that define route structure and must survive even when visual flags are stripped from exports. */
    public static final int STRUCTURAL_FLAGS  = FLAG_SUBWAYPOINT;
    public static final int PERSISTENT_BEHAVIOR_FLAGS = STRUCTURAL_FLAGS | DUNGEON_METADATA_FLAGS;

    public static final int TEMP_NONE = 0;
    public static final int TEMP_TIME = 1;
    public static final int TEMP_UNTIL_REACHED = 2;
    public static final int TEMP_UNTIL_LEAVE = 3;

    public static final int DEFAULT_COLOR = 0x4FE05A; // bright green -- reads clearly against most biomes
    public static final double MIN_REACH_RADIUS = 0.5;
    public static final double DEFAULT_REACH_RADIUS = 3.0;
    public static final double MAX_REACH_RADIUS = 100.0;
    public static final int PRECISE_SCALE = 16;
    private static final int PRECISE_BLOCK_CENTER_OFFSET = PRECISE_SCALE / 2;

    public Waypoint {
        name = name == null ? "" : name;
        customRadius = normalizeCustomRadius(customRadius);
        x = blockCoordinateFromPrecise(preciseX);
        y = blockCoordinateFromPrecise(preciseY);
        z = blockCoordinateFromPrecise(preciseZ);
    }

    /**
     * Backward-compatible constructor for call sites that pre-date temp waypoints.
     * Anything built this way is treated as permanent (tempMode=0, expiresAt=0).
     */
    public Waypoint(int x, int y, int z, String name, int color, int flags, double customRadius) {
        this(x, y, z, name, color, flags, customRadius, TEMP_NONE, 0L);
    }

    public Waypoint(int x, int y, int z, String name, int color, int flags,
                    double customRadius, int tempMode, long expiresAtMillis) {
        this(x, y, z, name, color, flags, customRadius, tempMode, expiresAtMillis,
                preciseBlockCenter(x), preciseBlockCenter(y), preciseBlockCenter(z));
    }

    public static Waypoint at(int x, int y, int z) {
        return new Waypoint(x, y, z, "", DEFAULT_COLOR, 0, 0.0);
    }

    public static double normalizeCustomRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0) return 0.0;
        return Math.min(radius, MAX_REACH_RADIUS);
    }

    public static double normalizeDefaultRadius(double radius) {
        if (!Double.isFinite(radius)) return DEFAULT_REACH_RADIUS;
        return Math.clamp(radius, MIN_REACH_RADIUS, MAX_REACH_RADIUS);
    }

    public boolean hasName() {
        return !name.isEmpty();
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public boolean isTemp() {
        return tempMode != TEMP_NONE;
    }

    public boolean isSubwaypoint() {
        return hasFlag(FLAG_SUBWAYPOINT);
    }

    /** True iff this is a time-based temp and the deadline has passed. */
    public boolean isExpired(long nowMillis) {
        return tempMode == TEMP_TIME && expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }

    /**
     * Invalid persisted/default temp modes fall back to REACH: no timer to
     * reason about, no server-scope tie-in, just "delete it after I go there."
     */
    public static int normalizeTempMode(int mode) {
        if (mode < TEMP_TIME || mode > TEMP_UNTIL_LEAVE) return TEMP_UNTIL_REACHED;
        return mode;
    }

    public static String tempModeName(int mode) {
        return switch (mode) {
            case TEMP_TIME          -> "TIME";
            case TEMP_UNTIL_REACHED -> "REACH";
            case TEMP_UNTIL_LEAVE   -> "LEAVE";
            default -> "?";
        };
    }

    public Waypoint withName(String newName) {
        return new Waypoint(x, y, z, newName, color, flags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    public Waypoint withColor(int newColor) {
        return new Waypoint(x, y, z, name, newColor, flags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    public Waypoint withFlags(int newFlags) {
        return new Waypoint(x, y, z, name, color, newFlags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    public Waypoint withRadius(double newRadius) {
        return new Waypoint(x, y, z, name, color, flags, newRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    public Waypoint withPos(int nx, int ny, int nz) {
        return new Waypoint(nx, ny, nz, name, color, flags, customRadius,
                tempMode, expiresAtMillis);
    }

    public Waypoint withPreciseSixteenths(int nextPreciseX, int nextPreciseY, int nextPreciseZ) {
        return new Waypoint(x, y, z, name, color, flags, customRadius,
                tempMode, expiresAtMillis, nextPreciseX, nextPreciseY, nextPreciseZ);
    }

    public double centerX() {
        return (preciseX + (isSubwaypoint() && hasFlag(FLAG_SMALL_SUBWAYPOINT) ? 0.5 : 0.0))
                / (double) PRECISE_SCALE;
    }

    public double centerY() {
        return (preciseY + (isSubwaypoint() && hasFlag(FLAG_SMALL_SUBWAYPOINT) ? 0.5 : 0.0))
                / (double) PRECISE_SCALE;
    }

    public double centerZ() {
        return (preciseZ + (isSubwaypoint() && hasFlag(FLAG_SMALL_SUBWAYPOINT) ? 0.5 : 0.0))
                / (double) PRECISE_SCALE;
    }

    public boolean hasCustomPrecisePosition() {
        return preciseX != preciseBlockCenter(x)
                || preciseY != preciseBlockCenter(y)
                || preciseZ != preciseBlockCenter(z);
    }

    public static int snapToPreciseSixteenths(double coordinate) {
        return (int) Math.floor(coordinate * PRECISE_SCALE);
    }

    public Waypoint withSubwaypoint(boolean subwaypoint) {
        int nextFlags = subwaypoint
                ? flags | FLAG_SUBWAYPOINT
                : flags & ~FLAG_SUBWAYPOINT & ~SUBWAYPOINT_STYLE_FLAGS;
        return withFlags(nextFlags);
    }

    /** Flip a waypoint's temp mode. Typically used to build a brand-new temp waypoint from {@link #at}. */
    public Waypoint withTemp(int mode, long expiresAt) {
        return new Waypoint(x, y, z, name, color, flags, customRadius, mode, expiresAt,
                preciseX, preciseY, preciseZ);
    }

    private static int preciseBlockCenter(int blockCoordinate) {
        return blockCoordinate * PRECISE_SCALE + PRECISE_BLOCK_CENTER_OFFSET;
    }

    private static int blockCoordinateFromPrecise(int preciseCoordinate) {
        return Math.floorDiv(preciseCoordinate, PRECISE_SCALE);
    }
}
