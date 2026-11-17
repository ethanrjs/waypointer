package com.babbur.waypointer.debug;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteIds;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The route ID state for the debug report (/wp debug): which zone an ID without a zone means,
 * every zone's numbers in use and its next one, and each saved route that has no usable ID with
 * the reason. Routes are named by ID only, never by their names.
 */
public final class RouteIdDiagnostics {

    private static final int IDS_PER_ZONE = 24;

    private RouteIdDiagnostics() {}

    public record Report(List<String> summary, List<String> zones, List<String> problems) {}

    public static Report report(ActiveGroupManager manager) {
        return new Report(summary(manager), zones(manager), problems(manager));
    }

    static List<String> summary(ActiveGroupManager manager) {
        List<String> out = new ArrayList<>();
        Zone zone = manager.currentZone();
        if (zone == null) {
            out.add("Current zone: (none); IDs without a zone (@1) name nothing here");
        } else {
            String shared = Zone.routeZoneKey(zone.id());
            out.add("Current zone: " + zone.id() + ", short name " + RouteIds.shortName(zone.id())
                    + (shared.equals(zone.id()) ? "" : ", also finds " + shared + " routes")
                    + "; @1 means " + RouteIds.routePrefixForZone(zone.id()) + "1");
        }
        WaypointGroup target = manager.targetRoute();
        WaypointGroup pinned = manager.recordingTarget();
        out.add("Command target: " + describe(target)
                + (target == null ? "" : target == pinned ? " (pinned)" : " (automatic)"));
        int saved = 0;
        int withId = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() || group.runtimeOnly()) continue;
            saved++;
            if (RouteIds.routeId(group) != null) withId++;
        }
        out.add("Saved routes: " + saved + ", with an ID: " + withId);
        return out;
    }

    /** One line per zone that has saved routes or has ever handed out a number. */
    static List<String> zones(ActiveGroupManager manager) {
        Map<String, List<Integer>> numbers = new TreeMap<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() || group.runtimeOnly()) continue;
            List<Integer> zone = numbers.computeIfAbsent(group.zoneId(), id -> new ArrayList<>());
            if (group.routeNumberMatchesZone()) zone.add(group.routeNumber());
        }
        Map<String, Integer> watermarks = manager.routeNumberHighWatermarks();
        for (String zone : watermarks.keySet()) numbers.computeIfAbsent(zone, id -> new ArrayList<>());

        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : numbers.entrySet()) {
            String zone = entry.getKey();
            List<Integer> used = new ArrayList<>(entry.getValue());
            used.sort(null);
            int highest = Math.max(watermarks.getOrDefault(zone, 0), used.isEmpty() ? 0 : used.get(used.size() - 1));
            String prefix = RouteIds.routePrefixForZone(zone);
            StringBuilder line = new StringBuilder(zone).append(": ").append(used.size()).append(" in use");
            if (!used.isEmpty()) {
                line.append(" (");
                for (int i = 0; i < Math.min(used.size(), IDS_PER_ZONE); i++) {
                    if (i > 0) line.append(' ');
                    line.append(prefix).append(used.get(i));
                }
                if (used.size() > IDS_PER_ZONE) line.append(" +").append(used.size() - IDS_PER_ZONE);
                line.append(')');
            }
            line.append(", next ").append(prefix).append(highest + 1)
                    .append(", retired ").append(highest - used.size());
            out.add(line.toString());
        }
        return out;
    }

    /** Saved routes with no usable ID, and numbers two routes of one zone both claim. */
    static List<String> problems(ActiveGroupManager manager) {
        List<String> out = new ArrayList<>();
        Map<String, String> owners = new LinkedHashMap<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() || group.runtimeOnly()) continue;
            String id = RouteIds.routeId(group);
            if (id == null) {
                out.add("No ID: route " + shortInternalId(group) + " in " + group.zoneId() + ", " + reason(group));
                continue;
            }
            String other = owners.putIfAbsent(id, shortInternalId(group));
            if (other != null) {
                out.add("Duplicate " + id + ": routes " + other + " and " + shortInternalId(group));
            }
            RouteIds.Reference parsed = RouteIds.parse(id);
            if (parsed == null || RouteIds.find(manager.allGroups(), parsed) != group) {
                out.add("Unreachable " + id + ": typing it finds "
                        + (parsed == null ? "nothing" : describe(RouteIds.find(manager.allGroups(), parsed))));
            }
        }
        return out;
    }

    private static String reason(WaypointGroup group) {
        if (group.routeNumber() <= 0) return "not numbered yet";
        if (!group.routeNumberMatchesZone()) return "number " + group.routeNumber() + " belongs to its previous zone";
        return "its zone ID cannot be typed in an ID";
    }

    private static String describe(WaypointGroup group) {
        if (group == null) return "(none)";
        String id = RouteIds.routeId(group);
        if (id != null) return id;
        return group.temp() ? "temporary waypoints" : "route " + shortInternalId(group) + " (no ID)";
    }

    private static String shortInternalId(WaypointGroup group) {
        String id = group.id();
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
