package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportImportSliceScreenTest {

    @Test
    void top_level_export_prefers_selected_route_over_visible_routes() {
        WaypointGroup selected = WaypointGroup.create("Selected", "hub");
        WaypointGroup selectedSecond = WaypointGroup.create("Selected 2", "hub");
        WaypointGroup firstVisible = WaypointGroup.create("First", "hub");
        WaypointGroup secondVisible = WaypointGroup.create("Second", "hub");
        List<WaypointGroup> visible = List.of(firstVisible, secondVisible);

        assertEquals(List.of(selected),
                WaypointerScreen.exportGroupsForSelection(List.of(selected), visible));
        assertEquals(List.of(selected, selectedSecond),
                WaypointerScreen.exportGroupsForSelection(
                        List.of(selected, selectedSecond), visible));
        assertEquals(visible, WaypointerScreen.exportGroupsForSelection(List.of(), visible));
        assertEquals(visible, WaypointerScreen.exportGroupsForSelection(null, visible));
        assertEquals(List.of(), WaypointerScreen.exportGroupsForSelection(null, null));
    }

    @Test
    void route_selection_helper_supports_ctrl_toggle_and_shift_range() {
        List<String> visible = List.of("a", "b", "c", "d");

        assertEquals(List.of("c"), List.copyOf(RouteSelectionPolicy.afterClick(
                visible, new LinkedHashSet<>(), null, "c", false, false)));
        assertEquals(List.of("a", "c"), List.copyOf(RouteSelectionPolicy.afterClick(
                visible, new LinkedHashSet<>(List.of("a")), "a", "c", true, false)));
        assertEquals(List.of("c"), List.copyOf(RouteSelectionPolicy.afterClick(
                visible, new LinkedHashSet<>(List.of("a", "c")), "a", "a", true, false)));
        assertEquals(List.of("b", "c", "d"), List.copyOf(RouteSelectionPolicy.afterClick(
                visible, new LinkedHashSet<>(List.of("b")), "b", "d", false, true)));
        assertEquals(List.of("c"), List.copyOf(RouteSelectionPolicy.afterClick(
                visible, new LinkedHashSet<>(), null, "c", false, true)));
    }

    @Test
    void collapsed_folder_selection_stays_scoped_to_the_selected_routes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        WaypointGroup outside = WaypointGroup.create("Outside", "the_end");
        manager.addAll(List.of(first, second, outside));
        RouteFolder folder = new RouteFolder(
                "folder", "Mining", "hub", false, 0x123456);
        manager.addFolder(folder, List.of(first.id(), second.id()));
        manager.toggleFolderCollapsed(folder.id());

        assertEquals(List.of(second), WaypointerScreen.logicalSelectedGroups(
                manager, "hub", new LinkedHashSet<>(List.of(second.id()))));
        assertEquals(List.of(first, second), WaypointerScreen.exportFolderGroups(manager, folder));
    }

    @Test
    void preview_overflow_text_uses_compact_more_lines_copy() {
        assertEquals("...0 more lines", ExportPolicy.previewOverflowText(0));
        assertEquals("...1 more line", ExportPolicy.previewOverflowText(1));
        assertEquals("...3 more lines", ExportPolicy.previewOverflowText(3));
    }

}
