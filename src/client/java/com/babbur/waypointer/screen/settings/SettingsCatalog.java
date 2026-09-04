package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.babbur.waypointer.screen.settings.Setting.Store.DUNGEON;
import static com.babbur.waypointer.screen.settings.Setting.Store.MAIN;

public final class SettingsCatalog {

    public record Group(String id, String label, String parentSettingId,
                        Setting.EnabledWhen childrenVisibleWhen, List<Setting> settings) {

        public static Group plain(Setting... settings) {
            return new Group(null, null, null, null, List.of(settings));
        }

        public static Group plain(String id, String label, Setting... settings) {
            return new Group(id, label, null, null, List.of(settings));
        }

        public static Group parented(Setting.EnabledWhen childrenVisibleWhen, Setting... settings) {
            return new Group(null, null, settings[0].id(), childrenVisibleWhen, List.of(settings));
        }

        public static Group parented(String id, String label, Setting.EnabledWhen childrenVisibleWhen,
                                     Setting... settings) {
            return new Group(id, label, settings[0].id(), childrenVisibleWhen, List.of(settings));
        }
    }

    public record Category(String id, String label, String masterSettingId,
                           Setting.EnabledWhen bodyVisibleWhen, List<Group> groups) {

        public static Category of(String id, String label, Group... groups) {
            return new Category(id, label, null, null, List.of(groups));
        }
    }

    public static final String ACTION_CONFIG_CODE = "action.configCode";
    public static final String ACTION_PRESETS = "action.presets";
    public static final String ACTION_DISABLE_ALL = "action.disableAll";
    public static final String ACTION_RESET_DEFAULTS = "action.resetDefaults";
    public static final String ACTION_PERF_TEST = "action.perfTest";
    public static final String ACTION_WAYPOINT_PAINT = "action.waypointPaint";

    private static final Setting.EnabledWhen ANY_LABEL_TEXT = (c, d) ->
            c.showWaypointNames() || c.showWaypointDistances() || c.showRouteProgress();

    private static final List<Category> CATEGORIES = buildCategories();
    private static final List<Setting> ALL_SETTINGS = flatten(CATEGORIES);

    private SettingsCatalog() {}

    public static List<Category> categories() {
        return CATEGORIES;
    }

    public static List<Setting> allSettings() {
        return ALL_SETTINGS;
    }

    public static Setting byId(String id) {
        for (Setting setting : ALL_SETTINGS) {
            if (setting.id().equals(id)) return setting;
        }
        return null;
    }

    public static String categoryTranslationKey(Category category) {
        return "waypointer.settings.category." + category.id();
    }

    public static String groupTranslationKey(Category category, Group group) {
        if (category == null || group == null || group.id() == null) return null;
        return "waypointer.settings.group." + category.id() + "." + group.id();
    }

    public static int countChangedSettings(WaypointerConfig live, WaypointerConfig decoded) {
        if (live == null || decoded == null) return 0;
        int changed = 0;
        for (Setting setting : ALL_SETTINGS) {
            if (setting.store() != MAIN) continue;
            if (!Objects.equals(setting.get(live, null), setting.get(decoded, null))) changed++;
        }
        return changed;
    }

    public static int countChangedSettings(
            WaypointerConfig liveMain,
            DungeonConfig liveDungeon,
            WaypointerConfig nextMain,
            DungeonConfig nextDungeon) {
        if (liveMain == null || liveDungeon == null || nextMain == null || nextDungeon == null) {
            return 0;
        }
        int changed = 0;
        for (Setting setting : ALL_SETTINGS) {
            Object live = setting.get(liveMain, liveDungeon);
            Object next = setting.get(nextMain, nextDungeon);
            if (!Objects.equals(live, next)) changed++;
        }
        return changed;
    }

    private static List<Setting> flatten(List<Category> categories) {
        List<Setting> out = new ArrayList<>();
        for (Category category : categories) {
            for (Group group : category.groups()) {
                out.addAll(group.settings());
            }
        }
        return List.copyOf(out);
    }

    private static double dbl(Object v) {
        return ((Number) v).doubleValue();
    }

    private static int rgb(Object v) {
        return ((Number) v).intValue();
    }

    private static List<Category> buildCategories() {
        List<Category> out = new ArrayList<>();
        out.add(waypoints());
        out.add(appearance());
        out.add(routes());
        out.add(dungeons());
        out.add(mining());
        out.add(chat());
        out.add(sharing());
        out.add(system());
        return List.copyOf(out);
    }

    private static Category waypoints() {
        return Category.of("waypoints", "Waypoints",
                Group.plain(
                        Setting.bool("placeNewWaypointsBelowPlayer", MAIN, "Add new waypoints below player",
                                "Waypoints created at your position go one block below you, under your feet.",
                                (c, d) -> c.placeNewWaypointsBelowPlayer(),
                                (c, d, v) -> c.setPlaceNewWaypointsBelowPlayer((Boolean) v)),
                        Setting.number("maxStaticWaypointRenderDistance", MAIN, "Static marker distance (0 = unlimited)",
                                "Hide static-route waypoints farther away than this.",
                                (c, d) -> c.maxStaticWaypointRenderDistance(),
                                (c, d, v) -> c.setMaxStaticWaypointRenderDistance(dbl(v)))
                                .range(0.0, Double.POSITIVE_INFINITY)
                                .impact(Setting.Impact.HIGH)
                                .aliases("render distance", "fps")),
                Group.plain("temporary_waypoints", "Temporary waypoints",
                        Setting.enumCycle("tempDefaultMode", MAIN, "Temp waypoint expiry",
                                null,
                                List.of(new Setting.EnumOption("timed", "Timed", Waypoint.TEMP_TIME),
                                        new Setting.EnumOption("until_reached", "Until reached", Waypoint.TEMP_UNTIL_REACHED),
                                        new Setting.EnumOption("until_leaving", "Until leaving", Waypoint.TEMP_UNTIL_LEAVE)),
                                (c, d) -> c.tempDefaultMode(),
                                (c, d, v) -> c.setTempDefaultMode(((Number) v).intValue()))
                                .aliases("expire", "timer"),
                        Setting.number("tempDefaultDurationSec", MAIN, "Temp duration (sec)",
                                null,
                                (c, d) -> (double) c.tempDefaultDurationSec(),
                                (c, d, v) -> {
                                    double value = dbl(v);
                                    if (!Double.isFinite(value)) return;
                                    long rounded = Math.round(value);
                                    c.setTempDefaultDurationSec(rounded > Integer.MAX_VALUE
                                            ? Integer.MAX_VALUE : (int) rounded);
                                })
                                .range(1.0, 86_400.0)
                                .wholeNumber()
                                .enabledWhen((c, d) -> c.tempDefaultMode() == Waypoint.TEMP_TIME)
                                .aliases("timer"),
                        Setting.bool("focusTempWaypoints", MAIN, "Focus mode for temporary waypoints",
                                "Show only the temporary waypoint that is active.",
                                (c, d) -> c.focusTempWaypoints(),
                                (c, d, v) -> c.setFocusTempWaypoints((Boolean) v))));
    }

    private static Category appearance() {
        return Category.of("appearance", "Appearance",
                Group.plain("waypoint_marker", "Waypoint",
                        Setting.enumCycle("boxStyle", MAIN, "Box style",
                                null,
                                List.of(new Setting.EnumOption("outlined", "Outlined", WaypointerConfig.BoxStyle.OUTLINED),
                                        new Setting.EnumOption("filled", "Filled", WaypointerConfig.BoxStyle.FILLED),
                                        new Setting.EnumOption("filled_outlined", "Filled + Outline", WaypointerConfig.BoxStyle.FILLED_OUTLINED),
                                        new Setting.EnumOption("paint", "Paint", WaypointerConfig.BoxStyle.PAINT)),
                                (c, d) -> c.boxStyle(),
                                (c, d, v) -> c.setBoxStyle((WaypointerConfig.BoxStyle) v))
                                .impact(Setting.Impact.LOW),
                        Setting.number("waypointMarkerScale", MAIN, "Waypoint size (0.25-3)",
                                null,
                                (c, d) -> c.waypointMarkerScale(),
                                (c, d, v) -> c.setWaypointMarkerScale(dbl(v)))
                                .range(0.25, 3.0)
                                .aliases("marker scale", "box size"),
                        Setting.color("defaultWaypointColor", MAIN, "Default waypoint color",
                                null,
                                "Default Waypoint Color", null,
                                (c, d) -> c.defaultWaypointColor(),
                                (c, d, v) -> c.setDefaultWaypointColor(rgb(v))),
                        Setting.number("beaconOpacity", MAIN, "Waypoint fill opacity (0-1)",
                                null,
                                (c, d) -> c.beaconOpacity(),
                                (c, d, v) -> c.setBeaconOpacity(dbl(v)))
                                .range(0.0, 1.0)
                                .enabledWhen((c, d) -> c.boxStyle() != WaypointerConfig.BoxStyle.OUTLINED)
                                .aliases("fade", "transparency"),
                        Setting.bool("matchWaypointOutlineToWaypointColor", MAIN,
                                "Outline inherits waypoint color",
                                "Use each waypoint's color for its outline instead of the outline color below.",
                                (c, d) -> c.matchWaypointOutlineToWaypointColor(),
                                (c, d, v) -> c.setMatchWaypointOutlineToWaypointColor((Boolean) v))
                                .enabledWhen((c, d) -> c.boxStyle() == WaypointerConfig.BoxStyle.OUTLINED
                                        || c.boxStyle() == WaypointerConfig.BoxStyle.FILLED_OUTLINED),
                        Setting.color("waypointOutlineColor", MAIN, "Outline color",
                                null,
                                "Waypoint Outline Color", null,
                                (c, d) -> c.waypointOutlineColor(),
                                (c, d, v) -> c.setWaypointOutlineColor(rgb(v)))
                                .enabledWhen((c, d) -> !c.matchWaypointOutlineToWaypointColor()
                                        && (c.boxStyle() == WaypointerConfig.BoxStyle.OUTLINED
                                        || c.boxStyle() == WaypointerConfig.BoxStyle.FILLED_OUTLINED)),
                        Setting.number("waypointOutlineOpacity", MAIN, "Outline opacity (0-1)",
                                null,
                                (c, d) -> c.waypointOutlineOpacity(),
                                (c, d, v) -> c.setWaypointOutlineOpacity(dbl(v)))
                                .range(0.0, 1.0)
                                .enabledWhen((c, d) -> c.boxStyle() == WaypointerConfig.BoxStyle.OUTLINED
                                        || c.boxStyle() == WaypointerConfig.BoxStyle.FILLED_OUTLINED)
                                .aliases("edge alpha", "outline transparency"),
                        Setting.number("waypointOutlineThickness", MAIN, "Outline thickness (px)",
                                null,
                                (c, d) -> c.waypointOutlineThickness(),
                                (c, d, v) -> c.setWaypointOutlineThickness(dbl(v)))
                                .range(1.0, 12.0)
                                .enabledWhen((c, d) -> c.boxStyle() == WaypointerConfig.BoxStyle.OUTLINED
                                        || c.boxStyle() == WaypointerConfig.BoxStyle.FILLED_OUTLINED)
                                .aliases("width"),
                        Setting.action(ACTION_WAYPOINT_PAINT, "Paint",
                                "Add pictures or paint your own waypoints.")
                                .enabledWhen((c, d) -> c.boxStyle() == WaypointerConfig.BoxStyle.PAINT)
                                .aliases("painter", "texture", "pixel")),
                Group.plain("sequenced", "Sequenced",
                        Setting.number("sequencePreviousWaypointCount", MAIN,
                                "Previous waypoints", "Enter 0-32 reached route steps, or enter All.",
                                (c, d) -> (double) c.sequencePreviousWaypointCount(),
                                (c, d, v) -> c.setSequencePreviousWaypointCount(((Number) v).intValue()))
                                .range(0, SequenceVisibility.MAX_CONTEXT_WAYPOINTS).wholeNumber()
                                .numberDisplay(SequenceVisibility.ALL,
                                        "waypointer.settings.value.all", "All")
                                .impact(Setting.Impact.MEDIUM),
                        Setting.bool("showCurrentSequenceWaypoint", MAIN,
                                "Current waypoint", "Show the current route step.",
                                (c, d) -> c.showCurrentSequenceWaypoint(),
                                (c, d, v) -> c.setShowCurrentSequenceWaypoint((Boolean) v)),
                        Setting.number("sequenceNextWaypointCount", MAIN,
                                "Next waypoints", "Amount of surroundings you want to see. 0-32",
                                (c, d) -> (double) c.sequenceNextWaypointCount(),
                                (c, d, v) -> c.setSequenceNextWaypointCount(((Number) v).intValue()))
                                .range(0, SequenceVisibility.MAX_CONTEXT_WAYPOINTS).wholeNumber()
                                .impact(Setting.Impact.MEDIUM),
                        Setting.bool("dimSequenceContextWaypoints", MAIN, "Dim surrounding waypoints",
                                null,
                                (c, d) -> c.dimSequenceContextWaypoints(),
                                (c, d, v) -> c.setDimSequenceContextWaypoints((Boolean) v)),
                        Setting.bool("colorSequenceWaypointsByRole", MAIN,
                                "Color waypoints by sequence role",
                                "Use separate colors for previous, current, and next route steps.",
                                (c, d) -> c.colorSequenceWaypointsByRole(),
                                (c, d, v) -> c.setColorSequenceWaypointsByRole((Boolean) v)),
                        Setting.color("sequencePreviousWaypointColor", MAIN,
                                "Previous waypoint color", null,
                                "Previous Waypoint Color", null,
                                (c, d) -> c.sequencePreviousWaypointColor(),
                                (c, d, v) -> c.setSequencePreviousWaypointColor(rgb(v)))
                                .enabledWhen((c, d) -> c.colorSequenceWaypointsByRole()),
                        Setting.color("sequenceCurrentWaypointColor", MAIN,
                                "Current waypoint color", null,
                                "Current Waypoint Color", null,
                                (c, d) -> c.sequenceCurrentWaypointColor(),
                                (c, d, v) -> c.setSequenceCurrentWaypointColor(rgb(v)))
                                .enabledWhen((c, d) -> c.colorSequenceWaypointsByRole()),
                        Setting.color("sequenceNextWaypointColor", MAIN,
                                "Next waypoint color", null,
                                "Next Waypoint Color", null,
                                (c, d) -> c.sequenceNextWaypointColor(),
                                (c, d, v) -> c.setSequenceNextWaypointColor(rgb(v)))
                                .enabledWhen((c, d) -> c.colorSequenceWaypointsByRole())),
                Group.plain("etherwarp", "Etherwarp",
                        Setting.enumCycle("etherwarpAlignmentSound", MAIN,
                                "Etherwarp alignment sound",
                                "Choose a sound to play when you can etherwarp to a waypoint.",
                                List.of(new Setting.EnumOption("off", "Off",
                                                WaypointerConfig.EtherwarpAlignmentSound.OFF),
                                        new Setting.EnumOption("experience", "Experience",
                                                WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE),
                                        new Setting.EnumOption("pling", "Pling",
                                                WaypointerConfig.EtherwarpAlignmentSound.PLING),
                                        new Setting.EnumOption("bell", "Bell",
                                                WaypointerConfig.EtherwarpAlignmentSound.BELL)),
                                (c, d) -> c.etherwarpAlignmentSound(),
                                (c, d, v) -> c.setEtherwarpAlignmentSound(
                                        (WaypointerConfig.EtherwarpAlignmentSound) v))),
                Group.plain("labels", "Labels",
                        Setting.bool("showWaypointNames", MAIN, "Show waypoint names",
                                null,
                                (c, d) -> c.showWaypointNames(),
                                (c, d, v) -> c.setShowWaypointNames((Boolean) v))
                                .impact(Setting.Impact.HIGH)
                                .aliases("text", "title"),
                        Setting.bool("showWaypointDistances", MAIN, "Show waypoint distances",
                                null,
                                (c, d) -> c.showWaypointDistances(),
                                (c, d, v) -> c.setShowWaypointDistances((Boolean) v))
                                .impact(Setting.Impact.MEDIUM),
                        Setting.bool("showRouteProgress", MAIN, "Show route progress",
                                null,
                                (c, d) -> c.showRouteProgress(),
                                (c, d, v) -> c.setShowRouteProgress((Boolean) v))
                                .aliases("percent"),
                        Setting.bool("matchWaypointTextToWaypointColor", MAIN, "Waypoint text inherits color",
                                null,
                                (c, d) -> c.matchWaypointTextToWaypointColor(),
                                (c, d, v) -> c.setMatchWaypointTextToWaypointColor((Boolean) v))
                                .enabledWhen((c, d) -> c.showWaypointNames()),
                        Setting.bool("showLabelBackdrop", MAIN, "Show label backdrop",
                                "Draw a dark background behind label text.",
                                (c, d) -> c.showLabelBackdrop(),
                                (c, d, v) -> c.setShowLabelBackdrop((Boolean) v))
                                .impact(Setting.Impact.LOW)
                                .enabledWhen(ANY_LABEL_TEXT),
                        Setting.bool("showLabelTextShadow", MAIN, "Show text shadows",
                                null,
                                (c, d) -> c.showLabelTextShadow(),
                                (c, d, v) -> c.setShowLabelTextShadow((Boolean) v))
                                .impact(Setting.Impact.LOW)
                                .enabledWhen(ANY_LABEL_TEXT),
                        Setting.bool("scaleWaypointTextWithDistance", MAIN, "Scale text with distance",
                                null,
                                (c, d) -> c.scaleWaypointTextWithDistance(),
                                (c, d, v) -> c.setScaleWaypointTextWithDistance((Boolean) v))
                                .enabledWhen(ANY_LABEL_TEXT),
                        Setting.number("labelScale", MAIN, "Label scale (0.25-4)",
                                null,
                                (c, d) -> c.labelScale(),
                                (c, d, v) -> c.setLabelScale(dbl(v)))
                                .range(0.25, 4.0)
                                .enabledWhen(ANY_LABEL_TEXT)
                                .aliases("size", "text size"),
                        Setting.number("labelHeightOffset", MAIN, "Label height offset (blocks)",
                                null,
                                (c, d) -> c.labelHeightOffset(),
                                (c, d, v) -> c.setLabelHeightOffset(dbl(v)))
                                .enabledWhen(ANY_LABEL_TEXT),
                        Setting.number("maxWaypointLabels", MAIN, "Max waypoint labels (0 = unlimited)",
                                null,
                                (c, d) -> (double) c.maxWaypointLabels(),
                                (c, d, v) -> {
                                    double value = dbl(v);
                                    if (!Double.isFinite(value)) return;
                                    long rounded = Math.round(value);
                                    if (rounded <= 0) {
                                        c.setMaxWaypointLabels(0);
                                    } else {
                                        c.setMaxWaypointLabels(rounded > Integer.MAX_VALUE
                                                ? Integer.MAX_VALUE : (int) rounded);
                                    }
                                })
                                .range(0.0, Integer.MAX_VALUE)
                                .wholeNumber()
                                .impact(Setting.Impact.HIGH)
                                .aliases("limit", "fps")),
                Group.parented((c, d) -> c.hideWaypointLabelsNearPlayer(),
                        Setting.bool("hideWaypointLabelsNearPlayer", MAIN, "Hide labels when near",
                                null,
                                (c, d) -> c.hideWaypointLabelsNearPlayer(),
                                (c, d, v) -> c.setHideWaypointLabelsNearPlayer((Boolean) v))
                                .enabledWhen(ANY_LABEL_TEXT),
                        Setting.number("hideWaypointLabelsNearRadius", MAIN, "Label near radius (blocks)",
                                null,
                                (c, d) -> c.hideWaypointLabelsNearRadius(),
                                (c, d, v) -> c.setHideWaypointLabelsNearRadius(dbl(v)))
                                .range(0.5, 100.0)),
                Group.parented("tracers", "Tracers", (c, d) -> c.showTracer(),
                        Setting.bool("showTracer", MAIN, "Show tracers",
                                "Draw lines from your crosshair to active waypoints.",
                                (c, d) -> c.showTracer(),
                                (c, d, v) -> c.setShowTracer((Boolean) v))
                                .impact(Setting.Impact.LOW)
                                .aliases("esp", "line"),
                        Setting.number("tracerOpacity", MAIN, "Tracer opacity (0-1)",
                                null,
                                (c, d) -> c.tracerOpacity(),
                                (c, d, v) -> c.setTracerOpacity(dbl(v)))
                                .range(0.0, 1.0)
                                .aliases("fade", "transparency"),
                        Setting.number("tracerThickness", MAIN, "Tracer thickness (px)",
                                null,
                                (c, d) -> c.tracerThickness(),
                                (c, d, v) -> c.setTracerThickness(dbl(v)))
                                .range(1.0, 12.0)
                                .aliases("width"),
                        Setting.bool("matchTracerToWaypointColor", MAIN, "Tracer inherits waypoint color",
                                null,
                                (c, d) -> c.matchTracerToWaypointColor(),
                                (c, d, v) -> c.setMatchTracerToWaypointColor((Boolean) v)),
                        Setting.color("tracerColor", MAIN, "Tracer color",
                                null,
                                "Tracer Color", null,
                                (c, d) -> c.tracerColor(),
                                (c, d, v) -> c.setTracerColor(rgb(v)))
                                .enabledWhen((c, d) -> c.showTracer() && !c.matchTracerToWaypointColor()),
                        Setting.bool("hideTracerOnStaticRoutes", MAIN, "Hide tracer on static routes",
                                null,
                                (c, d) -> c.hideTracerOnStaticRoutes(),
                                (c, d, v) -> c.setHideTracerOnStaticRoutes((Boolean) v))),
                Group.parented("beacon_beams", "Beacon beams",
                        (c, d) -> c.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF,
                        Setting.enumCycle("beaconBeamMode", MAIN, "Beacon beams",
                                null,
                                List.of(new Setting.EnumOption("off", "Off", WaypointerConfig.BeaconBeamMode.OFF),
                                        new Setting.EnumOption("current", "Current", WaypointerConfig.BeaconBeamMode.CURRENT),
                                        new Setting.EnumOption("all_visible", "All visible", WaypointerConfig.BeaconBeamMode.ALL_VISIBLE)),
                                (c, d) -> c.beaconBeamMode(),
                                (c, d, v) -> c.setBeaconBeamMode((WaypointerConfig.BeaconBeamMode) v))
                                .impact(Setting.Impact.MEDIUM)
                                .aliases("beam", "pillar"),
                        Setting.bool("useBeaconBeamTextures", MAIN, "Use beacon textures",
                                null,
                                (c, d) -> c.useBeaconBeamTextures(),
                                (c, d, v) -> c.setUseBeaconBeamTextures((Boolean) v))
                                .impact(Setting.Impact.MEDIUM),
                        Setting.bool("beaconBeamExtendsBelowWaypoint", MAIN, "Beam extends below waypoint",
                                null,
                                (c, d) -> c.beaconBeamExtendsBelowWaypoint(),
                                (c, d, v) -> c.setBeaconBeamExtendsBelowWaypoint((Boolean) v))
                                .impact(Setting.Impact.LOW)),
                Group.parented("route_lines", "Route lines", (c, d) -> c.showRouteLines(),
                        Setting.bool("showRouteLines", MAIN, "Show route connector lines",
                                "Draw lines between the waypoints of a route.",
                                (c, d) -> c.showRouteLines(),
                                (c, d, v) -> c.setShowRouteLines((Boolean) v))
                                .impact(Setting.Impact.LOW),
                        Setting.bool("useEtherwarpHeight", MAIN, "Use Etherwarp height",
                                null,
                                (c, d) -> c.useEtherwarpHeight(),
                                (c, d, v) -> c.setUseEtherwarpHeight((Boolean) v)),
                        Setting.color("routeLineColor", MAIN, "Route line color",
                                null,
                                "Route Line Color", null,
                                (c, d) -> c.routeLineColor(),
                                (c, d, v) -> c.setRouteLineColor(rgb(v)))));
    }

    private static Category routes() {
        return Category.of("routes", "Routes & progression",
                Group.plain(
                        Setting.number("defaultReachRadius", MAIN, "Default reach radius (blocks)",
                                "How close you need to get for a waypoint to count as reached.",
                                (c, d) -> c.defaultReachRadius(),
                                (c, d, v) -> c.setDefaultReachRadius(dbl(v)))
                                .range(0.5, 100.0)
                                .aliases("trigger"),
                        Setting.bool("resetProgressOnWorldJoin", MAIN, "Reset progress when joining a world",
                                null,
                                (c, d) -> c.resetProgressOnWorldJoin(),
                                (c, d, v) -> c.setResetProgressOnWorldJoin((Boolean) v)),
                        Setting.bool("restartRouteWhenComplete", MAIN, "Restart route after last waypoint",
                                null,
                                (c, d) -> c.restartRouteWhenComplete(),
                                (c, d, v) -> c.setRestartRouteWhenComplete((Boolean) v))
                                .aliases("loop"),
                        Setting.bool("allowBackwardProgress", MAIN, "Allow stepping back",
                                "Reaching an earlier waypoint moves the route back to it",
                                (c, d) -> c.allowBackwardProgress(),
                                (c, d, v) -> c.setAllowBackwardProgress((Boolean) v))
                                .impact(Setting.Impact.LOW),
                        Setting.bool("showRouteIndicesInGui", MAIN, "Show route indices",
                                "In the route list, show each route's index number for commands.",
                                (c, d) -> c.showRouteIndicesInGui(),
                                (c, d, v) -> c.setShowRouteIndicesInGui((Boolean) v))
                                .aliases("command", "index", "number"),
                        Setting.bool("keepSubwaypointsVisibleUntilNextWaypoint", MAIN,
                                "Keep subwaypoints until next waypoint",
                                null,
                                (c, d) -> c.keepSubwaypointsVisibleUntilNextWaypoint(),
                                (c, d, v) -> c.setKeepSubwaypointsVisibleUntilNextWaypoint((Boolean) v))
                                .aliases("subwaypoint", "label", "hide"),
                        Setting.bool("hideReachedStaticWaypointsUntilCycleComplete", MAIN, "Hide reached static waypoints",
                                null,
                                (c, d) -> c.hideReachedStaticWaypointsUntilCycleComplete(),
                                (c, d, v) -> c.setHideReachedStaticWaypointsUntilCycleComplete((Boolean) v))
                                .aliases("checklist")),
                Group.parented((c, d) -> c.skipAheadMechanicEnabled(),
                        Setting.bool("skipAheadMechanicEnabled", MAIN, "Enable waypoint skipping",
                                null,
                                (c, d) -> c.skipAheadMechanicEnabled(),
                                (c, d, v) -> c.setSkipAheadMechanicEnabled((Boolean) v)),
                        Setting.bool("skipAheadOnlyVisibleWaypoints", MAIN, "Only skip to visible waypoints",
                                null,
                                (c, d) -> c.skipAheadOnlyVisibleWaypoints(),
                                (c, d, v) -> c.setSkipAheadOnlyVisibleWaypoints((Boolean) v))),
                Group.parented((c, d) -> c.hideWaypointsNearPlayer(),
                        Setting.bool("hideWaypointsNearPlayer", MAIN, "Hide waypoints when near",
                                null,
                                (c, d) -> c.hideWaypointsNearPlayer(),
                                (c, d, v) -> c.setHideWaypointsNearPlayer((Boolean) v)),
                        Setting.number("hideWaypointsNearRadius", MAIN, "Near hide radius (blocks)",
                                null,
                                (c, d) -> c.hideWaypointsNearRadius(),
                                (c, d, v) -> c.setHideWaypointsNearRadius(dbl(v)))
                                .range(0.5, 100.0)));
    }

    private static Category dungeons() {
        return new Category("dungeons", "Dungeons", "enabled",
                (c, d) -> d == null || d.enabled(),
                List.of(
                        Group.plain(
                                Setting.bool("enabled", DUNGEON, "Dungeon features",
                                        null,
                                        (c, d) -> d.enabled(),
                                        (c, d, v) -> d.setEnabled((Boolean) v))
                                        .impact(Setting.Impact.MEDIUM)
                                        .aliases("skyblock", "secrets", "catacombs"),
                                Setting.bool("hideCompletedRooms", DUNGEON, "Hide completed rooms",
                                        null,
                                        (c, d) -> d.hideCompletedRooms(),
                                        (c, d, v) -> d.setHideCompletedRooms((Boolean) v)),
                                Setting.bool("secretCompletionSound", DUNGEON,
                                        "Secret completion sound",
                                        null,
                                        (c, d) -> d.secretCompletionSound(),
                                        (c, d, v) -> d.setSecretCompletionSound((Boolean) v))),
                         Group.plain("dungeon_route_rendering", "Dungeon route rendering",
                                Setting.bool("showDungeonRouteLines", DUNGEON,
                                        "Route connector lines",
                                        null,
                                        (c, d) -> d.showDungeonRouteLines(),
                                        (c, d, v) -> d.setShowDungeonRouteLines((Boolean) v))
                                        .impact(Setting.Impact.LOW),
                                Setting.bool("showDungeonTracers", DUNGEON,
                                        "Tracers",
                                        null,
                                        (c, d) -> d.showDungeonTracers(),
                                        (c, d, v) -> d.setShowDungeonTracers((Boolean) v))
                                        .impact(Setting.Impact.LOW)),
                        Group.parented((c, d) -> c.showDungeonEntryPathToFirstWaypoint(),
                                Setting.bool("showDungeonEntryPathToFirstWaypoint", MAIN, "Pathfind to First Waypoint",
                                        null,
                                        (c, d) -> c.showDungeonEntryPathToFirstWaypoint(),
                                        (c, d, v) -> c.setShowDungeonEntryPathToFirstWaypoint((Boolean) v))
                                        .impact(Setting.Impact.LOW),
                                Setting.bool("showDungeonEntryPathToFollowingWaypoints", MAIN, "Pathfind to All Waypoints",
                                        null,
                                        (c, d) -> c.showDungeonEntryPathToFollowingWaypoints(),
                                        (c, d, v) -> c.setShowDungeonEntryPathToFollowingWaypoints((Boolean) v)),
                                Setting.color("dungeonEntryPathColor", MAIN, "Dungeon entry path color",
                                        null,
                                        "Dungeon Entry Path Color", null,
                                        (c, d) -> c.dungeonEntryPathColor(),
                                        (c, d, v) -> c.setDungeonEntryPathColor(rgb(v))))));
    }

    private static Category mining() {
        return new Category("mining", "Mining", "crystalHollowsEnabled",
                (c, d) -> c.crystalHollowsEnabled(),
                List.of(Group.plain("crystal_hollows_structures", "Crystal Hollows structures",
                        Setting.bool("crystalHollowsEnabled", MAIN, "Crystal Hollows features",
                                "Detect lobby structures using only information already shown to you.",
                                (c, d) -> c.crystalHollowsEnabled(),
                                (c, d, v) -> c.setCrystalHollowsEnabled((Boolean) v))
                                .impact(Setting.Impact.LOW)
                                .aliases("mining", "compass", "hollows", "divan", "temple"),
                        Setting.bool("crystalHollowsStructureWaypoints", MAIN,
                                "Structure waypoints",
                                "Show detected structures in the runtime Structures folder.",
                                (c, d) -> c.crystalHollowsStructureWaypoints(),
                                (c, d, v) -> c.setCrystalHollowsStructureWaypoints((Boolean) v)),
                        Setting.bool("crystalHollowsShowRoughMarkers", MAIN,
                                "Approximate markers",
                                "Show lower-confidence locations estimated from sidebar areas.",
                                (c, d) -> c.crystalHollowsShowRoughMarkers(),
                                (c, d, v) -> c.setCrystalHollowsShowRoughMarkers((Boolean) v)),
                        Setting.bool("crystalHollowsEntityDetection", MAIN,
                                "Visible NPC detection",
                                "Use nearby NPCs that are visible or named by your current sidebar area.",
                                (c, d) -> c.crystalHollowsEntityDetection(),
                                (c, d, v) -> c.setCrystalHollowsEntityDetection((Boolean) v)),
                        Setting.bool("crystalHollowsChatDetection", MAIN,
                                "Shared-coordinate detection",
                                "Read structure coordinates shared by players in chat.",
                                (c, d) -> c.crystalHollowsChatDetection(),
                                (c, d, v) -> c.setCrystalHollowsChatDetection((Boolean) v)),
                        Setting.bool("crystalHollowsAnnounceDetections", MAIN,
                                "Detection messages",
                                "Announce new and improved structure locations in chat.",
                                (c, d) -> c.crystalHollowsAnnounceDetections(),
                                (c, d, v) -> c.setCrystalHollowsAnnounceDetections((Boolean) v)),
                        Setting.bool("crystalHollowsNucleusWaypoints", MAIN,
                                "Crystal Nucleus entrances",
                                "Add the nine fixed Nucleus centre and entrance waypoints.",
                                (c, d) -> c.crystalHollowsNucleusWaypoints(),
                                (c, d, v) -> c.setCrystalHollowsNucleusWaypoints((Boolean) v))),
                        Group.parented("wishing_compass", "Wishing Compass",
                                (c, d) -> c.crystalHollowsWishingCompassSolver(),
                                Setting.bool("crystalHollowsWishingCompassSolver", MAIN,
                                        "Wishing Compass solver",
                                        "Capture your compass particles and triangulate their destination.",
                                        (c, d) -> c.crystalHollowsWishingCompassSolver(),
                                        (c, d, v) -> c.setCrystalHollowsWishingCompassSolver((Boolean) v)),
                                Setting.bool("crystalHollowsCompassRays", MAIN,
                                        "Compass rays",
                                        "Render captured Wishing Compass directions while solving.",
                                        (c, d) -> c.crystalHollowsCompassRays(),
                                        (c, d, v) -> c.setCrystalHollowsCompassRays((Boolean) v)))));
    }

    private static Category chat() {
        return Category.of("chat", "Chat",
                Group.parented((c, d) -> c.chatCoordDetection(),
                        Setting.bool("chatCoordDetection", MAIN, "Chat coord detection",
                                null,
                                (c, d) -> c.chatCoordDetection(),
                                (c, d, v) -> c.setChatCoordDetection((Boolean) v))
                                .aliases("coordinates"),
                        Setting.bool("autoAddChatTempWaypoints", MAIN, "Create Waypoints from Chat Messages",
                                null,
                                (c, d) -> c.autoAddChatTempWaypoints(),
                                (c, d, v) -> c.setAutoAddChatTempWaypoints((Boolean) v))),
                Group.plain(
                        Setting.bool("showWaypointChatShareButtons", MAIN, "Chat Share Buttons",
                                null,
                                (c, d) -> c.showWaypointChatShareButtons(),
                                (c, d, v) -> c.setShowWaypointChatShareButtons((Boolean) v)),
                        Setting.bool("chatCodecDetection", MAIN, "Chat codec detection (imports)",
                                "Detect Waypointer share codes in chat.",
                                (c, d) -> c.chatCodecDetection(),
                                (c, d, v) -> c.setChatCodecDetection((Boolean) v))
                                .aliases("share"),
                        Setting.bool("showContributorBadges", MAIN, "Contributor badges",
                                "Show Waypointer contributor badges in chat, player nametags, "
                                        + "and the player list.",
                                (c, d) -> c.showContributorBadges(),
                                (c, d, v) -> c.setShowContributorBadges((Boolean) v))));
    }

    private static Category sharing() {
        return Category.of("sharing", "Sharing",
                Group.parented((c, d) -> c.importedRouteColorMode() == WaypointGroup.GradientMode.STATIC,
                        Setting.enumCycle("importedRouteColorMode", MAIN, "Imported route colors",
                                "Overrides imported waypoint colors.",
                                List.of(new Setting.EnumOption("one_color", "One color", WaypointGroup.GradientMode.STATIC),
                                        new Setting.EnumOption("gradient", "Gradient", WaypointGroup.GradientMode.AUTO),
                                        new Setting.EnumOption("manual", "Manual", WaypointGroup.GradientMode.MANUAL)),
                                (c, d) -> c.importedRouteColorMode(),
                                (c, d, v) -> c.setImportedRouteColorMode((WaypointGroup.GradientMode) v))
                                .aliases("gradient", "import"),
                        Setting.color("importedRouteDefaultColor", MAIN, "Imported color",
                                null,
                                "Imported Route Color", null,
                                (c, d) -> c.importedRouteDefaultColor(),
                                (c, d, v) -> c.setImportedRouteDefaultColor(rgb(v)))),
                Group.plain("export_screen", "Export screen",
                        Setting.bool("showExportRoutePreview", MAIN, "3D route preview",
                                "Show a rotating 3D preview of the route on the export screen.",
                                (c, d) -> c.showExportRoutePreview(),
                                (c, d, v) -> c.setShowExportRoutePreview((Boolean) v))
                                .aliases("preview", "3d", "isometric")),
                Group.plain("export_defaults", "Export defaults",
                        Setting.bool("exportIncludeNames", MAIN, "Include names in default export",
                                null,
                                (c, d) -> c.exportIncludeNames(),
                                (c, d, v) -> c.setExportIncludeNames((Boolean) v)),
                        Setting.bool("exportIncludeColors", MAIN, "Include colors in default export",
                                null,
                                (c, d) -> c.exportIncludeColors(),
                                (c, d, v) -> c.setExportIncludeColors((Boolean) v)),
                        Setting.bool("exportIncludeRadii", MAIN, "Include radii in default export",
                                null,
                                (c, d) -> c.exportIncludeRadii(),
                                (c, d, v) -> c.setExportIncludeRadii((Boolean) v)),
                        Setting.bool("exportIncludeWaypointFlags", MAIN, "Include waypoint flags",
                                null,
                                (c, d) -> c.exportIncludeWaypointFlags(),
                                (c, d, v) -> c.setExportIncludeWaypointFlags((Boolean) v)),
                        Setting.bool("exportIncludeGroupMeta", MAIN, "Include route metadata",
                                null,
                                (c, d) -> c.exportIncludeGroupMeta(),
                                (c, d, v) -> c.setExportIncludeGroupMeta((Boolean) v)),
                        Setting.bool("exportIncludeZone", MAIN, "Include island in default export",
                                null,
                                (c, d) -> c.exportIncludeZone(),
                                (c, d, v) -> c.setExportIncludeZone((Boolean) v))));
    }

    private static Category system() {
        return Category.of("system", "System",
                Group.plain(
                        Setting.bool("irisShaderHudFallback", MAIN, "Iris shader compatibility",
                                null,
                                (c, d) -> c.irisShaderHudFallback(),
                                (c, d, v) -> c.setIrisShaderHudFallback((Boolean) v))
                                .aliases("shaders"),
                        Setting.bool("editSounds", MAIN, "Edit mode sounds",
                                null,
                                (c, d) -> c.editSounds(),
                                (c, d, v) -> c.setEditSounds((Boolean) v))
                                .aliases("audio"),
                        Setting.bool("showEditModeSubtitle", MAIN, "Show EDIT MODE subtitle",
                                null,
                                (c, d) -> c.showEditModeSubtitle(),
                                (c, d, v) -> c.setShowEditModeSubtitle((Boolean) v))),
                Group.plain("config_presets", "Config & presets",
                        Setting.action(ACTION_CONFIG_CODE, "Config code",
                                null)
                                .aliases("share", "wpc", "import", "export"),
                        Setting.action(ACTION_PRESETS, "Presets",
                                null)
                                .aliases("profile", "minimal", "default")),
                Group.plain("diagnostics", "Diagnostics",
                        Setting.action(ACTION_PERF_TEST, "Performance stress test", "")
                                .aliases("benchmark", "profiler", "fps", "stress", "lag")),
                new Group(null, null, null, null, List.of(
                        Setting.hidden("dungeonWaypointsFeatureEnabled",
                                (c, d) -> c.dungeonWaypointsFeatureEnabled(),
                                (c, d, v) -> c.setDungeonWaypointsFeatureEnabled((Boolean) v)),
                        Setting.hidden("chatCoordSenderBlacklist",
                                (c, d) -> c.chatCoordSenderBlacklist(),
                                (c, d, v) -> {
                                    for (Object name : (List<?>) v) {
                                        c.addChatCoordSenderBlacklist(String.valueOf(name));
                                    }
                                }))));
    }
}
