package dev.ethan.waypointer.api;

/**
 * Implement this interface from another Fabric client mod to receive
 * Waypointer's public API after Waypointer has loaded saved waypoint state.
 *
 * <p>Register implementations under the {@code "waypointer:api"} entrypoint
 * key in the consuming mod's {@code fabric.mod.json}.
 */
@FunctionalInterface
public interface WaypointerApiEntrypoint {
    /**
     * Called once Waypointer is ready for other mods to use.
     *
     * <p>At this point config and saved routes have loaded, and API mutations
     * will autosave through Waypointer's normal save path.
     */
    void onWaypointerApiReady(WaypointerApi api);
}
