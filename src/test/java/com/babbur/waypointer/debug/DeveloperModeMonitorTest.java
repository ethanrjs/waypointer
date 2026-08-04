package com.babbur.waypointer.debug;

import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonDetectionConfidence;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperModeMonitorTest {

    @Test
    void confirmedCatalogRoomHasNoAutomaticDetectionProblems() {
        DungeonRoom room = room("red-green", DungeonDetectionConfidence.CORE_CONFIRMED,
                List.of(DungeonRoom.packSegment(-185, -185)));

        assertTrue(DeveloperModeMonitor.detectionProblems(room).isEmpty());
    }

    @Test
    void coreShapeMismatchIsReportedAsAnAnomaly() {
        DungeonRoom room = room("red-green", DungeonDetectionConfidence.CORE_MATCHED,
                List.of(DungeonRoom.packSegment(-185, -185)));

        assertEquals(List.of("Core identity matched but the detected room shape did not"),
                DeveloperModeMonitor.detectionProblems(room));
    }

    @Test
    void unmatchedLowConfidenceRoomWithoutSegmentsReportsEveryMechanicalProblem() {
        DungeonRoom room = room("", DungeonDetectionConfidence.MAP_FALLBACK, List.of());

        List<String> problems = DeveloperModeMonitor.detectionProblems(room);

        assertEquals(3, problems.size());
        assertTrue(problems.contains("Detected room has no catalog identity"));
        assertTrue(problems.contains("Detected room has no physical segments"));
        assertTrue(problems.contains("Detected room has low confidence: MAP_FALLBACK"));
    }

    @Test
    void overlappingPhysicalSegmentsKeepMetadataCorrectionsInTheSameVisit() {
        long first = DungeonRoom.packSegment(-185, -185);
        long second = DungeonRoom.packSegment(-153, -185);
        long different = DungeonRoom.packSegment(-121, -185);

        assertTrue(DeveloperModeMonitor.intersects(List.of(first, second), List.of(second)));
        assertFalse(DeveloperModeMonitor.intersects(List.of(first, second), List.of(different)));
    }

    @Test
    void unresolvedGraceWaitsBeyondTheTrackersSeventyFiveTickRetry() {
        assertTrue(DeveloperModeMonitor.UNRESOLVED_GRACE_TICKS > 75);
        assertEquals(0, DeveloperModeMonitor.UNRESOLVED_GRACE_TICKS
                % DeveloperModeMonitor.SAMPLE_INTERVAL_TICKS);
    }

    @Test
    void activeRouteHudIncludesExactCurrentWaypointDiagnostics() {
        WaypointGroup group = new WaypointGroup("12345678-aaaa", "Secrets\nRoute", "dungeon_test");
        group.setDefaultRadius(4.5);
        group.setRuntimeOnly(true);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(10, 64, -20, "Lever", 0x12ABEF,
                Waypoint.FLAG_SKIP_ON_INTERACT, 0.0));

        List<String> lines = DeveloperModeMonitor.activeRouteHudLines(group);

        assertEquals("Route: Secrets Route [12345678] | SEQUENCE runtime | index=0/1", lines.get(0));
        assertEquals("  Target #1 \"Lever\" | xyz=10,64,-20 p16=168,1032,-312"
                + " | r=4.50 color=#12ABEF flags=0x00000400", lines.get(1));
    }

    private static DungeonRoom room(String id,
                                    DungeonDetectionConfidence confidence,
                                    List<Long> segments) {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                -185,
                -185,
                segments,
                id,
                id.isBlank() ? "" : "Red-Green",
                confidence);
    }
}
