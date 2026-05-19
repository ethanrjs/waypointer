package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.diana.DianaRareMob;
import dev.ethan.waypointer.diana.DianaWarp;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Tabbed settings screen.
 *
 * <p>Direct tabs keep the growing settings surface quickly navigable without
 * returning to one dense wall of checkboxes. Numeric fields use text input with
 * live commit so users can type free-form values without hunting through sliders.
 * Every mutation saves immediately via {@link WaypointerConfig}, so closing the
 * screen never loses work.
 *
 * Uses the shared {@link GuiTokens} footer so "Done" can't collide with anything
 * and the chrome matches the other two screens.
 */
public final class ConfigScreen extends Screen {

    private enum Page {
        VISUALS("Visuals"),
        PERFORMANCE("Performance"),
        ROUTES("Routes"),
        CHAT("Chat"),
        DIANA("Diana"),
        SYSTEM("System"),
        TEMP_WPS("Temp WPs");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private final Screen parent;
    private final WaypointerConfig config;
    private final Page page;
    private final List<DependentControl> dependentControls = new ArrayList<>();
    private String searchQuery;
    private boolean rebuildRequested;
    private boolean restoreSearchFocus;
    private EditBox searchBox;
    private static final List<SearchEntry> SEARCH_INDEX = List.of(
            entry(Page.VISUALS, "Markers", "Waypoint box opacity (0-1)",
                    "Opacity of each waypoint's world-space box."),
            entry(Page.VISUALS, "Markers", "Box style",
                    "Outlined, filled, or filled plus outline waypoint boxes."),
            entry(Page.VISUALS, "Markers", "Outline thickness (px)",
                    "Width of waypoint outlines."),
            entry(Page.VISUALS, "Markers", "Beacon beams",
                    "Optional vertical guide beams for current or all visible waypoints."),
            entry(Page.VISUALS, "Markers", "Beam extends below waypoint",
                    "Start beacon beams at the world's bottom instead of the waypoint Y level."),
            entry(Page.VISUALS, "Markers", "Show completed waypoints",
                    "Keep waypoints visible after they have been reached."),
            entry(Page.VISUALS, "Maintenance", "Disable all features",
                    "Turn off every Waypointer feature toggle after confirmation."),
            entry(Page.VISUALS, "Labels & Tracers", "Show waypoint names",
                    "Floating name labels at each rendered waypoint."),
            entry(Page.VISUALS, "Labels & Tracers", "Show waypoint distances",
                    "Distance text below each waypoint label."),
            entry(Page.VISUALS, "Labels & Tracers", "Waypoint text inherits color",
                    "Waypoint labels use each waypoint's color."),
            entry(Page.VISUALS, "Labels & Tracers", "Show label backdrop",
                    "Dark rectangle behind floating waypoint names."),
            entry(Page.VISUALS, "Labels & Tracers", "Scale text with distance",
                    "Waypoint labels shrink as their anchor gets farther from the camera."),
            entry(Page.VISUALS, "Labels & Tracers", "Label height offset (blocks)",
                    "Extra blocks to push each waypoint label above its marker."),
            entry(Page.VISUALS, "Labels & Tracers", "Show tracers",
                    "Master switch for crosshair tracer lines."),
            entry(Page.VISUALS, "Labels & Tracers", "Tracer opacity (0-1)",
                    "Opacity of the line drawn from the crosshair to the active waypoint."),
            entry(Page.VISUALS, "Labels & Tracers", "Tracer thickness (px)",
                    "Pixel width of the crosshair tracer line."),
            entry(Page.VISUALS, "Labels & Tracers", "Tracer color (hex RRGGBB)",
                    "Fixed tracer color used when tracer color inheritance is off."),
            entry(Page.VISUALS, "Labels & Tracers", "Tracer inherits waypoint color",
                    "Tracer uses each waypoint's rendered color."),

            entry(Page.PERFORMANCE, "Budgets", "Max waypoint labels (0 = unlimited)",
                    "Keep only the nearest N floating labels on screen."),
            entry(Page.PERFORMANCE, "Budgets", "Static marker distance (0 = unlimited)",
                    "Skip static waypoint rendering beyond this many blocks."),
            entry(Page.PERFORMANCE, "Label Performance", "Show waypoint names",
                    "Every visible name submits text to the HUD."),
            entry(Page.PERFORMANCE, "Label Performance", "Show waypoint distances",
                    "Each visible distance is another text draw."),
            entry(Page.PERFORMANCE, "Label Performance", "Show label backdrop",
                    "Remove dark rectangles behind label rows."),

            entry(Page.ROUTES, "Progression", "Default reach radius (blocks)",
                    "How close you must stand to mark a waypoint reached."),
            entry(Page.ROUTES, "Progression", "Enable waypoint skip-ahead mechanic",
                    "Skip waypoints by walking to a later waypoint in a sequenced route."),
            entry(Page.ROUTES, "Progression", "Reset progress when joining a world",
                    "Reset every group's current waypoint on world load or multiplayer join."),
            entry(Page.ROUTES, "Progression", "Restart route after last waypoint",
                    "Wrap progress to the first point after completing the final waypoint."),
            entry(Page.ROUTES, "Progression", "Add new waypoints below player",
                    "Place new player-position waypoints one block below your feet."),
            entry(Page.ROUTES, "Route Display", "Dim sequence context waypoints",
                    "Dim waypoints around the current point in a sequenced route."),
            entry(Page.ROUTES, "Route Display", "Hide tracer on static routes",
                    "Disable waypoint tracer lines on static routes."),
            entry(Page.ROUTES, "Route Display", "Hide waypoints when near",
                    "Hide waypoint boxes, labels, beams, and tracers while near them."),
            entry(Page.ROUTES, "Route Display", "Near hide radius (blocks)",
                    "Distance from the waypoint where near-hide starts."),
            entry(Page.ROUTES, "Route Display", "Hide reached static waypoints",
                    "For static groups, hide each waypoint after it is reached until the cycle resets."),

            entry(Page.CHAT, "Chat Detection", "Chat coord detection",
                    "Scan incoming chat for coordinates and offer waypoint quick-add flows."),
            entry(Page.CHAT, "Chat Detection", "Chat codec detection (imports)",
                    "Detect Waypointer share codes pasted in chat."),
            entry(Page.DIANA, "Burrows", "Diana burrow waypoints",
                    "Detect Diana burrow particles in the Hub and create temporary waypoints."),
        entry(Page.DIANA, "Burrows", "Hide start burrows during active chain",
                "Do not target fresh start burrows while chat says the current Griffin chain is still active."),
        entry(Page.DIANA, "Burrows", "Diana spade debug logging",
                "Writes Ancestral Spade solver milestones and rejection reasons to latest.log."),
        entry(Page.DIANA, "Warp Assist", "Diana warp assist",
                "Suggest and execute enabled Hub warps that save travel distance to the estimate."),
            entry(Page.DIANA, "Warp Assist", "Diana warp savings threshold",
                    "Minimum distance saved before a warp is suggested."),
            entry(Page.DIANA, "Warp Assist", "Enabled Diana warps",
                    "Choose which Hub warp commands the assist may use."),
            entry(Page.DIANA, "Appearance", "Diana estimate color",
                    "Color used for the spade estimate waypoint."),
            entry(Page.DIANA, "Appearance", "Diana start color",
                    "Color used for start burrow waypoints."),
            entry(Page.DIANA, "Appearance", "Diana mob color",
                    "Color used for mob burrow waypoints."),
            entry(Page.DIANA, "Appearance", "Diana treasure color",
                    "Color used for treasure burrow waypoints."),
            entry(Page.DIANA, "Chat Shares", "Diana rare mob waypoints",
                    "Detect Diana rare mob coordinate shares in Hub chat."),
            entry(Page.DIANA, "Chat Shares", "Diana rare mob party sharing",
                    "Share selected Diana mob coordinates in party chat when you dig them up."),
            entry(Page.DIANA, "Chat Shares", "Shared Diana rare mobs",
                    "Choose which Diana mobs Waypointer shares to party chat."),

            entry(Page.SYSTEM, "Maintenance", "Check for updates on startup",
                    "Check GitHub once at startup for a newer Waypointer release."),
            entry(Page.SYSTEM, "Maintenance", "Experimental Iris HUD fallback",
                    "Render waypoints through a HUD fallback when Iris shaders interfere."),

            entry(Page.TEMP_WPS, "Creation", "Auto-add chat temp waypoints",
                    "Immediately create temporary waypoints from detected chat coordinates."),
            entry(Page.TEMP_WPS, "Creation", "Focus mode for temp waypoints",
                    "Hide other active-zone waypoints and trace to the newest temp marker."),
            entry(Page.TEMP_WPS, "Cleanup", "Delete temp waypoints when reached",
                    "Remove temporary waypoints when you enter their reach radius."),
            entry(Page.TEMP_WPS, "Cleanup", "Temp waypoints expire",
                    "Use time-based expiry for newly-created temporary waypoints."),
            entry(Page.TEMP_WPS, "Cleanup", "Temp duration (mins)",
                    "Default lifetime for time-based temporary waypoints.")
    );

    public ConfigScreen(Screen parent, WaypointerConfig config) {
        this(parent, config, Page.VISUALS, "");
    }

    private ConfigScreen(Screen parent, WaypointerConfig config, Page page) {
        this(parent, config, page, "");
    }

    private ConfigScreen(Screen parent, WaypointerConfig config, Page page, String searchQuery) {
        super(Component.literal("Waypointer Settings"));
        this.parent = parent;
        this.config = config;
        this.page = page == null ? Page.VISUALS : page;
        this.searchQuery = searchQuery == null ? "" : searchQuery;
    }

    @Override
    protected void init() {
        dependentControls.clear();

        int navY = PAD_OUTER + font.lineHeight + GAP;
        int top = navY + BTN_H + GAP_SECTION;
        int rowH = 24;
        int colGap = GAP_SECTION;
        int col1 = PAD_OUTER;
        int colW = (width - PAD_OUTER * 2 - colGap) / 2;
        int col2 = col1 + colW + colGap;

        int headerY = top;
        int rowsY = top + 16;

        int searchX = addPageTabs(navY);
        addSearchBox(searchX, navY);

        if (searchActive()) {
            addSearchResultsPage(col1, col2, colW, rowsY, rowH);
        } else switch (page) {
            case VISUALS -> addVisualsPage(col1, col2, colW, rowsY, rowH);
            case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
            case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
            case CHAT -> addChatPage(col1, col2, colW, rowsY, rowH);
            case DIANA -> addDianaPage(col1, col2, colW, rowsY, rowH);
            case SYSTEM -> addSystemPage(col1, col2, colW, rowsY, rowH);
            case TEMP_WPS -> addTempWaypointsPage(col1, col2, colW, rowsY, rowH);
        }

        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> empty = new ArrayList<>();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", -1, this::onClose,
                tip("Return to the previous screen.\nChanges save automatically."));
        GuiTokens.layoutFooter(width, footerY, empty, done, this::addRenderableWidget, font);
        if (!searchActive() && page == Page.VISUALS) {
            addVisualsMaintenanceControls(colW);
        }

        this.leftHeaderX = col1;
        this.rightHeaderX = col2;
        this.sectionHeaderY = headerY;
    }

    @Override
    public void tick() {
        super.tick();
        if (!rebuildRequested) return;

        rebuildRequested = false;
        clearWidgets();
        init();
    }

    private boolean searchActive() {
        return !searchQuery.trim().isEmpty();
    }

    private static SearchEntry entry(Page page, String section, String label, String description) {
        return new SearchEntry(page, section, label, description);
    }

    private static List<SearchEntry> searchEntries(String query) {
        String[] terms = normalizeSearch(query).split("\\s+");
        List<SearchEntry> matches = new ArrayList<>();
        for (SearchEntry entry : SEARCH_INDEX) {
            String haystack = normalizeSearch(entry.page().label + " "
                    + entry.section() + " "
                    + entry.label() + " "
                    + entry.description());
            boolean matched = true;
            for (String term : terms) {
                if (term.isEmpty()) continue;
                if (!haystack.contains(term)) {
                    matched = false;
                    break;
                }
            }
            if (matched) matches.add(entry);
        }
        return matches;
    }

    private static String normalizeSearch(String text) {
        return (text == null ? "" : text)
                .toLowerCase(Locale.ROOT)
                .replace("wps", "waypoints")
                .replace("wp", "waypoint");
    }

    private int addPageTabs(int y) {
        int x = PAD_OUTER;
        for (Page target : Page.values()) {
            int tabW = Math.max(64, font.width(target.label) + 18);
            Button btn = Button.builder(Component.literal(target.label), b -> {
                if (searchActive() || target != page) {
                    minecraft.setScreen(new ConfigScreen(parent, config, target, ""));
                }
            }).bounds(x, y, tabW, BTN_H).build();
            btn.active = searchActive() || target != page;
            addRenderableWidget(btn);
            x += tabW + GAP;
        }
        return x;
    }

    private void addSearchBox(int x, int y) {
        int available = width - PAD_OUTER - x;
        if (available < 86) return;

        int searchW = Math.min(180, available);
        searchBox = new EditBox(font, x, y, searchW, BTN_H, Component.literal("Search settings"));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchQuery);
        searchBox.setHint(Component.literal("Search settings"));
        searchBox.setResponder(v -> {
            String next = v == null ? "" : v;
            if (Objects.equals(next, searchQuery)) return;
            searchQuery = next;
            rebuildRequested = true;
            restoreSearchFocus = true;
        });
        addRenderableWidget(searchBox);
        if (restoreSearchFocus) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            restoreSearchFocus = false;
        }
    }

    private void addVisualsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Markers";
        rightHeader = "Labels";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Waypoint box opacity (0-1)",
                config.beaconOpacity(), config::setBeaconOpacity,
                "Opacity of each waypoint's world-space box. 0 hides the volume,\n"
              + "1 is the strongest fill; labels can still show separately.");
        y += rowH;
        addBoxStyleRow(col1, y, colW);
        y += rowH;
        addNumberRow(col1, y, colW, "Outline thickness (px)",
                config.waypointOutlineThickness(), config::setWaypointOutlineThickness,
                "Width of waypoint outlines.");
        y += rowH + GAP;
        addSectionHeader(col1, y, "Beacon Beams", colW);
        y += font.lineHeight + GAP;
        addBeamModeRow(col1, y, colW);
        y += rowH;
        addBoolRow(col1, y, "Beam extends below waypoint",
                config.beaconBeamExtendsBelowWaypoint(), config::setBeaconBeamExtendsBelowWaypoint,
                () -> config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF,
                "When beacon beams are enabled, start each beam at the world's bottom\n"
              + "instead of at the waypoint's Y level. Useful for finding targets\n"
              + "above or below you through terrain.");
        y += rowH;
        addBoolRow(col1, y, "Show completed waypoints", config.showCompleted(), config::setShowCompleted,
                "When on, waypoints you have already reached still draw (usually faded).\n"
              + "When off, completed stops disappear from the world HUD.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names", config.showWaypointNames(), config::setShowWaypointNames,
                "Floating name labels at each rendered waypoint. Off keeps boxes without text.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint distances",
                config.showWaypointDistances(), config::setShowWaypointDistances,
                "Distance text below each waypoint label. Can stay on even when names\n"
              + "are hidden, giving compact distance-only markers.");
        y2 += rowH;
        addBoolRow(col2, y2, "Waypoint text inherits color",
                config.matchWaypointTextToWaypointColor(), config::setMatchWaypointTextToWaypointColor,
                config::showWaypointNames,
                "When on, each floating waypoint name uses that waypoint's color.\n"
              + "When off, names stay white for maximum contrast.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show label backdrop", config.showLabelBackdrop(), config::setShowLabelBackdrop,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                "Draws a dark rectangle behind floating waypoint names for readability.\n"
              + "Turn off for a lighter HUD when labels stack in busy areas.");
        y2 += rowH;
        addBoolRow(col2, y2, "Scale text with distance",
                config.scaleWaypointTextWithDistance(), config::setScaleWaypointTextWithDistance,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                "When on, waypoint labels use camera-depth scaling like a small\n"
              + "world-space label, while still drawing through the 2D HUD path.\n"
              + "Off preserves fixed-size labels.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Label height offset (blocks)",
                config.labelHeightOffset(), config::setLabelHeightOffset,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                "Extra blocks to push each waypoint label above its marker. 0 keeps the\n"
              + "default placement. Use large values if distant labels still cover the\n"
              + "box; finite numbers only, no arbitrary clamp.");
        y2 += rowH + GAP;
        addSectionHeader(col2, y2, "Tracers", colW);
        y2 += font.lineHeight + GAP;
        addBoolRow(col2, y2, "Show tracers", config.showTracer(), config::setShowTracer,
                "Master switch for crosshair tracers. When off, no tracer lines are drawn\n"
              + "for any group (other waypoint rendering is unchanged).");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Tracer opacity (0-1)",
                config.tracerOpacity(), config::setTracerOpacity,
                config::showTracer,
                "Opacity of the line drawn from the crosshair to the active waypoint.\n"
              + "0 is fully transparent, 1 is solid.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Tracer thickness (px)",
                config.tracerThickness(), config::setTracerThickness,
                config::showTracer,
                "Pixel width of the crosshair tracer line. Values are clamped\n"
              + "from 1 to 12 so it stays visible without flooding the screen.");
        y2 += rowH;
        addTracerColorRow(col2, y2, colW,
                () -> config.showTracer() && !config.matchTracerToWaypointColor(),
                "Fixed tracer color as hex RRGGBB (e.g. 4FE05A). Only used when\n"
              + "\"Tracer inherits waypoint color\" is off.");
        y2 += rowH;
        addBoolRow(col2, y2, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                config::showTracer,
                "When on, the tracer uses each waypoint's rendered color (gradient routes\n"
              + "shift hue as you progress). When off, every tracer uses the hex color above.");
    }

    private void addPerformancePage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Budgets";
        rightHeader = "Label Performance";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Max waypoint labels (0 = unlimited)",
                config.maxWaypointLabels(),
                this::setMaxWaypointLabels,
                performanceTooltip(
                        "Keeps only the nearest N floating labels on screen.\n"
                      + "Boxes and tracers can still render normally.",
                        Impact.HIGH));
        y += rowH;
        addNumberRow(col1, y, colW, "Static marker distance (0 = unlimited)",
                config.maxStaticWaypointRenderDistance(),
                config::setMaxStaticWaypointRenderDistance,
                performanceTooltip(
                        "Skips boxes, beams, and labels for static waypoints farther\n"
                      + "than this many blocks. Sequence targets stay uncapped.",
                        Impact.HIGH));

        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names",
                config.showWaypointNames(), config::setShowWaypointNames,
                performanceTooltip(
                        "Every visible name submits text to the HUD, which adds up\n"
                      + "quickly in dense routes.",
                        Impact.HIGH));
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint distances",
                config.showWaypointDistances(), config::setShowWaypointDistances,
                performanceTooltip(
                        "Each visible distance is another text draw. Useful, but busy\n"
                      + "routes can make it expensive.",
                        Impact.MEDIUM));
        y2 += rowH;
        addBoolRow(col2, y2, "Show label backdrop",
                config.showLabelBackdrop(), config::setShowLabelBackdrop,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                performanceTooltip(
                        "Removes the dark rectangle drawn behind each label row.\n"
                      + "Mostly helps when lots of labels are visible.",
                        Impact.LOW));
    }

    private void addRoutesPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Progression";
        rightHeader = "Route Display";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Default reach radius (blocks)",
                config.defaultReachRadius(), config::setDefaultReachRadius,
                "How close you must stand (in blocks) to mark the current waypoint reached,\n"
              + "when a waypoint does not set its own radius. Group default radius can\n"
              + "override this in the group editor.");
        y += rowH;
        addBoolRow(col1, y, "Enable waypoint skip-ahead mechanic",
                config.skipAheadMechanicEnabled(), config::setSkipAheadMechanicEnabled,
                "Skip waypoints by walking to a waypoint further in a sequenced route.");
        y += rowH;
        addBoolRow(col1, y, "Reset progress when joining a world",
                config.resetProgressOnWorldJoin(), config::setResetProgressOnWorldJoin,
                "On world load or multiplayer join, every group's \"current\" waypoint resets\n"
              + "to the start. Off keeps saved progress across reconnects.");
        y += rowH;
        addBoolRow(col1, y, "Restart route after last waypoint",
                config.restartRouteWhenComplete(), config::setRestartRouteWhenComplete,
                "After you complete the final waypoint, progress wraps to the first point\n"
              + "so loop and farm routes do not sit in a \"finished\" state.");
        y += rowH;
        addBoolRow(col1, y, "Add new waypoints below player",
                config.placeNewWaypointsBelowPlayer(), config::setPlaceNewWaypointsBelowPlayer,
                "When adding at your position, place the marker one block below your feet.\n"
              + "Turn off to use your exact standing block. Typed coordinates stay exact.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Dim sequence context waypoints",
                config.dimSequenceContextWaypoints(), config::setDimSequenceContextWaypoints,
                "Dim waypoints surrounding your current one in a sequenced route.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide tracer on static routes",
                config.hideTracerOnStaticRoutes(), config::setHideTracerOnStaticRoutes,
                config::showTracer,
                "Disable the waypoint tracer on static routes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide waypoints when near",
                config.hideWaypointsNearPlayer(), config::setHideWaypointsNearPlayer,
                "When on, waypoint boxes, labels, beams, and tracers hide while\n"
              + "you stand near the waypoint, then reappear after you move away.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Near hide radius (blocks)",
                config.hideWaypointsNearRadius(), config::setHideWaypointsNearRadius,
                config::hideWaypointsNearPlayer,
                "Distance from the waypoint where near-hide starts. Default is 5 blocks.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide reached static waypoints",
                config.hideReachedStaticWaypointsUntilCycleComplete(),
                config::setHideReachedStaticWaypointsUntilCycleComplete,
                "For STATIC groups, hide each waypoint when you enter its reach radius.\n"
              + "After every waypoint in the group has been reached, all of them show again.");
    }

    private void addChatPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Chat Detection";
        rightHeader = "";

        int y = rowsY;
        addBoolRow(col1, y, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection,
                "Scans incoming chat for coordinates and can offer quick-add flows for\n"
              + "temporary or permanent waypoints (no effect when chat has no coords).");
        y += rowH;
        addBoolRow(col1, y, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection,
                "Detects Waypointer share codes pasted in chat so you can import routes\n"
              + "without opening the main menu.");

    }

    private void addDianaPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Burrows & Travel";
        rightHeader = "Colors & Shares";

        int y = rowsY;
        addBoolRow(col1, y, "Diana burrow waypoints",
                config.dianaBurrowWaypoints(), config::setDianaBurrowWaypoints,
                "Detects real Diana burrow particles in the Hub and creates temporary\n"
                + "waypoints for confirmed burrows plus conservative Ancestral Spade\n"
                + "spade-curve estimates.");
        y += rowH;
        addBoolRow(col1, y, "Hide start burrows during active chain",
                config.dianaHideStartBurrowsUntilChainComplete(),
                config::setDianaHideStartBurrowsUntilChainComplete,
                config::dianaBurrowWaypoints,
                "When on, start burrow waypoints are withheld while chat progress says\n"
              + "your current Griffin chain is incomplete, so the tracer cannot pick\n"
              + "a fresh start before the chain is done.");
        y += rowH;
        addBoolRow(col1, y, "Diana spade debug logging",
                config.dianaSpadeDebugLogging(), config::setDianaSpadeDebugLogging,
                config::dianaBurrowWaypoints,
                "Writes focused Ancestral Spade solver milestones to latest.log.\n"
              + "Use when estimates stop appearing or seem inconsistent.");
        y += rowH;
        addBoolRow(col1, y, "Diana warp assist",
                config.dianaWarpAssist(), config::setDianaWarpAssist,
                config::dianaBurrowWaypoints,
                "Suggests and executes enabled Hub warps when they save travel\n"
                + "distance to the current Diana estimate waypoint.");
        y += rowH;
        addNumberRow(col1, y, colW, "Diana warp savings threshold",
                config.dianaWarpMinSavings(), config::setDianaWarpMinSavings,
                () -> config.dianaBurrowWaypoints() && config.dianaWarpAssist(),
                "Minimum distance a warp must save before it is suggested.\n"
                + "Default is 45 blocks.");
        y += rowH;
        addDianaWarpsRow(col1, y, colW);

        int y2 = rowsY;
        addColorRow(col2, y2, colW, "Diana estimate color",
                config.dianaEstimateWaypointColor(), config::setDianaEstimateWaypointColor,
                config::dianaBurrowWaypoints,
                "Color used for the spade estimate waypoint.");
        y2 += rowH;
        addColorRow(col2, y2, colW, "Diana start color",
                config.dianaStartBurrowColor(), config::setDianaStartBurrowColor,
                config::dianaBurrowWaypoints,
                "Color used for start burrow waypoints.");
        y2 += rowH;
        addColorRow(col2, y2, colW, "Diana mob color",
                config.dianaMobBurrowColor(), config::setDianaMobBurrowColor,
                config::dianaBurrowWaypoints,
                "Color used for mob burrow waypoints.");
        y2 += rowH;
        addColorRow(col2, y2, colW, "Diana treasure color",
                config.dianaTreasureBurrowColor(), config::setDianaTreasureBurrowColor,
                config::dianaBurrowWaypoints,
                "Color used for treasure burrow waypoints.");
        y2 += rowH;
        addBoolRow(col2, y2, "Diana rare mob waypoints",
                config.dianaRareMobWaypoints(), config::setDianaRareMobWaypoints,
                "Detects SkyHanni-style Diana rare mob coordinate shares in Hub chat\n"
                + "and creates temporary waypoints for them.");
        y2 += rowH;
        addBoolRow(col2, y2, "Diana rare mob party sharing",
                config.dianaRareMobPartySharing(), config::setDianaRareMobPartySharing,
                "Shares selected Diana mob coordinates to party chat when you dig\n"
                + "one up. Uses /pc so it stays in party chat.");
        y2 += rowH;
        addDianaRareMobSharesRow(col2, y2, colW);
    }

    private void addSystemPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "";
        rightHeader = "Maintenance";

        int y2 = rowsY;
        addBoolRow(col2, y2, "Check for updates on startup",
                config.checkForUpdates(), config::setCheckForUpdates,
                "On client start, checks GitHub once for a newer Waypointer release.\n"
              + "Off avoids any update HTTP request.");
        y2 += rowH;
        addBoolRow(col2, y2, "Experimental Iris HUD fallback",
                config.irisShaderHudFallback(), config::setIrisShaderHudFallback,
                "Allows tracers and waypoints to render with iris shaders enabled.\n"
              + "The appearance will be degraded.");
    }

    private void addTempWaypointsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Creation";
        rightHeader = "Cleanup";

        int y = rowsY;
        addBoolRow(col1, y, "Auto-add chat temp waypoints",
                config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                config::chatCoordDetection,
                "When chat coord detection finds a coordinate, immediately creates a\n"
              + "temporary waypoint using your default expiry. Off keeps click-to-add only.");
        y += rowH;
        addBoolRow(col1, y, "Focus mode for temp waypoints",
                config.focusTempWaypoints(), config::setFocusTempWaypoints,
                "When on, adding a temporary waypoint hides other waypoints in the\n"
              + "active zone and forces a tracer to the temp until you leave the server.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Delete temp waypoints when reached",
                config.deleteTempWaypointsWhenReached(), config::setDeleteTempWaypointsWhenReached,
                "When on, temporary waypoints disappear as soon as you enter their\n"
              + "reach radius. Time-based temporary waypoints can still expire first.");
        y2 += rowH;
        addBoolRow(col2, y2, "Temp waypoints expire",
                config.tempWaypointsExpireByDefault(), config::setTempWaypointsExpireByDefault,
                "When on, newly-created temp waypoints use TIME mode by default.\n"
              + "When off, they last until you leave the server unless changed in\n"
              + "the Add Temp dialog.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Temp duration (mins)",
                config.tempDefaultDurationMin(), this::setTempDefaultDuration,
                config::tempWaypointsExpireByDefault,
                "Default lifetime for TIME-mode temporary waypoints.");
    }

    private void addSearchResultsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Search results";
        rightHeader = "";

        List<SearchEntry> matches = searchEntries(searchQuery);
        if (matches.isEmpty()) {
            addRenderableOnly(new LabelWidget(col1, rowsY + 6,
                    "No matching settings.", colW * 2 + GAP_SECTION, () -> true));
            return;
        }

        int bottom = height - FOOTER_H - GAP;
        int[] y = { rowsY, rowsY };
        int shown = 0;
        for (SearchEntry entry : matches) {
            int col = y[0] <= y[1] ? 0 : 1;
            int x = col == 0 ? col1 : col2;
            if (y[col] + BTN_H > bottom) break;

            addSearchResultControl(x, y[col], colW, entry);
            y[col] += rowH;
            shown++;
        }

        if (shown < matches.size()) {
            int col = y[0] <= y[1] ? 0 : 1;
            int x = col == 0 ? col1 : col2;
            if (y[col] + font.lineHeight <= bottom) {
                addRenderableOnly(new LabelWidget(x, y[col] + 6,
                        (matches.size() - shown) + " more matches. Narrow the search.",
                        colW, () -> true));
            }
        }
    }

    private void addSearchResultControl(int x, int y, int colW, SearchEntry entry) {
        switch (entry.label()) {
            case "Waypoint box opacity (0-1)" -> addNumberRow(x, y, colW, "Waypoint box opacity (0-1)",
                    config.beaconOpacity(), config::setBeaconOpacity,
                    "Opacity of each waypoint's world-space box. 0 hides the volume,\n"
                  + "1 is the strongest fill; labels can still show separately.");
            case "Box style" -> addBoxStyleRow(x, y, colW);
            case "Outline thickness (px)" -> addNumberRow(x, y, colW, "Outline thickness (px)",
                    config.waypointOutlineThickness(), config::setWaypointOutlineThickness,
                    "Width of waypoint outlines.");
            case "Beacon beams" -> addBeamModeRow(x, y, colW);
            case "Beam extends below waypoint" -> addBoolRow(x, y, "Beam extends below waypoint",
                    config.beaconBeamExtendsBelowWaypoint(), config::setBeaconBeamExtendsBelowWaypoint,
                    () -> config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF,
                    "When beacon beams are enabled, start each beam at the world's bottom\n"
                  + "instead of at the waypoint's Y level. Useful for finding targets\n"
                  + "above or below you through terrain.");
            case "Show completed waypoints" -> addBoolRow(x, y, "Show completed waypoints",
                    config.showCompleted(), config::setShowCompleted,
                    "When on, waypoints you have already reached still draw (usually faded).\n"
                  + "When off, completed stops disappear from the world HUD.");
            case "Disable all features" -> addDisableAllFeaturesButton(x, y, colW);
            case "Show waypoint names" -> addBoolRow(x, y, "Show waypoint names",
                    config.showWaypointNames(), config::setShowWaypointNames,
                    entry.description());
            case "Show waypoint distances" -> addBoolRow(x, y, "Show waypoint distances",
                    config.showWaypointDistances(), config::setShowWaypointDistances,
                    entry.description());
            case "Waypoint text inherits color" -> addBoolRow(x, y, "Waypoint text inherits color",
                    config.matchWaypointTextToWaypointColor(), config::setMatchWaypointTextToWaypointColor,
                    config::showWaypointNames,
                    "When on, each floating waypoint name uses that waypoint's color.\n"
                  + "When off, names stay white for maximum contrast.");
            case "Show label backdrop" -> addBoolRow(x, y, "Show label backdrop",
                    config.showLabelBackdrop(), config::setShowLabelBackdrop,
                    () -> config.showWaypointNames() || config.showWaypointDistances(),
                    entry.description());
            case "Scale text with distance" -> addBoolRow(x, y, "Scale text with distance",
                    config.scaleWaypointTextWithDistance(), config::setScaleWaypointTextWithDistance,
                    () -> config.showWaypointNames() || config.showWaypointDistances(),
                    "When on, waypoint labels use camera-depth scaling like a small\n"
                  + "world-space label, while still drawing through the 2D HUD path.\n"
                  + "Off preserves fixed-size labels.");
            case "Label height offset (blocks)" -> addNumberRow(x, y, colW, "Label height offset (blocks)",
                    config.labelHeightOffset(), config::setLabelHeightOffset,
                    () -> config.showWaypointNames() || config.showWaypointDistances(),
                    "Extra blocks to push each waypoint label above its marker. 0 keeps the\n"
                  + "default placement. Use large values if distant labels still cover the\n"
                  + "box; finite numbers only, no arbitrary clamp.");
            case "Show tracers" -> addBoolRow(x, y, "Show tracers",
                    config.showTracer(), config::setShowTracer,
                    "Master switch for crosshair tracers. When off, no tracer lines are drawn\n"
                  + "for any group (other waypoint rendering is unchanged).");
            case "Tracer opacity (0-1)" -> addNumberRow(x, y, colW, "Tracer opacity (0-1)",
                    config.tracerOpacity(), config::setTracerOpacity,
                    config::showTracer,
                    "Opacity of the line drawn from the crosshair to the active waypoint.\n"
                  + "0 is fully transparent, 1 is solid.");
            case "Tracer thickness (px)" -> addNumberRow(x, y, colW, "Tracer thickness (px)",
                    config.tracerThickness(), config::setTracerThickness,
                    config::showTracer,
                    "Pixel width of the crosshair tracer line. Values are clamped\n"
                  + "from 1 to 12 so it stays visible without flooding the screen.");
            case "Tracer color (hex RRGGBB)" -> addTracerColorRow(x, y, colW,
                    () -> config.showTracer() && !config.matchTracerToWaypointColor(),
                    "Fixed tracer color as hex RRGGBB (e.g. 4FE05A). Only used when\n"
                  + "\"Tracer inherits waypoint color\" is off.");
            case "Tracer inherits waypoint color" -> addBoolRow(x, y, "Tracer inherits waypoint color",
                    config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                    config::showTracer,
                    "When on, the tracer uses each waypoint's rendered color (gradient routes\n"
                  + "shift hue as you progress). When off, every tracer uses the hex color above.");
            case "Max waypoint labels (0 = unlimited)" -> addNumberRow(x, y, colW,
                    "Max waypoint labels (0 = unlimited)",
                    config.maxWaypointLabels(), this::setMaxWaypointLabels,
                    performanceTooltip(entry.description(), Impact.HIGH));
            case "Static marker distance (0 = unlimited)" -> addNumberRow(x, y, colW,
                    "Static marker distance (0 = unlimited)",
                    config.maxStaticWaypointRenderDistance(), config::setMaxStaticWaypointRenderDistance,
                    performanceTooltip(entry.description(), Impact.HIGH));
            case "Default reach radius (blocks)" -> addNumberRow(x, y, colW, "Default reach radius (blocks)",
                    config.defaultReachRadius(), config::setDefaultReachRadius,
                    "How close you must stand (in blocks) to mark the current waypoint reached,\n"
                  + "when a waypoint does not set its own radius. Group default radius can\n"
                  + "override this in the group editor.");
            case "Enable waypoint skip-ahead mechanic" -> addBoolRow(x, y, "Enable waypoint skip-ahead mechanic",
                    config.skipAheadMechanicEnabled(), config::setSkipAheadMechanicEnabled,
                    entry.description());
            case "Reset progress when joining a world" -> addBoolRow(x, y, "Reset progress when joining a world",
                    config.resetProgressOnWorldJoin(), config::setResetProgressOnWorldJoin,
                    "On world load or multiplayer join, every group's \"current\" waypoint resets\n"
                  + "to the start. Off keeps saved progress across reconnects.");
            case "Restart route after last waypoint" -> addBoolRow(x, y, "Restart route after last waypoint",
                    config.restartRouteWhenComplete(), config::setRestartRouteWhenComplete,
                    "After you complete the final waypoint, progress wraps to the first point\n"
                  + "so loop and farm routes do not sit in a \"finished\" state.");
            case "Add new waypoints below player" -> addBoolRow(x, y, "Add new waypoints below player",
                    config.placeNewWaypointsBelowPlayer(), config::setPlaceNewWaypointsBelowPlayer,
                    "When adding at your position, place the marker one block below your feet.\n"
                  + "Turn off to use your exact standing block. Typed coordinates stay exact.");
            case "Dim sequence context waypoints" -> addBoolRow(x, y, "Dim sequence context waypoints",
                    config.dimSequenceContextWaypoints(), config::setDimSequenceContextWaypoints,
                    entry.description());
            case "Hide tracer on static routes" -> addBoolRow(x, y, "Hide tracer on static routes",
                    config.hideTracerOnStaticRoutes(), config::setHideTracerOnStaticRoutes,
                    config::showTracer,
                    entry.description());
            case "Hide waypoints when near" -> addBoolRow(x, y, "Hide waypoints when near",
                    config.hideWaypointsNearPlayer(), config::setHideWaypointsNearPlayer,
                    "When on, waypoint boxes, labels, beams, and tracers hide while\n"
                  + "you stand near the waypoint, then reappear after you move away.");
            case "Near hide radius (blocks)" -> addNumberRow(x, y, colW, "Near hide radius (blocks)",
                    config.hideWaypointsNearRadius(), config::setHideWaypointsNearRadius,
                    config::hideWaypointsNearPlayer,
                    entry.description());
            case "Hide reached static waypoints" -> addBoolRow(x, y, "Hide reached static waypoints",
                    config.hideReachedStaticWaypointsUntilCycleComplete(),
                    config::setHideReachedStaticWaypointsUntilCycleComplete,
                    "For STATIC groups, hide each waypoint when you enter its reach radius.\n"
                  + "After every waypoint in the group has been reached, all of them show again.");
            case "Chat coord detection" -> addBoolRow(x, y, "Chat coord detection",
                    config.chatCoordDetection(), config::setChatCoordDetection,
                    "Scans incoming chat for coordinates and can offer quick-add flows for\n"
                  + "temporary or permanent waypoints (no effect when chat has no coords).");
            case "Chat codec detection (imports)" -> addBoolRow(x, y, "Chat codec detection (imports)",
                    config.chatCodecDetection(), config::setChatCodecDetection,
                    "Detects Waypointer share codes pasted in chat so you can import routes\n"
                  + "without opening the main menu.");
            case "Diana burrow waypoints" -> addBoolRow(x, y, "Diana burrow waypoints",
                    config.dianaBurrowWaypoints(), config::setDianaBurrowWaypoints,
                    "Detects real Diana burrow particles in the Hub and creates temporary\n"
                  + "waypoints for confirmed burrows plus conservative Ancestral Spade\n"
                  + "arrow guesses.");
            case "Hide start burrows during active chain" -> addBoolRow(x, y,
                    "Hide start burrows during active chain",
                    config.dianaHideStartBurrowsUntilChainComplete(),
                    config::setDianaHideStartBurrowsUntilChainComplete,
                    config::dianaBurrowWaypoints,
                    "When on, start burrow waypoints are withheld while chat progress says\n"
                  + "your current Griffin chain is incomplete, so the tracer cannot pick\n"
                  + "a fresh start before the chain is done.");
            case "Diana spade debug logging" -> addBoolRow(x, y, "Diana spade debug logging",
                    config.dianaSpadeDebugLogging(), config::setDianaSpadeDebugLogging,
                    config::dianaBurrowWaypoints,
                    entry.description());
            case "Diana estimate color" -> addColorRow(x, y, colW, "Diana estimate color",
                    config.dianaEstimateWaypointColor(), config::setDianaEstimateWaypointColor,
                    config::dianaBurrowWaypoints,
                    entry.description());
            case "Diana start color" -> addColorRow(x, y, colW, "Diana start color",
                    config.dianaStartBurrowColor(), config::setDianaStartBurrowColor,
                    config::dianaBurrowWaypoints,
                    entry.description());
            case "Diana mob color" -> addColorRow(x, y, colW, "Diana mob color",
                    config.dianaMobBurrowColor(), config::setDianaMobBurrowColor,
                    config::dianaBurrowWaypoints,
                    entry.description());
            case "Diana treasure color" -> addColorRow(x, y, colW, "Diana treasure color",
                    config.dianaTreasureBurrowColor(), config::setDianaTreasureBurrowColor,
                    config::dianaBurrowWaypoints,
                    entry.description());
            case "Diana warp assist" -> addBoolRow(x, y, "Diana warp assist",
                    config.dianaWarpAssist(), config::setDianaWarpAssist,
                    config::dianaBurrowWaypoints,
                    entry.description());
            case "Diana warp savings threshold" -> addNumberRow(x, y, colW,
                    "Diana warp savings threshold",
                    config.dianaWarpMinSavings(), config::setDianaWarpMinSavings,
                    () -> config.dianaBurrowWaypoints() && config.dianaWarpAssist(),
                    entry.description());
            case "Enabled Diana warps" -> addDianaWarpsRow(x, y, colW);
            case "Diana rare mob waypoints" -> addBoolRow(x, y, "Diana rare mob waypoints",
                    config.dianaRareMobWaypoints(), config::setDianaRareMobWaypoints,
                    entry.description());
            case "Diana rare mob party sharing" -> addBoolRow(x, y, "Diana rare mob party sharing",
                    config.dianaRareMobPartySharing(), config::setDianaRareMobPartySharing,
                    entry.description());
            case "Shared Diana rare mobs" -> addDianaRareMobSharesRow(x, y, colW);
            case "Check for updates on startup" -> addBoolRow(x, y, "Check for updates on startup",
                    config.checkForUpdates(), config::setCheckForUpdates,
                    "On client start, checks GitHub once for a newer Waypointer release.\n"
                  + "Off avoids any update HTTP request.");
            case "Experimental Iris HUD fallback" -> addBoolRow(x, y, "Experimental Iris HUD fallback",
                    config.irisShaderHudFallback(), config::setIrisShaderHudFallback,
                    entry.description());
            case "Auto-add chat temp waypoints" -> addBoolRow(x, y, "Auto-add chat temp waypoints",
                    config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                    config::chatCoordDetection,
                    "When chat coord detection finds a coordinate, immediately creates a\n"
                  + "temporary waypoint using your default expiry. Off keeps click-to-add only.");
            case "Focus mode for temp waypoints" -> addBoolRow(x, y, "Focus mode for temp waypoints",
                    config.focusTempWaypoints(), config::setFocusTempWaypoints,
                    "When on, adding a temporary waypoint hides other waypoints in the\n"
                  + "active zone and forces a tracer to the temp until you leave the server.");
            case "Delete temp waypoints when reached" -> addBoolRow(x, y,
                    "Delete temp waypoints when reached",
                    config.deleteTempWaypointsWhenReached(), config::setDeleteTempWaypointsWhenReached,
                    "When on, temporary waypoints disappear as soon as you enter their\n"
                  + "reach radius. Time-based temporary waypoints can still expire first.");
            case "Temp waypoints expire" -> addBoolRow(x, y, "Temp waypoints expire",
                    config.tempWaypointsExpireByDefault(), config::setTempWaypointsExpireByDefault,
                    "When on, newly-created temp waypoints use TIME mode by default.\n"
                  + "When off, they last until you leave the server unless changed in\n"
                  + "the Add Temp dialog.");
            case "Temp duration (mins)" -> addNumberRow(x, y, colW, "Temp duration (mins)",
                    config.tempDefaultDurationMin(), this::setTempDefaultDuration,
                    config::tempWaypointsExpireByDefault,
                    entry.description());
            default -> addRenderableOnly(new LabelWidget(x, y + 6, entry.label(), colW, () -> true));
        }
    }

    private void addDisableAllFeaturesButton(int x, int y, int colW) {
        Button btn = Button.builder(Component.literal("Disable all features"), b -> confirmDisableAllFeatures())
                .bounds(x, y, Math.min(180, colW), BTN_H)
                .tooltip(tip("Turn off every Waypointer feature toggle.\nShows a confirmation first."))
                .build();
        addRenderableWidget(btn);
    }

    private void addVisualsMaintenanceControls(int colW) {
        int buttonY = height - FOOTER_H - BTN_H - GAP;
        int headerY = buttonY - font.lineHeight - GAP;
        addSectionHeader(PAD_OUTER, headerY, "Maintenance", colW);
        addDisableAllFeaturesButton(PAD_OUTER, buttonY, colW);
    }

    private void addSectionHeader(int x, int y, String text, int colW) {
        addRenderableOnly(new SectionHeaderWidget(x, y, text, colW));
    }

    private void addDianaWarpsRow(int x, int y, int colW) {
        BooleanSupplier enabled = () -> config.dianaBurrowWaypoints() && config.dianaWarpAssist();
        Button btn = Button.builder(Component.literal(dianaWarpsButtonLabel()),
                        b -> minecraft.setScreen(new DianaWarpSettingsScreen(this, config)))
                .bounds(x, y, Math.min(190, colW), BTN_H)
                .tooltip(tip("Choose warp commands for Diana assist.\nDark Auction and Crypt start off."))
                .build();
        trackDependent(btn, enabled);
        addRenderableWidget(btn);
    }

    private String dianaWarpsButtonLabel() {
        return "Enabled Diana warps: " + config.dianaEnabledWarpCount()
                + "/" + DianaWarp.values().length;
    }

    private void addDianaRareMobSharesRow(int x, int y, int colW) {
        BooleanSupplier enabled = config::dianaRareMobPartySharing;
        Button btn = Button.builder(Component.literal(dianaRareMobSharesButtonLabel()),
                        b -> minecraft.setScreen(new DianaRareMobShareSettingsScreen(this, config)))
                .bounds(x, y, Math.min(210, colW), BTN_H)
                .tooltip(tip("Choose which Diana mobs Waypointer shares to party chat."))
                .build();
        trackDependent(btn, enabled);
        addRenderableWidget(btn);
    }

    private String dianaRareMobSharesButtonLabel() {
        return "Shared Diana mobs: " + config.dianaRareMobShareEnabledCount()
                + "/" + DianaRareMob.values().length;
    }

    private void confirmDisableAllFeatures() {
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                config.disableAllFeatures();
            }
            minecraft.setScreen(new ConfigScreen(parent, config, page));
        }, Component.literal("Disable all Waypointer features?"),
                Component.literal("This turns off every feature toggle in settings. You can re-enable features later."),
                Component.literal("Disable all"),
                Component.literal("Cancel")));
    }

    private int leftHeaderX;
    private int rightHeaderX;
    private int sectionHeaderY;
    private String leftHeader = "";
    private String rightHeader = "";

    private interface DoubleSetter { void accept(double value); }
    private interface IntSetter { void accept(int value); }
    private interface StringSetter { void accept(String value); }

    private enum Impact {
        HIGH("HIGH", ChatFormatting.RED),
        MEDIUM("MEDIUM", ChatFormatting.GOLD),
        LOW("LOW", ChatFormatting.GREEN);

        final String label;
        final ChatFormatting color;

        Impact(String label, ChatFormatting color) {
            this.label = label;
            this.color = color;
        }
    }

    private static Component performanceTooltip(String description, Impact impact) {
        MutableComponent out = Component.literal(wrapTooltip(description)).withStyle(ChatFormatting.GRAY);
        out.append(Component.literal("\n\nImpact: ").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal(impact.label).withStyle(impact.color));
        return out;
    }

    private static Tooltip tip(String text) {
        return Tooltip.create(tooltipText(text));
    }

    private static Component tooltipText(String text) {
        return Component.literal(wrapTooltip(text)).withStyle(ChatFormatting.GRAY);
    }

    private static String wrapTooltip(String text) {
        String cleaned = (text == null ? "" : text)
                .replace('—', ':')
                .replace('–', '-')
                .replace("â€”", ":")
                .replace(" -- ", ": ")
                .trim();
        StringBuilder out = new StringBuilder();
        for (String paragraph : cleaned.split("\\n", -1)) {
            appendWrapped(out, paragraph.trim(), 30);
        }
        return out.toString();
    }

    private static void appendWrapped(StringBuilder out, String text, int maxChars) {
        if (text.isEmpty()) {
            if (!out.isEmpty()) out.append('\n');
            return;
        }
        String[] words = text.split("\\s+");
        int lineLen = 0;
        for (String word : words) {
            if (lineLen > 0 && lineLen + 1 + word.length() > maxChars) {
                out.append('\n');
                lineLen = 0;
            } else if (!out.isEmpty() && lineLen == 0) {
                out.append('\n');
            }
            if (lineLen > 0) {
                out.append(' ');
                lineLen++;
            }
            out.append(word);
            lineLen += word.length();
        }
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, tooltipText(tooltip));
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              Component tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, () -> true, tooltip);
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              BooleanSupplier enabled, String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, enabled, tooltipText(tooltip));
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              BooleanSupplier enabled, Component tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, enabled, tooltip);
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              boolean hex, String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, hex, () -> true, tooltipText(tooltip));
    }

    /**
     * Label + inline editor. The hex flag switches the parse path so colors round-trip
     * through user-friendly {@code RRGGBB} strings for any legacy hex-only rows.
     */
    private void addNumberRow(int x, int y, int colW, String label, double initial,
                              DoubleSetter setter, boolean hex, BooleanSupplier enabled,
                              Component tooltip) {
        int boxW = 80;
        int labelW = colW - boxW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, enabled));
        EditBox box = new EditBox(font, x + labelW + GAP, y + 2, boxW, BTN_H, Component.literal(label));
        box.setMaxLength(24);
        box.setValue(hex ? String.format("%06X", (int) initial) : stripTrailingZeros(initial));
        box.setResponder(v -> {
            if (v.isEmpty()) return;
            try {
                double parsed = hex ? Integer.parseInt(v.trim(), 16) : Double.parseDouble(v.trim());
                setter.accept(parsed);
            } catch (NumberFormatException ignored) {
                // Partial edits are expected while typing; keep the last valid value.
            }
        });
        box.setTooltip(Tooltip.create(tooltip));
        trackDependent(box, enabled);
        addRenderableWidget(box);
    }

    private void addTextRow(int x, int y, int colW, String label, String initial,
                            StringSetter setter, BooleanSupplier enabled,
                            int maxLength, String tooltip) {
        int boxW = 112;
        int labelW = colW - boxW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, enabled));
        EditBox box = new EditBox(font, x + labelW + GAP, y + 2, boxW, BTN_H, Component.literal(label));
        box.setMaxLength(maxLength);
        box.setValue(initial == null ? "" : initial);
        box.setResponder(setter::accept);
        box.setTooltip(tip(tooltip));
        trackDependent(box, enabled);
        addRenderableWidget(box);
    }

    private void addColorRow(int x, int y, int colW, String label, int initial,
                             IntSetter setter, BooleanSupplier enabled,
                             String tooltip) {
        int swatchW = 90;
        int labelW = colW - swatchW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, enabled));

        int[] currentColor = { initial };

        ColorSwatchButton[] swatchRef = new ColorSwatchButton[1];
        ColorSwatchButton swatch = new ColorSwatchButton(
                x + labelW + GAP, y + 2, swatchW, BTN_H,
                "Pick color", currentColor[0] & 0xFFFFFF, () -> ColorPickerScreen.open(this,
                label, currentColor[0] & 0xFFFFFF, opacityFromColor(currentColor[0]), picked -> {
                    currentColor[0] = withOpacity(picked, opacityFromColor(currentColor[0]));
                    setter.accept(currentColor[0]);
                    swatchRef[0].setColor(picked);
                }, opacity -> {
                    currentColor[0] = withOpacity(currentColor[0], opacity);
                    setter.accept(currentColor[0]);
                }));
        swatchRef[0] = swatch;
        swatch.setTooltip(tip(tooltip));

        trackDependent(swatch, enabled);
        addRenderableWidget(swatch);
    }

    private void addTracerColorRow(int x, int y, int colW, BooleanSupplier enabled,
                                   String tooltip) {
        int swatchW = 90;
        int labelW = colW - swatchW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6,
                "Tracer color", labelW, enabled));

        ColorSwatchButton[] swatchRef = new ColorSwatchButton[1];
        ColorSwatchButton swatch = new ColorSwatchButton(
                x + labelW + GAP, y + 2, swatchW, BTN_H,
                "Pick color", config.tracerColor(), () -> ColorPickerScreen.open(this,
                "Tracer Color", config.tracerColor(), config.tracerOpacity(), picked -> {
                    config.setTracerColor(picked);
                    swatchRef[0].setColor(picked);
                }, config::setTracerOpacity));
        swatchRef[0] = swatch;
        swatch.setTooltip(tip(tooltip));

        trackDependent(swatch, enabled);
        addRenderableWidget(swatch);
    }

    private void addBoxStyleRow(int x, int y, int colW) {
        addBoxStyleRow(x, y, colW,
                "How each waypoint is drawn in the world:\n"
              + "Outlined: edge lines only.\n"
              + "Filled: translucent faces.\n"
              + "Filled + Outline: both.");
    }

    private void addBoxStyleRow(int x, int y, int colW, String tooltip) {
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, "Box style", labelW, () -> true));
        Button btn = Button.builder(Component.literal(boxStyleLabel(config.boxStyle())), b -> {
            WaypointerConfig.BoxStyle[] values = WaypointerConfig.BoxStyle.values();
            WaypointerConfig.BoxStyle next = values[(config.boxStyle().ordinal() + 1) % values.length];
            config.setBoxStyle(next);
            b.setMessage(Component.literal(boxStyleLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(tip(tooltip))
                .build();
        addRenderableWidget(btn);
    }

    private void addBeamModeRow(int x, int y, int colW) {
        addBeamModeRow(x, y, colW,
                "Optional vertical guide beams:\n"
              + "Off: no beams.\n"
              + "Current: active targets only.\n"
              + "All visible: every waypoint.");
    }

    private void addBeamModeRow(int x, int y, int colW, String tooltip) {
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, "Beacon beams", labelW, () -> true));
        Button btn = Button.builder(Component.literal(beamModeLabel(config.beaconBeamMode())), b -> {
            WaypointerConfig.BeaconBeamMode[] values = WaypointerConfig.BeaconBeamMode.values();
            WaypointerConfig.BeaconBeamMode next =
                    values[(config.beaconBeamMode().ordinal() + 1) % values.length];
            config.setBeaconBeamMode(next);
            b.setMessage(Component.literal(beamModeLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(tip(tooltip))
                .build();
        addRenderableWidget(btn);
    }

    private static String boxStyleLabel(WaypointerConfig.BoxStyle s) {
        return switch (s) {
            case OUTLINED -> "Outlined";
            case FILLED -> "Filled";
            case FILLED_OUTLINED -> "Filled + Outline";
        };
    }

    private static String beamModeLabel(WaypointerConfig.BeaconBeamMode s) {
        return switch (s) {
            case OFF -> "Off";
            case CURRENT -> "Current";
            case ALL_VISIBLE -> "All visible";
        };
    }

    private void setMaxWaypointLabels(double value) {
        if (!Double.isFinite(value)) return;

        long rounded = Math.round(value);
        if (rounded <= 0) {
            config.setMaxWaypointLabels(0);
            return;
        }

        int clamped = rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
        config.setMaxWaypointLabels(clamped);
    }

    private void setTempDefaultDuration(double value) {
        if (!Double.isFinite(value)) return;
        long rounded = Math.round(value);
        int clamped = rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
        config.setTempDefaultDurationMin(clamped);
    }

    private static double opacityFromColor(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) return 1.0;
        if (alpha == 1) return 0.0;
        return alpha / 255.0;
    }

    private static int withOpacity(int color, double opacity) {
        int alpha = Math.max(0, Math.min(255, (int) Math.round(opacity * 255.0)));
        if (alpha == 0) alpha = 1;
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter, String tooltip) {
        addBoolRow(x, y, label, initial, setter, tooltipText(tooltip));
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter, Component tooltip) {
        addBoolRow(x, y, label, initial, setter, () -> true, tooltip);
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter,
                            BooleanSupplier enabled, String tooltip) {
        addBoolRow(x, y, label, initial, setter, enabled, tooltipText(tooltip));
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter,
                            BooleanSupplier enabled, Component tooltip) {
        Checkbox cb = Checkbox.builder(Component.literal(label), font)
                .pos(x, y)
                .selected(initial)
                .onValueChange((b, v) -> setter.accept(v))
                .build();
        cb.setTooltip(Tooltip.create(tooltip));
        trackDependent(cb, enabled);
        addRenderableWidget(cb);
    }

    private void trackDependent(AbstractWidget widget, BooleanSupplier enabled) {
        widget.active = enabled.getAsBoolean();
        dependentControls.add(new DependentControl(widget, enabled));
    }

    private void refreshDependentControls() {
        for (DependentControl control : dependentControls) {
            control.widget().active = control.enabled().getAsBoolean();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        refreshDependentControls();
        g.fill(0, 0, width, height, SURFACE);

        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.drawString(font, "Changes save automatically.",
                width - PAD_OUTER - font.width("Changes save automatically."),
                PAD_OUTER, TEXT_DIM, false);

        g.drawString(font, leftHeader, leftHeaderX, sectionHeaderY, TEXT_DIM, false);
        g.drawString(font, rightHeader, rightHeaderX, sectionHeaderY, TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Right-padded label with an explicit width so long labels truncate at column bounds. */
    private record DependentControl(AbstractWidget widget, BooleanSupplier enabled) {}

    private record SearchEntry(Page page, String section, String label, String description) {}

    private record LabelWidget(int x, int y, String text, int maxW, BooleanSupplier enabled)
            implements net.minecraft.client.gui.components.Renderable {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(text, maxW);
            g.drawString(font, clipped, x, y, enabled.getAsBoolean() ? TEXT : TEXT_DIM, false);
        }
    }

    private record SectionHeaderWidget(int x, int y, String text, int maxW)
            implements net.minecraft.client.gui.components.Renderable {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(text, maxW);
            g.drawString(font, clipped, x, y, TEXT_DIM, false);
        }
    }

    private static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
