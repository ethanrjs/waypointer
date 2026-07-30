# Waypointer Repository Knowledge Base

Repository: `ethanrjs/waypointer`  
Baseline branch: `main`  
Baseline commit: `d32291ffdac49fa950304c3683b5851f7bdfcea7`  
Generated: `2026-07-30`

This file is a fast navigation index for Waypointer. It answers these questions:

- Where is a setting defined?
- Where is its default value stored?
- Which code uses the setting?
- Does a feature exist?
- Which files implement the feature?
- Where does Waypointer store user data?
- Which tests cover a subsystem?

The current source code is authoritative. Update the baseline commit after a repository-wide review.

## Trust order

Use this order when sources disagree:

1. Current source code on the checked-out branch.
2. `SettingsCatalog.java` for visible settings and settings categories.
3. `WaypointerConfig.java` and `DungeonConfig.java` for defaults and persistence.
4. Tests for supported behavior and compatibility.
5. `README.md`, `API.md`, `CODEC.md`, and release notes.

## Fast lookup rules

| Question | First file | Next step |
|---|---|---|
| Where is a visible setting? | `src/client/java/com/babbur/waypointer/screen/settings/SettingsCatalog.java` | Search the exact setting ID. |
| What is the default value? | `src/main/java/com/babbur/waypointer/config/WaypointerConfig.java` | Dungeon settings use `DungeonConfig.java`. |
| Where does a setting save? | `WaypointerConfig.java` or `DungeonConfig.java` | Main settings save to `config.json`. Dungeon settings save to `dungeon.json`. |
| Which code uses a setting? | Search its getter, such as `showRouteLines()` | The consumer is usually in `render`, `progression`, `chat`, or `dungeon`. |
| Does a feature exist? | Use the feature index in this file | Open the listed primary files and tests. |
| Where are commands registered? | `src/client/java/com/babbur/waypointer/commands/WaypointerCommands.java` | Start in `register(...)`. |
| Where does client startup happen? | `src/client/java/com/babbur/waypointer/WaypointerClient.java` | Read `onInitializeClient()`. |
| Where are routes stored? | `src/main/java/com/babbur/waypointer/config/Storage.java` | The user file is `waypoints.json`. |
| Where is import or export implemented? | `src/main/java/com/babbur/waypointer/codec/` | Start with `WaypointCodec.java` and `WaypointImporter.java`. |
| Where is the public API? | `API.md` and `src/main/java/com/babbur/waypointer/api/` | Start with `WaypointerApi.java`. |

## Common answers

| Question | Answer |
|---|---|
| Can Waypointer draw lines between route waypoints? | Yes. Open Settings, select **Routes & progression**, and enable **Show route connector lines**. The setting ID is `showRouteLines`. Its default is `false`. |
| Where is the route line setting defined? | `SettingsCatalog.java`, in the `routes()` category. |
| Where is the route line value stored? | `WaypointerConfig.java` as `showRouteLines`. |
| Where are route lines rendered? | `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java`. |
| Can the route line color change? | Yes. Use `routeLineColor`. Its default is `0x00FF00`. |
| Are dungeon route lines separate? | Yes. Use `showDungeonRouteLines` in the Dungeons category. Its default is `true`. |
| Can routes show all points at once? | Yes. Set the route load mode to `STATIC`. |
| Can routes show ordered context points? | Yes. The default route load mode is `SEQUENCE`. |
| Do subwaypoints exist? | Yes. They use `FLAG_SUBWAYPOINT` and optional style flags in `Waypoint.java`. |
| Are temporary waypoints persisted? | No. `Storage` excludes temporary waypoints from `waypoints.json`. |
| Is there a public integration API? | Yes. See `API.md` and `src/main/java/com/babbur/waypointer/api/`. |
| Does Waypointer support shader compatibility? | Yes. `irisShaderHudFallback` enables a HUD fallback for Iris shader packs. |

## Repository layout

| Path | Purpose |
|---|---|
| `src/main/java/com/babbur/waypointer/` | Shared data, configuration, codec, API, and core logic. |
| `src/client/java/com/babbur/waypointer/` | Minecraft client startup, rendering, screens, commands, input, chat, location, and dungeon runtime code. |
| `src/test/java/com/babbur/waypointer/` | Unit, compatibility, command-tree, codec, API, and behavior tests. |
| `src/main/resources/` | Fabric metadata, mixin files, assets, and translations. |
| `release-notes/` | Version-specific release notes. |
| `README.md` | User-facing feature summary and build instructions. |
| `API.md` | Public API documentation. |
| `CODEC.md` | Route share-code documentation. |
| `PRODUCT.md` | Product and behavior notes. |

## Startup and dependency wiring

`src/client/java/com/babbur/waypointer/WaypointerClient.java` is the client bootstrap.

`onInitializeClient()` loads and connects these major components:

1. `WaypointerConfig`
2. `DungeonConfig`
3. `ActiveGroupManager`
4. `Storage`
5. `DefaultWaypointerApi`
6. `LocationTracker`
7. `ProximityTracker`
8. `TempWaypointCleaner`
9. `WorldJoinProgressReset`
10. `WaypointRenderer`
11. `TracerRenderer`
12. `WaypointRepositionMode`
13. The dungeon subsystem
14. `WaypointerCommands`
15. `WaypointerKeybinds`
16. `ChatCoordDetector`
17. `ChatImportDetector`
18. Fabric API entrypoints

Use this file to answer whether a subsystem installs during startup.

## Core architecture

| File | Responsibility |
|---|---|
| `src/main/java/com/babbur/waypointer/core/Waypoint.java` | Immutable waypoint data, flags, temporary modes, colors, radii, and precise coordinates. |
| `src/main/java/com/babbur/waypointer/core/WaypointGroup.java` | An ordered route, route progress, load mode, color mode, route timing, subwaypoint structure, and route-level values. |
| `src/main/java/com/babbur/waypointer/core/ActiveGroupManager.java` | All groups, current zone, active groups, authoring focus, temporary focus, previews, and data listeners. |
| `src/main/java/com/babbur/waypointer/core/Zone.java` | Zone identity, canonical IDs, and display data. |
| `src/client/java/com/babbur/waypointer/location/LocationTracker.java` | Detects the current location and updates the active zone. |
| `src/client/java/com/babbur/waypointer/progression/ProximityTracker.java` | Advances routes from player proximity and route rules. |
| `src/main/java/com/babbur/waypointer/config/Storage.java` | Loads and saves the persistent route library. |

### Route modes

`WaypointGroup.LoadMode` defines route visibility:

- `SEQUENCE`: Show route context around the current waypoint.
- `STATIC`: Show all route waypoints.

`WaypointGroup.GradientMode` defines route colors:

- `STATIC`: Use one route color.
- `AUTO`: Interpolate colors across the route.
- `MANUAL`: Keep each waypoint color.

### Waypoint defaults

| Value | Default |
|---|---|
| Color | `0x4FE05A` |
| Reach radius | `3.0` blocks |
| Minimum radius | `0.5` blocks |
| Maximum radius | `100.0` blocks |
| Precise coordinate scale | `16` units per block |

### Waypoint flags

All flags are in `src/main/java/com/babbur/waypointer/core/Waypoint.java`.

| Flag | Purpose |
|---|---|
| `FLAG_HIDE_BEACON` | Hide the waypoint box or beacon surface. |
| `FLAG_HIDE_NAME` | Hide the waypoint name. |
| `FLAG_THROUGH_WALL` | Render through walls. |
| `FLAG_LOCKED_COLOR` | Exclude the waypoint from automatic recoloring. |
| `FLAG_SUBWAYPOINT` | Mark a one-level child of the previous main waypoint. |
| `FLAG_SMALL_SUBWAYPOINT` | Render a small subwaypoint cube. |
| `FLAG_FILLED_SUBWAYPOINT` | Force a filled subwaypoint. |
| `FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED` | Hide the child after its parent activates. |
| `FLAG_DEPTH_CHECKED` | Use normal depth behavior. |
| `FLAG_SKIP_ON_STAND` | Advance when the player stands on the block. |
| `FLAG_SKIP_ON_INTERACT` | Advance when the player interacts with the block. |
| `FLAG_SKIP_ON_MINE` | Advance when the player mines the block. |
| `FLAG_DUNGEON_SECRET` | Mark a dungeon secret stage. |
| `FLAG_DUNGEON_ETHERWARP` | Mark an Etherwarp action. |
| `FLAG_DUNGEON_DUNGEONBREAKER` | Mark a Dungeonbreaker action. |
| `FLAG_DUNGEON_SUPERBOOM` | Mark a Superboom action. |
| `FLAG_DUNGEON_PEARL` | Mark an Ender Pearl launch. |
| `FLAG_DUNGEON_PEARL_TARGET` | Mark the paired pearl landing target. |
| `FLAG_DUNGEON_ITEM` | Mark an item-based secret. |
| `FLAG_DUNGEON_BAT` | Mark a bat-based secret. |

### Temporary waypoint modes

| Constant | Behavior |
|---|---|
| `TEMP_TIME` | Remove after the configured time. |
| `TEMP_UNTIL_REACHED` | Remove when reached. |
| `TEMP_UNTIL_LEAVE` | Remove when the player leaves the server. |

Temporary waypoints never save to the persistent route library.

## Settings system

### Settings files

| File | Purpose |
|---|---|
| `src/client/java/com/babbur/waypointer/screen/settings/SettingsCatalog.java` | Source of truth for visible settings, categories, labels, aliases, conditions, and actions. |
| `src/client/java/com/babbur/waypointer/screen/settings/Setting.java` | Declarative setting model and control types. |
| `src/client/java/com/babbur/waypointer/screen/SettingsScreen.java` | Settings screen layout, navigation, search, and controls. |
| `src/main/java/com/babbur/waypointer/config/WaypointerConfig.java` | Main setting fields, defaults, validation, migration, and save logic. |
| `src/main/java/com/babbur/waypointer/dungeon/config/DungeonConfig.java` | Dungeon setting fields, defaults, and save logic. |
| `src/main/java/com/babbur/waypointer/config/WaypointerConfigCodec.java` | Settings share-code import and export. |
| `src/client/java/com/babbur/waypointer/screen/settings/SettingsPresets.java` | Bundled settings presets. |
| `src/main/resources/assets/waypointer/lang/en_us.json` | Primary English translation keys. |

Settings catalog IDs match their backing config field names. Action IDs start with `action.`.

### Waypoints settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `boxStyle` | Box style | `OUTLINED` | Main |
| `beaconOpacity` | Waypoint box opacity | `0.5` | Main |
| `waypointOutlineThickness` | Outline thickness | `3.0` px | Main |
| `showCompleted` | Show completed waypoints | `true` | Main |
| `defaultWaypointColor` | Default waypoint color | `0x4FE05A` | Main |
| `action.waypointPaint` | Paint | Action | None |
| `placeNewWaypointsBelowPlayer` | Add new waypoints below player | `true` | Main |
| `maxStaticWaypointRenderDistance` | Static marker distance | `0.0` unlimited | Main |
| `tempDefaultMode` | Temp waypoint expiry | `TEMP_TIME` | Main |
| `tempDefaultDurationSec` | Temp duration | `60` seconds | Main |
| `focusTempWaypoints` | Focus mode for temp waypoints | `false` | Main |

Primary consumers:

- `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java`
- `src/client/java/com/babbur/waypointer/input/WaypointAddFlow.java`
- `src/client/java/com/babbur/waypointer/progression/TempWaypointCleaner.java`
- `src/client/java/com/babbur/waypointer/screen/WaypointPainterScreen.java`

### Labels settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `showWaypointNames` | Show waypoint names | `true` | Main |
| `showWaypointDistances` | Show waypoint distances | `true` | Main |
| `matchWaypointTextToWaypointColor` | Waypoint text inherits color | `true` | Main |
| `showLabelBackdrop` | Show label backdrop | `true` | Main |
| `showLabelTextShadow` | Show text shadows | `true` | Main |
| `scaleWaypointTextWithDistance` | Scale text with distance | `false` | Main |
| `labelScale` | Label scale | `1.0` | Main |
| `labelHeightOffset` | Label height offset | `0.0` blocks | Main |
| `maxWaypointLabels` | Max waypoint labels | `32` | Main |
| `hideWaypointLabelsNearPlayer` | Hide labels when near | `false` | Main |
| `hideWaypointLabelsNearRadius` | Label near radius | `5.0` blocks | Main |

Primary consumer: `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java`.

### Tracer settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `showTracer` | Show tracers | `true` | Main |
| `tracerOpacity` | Tracer opacity | `0.95` | Main |
| `tracerThickness` | Tracer thickness | `3.0` px | Main |
| `matchTracerToWaypointColor` | Tracer inherits waypoint color | `true` | Main |
| `tracerColor` | Tracer color | `0x4FE05A` | Main |
| `hideTracerOnStaticRoutes` | Hide tracer on static routes | `true` | Main |

Primary consumers:

- `src/client/java/com/babbur/waypointer/render/TracerRenderer.java`
- `src/client/java/com/babbur/waypointer/render/IrisShaderFallback.java`

### Beacon beam settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `beaconBeamMode` | Beacon beams | `OFF` | Main |
| `useBeaconBeamTextures` | Use beacon textures | `true` | Main |
| `beaconBeamExtendsBelowWaypoint` | Beam extends below waypoint | `false` | Main |

Primary consumer: `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java`.

### Route and progression settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `defaultReachRadius` | Default reach radius | `3.0` blocks | Main |
| `resetProgressOnWorldJoin` | Reset progress when joining a world | `true` | Main |
| `restartRouteWhenComplete` | Restart route after last waypoint | `true` | Main |
| `routeTimesEnabled` | Route times | `false` | Main |
| `showRouteIndicesInGui` | Show route indices | `false` | Main |
| `showRouteProgress` | Show route progress | `false` | Main |
| `dimSequenceContextWaypoints` | Dim sequence context waypoints | `true` | Main |
| `keepSubwaypointsVisibleUntilNextWaypoint` | Keep subwaypoints until next waypoint | `true` | Main |
| `hideReachedStaticWaypointsUntilCycleComplete` | Hide reached static waypoints | `false` | Main |
| `skipAheadMechanicEnabled` | Enable waypoint skip-ahead mechanic | `true` | Main |
| `skipAheadOnlyVisibleWaypoints` | Only skip to visible waypoints | `true` | Main |
| `hideWaypointsNearPlayer` | Hide waypoints when near | `false` | Main |
| `hideWaypointsNearRadius` | Near hide radius | `5.0` blocks | Main |
| `showRouteLines` | Show route connector lines | `false` | Main |
| `routeLineColor` | Route line color | `0x00FF00` | Main |

Primary consumers:

- `src/client/java/com/babbur/waypointer/progression/ProximityTracker.java`
- `src/client/java/com/babbur/waypointer/progression/WorldJoinProgressReset.java`
- `src/main/java/com/babbur/waypointer/core/WaypointGroup.java`
- `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java`
- `src/client/java/com/babbur/waypointer/screen/WaypointerScreen.java`
- `src/client/java/com/babbur/waypointer/screen/GroupEditScreen.java`

### Dungeon settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `enabled` | Dungeon features | `true` | Dungeon |
| `hideCompletedRooms` | Hide completed rooms | `true` | Dungeon |
| `autoCompleteRoomsOnGreenCheckmark` | Auto-complete rooms on green checkmark | `true` | Dungeon |
| `visibleSecretStages` | Visible secret stages | `1` | Dungeon |
| `secretCompletionSound` | Secret completion sound | `true` | Dungeon |
| `showDungeonRouteLines` | Route connector lines | `true` | Dungeon |
| `showDungeonTracers` | Tracers | `false` | Dungeon |
| `showPearlTrajectories` | Ender Pearl trajectories | `true` | Dungeon |
| `showDungeonEntryPathToFirstWaypoint` | Dungeon entry path to first waypoint | `false` | Main |
| `showDungeonEntryPathToFollowingWaypoints` | Continue dungeon path after first | `false` | Main |
| `dungeonEntryPathColor` | Dungeon entry path color | `0x00FF00` | Main |

Primary consumers:

- `src/client/java/com/babbur/waypointer/dungeon/DungeonStateTracker.java`
- `src/client/java/com/babbur/waypointer/dungeon/DungeonRoomRouteSync.java`
- `src/client/java/com/babbur/waypointer/dungeon/DungeonTriggerDetector.java`
- `src/client/java/com/babbur/waypointer/dungeon/DungeonMapCheckmarks.java`
- `src/client/java/com/babbur/waypointer/dungeon/DungeonSecretCompletionSound.java`
- `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java`
- `src/client/java/com/babbur/waypointer/render/TracerRenderer.java`

### Chat settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `chatCoordDetection` | Chat coord detection | `true` | Main |
| `autoAddChatTempWaypoints` | Auto-add chat temp waypoints | `false` | Main |
| `showWaypointChatShareButtons` | New waypoint chat share buttons | `true` | Main |
| `chatCodecDetection` | Chat codec detection | `true` | Main |
| `showContributorBadges` | Contributor badges | `true` | Main |

Primary consumers:

- `src/client/java/com/babbur/waypointer/chat/ChatCoordDetector.java`
- `src/client/java/com/babbur/waypointer/chat/ChatImportDetector.java`
- `src/client/java/com/babbur/waypointer/mixin/client/ChatComponentMixin.java`
- `src/main/java/com/babbur/waypointer/chat/WaypointerContributorBadge.java`
- Contributor badge mixins in `src/client/java/com/babbur/waypointer/mixin/client/`

### Sharing settings

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `importedRouteColorMode` | Imported route colors | `STATIC` or One color | Main |
| `importedRouteDefaultColor` | Imported color | `0x00FF00` | Main |
| `exportIncludeNames` | Include names in default export | `true` | Main |
| `exportIncludeColors` | Include colors in default export | `true` | Main |
| `exportIncludeRadii` | Include radii in default export | `true` | Main |
| `exportIncludeWaypointFlags` | Include waypoint flags | `true` | Main |
| `exportIncludeGroupMeta` | Include route metadata | `true` | Main |

Primary consumers:

- `src/main/java/com/babbur/waypointer/codec/WaypointCodec.java`
- `src/main/java/com/babbur/waypointer/codec/WaypointExportCodec.java`
- `src/main/java/com/babbur/waypointer/codec/WaypointImporter.java`
- `src/client/java/com/babbur/waypointer/screen/ExportScreen.java`
- `src/client/java/com/babbur/waypointer/commands/WaypointerCommands.java`

### System settings and actions

| Setting ID | UI label | Default | Store |
|---|---|---:|---|
| `irisShaderHudFallback` | Iris shader compatibility | `true` | Main |
| `editSounds` | Edit mode sounds | `true` | Main |
| `showEditModeSubtitle` | Show EDIT MODE subtitle | `true` | Main |
| `action.configCode` | Config code | Action | None |
| `action.presets` | Presets | Action | None |
| `action.disableAll` | Disable All | Action | None |
| `action.resetDefaults` | Reset to Defaults | Action | None |
| `action.perfTest` | Performance stress test | Action | None |

Primary consumers:

- `src/client/java/com/babbur/waypointer/render/IrisShaderFallback.java`
- `src/client/java/com/babbur/waypointer/input/WaypointRepositionMode.java`
- `src/client/java/com/babbur/waypointer/screen/SettingsScreen.java`
- `src/client/java/com/babbur/waypointer/screen/settings/PerfStressTestController.java`

### Hidden and indirect config fields

These fields do not have normal settings rows.

| Field | Default or role | File |
|---|---|---|
| `configSchemaVersion` | Main config migration version | `WaypointerConfig.java` |
| `waypointPainterPalette` | Local painter swatches | `WaypointerConfig.java` |
| `waypointPainterDefaultPalette` | Default route paint palette | `WaypointerConfig.java` |
| `waypointPainterDefaultPixels` | Default route paint pixels | `WaypointerConfig.java` |
| `chatCoordSenderBlacklist` | Usernames ignored by chat coordinate detection | `WaypointerConfig.java` |
| `dungeonWaypointsFeatureEnabled` | Legacy compatibility field | `WaypointerConfig.java` |
| `debugLogRoomChanges` | Dungeon room-change debug messages | `DungeonConfig.java` |
| `defaultDirection` | Default dungeon room direction, `NW` | `DungeonConfig.java` |
| `routesPromptDismissed` | Community route prompt state | `DungeonConfig.java` |
| `hiddenRouteRoomIds` | Disabled dungeon room route IDs | `DungeonConfig.java` |

## User data files

Waypointer uses the Fabric config directory under `waypointer/`.

| File | Contents | Owner |
|---|---|---|
| `config/waypointer/config.json` | Main settings | `WaypointerConfig` |
| `config/waypointer/dungeon.json` | Dungeon settings and dungeon route visibility | `DungeonConfig` |
| `config/waypointer/waypoints.json` | Persistent routes and waypoints | `Storage` |

`Storage` uses debounced atomic writes. It excludes temporary and runtime-only groups.

If `waypoints.json` is invalid, `Storage` moves it to an `.invalid` quarantine file when possible.

### Persisted route fields

`Storage.groupToJson(...)` saves these route values:

- `id`
- `name`
- `zone`
- `enabled`
- `currentIndex`
- `gradientMode`
- `loadMode`
- `defaultRadius`
- `skipAheadEnabled`
- `bestTimeMillis`, when present
- `staticColor`
- `gradientStartColor`
- `gradientEndColor`
- `paintEnabled`
- `paint`, when present
- `waypoints`

## Feature index

| Feature | Exists | Primary files |
|---|---|---|
| Per-zone route activation | Yes | `LocationTracker.java`, `Zone.java`, `ActiveGroupManager.java` |
| Ordered sequence routes | Yes | `WaypointGroup.java`, `ProximityTracker.java` |
| Static all-waypoint routes | Yes | `WaypointGroup.LoadMode.STATIC`, `WaypointRenderer.java` |
| Route skip-ahead | Yes | `ProximityTracker.java`, `WaypointGroup.java`, `WaypointerConfig.java` |
| Manual skip and skip-to | Yes | `WaypointerCommands.java`, `WaypointerKeybinds.java` |
| Route completion timing | Yes | `WaypointGroup.java`, `ProximityTracker.java` |
| Route connector lines | Yes | `WaypointRenderer.java`, `showRouteLines`, `routeLineColor` |
| Dungeon route connector lines | Yes | `DungeonConfig.java`, `WaypointRenderer.java` |
| Dungeon entry path | Yes | `WaypointRenderer.java`, dungeon entry path settings |
| Waypoint boxes and fills | Yes | `WaypointRenderer.java`, `boxStyle` |
| Waypoint labels and distances | Yes | `WaypointRenderer.java`, label settings |
| Route progress labels | Yes | `WaypointRenderer.java`, `showRouteProgress` |
| Crosshair tracers | Yes | `TracerRenderer.java` |
| Beacon beams | Yes | `WaypointRenderer.java`, beacon beam settings |
| Iris shader HUD fallback | Yes | `IrisShaderFallback.java` |
| Temporary waypoints | Yes | `Waypoint.java`, `TempWaypointCleaner.java`, `AddTempScreen.java` |
| Chat coordinate detection | Yes | `ChatCoordDetector.java` |
| Automatic chat temp waypoints | Yes | `ChatCoordDetector.java`, `autoAddChatTempWaypoints` |
| Chat share-code detection | Yes | `ChatImportDetector.java`, `CodecScanner.java` |
| Compact route export | Yes | `WaypointCodec.java`, `V9CompactCodec.java`, `WaypointExportCodec.java` |
| Route import | Yes | `WaypointImporter.java` |
| External format compatibility tests | Yes | Codec compatibility tests under `src/test/java/` |
| Subwaypoints | Yes | `Waypoint.java`, `WaypointGroup.java`, `WaypointRenderer.java`, `GroupEditScreen.java` |
| Small and filled subwaypoints | Yes | `Waypoint.java`, `WaypointRenderer.java` |
| Precise 1/16-block positions | Yes | `Waypoint.java`, `WaypointRepositionMode.java` |
| Persistent edit mode | Yes | `WaypointRepositionMode.java` |
| Route and waypoint GUI editing | Yes | `WaypointerScreen.java`, `GroupEditScreen.java` |
| Waypoint painter | Yes | `WaypointPainterScreen.java`, `WaypointPaintApplyScreen.java`, `WaypointPaint.java` |
| Config share codes | Yes | `WaypointerConfigCodec.java`, `SettingsScreen.java` |
| Settings presets | Yes | `SettingsPresets.java` |
| Settings search and recent settings | Yes | `SettingsScreen.java`, `RecentSettings.java` |
| Performance stress test | Yes | `PerfStressTestController.java`, `PerfScenarios.java` |
| Developer diagnostics | Yes | `DeveloperModeMonitor.java`, `DebugInspectScreen.java`, `DebugReportConsentScreen.java` |
| Contributor badges | Yes | `WaypointerContributorBadge.java` and client mixins |
| Public integration API | Yes | `API.md`, `src/main/java/com/babbur/waypointer/api/` |
| Localization | Yes | `src/main/resources/assets/waypointer/lang/` |
| Dungeon room detection | Yes | `DungeonStateTracker.java`, dungeon data classes |
| Dungeon room route projection | Yes | `DungeonRoomRouteSync.java`, `DungeonRoomZoneBridge.java` |
| Dungeon secret trigger detection | Yes | `DungeonTriggerDetector.java` |
| Dungeon map checkmark completion | Yes | `DungeonMapCheckmarks.java` |
| Dungeon completion sound | Yes | `DungeonSecretCompletionSound.java` |
| Ender Pearl trajectory display | Yes | Dungeon rendering and trigger files |
| Community dungeon route download | Yes | `DungeonRouteDownloader.java`, `DungeonCommands.java` |
| Per-room dungeon route visibility | Yes | `DungeonConfig.hiddenRouteRoomIds`, dungeon route UI and commands |
| Happy Snowman easter egg | Yes | `HappySnowmanSession.java`, `/happysnowman` |

## Rendering map

| Concern | Primary file |
|---|---|
| Waypoint boxes, fills, labels, beams, route lines, and dungeon paths | `src/client/java/com/babbur/waypointer/render/WaypointRenderer.java` |
| Crosshair tracer | `src/client/java/com/babbur/waypointer/render/TracerRenderer.java` |
| Iris shader fallback | `src/client/java/com/babbur/waypointer/render/IrisShaderFallback.java` |
| Render diagnostics | `src/client/java/com/babbur/waypointer/render/RenderDiagnostics.java` |
| Happy Snowman session rendering | `src/client/java/com/babbur/waypointer/render/HappySnowmanSession.java` |

Search a config getter in these files to find the exact render branch.

## Command map

Command roots:

- `/waypointer`
- `/wptr`
- `/wp`
- `/happysnowman`

All command registration is in:

`src/client/java/com/babbur/waypointer/commands/WaypointerCommands.java`

### Main command families

| Command family | Purpose |
|---|---|
| `gui` | Open the Waypointer GUI. |
| `help` | Show command help by page or section. |
| `list` | List active route data. |
| `add`, `add at` | Add a persistent waypoint. |
| `addtemp at` | Add a temporary waypoint. |
| `insert` | Insert a waypoint at a route slot. |
| `remove` | Remove a waypoint. |
| `move` | Reorder a waypoint. |
| `skip`, `unskip`, `skipto` | Control route progress. |
| `reset` | Reset the active route. |
| `mode` | Set the active route load mode. |
| `radius` | Set the active route radius. |
| `sub` | Toggle subwaypoint state. |
| `tiny`, `filled`, `hap` | Change subwaypoint style or visibility flags. |
| `sts`, `its`, `los` | Change trigger or depth flags. |
| `export` | Export the active route. |
| `import`, `importfile`, `importchat` | Import route data. |
| `blacklist` | Manage ignored chat coordinate senders. |
| `editmode`, `edit mode` | Toggle persistent edit mode. |
| `debug`, `devmode` | Open or control diagnostics. |
| `waypoint` | Edit a waypoint by index. |
| `route`, `group` | Create or edit route-level values. |
| `area` | Work with route zones. |

### Route or group operations

The `route` and `group` command trees include:

- create
- rename
- zone or area assignment
- load mode
- radius
- skip-ahead
- enable
- disable
- color mode
- one color
- gradient endpoints
- delete with confirmation

Use the command tree in source for exact syntax and current aliases.

## Screen map

| Screen | Purpose |
|---|---|
| `WaypointerScreen.java` | Main route list and navigation. |
| `GroupEditScreen.java` | Route and waypoint editing. |
| `SettingsScreen.java` | Settings categories, search, actions, and controls. |
| `AddNamedWaypointScreen.java` | Add a named persistent waypoint. |
| `AddTempScreen.java` | Add a temporary waypoint. |
| `ExportScreen.java` | Configure and copy route exports. |
| `ColorPickerScreen.java` | Select colors. |
| `WaypointPainterScreen.java` | Create waypoint paint textures. |
| `WaypointPaintApplyScreen.java` | Apply paint to routes. |
| `DungeonRoomExportScreen.java` | Export dungeon room routes. |
| `DebugInspectScreen.java` | Inspect debug data. |
| `DebugReportConsentScreen.java` | Review debug report sharing. |

`WaypointerGuiScreens.java` identifies screens owned by Waypointer and supports GUI suspension and resume.

## Import, export, and codec map

| File | Purpose |
|---|---|
| `CODEC.md` | Share-code format documentation. |
| `WaypointCodec.java` | Main route codec entrypoint and options. |
| `WaypointExportCodec.java` | Export projection and encoding. |
| `WaypointImporter.java` | Import detection, conversion, and route creation. |
| `V9CompactCodec.java` | Compact V9 implementation. |
| `CodecDictionary.java` | Codec dictionary support. |
| `V9CodecDictionary.java` | V9 dictionary data. |
| `CodecZoneDictionary.java` | Zone dictionary data. |
| `DecodeDebug.java` | Decode diagnostics. |
| `CodecScanner.java` | Find share codes in text or chat. |
| `ExportScreen.java` | User export controls. |
| `ChatImportDetector.java` | Detect import payloads in chat. |
| `ChatImportCache.java` | Cache clickable chat imports. |

### Codec and compatibility tests

Important tests include:

- `WaypointCodecTest.java`
- `WaypointCodecV9Test.java`
- `WaypointCodecDebugTest.java`
- `WaypointExportCodecTest.java`
- `WaypointImportHardeningTest.java`
- `WaypointImporterTest.java`
- `CodecScannerTest.java`
- `ChatImportDetectorTest.java`
- `SkytilsCurrentCompatibilityTest.java`
- `SkyblockerCurrentCompatibilityTest.java`
- `OdinDungeonWaypointImportTest.java`
- `ZoneCanonicalizationIntegrationTest.java`

## Dungeon subsystem map

The dungeon subsystem installs from `WaypointerClient.installDungeonSubsystem(...)`.

| File | Responsibility |
|---|---|
| `DungeonConfig.java` | Dungeon settings and hidden route IDs. |
| `DungeonRoomData.java` | Room definitions and custom data store. |
| `DungeonStateTracker.java` | Current dungeon and room state. |
| `DungeonRouteSession.java` | Session progress for room routes. |
| `DungeonRoomZoneBridge.java` | Connect room detection to zone state. |
| `DungeonRoomRouteSync.java` | Project stored room-local routes into runtime world routes. |
| `DungeonTriggerDetector.java` | Detect stand, interact, mine, item, bat, and secret events. |
| `DungeonMapCheckmarks.java` | Read map completion checkmarks. |
| `DungeonChestInteractionGuard.java` | Guard chest interaction logic. |
| `DungeonRouteDownloader.java` | Download community routes. |
| `DungeonCommands.java` | Dungeon command tree. |
| `DungeonRoomBlockLookup.java` | Room block lookup support. |
| `DungeonSecretCompletionSound.java` | Completion sound behavior. |
| `DungeonSoundHook.java` | Sound event hook. |
| `DungeonRoomWaypointPlacement.java` | Dungeon-local waypoint placement. |

Stored dungeon room routes use room-local coordinates. `DungeonRoomRouteSync` creates runtime mirrors for the detected room placement.

## Public API map

Start with `API.md`.

Key API files under `src/main/java/com/babbur/waypointer/api/` include:

- `WaypointerApi.java`
- `DefaultWaypointerApi.java`
- `WaypointerApiEntrypoint.java`
- `WaypointerApiEntrypoints.java`
- `WaypointerHandle.java`
- `WaypointSnapshot.java`
- `WaypointGroupSnapshot.java`
- `ZoneSnapshot.java`
- `WaypointSpec.java`
- `RouteSpec.java`
- `RouteOverlaySpec.java`
- `WaypointReference.java`
- `WaypointFlags.java`
- `ImportSource.java`
- `ImportOptions.java`
- `ImportSummary.java`
- `ExportTarget.java`
- `ExportOptions.java`
- `RouteLoadMode.java`

`WaypointerClient` creates `DefaultWaypointerApi` and invokes Fabric API entrypoints during startup.

## Tests and validation

Use these commands from the repository root:

```bash
./gradlew test
./gradlew buildAllTargets
```

High-value test areas:

| Area | Test examples |
|---|---|
| Settings parity | `SettingsCatalogTest` and config codec tests |
| Command structure | `WaypointerCommandTreeTest.java` |
| Codec behavior | `WaypointCodecTest.java`, `WaypointCodecV9Test.java` |
| Import hardening | `WaypointImportHardeningTest.java` |
| External compatibility | Skytils, Skyblocker, and Odin compatibility tests |
| Public API | `DefaultWaypointerApiTest.java` |
| Zone migration | `ZoneCanonicalizationIntegrationTest.java` |
| Chat imports | `CodecScannerTest.java`, `ChatImportDetectorTest.java` |

## How to answer repository questions

### Find a setting

1. Search the user-facing phrase in `SettingsCatalog.java`.
2. Record the setting ID.
3. Find the field and default in `WaypointerConfig.java` or `DungeonConfig.java`.
4. Search the getter across the repository.
5. Report the settings category, setting ID, default, and runtime consumer.

### Check whether a feature exists

1. Search this feature index.
2. Check the listed primary source files.
3. Check startup wiring in `WaypointerClient.java`.
4. Check tests for supported behavior.
5. State whether the feature is user-visible, hidden, experimental, or legacy.

### Trace a route behavior

1. Start with `WaypointGroup.java`.
2. Check `ActiveGroupManager.java` for active route selection.
3. Check `ProximityTracker.java` for progress.
4. Check `WaypointRenderer.java` and `TracerRenderer.java` for display.
5. Check `Storage.java` for persistence.

### Trace an import or export issue

1. Start with `WaypointCodec.java`.
2. Check `WaypointExportCodec.java` or `WaypointImporter.java`.
3. Check `CODEC.md`.
4. Run the matching codec and compatibility tests.

### Trace a dungeon issue

1. Confirm `DungeonConfig.enabled()`.
2. Check room detection in `DungeonStateTracker.java`.
3. Check route projection in `DungeonRoomRouteSync.java`.
4. Check completion events in `DungeonTriggerDetector.java`.
5. Check map completion in `DungeonMapCheckmarks.java`.
6. Check current room data in `DungeonRoomData.java`.

## Maintenance rules

Update this file when any of these changes occur:

- A setting is added, removed, renamed, moved, or gets a new default.
- A feature gains a new primary implementation file.
- A user data file or schema changes.
- A command family changes.
- A codec version or compatibility target changes.
- A new public API surface appears.
- A subsystem moves during a refactor.

After an update:

1. Set the new baseline commit.
2. Compare the settings tables with `SettingsCatalog.java`.
3. Compare defaults with both config classes.
4. Run `./gradlew test`.
5. Run `./gradlew buildAllTargets` when release targets changed.
