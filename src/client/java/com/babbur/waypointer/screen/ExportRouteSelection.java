package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ExportRouteSelection {

    private final boolean[] selected;

    ExportRouteSelection(int routeCount) {
        selected = new boolean[Math.max(0, routeCount)];
        Arrays.fill(selected, true);
    }

    static ExportRouteSelection of(boolean... selected) {
        return new ExportRouteSelection(selected == null ? new boolean[0] : selected.clone());
    }

    private ExportRouteSelection(boolean[] selected) {
        this.selected = selected;
    }

    boolean isSelected(int index) {
        return index >= 0 && index < selected.length && selected[index];
    }

    boolean toggle(int index) {
        if (index < 0 || index >= selected.length) return false;
        if (selected[index] && count() == 1) return false;
        selected[index] = !selected[index];
        return true;
    }

    void selectAll() {
        Arrays.fill(selected, true);
    }

    int count() {
        int count = 0;
        for (boolean routeSelected : selected) {
            if (routeSelected) count++;
        }
        return count;
    }

    boolean hasExcludedRoutes() {
        for (boolean routeSelected : selected) {
            if (!routeSelected) return true;
        }
        return false;
    }

    int firstSelectedIndex() {
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) return i;
        }
        return selected.length == 0 ? -1 : 0;
    }

    int navigate(int current, int delta) {
        List<Integer> indexes = selectedIndexes();
        if (indexes.isEmpty()) return -1;
        int ordinal = indexes.indexOf(current);
        int start = ordinal < 0 ? 0 : ordinal;
        return indexes.get(Math.floorMod(start + delta, indexes.size()));
    }

    int replacementFor(int current) {
        if (isSelected(current)) return current;
        int safeCurrent = Math.max(0, current);
        for (int i = safeCurrent + 1; i < selected.length; i++) {
            if (selected[i]) return i;
        }
        for (int i = Math.min(safeCurrent - 1, selected.length - 1); i >= 0; i--) {
            if (selected[i]) return i;
        }
        return -1;
    }

    String counter(int current) {
        List<Integer> indexes = selectedIndexes();
        int ordinal = indexes.indexOf(current);
        return indexes.size() <= 1 || ordinal < 0
                ? ""
                : (ordinal + 1) + " of " + indexes.size();
    }

    List<WaypointGroup> selectedGroups(List<WaypointGroup> groups) {
        if (groups == null) return List.of();
        if (groups.size() <= 1) return groups;
        List<WaypointGroup> result = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            if (isSelected(i)) result.add(groups.get(i));
        }
        return result;
    }

    boolean[] snapshot() {
        return selected.clone();
    }

    private List<Integer> selectedIndexes() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) result.add(i);
        }
        return result;
    }
}
