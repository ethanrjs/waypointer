package com.babbur.waypointer.dungeon;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DungeonTriggerSelection {

    private static final int MIN_CHAT_TRIGGER_CHARS = 4;

    private DungeonTriggerSelection() {}

    static boolean itemNameMatchesSuperboom(String itemName) {
        String normalized = normalizePhrase(itemName);
        return "superboom tnt".equals(normalized)
                || "superboom".equals(normalized);
    }

    static boolean chatMessageMatchesWaypoint(String message, DungeonWaypoint waypoint) {
        if (waypoint == null || waypoint.trigger() != DungeonWaypointTrigger.CHAT_MESSAGE) {
            return false;
        }

        String rawNeedle = waypoint.hasName()
                ? waypoint.name()
                : chatCategoryNeedle(waypoint.category());
        String needle = normalizePhrase(rawNeedle);
        if (needle.length() < MIN_CHAT_TRIGGER_CHARS) return false;

        String haystack = normalizePhrase(message);
        if (haystack.isEmpty()) return false;
        return containsNormalizedPhrase(haystack, needle);
    }

    static DungeonWaypoint nearestEntityTrigger(
            DungeonRoom room,
            List<DungeonWaypoint> waypoints,
            DungeonWaypointTrigger trigger,
            double entityX,
            double entityY,
            double entityZ,
            double maxDistanceSq) {
        if (trigger == null) return null;
        return nearestEntityTrigger(room, waypoints, EnumSet.of(trigger),
                entityX, entityY, entityZ, maxDistanceSq);
    }

    static DungeonWaypoint nearestEntityTrigger(
            DungeonRoom room,
            List<DungeonWaypoint> waypoints,
            Set<DungeonWaypointTrigger> triggers,
            double entityX,
            double entityY,
            double entityZ,
            double maxDistanceSq) {
        if (room == null || waypoints == null || triggers == null || triggers.isEmpty()) return null;

        DungeonWaypoint nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint == null || waypoint.secretIndex() <= 0) continue;
            if (!triggers.contains(waypoint.trigger())) continue;

            double distanceSq = distanceToWaypointSq(room, waypoint, entityX, entityY, entityZ);
            if (distanceSq <= maxDistanceSq && distanceSq < nearestDistanceSq) {
                nearest = waypoint;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    private static String chatCategoryNeedle(DungeonSecretCategory category) {
        if (category == null || category == DungeonSecretCategory.DEFAULT) return "";
        return category.id;
    }

    private static boolean containsNormalizedPhrase(String haystack, String needle) {
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    private static String normalizePhrase(String raw) {
        if (raw == null || raw.isBlank()) return "";

        StringBuilder normalized = new StringBuilder(raw.length());
        boolean previousWasSpace = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00A7' && i + 1 < raw.length()) {
                i++;
                continue;
            }

            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
                previousWasSpace = false;
            } else if (!previousWasSpace) {
                normalized.append(' ');
                previousWasSpace = true;
            }
        }

        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') {
            normalized.setLength(length - 1);
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    private static double distanceToWaypointSq(
            DungeonRoom room,
            DungeonWaypoint waypoint,
            double x,
            double y,
            double z) {
        int[] world = DungeonMapMath.relativeToActual(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                waypoint.x(),
                waypoint.y(),
                waypoint.z());
        double dx = x - (world[0] + 0.5);
        double dy = y - (world[1] + 0.5);
        double dz = z - (world[2] + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
