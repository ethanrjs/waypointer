package dev.ethan.waypointer.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugEventLogTest {

    @AfterEach
    void clearLog() {
        DebugEventLog.clear();
    }

    @Test
    void snapshotReturnsOldestToNewestAndPlainTextIncludesClickDecision() {
        DebugEventLog.clear();

        DebugEventLog.record("GroupEditScreen", "waypoint", "#0", 0,
                "(none)", "#0", false, false, false, "waypoint-row", "select");
        DebugEventLog.record("GroupEditScreen", "waypoint", "#0", 0,
                "#0", "#0", true, false, false, "waypoint-row", "rename");

        List<DebugEventLog.Entry> events = DebugEventLog.snapshot();

        assertEquals(2, events.size());
        assertEquals("select", events.get(0).action);
        assertEquals("rename", events.get(1).action);
        assertTrue(events.get(1).plainText().contains("dbl=Y"));
        assertTrue(events.get(1).plainText().contains("action=rename"));
    }

    @Test
    void ringBufferKeepsOnlyMostRecentThirtyTwoEvents() {
        DebugEventLog.clear();

        for (int i = 0; i < 35; i++) {
            DebugEventLog.record("WaypointerScreen", "route", "route-" + i, i,
                    "(none)", "route-" + i, false, false, false, "route-row", "select");
        }

        List<DebugEventLog.Entry> events = DebugEventLog.snapshot();

        assertEquals(32, events.size());
        assertEquals(3, events.get(0).sequence);
        assertEquals(34, events.get(31).sequence);
    }
}
