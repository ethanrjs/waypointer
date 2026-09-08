# Crystal Hollows and dungeon commands

The command surface is limited to manual coordinates, GUI navigation, and file import.

| Command | Purpose |
| --- | --- |
| `/wpch add <structure> <x> <y> <z>` | Record a manual Crystal Hollows structure location. All three integer coordinates are required. Structure IDs and aliases such as `jungle_temple` and `divan` are accepted. |
| `/wpd` | Open the dungeon room routes in the main GUI. |
| `/wpd import <file>` | Import dungeon routes directly from a file, including legacy SecretRoutes and Odin formats. The GUI import control reads the clipboard. |
| `/waypointer-dungeon` | Compatibility alias for `/wpd`, including file import. |

Example: `/wpch add odawa 349 110 390`.

Structure and compass Share buttons fill the chat input with the displayed name and coordinates. The route menu opens the same editable chat draft. Review the text and press Send to share it in your selected chat channel. These buttons do not run a hidden command or send automatically.

Use `/wp` for the main GUI, `/wp gui dungeon` for dungeon rooms, and the Settings button for Mining and Dungeons settings. Edit a route to reset its progress. Use the existing waypoint controls and keybinds for route progress. Use `/wp debug` for diagnostics.

The Crystal Hollows `info`, `share`, `remove`, `clear`, `compass`, and `toggle` commands are removed. Bare `/wpch`, addition at the player without coordinates, and the `/waypointer-crystal` alias are also removed.

The dungeon `info`, `room list`, `route next`, `route reset`, `route found`, `rotate`, and `toggle` commands are removed. Manual rotation overrides and the room-change debug toggle are retired command controls; they do not have matching GUI buttons.
