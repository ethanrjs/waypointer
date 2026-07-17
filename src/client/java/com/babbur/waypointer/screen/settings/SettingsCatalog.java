package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.babbur.waypointer.screen.settings.Setting.Store.DUNGEON;
import static com.babbur.waypointer.screen.settings.Setting.Store.MAIN;

/**
 * The full declarative catalog behind the settings screen.
 *
 * <p>Feature-first: every setting has exactly one home category. The catalog
 * replaces the old hand-maintained duplication between the screen's page
 * builders and {@code countChangedSettings} — the import diff is derived from
 * these entries, and parity tests guard the codec and the config bulk ops
 * against this list (see {@code SettingsCatalogTest}).
 *
 * <p>Entry ids equal the exact backing config field name for MAIN/DUNGEON
 * entries; parity tests rely on that. Action rows use {@code action.*} ids.
 */
public final class SettingsCatalog {

    /**
     * A run of settings inside a category. When {@code parentSettingId} is set,
     * the first setting renders as the group's header row and the remaining
     * settings are omitted entirely (not just grayed) while
     * {@code childrenVisibleWhen} tests false.
     */
    public record Group(String label, String parentSettingId,
                        Setting.EnabledWhen childrenVisibleWhen, List<Setting> settings) {

        public static Group plain(String label, Setting... settings) {
            return new Group(label, null, null, List.of(settings));
        }

        public static Group parented(Setting.EnabledWhen childrenVisibleWhen, Setting... settings) {
            return new Group(null, settings[0].id(), childrenVisibleWhen, List.of(settings));
        }
    }

    /**
     * A sidebar category. When {@code masterSettingId} is set and
     * {@code bodyVisibleWhen} tests false, only the master's own row renders
     * and the rest of the category body collapses.
     */
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

    private static final Setting.EnabledWhen ANY_LABEL_TEXT = (c, d) ->
            c.showWaypointNames() || c.showWaypointDistances() || c.showRouteProgress();

    private static final List<Category> CATEGORIES = buildCategories();
    private static final List<Setting> ALL_SETTINGS = flatten(CATEGORIES);

    private SettingsCatalog() {}

    /** Sidebar categories in display order. */
    public static List<Category> categories() {
        return CATEGORIES;
    }

    /** Every setting in catalog (declaration) order, including HIDDEN and ACTION entries. */
    public static List<Setting> allSettings() {
        return ALL_SETTINGS;
    }

    public static Setting byId(String id) {
        for (Setting setting : ALL_SETTINGS) {
            if (setting.id().equals(id)) return setting;
        }
        return null;
    }

    /**
     * Number of MAIN-backed settings whose value differs between the two
     * configs. Drives the config-code import confirmation ("N settings will be
     * changed"). Derived from the catalog so a cataloged field can never be
     * silently missing from the count.
     */
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
        out.add(labels());
        out.add(tracers());
        out.add(beams());
        out.add(routes());
        out.add(dungeons());
        out.add(chat());
        out.add(sharing());
        out.add(system());
        return List.copyOf(out);
    }

    private static Category waypoints() {
        return Category.of("waypoints", "Waypoints",
                Group.plain(null,
                        Setting.enumCycle("boxStyle", MAIN, "Box style",
                                null,
                                List.of(new Setting.EnumOption("Outlined", WaypointerConfig.BoxStyle.OUTLINED),
                                        new Setting.EnumOption("Filled", WaypointerConfig.BoxStyle.FILLED),
                                        new Setting.EnumOption("Filled + Outline", WaypointerConfig.BoxStyle.FILLED_OUTLINED)),
                                (c, d) -> c.boxStyle(),
                                (c, d, v) -> c.setBoxStyle((WaypointerConfig.BoxStyle) v))
                                .impact(Setting.Impact.LOW),
                        Setting.number("beaconOpacity", MAIN, "Waypoint box opacity (0-1)",
                                null,
                                (c, d) -> c.beaconOpacity(),
                                (c, d, v) -> c.setBeaconOpacity(dbl(v)))
                                .aliases("fade", "transparency"),
                        Setting.number("waypointOutlineThickness", MAIN, "Outline thickness (px)",
                                null,
                                (c, d) -> c.waypointOutlineThickness(),
                                (c, d, v) -> c.setWaypointOutlineThickness(dbl(v)))
                                .aliases("width"),
                        Setting.bool("sharpWaypointEdges", MAIN, "Sharp waypoint edges",
                                null,
                                (c, d) -> c.sharpWaypointEdges(),
                                (c, d, v) -> c.setSharpWaypointEdges((Boolean) v)),
                        Setting.bool("showCompleted", MAIN, "Show completed waypoints",
                                "Keep showing waypoints you've already reached.",
                                (c, d) -> c.showCompleted(),
                                (c, d, v) -> c.setShowCompleted((Boolean) v))
                                .impact(Setting.Impact.MEDIUM),
                        Setting.color("defaultWaypointColor", MAIN, "Default waypoint color",
                                "Used for newly created waypoints. Existing waypoints keep their color.",
                                "Default Waypoint Colour", "Pick default waypoint color.",
                                (c, d) -> c.defaultWaypointColor(),
                                (c, d, v) -> c.setDefaultWaypointColor(rgb(v))),
                        Setting.bool("placeNewWaypointsBelowPlayer", MAIN, "Add new waypoints below player",
                                "Waypoints created at your position go one block below you, under your feet.",
                                (c, d) -> c.placeNewWaypointsBelowPlayer(),
                                (c, d, v) -> c.setPlaceNewWaypointsBelowPlayer((Boolean) v)),
                        Setting.number("maxStaticWaypointRenderDistance", MAIN, "Static marker distance (0 = unlimited)",
                                "Hide static-route waypoints farther away than this.",
                                (c, d) -> c.maxStaticWaypointRenderDistance(),
                                (c, d, v) -> c.setMaxStaticWaypointRenderDistance(dbl(v)))
                                .impact(Setting.Impact.HIGH)
                                .aliases("render distance", "fps")),
                Group.plain("Temporary waypoints",
                        Setting.enumCycle("tempDefaultMode", MAIN, "Temp waypoint expiry",
                                "Default lifetime rule for new temp waypoints. Timed removes them after the duration below, "
                                        + "Until reached removes them when you reach them, "
                                        + "Until leaving keeps them until you leave the server.",
                                List.of(new Setting.EnumOption("Timed", Waypoint.TEMP_TIME),
                                        new Setting.EnumOption("Until reached", Waypoint.TEMP_UNTIL_REACHED),
                                        new Setting.EnumOption("Until leaving", Waypoint.TEMP_UNTIL_LEAVE)),
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
                                .enabledWhen((c, d) -> c.tempDefaultMode() == Waypoint.TEMP_TIME)
                                .aliases("timer"),
                        Setting.bool("focusTempWaypoints", MAIN, "Focus mode for temp waypoints",
                                "Show only the newest temp waypoint in the active zone.",
                                (c, d) -> c.focusTempWaypoints(),
                                (c, d, v) -> c.setFocusTempWaypoints((Boolean) v))));
    }

    private static Category labels() {
        return Category.of("labels", "Labels",
                Group.plain(null,
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
                                "Draw a shadow behind label text for readability.",
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
                                .enabledWhen(ANY_LABEL_TEXT)
                                .aliases("size", "text size"),
                        Setting.number("labelHeightOffset", MAIN, "Label height offset (blocks)",
                                "How far labels float above the waypoint box.",
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
                                .impact(Setting.Impact.HIGH)
                                .aliases("limit", "fps")),
                Group.parented((c, d) -> c.hideWaypointLabelsNearPlayer(),
                        Setting.bool("hideWaypointLabelsNearPlayer", MAIN, "Hide labels when near",
                                "Hides just the label text when you're close; the waypoint itself stays visible.",
                                (c, d) -> c.hideWaypointLabelsNearPlayer(),
                                (c, d, v) -> c.setHideWaypointLabelsNearPlayer((Boolean) v))
                                .enabledWhen(ANY_LABEL_TEXT),
                        Setting.number("hideWaypointLabelsNearRadius", MAIN, "Label near radius (blocks)",
                                null,
                                (c, d) -> c.hideWaypointLabelsNearRadius(),
                                (c, d, v) -> c.setHideWaypointLabelsNearRadius(dbl(v)))));
    }

    private static Category tracers() {
        return Category.of("tracers", "Tracers",
                Group.parented((c, d) -> c.showTracer(),
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
                                .aliases("fade", "transparency"),
                        Setting.number("tracerThickness", MAIN, "Tracer thickness (px)",
                                null,
                                (c, d) -> c.tracerThickness(),
                                (c, d, v) -> c.setTracerThickness(dbl(v)))
                                .aliases("width"),
                        Setting.bool("matchTracerToWaypointColor", MAIN, "Tracer inherits waypoint color",
                                null,
                                (c, d) -> c.matchTracerToWaypointColor(),
                                (c, d, v) -> c.setMatchTracerToWaypointColor((Boolean) v)),
                        Setting.color("tracerColor", MAIN, "Tracer color",
                                "Used when tracers don't inherit the waypoint's color.",
                                "Tracer Colour", "Pick tracer color.",
                                (c, d) -> c.tracerColor(),
                                (c, d, v) -> c.setTracerColor(rgb(v)))
                                .enabledWhen((c, d) -> c.showTracer() && !c.matchTracerToWaypointColor()),
                        Setting.bool("hideTracerOnStaticRoutes", MAIN, "Hide tracer on static routes",
                                null,
                                (c, d) -> c.hideTracerOnStaticRoutes(),
                                (c, d, v) -> c.setHideTracerOnStaticRoutes((Boolean) v))));
    }

    private static Category beams() {
        return Category.of("beams", "Beacon beams",
                Group.parented((c, d) -> c.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF,
                        Setting.enumCycle("beaconBeamMode", MAIN, "Beacon beams",
                                "Current beams only your current waypoint. All visible beams every visible waypoint.",
                                List.of(new Setting.EnumOption("Off", WaypointerConfig.BeaconBeamMode.OFF),
                                        new Setting.EnumOption("Current", WaypointerConfig.BeaconBeamMode.CURRENT),
                                        new Setting.EnumOption("All visible", WaypointerConfig.BeaconBeamMode.ALL_VISIBLE)),
                                (c, d) -> c.beaconBeamMode(),
                                (c, d, v) -> c.setBeaconBeamMode((WaypointerConfig.BeaconBeamMode) v))
                                .impact(Setting.Impact.MEDIUM)
                                .aliases("beam", "pillar"),
                        Setting.bool("useBeaconBeamTextures", MAIN, "Use beacon textures",
                                "Use the vanilla beacon look for beams. Off draws plain flat beams instead.",
                                (c, d) -> c.useBeaconBeamTextures(),
                                (c, d, v) -> c.setUseBeaconBeamTextures((Boolean) v))
                                .impact(Setting.Impact.MEDIUM),
                        Setting.bool("beaconBeamExtendsBelowWaypoint", MAIN, "Beam extends below waypoint",
                                "Beams start at the bottom of the world instead of at the waypoint.",
                                (c, d) -> c.beaconBeamExtendsBelowWaypoint(),
                                (c, d, v) -> c.setBeaconBeamExtendsBelowWaypoint((Boolean) v))
                                .impact(Setting.Impact.LOW)));
    }

    private static Category routes() {
        return Category.of("routes", "Routes & progression",
                Group.plain(null,
                        Setting.number("defaultReachRadius", MAIN, "Default reach radius (blocks)",
                                "How close you need to get for a waypoint to count as reached.",
                                (c, d) -> c.defaultReachRadius(),
                                (c, d, v) -> c.setDefaultReachRadius(dbl(v)))
                                .aliases("trigger"),
                        Setting.bool("resetProgressOnWorldJoin", MAIN, "Reset progress when joining a world",
                                "Joining a world resets each route to the first waypoint.",
                                (c, d) -> c.resetProgressOnWorldJoin(),
                                (c, d, v) -> c.setResetProgressOnWorldJoin((Boolean) v)),
                        Setting.bool("restartRouteWhenComplete", MAIN, "Restart route after last waypoint",
                                null,
                                (c, d) -> c.restartRouteWhenComplete(),
                                (c, d, v) -> c.setRestartRouteWhenComplete((Boolean) v))
                                .aliases("loop"),
                        Setting.bool("showRouteProgress", MAIN, "Show route progress",
                                "Show route progress percentage on waypoint labels.",
                                (c, d) -> c.showRouteProgress(),
                                (c, d, v) -> c.setShowRouteProgress((Boolean) v))
                                .aliases("percent"),
                        Setting.bool("dimSequenceContextWaypoints", MAIN, "Dim sequence context waypoints",
                                "Dim waypoints surrounding your current one in a sequenced route.",
                                (c, d) -> c.dimSequenceContextWaypoints(),
                                (c, d, v) -> c.setDimSequenceContextWaypoints((Boolean) v)),
                        Setting.bool("hideReachedStaticWaypointsUntilCycleComplete", MAIN, "Hide reached static waypoints",
                                "Waypoints on static routes disappear as you reach them, like a checklist, "
                                        + "and all come back once you've reached every one.",
                                (c, d) -> c.hideReachedStaticWaypointsUntilCycleComplete(),
                                (c, d, v) -> c.setHideReachedStaticWaypointsUntilCycleComplete((Boolean) v))
                                .aliases("checklist")),
                Group.parented((c, d) -> c.skipAheadMechanicEnabled(),
                        Setting.bool("skipAheadMechanicEnabled", MAIN, "Enable waypoint skip-ahead mechanic",
                                "Reaching a later waypoint skips earlier route steps.",
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
                                (c, d, v) -> c.setHideWaypointsNearRadius(dbl(v)))),
                Group.parented((c, d) -> c.showRouteLines(),
                        Setting.bool("showRouteLines", MAIN, "Show route connector lines",
                                "Draw lines between the waypoints of a route.",
                                (c, d) -> c.showRouteLines(),
                                (c, d, v) -> c.setShowRouteLines((Boolean) v))
                                .impact(Setting.Impact.LOW),
                        Setting.color("routeLineColor", MAIN, "Route line color",
                                null,
                                "Route Line Colour", "Pick route connector line color.",
                                (c, d) -> c.routeLineColor(),
                                (c, d, v) -> c.setRouteLineColor(rgb(v)))));
    }

    private static Category dungeons() {
        return new Category("dungeons", "Dungeons", "enabled",
                (c, d) -> d == null || d.enabled(),
                List.of(
                        Group.plain(null,
                                Setting.bool("enabled", DUNGEON, "Dungeon features",
                                        "Master switch for dungeon room detection and room waypoints.",
                                        (c, d) -> d.enabled(),
                                        (c, d, v) -> d.setEnabled((Boolean) v))
                                        .impact(Setting.Impact.MEDIUM)
                                        .aliases("skyblock", "secrets", "catacombs"),
                                Setting.bool("hideCompletedRooms", DUNGEON, "Hide completed rooms",
                                        "Remove a room's route waypoints once every secret in it is found.",
                                        (c, d) -> d.hideCompletedRooms(),
                                        (c, d, v) -> d.setHideCompletedRooms((Boolean) v)),
                                Setting.bool("autoCompleteRoomsOnGreenCheckmark", DUNGEON, "Auto-complete rooms on green checkmark",
                                        "Treat the dungeon map's green checkmark as all secrets found for that room, "
                                                + "even when a teammate collected them.",
                                        (c, d) -> d.autoCompleteRoomsOnGreenCheckmark(),
                                        (c, d, v) -> d.setAutoCompleteRoomsOnGreenCheckmark((Boolean) v))),
                        Group.parented((c, d) -> c.showDungeonEntryPathToFirstWaypoint(),
                                Setting.bool("showDungeonEntryPathToFirstWaypoint", MAIN, "Dungeon entry path to first waypoint",
                                        "Draw a teleport-friendly path to waypoint #1 while entering a dungeon room.",
                                        (c, d) -> c.showDungeonEntryPathToFirstWaypoint(),
                                        (c, d, v) -> c.setShowDungeonEntryPathToFirstWaypoint((Boolean) v))
                                        .impact(Setting.Impact.LOW),
                                Setting.bool("showDungeonEntryPathToFollowingWaypoints", MAIN, "Continue dungeon path after first",
                                        "Keep the path going to later waypoints after you reach the first.",
                                        (c, d) -> c.showDungeonEntryPathToFollowingWaypoints(),
                                        (c, d, v) -> c.setShowDungeonEntryPathToFollowingWaypoints((Boolean) v)),
                                Setting.color("dungeonEntryPathColor", MAIN, "Dungeon entry path color",
                                        null,
                                        "Dungeon Entry Path Colour", "Pick dungeon entry path color.",
                                        (c, d) -> c.dungeonEntryPathColor(),
                                        (c, d, v) -> c.setDungeonEntryPathColor(rgb(v))))));
    }

    private static Category chat() {
        return Category.of("chat", "Chat",
                Group.parented((c, d) -> c.chatCoordDetection(),
                        Setting.bool("chatCoordDetection", MAIN, "Chat coord detection",
                                "Detect coordinates in chat for quick waypoint adds.",
                                (c, d) -> c.chatCoordDetection(),
                                (c, d, v) -> c.setChatCoordDetection((Boolean) v))
                                .aliases("coordinates"),
                        Setting.bool("autoAddChatTempWaypoints", MAIN, "Auto-add chat temp waypoints",
                                "Create temp waypoints automatically from chat coordinates.",
                                (c, d) -> c.autoAddChatTempWaypoints(),
                                (c, d, v) -> c.setAutoAddChatTempWaypoints((Boolean) v))),
                Group.plain(null,
                        Setting.bool("chatCodecDetection", MAIN, "Chat codec detection (imports)",
                                "Detect Waypointer share codes in chat.",
                                (c, d) -> c.chatCodecDetection(),
                                (c, d, v) -> c.setChatCodecDetection((Boolean) v))
                                .aliases("share"),
                        Setting.bool("showContributorBadges", MAIN, "Contributor badges",
                                "Show Waypointer contributor badges in chat and the player list.",
                                (c, d) -> c.showContributorBadges(),
                                (c, d, v) -> c.setShowContributorBadges((Boolean) v))));
    }

    private static Category sharing() {
        return Category.of("sharing", "Sharing",
                Group.parented((c, d) -> c.importedRouteColorMode() == WaypointGroup.GradientMode.STATIC,
                        Setting.enumCycle("importedRouteColorMode", MAIN, "Imported route colors",
                                "One color overrides every imported waypoint with the default color.\n"
                                        + "Gradient recolors the imported route with its gradient.\n"
                                        + "Manual preserves colors from the imported payload.",
                                List.of(new Setting.EnumOption("One color", WaypointGroup.GradientMode.STATIC),
                                        new Setting.EnumOption("Gradient", WaypointGroup.GradientMode.AUTO),
                                        new Setting.EnumOption("Manual", WaypointGroup.GradientMode.MANUAL)),
                                (c, d) -> c.importedRouteColorMode(),
                                (c, d, v) -> c.setImportedRouteColorMode((WaypointGroup.GradientMode) v))
                                .aliases("gradient", "import"),
                        Setting.color("importedRouteDefaultColor", MAIN, "Imported color",
                                "Default color applied to imported routes in One color mode.",
                                "Imported Route Colour", "Pick imported route color.",
                                (c, d) -> c.importedRouteDefaultColor(),
                                (c, d, v) -> c.setImportedRouteDefaultColor(rgb(v)))),
                Group.plain("Export defaults",
                        Setting.bool("exportIncludeNames", MAIN, "Include names in default export",
                                null,
                                (c, d) -> c.exportIncludeNames(),
                                (c, d, v) -> c.setExportIncludeNames((Boolean) v)),
                        Setting.bool("exportIncludeColors", MAIN, "Include colors in default export",
                                null,
                                (c, d) -> c.exportIncludeColors(),
                                (c, d, v) -> c.setExportIncludeColors((Boolean) v)),
                        Setting.bool("exportIncludeRadii", MAIN, "Include radii in default export",
                                "Include custom waypoint reach radii in exported share codes.",
                                (c, d) -> c.exportIncludeRadii(),
                                (c, d, v) -> c.setExportIncludeRadii((Boolean) v)),
                        Setting.bool("exportIncludeWaypointFlags", MAIN, "Include waypoint flags",
                                "Include each waypoint's hide and through-wall flags in exported share codes.",
                                (c, d) -> c.exportIncludeWaypointFlags(),
                                (c, d, v) -> c.setExportIncludeWaypointFlags((Boolean) v)),
                        Setting.bool("exportIncludeGroupMeta", MAIN, "Include route metadata",
                                "Include route name, mode, and route-level metadata in exported share codes.",
                                (c, d) -> c.exportIncludeGroupMeta(),
                                (c, d, v) -> c.setExportIncludeGroupMeta((Boolean) v))));
    }

    private static Category system() {
        return Category.of("system", "System",
                Group.plain(null,
                        Setting.bool("irisShaderHudFallback", MAIN, "Experimental Iris HUD fallback",
                                "Draw waypoints with a shader-safe fallback renderer while an Iris shader pack "
                                        + "is active. Try this if waypoints disappear when shaders are on.",
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
                Group.plain("Config & presets",
                        Setting.action(ACTION_CONFIG_CODE, "Config code",
                                "Copy your settings as a WPC: config code, or import one from your clipboard. "
                                        + "Importing overwrites existing settings.")
                                .aliases("share", "wpc", "import", "export"),
                        Setting.action(ACTION_PRESETS, "Presets",
                                "Apply a bundled settings profile. Minimal keeps a quiet HUD, Default restores "
                                        + "defaults, Everything turns on all display features.")
                                .aliases("profile", "minimal", "everything"),
                        Setting.action(ACTION_DISABLE_ALL, "Disable All",
                                "Turn every toggle off. Asks for confirmation before applying."),
                        Setting.action(ACTION_RESET_DEFAULTS, "Reset to Defaults",
                                "Restore every setting to its default value. Click twice within 3 seconds to confirm.")
                                .aliases("reset")),
                Group.plain("Diagnostics",
                        Setting.action(ACTION_PERF_TEST, "Performance stress test",
                                "Runs a 60 second benchmark across 3D waypoints, dungeon secrets, and an adaptive "
                                        + "subwaypoint ramp. The settings overlay hides while it runs; press Esc to cancel. "
                                        + "Your settings are restored when it finishes; "
                                        + "Copy report puts the results on your clipboard.")
                                .aliases("benchmark", "profiler", "fps", "stress", "lag")),
                // Legacy/codec-only fields: no row anywhere, but they stay in the
                // import diff and the parity tests so the codec surface is covered.
                new Group(null, null, null, List.of(
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
