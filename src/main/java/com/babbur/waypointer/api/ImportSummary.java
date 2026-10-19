package com.babbur.waypointer.api;

import java.util.List;
import java.util.Objects;

public record ImportSummary(
        ImportSource source,
        String label,
        int groupCount,
        int waypointCount,
        List<String> groupIds) {

    public ImportSummary {
        source = Objects.requireNonNull(source, "source");
        label = label == null ? "" : label;
        groupIds = List.copyOf(groupIds);
    }
}
