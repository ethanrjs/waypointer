package com.babbur.waypointer.catalog;

import java.util.List;

public record CatalogRoutePreviewData(int version, int totalPoints, boolean truncated, List<Group> groups) {

    public record Point(int x, int y, int z, int rgb, boolean subwaypoint) {}

    public record Group(boolean dungeon, List<Point> points) {
        public Group {
            points = List.copyOf(points);
        }
    }

    public CatalogRoutePreviewData {
        groups = List.copyOf(groups);
    }
}
