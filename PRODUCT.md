# Waypointer design

## Design Context

### Users
Hypixel Skyblock players mid-run. Dungeon groups plotting F7 terminal paths, Crystal Hollows miners laying out fairy grotto routes, Garden farmers saving plot corners. They open Waypointer briefly between encounters, scan or edit, and close. The GUI renders over an active, busy Minecraft world.

### Brand personality
Clinical / utility. Fast, dense-but-calm, unopinionated. A trader's terminal, not a PvP hack client. Three words: **precise, quiet, functional**.

### Aesthetic direction
- **Theme**: Dark translucent over the world. We do not control light/dark; the world is always the backdrop.
- **References**: macOS HUDs, Linear's command palette, a well-laid-out spreadsheet.
- **Anti-references**: vanilla Minecraft menus (gray button soup, no hierarchy), bloated PvP client UIs (neon gradients, cyberpunk panels, everything fighting for attention).
- **Density**: breathing room. Fewer elements visible at once, generous whitespace inside panels, hierarchy via space and weight -- not color.

### Design principles
1. **Space does the work.** Before reaching for a color or a border, try a gap. Hierarchy lives in spacing first, weight second, color last.
2. **One accent color.** A single muted aqua (`ACCENT = 0xFF4FB3C4`) for *the currently selected thing* only. Everything else is grayscale.
3. **Panels over buttons.** Group related controls into one visual surface (a sidebar) rather than scattering `Button` widgets.
4. **No button if a panel will do.** Every button we can remove by inlining its state into a panel is a win. (Sort trio -> one cycling toggle. Gradient / Mode toggles live in the metadata panel, not as hero buttons.)
5. **Translucent surfaces, not stacked cards.** The world is our background; use single-depth semi-transparent fills, never nested borders or drop shadows.

## Implementation

All shared tokens live in `src/client/java/dev/ethan/waypointer/screen/GuiTokens.java`. Every screen should use those constants rather than inventing new pixel values. `GuiTokens.layoutFooter` is the only correct way to build a footer; it measures first and wraps overflow onto a row above rather than letting buttons slide under the right-anchored one.

**Screens:**
- `WaypointerScreen` -- sidebar with zones, main list of groups, responsive footer with `New Waypoints / Edit / Delete / Import / Export Zone / Settings` on the left and `Done` on the right.
- `GroupEditScreen` -- sidebar with group metadata (name, gradient, mode, radius, sort cycle, reset progress), main waypoint list, footer with `+ Add Here / Export / Remove / ^ / v` on the left and `Done` on the right.
- `ConfigScreen` -- two-column toggles grouped under `Rendering` and `Behavior` headers, responsive footer.
- `ExportScreen` -- preset toolbar + preview + responsive footer. Minimal structural changes; shares tokens for consistency.

**The "unknown" zone** in the sidebar is intentionally muted (no accent bar, muted label) so an unresolved zone never becomes the visual focal point of an empty state.
