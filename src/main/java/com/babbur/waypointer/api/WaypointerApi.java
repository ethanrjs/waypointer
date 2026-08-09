package com.babbur.waypointer.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Stable facade exposed to other Fabric client mods.
 *
 * <p>All returned group and waypoint data is immutable snapshot data. Any
 * operation that changes Waypointer state goes through this interface so the
 * renderer, autosave listener, and UI caches all observe the same change.
 * Calls from worker threads are dispatched synchronously to the Minecraft
 * client thread before returning. Potentially expensive import parsing runs on
 * the calling thread; only the resulting state mutation is dispatched.
 */
public interface WaypointerApi {

    /**
     * The zone Waypointer currently thinks the player is in.
     *
     * @return the current zone, or {@code null} when Waypointer has not detected
     *         a Skyblock zone yet
     */
    ZoneSnapshot currentZone();

    /** Null-free form of {@link #currentZone()}. */
    default Optional<ZoneSnapshot> currentZoneOptional() {
        return Optional.ofNullable(currentZone());
    }

    /**
     * Every group Waypointer currently knows about, including saved routes,
     * temporary buckets, API overlays, and internal runtime projections.
     *
     * <p>The returned groups are immutable snapshots. Call this again after
     * {@link #onDataChanged(Runnable)} fires if you need fresh data.
     */
    List<WaypointGroupSnapshot> allGroups();

    /**
     * Persisted, user-owned routes only.
     *
     * <p>This excludes temporary buckets, API overlays, and internal runtime
     * projections. Use this collection when presenting user data or preparing
     * a saved-route mutation.
     */
    List<WaypointGroupSnapshot> savedRoutes();

    /**
     * Routes that should render in the current zone right now.
     *
     * <p>This applies Waypointer's normal active-zone and enabled-route filters.
     */
    List<WaypointGroupSnapshot> activeGroups();

    /**
     * Groups of any kind attached to a specific Waypointer zone id.
     *
     * @param zoneId zone id such as {@code "hub"}, {@code "dungeon_f7"}, or
     *               {@code "dwarven_mines"}
     */
    List<WaypointGroupSnapshot> groupsForZone(String zoneId);

    /**
     * Create a normal saved route.
     *
     * <p>The route becomes user data: it can be edited, exported, and deleted
     * from Waypointer, and it is saved with the rest of the user's routes.
     *
     * <pre>{@code
     * String groupId = waypointer.createRoute(RouteSpec.builder()
     *         .name("Mining Route")
     *         .zoneId("dwarven_mines")
     *         .waypoint(WaypointSpec.at(10, 64, 10).name("Start"))
     *         .build());
     * }</pre>
     *
     * @return the new route's group id
     */
    String createRoute(RouteSpec route);

    /**
     * Remove a saved, user-owned route by id.
     *
     * @return {@code true} when a group was removed, {@code false} when the id
     *         did not identify a saved route
     */
    boolean removeRoute(String groupId);

    /**
     * Add one waypoint to an existing saved route.
     *
     * @return {@code true} when the waypoint was added, {@code false} when the
     *         route id did not identify a saved route
     */
    boolean addWaypoint(String groupId, WaypointSpec waypoint);

    /**
     * Replace one waypoint in an existing saved route by list index.
     *
     * <p>Use this for edits that should keep the waypoint in the same list
     * position while changing its coordinates, name, color, flags, or radius.
     *
     * <p>This compatibility method cannot detect an index made stale by a user
     * reorder. Prefer {@link #updateWaypoint(WaypointReference, WaypointSpec)}.
     *
     * @return {@code true} when the waypoint was replaced, {@code false} when the
     *         route was not saved/mutable or the index was outside the route
     */
    @Deprecated(forRemoval = false, since = "1.8.2")
    boolean updateWaypoint(String groupId, int waypointIndex, WaypointSpec replacement);

    /**
     * Replace a waypoint only if the saved route still matches an immutable
     * reference captured from {@link WaypointGroupSnapshot#waypointReferences()}.
     *
     * @return {@code false} without mutation when the route is missing or
     *         read-only, or the reference became stale
     */
    boolean updateWaypoint(WaypointReference reference, WaypointSpec replacement);

    /**
     * Remove a waypoint only if the saved route still matches an immutable
     * reference captured from {@link WaypointGroupSnapshot#waypointReferences()}.
     *
     * @return {@code false} without mutation when the route is missing or
     *         read-only, or the reference became stale
     */
    boolean removeWaypoint(WaypointReference reference);

    /**
     * Add one session-only marker to Waypointer's temp bucket for the current zone.
     *
     * <p>Use this for short-lived markers such as chat coordinates, burrows, or
     * helper points. The marker is not saved to disk.
     *
     * @return a snapshot of the temp group that now owns the marker
     * @deprecated prefer {@link #showTempWaypoint(WaypointSpec)} when the caller
     *             should own and later remove the exact marker
     */
    @Deprecated(forRemoval = false, since = "1.8.2")
    WaypointGroupSnapshot addTempWaypoint(WaypointSpec waypoint);

    /**
     * Show one session-only marker in the current zone.
     *
     * <p>The returned handle owns exactly the inserted marker. Closing it is
     * idempotent and never removes an equal marker created by another caller.
     */
    WaypointerHandle showTempWaypoint(WaypointSpec waypoint);

    /**
     * Show a session-only route owned by your mod.
     *
     * <p>Use overlays when your mod wants Waypointer to render a route without
     * adding it to the user's saved route list. Close the returned handle to
     * remove the overlay.
     *
     * <pre>{@code
     * WaypointerHandle overlay = waypointer.showRouteOverlay(RouteOverlaySpec.builder()
     *         .name("Event Route")
     *         .zoneId("hub")
     *         .waypoint(WaypointSpec.at(1, 70, 1))
     *         .build());
     *
     * overlay.close();
     * }</pre>
     */
    WaypointerHandle showRouteOverlay(RouteOverlaySpec overlay);

    /**
     * Import routes from any share format Waypointer understands.
     *
     * <p>Accepted formats match Waypointer's import command: Waypointer
     * {@code WP:} payloads, Skyblocker, Skytils, SkyHanni, Soopy, Firmament,
     * Coleweight, Odin, and JSON.
     *
     * @param options import options, or {@code null} for {@link ImportOptions#defaults()}
     * @throws IllegalArgumentException when the payload is malformed,
     *         unsupported, or exceeds Waypointer's import limits
     */
    ImportSummary importRoutes(String payload, ImportOptions options);

    /** Import with {@link ImportOptions#defaults()}. */
    default ImportSummary importRoutes(String payload) {
        return importRoutes(payload, ImportOptions.defaults());
    }

    /**
     * Export selected routes to a share string.
     *
     * <p>Routes are exported in the order supplied by {@code groupIds}. Missing
     * ids are skipped so callers can race safely with user edits. The operation
     * is read-only and does not notify data listeners.
     *
     * @param groupIds route ids to export
     * @param options export options, or {@code null} for {@link ExportOptions#defaults()}
     */
    String exportRoutes(List<String> groupIds, ExportOptions options);

    /** Export to Waypointer's full-fidelity format with default options. */
    default String exportRoutes(List<String> groupIds) {
        return exportRoutes(groupIds, ExportOptions.defaults());
    }

    /**
     * Listen for route or waypoint changes.
     *
     * <p>Callbacks run on the client thread. Keep them lightweight and close the
     * returned handle when your mod no longer needs updates.
     *
     * <p>This is an invalidation signal, not a one-callback-per-operation audit
     * stream. Automatic sequence progress is not currently guaranteed to fire it.
     */
    WaypointerHandle onDataChanged(Runnable listener);

    /**
     * Listen for Waypointer's current zone changing.
     *
     * <p>The listener receives {@code null} when Waypointer leaves a detected
     * Skyblock zone.
     */
    WaypointerHandle onZoneChanged(Consumer<ZoneSnapshot> listener);

    /** Null-free zone-state listener. */
    default WaypointerHandle onZoneStateChanged(Consumer<Optional<ZoneSnapshot>> listener) {
        Objects.requireNonNull(listener, "listener");
        return onZoneChanged(zone -> listener.accept(Optional.ofNullable(zone)));
    }
}
