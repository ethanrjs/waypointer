package com.babbur.waypointer.core;

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
        int preciseZ,
        WaypointStyle styleOverride) {

    public static final int FLAG_HIDE_BEACON  = 1;
    public static final int FLAG_HIDE_NAME    = 1 << 1;
    public static final int FLAG_THROUGH_WALL = 1 << 2;
    public static final int FLAG_LOCKED_COLOR = 1 << 3;
    public static final int FLAG_SUBWAYPOINT  = 1 << 4;
    public static final int FLAG_SMALL_SUBWAYPOINT = 1 << 5;
    public static final int FLAG_FILLED_SUBWAYPOINT = 1 << 6;
    public static final int FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED = 1 << 7;
    public static final int FLAG_DEPTH_CHECKED = 1 << 8;
    public static final int FLAG_SKIP_ON_STAND = 1 << 9;
    public static final int FLAG_SKIP_ON_INTERACT = 1 << 10;
    public static final int FLAG_SKIP_ON_MINE = 1 << 11;
    public static final int FLAG_DUNGEON_SECRET = 1 << 12;
    public static final int FLAG_DUNGEON_ETHERWARP = 1 << 13;
    public static final int FLAG_DUNGEON_DUNGEONBREAKER = 1 << 14;
    public static final int FLAG_DUNGEON_SUPERBOOM = 1 << 15;
    public static final int FLAG_DUNGEON_PEARL = 1 << 16;
    public static final int FLAG_DUNGEON_PEARL_TARGET = 1 << 17;
    public static final int FLAG_DUNGEON_ITEM = 1 << 18;
    public static final int FLAG_DUNGEON_BAT = 1 << 19;
    public static final int FLAG_DISABLED = 1 << 20;
    public static final int FLAG_STEP_ANY_ORDER = 1 << 21;
    public static final int DUNGEON_COMPLETION_FLAGS = FLAG_SKIP_ON_STAND
            | FLAG_SKIP_ON_INTERACT
            | FLAG_SKIP_ON_MINE;
    public static final int DUNGEON_METADATA_FLAGS = FLAG_DUNGEON_SECRET
            | FLAG_DUNGEON_ETHERWARP
            | FLAG_DUNGEON_DUNGEONBREAKER
            | FLAG_DUNGEON_SUPERBOOM
            | FLAG_DUNGEON_PEARL
            | FLAG_DUNGEON_PEARL_TARGET
            | FLAG_DUNGEON_ITEM
            | FLAG_DUNGEON_BAT;
    public static final int SUBWAYPOINT_STYLE_FLAGS = FLAG_SMALL_SUBWAYPOINT
            | FLAG_FILLED_SUBWAYPOINT
            | FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED;
    public static final int STRUCTURAL_FLAGS  = FLAG_SUBWAYPOINT;
    public static final int PERSISTENT_BEHAVIOR_FLAGS = STRUCTURAL_FLAGS
            | DUNGEON_METADATA_FLAGS
            | FLAG_DISABLED
            | FLAG_STEP_ANY_ORDER;

    public static final int TEMP_NONE = 0;
    public static final int TEMP_TIME = 1;
    public static final int TEMP_UNTIL_REACHED = 2;
    public static final int TEMP_UNTIL_LEAVE = 3;

    public static final int DEFAULT_COLOR = 0x4FE05A;
    public static final double MIN_REACH_RADIUS = 0.5;
    public static final double DEFAULT_REACH_RADIUS = 3.0;
    public static final double MAX_REACH_RADIUS = 100.0;
    public static final int PRECISE_SCALE = 16;
    private static final int PRECISE_BLOCK_CENTER_OFFSET = PRECISE_SCALE / 2;
    public static final int MIN_BLOCK_COORDINATE = -134_217_728;
    public static final int MAX_BLOCK_COORDINATE = 134_217_727;

    public Waypoint {
        styleOverride = styleOverride == null ? WaypointStyle.EMPTY : styleOverride;
        name = name == null ? "" : name;
        customRadius = normalizeCustomRadius(customRadius);
        x = blockCoordinateFromPrecise(preciseX);
        y = blockCoordinateFromPrecise(preciseY);
        z = blockCoordinateFromPrecise(preciseZ);
    }

    public Waypoint(int x, int y, int z, String name, int color, int flags, double customRadius) {
        this(x, y, z, name, color, flags, customRadius, TEMP_NONE, 0L);
    }

    public Waypoint(int x, int y, int z, String name, int color, int flags,
                    double customRadius, int tempMode, long expiresAtMillis) {
        this(x, y, z, name, color, flags, customRadius, tempMode, expiresAtMillis,
                preciseBlockCenter(x), preciseBlockCenter(y), preciseBlockCenter(z));
    }

    public Waypoint(int x, int y, int z, String name, int color, int flags,
                    double customRadius, int tempMode, long expiresAtMillis,
                    int preciseX, int preciseY, int preciseZ) {
        this(x, y, z, name, color, flags, customRadius, tempMode, expiresAtMillis,
                preciseX, preciseY, preciseZ, WaypointStyle.EMPTY);
    }

    public Waypoint withStyleOverride(WaypointStyle style) {
        return new Waypoint(x, y, z, name, color, flags, customRadius, tempMode,
                expiresAtMillis, preciseX, preciseY, preciseZ, style);
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

    public boolean isDisabled() {
        return hasFlag(FLAG_DISABLED);
    }

    public boolean isExpired(long nowMillis) {
        return tempMode == TEMP_TIME && expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }

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
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ, styleOverride);
    }

    public Waypoint withColor(int newColor) {
        return new Waypoint(x, y, z, name, newColor, flags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ, styleOverride);
    }

    public Waypoint withFlags(int newFlags) {
        return new Waypoint(x, y, z, name, color, newFlags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ, styleOverride);
    }

    public Waypoint withRadius(double newRadius) {
        return new Waypoint(x, y, z, name, color, flags, newRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ, styleOverride);
    }

    public Waypoint withPos(int nx, int ny, int nz) {
        return new Waypoint(nx, ny, nz, name, color, flags, customRadius,
                tempMode, expiresAtMillis, preciseBlockCenter(nx), preciseBlockCenter(ny),
                preciseBlockCenter(nz), styleOverride);
    }

    public Waypoint withPreciseSixteenths(int nextPreciseX, int nextPreciseY, int nextPreciseZ) {
        return new Waypoint(x, y, z, name, color, flags, customRadius,
                tempMode, expiresAtMillis, nextPreciseX, nextPreciseY, nextPreciseZ, styleOverride);
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
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException("coordinate must be finite");
        }
        double precise = Math.floor(coordinate * PRECISE_SCALE);
        if (precise < Integer.MIN_VALUE || precise > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("coordinate is outside the precise range");
        }
        return (int) precise;
    }

    public Waypoint withSubwaypoint(boolean subwaypoint) {
        int nextFlags = subwaypoint
                ? flags | FLAG_SUBWAYPOINT
                : flags & ~FLAG_SUBWAYPOINT & ~SUBWAYPOINT_STYLE_FLAGS;
        return withFlags(nextFlags);
    }

    public Waypoint withTemp(int mode, long expiresAt) {
        return new Waypoint(x, y, z, name, color, flags, customRadius, mode, expiresAt,
                preciseX, preciseY, preciseZ, styleOverride);
    }

    public static int preciseBlockCenter(int blockCoordinate) {
        if (!isRepresentableBlockCoordinate(blockCoordinate)) {
            throw new IllegalArgumentException("block coordinate is outside the precise range");
        }
        return blockCoordinate * PRECISE_SCALE + PRECISE_BLOCK_CENTER_OFFSET;
    }

    public static boolean isRepresentableBlockCoordinate(int blockCoordinate) {
        return blockCoordinate >= MIN_BLOCK_COORDINATE
                && blockCoordinate <= MAX_BLOCK_COORDINATE;
    }

    public static int blockCoordinateFromPrecise(int preciseCoordinate) {
        return Math.floorDiv(preciseCoordinate, PRECISE_SCALE);
    }
}
