package dev.ethan.waypointer.dungeon.data;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.dungeon.DungeonHighlight;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonSecretCategory;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory store of the dungeon waypoints Waypointer should render in each
 * room.
 *
 * <p>For the issue #9 MVP this is a thin abstraction over a fixed lookup
 * table built in code. It carries a {@link #demoWaypoints()} entry so the end-
 * to-end pipeline (detection -> data lookup -> room-local-to-world transform
 * -> render) can be exercised without any external data file. Real curated
 * data (sourced from Skyblocker's GPL-3.0 secret JSON or the upstream
 * DungeonRoomsMod repo) is deferred until a license-clean ingestion path is
 * decided -- see follow-up issue notes in the PR.
 *
 * <p>The schema is deliberately Skyblocker-compatible: data keys are room
 * "shape", values are lists of waypoints with categorised secrets. Once a
 * data source is wired up (see {@code TODO} below), the loader will populate
 * this table from JSON without callers needing to change.
 */
public final class DungeonRoomData {

    /**
     * Custom waypoints the user added at runtime via the
     * {@code /waypointer dungeon mark} client command. Keyed by the
     * containing {@link DungeonRoom#identityKey()} so they survive a tracker
     * re-detection of the same room (different physical instance, same
     * shape + corner) without the user having to redo them.
     */
    private static final AtomicReference<Map<String, List<DungeonWaypoint>>> CUSTOM =
            new AtomicReference<>(Collections.emptyMap());

    /**
     * Built-in demo data, used when no curated source is wired up. Maps each
     * shape to one example waypoint+highlight pair so the renderer has
     * something to draw when the user types {@code /waypointer dungeon test}.
     */
    private static final Map<DungeonRoomShape, List<DungeonWaypoint>> DEMO = buildDemo();

    private DungeonRoomData() {}

    /**
     * Curated waypoints for the given room. MVP returns an empty list -- the
     * curated dataset is not bundled (Skyblocker's source is GPL-3.0 and the
     * upstream DungeonRoomsMod data is GPL-3.0 too; bundling either would
     * relicense Waypointer). Implementers wiring up a JSON loader should
     * replace this body with a shape-keyed lookup.
     */
    public static List<DungeonWaypoint> waypointsFor(DungeonRoom room) {
        if (room == null) return List.of();
        List<DungeonWaypoint> custom = CUSTOM.get().getOrDefault(room.identityKey(), List.of());
        if (custom.isEmpty()) return List.of();
        return custom;
    }

    /**
     * Demo data for the supplied shape. Used by {@code /waypointer dungeon
     * test} so a player can confirm rendering works in any room without
     * waiting on curated data being authored.
     */
    public static List<DungeonWaypoint> demoFor(DungeonRoomShape shape) {
        return DEMO.getOrDefault(shape, DEMO.get(DungeonRoomShape.ONE_BY_ONE));
    }

    public static Map<DungeonRoomShape, List<DungeonWaypoint>> demoWaypoints() {
        return DEMO;
    }

    /** Append a runtime-authored waypoint to the given room's bucket. */
    public static void addCustom(String roomKey, DungeonWaypoint waypoint) {
        CUSTOM.updateAndGet(prev -> {
            Map<String, List<DungeonWaypoint>> next = new java.util.HashMap<>(prev);
            List<DungeonWaypoint> bucket = new ArrayList<>(next.getOrDefault(roomKey, List.of()));
            bucket.add(waypoint);
            next.put(roomKey, List.copyOf(bucket));
            return Map.copyOf(next);
        });
    }

    public static void clearCustom(String roomKey) {
        CUSTOM.updateAndGet(prev -> {
            if (!prev.containsKey(roomKey)) return prev;
            Map<String, List<DungeonWaypoint>> next = new java.util.HashMap<>(prev);
            next.remove(roomKey);
            return Map.copyOf(next);
        });
    }

    public static void clearAllCustom() {
        CUSTOM.set(Collections.emptyMap());
    }

    private static Map<DungeonRoomShape, List<DungeonWaypoint>> buildDemo() {
        Map<DungeonRoomShape, List<DungeonWaypoint>> map = new EnumMap<>(DungeonRoomShape.class);
        for (DungeonRoomShape shape : DungeonRoomShape.values()) {
            // One CHEST waypoint at the canonical NW segment's centre, with
            // two outline highlights one block to either side. The category +
            // multi-highlight setup is the smallest non-trivial example of
            // the parent->children relationship called out in issue #9.
            DungeonWaypoint demo = new DungeonWaypoint(
                    "demo:" + shape.name(),
                    1,
                    DungeonSecretCategory.CHEST,
                    16, 70, 16,
                    "Waypointer demo",
                    List.of(
                            DungeonHighlight.outline(15, 70, 15),
                            DungeonHighlight.outline(17, 70, 17)
                    )
            );
            map.put(shape, List.of(demo));
        }
        try {
            Waypointer.LOGGER.debug("Built dungeon demo data for {} shape(s)", map.size());
        } catch (Throwable ignored) {
            // LOGGER may be unavailable during early class-init in tests; safe to swallow.
        }
        return Collections.unmodifiableMap(map);
    }
}
