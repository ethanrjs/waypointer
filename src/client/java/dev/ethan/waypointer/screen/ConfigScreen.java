package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.config.WaypointerConfigCodec;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.update.UpdateChecker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

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
        COLORS("Colors"),
        PERFORMANCE("Performance"),
        ROUTES("Routes"),
        IMPORT_EXPORT("Import / Export"),
        CHAT("Chat"),
        OTHER("Other");

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
    private static final int SETTINGS_SEARCH_CLEAR_W = 52;
    private static final int CONFIRM_NONE = 0;
    private static final int CONFIRM_DISABLE_ALL = 1;
    private static final int CONFIRM_RESET_DEFAULTS = 2;
    private static final long CONFIRMATION_WINDOW_MS = 3_000L;
    private EditBox settingsSearchBox;
    private String settingsSearchQuery;
    private boolean settingsSearchRebuildPending;
    private boolean refocusSettingsSearchAfterRebuild;
    private int pendingConfirmationAction = CONFIRM_NONE;
    private long pendingConfirmationUntilMillis;
    private Page searchPageContext;
    private Button settingsSearchClearButton;
    private int searchCol1;
    private int searchCol2;
    private int searchColW;
    private int searchRowsY;
    private int searchRowH;
    private int searchMaxVisibleMatches;
    private int searchTotalMatches;
    private int searchRenderedMatches;
    private boolean updateCheckInProgress;
    private long updateCheckRequestSeq;
    private UpdateChecker.CheckResult updateCheckResult;
    private boolean updateDownloadInProgress;
    private long updateDownloadRequestSeq;
    private UpdateChecker.DownloadResult updateDownloadResult;
    private Component configCodeStatus;

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
        settingsSearchClearButton = null;
        searchPageContext = null;
        searchTotalMatches = 0;
        searchRenderedMatches = 0;
        clearExpiredConfirmation();

        int navY = PAD_OUTER + font.lineHeight + GAP;
        int rowH = 24;
        int colGap = GAP_SECTION;
        int col1 = PAD_OUTER;
        int colW = (width - PAD_OUTER * 2 - colGap) / 2;
        int col2 = col1 + colW + colGap;

        int[] tabLayout = addPageTabs(navY);
        addSettingsSearchBox(tabLayout[0], navY);

        int top = tabLayout[1] + GAP_SECTION;
        int headerY = top;
        int rowsY = top + 16;

        if (settingsSearchActive()) {
            addSearchResults(col1, col2, colW, rowsY, rowH);
        } else {
            switch (page) {
                case VISUALS -> addVisualsPage(col1, col2, colW, rowsY, rowH);
                case COLORS -> addColorsPage(col1, col2, colW, rowsY, rowH);
                case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
                case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
                case IMPORT_EXPORT -> addImportExportPage(col1, col2, colW, rowsY, rowH);
                case CHAT -> addChatPage(col1, col2, colW, rowsY, rowH);
                case OTHER -> addOtherPage(col1, col2, colW, rowsY, rowH);
            }
        }

        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        if (page == Page.OTHER) {
            left.add(new GuiTokens.ButtonSpec(
                    confirmationActive(CONFIRM_DISABLE_ALL) ? "Confirm Disable" : "Disable All",
                    112,
                    this::disableAllSettings));
            left.add(new GuiTokens.ButtonSpec(
                    confirmationActive(CONFIRM_RESET_DEFAULTS) ? "Confirm Reset" : "Reset to Defaults",
                    132,
                    this::resetSettingsToDefaults));
        }
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", -1,
                this::onClose,
                Tooltip.create(Component.literal(
                        "Return to the previous screen.\n"
                      + "Every change on this page is saved as you type or click.")));
        GuiTokens.layoutFooter(width, footerY, left, done,
                this::addRenderableWidget, font);

        this.leftHeaderX = col1;
        this.rightHeaderX = col2;
        this.sectionHeaderY = headerY;
    }

    @Override
    public void tick() {
        super.tick();
        if (settingsSearchRebuildPending) {
            settingsSearchRebuildPending = false;
            refocusSettingsSearchAfterRebuild = true;
            rebuildSettingsWidgets();
            return;
        }
        if (clearExpiredConfirmation()) {
            rebuildSettingsWidgets();
        }
    }

    private int[] addPageTabs(int y) {
        int x = PAD_OUTER;
        int rowY = y;
        int firstRowEnd = PAD_OUTER;
        boolean firstRow = true;
        int firstRowLimit = width - PAD_OUTER - SETTINGS_SEARCH_MIN_W - GAP;
        int fullRowLimit = width - PAD_OUTER;
        for (Page target : Page.values()) {
            int tabW = Math.max(54, font.width(target.label) + 16);
            int rowLimit = firstRow ? firstRowLimit : fullRowLimit;
            if (x > PAD_OUTER && x + tabW > rowLimit) {
                rowY += BTN_H + GAP;
                x = PAD_OUTER;
                firstRow = false;
                rowLimit = fullRowLimit;
            }
            Button btn = Button.builder(Component.literal(target.label),
                    b -> openPage(target)).bounds(x, rowY, tabW, BTN_H).build();
            btn.active = target != page;
            addRenderableWidget(btn);
            if (firstRow) firstRowEnd = x + tabW;
            x += tabW + GAP;
        }
        return new int[]{firstRowEnd, rowY + BTN_H};
    }

    private void openPage(Page target) {
        if (target == null || target == page) return;
        minecraft.setScreen(new ConfigScreen(parent, config, target, settingsSearchQuery));
    }

    private void addSettingsSearchBox(int tabsEnd, int y) {
        int right = width - PAD_OUTER;
        int leftLimit = tabsEnd + GAP;
        int available = right - leftLimit;
        int clearW = SETTINGS_SEARCH_CLEAR_W;
        int searchAvailable = available - clearW - GAP_TIGHT;
        if (searchAvailable < SETTINGS_SEARCH_MIN_W) {
            refocusSettingsSearchAfterRebuild = false;
            return;
        }

        int searchW = Math.min(SETTINGS_SEARCH_W, searchAvailable);
        int clearX = right - clearW;
        int searchX = clearX - GAP_TIGHT - searchW;
        settingsSearchBox = new EditBox(font, searchX, y, searchW, BTN_H,
                Component.literal("Search settings"));
        settingsSearchBox.setMaxLength(80);
        settingsSearchBox.setValue(settingsSearchQuery);
        settingsSearchBox.setHint(Component.literal("Search settings"));
        settingsSearchBox.setResponder(
                this::onSettingsSearchChanged);
        addRenderableWidget(settingsSearchBox);

        settingsSearchClearButton = Button.builder(Component.literal("Clear"), this::clearSettingsSearch)
                .bounds(clearX, y, clearW, BTN_H)
                .tooltip(Tooltip.create(Component.literal("Clear settings search.")))
                .build();
        updateSettingsSearchClearButton();
        addRenderableWidget(settingsSearchClearButton);
        if (refocusSettingsSearchAfterRebuild) {
            setFocused(settingsSearchBox);
            settingsSearchBox.setFocused(true);
            refocusSettingsSearchAfterRebuild = false;
        }
    }

    private void onSettingsSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(settingsSearchQuery)) return;
        settingsSearchQuery = next;
        settingsSearchRebuildPending = true;
        refocusSettingsSearchAfterRebuild = true;
        updateSettingsSearchClearButton();
    }

    private void clearSettingsSearch(Button button) {
        if (settingsSearchBox != null) {
            settingsSearchBox.setValue("");
        }
        onSettingsSearchChanged("");
    }

    private void updateSettingsSearchClearButton() {
        if (settingsSearchClearButton != null) {
            settingsSearchClearButton.active = settingsSearchClearButtonActive(settingsSearchQuery);
        }
    }

    static boolean settingsSearchClearButtonActive(String query) {
        return query != null && !query.isEmpty();
    }

    private void rebuildSettingsWidgets() {
        rebuildWidgets();
    }

    private void disableAllSettings() {
        if (!consumeOrArmConfirmation(CONFIRM_DISABLE_ALL)) return;
        config.disableAllSettings();
        settingsSearchQuery = "";
        clearPendingConfirmation();
        rebuildSettingsWidgets();
    }

    private void resetSettingsToDefaults() {
        if (!consumeOrArmConfirmation(CONFIRM_RESET_DEFAULTS)) return;
        config.resetToDefaults();
        settingsSearchQuery = "";
        clearPendingConfirmation();
        rebuildSettingsWidgets();
    }

    private boolean consumeOrArmConfirmation(int action) {
        long now = System.currentTimeMillis();
        if (pendingConfirmationAction == action && now <= pendingConfirmationUntilMillis) {
            return true;
        }
        pendingConfirmationAction = action;
        pendingConfirmationUntilMillis = now + CONFIRMATION_WINDOW_MS;
        rebuildSettingsWidgets();
        return false;
    }

    private boolean confirmationActive(int action) {
        return pendingConfirmationAction == action
                && System.currentTimeMillis() <= pendingConfirmationUntilMillis;
    }

    private boolean clearExpiredConfirmation() {
        if (pendingConfirmationAction == CONFIRM_NONE) return false;
        if (System.currentTimeMillis() <= pendingConfirmationUntilMillis) return false;
        clearPendingConfirmation();
        return true;
    }

    private void clearPendingConfirmation() {
        pendingConfirmationAction = CONFIRM_NONE;
        pendingConfirmationUntilMillis = 0L;
    }

    private void addSearchResults(int col1, int col2, int colW, int rowsY, int rowH) {
        beginSearchResults(col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.VISUALS, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.COLORS, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.PERFORMANCE, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.ROUTES, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.IMPORT_EXPORT, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.CHAT, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.OTHER, col1, col2, colW, rowsY, rowH);
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
                case COLORS -> addColorsPage(col1, col2, colW, rowsY, rowH);
                case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
                case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
                case IMPORT_EXPORT -> addImportExportPage(col1, col2, colW, rowsY, rowH);
                case CHAT -> addChatPage(col1, col2, colW, rowsY, rowH);
                case OTHER -> addOtherPage(col1, col2, colW, rowsY, rowH);
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

    private boolean anyWaypointLabelTextEnabled() {
        return config.showWaypointNames()
                || config.showWaypointDistances()
                || config.showRouteProgress();
    }

    private boolean beaconBeamsEnabled() {
        return config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF;
    }

    private void addVisualsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Markers";
        rightHeader = "Labels & Tracers";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Waypoint box opacity (0-1)",
                config.beaconOpacity(), config::setBeaconOpacity,
                "Controls waypoint box fill opacity.");
        y += rowH;
        addBoxStyleRow(col1, y, colW);
        y += rowH;
        addNumberRow(col1, y, colW, "Outline thickness (px)",
                config.waypointOutlineThickness(), config::setWaypointOutlineThickness,
                "Controls waypoint outline width.");
        y += rowH;
        addBoolRow(col1, y, "Sharp waypoint edges",
                config.sharpWaypointEdges(), config::setSharpWaypointEdges,
                "Use crisper projected waypoint outline edges.");
        y += rowH;
        addBeamModeRow(col1, y, colW);
        y += rowH;
        addBoolRow(col1, y, "Beam extends below waypoint",
                config.beaconBeamExtendsBelowWaypoint(), config::setBeaconBeamExtendsBelowWaypoint,
                () -> config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF,
                "Draw beacon beams from world bottom instead of waypoint height.");
        y += rowH;
        addBoolRow(col1, y, "Show completed waypoints", config.showCompleted(), config::setShowCompleted,
                "Show waypoints you already reached.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names", config.showWaypointNames(), config::setShowWaypointNames,
                "Show waypoint name labels.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint distances",
                config.showWaypointDistances(), config::setShowWaypointDistances,
                "Show distance text below labels.");
        y2 += rowH;
        addBoolRow(col2, y2, "Waypoint text inherits color",
                config.matchWaypointTextToWaypointColor(), config::setMatchWaypointTextToWaypointColor,
                config::showWaypointNames,
                "Use each waypoint color for its label.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show label backdrop", config.showLabelBackdrop(), config::setShowLabelBackdrop,
                this::anyWaypointLabelTextEnabled,
                "Draw a dark background behind labels.");
        y2 += rowH;
        addBoolRow(col2, y2, "Scale text with distance",
                config.scaleWaypointTextWithDistance(), config::setScaleWaypointTextWithDistance,
                this::anyWaypointLabelTextEnabled,
                "Scale labels by distance from camera.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Label scale (0.25-4)",
                config.labelScale(), config::setLabelScale,
                this::anyWaypointLabelTextEnabled,
                "Baseline size multiplier for waypoint labels.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Label height offset (blocks)",
                config.labelHeightOffset(), config::setLabelHeightOffset,
                this::anyWaypointLabelTextEnabled,
                "Raises labels above waypoint boxes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show EDIT MODE subtitle",
                config.showEditModeSubtitle(), config::setShowEditModeSubtitle,
                "Show an aqua EDIT MODE label while edit mode is active.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide labels when near",
                config.hideWaypointLabelsNearPlayer(), config::setHideWaypointLabelsNearPlayer,
                this::anyWaypointLabelTextEnabled,
                "Hide only text labels while you stand near their waypoint.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Label near radius (blocks)",
                config.hideWaypointLabelsNearRadius(), config::setHideWaypointLabelsNearRadius,
                config::hideWaypointLabelsNearPlayer,
                "Distance where label-only near-hide starts.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show tracers", config.showTracer(), config::setShowTracer,
                "Draw lines from crosshair to active waypoints.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Tracer opacity (0-1)",
                config.tracerOpacity(), config::setTracerOpacity,
                config::showTracer,
                "Controls tracer line opacity.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Tracer thickness (px)",
                config.tracerThickness(), config::setTracerThickness,
                config::showTracer,
                "Controls tracer line width.");
        y2 += rowH;
        addBoolRow(col2, y2, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                config::showTracer,
                "Use active waypoint color for tracer lines.");
    }

    private void addColorsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Defaults";
        rightHeader = "Behavior";

        int y = rowsY;
        addDefaultWaypointColorRow(col1, y, colW,
                "Default color for future manually-created and temp waypoints.");
        y += rowH;
        addTracerColorRow(col1, y, colW,
                () -> config.showTracer() && !config.matchTracerToWaypointColor(),
                "Fallback tracer color when tracer inheritance is off.");
        y += rowH;
        addImportedRouteColorRow(col1, y, colW, this::isImportedRouteStaticColorMode,
                "Default color applied to imported routes in One color mode.");
        y += rowH;
        addRouteLineColorRow(col1, y, colW, config::showRouteLines,
                "Color for route connector lines.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Waypoint text inherits color",
                config.matchWaypointTextToWaypointColor(), config::setMatchWaypointTextToWaypointColor,
                config::showWaypointNames,
                "Use each waypoint color for its label.");
        y2 += rowH;
        addBoolRow(col2, y2, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                config::showTracer,
                "Use the active waypoint color for tracer lines.");
        y2 += rowH;
        addImportedRouteColorModeRow(col2, y2, colW);
        y2 += rowH;
        addBoolRow(col2, y2, "Show route connector lines",
                config.showRouteLines(), config::setShowRouteLines,
                "Draw lines between visible route waypoint centers.");
    }

    private void addPerformancePage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Budgets";
        rightHeader = "Labels & Beams";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Max waypoint labels (0 = unlimited)",
                config.maxWaypointLabels(),
                this::setMaxWaypointLabels,
                performanceTooltip(
                        "Limits floating labels drawn per frame.",
                        Impact.HIGH));
        y += rowH;
        addNumberRow(col1, y, colW, "Static marker distance (0 = unlimited)",
                config.maxStaticWaypointRenderDistance(),
                config::setMaxStaticWaypointRenderDistance,
                performanceTooltip(
                        "Hides far static-route markers beyond this distance.",
                        Impact.HIGH));
        y += rowH;
        addBoolRow(col1, y, "Use beacon textures",
                config.useBeaconBeamTextures(), config::setUseBeaconBeamTextures,
                this::beaconBeamsEnabled,
                performanceTooltip(
                        "Uses the vanilla core/glow beacon texture. Off uses flat beams and saves roughly one textured batch plus 16 vertices per visible beam; usually tiny in Current mode, medium in All visible or dense static routes.",
                        Impact.MEDIUM));

        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names",
                config.showWaypointNames(), config::setShowWaypointNames,
                performanceTooltip(
                        "Name labels add HUD text draws.",
                        Impact.HIGH));
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint distances",
                config.showWaypointDistances(), config::setShowWaypointDistances,
                performanceTooltip(
                        "Distance labels add HUD text draws.",
                        Impact.MEDIUM));
        y2 += rowH;
        addBoolRow(col2, y2, "Show label backdrop",
                config.showLabelBackdrop(), config::setShowLabelBackdrop,
                this::anyWaypointLabelTextEnabled,
                performanceTooltip(
                        "Label backdrops add extra HUD quads.",
                        Impact.LOW));
    }
    private void addRoutesPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Progression";
        rightHeader = "Route Display";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Default reach radius (blocks)",
                config.defaultReachRadius(), config::setDefaultReachRadius,
                "Distance needed to mark a waypoint reached.");
        y += rowH;
        addBoolRow(col1, y, "Enable waypoint skip-ahead mechanic",
                config.skipAheadMechanicEnabled(), config::setSkipAheadMechanicEnabled,
                "Reaching a later waypoint skips earlier route steps.");
        y += rowH;
        addBoolRow(col1, y, "Only skip to visible waypoints",
                config.skipAheadOnlyVisibleWaypoints(), config::setSkipAheadOnlyVisibleWaypoints,
                config::skipAheadMechanicEnabled,
                "Automatic skip-ahead only targets route points currently shown.");
        y += rowH;
        addBoolRow(col1, y, "Reset progress when joining a world",
                config.resetProgressOnWorldJoin(), config::setResetProgressOnWorldJoin,
                "Joining a world resets each route to the first waypoint.");
        y += rowH;
        addBoolRow(col1, y, "Restart route after last waypoint",
                config.restartRouteWhenComplete(), config::setRestartRouteWhenComplete,
                "Reaching final waypoint resets progress in route.");
        y += rowH;
        addBoolRow(col1, y, "Add new waypoints below player",
                config.placeNewWaypointsBelowPlayer(), config::setPlaceNewWaypointsBelowPlayer,
                "Place new player-position waypoints one block below you.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Show route progress",
                config.showRouteProgress(), config::setShowRouteProgress,
                "Show route progress percentage on waypoint labels.");
        y2 += rowH;
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
                "Hide waypoint visuals while you stand near them.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Near hide radius (blocks)",
                config.hideWaypointsNearRadius(), config::setHideWaypointsNearRadius,
                config::hideWaypointsNearPlayer,
                "Distance where near-hide starts.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide reached static waypoints",
                config.hideReachedStaticWaypointsUntilCycleComplete(),
                config::setHideReachedStaticWaypointsUntilCycleComplete,
                hideReachedStaticWaypointsTooltip());
        y2 += rowH;
        addBoolRow(col2, y2, "Show route connector lines",
                config.showRouteLines(), config::setShowRouteLines,
                "Draw lines between the centers of visible route waypoints.");
        y2 += rowH;
        addBoolRow(col2, y2, "Dungeon entry path to first waypoint",
                config.showDungeonEntryPathToFirstWaypoint(),
                config::setShowDungeonEntryPathToFirstWaypoint,
                "Draw a teleport-friendly path to waypoint #1 while entering a dungeon room.");
        y2 += rowH;
        addBoolRow(col2, y2, "Continue dungeon path after first",
                config.showDungeonEntryPathToFollowingWaypoints(),
                config::setShowDungeonEntryPathToFollowingWaypoints,
                config::showDungeonEntryPathToFirstWaypoint,
                "Keep drawing the dungeon path to later active route waypoints.");
        y2 += rowH;
        addDungeonEntryPathColorRow(col2, y2, colW, config::showDungeonEntryPathToFirstWaypoint,
                "Color for the dungeon entry path and arrows.");
        y2 += rowH;
        addRouteLineColorRow(col2, y2, colW, config::showRouteLines,
                "Color for route connector lines.");
        y2 += rowH;
        addBoolRow(col2, y2, "Focus mode for temp waypoints",
                config.focusTempWaypoints(), config::setFocusTempWaypoints,
                "Show only the newest temp waypoint in the active zone.");
        y2 += rowH;
        addBoolRow(col2, y2, "Temp waypoints expire",
                config.tempWaypointsExpireByDefault(), config::setTempWaypointsExpireByDefault,
                (Component) null);
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Temp duration (sec)",
                config.tempDefaultDurationSec(), this::setTempDefaultDurationSeconds,
                config::tempWaypointsExpireByDefault,
                "Default lifetime in seconds for expiring temp waypoints.");
    }

    private void addRouteLineColorRow(int x, int y, int colW, BooleanSupplier enabled,
                                      String tooltip) {
        addRgbColorRow(x, y, colW,
                "Route line color (hex RRGGBB)",
                "Route line color",
                config.routeLineColor(),
                config::setRouteLineColor,
                enabled,
                tooltip,
                "Route Line Colour",
                "Pick route connector line color.");
    }

    private void addDungeonEntryPathColorRow(int x, int y, int colW, BooleanSupplier enabled,
                                             String tooltip) {
        addRgbColorRow(x, y, colW,
                "Dungeon entry path color (hex RRGGBB)",
                "Dungeon entry path color",
                config.dungeonEntryPathColor(),
                config::setDungeonEntryPathColor,
                enabled,
                tooltip,
                "Dungeon Entry Path Colour",
                "Pick dungeon entry path color.");
    }

    private void addDefaultWaypointColorRow(int x, int y, int colW, String tooltip) {
        addRgbColorRow(x, y, colW,
                "Waypoint color (hex RRGGBB)",
                "Default waypoint color",
                config.defaultWaypointColor(),
                config::setDefaultWaypointColor,
                ConfigScreen::alwaysEnabled,
                tooltip,
                "Default Waypoint Colour",
                "Pick default waypoint color.");
    }

    private void addRgbColorRow(int x, int y, int colW, String label, String editorName,
                                int currentColor, IntConsumer setter, BooleanSupplier enabled,
                                String tooltip, String pickerTitle, String swatchTooltip) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(tooltip));
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
                Component.literal(editorName));
        box.setMaxLength(6);
        box.setValue(String.format("%06X", currentColor & 0xFFFFFF));
        box.setTooltip(Tooltip.create(tooltipComponent));

        ColorSwatchButton[] swatchRef = new ColorSwatchButton[1];
        ColorSwatchButton swatch = new ColorSwatchButton(
                x + labelW + GAP + boxW + GAP, y + 2, swatchW, BTN_H,
                "Pick color", currentColor,
                () -> {
                    int pickerColor = swatchRef[0] == null ? currentColor : swatchRef[0].getColor();
                    ColorPickerScreen.open(this, pickerTitle, pickerColor,
                            picked -> {
                            setter.accept(picked);
                            box.setValue(String.format("%06X", picked & 0xFFFFFF));
                            if (swatchRef[0] != null) swatchRef[0].setColor(picked);
                    });
                });
        swatchRef[0] = swatch;
        swatch.setTooltip(Tooltip.create(Component.literal(swatchTooltip)));

        box.setResponder(
                v -> {
            Integer parsed = parseRgbHexColor(v);
            if (parsed == null) return;
            setter.accept(parsed);
            swatch.setColor(parsed);
        });

        addRenderableWidget(box);
        addRenderableWidget(swatch);
    }

    static Integer parseRgbHexColor(String rawValue) {
        if (rawValue == null) return null;
        String trimmed = rawValue.trim();
        if (trimmed.length() != 6) return null;
        try {
            return Integer.parseInt(trimmed, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addChatPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Chat Detection";
        rightHeader = "Chat Temp Waypoints";

        int y = rowsY;
        addBoolRow(col1, y, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection,
                "Detect coordinates in chat for quick waypoint adds.");
        y += rowH;
        addBoolRow(col1, y, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection,
                "Detect Waypointer share codes in chat.");
        y += rowH;
        addBoolRow(col1, y, "Contributor badges",
                config.showContributorBadges(), config::setShowContributorBadges,
                "Show Waypointer contributor badges in chat and the player list.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Auto-add chat temp waypoints",
                config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                config::chatCoordDetection,
                "Create temp waypoints automatically from chat coordinates.");
    }

    private void addImportExportPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Import Defaults";
        rightHeader = "Export Defaults";

        int y = rowsY;
        addImportedRouteColorModeRow(col1, y, colW);
        y += rowH;
        addImportedRouteColorRow(col1, y, colW, this::isImportedRouteStaticColorMode,
                "Default color for imported routes in one-color mode.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Include names in default export",
                config.exportIncludeNames(), config::setExportIncludeNames,
                "Include waypoint names in exported share codes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Include colors in default export",
                config.exportIncludeColors(), config::setExportIncludeColors,
                "Include waypoint colors in exported share codes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Include radii in default export",
                config.exportIncludeRadii(), config::setExportIncludeRadii,
                "Include custom waypoint reach radii in exported share codes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Include waypoint flags",
                config.exportIncludeWaypointFlags(), config::setExportIncludeWaypointFlags,
                "Include per-waypoint render flags in exported share codes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Include group metadata",
                config.exportIncludeGroupMeta(), config::setExportIncludeGroupMeta,
                "Include route name, mode, and route-level metadata in exported share codes.");
    }

        private void addImportedRouteColorModeRow(int x, int y, int colW) {
        Component tooltipComponent = importedRouteColorModeTooltip();
        String label = "Imported route colors";
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int buttonW = 140;
        int labelW = colW - buttonW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, ConfigScreen::alwaysEnabled));
        Button btn = Button.builder(Component.literal(importedRouteColorModeLabel(config.importedRouteColorMode())),
                                b -> {
            WaypointGroup.GradientMode next = nextImportedRouteColorMode(config.importedRouteColorMode());
            config.setImportedRouteColorMode(next);
            b.setMessage(Component.literal(importedRouteColorModeLabel(next)));
            refreshDependentControls();
        }).bounds(x + labelW + GAP, y, buttonW, BTN_H)
                .tooltip(Tooltip.create(tooltipComponent))
                .build();
        addRenderableWidget(btn);
    }

        private static WaypointGroup.GradientMode nextImportedRouteColorMode(WaypointGroup.GradientMode mode) {
        if (mode == WaypointGroup.GradientMode.STATIC) return WaypointGroup.GradientMode.AUTO;
        if (mode == WaypointGroup.GradientMode.AUTO) return WaypointGroup.GradientMode.MANUAL;
        return WaypointGroup.GradientMode.STATIC;
    }

        private static String importedRouteColorModeLabel(WaypointGroup.GradientMode mode) {
        if (mode == WaypointGroup.GradientMode.AUTO) return "Gradient";
        if (mode == WaypointGroup.GradientMode.MANUAL) return "Manual";
        return "One color";
    }

        private static Component importedRouteColorModeTooltip() {
        return normalizedTooltipComponent(Component.literal(
                "One color overrides every imported waypoint with the default color.\n"
              + "Gradient recolors the imported route with its gradient.\n"
              + "Manual preserves colors from the imported payload."));
    }

        private boolean isImportedRouteStaticColorMode() {
        return config.importedRouteColorMode() == WaypointGroup.GradientMode.STATIC;
    }

    private void addImportedRouteColorRow(int x, int y, int colW, BooleanSupplier enabled,
                                          String tooltip) {
        addRgbColorRow(x, y, colW,
                "Imported color (hex RRGGBB)",
                "Imported color",
                config.importedRouteDefaultColor(),
                config::setImportedRouteDefaultColor,
                enabled,
                tooltip,
                "Imported Route Colour",
                "Pick imported route color.");
    }

    private void addOtherPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Maintenance";
        rightHeader = "Updates";

        int y = rowsY;
        addBoolRow(col1, y, "Check for updates on startup",
                config.checkForUpdates(), config::setCheckForUpdates,
                "Check GitHub for new Waypointer releases on startup.");
        y += rowH;
        addBoolRow(col1, y, "Experimental Iris HUD fallback",
                config.irisShaderHudFallback(), config::setIrisShaderHudFallback,
                "Render waypoint HUD fallback when Iris shaders are active.");
        y += rowH;
        addBoolRow(col1, y, "Edit mode sounds",
                config.editSounds(), config::setEditSounds,
                "Play local UI sounds for edit-mode actions.");
        y += rowH;
        addConfigCodeControlsRow(col1, y, colW);
        y += rowH;
        addConfigCodeStatusRow(col1, y, colW);

        int y2 = rowsY;
        addUpdateControlsRow(col2, y2, colW);
        y2 += rowH;
        addUpdateStatusRow(col2, y2, colW);
    }

    private void addConfigCodeControlsRow(int x, int y, int colW) {
        Component importTooltipComponent = normalizedTooltipComponent(Component.literal(
                "Import settings. This will overwrite existing settings."));
        String label = "Config code";
        if (!shouldRenderSettingRow(label, importTooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int buttonW = (colW - GAP) / 2;

        Button copy = Button.builder(Component.literal("Copy config code"), this::copyConfigCode)
                .bounds(x, y, buttonW, BTN_H)
                .build();
        Button importButton = Button.builder(Component.literal("Import config code"), this::importConfigCode)
                .bounds(x + buttonW + GAP, y, colW - buttonW - GAP, BTN_H)
                .tooltip(Tooltip.create(importTooltipComponent))
                .build();
        addRenderableWidget(copy);
        addRenderableWidget(importButton);
    }

    private void addConfigCodeStatusRow(int x, int y, int colW) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(
                "Shows the result of copying or importing a Waypointer config code."));
        if (!shouldRenderSettingRow("Config code status", tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        Component status = configCodeStatus == null
                ? Component.literal("WPC: config codes use your clipboard.").withStyle(ChatFormatting.GRAY)
                : configCodeStatus;
        addRenderableOnly(new ComponentLabelWidget(geometry[0], geometry[1] + 6,
                status, geometry[2], TEXT_DIM, ConfigScreen::alwaysEnabled));
    }

    private void copyConfigCode(Button b) {
        try {
            String code = WaypointerConfigCodec.encode(config);
            minecraft.keyboardHandler.setClipboard(code);
            setConfigCodeStatus(Component.literal("Config code copied.").withStyle(ChatFormatting.GREEN));
        } catch (Throwable t) {
            setConfigCodeStatus(Component.literal("Could not copy config code.").withStyle(ChatFormatting.RED));
        }
        rebuildSettingsWidgets();
    }

    private void importConfigCode(Button b) {
        String text;
        try {
            text = minecraft.keyboardHandler.getClipboard();
        } catch (Throwable t) {
            setConfigCodeStatus(Component.literal("Could not read clipboard.").withStyle(ChatFormatting.RED));
            rebuildSettingsWidgets();
            return;
        }
        if (text == null || text.isBlank()) {
            setConfigCodeStatus(Component.literal("Clipboard is empty.").withStyle(ChatFormatting.RED));
            rebuildSettingsWidgets();
            return;
        }

        try {
            WaypointerConfig decoded = WaypointerConfigCodec.decode(text);
            int changedSettings = countChangedSettings(decoded);
            showImportConfigConfirmation(decoded, changedSettings);
            return;
        } catch (RuntimeException e) {
            setConfigCodeStatus(Component.literal("Invalid config code.").withStyle(ChatFormatting.RED));
        }
        rebuildSettingsWidgets();
    }

    private void showImportConfigConfirmation(WaypointerConfig decoded, int changedSettings) {
        if (decoded == null) {
            setConfigCodeStatus(Component.literal("Invalid config code.").withStyle(ChatFormatting.RED));
            rebuildSettingsWidgets();
            return;
        }

        String settingWord = changedSettings == 1 ? "setting" : "settings";
        Component title = Component.literal("Import config code?");
        Component message = Component.literal(
                "Import settings. This will overwrite existing settings.\n"
              + changedSettings + " " + settingWord
              + " will be changed from their current states.");
        ConfirmScreen confirmScreen = new ConfirmScreen(
                confirmed -> {
            if (confirmed) {
                applyConfirmedConfigImport(decoded, changedSettings);
            } else {
                setConfigCodeStatus(Component.literal("Config import cancelled.").withStyle(ChatFormatting.GRAY));
            }
            minecraft.setScreen(this);
        }, title, message, Component.literal("Import settings"), Component.literal("Cancel"));
        minecraft.setScreen(confirmScreen);
    }

    private void applyConfirmedConfigImport(WaypointerConfig decoded, int changedSettings) {
        if (decoded == null) {
            setConfigCodeStatus(Component.literal("Invalid config code.").withStyle(ChatFormatting.RED));
            return;
        }
        config.replaceWith(decoded);
        settingsSearchQuery = "";
        clearPendingConfirmation();
        String settingWord = changedSettings == 1 ? "setting" : "settings";
        setConfigCodeStatus(Component.literal("Config imported. " + changedSettings + " "
                + settingWord + " changed.").withStyle(ChatFormatting.GREEN));
    }
    private int countChangedSettings(WaypointerConfig decoded) {
        if (decoded == null) return 0;

        int changed = 0;
        if (Double.compare(config.defaultReachRadius(), decoded.defaultReachRadius()) != 0) changed++;
        if (config.resetProgressOnWorldJoin() != decoded.resetProgressOnWorldJoin()) changed++;
        if (config.restartRouteWhenComplete() != decoded.restartRouteWhenComplete()) changed++;
        if (config.defaultWaypointColor() != decoded.defaultWaypointColor()) changed++;
        if (config.tracerColor() != decoded.tracerColor()) changed++;
        if (config.matchTracerToWaypointColor() != decoded.matchTracerToWaypointColor()) changed++;
        if (Double.compare(config.tracerOpacity(), decoded.tracerOpacity()) != 0) changed++;
        if (Double.compare(config.tracerThickness(), decoded.tracerThickness()) != 0) changed++;
        if (Double.compare(config.waypointOutlineThickness(), decoded.waypointOutlineThickness()) != 0) changed++;
        if (config.sharpWaypointEdges() != decoded.sharpWaypointEdges()) changed++;
        if (Double.compare(config.beaconOpacity(), decoded.beaconOpacity()) != 0) changed++;
        if (config.showWaypointNames() != decoded.showWaypointNames()) changed++;
        if (config.showWaypointDistances() != decoded.showWaypointDistances()) changed++;
        if (config.showRouteProgress() != decoded.showRouteProgress()) changed++;
        if (Double.compare(config.labelScale(), decoded.labelScale()) != 0) changed++;
        if (config.scaleWaypointTextWithDistance() != decoded.scaleWaypointTextWithDistance()) changed++;
        if (config.matchWaypointTextToWaypointColor() != decoded.matchWaypointTextToWaypointColor()) changed++;
        if (config.showCompleted() != decoded.showCompleted()) changed++;
        if (config.showTracer() != decoded.showTracer()) changed++;
        if (config.dimSequenceContextWaypoints() != decoded.dimSequenceContextWaypoints()) changed++;
        if (config.hideTracerOnStaticRoutes() != decoded.hideTracerOnStaticRoutes()) changed++;
        if (config.hideWaypointsNearPlayer() != decoded.hideWaypointsNearPlayer()) changed++;
        if (Double.compare(config.hideWaypointsNearRadius(), decoded.hideWaypointsNearRadius()) != 0) changed++;
        if (config.hideWaypointLabelsNearPlayer() != decoded.hideWaypointLabelsNearPlayer()) changed++;
        if (Double.compare(config.hideWaypointLabelsNearRadius(), decoded.hideWaypointLabelsNearRadius()) != 0) changed++;
        if (config.hideReachedStaticWaypointsUntilCycleComplete()
                != decoded.hideReachedStaticWaypointsUntilCycleComplete()) changed++;
        if (config.skipAheadOnlyVisibleWaypoints() != decoded.skipAheadOnlyVisibleWaypoints()) changed++;
        if (config.showRouteLines() != decoded.showRouteLines()) changed++;
        if (config.showDungeonEntryPathToFirstWaypoint()
                != decoded.showDungeonEntryPathToFirstWaypoint()) changed++;
        if (config.showDungeonEntryPathToFollowingWaypoints()
                != decoded.showDungeonEntryPathToFollowingWaypoints()) changed++;
        if (config.dungeonEntryPathColor() != decoded.dungeonEntryPathColor()) changed++;
        if (config.routeLineColor() != decoded.routeLineColor()) changed++;
        if (config.showLabelBackdrop() != decoded.showLabelBackdrop()) changed++;
        if (config.maxWaypointLabels() != decoded.maxWaypointLabels()) changed++;
        if (Double.compare(config.maxStaticWaypointRenderDistance(),
                decoded.maxStaticWaypointRenderDistance()) != 0) changed++;
        if (Double.compare(config.labelHeightOffset(), decoded.labelHeightOffset()) != 0) changed++;
        if (config.boxStyle() != decoded.boxStyle()) changed++;
        if (config.beaconBeamMode() != decoded.beaconBeamMode()) changed++;
        if (config.beaconBeamExtendsBelowWaypoint() != decoded.beaconBeamExtendsBelowWaypoint()) changed++;
        if (config.useBeaconBeamTextures() != decoded.useBeaconBeamTextures()) changed++;
        if (config.editSounds() != decoded.editSounds()) changed++;
        if (config.showEditModeSubtitle() != decoded.showEditModeSubtitle()) changed++;
        if (config.chatCoordDetection() != decoded.chatCoordDetection()) changed++;
        if (!config.chatCoordSenderBlacklist().equals(decoded.chatCoordSenderBlacklist())) changed++;
        if (config.autoAddChatTempWaypoints() != decoded.autoAddChatTempWaypoints()) changed++;
        if (config.placeNewWaypointsBelowPlayer() != decoded.placeNewWaypointsBelowPlayer()) changed++;
        if (config.focusTempWaypoints() != decoded.focusTempWaypoints()) changed++;
        if (config.chatCodecDetection() != decoded.chatCodecDetection()) changed++;
        if (config.importedRouteColorMode() != decoded.importedRouteColorMode()) changed++;
        if (config.importedRouteDefaultColor() != decoded.importedRouteDefaultColor()) changed++;
        if (config.exportIncludeNames() != decoded.exportIncludeNames()) changed++;
        if (config.exportIncludeColors() != decoded.exportIncludeColors()) changed++;
        if (config.exportIncludeRadii() != decoded.exportIncludeRadii()) changed++;
        if (config.exportIncludeWaypointFlags() != decoded.exportIncludeWaypointFlags()) changed++;
        if (config.exportIncludeGroupMeta() != decoded.exportIncludeGroupMeta()) changed++;
        if (config.dungeonWaypointsFeatureEnabled() != decoded.dungeonWaypointsFeatureEnabled()) changed++;
        if (config.skipAheadMechanicEnabled() != decoded.skipAheadMechanicEnabled()) changed++;
        if (config.checkForUpdates() != decoded.checkForUpdates()) changed++;
        if (config.irisShaderHudFallback() != decoded.irisShaderHudFallback()) changed++;
        if (config.tempDefaultMode() != decoded.tempDefaultMode()) changed++;
        if (config.tempDefaultDurationSec() != decoded.tempDefaultDurationSec()) changed++;
        return changed;
    }

    private void setConfigCodeStatus(Component status) {
        configCodeStatus = status;
    }

        private void addUpdateControlsRow(int x, int y, int colW) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(
                "Download asks for confirmation, then saves a verified release jar to your Minecraft mods folder.\n"
              + "Restart Minecraft after it finishes. The refresh button checks GitHub now."));
        String label = "Latest release";
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];

        int refreshW = BTN_H + 8;
        int downloadW = Math.min(132, Math.max(104, colW / 2 + 8));
        int labelW = Math.max(0, colW - downloadW - refreshW - GAP * 2);
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, ConfigScreen::alwaysEnabled));

        Button download = Button.builder(Component.literal(updateDownloadButtonLabel()), this::downloadLatestUpdate)
                .bounds(x + labelW + GAP, y, downloadW, BTN_H)
                .tooltip(Tooltip.create(tooltipComponent))
                .build();
        download.active = canDownloadUpdate();
        addRenderableWidget(download);

        LargeRefreshButton refresh = new LargeRefreshButton(
                x + labelW + GAP + downloadW + GAP, y, refreshW, BTN_H,
                this::startManualUpdateCheck);
        refresh.setTooltip(Tooltip.create(Component.literal("Check for updates now.")));
        refresh.active = !updateCheckInProgress && !updateDownloadInProgress;
        addRenderableWidget(refresh);
    }

    static String hideReachedStaticWaypointsTooltip() {
        return "Static routes hide reached main markers until the cycle resets; "
                + "current/tracer does not advance, and subwaypoints are ignored.";
    }

        private void addUpdateStatusRow(int x, int y, int colW) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(
                "Shows the result of the latest manual update check."));
        if (!shouldRenderSettingRow("Update status", tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        addRenderableOnly(new ComponentLabelWidget(geometry[0], geometry[1] + 6,
                updateStatusComponent(), geometry[2], TEXT_DIM, ConfigScreen::alwaysEnabled));
    }

        private String updateDownloadButtonLabel() {
        if (updateCheckInProgress) return "Checking...";
        if (updateDownloadInProgress) return "Downloading...";
        if (updateCheckResult != null
                && updateCheckResult.updateAvailable()
                && updateCheckResult.latestVersion() != null) {
            return "Download v" + updateCheckResult.latestVersion();
        }
        return "Download update";
    }

        private Component updateStatusComponent() {
        MutableComponent out = Component.literal("Status: ").withStyle(ChatFormatting.GRAY);
        ChatFormatting color;
        if (updateDownloadInProgress) {
            color = ChatFormatting.YELLOW;
        } else if (updateDownloadResult != null) {
            color = updateDownloadResult.success() ? ChatFormatting.GREEN : ChatFormatting.RED;
        } else if (updateCheckInProgress || updateCheckResult == null) {
            color = ChatFormatting.GRAY;
        } else if (updateCheckResult.failureMessage() != null
                && !updateCheckResult.failureMessage().isBlank()) {
            color = ChatFormatting.RED;
        } else if (updateCheckResult.updateAvailable()) {
            color = ChatFormatting.GREEN;
        } else {
            color = ChatFormatting.DARK_GREEN;
        }
        out.append(Component.literal(updateStatusText()).withStyle(color));
        return out;
    }

        private String updateStatusText() {
        if (updateDownloadInProgress) return "Downloading update...";
        if (updateDownloadResult != null) {
            String message = updateDownloadResult.message();
            return message == null || message.isBlank()
                    ? "Could not download update. Try again."
                    : message;
        }
        if (updateCheckInProgress) return "Checking GitHub releases...";
        if (updateCheckResult == null) return "Not checked yet.";
        if (updateCheckResult.failureMessage() != null && !updateCheckResult.failureMessage().isBlank()) {
            return updateCheckResult.failureMessage() + " Try again.";
        }
        if (updateCheckResult.updateAvailable()) {
            if (!UpdateChecker.isJarDownloadUri(updateCheckResult.downloadUri())) {
                return updateCheckResult.latestVersion() == null
                        ? "Update available, but no jar asset was found."
                        : "Update available v" + updateCheckResult.latestVersion()
                                + ", but no jar asset was found.";
            }
            if (!UpdateChecker.hasSha256Digest(updateCheckResult.downloadSha256())) {
                return updateCheckResult.latestVersion() == null
                        ? "Update available, but the jar has no SHA-256 digest."
                        : "Update available v" + updateCheckResult.latestVersion()
                                + ", but the jar has no SHA-256 digest.";
            }
            return updateCheckResult.latestVersion() == null
                    ? "Update available"
                    : "Update available v" + updateCheckResult.latestVersion();
        }
        String local = updateCheckResult.localVersion() == null ? UpdateChecker.currentModVersion()
                : updateCheckResult.localVersion();
        return "Up to date v" + local;
    }

        private boolean canDownloadUpdate() {
        return !updateCheckInProgress
                && !updateDownloadInProgress
                && updateCheckResult != null
                && updateCheckResult.updateAvailable()
                && UpdateChecker.isJarDownloadUri(updateCheckResult.downloadUri())
                && UpdateChecker.hasSha256Digest(updateCheckResult.downloadSha256());
    }

        private void startManualUpdateCheck(Button b) {
        if (updateCheckInProgress || updateDownloadInProgress) return;
        long requestId = ++updateCheckRequestSeq;
        updateCheckInProgress = true;
        updateCheckResult = null;
        updateDownloadResult = null;
        rebuildSettingsWidgets();

        UpdateChecker.checkLatestAsync(UpdateChecker.currentModVersion()).whenComplete(
                                (result, error) -> {
            UpdateChecker.CheckResult safeResult = result;
            if (error != null) {
                safeResult = new UpdateChecker.CheckResult(UpdateChecker.currentModVersion(),
                        null, false, null, null, null, "Could not check GitHub releases.");
            }
            UpdateChecker.CheckResult finalResult = safeResult;
            net.minecraft.client.Minecraft.getInstance().execute(
                                        () -> handleManualUpdateCheckResult(requestId, finalResult));
        });
    }

        private void handleManualUpdateCheckResult(long requestId, UpdateChecker.CheckResult result) {
        if (requestId != updateCheckRequestSeq) return;
        updateCheckInProgress = false;
        updateCheckResult = result == null
                ? new UpdateChecker.CheckResult(UpdateChecker.currentModVersion(),
                        null, false, null, null, null, "Could not check GitHub releases.")
                : result;
        if (minecraft != null && minecraft.screen == this) {
            rebuildSettingsWidgets();
        }
    }

        private void downloadLatestUpdate(Button b) {
        if (!canDownloadUpdate()) return;
        UpdateChecker.CheckResult result = updateCheckResult;
        String version = result.latestVersion() == null ? "the latest release" : "v" + result.latestVersion();
        Component title = Component.literal("Download Waypointer update?");
        Component message = Component.literal(
                "Download " + version + " to your Minecraft mods folder?\n"
              + "Waypointer will verify the jar before installing it. Restart Minecraft after it finishes.");
        ConfirmScreen confirmScreen = new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) {
                beginLatestUpdateDownload(result);
            }
        }, title, message, Component.literal("Download update"), Component.literal("Cancel"));
        minecraft.setScreen(confirmScreen);
    }

        private void beginLatestUpdateDownload(UpdateChecker.CheckResult result) {
        if (result == null || !canDownloadUpdate()) return;
        long requestId = ++updateDownloadRequestSeq;
        updateDownloadInProgress = true;
        updateDownloadResult = null;
        rebuildSettingsWidgets();

        UpdateChecker.downloadLatestJarAsync(result).whenComplete(
                                (downloadResult, error) -> {
            UpdateChecker.DownloadResult safeResult = downloadResult;
            if (error != null) {
                safeResult = new UpdateChecker.DownloadResult(false, null, null,
                        "Could not download update. Try again.");
            }
            UpdateChecker.DownloadResult finalResult = safeResult;
            net.minecraft.client.Minecraft.getInstance().execute(
                                        () -> handleUpdateDownloadResult(requestId, finalResult));
        });
    }

        private void handleUpdateDownloadResult(long requestId, UpdateChecker.DownloadResult result) {
        if (requestId != updateDownloadRequestSeq) return;
        updateDownloadInProgress = false;
        updateDownloadResult = result == null
                ? new UpdateChecker.DownloadResult(false, null, null,
                        "Could not download update. Try again.")
                : result;
        if (minecraft != null && minecraft.screen == this) {
            rebuildSettingsWidgets();
        }
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
        MutableComponent out = Component.literal(normalizeTooltipText(description)).withStyle(ChatFormatting.GRAY);
        out.append(Component.literal("\n\nImpact: ").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal(impact.label).withStyle(impact.color));
        return out;
    }

    private static Component normalizedTooltipComponent(Component tooltip) {
        if (tooltip == null) return Component.literal("");
        if (!tooltip.getSiblings().isEmpty()) return tooltip;
        return Component.literal(normalizeTooltipText(tooltip.getString())).withStyle(tooltip.getStyle());
    }

    private static String normalizeTooltipText(String raw) {
        if (raw == null) return "";
        String normalizedLineEndings = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalizedLineEndings.split("\n", -1);
        StringBuilder out = new StringBuilder(raw.length());
        boolean hasText = false;
        boolean pendingParagraphBreak = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (hasText) pendingParagraphBreak = true;
                continue;
            }

            if (!hasText) {
                out.append(trimmed);
                hasText = true;
            } else if (pendingParagraphBreak) {
                out.append("\n\n").append(trimmed);
                pendingParagraphBreak = false;
            } else {
                out.append(' ').append(trimmed);
            }
        }

        return out.toString();
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

    static boolean fuzzySettingMatch(String query, String searchable) {
        if (query == null || query.isBlank()) return true;
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String haystack = searchable == null ? "" : searchable.toLowerCase(Locale.ROOT);
        for (String token : normalizedQuery.split("\\s+")) {
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
        Component tooltipComponent = normalizedTooltipComponent(tooltip);
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
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
        box.setTooltip(Tooltip.create(tooltipComponent));
        trackDependent(box, enabled);
        addRenderableWidget(box);
    }

    private void addTracerColorRow(int x, int y, int colW, BooleanSupplier enabled,
                                   String tooltip) {
        addRgbColorRow(x, y, colW,
                "Tracer color (hex RRGGBB)",
                "Tracer color",
                config.tracerColor(),
                config::setTracerColor,
                enabled,
                tooltip,
                "Tracer Colour",
                "Pick tracer color.");
    }

    private void addBoxStyleRow(int x, int y, int colW) {
        addBoxStyleRow(x, y, colW,
                "Choose outline, filled, or filled plus outline boxes.");
    }

    private void addBoxStyleRow(int x, int y, int colW, String tooltip) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(tooltip));
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
                "Choose which waypoints show beacon beams.");
    }

    private void addBeamModeRow(int x, int y, int colW, String tooltip) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(tooltip));
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

        private static boolean alwaysEnabled() {
        return true;
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
    private void setTempDefaultDurationSeconds(double value) {
        if (!Double.isFinite(value)) return;
        long rounded = Math.round(value);
        int clamped = rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
        config.setTempDefaultDurationSec(clamped);
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
        Component tooltipComponent = normalizedTooltipComponent(tooltip);
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, 0);
        x = geometry[0];
        y = geometry[1];
        Checkbox cb = Checkbox.builder(Component.literal(label), font)
                .pos(x, y)
                .selected(initial)
                .onValueChange(
                        (b, v) -> setter.accept(v))
                .build();
        if (!tooltipComponent.getString().isEmpty()) {
            cb.setTooltip(Tooltip.create(tooltipComponent));
        }
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
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        refreshDependentControls();
        g.fill(0, 0, width, height, SURFACE);

        super.extractRenderState(g, mouseX, mouseY, partial);
        g.text(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.text(font, "Changes save automatically.",
                width - PAD_OUTER - font.width("Changes save automatically."),
                PAD_OUTER, TEXT_DIM, false);

        g.text(font, leftHeader, leftHeaderX, sectionHeaderY, TEXT_DIM, false);
        g.text(font, rightHeader, rightHeaderX, sectionHeaderY, TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Right-padded label with an explicit width so long labels truncate at column bounds. */
    private record DependentControl(AbstractWidget widget, BooleanSupplier enabled) {}

        private record ComponentLabelWidget(int x, int y, Component text, int maxW, int fallbackColor,
                                        BooleanSupplier enabled)
            implements net.minecraft.client.gui.components.Renderable {
                @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            Component safeText = text == null ? Component.empty() : text;
            if (font.width(safeText) <= maxW) {
                g.text(font, safeText, x, y, enabled.getAsBoolean() ? TEXT : TEXT_DIM, false);
                return;
            }
            String clipped = font.plainSubstrByWidth(safeText.getString(), maxW);
            g.text(font, clipped, x, y, enabled.getAsBoolean() ? fallbackColor : TEXT_DIM, false);
        }
    }

        private static final class LargeRefreshButton extends Button {
        private static final String REFRESH_ICON = "\u21BB";
        private static final float ICON_SCALE = 2.35f;

                private LargeRefreshButton(int x, int y, int width, int height, OnPress onPress) {
            super(x, y, width, height,
                    Component.literal("Check for updates now"),
                    onPress,
                    DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            int iconW = font.width(REFRESH_ICON);
            float scaledW = iconW * ICON_SCALE;
            float scaledH = font.lineHeight * ICON_SCALE;
            float drawX = getX() + (getWidth() - scaledW) / 2.0f;
            float drawY = getY() + (getHeight() - scaledH) / 2.0f + 0.5f;
            int color = active ? TEXT : TEXT_MUTED;

            g.pose().pushMatrix();
            g.pose().translate(drawX, drawY);
            g.pose().scale(ICON_SCALE, ICON_SCALE);
            g.text(font, REFRESH_ICON, 0, 0, color, false);
            g.pose().popMatrix();
        }
    }

    private record LabelWidget(int x, int y, String text, int maxW, BooleanSupplier enabled)
            implements net.minecraft.client.gui.components.Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(text, maxW);
            g.text(font, clipped, x, y, enabled.getAsBoolean() ? TEXT : TEXT_DIM, false);
        }
    }

    private static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
