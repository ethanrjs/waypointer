package dev.ethan.waypointer.screen.settings;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * The synthetic waypoint load behind the settings screen's performance stress
 * test. A sweep over the user's live scene is only as informative as the scene
 * itself — two waypoints on a private island stress nothing — so the test
 * installs this dense temp grid around the player for its duration and removes
 * it afterwards.
 *
 * <p>The grid is sized to sit entirely inside the near-hide radius cap
 * (100 blocks): the baseline scenario hides waypoints via near-hide at that
 * radius, and a grid point outside it would leak into the baseline sample.
 * 45x45 at 3-block spacing puts the farthest corner ~93 blocks out.
 *
 * <p>The group is {@code temp}, so it never persists: a crash mid-test leaves
 * nothing behind after the session ends, matching the config snapshot's
 * crash-recovery story in {@link PerfStressTestController}.
 *
 * <p>MC-free so plain JUnit can exercise install/replace/remove; the caller
 * supplies the player position.
 */
public final class PerfStressRoute {

    public static final String GROUP_ID_PREFIX = "perf-stress::";
    public static final String GROUP_NAME = "Perf Stress Route";
    public static final int COLUMNS = 45;
    public static final int ROWS = 45;
    public static final int SPACING_BLOCKS = 3;
    public static final int WAYPOINT_COUNT = COLUMNS * ROWS;

    private PerfStressRoute() {}

    /**
     * Install the stress grid centered on the player, replacing any grid left
     * by a previous run. Returns the number of waypoints installed, or 0 when
     * no zone is active (the sweep then measures only the live scene).
     */
    public static int install(ActiveGroupManager manager,
                              double playerX, double playerY, double playerZ) {
        if (manager == null) return 0;
        Zone zone = manager.currentZone();
        if (zone == null) return 0;
        remove(manager);

        WaypointGroup group = new WaypointGroup(GROUP_ID_PREFIX + zone.id(), GROUP_NAME, zone.id());
        group.setEnabled(true);
        group.setTemp(true);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setDefaultRadius(0.5);

        int centerX = (int) Math.round(playerX);
        int centerY = (int) Math.round(playerY);
        int centerZ = (int) Math.round(playerZ);
        int startX = centerX - (COLUMNS / 2) * SPACING_BLOCKS;
        int startZ = centerZ - (ROWS / 2) * SPACING_BLOCKS;
        for (int i = 0; i < WAYPOINT_COUNT; i++) {
            int x = startX + (i % COLUMNS) * SPACING_BLOCKS;
            int z = startZ + (i / COLUMNS) * SPACING_BLOCKS;
            group.add(Waypoint.at(x, centerY, z)
                    .withName("Stress " + (i + 1))
                    .withColor(gridColor(i))
                    .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        }
        manager.add(group);
        return group.size();
    }

    /** Remove any stress grids from previous or current runs. Returns groups removed. */
    public static int remove(ActiveGroupManager manager) {
        if (manager == null) return 0;
        List<String> ids = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() && group.id() != null && group.id().startsWith(GROUP_ID_PREFIX)) {
                ids.add(group.id());
            }
        }
        for (String id : ids) manager.remove(id);
        return ids.size();
    }

    /** Blue-to-red sweep across the grid so label/box color variety is realistic. */
    private static int gridColor(int index) {
        double t = index / (double) (WAYPOINT_COUNT - 1);
        int red = (int) Math.round(64.0 + 191.0 * t);
        int green = (int) Math.round(96.0 + 128.0 * (1.0 - Math.abs(2.0 * t - 1.0)));
        int blue = (int) Math.round(255.0 - 191.0 * t);
        return (red << 16) | (green << 8) | blue;
    }
}
