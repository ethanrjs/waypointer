package dev.ethan.waypointer.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A single point in the world rendered by the mod.
 *
 * Immutable by design: edits produce a new instance. This keeps the tick loop
 * safe to iterate while the UI mutates a group, and makes undo trivial to add later.
 *
 * Color is 0xRRGGBB; alpha is controlled per-render by the renderer based on state
 * (completed / current / upcoming).
 *
 * customRadius is in blocks. 0 means "use the group's defaultRadius".
 */
public record Waypoint(
        int x,
        int y,
        int z,
        String name,
        int color,
        int flags,
        double customRadius) {

    public static final int FLAG_HIDE_BEACON  = 1;
    public static final int FLAG_HIDE_NAME    = 1 << 1;
    public static final int FLAG_THROUGH_WALL = 1 << 2;
    public static final int FLAG_LOCKED_COLOR = 1 << 3; // excluded from gradient auto-recolor

    public static final int DEFAULT_COLOR = 0x4FE05A; // bright green -- reads clearly against most biomes

    public Waypoint {
        name = name == null ? "" : name;
    }

    public static Waypoint at(int x, int y, int z) {
        return new Waypoint(x, y, z, "", DEFAULT_COLOR, 0, 0.0);
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

    public Waypoint withName(String newName)       { return new Waypoint(x, y, z, newName, color, flags, customRadius); }
    public Waypoint withColor(int newColor)        { return new Waypoint(x, y, z, name, newColor, flags, customRadius); }
    public Waypoint withFlags(int newFlags)        { return new Waypoint(x, y, z, name, color, newFlags, customRadius); }
    public Waypoint withRadius(double newRadius)   { return new Waypoint(x, y, z, name, color, flags, newRadius); }
    public Waypoint withPos(int nx, int ny, int nz){ return new Waypoint(nx, ny, nz, name, color, flags, customRadius); }
}
