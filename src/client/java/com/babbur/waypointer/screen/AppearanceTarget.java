package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.AppearanceScope;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.babbur.waypointer.text.AmpersandFormatting;
import net.minecraft.network.chat.Component;

record AppearanceTarget(AppearanceScope scope, Component name, Runnable afterWrite) {

    static final List<String> ZONE_FAMILIES = List.of("dungeon", "mineshaft", "crystal_hollows");

    private static final Set<String> ROUTE_HIDDEN = Set.of("defaultWaypointColor");

    private static final Set<String> WAYPOINT_HIDDEN = Set.of(
            "defaultWaypointColor", "waypointSkipFadeMs", "colorSequenceWaypointsByRole",
            "sequencePreviousWaypointColor", "sequenceCurrentWaypointColor",
            "sequenceNextWaypointColor", "dimSequenceContextWaypoints", "showRouteLines",
            "useEtherwarpHeight", "routeLineColor", "routeMinRenderDistance",
            "routeMaxRenderDistance", "routeLineWidth", "routeLineOpacity", "routeLineDashed",
            "hideTracerOnStaticRoutes", "showRouteProgress");

    static AppearanceTarget zone(WaypointerConfig config, String zoneKey) {
        return new AppearanceTarget(AppearanceScope.zone(config, zoneKey), zoneLabel(zoneKey), () -> { });
    }

    static AppearanceTarget route(ActiveGroupManager manager, WaypointerConfig config,
                                  WaypointGroup visible, Runnable afterWrite) {
        WaypointGroup stored = DungeonRoomRouteLibrary.durableEditTarget(manager, visible);
        if (stored == null) return null;
        AppearanceScope scope = AppearanceScope.route(config, stored, style -> {
            stored.setStyleOverride(style);
            if (visible != stored) visible.setStyleOverride(style);
        });
        return new AppearanceTarget(scope, Component.literal(AmpersandFormatting.translate(stored.name())), afterWrite);
    }

    static AppearanceTarget waypoint(ActiveGroupManager manager, WaypointerConfig config,
                                     WaypointGroup group, int index, Runnable afterWrite) {
        if (index < 0 || index >= group.size()
                || DungeonRoomRouteLibrary.durableEditTarget(manager, group) != group) return null;
        AppearanceScope scope = AppearanceScope.waypoint(config, group, index,
                style -> group.setWaypointStyle(index, style));
        String label = group.get(index).name();
        Component name = label == null || label.isBlank()
                ? Component.translatable("waypointer.screen.appearance.waypoint_number",
                        group.displayIndexLabel(index).replaceFirst("^#", ""))
                : Component.literal(AmpersandFormatting.translate(label));
        return new AppearanceTarget(scope, name, afterWrite);
    }

    boolean includes(String settingId) {
        return switch (scope.level()) {
            case WAYPOINT -> !WAYPOINT_HIDDEN.contains(settingId);
            case ROUTE -> !ROUTE_HIDDEN.contains(settingId);
            default -> true;
        };
    }

    Component sourceLabel(AppearanceScope.Layer layer) {
        if (layer == null) return Component.translatable("waypointer.screen.appearance.source.global");
        return switch (layer.level()) {
            case ZONE -> zoneLabel(layer.key());
            case ROUTE -> Component.translatable("waypointer.screen.appearance.source.route");
            case SAVED_STYLE -> Component.translatable("waypointer.screen.appearance.source.saved_style", layer.key());
            case GLOBAL, WAYPOINT -> name;
        };
    }

    List<Component> breadcrumb() {
        List<Component> crumbs = new ArrayList<>();
        crumbs.add(Component.translatable("waypointer.screen.appearance.source.global"));
        for (AppearanceScope.Layer layer : scope.parents()) {
            if (layer.level() == AppearanceScope.Level.ZONE) crumbs.add(zoneLabel(layer.key()));
        }
        if (scope.level() == AppearanceScope.Level.WAYPOINT) {
            crumbs.add(Component.translatable("waypointer.screen.appearance.source.route"));
        }
        crumbs.add(name);
        return crumbs;
    }

    static Component zoneLabel(String zoneKey) {
        return switch (zoneKey) {
            case "dungeon" -> Component.translatable("waypointer.screen.appearance.zone.dungeon");
            case "mineshaft" -> Component.translatable("waypointer.screen.appearance.zone.mineshaft");
            case "crystal_hollows" -> Component.translatable("waypointer.screen.appearance.zone.crystal_hollows");
            default -> {
                var room = DungeonRoomData.entry(zoneKey);
                yield Component.literal(room != null ? room.displayName() : Zone.fromId(zoneKey).displayName());
            }
        };
    }

    /**
     * The zone look a route in {@code zoneId} follows and can edit from its menu: the family for
     * dungeon rooms and mineshafts, otherwise the zone itself. Null where zone looks don't apply.
     */
    static String zoneLookKey(String zoneId) {
        List<String> keys = com.babbur.waypointer.config.WaypointStyles.zoneKeys(zoneId);
        if (keys.isEmpty()) return null;
        String key = keys.getFirst();
        return key.equals(Zone.PRIVATE_WORLD.id()) || key.equals(Zone.UNKNOWN.id())
                || key.startsWith("temp") ? null : key;
    }

    static List<String> switcherZoneKeys(Zone current) {
        return switcherZoneKeys(current, List.of(), List.of());
    }

    /**
     * Zones offered by the Editing switcher: the current zone, the three zone families, then
     * every other zone the player has routes in or already gave a look, sorted by name. Single
     * dungeon rooms and mineshaft variants stay under their family.
     */
    static List<String> switcherZoneKeys(Zone current, java.util.Collection<String> routeZoneIds,
                                         java.util.Collection<String> styledZoneKeys) {
        List<String> keys = new ArrayList<>();
        if (current != null && current != Zone.UNKNOWN && current != Zone.PRIVATE_WORLD) {
            for (String key : com.babbur.waypointer.config.WaypointStyles.zoneKeys(current.id())) {
                if (!ZONE_FAMILIES.contains(key)) keys.add(key);
            }
        }
        keys.addAll(ZONE_FAMILIES);
        java.util.TreeMap<String, String> others = new java.util.TreeMap<>();
        java.util.function.Consumer<String> offer = id -> {
            if (id == null || id.isBlank()) return;
            List<String> zoneKeys = com.babbur.waypointer.config.WaypointStyles.zoneKeys(id);
            if (zoneKeys.size() != 1) return;
            String key = zoneKeys.getFirst();
            if (keys.contains(key) || key.equals(Zone.PRIVATE_WORLD.id())
                    || key.equals(Zone.UNKNOWN.id()) || DungeonRoomData.entry(key) != null) return;
            others.put(zoneLabel(key).getString().toLowerCase(java.util.Locale.ROOT) + "|" + key, key);
        };
        routeZoneIds.forEach(offer);
        styledZoneKeys.forEach(offer);
        keys.addAll(others.values());
        return keys;
    }
}
