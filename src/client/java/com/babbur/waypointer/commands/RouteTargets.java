package com.babbur.waypointer.commands;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.screen.RouteFolderListModel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which route a {@code /wp} command acts on, and how commands name routes, folders and waypoints.
 *
 * <p>Every route and waypoint command acts on the zone's target route: the same route new
 * waypoints and the add keybinds go to ({@link ActiveGroupManager#targetRoute()}). Routes are
 * named by their number in {@code /wp list} (the hub's order) or by name; waypoints by the
 * labels the route editor shows ({@code 3}, {@code 2.1}).
 */
final class RouteTargets {

    enum Reason { PINNED, EDITED, FIRST }

    record Target(WaypointGroup route, Reason reason) {}

    /** A looked-up value, or the message explaining why there is none. */
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
                : zone != null && route == manager.lastEditedRoute(zone.id()) ? Reason.EDITED
                : Reason.FIRST;
        return new Target(route, reason);
    }

    Lookup<WaypointGroup> targetOrError() {
        WaypointGroup route = manager.targetRoute();
        if (route != null) return Lookup.of(route);
        return Lookup.fail(Component.translatable("waypointer.command.error.no_target", zoneName()));
    }

    /** Saved routes in the current zone, in hub order, plus the target when the hub lists it elsewhere. */
    List<WaypointGroup> listed() {
        Zone zone = manager.currentZone();
        List<WaypointGroup> out = new ArrayList<>();
        if (zone != null) {
            for (WaypointGroup route : RouteFolderListModel.orderedRoutes(manager, zone.id())) {
                if (!route.temp() && !route.runtimeOnly()) out.add(route);
            }
        }
        WaypointGroup target = manager.targetRoute();
        if (target != null && !out.contains(target)) out.add(target);
        return List.copyOf(out);
    }

    /** The route's number in {@code /wp list}, or 0 when it is not listed. */
    int number(WaypointGroup route) {
        return listed().indexOf(route) + 1;
    }

    Lookup<WaypointGroup> route(String rawReference) {
        String reference = unquote(rawReference);
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

    Lookup<RouteFolder> folder(String rawReference) {
        String reference = unquote(rawReference);
        Zone zone = manager.currentZone();
        List<RouteFolder> folders = zone == null ? List.of() : manager.foldersForZone(zone.id());
        return byName(folders, reference, RouteFolder::name,
                count -> Component.translatable("waypointer.command.error.folder_ambiguous", reference, count),
                () -> Component.translatable("waypointer.command.error.folder_not_found", reference, zoneName()));
    }

    /** Resolves an editor label such as {@code 3}, {@code #3} or {@code 2.1} to a waypoint index. */
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

    /** The label the editor shows for a waypoint, without the leading {@code #}. */
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
                                        java.util.function.Function<T, String> name,
                                        java.util.function.IntFunction<Component> ambiguous,
                                        java.util.function.Supplier<Component> missing) {
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
