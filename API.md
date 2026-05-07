# Waypointer API

Waypointer has a small Fabric API for client mods that want to work with
waypoints.

You can:

- read the user's routes
- add temporary markers
- create normal saved routes
- show routes owned by your mod
- import waypoint share strings
- listen for zone or waypoint changes

## Quick Start

Register an entrypoint in your mod's `fabric.mod.json`:

```json
{
  "entrypoints": {
    "waypointer:api": [
      "com.example.routes.ExampleWaypointerIntegration"
    ]
  },
  "depends": {
    "waypointer": ">=1.5.0"
  }
}
```

Then implement `WaypointerApiEntrypoint`:

```java
package com.example.routes;

import dev.ethan.waypointer.api.WaypointerApi;
import dev.ethan.waypointer.api.WaypointerApiEntrypoint;

public final class ExampleWaypointerIntegration implements WaypointerApiEntrypoint {
    private WaypointerApi waypointer;

    @Override
    public void onWaypointerApiReady(WaypointerApi api) {
        this.waypointer = api;
        ExampleRouteEvents.onRouteSelected(this::showRoute);
        ExampleRouteEvents.onRouteCleared(this::hideRoute);
    }
}
```

## Lifecycle

Waypointer calls `onWaypointerApiReady` after it has loaded config and saved
waypoints. API callbacks run on the Minecraft client thread, so keep them quick.

If one integration throws, Waypointer logs it and keeps loading the rest.

## Common Tasks

### Add A Temporary Marker

Temporary markers are good for things like burrows, chat coordinates, or helper
points that should disappear after the session.

```java
waypointer.addTempWaypoint(WaypointSpec.builder()
        .position(125, 72, -34)
        .name("Burrow")
        .color(0xFFD166)
        .source("Example Mod")
        .build());
```

### Create A Saved Route

Use `createRoute` when you want to add a normal Waypointer route. The user can
edit it, reorder it, export it, or delete it later.

```java
String groupId = waypointer.createRoute(RouteSpec.builder()
        .name("Example Mining Route")
        .zoneId("dwarven_mines")
        .loadMode(RouteLoadMode.SEQUENCE)
        .waypoint(WaypointSpec.at(10, 64, 10).name("Start"))
        .waypoint(WaypointSpec.at(20, 65, 20).name("Vein 1"))
        .waypoint(WaypointSpec.at(30, 66, 30).name("Vein 2"))
        .build());
```

### Read User Waypoints

Reads return snapshots. They are safe to keep around, but they do not update on
their own. Ask the API again when you need fresh data.

```java
for (WaypointGroupSnapshot group : waypointer.groupsForZone("dungeon_f7")) {
    if (!group.enabled()) continue;

    for (WaypointSnapshot waypoint : group.waypoints()) {
        ExampleHud.addMarker(
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                waypoint.name());
    }
}
```

### Show A Dynamic Overlay

Use overlays for routes owned by your mod. Closing the handle removes the route
without touching the user's saved Waypointer routes.

```java
private WaypointerHandle activeOverlay;

private void showRoute(ExampleRoute route) {
    hideRoute();

    activeOverlay = waypointer.showRouteOverlay(RouteOverlaySpec.builder()
            .name(route.displayName())
            .zoneId(route.zoneId())
            .loadMode(RouteLoadMode.STATIC)
            .waypoints(route.toWaypointSpecs())
            .build());
}

private void hideRoute() {
    if (activeOverlay == null) return;

    activeOverlay.close();
    activeOverlay = null;
}
```

### Import A Share String

`importRoutes` accepts the same formats as Waypointer's import command:
Waypointer `WP:` payloads, Skyblocker, Skytils, Soopy, Coleweight, and JSON.

```java
ImportSummary summary = waypointer.importRoutes(sharedText, ImportOptions.builder()
        .targetCurrentZoneWhenUnknown(true)
        .build());

ExampleChat.send("Imported " + summary.groupCount() + " Waypointer route(s)");
```

### Listen For Changes

Listeners return a handle. Close it when your mod no longer needs updates.

```java
WaypointerHandle dataSubscription = waypointer.onDataChanged(() -> {
    ExampleHud.replaceWaypoints(waypointer.activeGroups());
});

WaypointerHandle zoneSubscription = waypointer.onZoneChanged(zone -> {
    ExampleLogger.debug("Waypointer zone changed to " + zone.id());
});
```

## A Few Rules

- Snapshots are copies. They will not change under you.
- Anything that changes waypoints should go through `WaypointerApi`.
- Temporary waypoints and overlays are session-only.
- Close overlay and listener handles when you are done with them.
- Keep listener callbacks lightweight.

## Versioning

Use the types in `dev.ethan.waypointer.api`. Everything else is internal and may
change between releases.
