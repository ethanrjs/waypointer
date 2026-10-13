# English strings changed since 1.11.0

For the translation pass on 2026-10-15. Regenerate this list with:

```
git diff 1588724 -- src/main/resources/assets/waypointer/lang/en_us.json
```

## Changed wording (existing translations are now out of date)
- `waypointer.screen.main.menu.appearance`: "Appearance..." (now opens the route's look)
- `waypointer.screen.group_edit.mode.static`: "Show all" (was "STATIC")
- `waypointer.screen.group_edit.mode.sequence`: "Step by step" (was "SEQUENCE")
- `waypointer.screen.group_edit.mode.tooltip`
- `waypointer.screen.group_edit.menu.subwaypoint_on`: "Turn into subwaypoint"
- `waypointer.screen.group_edit.menu.subwaypoint_off`: "Turn into main waypoint"
- `waypointer.screen.route_catalog.search.tooltip`: shortened
- `waypointer.settings.setting.waypointSkipFadeMs.label`: "Waypoint fade time (ms)"
- `waypointer.settings.setting.waypointSkipFadeMs.tooltip`

## Removed
- `waypointer.screen.route_catalog.subtitle` (already deleted from every locale file)

## New key groups
- `waypointer.screen.appearance.*`: the scoped appearance screen
- `waypointer.screen.group_edit.appearance*`, `.peek*`, `.row.radius`, `.control.own_look`
- `waypointer.screen.group_edit.menu.color_reset`, `.add_subwaypoint`, `.appearance*`, `.subwaypoint_first`, `.subwaypoint_no_main`
- `waypointer.screen.main.route.*`: hub route subtitle, progress and load mode words
- `waypointer.command.suggest.*`: command suggestion tooltips
- `waypointer.command.route.target_*`, `waypointer.command.help.route_target`
