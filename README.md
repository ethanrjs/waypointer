Fabric mod for Hypixel Skyblock that aims to be the best possible waypoint manager.

Features

- Per-zone waypoints
- Dungeon rooms support (Odin-compatible room detection, all 140 Catacombs rooms)
- Crystal Hollows structure detection (Jungle Temple, Mines of Divan, Goblin Queen's Den, Lost Precursor City, Khazad-dûm, Fairy Grotto, King Yolkar, Odawa, Corleone, Key Guardians, Dragon's Lair) with per-lobby memory; uses only information the game already shows you
- Wishing Compass solver
- Ordered routes
- World rendering
- Subwaypoints
- Extremely compact export format
- Importable from chat messages
- Keybindings
- Editor mode
- Clean UI for editing and management
- Waypoint skipping in sequence
- Temporary Waypoints
- Clickable chat coordinates
- Auto-added temporary chat coordinate waypoints
- Customizable waypoint visuals
- Public API for other Fabric client mods
- Works with shaders

Waypoint Import/Export Compatibility

- Waypointer codes (I/E)
- Coleweight/SkyHanni (I/E)
- Skytils 1.x V1 (I/E) and V2 (I)
- Skyblocker V1 (I/E)
- ChunkLogger RouteSkipper (I/E)
- Soopy V1 (I)
- Firmament (I)
- Generic JSON-based (I)

Waypointer route-library codes preserve folder membership and folder colors.
Config export uses universal `WP:` V10 kind-3 codes. Legacy `WPC:` versions 1-6 remain importable.

Publisher identity across launcher profiles

New launcher profiles reuse the first Waypointer publishing identity saved on
this computer. A protected copy is kept in your user settings outside the
launcher profile, so replacing a profile preserves your publishing name.
Existing profiles with a different publishing identity keep that identity.
Publication history remains local to each profile.

The shared identity is stored under `waypointer/publisher/identity.json` in
macOS Application Support, Windows AppData/Roaming, or Linux XDG_CONFIG_HOME
(default `~/.config`). Keep this file private; it contains your publishing key.

Dungeon route import

- SecretRoutes `routes.json`: `/wpd import <file>`
- Odin dungeon waypoints: JSON file or import string
- Waypointer `WPD:` share codes (I/E)

Requirements

- Minecraft 26.1 to 26.2
- Java 25+
- Fabric Loader 0.19.3+
- [Fabric API](https://modrinth.com/mod/fabric-api):
  - Minecraft 26.1.x: a compatible 26.1.x release (the build target uses 0.154.2+26.1.2)
  - Minecraft 26.2: 0.154.2+26.2 or newer compatible 26.2 release
- [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.2+

Building
```powersh
./gradlew buildAllTargets
```
This builds and tests both targets serially and produces separate runnable jars;
it does not produce one universal jar:

- `build/libs/waypointer-<mod-version>-mc26.1.2.jar`
- `build/libs/waypointer-<mod-version>-mc26.2.jar`

Matching `-sources.jar` files are also generated. The current mod version is
`1.10.0`.

CODEC Specification
See [CODEC.md](CODEC.md) for the full specification.

API for Other Mods

Waypointer exposes a Fabric entrypoint API for other client mods:

```json
{
  "entrypoints": {
    "waypointer:api": [
      "com.example.routes.ExampleWaypointerIntegration"
    ]
  }
}
```

See [API.md](API.md) for more details and examples.

## License

[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) (`GPL-3.0-only`) — use, modify, and distribute the mod, including commercially, under GPLv3's terms. See `LICENSE` for the full text.
