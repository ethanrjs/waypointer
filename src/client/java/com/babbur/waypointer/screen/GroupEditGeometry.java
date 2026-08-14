package com.babbur.waypointer.screen;

import static com.babbur.waypointer.screen.GuiTokens.ROW_H;

final class GroupEditGeometry {

    static final int ROUTE_LIST_INSET = 2;

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
}
