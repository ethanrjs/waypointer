
Fabric mod for Hypixel Skyblock that aims to be the best possible waypoint manager.
<img width="2560" height="1369" alt="2026-11-15_02 21 56" src="https://github.com/user-attachments/assets/3e9e5119-6e8b-4f70-9dd8-030f452afd15" />

## Features

- Per-zone waypoints
- Dungeon rooms support
- Crystal Hollows structure detection
- In-Game Route Browser
- Ordered routes
- Subwaypoints
- Waypoint Painting
- Keybindings
- Waypoint skipping
- Wishing Compass solver
- Customizable waypoint visuals
- Works with shaders
- ...and so much more!

Supports ALL waypoint mods! (i think)

Waypointer route-library codes preserve folder membership and folder colors.
Config export uses universal `WP:` V10 kind-3 codes. Legacy `WPC:` versions 1-6 remain importable.

## Route and waypoint IDs

`@mod:1` names route 1 in Mines of Divan; `@mod:1#5` names its fifth main waypoint.
A subwaypoint uses a suffix such as `@mod:1#5.1`. Route lists show these IDs by default,
and route and waypoint menus always offer **Copy ID**.

- `/wp route @mod:1 rename Divan loop` edits that route without changing the recording target.
- `/wp waypoint @mod:1#5 rename Chest` edits a waypoint from any zone.
- `/wp move @mod:1#1 @mod:1#5` reorders waypoints within the same route.
- `/wp edit @mod:1`, `/wp waypoints @mod:1`, and `/wp export @mod:1#5` accept IDs too.

IDs belong to your local installation. Imported routes receive their own local IDs;
use an exported code or a catalog link to share a route with someone else. Route numbers
survive restarts and are not reused after deletion. Moving a route to another zone
gives it a new ID. Waypoint suffixes follow the editor's numbering, so inserting,
removing, or reordering waypoints can change them. Tab completion helps find IDs.

## Community routes

The 'Community' button next to the route search opens the catalog of routes
other players published. Pick a route to preview and install it. Publish your
own routes with the `Publish` button in the route editor.

## Skyblocker structure sharing

This mod interoperates with the Skyblocker API and has safeguards to prevent abuse and spam. If you are on the Skyblocker team and want me to shut off access to the Skyblocker web socket for users, please create an issue. I can disable it immediately, remotely.

Requirements

- Minecraft 26.1.x -> 26.3
- Java 25+
- Fabric Loader 0.19.3+
- [Fabric API](https://modrinth.com/mod/fabric-api):
  - Minecraft 26.1.x: a compatible 26.1.x release (the build target uses 0.154.2+26.1.2)
  - Minecraft 26.2: 0.154.2+26.2 or newer compatible 26.2 release
  - Minecraft 26.3: 0.162.0+26.3 or newer compatible 26.3 release
- [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.2+

Building
```powersh
./gradlew buildAllTargets
```

## API
See [API.md](API.md) for more details and examples.

## License

[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) (`GPL-3.0-only`) — use, modify, and distribute the mod, including commercially, under GPLv3's terms. See `LICENSE` for the full text.
