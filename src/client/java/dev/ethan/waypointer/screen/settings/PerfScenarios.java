package dev.ethan.waypointer.screen.settings;

import dev.ethan.waypointer.config.WaypointerConfig;

import java.util.List;
import java.util.function.Consumer;

/**
 * The scenario sweep behind the settings screen's performance stress test.
 *
 * <p>Each scenario is applied to the live config on top of a known "all
 * display features off" base, so results are order-independent and each
 * sample isolates one rendering subsystem (boxes, labels, tracers, beams,
 * route lines) before the combined worst-case scenarios at the end. The
 * first scenario hides waypoints entirely (near-hide at max radius) to
 * establish the no-waypoint baseline every other scenario is compared to.
 *
 * <p>MC-free so scenario definitions are unit-testable; the frame/CPU/GPU
 * sampling lives in {@code PerfStressTestController}.
 */
public final class PerfScenarios {

    /** Per-scenario timing: discard the settle window, keep the sample window. */
    public static final long SETTLE_MS = 400;
    public static final long SAMPLE_MS = 1_100;

    public record Scenario(String id, String label, String description,
                           Consumer<WaypointerConfig> apply) {}

    private PerfScenarios() {}

    public static List<Scenario> all() {
        return List.of(
                new Scenario("hidden", "Waypoints hidden (baseline)",
                        "near-hide on at 100 blocks; every display feature off",
                        c -> {
                            base(c);
                            c.setHideWaypointsNearPlayer(true);
                            c.setHideWaypointsNearRadius(100.0);
                        }),
                new Scenario("boxes-outlined", "Boxes: outlined",
                        "outlined boxes only; labels/tracers/beams/lines off",
                        PerfScenarios::base),
                new Scenario("boxes-filled", "Boxes: filled + outline, sharp edges",
                        "filled+outlined boxes with sharp edges; everything else off",
                        c -> {
                            base(c);
                            c.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
                            c.setSharpWaypointEdges(true);
                        }),
                new Scenario("labels-names", "Labels: names",
                        "name labels only",
                        c -> {
                            base(c);
                            c.setShowWaypointNames(true);
                        }),
                new Scenario("labels-full", "Labels: names + distances + backdrop",
                        "full label stack, unlimited label budget",
                        c -> {
                            base(c);
                            c.setShowWaypointNames(true);
                            c.setShowWaypointDistances(true);
                            c.setShowLabelBackdrop(true);
                        }),
                new Scenario("labels-scaled", "Labels: full + distance scaling + progress",
                        "full labels plus scale-with-distance and route progress text",
                        c -> {
                            base(c);
                            c.setShowWaypointNames(true);
                            c.setShowWaypointDistances(true);
                            c.setShowLabelBackdrop(true);
                            c.setScaleWaypointTextWithDistance(true);
                            c.setShowRouteProgress(true);
                        }),
                new Scenario("tracers", "Tracers",
                        "crosshair tracers on (including static routes); labels off",
                        c -> {
                            base(c);
                            c.setShowTracer(true);
                            c.setHideTracerOnStaticRoutes(false);
                        }),
                new Scenario("beams-current", "Beams: current, textured",
                        "beacon beam on the current waypoint, vanilla textures",
                        c -> {
                            base(c);
                            c.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.CURRENT);
                            c.setUseBeaconBeamTextures(true);
                        }),
                new Scenario("beams-all-textured", "Beams: all visible, textured",
                        "beacon beams on every visible waypoint, vanilla textures",
                        c -> {
                            base(c);
                            c.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
                            c.setUseBeaconBeamTextures(true);
                        }),
                new Scenario("beams-all-flat", "Beams: all visible, flat",
                        "beacon beams on every visible waypoint, flat quads",
                        c -> {
                            base(c);
                            c.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
                            c.setUseBeaconBeamTextures(false);
                        }),
                new Scenario("route-lines", "Route connector lines",
                        "connector segments between visible route waypoints",
                        c -> {
                            base(c);
                            c.setShowRouteLines(true);
                        }),
                new Scenario("everything", "Everything on (unlimited)",
                        "filled boxes, full labels, tracers, textured beams everywhere, route lines; no budgets",
                        c -> {
                            base(c);
                            c.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
                            c.setShowWaypointNames(true);
                            c.setShowWaypointDistances(true);
                            c.setShowLabelBackdrop(true);
                            c.setScaleWaypointTextWithDistance(true);
                            c.setShowRouteProgress(true);
                            c.setShowTracer(true);
                            c.setHideTracerOnStaticRoutes(false);
                            c.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
                            c.setUseBeaconBeamTextures(true);
                            c.setBeaconBeamExtendsBelowWaypoint(true);
                            c.setShowRouteLines(true);
                        }),
                new Scenario("everything-budgeted", "Everything on (budgeted)",
                        "same as above with max 12 labels and 128-block static marker distance",
                        c -> {
                            base(c);
                            c.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
                            c.setShowWaypointNames(true);
                            c.setShowWaypointDistances(true);
                            c.setShowLabelBackdrop(true);
                            c.setScaleWaypointTextWithDistance(true);
                            c.setShowRouteProgress(true);
                            c.setShowTracer(true);
                            c.setHideTracerOnStaticRoutes(false);
                            c.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
                            c.setUseBeaconBeamTextures(true);
                            c.setBeaconBeamExtendsBelowWaypoint(true);
                            c.setShowRouteLines(true);
                            c.setMaxWaypointLabels(12);
                            c.setMaxStaticWaypointRenderDistance(128.0);
                        }));
    }

    /**
     * Deterministic starting point: every display feature off, budgets
     * unlimited, default geometry. Scenarios enable exactly what they measure.
     */
    static void base(WaypointerConfig c) {
        c.setShowWaypointNames(false);
        c.setShowWaypointDistances(false);
        c.setShowRouteProgress(false);
        c.setShowLabelBackdrop(false);
        c.setScaleWaypointTextWithDistance(false);
        c.setShowTracer(false);
        c.setHideTracerOnStaticRoutes(true);
        c.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.OFF);
        c.setUseBeaconBeamTextures(true);
        c.setBeaconBeamExtendsBelowWaypoint(false);
        c.setShowRouteLines(false);
        c.setShowDungeonEntryPathToFirstWaypoint(false);
        c.setShowDungeonEntryPathToFollowingWaypoints(false);
        c.setBoxStyle(WaypointerConfig.BoxStyle.OUTLINED);
        c.setSharpWaypointEdges(false);
        c.setHideWaypointsNearPlayer(false);
        c.setShowCompleted(true);
        c.setMaxWaypointLabels(0);
        c.setMaxStaticWaypointRenderDistance(0.0);
    }
}
