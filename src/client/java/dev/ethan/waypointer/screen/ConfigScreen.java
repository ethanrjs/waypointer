package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        SYSTEM("System");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private final Screen parent;
    private final WaypointerConfig config;
    private final Page page;
    private final List<DependentControl> dependentControls = new ArrayList<>();
    private final Set<String> searchSeenSettingKeys = new HashSet<>();
    private static final int SETTINGS_SEARCH_W = 160;
    private static final int SETTINGS_SEARCH_MIN_W = 104;
    private EditBox settingsSearchBox;
    private String settingsSearchQuery;
    private Page searchPageContext;
    private int searchCol1;
    private int searchCol2;
    private int searchColW;
    private int searchRowsY;
    private int searchRowH;
    private int searchMaxVisibleMatches;
    private int searchTotalMatches;
    private int searchRenderedMatches;

    public ConfigScreen(Screen parent, WaypointerConfig config) {
        this(parent, config, Page.VISUALS, "");
    }

    private ConfigScreen(Screen parent, WaypointerConfig config, Page page) {
        this(parent, config, page, "");
    }

    private ConfigScreen(Screen parent, WaypointerConfig config, Page page, String settingsSearchQuery) {
        super(Component.literal("Waypointer Settings"));
        this.parent = parent;
        this.config = config;
        this.page = page == null ? Page.VISUALS : page;
        this.settingsSearchQuery = settingsSearchQuery == null ? "" : settingsSearchQuery;
    }

    @Override
    protected void init() {
        dependentControls.clear();
        searchSeenSettingKeys.clear();
        settingsSearchBox = null;
        searchPageContext = null;
        searchTotalMatches = 0;
        searchRenderedMatches = 0;

        int navY = PAD_OUTER + font.lineHeight + GAP;
        int top = navY + BTN_H + GAP_SECTION;
        int rowH = 24;
        int colGap = GAP_SECTION;
        int col1 = PAD_OUTER;
        int colW = (width - PAD_OUTER * 2 - colGap) / 2;
        int col2 = col1 + colW + colGap;

        int headerY = top;
        int rowsY = top + 16;

        int tabsEnd = addPageTabs(navY);
        addSettingsSearchBox(tabsEnd, navY);

        if (settingsSearchActive()) {
            addSearchResults(col1, col2, colW, rowsY, rowH);
        } else {
            switch (page) {
                case VISUALS -> addVisualsPage(col1, col2, colW, rowsY, rowH);
                case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
                case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
                case CHAT -> addChatPage(col1, col2, colW, rowsY, rowH);
                case SYSTEM -> addSystemPage(col1, col2, colW, rowsY, rowH);
            }
        }

        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        if (page == Page.VISUALS) {
            left.add(new GuiTokens.ButtonSpec("Disable All", 96, this::disableAllSettings,
                    Tooltip.create(Component.literal(
                            "Turn off every Waypointer toggle.\n"
                          + "Distances, colors, radii, and other numeric values are kept."))));
        }
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", -1, this::onClose,
                Tooltip.create(Component.literal(
                        "Return to the previous screen.\n"
                      + "Every change on this page is saved as you type or click.")));
        GuiTokens.layoutFooter(width, footerY, left, done, this::addRenderableWidget, font);

        this.leftHeaderX = col1;
        this.rightHeaderX = col2;
        this.sectionHeaderY = headerY;
    }

    private int addPageTabs(int y) {
        int x = PAD_OUTER;
        for (Page target : Page.values()) {
            int tabW = Math.max(64, font.width(target.label) + 18);
            Button btn = Button.builder(Component.literal(target.label),
                    b -> openPage(target)).bounds(x, y, tabW, BTN_H).build();
            btn.active = target != page;
            addRenderableWidget(btn);
            x += tabW + GAP;
        }
        return x;
    }

    private void openPage(Page target) {
        if (target == null || target == page) return;
        minecraft.setScreen(new ConfigScreen(parent, config, target, settingsSearchQuery));
    }

    private void addSettingsSearchBox(int tabsEnd, int y) {
        int right = width - PAD_OUTER;
        int leftLimit = tabsEnd + GAP;
        int available = right - leftLimit;
        if (available < SETTINGS_SEARCH_MIN_W) return;

        int searchW = Math.min(SETTINGS_SEARCH_W, available);
        int searchX = right - searchW;
        settingsSearchBox = new EditBox(font, searchX, y, searchW, BTN_H,
                Component.literal("Search settings"));
        settingsSearchBox.setMaxLength(80);
        settingsSearchBox.setValue(settingsSearchQuery);
        settingsSearchBox.setHint(Component.literal("Search settings"));
        settingsSearchBox.setTooltip(Tooltip.create(Component.literal(
                "Fuzzy-search settings by label, page, or tooltip text.")));
        settingsSearchBox.setResponder(this::onSettingsSearchChanged);
        addRenderableWidget(settingsSearchBox);
    }

    private void onSettingsSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(settingsSearchQuery)) return;
        settingsSearchQuery = next;
        rebuildSettingsWidgets();
        if (settingsSearchBox != null) settingsSearchBox.setFocused(true);
    }

    private void rebuildSettingsWidgets() {
        rebuildWidgets();
    }

    private void disableAllSettings() {
        config.disableAllSettings();
        settingsSearchQuery = "";
        rebuildSettingsWidgets();
    }

    private void addSearchResults(int col1, int col2, int colW, int rowsY, int rowH) {
        beginSearchResults(col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.VISUALS, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.PERFORMANCE, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.ROUTES, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.CHAT, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.SYSTEM, col1, col2, colW, rowsY, rowH);
        finishSearchResults();
    }

    private void beginSearchResults(int col1, int col2, int colW, int rowsY, int rowH) {
        searchCol1 = col1;
        searchCol2 = col2;
        searchColW = colW;
        searchRowsY = rowsY;
        searchRowH = rowH;
        searchSeenSettingKeys.clear();
        searchTotalMatches = 0;
        searchRenderedMatches = 0;
        int availableHeight = Math.max(rowH, height - rowsY - FOOTER_H - GAP_SECTION);
        searchMaxVisibleMatches = Math.max(2, (availableHeight / rowH) * 2);
    }

    private void addPageSearchResults(Page target, int col1, int col2, int colW, int rowsY, int rowH) {
        searchPageContext = target;
        try {
            switch (target) {
                case VISUALS -> addVisualsPage(col1, col2, colW, rowsY, rowH);
                case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
                case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
                case CHAT -> addChatPage(col1, col2, colW, rowsY, rowH);
                case SYSTEM -> addSystemPage(col1, col2, colW, rowsY, rowH);
            }
        } finally {
            searchPageContext = null;
        }
    }

    private void finishSearchResults() {
        if (searchTotalMatches == 0) {
            leftHeader = "No matching settings";
            rightHeader = "";
            return;
        }
        leftHeader = "Matching settings";
        if (searchRenderedMatches < searchTotalMatches) {
            rightHeader = "Showing " + searchRenderedMatches + " of " + searchTotalMatches;
        } else {
            rightHeader = searchTotalMatches + " match" + (searchTotalMatches == 1 ? "" : "es");
        }
    }

    private void addVisualsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Markers";
        rightHeader = "Labels & Tracers";

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
        y += rowH;
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
        y2 += rowH;
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
        y2 += rowH;
        addBoolRow(col2, y2, "Focus mode for temp waypoints",
                config.focusTempWaypoints(), config::setFocusTempWaypoints,
                "When on, adding a temporary waypoint hides other waypoints in the\n"
              + "active zone and forces a tracer to the temp until you leave the server.");
        y2 += rowH;
        addBoolRow(col2, y2, "Temp waypoints expire",
                config.tempWaypointsExpireByDefault(), config::setTempWaypointsExpireByDefault,
                "When on, newly-created temp waypoints use TIME mode by default.\n"
              + "When off, they last until you leave the server unless changed in\n"
              + "the Add Temp dialog.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Temp duration (min)",
                config.tempDefaultDurationMin(), this::setTempDefaultDuration,
                config::tempWaypointsExpireByDefault,
                "Default lifetime for TIME-mode temporary waypoints.");
    }

    private void addChatPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Chat Detection";
        rightHeader = "Export Defaults";

        int y = rowsY;
        addBoolRow(col1, y, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection,
                "Scans incoming chat for coordinates and can offer quick-add flows for\n"
              + "temporary or permanent waypoints (no effect when chat has no coords).");
        y += rowH;
        addBoolRow(col1, y, "Auto-add chat temp waypoints",
                config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                config::chatCoordDetection,
                "When chat coord detection finds a coordinate, immediately creates a\n"
              + "temporary waypoint using your default expiry. Off keeps click-to-add only.");
        y += rowH;
        addBoolRow(col1, y, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection,
                "Detects Waypointer share codes pasted in chat so you can import routes\n"
              + "without opening the main menu.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Include names in default export",
                config.exportIncludeNames(), config::setExportIncludeNames,
                "When exporting, include waypoint names in the payload. Makes shared codes\n"
              + "longer but preserves labels for the recipient.");
    }

    private void addSystemPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Location";
        rightHeader = "Maintenance";

        addBoolRow(col1, rowsY, "Always use scoreboard for zone detection",
                config.preferScoreboardFallback(), config::setPreferScoreboardFallback,
                "Prefer Hypixel-style scoreboard hints when resolving the current zone ID,\n"
              + "even when other signals exist. Use if tab/location detection misbehaves.");

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

    private int leftHeaderX;
    private int rightHeaderX;
    private int sectionHeaderY;
    private String leftHeader = "";
    private String rightHeader = "";

    private interface DoubleSetter { void accept(double value); }

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
        MutableComponent out = Component.literal(description).withStyle(ChatFormatting.GRAY);
        out.append(Component.literal("\n\nImpact: ").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal(impact.label).withStyle(impact.color));
        return out;
    }

    private boolean settingsSearchActive() {
        return !normalizedSettingsSearchQuery().isEmpty();
    }

    private String normalizedSettingsSearchQuery() {
        return settingsSearchQuery == null ? "" : settingsSearchQuery.trim().toLowerCase(Locale.ROOT);
    }

    private boolean shouldRenderSettingRow(String label, Component tooltip) {
        if (!settingsSearchActive()) return true;
        String safeLabel = label == null ? "" : label;
        String pageLabel = searchPageContext == null ? page.label : searchPageContext.label;
        String tooltipText = tooltip == null ? "" : tooltip.getString();
        String searchable = pageLabel + " " + safeLabel + " " + tooltipText;
        if (!fuzzySettingMatch(normalizedSettingsSearchQuery(), searchable)) return false;

        String key = safeLabel.toLowerCase(Locale.ROOT);
        if (!searchSeenSettingKeys.add(key)) return false;
        searchTotalMatches++;
        return searchTotalMatches <= searchMaxVisibleMatches;
    }

    private int[] settingRowGeometry(int x, int y, int colW) {
        if (!settingsSearchActive()) return new int[]{x, y, colW};
        int index = searchRenderedMatches;
        searchRenderedMatches++;
        int column = index % 2;
        int row = index / 2;
        int resultX = column == 0 ? searchCol1 : searchCol2;
        int resultY = searchRowsY + row * searchRowH;
        return new int[]{resultX, resultY, searchColW};
    }

    private static boolean fuzzySettingMatch(String query, String searchable) {
        if (query == null || query.isBlank()) return true;
        String haystack = searchable == null ? "" : searchable.toLowerCase(Locale.ROOT);
        for (String token : query.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (haystack.contains(token)) continue;
            if (!isSubsequence(token, haystack)) return false;
        }
        return true;
    }

    private static boolean isSubsequence(String needle, String haystack) {
        if (needle == null || needle.isEmpty()) return true;
        if (haystack == null || haystack.isEmpty()) return false;
        int needleIndex = 0;
        for (int i = 0; i < haystack.length() && needleIndex < needle.length(); i++) {
            if (haystack.charAt(i) == needle.charAt(needleIndex)) {
                needleIndex++;
            }
        }
        return needleIndex == needle.length();
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, Component.literal(tooltip));
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              Component tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, () -> true, tooltip);
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              BooleanSupplier enabled, String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, enabled, Component.literal(tooltip));
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              BooleanSupplier enabled, Component tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, enabled, tooltip);
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              boolean hex, String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, hex, () -> true, Component.literal(tooltip));
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial,
                              DoubleSetter setter, boolean hex, BooleanSupplier enabled,
                              Component tooltip) {
        if (!shouldRenderSettingRow(label, tooltip)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int boxW = 80;
        int labelW = colW - boxW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, enabled));
        EditBox box = new EditBox(font, x + labelW + GAP, y + 2, boxW, BTN_H, Component.literal(label));
        box.setMaxLength(24);
        box.setValue(hex ? String.format("%06X", (int) initial) : stripTrailingZeros(initial));
        box.setResponder(
                v -> {
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

    private void addTracerColorRow(int x, int y, int colW, BooleanSupplier enabled,
                                   String tooltip) {
        Component tooltipComponent = Component.literal(tooltip);
        String label = "Tracer color (hex RRGGBB)";
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int swatchW = 72;
        int boxW = 76;
        int labelW = colW - boxW - swatchW - GAP * 2;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, enabled));

        EditBox box = new EditBox(font, x + labelW + GAP, y + 2, boxW, BTN_H,
                Component.literal("Tracer color"));
        box.setMaxLength(6);
        box.setValue(String.format("%06X", config.tracerColor() & 0xFFFFFF));
        box.setTooltip(Tooltip.create(tooltipComponent));

        ColorSwatchButton swatch = new ColorSwatchButton(
                x + labelW + GAP + boxW + GAP, y + 2, swatchW, BTN_H,
                "Pick color", config.tracerColor(),
                () -> ColorPickerScreen.open(this,
                "Tracer Colour", config.tracerColor(),
                picked -> {
                    config.setTracerColor(picked);
                    box.setValue(String.format("%06X", picked & 0xFFFFFF));
                }));
        swatch.setTooltip(Tooltip.create(Component.literal(
                "Open the color picker for the fixed tracer color.")));

        box.setResponder(
                v -> {
            if (v.isEmpty()) return;
            try {
                int parsed = Integer.parseInt(v.trim(), 16) & 0xFFFFFF;
                config.setTracerColor(parsed);
                swatch.setColor(parsed);
            } catch (NumberFormatException ignored) {
                // Partial edits are expected while typing; keep the last valid color.
            }
        });

        trackDependent(box, enabled);
        trackDependent(swatch, enabled);
        addRenderableWidget(box);
        addRenderableWidget(swatch);
    }

    private void addBoxStyleRow(int x, int y, int colW) {
        addBoxStyleRow(x, y, colW,
                "How each waypoint is drawn in the world:\n"
              + "Outlined — edge lines only.\n"
              + "Filled — translucent faces (easier to see at distance).\n"
              + "Filled + Outline — both for maximum contrast.");
    }

    private void addBoxStyleRow(int x, int y, int colW, String tooltip) {
        Component tooltipComponent = Component.literal(tooltip);
        String label = "Box style";
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, () -> true));
        Button btn = Button.builder(Component.literal(boxStyleLabel(config.boxStyle())),
                b -> {
            WaypointerConfig.BoxStyle[] values = WaypointerConfig.BoxStyle.values();
            WaypointerConfig.BoxStyle next = values[(config.boxStyle().ordinal() + 1) % values.length];
            config.setBoxStyle(next);
            b.setMessage(Component.literal(boxStyleLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(Tooltip.create(tooltipComponent))
                .build();
        addRenderableWidget(btn);
    }

    private void addBeamModeRow(int x, int y, int colW) {
        addBeamModeRow(x, y, colW,
                "Optional vertical guide beams:\n"
              + "Off — no beams.\n"
              + "Current — only each active group's target.\n"
              + "All visible — every rendered waypoint.");
    }

    private void addBeamModeRow(int x, int y, int colW, String tooltip) {
        Component tooltipComponent = Component.literal(tooltip);
        String label = "Beacon beams";
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, () -> true));
        Button btn = Button.builder(Component.literal(beamModeLabel(config.beaconBeamMode())),
                b -> {
            WaypointerConfig.BeaconBeamMode[] values = WaypointerConfig.BeaconBeamMode.values();
            WaypointerConfig.BeaconBeamMode next =
                    values[(config.beaconBeamMode().ordinal() + 1) % values.length];
            config.setBeaconBeamMode(next);
            b.setMessage(Component.literal(beamModeLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(Tooltip.create(tooltipComponent))
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

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter, String tooltip) {
        addBoolRow(x, y, label, initial, setter, Component.literal(tooltip));
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter, Component tooltip) {
        addBoolRow(x, y, label, initial, setter, () -> true, tooltip);
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter,
                            BooleanSupplier enabled, String tooltip) {
        addBoolRow(x, y, label, initial, setter, enabled, Component.literal(tooltip));
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter,
                            BooleanSupplier enabled, Component tooltip) {
        if (!shouldRenderSettingRow(label, tooltip)) return;
        int[] geometry = settingRowGeometry(x, y, 0);
        x = geometry[0];
        y = geometry[1];
        Checkbox cb = Checkbox.builder(Component.literal(label), font)
                .pos(x, y)
                .selected(initial)
                .onValueChange(
                        (b, v) -> setter.accept(v))
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

    private record LabelWidget(int x, int y, String text, int maxW, BooleanSupplier enabled)
            implements net.minecraft.client.gui.components.Renderable {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(text, maxW);
            g.drawString(font, clipped, x, y, enabled.getAsBoolean() ? TEXT : TEXT_DIM, false);
        }
    }

    private static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
