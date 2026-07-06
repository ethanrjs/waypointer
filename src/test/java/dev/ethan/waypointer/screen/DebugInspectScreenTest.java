package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.debug.PerformanceStressProbe;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugInspectScreenTest {

    @Test
    void stressProbeContractIsReadOnlyUnlessOverlayIsExplicitlyInstalled() {
        ActiveGroupManager manager = new ActiveGroupManager();
        AtomicInteger dataChanges = new AtomicInteger();
        manager.addDataListener(dataChanges::incrementAndGet);

        PerformanceStressProbe.Result result = PerformanceStressProbe.runDefault();

        assertEquals(PerformanceStressProbe.DEFAULT_WAYPOINT_COUNT, result.waypointCount);
        assertEquals(0, manager.allGroups().size());
        assertEquals(0, dataChanges.get());
        assertTrue(DebugInspectScreen.readOnlyStressWarningText()
                .contains("read-only and does not add live waypoints"));
    }
}
