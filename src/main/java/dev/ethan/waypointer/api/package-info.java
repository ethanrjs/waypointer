/**
 * Public integration API for Fabric client mods that want to read or create
 * Waypointer routes.
 *
 * <p>Waypointer invokes {@code waypointer:api} entrypoints after config and
 * saved waypoint data have loaded. Callbacks run on the Minecraft client
 * initialization thread, and listener callbacks are invoked on the same client
 * thread that changes Waypointer state. Keep callbacks lightweight; schedule
 * expensive work in the consuming mod. API calls made from worker threads wait
 * for their client-thread work to finish so return values always reflect the
 * completed operation.
 *
 * <p>Snapshots returned by this package are immutable copies. Route mutations
 * must go through {@link dev.ethan.waypointer.api.WaypointerApi} so Waypointer
 * can invalidate render caches and mark storage dirty.
 */
package dev.ethan.waypointer.api;
