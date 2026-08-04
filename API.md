# Waypointer API 1.8.6

Waypointer exposes a client-side Fabric API for reading routes, creating saved
user routes, rendering session-only markers and overlays, importing and
exporting share strings, and observing route-data and zone changes.

The supported integration contract is the facade, entrypoint, specs, snapshots,
options, enums, references, and handles in `com.babbur.waypointer.api`.
`DefaultWaypointerApi` is Waypointer's implementation and must not be
constructed, cast to, or depended on by another mod. Everything outside the API
package is internal.

## Add Waypointer To Your Build

Waypointer is available from Modrinth's Maven repository. Use the release that
matches the Minecraft version your mod is compiling against:

```groovy
repositories {
    maven { url = "https://api.modrinth.com/maven" }
}

dependencies {
modImplementation "maven.modrinth:waypointer:1.8.6"
}
```

`modImplementation` makes Waypointer available to compilation and development
runs. It does not embed Waypointer unless you explicitly use Loom's `include`,
which integrations must not do. Players install Waypointer as a separate mod.

For an unreleased local build, put the matching jar in your mod's `libs`
directory instead:

```groovy
dependencies {
modImplementation files("libs/waypointer-1.8.6-mc26.1.2.jar")
}
```

Use the `mc26.2` jar when compiling for Minecraft 26.2.

Register both the integration entrypoint and runtime dependency in your
`fabric.mod.json`:

```json
{
  "entrypoints": {
    "waypointer:api": [
      "com.example.routes.ExampleWaypointerIntegration"
    ]
  },
  "depends": {
    "waypointer": ">=1.8.2 <2.0.0"
  }
}
```

Use `suggests` instead of `depends` only when your mod works completely without
Waypointer. Fabric does not load the custom entrypoint unless Waypointer asks
for it, so an isolated integration class can support an optional dependency.

## Receive The API

```java
package com.example.routes;

import com.babbur.waypointer.api.WaypointerApi;
import com.babbur.waypointer.api.WaypointerApiEntrypoint;
import com.babbur.waypointer.api.WaypointerHandle;

public final class ExampleWaypointerIntegration
        implements WaypointerApiEntrypoint {

    private WaypointerApi waypointer;
    private WaypointerHandle dataSubscription;
    private WaypointerHandle zoneSubscription;

    @Override
    public void onWaypointerApiReady(WaypointerApi api) {
        waypointer = api;
        dataSubscription = api.onDataChanged(this::refreshHud);
        zoneSubscription = api.onZoneStateChanged(zone -> {
            if (zone.isPresent()) refreshHud();
            else ExampleHud.clear();
        });
        refreshHud();
    }

    private void refreshHud() {
        ExampleHud.replaceWaypoints(waypointer.activeGroups());
    }

    public void close() {
        if (dataSubscription != null) dataSubscription.close();
        if (zoneSubscription != null) zoneSubscription.close();
    }
}
```

### Lifecycle

Waypointer invokes each `waypointer:api` entrypoint once during client startup,
after configuration and saved routes have loaded. Mutations made in the
entrypoint participate in Waypointer's normal autosave flow.

An exception from one entrypoint is logged and does not prevent other
integrations from loading. Failed entrypoints are not retried.

### Threading

Entrypoints and listener callbacks run on the Minecraft client thread. Keep
callbacks short and move expensive work elsewhere.

Every API method may be called from another thread. Off-thread calls are queued
on the client thread and block until the operation finishes, so their return
values always describe the completed operation. Do not call the API while
holding a lock that the client thread may need.

## Data Model And Ownership

Snapshots are immutable, detached copies. They never update themselves and are
safe to retain or iterate from another thread. Re-read the API after a relevant
notification or immediately before an edit.

Group ids are opaque strings. Do not parse or construct them.

Waypointer exposes four kinds of group:

- **Saved routes** are persisted user data. Routes created or imported through
  the API become user-owned and may later be edited or deleted in Waypointer.
- **Temporary buckets** are shared, session-only storage for unmanaged markers.
- **Overlays** are session-only groups owned through their returned handle.
- **Runtime groups** are internal projections such as the active copy of a
  dungeon-room route.

Use the narrowest read for the job:

| Method | Contents |
| --- | --- |
| `savedRoutes()` | Persisted user routes only |
| `activeGroups()` | Groups Waypointer currently considers renderable |
| `groupsForZone(id)` | Every group attached to the canonicalized zone id |
| `allGroups()` | Saved, temporary, overlay, and internal runtime groups |

Only saved routes are eligible for public mutation. Temporary, overlay, and
runtime groups are read-only through `removeRoute`, `addWaypoint`, and waypoint
edit methods.

## Read Routes

```java
for (WaypointGroupSnapshot group : waypointer.savedRoutes()) {
    if (!group.enabled()) continue;

    for (WaypointSnapshot point : group.waypoints()) {
        ExampleHud.addMarker(
                point.x(), point.y(), point.z(),
                point.name(), point.color());
    }
}
```

Snapshot lists are immutable. `WaypointGroupSnapshot.currentWaypoint()` may
return `null` when the route is empty, complete, or has no valid current index.

`currentZone()` may return `null` before detection or after leaving a detected
SkyBlock zone. Prefer `currentZoneOptional()` and `onZoneStateChanged(...)` in
new code. The nullable `onZoneChanged(...)` method remains for compatibility.

## Show A Managed Temporary Marker

Use a managed marker when your mod must remove one exact point later:

```java
WaypointerHandle burrow = waypointer.showTempWaypoint(
        WaypointSpec.builder()
                .position(125, 72, -34)
                .name("Burrow")
                .color(0xFFD166)
                .build());

// Later; closing twice is also safe.
burrow.close();
```

The handle owns exactly the marker inserted by that call. Closing it never
removes an equal marker created by another mod.

`addTempWaypoint(...)` is the legacy unmanaged form. It adds a marker to the
shared current-zone bucket and returns a group snapshot, not a removal handle.

`WaypointSpec.source(...)` is only a sanitized fallback display label when a
temporary point has no name. It is not persisted provenance.

## Show A Session-Only Route

Use an overlay when a whole route belongs to your mod and must not become user
data:

```java
private WaypointerHandle activeOverlay;

private void showTreasureRoute(List<Treasure> treasures) {
    hideTreasureRoute();

    activeOverlay = waypointer.showRouteOverlay(
            RouteOverlaySpec.builder()
                    .name("Treasure Route")
                    .zoneId("crystal_hollows")
                    .loadMode(RouteLoadMode.SEQUENCE)
                    .waypoints(treasures.stream()
                            .map(treasure -> WaypointSpec
                                    .at(treasure.x(), treasure.y(), treasure.z())
                                    .name(treasure.name())
                                    .color(0x55FFFF))
                            .toList())
                    .build());
}

private void hideTreasureRoute() {
    if (activeOverlay == null) return;
    activeOverlay.close();
    activeOverlay = null;
}
```

Closing the handle removes only that captured overlay. All handles returned by
Waypointer are idempotent.

## Create Saved User Data

```java
String routeId = waypointer.createRoute(
        RouteSpec.builder()
                .name("Commission Loop")
                .zoneId("dwarven_mines")
                .loadMode(RouteLoadMode.SEQUENCE)
                .defaultRadius(4.0)
                .waypoint(WaypointSpec.at(10, 64, 10)
                        .name("Start")
                        .color(0xFFD166))
                .waypoint(WaypointSpec.at(40, 82, -15)
                        .name("Titanium"))
                .build());
```

Use `dwarven_mines` for routes in Dwarven Mines, Glacite Tunnels, Dwarven Base Camp, and Great Glacite Lake. The retired zone IDs `glacite_tunnels`, `dwarven_base_camp`, and `great_glacite_lake` are accepted as input aliases and canonicalize to `dwarven_mines`.

The returned id identifies persisted user data. The user may edit or delete the
route at any time. `removeRoute(routeId)` is destructive: call it only for a
route your mod just created or one the user explicitly selected for deletion.

`addWaypoint(routeId, spec)` appends to a saved route and returns `false` if the
id no longer identifies saved mutable data.

## Safely Edit A Saved Waypoint

An index captured from an old list can point at different data after user
edits. Use an optimistic reference from a fresh route snapshot:

```java
WaypointGroupSnapshot route = waypointer.savedRoutes().stream()
        .filter(group -> group.id().equals(routeId))
        .findFirst()
        .orElseThrow();

WaypointReference stop = route.waypointReferences().get(1);

boolean updated = waypointer.updateWaypoint(
        stop,
        WaypointSpec.at(40, 80, -15)
                .name("Updated Titanium Stop"));

if (!updated) {
    // The route or waypoint changed. Re-read; do not retry blindly.
}
```

`removeWaypoint(reference)` follows the same optimistic rule. Both methods
return `false` without firing listeners when:

- the group no longer exists;
- the group is not a saved route;
- the index is no longer valid; or
- the waypoint no longer equals the snapshot captured by the reference.

`updateWaypoint(groupId, index, replacement)` remains for compatibility but is
deprecated because it cannot detect a stale index.

References are runtime concurrency guards, not permanent waypoint identifiers.
Do not persist them across sessions.

## Waypoint Flags

Use `WaypointFlags`; never copy bit values from Waypointer internals:

```java
WaypointSpec secret = WaypointSpec.at(12, 70, -8)
        .name("Lever")
        .flags(WaypointFlags.of(
                WaypointFlags.THROUGH_WALL,
                WaypointFlags.SKIP_ON_INTERACT));
```

| Flag | Meaning |
| --- | --- |
| `HIDE_BEACON` | Hide the beacon beam |
| `HIDE_NAME` | Hide the label |
| `THROUGH_WALL` | Render without normal occlusion |
| `LOCKED_COLOR` | Exclude the point from automatic gradient recoloring |
| `SUBWAYPOINT` | Make the point a child of the previous main waypoint |
| `SMALL_SUBWAYPOINT` | Render a child as a small cube |
| `FILLED_SUBWAYPOINT` | Render a child filled even in outline mode |
| `HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED` | Hide a child after its parent activates |
| `DEPTH_CHECKED` | Render only through the normal depth buffer |
| `SKIP_ON_STAND` | Advance when standing on the point's block |
| `SKIP_ON_INTERACT` | Advance when interacting with the point's block |
| `SKIP_ON_MINE` | Advance after the point's block is observed and then mined |

Subwaypoint style flags only have meaning when `SUBWAYPOINT` is also set.
`WaypointFlags.contains(flags, required)` checks a bit set.

## Import Routes

Imports create saved user routes:

```java
try {
    ImportSummary result = waypointer.importRoutes(
            clipboardText,
            ImportOptions.builder()
                    .targetCurrentZoneWhenUnknown(true)
                    .build());

    ExampleChat.send("Imported " + result.groupCount()
            + " route(s) from " + result.source());
} catch (IllegalArgumentException invalidPayload) {
    ExampleChat.send("Could not import routes: "
            + invalidPayload.getMessage());
}
```

Supported input families are Waypointer `WP:` payloads, Skyblocker, Skytils,
SkyHanni/Coleweight, Soopy V1, Firmament, Odin, and recognized JSON shapes.
`ImportSummary.source()` returns the API-owned `ImportSource` enum.

Malformed, unsupported, or oversized input throws `IllegalArgumentException`
before any imported group is added. `importRoutes(payload)` uses default
options; the two-argument overload also accepts `null` as defaults for backward
compatibility.

## Export Routes

```java
String shareText = waypointer.exportRoutes(
        List.of(routeId),
        ExportOptions.builder()
                .target(ExportTarget.WAYPOINTER)
                .label("Commission Loop")
                .build());
```

Exports are read-only. Ids are processed in supplied order; missing and `null`
ids are skipped. `exportRoutes(ids)` uses full-fidelity Waypointer defaults.

Supported targets are Waypointer, Skyblocker, Skytils, and SkyHanni.
Third-party formats cannot represent every Waypointer field, so use
`WAYPOINTER` for lossless round trips.

## Change Notifications

`onDataChanged(...)` is an invalidation signal for route membership or waypoint
data, not a one-callback-per-call audit stream. One operation may emit more than
one callback, while a bulk import deliberately emits one callback after every
group has landed.

Automatic sequence progress/current-index movement is not currently guaranteed
to emit `onDataChanged`. Integrations that need live progress should poll
`activeGroups()` at a reasonable rate until a dedicated progress event exists.

Listener callbacks run on the client thread. A `RuntimeException` from a public
listener is logged and does not prevent later listeners or Waypointer's own
updates from running. Close listener handles when the owning feature is disabled.

## Defaults, Validation, And Errors

Unless explicitly documented otherwise, passing `null` fails fast with
`NullPointerException`. The intentional compatibility exceptions are nullable
import/export options, which select their defaults.

API operation failures propagate to the caller. Mutation methods use `false`
for expected races such as missing, stale, or read-only targets.

Waypoint defaults and normalization:

- position: `(0, 0, 0)` until set;
- name and temporary fallback source: empty;
- color: `0x4FE05A`, with input reduced to `0xRRGGBB`;
- flags: none;
- custom radius: `0`, meaning use the route default;
- positive custom radii cap at 100 blocks;
- non-finite or non-positive custom radii become zero.

Route default radii clamp to 0.5 through 100 blocks, using 3 blocks for
non-finite values. Routes and overlays default to the `unknown` zone and
`STATIC` load mode. Saved routes start enabled.

## Compatibility Rules

- Depend only on the supported API types described at the top of this file.
- Declare an upper version bound when your integration has not been tested
  against the next major Waypointer API.
- Snapshot types are API outputs; do not implement `WaypointerApi` or construct
  `DefaultWaypointerApi` yourself.
- Existing flag bits will not be renumbered within the 1.8.x API line.
- Add a `default` branch when switching over API enums so a future value does
  not crash your integration.
- Treat group ids and waypoint references as opaque runtime values.

The 1.8.2 API moves the Java namespace to `com.babbur.waypointer`; integrations
must update imports and recompile. It also
replaces the leaked internal import-source type with `ImportSource`, adds
saved-route filtering, optimistic waypoint references, managed temporary
markers, public flags, and null-free zone conveniences.
