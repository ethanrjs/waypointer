Fabric mod for Hypixel Skyblock that aims to be the best possible waypoint manager.

### Features

- Per-zone waypoints
- Dungeon routes support
- Crystal Hollows structure detection + Map
- In-Game Route Browser
- Ordered routes
- Subwaypoints
- Waypoint Painting
- Global/Zone/Route/Waypoint level customization
- Share from chat
- Keybindings
- Waypoint skipping
- Wishing Compass solver
- Works with shaders
- ...and so much more!

Waypoint Import/Export Compatibility

- Waypointer codes (I/E)
- Coleweight/SkyHanni (I/E)
- Skytils 1.x V1 (I/E) and V2 (I)
- Skyblocker V1 (I/E)
- ChunkLogger RouteSkipper (I)
- Soopy V1 (I)
- Firmament (I)
- Skysoft (I/E) and JSON libraries (I)
- Odin (I/E)
- Generic JSON-based (I)

### Community routes

The 'Community' button next to the route search opens the catalog of routes
other players published. Pick a route to preview and install it. Publish your
own routes with the **Publish** button in the route editor.

### Skyblocker structure sharing

This mod interoperates with the Skyblocker API and has safeguards to prevent abuse and spam. If you are on the Skyblocker team and want me to shut off access to the Skyblocker web socket for users, please create an issue. I can disable it immediately, remotely.

### Requirements

- Minecraft 26.1 to 26.3
- Java 25+
- Fabric Loader 0.19.3+
- [Fabric API](https://modrinth.com/mod/fabric-api):
  - Minecraft 26.1.x: a compatible 26.1.x release (the build target uses 0.154.2+26.1.2)
  - Minecraft 26.2: 0.154.2+26.2 or newer compatible 26.2 release
  - Minecraft 26.3: 0.162.0+26.3 or newer compatible 26.3 release
- [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.2+

### Building
```powersh
./gradlew buildAllTargets
```
- `build/libs/waypointer-<mod-version>-mc26.1.2.jar`
- `build/libs/waypointer-<mod-version>-mc26.2.jar`
- `build/libs/waypointer-<mod-version>-mc26.3.jar`

### API for Other Mods

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
