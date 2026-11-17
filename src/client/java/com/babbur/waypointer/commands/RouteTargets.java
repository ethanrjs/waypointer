package com.babbur.waypointer.commands;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.RouteIds;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.screen.RouteFolderListModel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

final class RouteTargets {

    enum Reason { PINNED, EDITED, FIRST }

    record Target(WaypointGroup route, Reason reason) {}

    record WaypointAt(WaypointGroup route, int index) {}

    record Lookup<T>(T value, Component error) {
        static <T> Lookup<T> of(T value) { return new Lookup<>(value, null); }

        static <T> Lookup<T> fail(Component error) { return new Lookup<>(null, error); }

        boolean ok() { return error == null; }
    }

    private final ActiveGroupManager manager;

    RouteTargets(ActiveGroupManager manager) {
        this.manager = manager;
    }

    Target target() {
        WaypointGroup route = manager.targetRoute();
        if (route == null) return null;
        Zone zone = manager.currentZone();
        Reason reason = route == manager.recordingTarget() ? Reason.PINNED
                : zone != null && (route == manager.lastEditedRoute(zone.id())
                        || route == manager.lastEditedRoute(Zone.routeZoneKey(zone.id()))) ? Reason.EDITED
                : Reason.FIRST;
        return new Target(route, reason);
    }

    Lookup<WaypointGroup> targetOrError() {
        WaypointGroup route = manager.targetRoute();
        if (route != null) return Lookup.of(route);
        return Lookup.fail(Component.translatable("waypointer.command.error.no_target", zoneName()));
    }

    List<WaypointGroup> listed() {
        Zone zone = manager.currentZone();
        List<WaypointGroup> out = new ArrayList<>();
        if (zone != null) {
            for (WaypointGroup route : RouteFolderListModel.orderedRoutes(manager, zone.id())) {
                if (!route.temp() && !route.runtimeOnly()) out.add(route);
            }
            String sharedZoneId = Zone.routeZoneKey(zone.id());
            if (!sharedZoneId.equals(zone.id())) {
                for (WaypointGroup route : RouteFolderListModel.orderedRoutes(manager, sharedZoneId)) {
                    if (!route.temp() && !route.runtimeOnly()) out.add(route);
                }
            }
        }
        WaypointGroup target = manager.targetRoute();
        if (target != null && !out.contains(target)) out.add(target);
        return List.copyOf(out);
    }

    int number(WaypointGroup route) {
        return listed().indexOf(route) + 1;
    }

    Lookup<WaypointGroup> route(String rawReference) {
        String reference = unquote(rawReference);
        if (RouteIds.looksLikeReference(reference)) return routeById(manager, reference);
        List<WaypointGroup> routes = listed();
        if (reference.isEmpty()) {
            return Lookup.fail(Component.translatable("waypointer.command.error.route_missing"));
        }
        if (reference.chars().allMatch(Character::isDigit)) {
            int number = parseNumber(reference);
            if (number >= 1 && number <= routes.size()) return Lookup.of(routes.get(number - 1));
            return Lookup.fail(Component.translatable(
                    "waypointer.command.error.route_number", reference, routes.size(), zoneName()));
        }
        return byName(routes, reference, WaypointGroup::name,
                count -> Component.translatable("waypointer.command.error.route_ambiguous", reference, count),
                () -> Component.translatable("waypointer.command.error.route_not_found", reference, zoneName()));
    }

    /** A route by ID such as {@code @mod1}, from any zone. A waypoint ID names its route. */
    static Lookup<WaypointGroup> routeById(ActiveGroupManager manager, String id) {
        RouteIds.Reference reference = RouteIds.parse(id);
        if (reference == null) {
            return Lookup.fail(Component.translatable("waypointer.command.error.route_id_invalid", id));
        }
        WaypointGroup route = manager.findRoute(reference);
        return route == null ? Lookup.fail(missingRoute(manager, reference, id)) : Lookup.of(route);
    }

    /** Says what an ID that names no route got wrong: the zone, or the number within it. */
    private static Component missingRoute(ActiveGroupManager manager, RouteIds.Reference typed, String id) {
        RouteIds.Reference reference = manager.resolveReference(typed);
        return switch (manager.explainMissingRoute(typed)) {
            case NO_CURRENT_ZONE -> Component.translatable("waypointer.command.error.route_id_no_zone", id);
            case ZONE_HAS_NO_ROUTES -> Component.translatable("waypointer.command.error.route_id_zone",
                    id, Zone.fromId(reference.zoneId()).displayName());
            case NUMBER_NOT_IN_ZONE -> Component.translatable("waypointer.command.error.route_id_number",
                    id, Zone.fromId(reference.zoneId()).displayName(), idsIn(manager, reference));
        };
    }

    private static String idsIn(ActiveGroupManager manager, RouteIds.Reference reference) {
        List<String> ids = new ArrayList<>();
        String shared = Zone.routeZoneKey(reference.zoneId());
        for (WaypointGroup route : manager.allGroupsList()) {
            String routeId = RouteIds.routeId(route);
            if (routeId == null) continue;
            String zone = route.zoneId();
            if (zone.equals(reference.zoneId()) || zone.equals(shared) || zone.equals(reference.literalZoneId())) {
                ids.add(routeId);
            }
        }
        int shown = Math.min(ids.size(), 6);
        String listed = String.join(", ", ids.subList(0, shown));
        return shown < ids.size() ? listed + ", +" + (ids.size() - shown) : listed;
    }

    /** A waypoint by ID such as {@code @mod1#15} or {@code @mod1#2.1}. */
    static Lookup<WaypointAt> waypointById(ActiveGroupManager manager, String id) {
        Lookup<WaypointGroup> route = routeById(manager, id);
        if (!route.ok()) return Lookup.fail(route.error());
        RouteIds.Reference reference = RouteIds.parse(id);
        if (!reference.hasWaypoint()) {
            return Lookup.fail(Component.translatable("waypointer.command.error.waypoint_id_missing", id));
        }
        Lookup<Integer> index = waypoint(route.value(), reference.waypointLabel());
        return index.ok() ? Lookup.of(new WaypointAt(route.value(), index.value())) : Lookup.fail(index.error());
    }

    Lookup<RouteFolder> folder(String rawReference) {
        String reference = unquote(rawReference);
        Zone zone = manager.currentZone();
        List<RouteFolder> folders = zone == null ? List.of() : manager.foldersForZone(zone.id());
        return byName(folders, reference, RouteFolder::name,
                count -> Component.translatable("waypointer.command.error.folder_ambiguous", reference, count),
                () -> Component.translatable("waypointer.command.error.folder_not_found", reference, zoneName()));
    }

    static Lookup<Integer> waypoint(WaypointGroup route, String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.trim();
        if (label.startsWith("#")) label = label.substring(1);
        String[] parts = label.split("\\.", -1);
        int main = parts.length >= 1 && parts.length <= 2 ? parseNumber(parts[0]) : -1;
        int child = parts.length == 2 ? parseNumber(parts[1]) : 0;
        if (main < 1 || child < 0) {
            return Lookup.fail(Component.translatable("waypointer.command.error.waypoint_label", rawLabel));
        }
        int mainIndex = indexForMain(route, main);
        if (mainIndex < 0) {
            return Lookup.fail(route.mainWaypointCount() == 0
                    ? Component.translatable("waypointer.command.error.route_empty", route.name())
                    : Component.translatable("waypointer.command.error.waypoint_range",
                            "#" + main, route.mainWaypointCount()));
        }
        if (parts.length == 1) return Lookup.of(mainIndex);
        int index = mainIndex + child;
        if (child >= 1 && index < route.childEndExclusive(mainIndex)) return Lookup.of(index);
        return Lookup.fail(Component.translatable("waypointer.command.error.subwaypoint_range",
                "#" + main, route.childEndExclusive(mainIndex) - mainIndex - 1));
    }

    static String label(WaypointGroup route, int index) {
        String label = route.displayIndexLabel(index);
        return label.startsWith("#") ? label.substring(1) : label;
    }

    private static int indexForMain(WaypointGroup route, int ordinal) {
        int count = 0;
        for (int i = 0; i < route.size(); i++) {
            if (route.isSubwaypoint(i)) continue;
            if (++count == ordinal) return i;
        }
        return -1;
    }

    Component zoneName() {
        Zone zone = manager.currentZone();
        return zone == null
                ? Component.translatable("waypointer.command.this_zone")
                : Component.literal(zone.displayName());
    }

    private static <T> Lookup<T> byName(List<T> items, String reference,
                                        Function<T, String> name,
                                        IntFunction<Component> ambiguous,
                                        Supplier<Component> missing) {
        String wanted = reference.toLowerCase(Locale.ROOT);
        List<T> exact = new ArrayList<>();
        List<T> prefix = new ArrayList<>();
        for (T item : items) {
            String candidate = name.apply(item).trim().toLowerCase(Locale.ROOT);
            if (candidate.equals(wanted)) exact.add(item);
            else if (candidate.startsWith(wanted)) prefix.add(item);
        }
        List<T> matches = exact.isEmpty() ? prefix : exact;
        if (matches.size() == 1) return Lookup.of(matches.get(0));
        return Lookup.fail(matches.isEmpty() ? missing.get() : ambiguous.apply(matches.size()));
    }

    static String unquote(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static int parseNumber(String raw) {
        try {
            return raw == null || raw.isBlank() ? -1 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
