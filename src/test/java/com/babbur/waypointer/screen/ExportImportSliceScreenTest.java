package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(List.of("c"), List.copyOf(WaypointerScreen.routeSelectionAfterClick(
                visible, new LinkedHashSet<>(), null, "c", false, false)));
        assertEquals(List.of("a", "c"), List.copyOf(WaypointerScreen.routeSelectionAfterClick(
                visible, new LinkedHashSet<>(List.of("a")), "a", "c", true, false)));
        assertEquals(List.of("c"), List.copyOf(WaypointerScreen.routeSelectionAfterClick(
                visible, new LinkedHashSet<>(List.of("a", "c")), "a", "a", true, false)));
        assertEquals(List.of("b", "c", "d"), List.copyOf(WaypointerScreen.routeSelectionAfterClick(
                visible, new LinkedHashSet<>(List.of("b")), "b", "d", false, true)));
        assertEquals(List.of("c"), List.copyOf(WaypointerScreen.routeSelectionAfterClick(
                visible, new LinkedHashSet<>(), null, "c", false, true)));
    }

    @Test
    void preview_overflow_text_uses_compact_more_lines_copy() {
        assertEquals("...0 more lines", ExportScreen.previewOverflowText(0));
        assertEquals("...1 more line", ExportScreen.previewOverflowText(1));
        assertEquals("...3 more lines", ExportScreen.previewOverflowText(3));
    }

    @Test
    void export_defaults_include_waypoint_flags_for_all_route_shapes() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup normal = WaypointGroup.create("Normal", "hub");
        normal.add(Waypoint.at(0, 64, 0));

        WaypointGroup subway = WaypointGroup.create("Subway", "hub");
        subway.add(Waypoint.at(0, 64, 0));
        subway.add(Waypoint.at(1, 64, 1));
        assertTrue(subway.toggleSubwaypoint(1));

        WaypointCodec.Options normalOptions =
                ExportScreen.builderFromConfig(config, List.of(normal)).build();
        WaypointCodec.Options subwayOptions =
                ExportScreen.builderFromConfig(config, List.of(subway)).build();

        assertTrue(normalOptions.includeWaypointFlags);
        assertTrue(subwayOptions.includeWaypointFlags);
    }
}
