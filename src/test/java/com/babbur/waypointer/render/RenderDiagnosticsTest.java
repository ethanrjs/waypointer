package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderDiagnosticsTest {

    @BeforeEach
    void enableDetailedCapture() {
        RenderDiagnostics.setDetailedCaptureEnabled(true);
    }

    @AfterEach
    void disableDetailedCapture() {
        RenderDiagnostics.setDetailedCaptureEnabled(false);
    }

    @Test
    void dungeonPathRequiresTwoPointsBeforeItCanReplaceStraightTracer() {
        assertFalse(WaypointRenderer.isDrawableDungeonEntryPath(List.of()));
        assertFalse(WaypointRenderer.isDrawableDungeonEntryPath(List.of(new Vec3(0.0, 64.0, 0.0))));
        assertTrue(WaypointRenderer.isDrawableDungeonEntryPath(List.of(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(5.0, 64.0, 5.0))));

        assertFalse(RenderDiagnostics.shouldSuppressStraightTracer(false, false));
        assertTrue(RenderDiagnostics.shouldSuppressStraightTracer(false, true));
    }

    @Test
    void irisHudFallbackNeverSuppressesStraightTracerForWorldDungeonPath() {
        assertFalse(RenderDiagnostics.shouldSuppressStraightTracer(true, true));
        assertFalse(RenderDiagnostics.shouldSuppressStraightTracer(true, false));
    }

    @Test
    void retainedPathPreparationSuppressesTracerBeforeActualSubmission() {
        WaypointGroup group = WaypointGroup.create("Prepared", "altar");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        RenderDiagnostics.beginFrame(List.of(group), new WaypointerConfig(), false);

        RenderDiagnostics.recordPreparedDungeonPath(group, true);

        assertTrue(RenderDiagnostics.shouldSuppressStraightTracer(group));
        RenderDiagnostics.recordStraightTracerSuppressed(group);
        assertFalse(RenderDiagnostics.snapshot().groups().getFirst().dungeonPathSubmitted());
        assertEquals("dungeon path prepared; straight tracer suppressed",
                RenderDiagnostics.snapshot().groups().getFirst().finalOutcome());

        RenderDiagnostics.recordDungeonPathSubmission(group, true);

        assertTrue(RenderDiagnostics.shouldSuppressStraightTracer(group));
        assertTrue(RenderDiagnostics.snapshot().groups().getFirst().dungeonPathSubmitted());
    }

    @Test
    void failedRetainedPathSubmissionReleasesPreparedTracerSuppression() {
        WaypointGroup group = WaypointGroup.create("Failed", "altar");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        RenderDiagnostics.beginFrame(List.of(group), new WaypointerConfig(), false);

        RenderDiagnostics.recordPreparedDungeonPath(group, true);
        assertTrue(RenderDiagnostics.shouldSuppressStraightTracer(group));

        RenderDiagnostics.recordDungeonPathSubmission(group, false);

        assertFalse(RenderDiagnostics.shouldSuppressStraightTracer(group));
        assertFalse(RenderDiagnostics.snapshot().groups().getFirst().dungeonPathSubmitted());
        assertEquals("nothing submitted: dungeon path submission failed",
                RenderDiagnostics.snapshot().groups().getFirst().finalOutcome());
    }

    @Test
    void preparedPathDoesNotSurviveTheNextFrame() {
        WaypointGroup group = WaypointGroup.create("Stale", "altar");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointerConfig config = new WaypointerConfig();
        RenderDiagnostics.beginFrame(List.of(group), config, false);
        RenderDiagnostics.recordPreparedDungeonPath(group, true);
        assertTrue(RenderDiagnostics.shouldSuppressStraightTracer(group));

        RenderDiagnostics.beginFrame(List.of(group), config, false);

        assertFalse(RenderDiagnostics.shouldSuppressStraightTracer(group));
        assertFalse(RenderDiagnostics.snapshot().groups().getFirst().dungeonPathSubmitted());
    }

    @Test
    void fallbackStateWorksWithDetailedCaptureDisabled() {
        WaypointGroup group = WaypointGroup.create("Minimal", "altar");
        RenderDiagnostics.setDetailedCaptureEnabled(false);
        RenderDiagnostics.beginFrame(List.of(group), new WaypointerConfig(), false);

        RenderDiagnostics.recordDungeonPathSubmission(group, true);

        assertTrue(RenderDiagnostics.shouldSuppressStraightTracer(group));
        assertTrue(RenderDiagnostics.snapshot().groups().isEmpty());
        assertEquals(0L, RenderDiagnostics.snapshot().updatedAtEpochMillis());
    }

    @Test
    void cachedEmptyPathFallsThroughToStraightTracerAndReportsFinalDecision() {
        WaypointGroup group = WaypointGroup.create("Empty Path", "altar");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.add(new Waypoint(5, 64, 5, "Target", Waypoint.DEFAULT_COLOR, 0, 0.0));
        RenderDiagnostics.beginFrame(List.of(group), new WaypointerConfig(), false);

        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(5, 64, 5);
        GroundPathfinder.Diagnostics diagnostics = new GroundPathfinder.Diagnostics(
                start,
                goal,
                start,
                goal,
                GroundPathfinder.FailureReason.NO_ROUTE_WITHIN_BOUNDS,
                37,
                8_000,
                2_500_000L);
        RenderDiagnostics.recordPathLookup(
                group,
                new GroundPathfinder.PathResult(List.of(), diagnostics),
                true,
                125_000_000L);
        RenderDiagnostics.recordPreparedDungeonPath(group, false);
        RenderDiagnostics.recordDungeonPathSubmission(group, false);

        assertFalse(RenderDiagnostics.shouldSuppressStraightTracer(group));
        RenderDiagnostics.recordStraightTracerSubmitted(group);

        RenderDiagnostics.GroupSnapshot snapshot = RenderDiagnostics.snapshot().groups().getFirst();
        assertEquals("hit", snapshot.path().cacheStatus());
        assertEquals(125.0, snapshot.path().cacheAgeMillis());
        assertEquals("empty", snapshot.path().result());
        assertEquals("no route within search bounds", snapshot.path().reason());
        assertEquals(37, snapshot.path().expansions());
        assertFalse(snapshot.dungeonPathSubmitted());
        assertFalse(snapshot.straightTracerSuppressed());
        assertTrue(snapshot.straightTracerSubmitted());
        assertEquals("straight tracer submitted", snapshot.finalOutcome());
    }
}
