package dev.ethan.waypointer.api;

import dev.ethan.waypointer.codec.WaypointImporter;

import java.util.List;

/**
 * Result of importing routes through {@link WaypointerApi#importRoutes}.
 */
public record ImportSummary(
        WaypointImporter.Source source,
        String label,
        int groupCount,
        int waypointCount,
        List<String> groupIds) {

    public ImportSummary {
        label = label == null ? "" : label;
        groupIds = List.copyOf(groupIds);
    }
}
