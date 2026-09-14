package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugInspectScreenTest {

    @Test
    void debugMasksPublisherIdsAndHandlesMissingTimingWithoutNumericNaN() {
        assertEquals("publisher12345...", DebugInspectScreen.shortPublisherId("publisher12345abcdef"));
        assertEquals("(none)", DebugInspectScreen.shortPublisherId(null));
        assertEquals("Unavailable", DebugInspectScreen.timingNumber(Double.NaN, " TPS"));
        assertEquals("19.75 TPS", DebugInspectScreen.timingNumber(19.75, " TPS"));
        assertEquals(0xFFCA7A7A, DebugInspectScreen.timingQualityColor("Stale"));
        assertEquals(0xFF8BD49C, DebugInspectScreen.timingQualityColor("Stable"));
        assertEquals(0xFFE6C07B, DebugInspectScreen.timingQualityColor("Uncertain"));
        assertEquals(0xFF8BD49C, DebugInspectScreen.socketStatusColor("subscribed"));
        assertEquals(0xFFCA7A7A, DebugInspectScreen.socketStatusColor("denied"));
        assertEquals(0xFFE6C07B, DebugInspectScreen.socketStatusColor("backoff"));
    }

    @Test
    void routeActivationCountsDistinguishDisabledOtherZoneAndFocusedRoutesWithoutNames() {
        var manager = new com.babbur.waypointer.core.ActiveGroupManager();
        manager.onZoneChanged(com.babbur.waypointer.core.Zone.fromId("hub"));
        var visible = com.babbur.waypointer.core.WaypointGroup.create("Private route title", "hub");
        var disabled = com.babbur.waypointer.core.WaypointGroup.create("Disabled", "hub");
        disabled.setEnabled(false);
        manager.add(visible);
        manager.add(disabled);
        manager.add(com.babbur.waypointer.core.WaypointGroup.create("Elsewhere", "crystal_hollows"));
        var target = manager.addTempWaypoint(4, 5, 6, "Private sender");
        manager.focusTempWaypoint(target, 0);

        var lines = DebugInspectScreen.routeActivationLines(manager);
        assertTrue(lines.contains("Current-zone routes: 3 total, 2 enabled, 1 active (includes previews)"));
        assertTrue(lines.contains("Temporary focus: true"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("Private")));
        assertEquals(4, manager.allGroups().size());
    }

    @Test
    void debugSidebarWidensWhenSpaceAllowsAndKeepsMainPaneUsable() {
        assertEquals(208, DebugInspectScreen.debugSidebarWidth(854));
        assertEquals(172, DebugInspectScreen.debugSidebarWidth(320));
        assertEquals(80, DebugInspectScreen.debugSidebarWidth(160));
        assertEquals(0, DebugInspectScreen.debugSidebarWidth(40));
    }

    @Test
    void debugSidebarUsesConciseSectionLabels() {
        assertEquals("Summary",
                DebugInspectScreen.sidebarSectionLabel("Troubleshooting Report"));
        assertEquals("Server & location",
                DebugInspectScreen.sidebarSectionLabel("Server, Player, and Location"));
        assertEquals("Tracers & paths",
                DebugInspectScreen.sidebarSectionLabel("Tracer and Dungeon Path Settings"));
        assertEquals("Active routes",
                DebugInspectScreen.sidebarSectionLabel("Active Routes and Waypoints"));
        assertEquals("Secret progress",
                DebugInspectScreen.sidebarSectionLabel("Built-in Dungeon Secret Progress"));
        assertEquals("Server & location unavailable",
                DebugInspectScreen.sidebarSectionLabel(
                        "Server, Player, and Location (Unavailable)"));
        assertEquals("Keybinds", DebugInspectScreen.sidebarSectionLabel("Keybinds"));
    }
}
