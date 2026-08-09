package com.babbur.waypointer.api;

public final class WaypointFlags {

    public static final int HIDE_BEACON = 1;
    public static final int HIDE_NAME = 1 << 1;
    public static final int THROUGH_WALL = 1 << 2;
    public static final int LOCKED_COLOR = 1 << 3;
    public static final int SUBWAYPOINT = 1 << 4;
    public static final int SMALL_SUBWAYPOINT = 1 << 5;
    public static final int FILLED_SUBWAYPOINT = 1 << 6;
    public static final int HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED = 1 << 7;
    public static final int DEPTH_CHECKED = 1 << 8;
    public static final int SKIP_ON_STAND = 1 << 9;
    public static final int SKIP_ON_INTERACT = 1 << 10;
    public static final int SKIP_ON_MINE = 1 << 11;

    public static final int SUBWAYPOINT_STYLE = SMALL_SUBWAYPOINT
            | FILLED_SUBWAYPOINT
            | HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED;
    public static final int STRUCTURAL = SUBWAYPOINT;

    private WaypointFlags() {
    }

    public static int of(int... flags) {
        int combined = 0;
        for (int flag : flags) combined |= flag;
        return combined;
    }

    public static boolean contains(int flags, int required) {
        return (flags & required) == required;
    }
}
