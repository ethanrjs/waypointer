package dev.ethan.waypointer.api;

import java.util.List;
import java.util.function.Consumer;

/**
 * Stable facade exposed to other Fabric client mods.
 *
 * <p>All returned group and waypoint data is immutable snapshot data. Any
 * operation that changes Waypointer state goes through this interface so the
 * renderer, autosave listener, and UI caches all observe the same change.
 */
public interface WaypointerApi {

    /**
     * The zone Waypointer currently thinks the player is in.
     *
     * @return the current zone, or {@code null} when Waypointer has not detected
     *         a Skyblock zone yet
     */
    ZoneSnapshot currentZone();

    /**
     * Every route Waypointer currently knows about.
     *
     * <p>The returned groups are immutable snapshots. Call this again after
     * {@link #onDataChanged(Runnable)} fires if you need fresh data.
     */
    List<WaypointGroupSnapshot> allGroups();

    /**
     * Routes that should render in the current zone right now.
     *
     * <p>This applies Waypointer's normal active-zone and enabled-route filters.
     */
    List<WaypointGroupSnapshot> activeGroups();

    /**
     * Routes attached to a specific Waypointer zone id.
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
     * Remove a route or overlay group by id.
     *
     * @return {@code true} when a group was removed, {@code false} when the id
     *         did not exist
     */
    boolean removeRoute(String groupId);

    /**
     * Add one waypoint to an existing route.
     *
     * @return {@code true} when the waypoint was added, {@code false} when the
     *         route id did not exist
     */
    boolean addWaypoint(String groupId, WaypointSpec waypoint);

    /**
     * Replace one waypoint in an existing route.
     *
     * <p>Use this for edits that should keep the waypoint in the same list
     * position while changing its coordinates, name, color, flags, or radius.
     *
     * @return {@code true} when the waypoint was replaced, {@code false} when the
     *         route id did not exist or the index was outside the route
     */
    boolean updateWaypoint(String groupId, int waypointIndex, WaypointSpec replacement);

    /**
     * Add one session-only marker to Waypointer's temp bucket for the current zone.
     *
     * <p>Use this for short-lived markers such as chat coordinates, burrows, or
     * helper points. The marker is not saved to disk.
     *
     * @return a snapshot of the temp group that now owns the marker
     */
    WaypointGroupSnapshot addTempWaypoint(WaypointSpec waypoint);

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
     * {@code WP:} payloads, Skyblocker, Skytils, SkyHanni, Soopy, Coleweight,
     * and JSON.
     *
     * @param options import options, or {@code null} for {@link ImportOptions#defaults()}
     */
    ImportSummary importRoutes(String payload, ImportOptions options);

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

    /**
     * Listen for route or waypoint changes.
     *
     * <p>Callbacks run on the client thread. Keep them lightweight and close the
     * returned handle when your mod no longer needs updates.
     */
    WaypointerHandle onDataChanged(Runnable listener);

    /**
     * Listen for Waypointer's current zone changing.
     *
     * <p>The listener receives {@code null} when Waypointer leaves a detected
     * Skyblock zone.
     */
    WaypointerHandle onZoneChanged(Consumer<ZoneSnapshot> listener);
}
