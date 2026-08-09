package com.babbur.waypointer.screen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RouteSelectionPolicy {

    private RouteSelectionPolicy() {
    }

    static LinkedHashSet<String> afterClick(List<String> visibleIds,
                                            Set<String> previousIds,
                                            String anchorId,
                                            String clickedId,
                                            boolean controlDown,
                                            boolean shiftDown) {
        LinkedHashSet<String> orderedVisibleIds = normalizedIds(visibleIds);
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (clickedId == null || clickedId.isBlank()) return next;
        if (!orderedVisibleIds.contains(clickedId)) {
            next.add(clickedId);
            return next;
        }

        if (shiftDown) {
            List<String> ordered = new ArrayList<>(orderedVisibleIds);
            String rangeAnchor = orderedVisibleIds.contains(anchorId) ? anchorId : clickedId;
            int anchorIndex = ordered.indexOf(rangeAnchor);
            int clickedIndex = ordered.indexOf(clickedId);
            int start = Math.min(anchorIndex, clickedIndex);
            int end = Math.max(anchorIndex, clickedIndex);
            for (int i = start; i <= end; i++) next.add(ordered.get(i));
            return next;
        }

        if (controlDown) {
            if (previousIds != null) {
                for (String id : orderedVisibleIds) {
                    if (previousIds.contains(id)) next.add(id);
                }
            }
            if (!next.remove(clickedId)) next.add(clickedId);
            return next;
        }

        next.add(clickedId);
        return next;
    }

    static String firstVisibleSelection(List<String> visibleIds, Set<String> selectedIds) {
        if (visibleIds == null || selectedIds == null) return null;
        for (String id : visibleIds) {
            if (selectedIds.contains(id)) return id;
        }
        return null;
    }

    private static LinkedHashSet<String> normalizedIds(List<String> ids) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (ids == null) return normalized;
        for (String id : ids) {
            if (id != null) normalized.add(id);
        }
        return normalized;
    }
}
