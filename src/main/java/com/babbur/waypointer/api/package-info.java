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
 * must go through {@link com.babbur.waypointer.api.WaypointerApi} so Waypointer
 * can invalidate render caches and mark storage dirty.
 *
 * <p>The supported consumer contract is {@code WaypointerApi}, its entrypoint,
 * and the specs, snapshots, options, enums, references, and handles described in
 * {@code API.md}. {@code DefaultWaypointerApi} is a public implementation only
 * for Waypointer's bootstrap and tests; integrations must not construct or cast
 * to it.
 */
package com.babbur.waypointer.api;
