package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugInspectScreenTest {

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
