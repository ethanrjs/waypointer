package dev.ethan.waypointer.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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
 *   <li>{@link #TEMP_NONE} — a normal, persisted waypoint.
 *   <li>{@link #TEMP_TIME} — removed once {@link System#currentTimeMillis()} passes {@code expiresAtMillis}.
 *   <li>{@link #TEMP_UNTIL_REACHED} — removed by the proximity tracker when it advances past this waypoint.
 *   <li>{@link #TEMP_UNTIL_LEAVE} — removed when the player leaves the server.
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
        long expiresAtMillis) {

    public static final int FLAG_HIDE_BEACON  = 1;
    public static final int FLAG_HIDE_NAME    = 1 << 1;
    public static final int FLAG_THROUGH_WALL = 1 << 2;
    public static final int FLAG_LOCKED_COLOR = 1 << 3; // excluded from gradient auto-recolor

    public static final int TEMP_NONE = 0;
    public static final int TEMP_TIME = 1;
    public static final int TEMP_UNTIL_REACHED = 2;
    public static final int TEMP_UNTIL_LEAVE = 3;

    public static final int DEFAULT_COLOR = 0x4FE05A; // bright green -- reads clearly against most biomes

    public Waypoint {
        name = name == null ? "" : name;
    }

    /**
     * Backward-compatible constructor for call sites that pre-date temp waypoints.
     * Anything built this way is treated as permanent (tempMode=0, expiresAt=0).
     */
    public Waypoint(int x, int y, int z, String name, int color, int flags, double customRadius) {
        this(x, y, z, name, color, flags, customRadius, TEMP_NONE, 0L);
    }

    public static Waypoint at(int x, int y, int z) {
        return new Waypoint(x, y, z, "", DEFAULT_COLOR, 0, 0.0, TEMP_NONE, 0L);
    }

    public BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }

    public Vec3 center() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
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

    /** True iff this is a time-based temp and the deadline has passed. */
    public boolean isExpired(long nowMillis) {
        return tempMode == TEMP_TIME && expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }

    public Waypoint withName(String newName)       { return new Waypoint(x, y, z, newName, color, flags, customRadius, tempMode, expiresAtMillis); }
    public Waypoint withColor(int newColor)        { return new Waypoint(x, y, z, name, newColor, flags, customRadius, tempMode, expiresAtMillis); }
    public Waypoint withFlags(int newFlags)        { return new Waypoint(x, y, z, name, color, newFlags, customRadius, tempMode, expiresAtMillis); }
    public Waypoint withRadius(double newRadius)   { return new Waypoint(x, y, z, name, color, flags, newRadius, tempMode, expiresAtMillis); }
    public Waypoint withPos(int nx, int ny, int nz){ return new Waypoint(nx, ny, nz, name, color, flags, customRadius, tempMode, expiresAtMillis); }

    /** Flip a waypoint's temp mode. Typically used to build a brand-new temp waypoint from {@link #at}. */
    public Waypoint withTemp(int mode, long expiresAt) {
        return new Waypoint(x, y, z, name, color, flags, customRadius, mode, expiresAt);
    }
}
