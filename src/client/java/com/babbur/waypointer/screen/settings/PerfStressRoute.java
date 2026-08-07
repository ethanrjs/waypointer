package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * The synthetic waypoint load behind the settings screen's performance stress
 * test. A sweep over the user's live scene is only as informative as the scene
 * itself -- two waypoints on a private island stress nothing -- so the test
 * installs deterministic 2D, 3D, dungeon-secret, and subwaypoint loads around
 * the player for its duration and removes them afterwards.
 *
 * <p>Every profile sits entirely inside the 100-block near-hide cap used by
 * the baseline. The legacy 45x45 plane reaches about 93 blocks at its corners;
 * 3D profiles use an 83-block Fibonacci ellipsoid.
 *
 * <p>The group is runtime-only, so it never persists while still exercising
 * route-progress labels (which intentionally skip temporary groups).
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

    public enum Profile {
        GRID_2D,
        GRID_3D,
        DUNGEON_SECRETS,
        SUBWAYPOINTS_3D
    }

    public record Load(Profile profile, int mainWaypoints, int subwaypointsPerMain) {
        public Load {
            if (profile == null) throw new IllegalArgumentException("profile");
            if (mainWaypoints <= 0) throw new IllegalArgumentException("mainWaypoints must be positive");
            if (subwaypointsPerMain < 0) throw new IllegalArgumentException("subwaypointsPerMain must be non-negative");
        }

        public int totalWaypoints() {
            return Math.multiplyExact(
                    mainWaypoints,
                    Math.addExact(subwaypointsPerMain, 1));
        }
    }

    private PerfStressRoute() {}

    /**
     * Install the stress grid centered on the player, replacing any grid left
     * by a previous run. Returns the number of waypoints installed, or 0 when
     * no zone is active (the sweep then measures only the live scene).
     */
    public static int install(ActiveGroupManager manager,
                              double playerX, double playerY, double playerZ) {
        return install(manager, playerX, playerY, playerZ,
                new Load(Profile.GRID_2D, WAYPOINT_COUNT, 0));
    }

    public static int install(ActiveGroupManager manager,
                              double playerX, double playerY, double playerZ,
                              Load load) {
        if (manager == null) return 0;
        Zone zone = manager.currentZone();
        if (zone == null) return 0;
        remove(manager);

        WaypointGroup group = new WaypointGroup(GROUP_ID_PREFIX + zone.id(), GROUP_NAME, zone.id());
        group.setEnabled(true);
        group.setRuntimeOnly(true);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(load.profile() == Profile.DUNGEON_SECRETS
                ? WaypointGroup.LoadMode.SEQUENCE
                : WaypointGroup.LoadMode.STATIC);
        group.setDefaultRadius(0.5);

        List<Waypoint> points = new ArrayList<>(load.totalWaypoints());
        for (int i = 0; i < load.mainWaypoints(); i++) {
            double[] position = position(load.profile(), i, load.mainWaypoints(),
                    playerX, playerY, playerZ);
            String name = load.profile() == Profile.DUNGEON_SECRETS
                    ? secretName(i)
                    : "Stress " + (i + 1);
            int mainFlags = i % 4 == 0 ? Waypoint.FLAG_DEPTH_CHECKED : 0;
            points.add(at(position[0], position[1], position[2])
                    .withName(name)
                    .withColor(gridColor(i, load.mainWaypoints()))
                    .withFlags(mainFlags));

            for (int child = 0; child < load.subwaypointsPerMain(); child++) {
                double angle = child * 2.399963229728653;
                double radius = 0.65 + (child % 5) * 0.35;
                points.add(at(
                        position[0] + Math.cos(angle) * radius,
                        position[1] + ((child % 7) - 3) * 0.3,
                        position[2] + Math.sin(angle) * radius)
                        .withName(child % 6 == 0 ? "Secret highlight " + (child + 1) : "")
                        .withColor(0xF5C451)
                        .withFlags(subwaypointFlags(child)));
            }
        }
        group.addAll(points);
        manager.add(group);
        return group.size();
    }

    /** Remove any stress grids from previous or current runs. Returns groups removed. */
    public static int remove(ActiveGroupManager manager) {
        if (manager == null) return 0;
        List<String> ids = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if ((group.temp() || group.runtimeOnly())
                    && group.id() != null && group.id().startsWith(GROUP_ID_PREFIX)) {
                ids.add(group.id());
            }
        }
        for (String id : ids) manager.remove(id);
        return ids.size();
    }

    private static double[] position(Profile profile, int index, int count,
                                     double centerX, double centerY, double centerZ) {
        if (profile == Profile.GRID_2D) {
            int columns = (int) Math.ceil(Math.sqrt(count));
            int row = index / columns;
            int column = index % columns;
            double start = -(columns - 1) * SPACING_BLOCKS / 2.0;
            return new double[]{centerX + start + column * SPACING_BLOCKS,
                    centerY, centerZ + start + row * SPACING_BLOCKS};
        }

        // Fibonacci ellipsoid: deterministic, view-independent, and entirely
        // inside the baseline's 100-block near-hide radius.
        double unitY = 1.0 - 2.0 * (index + 0.5) / count;
        double radial = Math.sqrt(Math.max(0.0, 1.0 - unitY * unitY)) * 70.0;
        double angle = index * 2.399963229728653;
        return new double[]{centerX + Math.cos(angle) * radial,
                centerY + unitY * 45.0,
                centerZ + Math.sin(angle) * radial};
    }

    private static Waypoint at(double x, double y, double z) {
        return Waypoint.at((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                .withPreciseSixteenths(
                        Waypoint.snapToPreciseSixteenths(x),
                        Waypoint.snapToPreciseSixteenths(y),
                        Waypoint.snapToPreciseSixteenths(z));
    }

    private static int subwaypointFlags(int index) {
        int flags = Waypoint.FLAG_SUBWAYPOINT;
        if ((index & 1) == 0) flags |= Waypoint.FLAG_SMALL_SUBWAYPOINT;
        if (index % 3 == 0) flags |= Waypoint.FLAG_FILLED_SUBWAYPOINT;
        if (index % 4 == 0) flags |= Waypoint.FLAG_DEPTH_CHECKED;
        if (index % 5 == 0) flags |= Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED;
        return flags;
    }

    private static String secretName(int index) {
        String[] categories = {"Chest", "Bat", "Item", "Wither essence", "Lever", "Crypt"};
        return categories[index % categories.length] + " secret " + (index + 1);
    }

    /** Blue-to-red sweep across the grid so label/box color variety is realistic. */
    private static int gridColor(int index, int count) {
        double t = count <= 1 ? 0.0 : index / (double) (count - 1);
        int red = (int) Math.round(64.0 + 191.0 * t);
        int green = (int) Math.round(96.0 + 128.0 * (1.0 - Math.abs(2.0 * t - 1.0)));
        int blue = (int) Math.round(255.0 - 191.0 * t);
        return (red << 16) | (green << 8) | blue;
    }
}
