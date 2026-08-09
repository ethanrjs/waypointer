package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;

import java.util.List;
import java.util.function.Consumer;

public final class PerfScenarios {

    public static final long TARGET_ACTIVE_MS = 60_000;
    public static final long SETTLE_MS = 500;
    public static final long SAMPLE_MS = 2_500;
    public static final long ADAPTIVE_SAMPLE_MS = 1_800;

    public record Scenario(String id, String label, String description,
                           Consumer<WaypointerConfig> apply,
                           PerfStressRoute.Load load,
                           boolean adaptive) {
        public Scenario(String id, String label, String description,
                        Consumer<WaypointerConfig> apply) {
            this(id, label, description, apply, loadFor(id), false);
        }
    }

    private PerfScenarios() {}

    public static String labelTranslationKey(Scenario scenario) {
        return "waypointer.settings.perf.scenario." + scenario.id() + ".label";
    }

    public static String descriptionTranslationKey(Scenario scenario) {
        return "waypointer.settings.perf.scenario." + scenario.id() + ".description";
    }

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
                new Scenario("boxes-filled", "Boxes: filled + outline",
                        "filled+outlined boxes; everything else off",
                        c -> {
                            base(c);
                            c.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
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
                        "pathological connector ceiling across a dense 3D insertion order",
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
                        "same 1,024-point load with max 12 labels and 64-block static marker distance",
                        c -> {
                            everything(c);
                            c.setMaxWaypointLabels(12);
                            c.setMaxStaticWaypointRenderDistance(64.0);
                        }),
                new Scenario("adaptive-subwaypoints", "Adaptive subwaypoint ceiling",
                        "ramps 3D subwaypoint density until sustained frame time reaches 250 ms",
                        PerfScenarios::everything,
                        loadFor("adaptive-subwaypoints"), true));
    }

    public static long adaptivePhaseMs() {
        return TARGET_ACTIVE_MS - fixedScenarioCount() * (SETTLE_MS + SAMPLE_MS);
    }

    public static int fixedScenarioCount() {
        int count = 0;
        for (Scenario scenario : all()) if (!scenario.adaptive()) count++;
        return count;
    }

    private static PerfStressRoute.Load loadFor(String id) {
        return switch (id) {
            case "hidden" -> load(PerfStressRoute.Profile.GRID_3D, 384, 0);
            case "boxes-outlined" -> load(PerfStressRoute.Profile.GRID_3D, 768, 0);
            case "boxes-filled" -> load(PerfStressRoute.Profile.GRID_3D, 768, 0);
            case "labels-names", "labels-full" ->
                    load(PerfStressRoute.Profile.DUNGEON_SECRETS, 12, 4);
            case "labels-scaled" -> load(PerfStressRoute.Profile.SUBWAYPOINTS_3D, 48, 7);
            case "tracers" -> load(PerfStressRoute.Profile.GRID_3D, 768, 0);
            case "beams-current" -> load(PerfStressRoute.Profile.DUNGEON_SECRETS, 12, 4);
            case "beams-all-textured" -> load(PerfStressRoute.Profile.GRID_3D, 1_024, 0);
            case "beams-all-flat", "route-lines" ->
                    load(PerfStressRoute.Profile.GRID_3D, 1_024, 0);
            case "everything" -> load(PerfStressRoute.Profile.SUBWAYPOINTS_3D, 128, 7);
            case "everything-budgeted" -> load(PerfStressRoute.Profile.SUBWAYPOINTS_3D, 128, 7);
            case "adaptive-subwaypoints" -> load(PerfStressRoute.Profile.SUBWAYPOINTS_3D, 64, 7);
            default -> load(PerfStressRoute.Profile.GRID_3D, 256, 0);
        };
    }

    private static PerfStressRoute.Load load(PerfStressRoute.Profile profile,
                                             int mainWaypoints, int subwaypointsPerMain) {
        return new PerfStressRoute.Load(profile, mainWaypoints, subwaypointsPerMain);
    }

    private static void everything(WaypointerConfig c) {
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
    }

    /** Scenarios start here and enable only the features they measure. */
    static void base(WaypointerConfig c) {
        c.setShowWaypointNames(false);
        c.setShowWaypointDistances(false);
        c.setShowRouteProgress(false);
        c.setShowLabelBackdrop(false);
        c.setShowLabelTextShadow(true);
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
        c.setHideWaypointsNearPlayer(false);
        c.setShowCompleted(true);
        c.setMaxWaypointLabels(0);
        c.setMaxStaticWaypointRenderDistance(0.0);
    }
}
