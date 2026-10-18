package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;

import java.util.ArrayList;
import java.util.List;

public enum DungeonWaypointType {
    SECRET("Secret", "S", Waypoint.FLAG_DUNGEON_SECRET),
    ETHERWARP("Etherwarp", "E", Waypoint.FLAG_DUNGEON_ETHERWARP),
    DUNGEONBREAKER("Dungeonbreaker", "D", Waypoint.FLAG_DUNGEON_DUNGEONBREAKER),
    SUPERBOOM("Superboom", "T", Waypoint.FLAG_DUNGEON_SUPERBOOM),
    PEARL("Pearl", "P", Waypoint.FLAG_DUNGEON_PEARL),
    PEARL_TARGET("Pearl target", "PT", Waypoint.FLAG_DUNGEON_PEARL_TARGET),
    ITEM("Item", "I", Waypoint.FLAG_DUNGEON_ITEM),
    BAT("Bat", "B", Waypoint.FLAG_DUNGEON_BAT);

    private final String label;
    private final String fallbackGlyph;
    private final int flag;

    DungeonWaypointType(String label, String fallbackGlyph, int flag) {
        this.label = label;
        this.fallbackGlyph = fallbackGlyph;
        this.flag = flag;
    }

    public String label() {
        return label;
    }

    public String fallbackGlyph() {
        return fallbackGlyph;
    }

    public int flag() {
        return flag;
    }

    public boolean isSet(Waypoint waypoint) {
        return waypoint != null && waypoint.hasFlag(flag);
    }

    public Waypoint selectExclusive(Waypoint waypoint) {
        if (waypoint == null) return null;
        int flags = waypoint.flags() & ~Waypoint.DUNGEON_METADATA_FLAGS;
        if (!isSet(waypoint)) flags |= flag;
        return waypoint.withFlags(flags);
    }

    public int applyExclusive(int flags) {
        return (flags & ~Waypoint.DUNGEON_METADATA_FLAGS) | flag;
    }

    public static DungeonWaypointType firstType(int flags) {
        for (DungeonWaypointType type : values()) {
            if ((flags & type.flag) != 0) return type;
        }
        return null;
    }

    public static List<DungeonWaypointType> activeTypes(Waypoint waypoint) {
        if (waypoint == null) return List.of();
        List<DungeonWaypointType> active = new ArrayList<>();
        for (DungeonWaypointType type : values()) {
            if (type.isSet(waypoint)) active.add(type);
        }
        return List.copyOf(active);
    }

    public static String activeSummary(Waypoint waypoint) {
        return activeTypes(waypoint).stream()
                .map(DungeonWaypointType::label)
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }
}
