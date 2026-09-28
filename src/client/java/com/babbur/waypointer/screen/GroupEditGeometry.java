package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import static com.babbur.waypointer.screen.GuiTokens.ROW_H;

final class GroupEditGeometry {

    static final int ROUTE_LIST_INSET = 2;
    static final int WAYPOINT_SWATCH_SIZE = 14;
    static final int WAYPOINT_SWATCH_TOP = 4;
    static final int WAYPOINT_SUBWAYPOINT_INDENT = 16;
    static final int WAYPOINT_SWATCH_TEXT_GAP = 6;
    static final int WAYPOINT_ORDINAL_GAP = GuiTokens.GAP;

    private GroupEditGeometry() {}

    static int contentBottom(int screenHeight, int footerSpace) {
        return screenHeight - footerSpace - GuiTokens.GAP_SECTION;
    }

    static int maxSidebarScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - Math.max(0, viewportHeight));
    }

    static int waypointRowPitch() {
        return ROW_H;
    }

    static int routeListMaxScroll(int itemCount, int viewportHeight) {
        long contentHeight = (long) Math.max(0, itemCount) * waypointRowPitch();
        int usableHeight = Math.max(0, viewportHeight - ROUTE_LIST_INSET * 2);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, contentHeight - usableHeight));
    }

    static int routeScrollOffsetForPointer(double pointerY, int dragOffset,
                                           int trackTop, int trackBottom,
                                           int thumbHeight, int maxScroll) {
        int travel = Math.max(0, trackBottom - trackTop - thumbHeight);
        if (travel == 0 || maxScroll <= 0) return 0;
        double thumbTop = Math.max(trackTop,
                Math.min(trackTop + travel, pointerY - dragOffset));
        return (int) Math.round((thumbTop - trackTop) * maxScroll / travel);
    }

    static int sidebarScrollOffsetToReveal(int currentOffset, int widgetHomeY, int widgetHeight,
                                           int viewportTop, int viewportBottom, int maxScroll) {
        int result = currentOffset;
        if (widgetHomeY - currentOffset < viewportTop) {
            result = widgetHomeY - viewportTop;
        } else if (widgetHomeY + widgetHeight - currentOffset > viewportBottom) {
            result = widgetHomeY + widgetHeight - viewportBottom;
        }
        return Math.max(0, Math.min(maxScroll, result));
    }

    static int coordinateAfterScroll(int value, double verticalScroll) {
        if (verticalScroll > 0.0) return value == Integer.MAX_VALUE ? value : value + 1;
        if (verticalScroll < 0.0) return value == Integer.MIN_VALUE ? value : value - 1;
        return value;
    }

    static Integer parseCoordinate(String raw) {
        if (raw == null || raw.strip().isEmpty()) return null;
        String stripped = raw.strip();
        StringBuilder normalized = new StringBuilder(stripped.length());
        for (int offset = 0; offset < stripped.length();) {
            int codePoint = stripped.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(codePoint)) return null;
            if (normalized.isEmpty() && (codePoint == '-' || codePoint == 0x2212)) {
                normalized.append('-');
                continue;
            }
            if (normalized.isEmpty() && (codePoint == '+' || codePoint == 0xFF0B)) {
                normalized.append('+');
                continue;
            }
            int digit = Character.digit(codePoint, 10);
            if (digit < 0) return null;
            normalized.append((char) ('0' + digit));
        }
        if (normalized.isEmpty()
                || "-".contentEquals(normalized)
                || "+".contentEquals(normalized)) return null;
        try {
            return Integer.parseInt(normalized.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String coordinateError(int axis, String raw) {
        String axisLabel = axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
        return raw == null || raw.trim().isEmpty()
                ? axisLabel + " coordinate is required."
                : axisLabel + " coordinate must be a whole number.";
    }

    static int labelEditorWidth(int editorX, int textRightX) {
        return Math.max(0, textRightX - editorX);
    }

    static int waypointSwatchX(int rowLeft, boolean subwaypoint) {
        return rowLeft + GuiTokens.GAP + 2 + (subwaypoint ? WAYPOINT_SUBWAYPOINT_INDENT : 0);
    }

    static boolean isOverWaypointSwatch(double x, double y, int rowLeft, int rowTop,
                                        boolean subwaypoint) {
        int swatchX = waypointSwatchX(rowLeft, subwaypoint);
        int swatchY = rowTop + WAYPOINT_SWATCH_TOP;
        return x >= swatchX && x < swatchX + WAYPOINT_SWATCH_SIZE
                && y >= swatchY && y < swatchY + WAYPOINT_SWATCH_SIZE;
    }

    /**
     * Horizontal layout of one waypoint row: color swatch, then the {@code #n}
     * label left-aligned in a column as wide as the widest label on the same
     * level, then the coordinates. Rows on one level therefore start their
     * coordinates at the same pixel.
     */
    static WaypointRowColumns waypointRowColumns(int rowLeft, boolean subwaypoint,
                                                 WaypointOrdinalColumn ordinals) {
        int swatchX = waypointSwatchX(rowLeft, subwaypoint);
        int ordinalX = swatchX + WAYPOINT_SWATCH_SIZE + WAYPOINT_SWATCH_TEXT_GAP;
        int ordinalWidth = ordinals == null ? 0 : ordinals.width(subwaypoint);
        return new WaypointRowColumns(swatchX, ordinalX,
                ordinalX + ordinalWidth + WAYPOINT_ORDINAL_GAP);
    }

    /** Where a waypoint's name (or its rename box) starts after its coordinates. */
    static int waypointNameX(int coordinatesX, int coordinatesWidth) {
        return coordinatesX + Math.max(0, coordinatesWidth) + GuiTokens.GAP;
    }

    /**
     * Measures every row's ordinal label once. Main waypoints and subwaypoints
     * each get their own column width because subwaypoint rows are indented.
     */
    static WaypointOrdinalColumn waypointOrdinalColumn(WaypointGroup group,
                                                       UnaryOperator<String> localize,
                                                       ToIntFunction<String> textWidth) {
        if (group == null) return WaypointOrdinalColumn.EMPTY;
        List<String> rawLabels = group.displayIndexLabels();
        List<String> labels = new ArrayList<>(rawLabels.size());
        BitSet subwaypoints = new BitSet(rawLabels.size());
        int mainWidth = 0;
        int subwaypointWidth = 0;
        for (int i = 0; i < rawLabels.size(); i++) {
            String label = localize.apply(rawLabels.get(i));
            labels.add(label);
            int width = Math.max(0, textWidth.applyAsInt(label));
            if (group.isSubwaypoint(i)) {
                subwaypoints.set(i);
                subwaypointWidth = Math.max(subwaypointWidth, width);
            } else {
                mainWidth = Math.max(mainWidth, width);
            }
        }
        return new WaypointOrdinalColumn(List.copyOf(labels), subwaypoints,
                mainWidth, subwaypointWidth);
    }

    record WaypointRowColumns(int swatchX, int ordinalX, int coordinatesX) {
    }

    /** Localized ordinal labels for one route plus the column width of each level. */
    record WaypointOrdinalColumn(List<String> labels, BitSet subwaypoints,
                                 int mainWidth, int subwaypointWidth) {

        static final WaypointOrdinalColumn EMPTY =
                new WaypointOrdinalColumn(List.of(), new BitSet(), 0, 0);

        int width(boolean subwaypoint) {
            return subwaypoint ? subwaypointWidth : mainWidth;
        }

        String label(int index) {
            return index >= 0 && index < labels.size() ? labels.get(index) : "";
        }

        /** True while the route still has the same rows at the same levels. */
        boolean matchesStructureOf(WaypointGroup group) {
            if (group == null || group.size() != labels.size()) return false;
            for (int i = 0; i < labels.size(); i++) {
                if (group.isSubwaypoint(i) != subwaypoints.get(i)) return false;
            }
            return true;
        }
    }
}
