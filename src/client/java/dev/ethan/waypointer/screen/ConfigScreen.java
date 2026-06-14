package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.update.UpdateChecker;
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
        clearExpiredConfirmation();

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
        if (available < SETTINGS_SEARCH_MIN_W) {
            refocusSettingsSearchAfterRebuild = false;
            return;
        }

        int searchW = Math.min(SETTINGS_SEARCH_W, available);
        int searchX = right - searchW;
        settingsSearchBox = new EditBox(font, searchX, y, searchW, BTN_H,
                Component.literal("Search settings"));
        settingsSearchBox.setMaxLength(80);
        settingsSearchBox.setValue(settingsSearchQuery);
        settingsSearchBox.setHint(Component.literal("Search settings"));
        settingsSearchBox.setResponder(
                this::onSettingsSearchChanged);
        addRenderableWidget(settingsSearchBox);
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
        addPageSearchResults(Page.PERFORMANCE, col1, col2, colW, rowsY, rowH);
        addPageSearchResults(Page.ROUTES, col1, col2, colW, rowsY, rowH);
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
                case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
                case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
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
        addTracerColorRow(col2, y2, colW,
                () -> config.showTracer() && !config.matchTracerToWaypointColor(),
                "Hex tracer color when color inheritance is off.");
        y2 += rowH;
        addBoolRow(col2, y2, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                config::showTracer,
                "Use active waypoint color for tracer lines.");
    }

    private void addPerformancePage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Budgets";
        rightHeader = "Label Performance";

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
                "Hide reached static waypoints until the route resets.");
        y2 += rowH;
        addBoolRow(col2, y2, "Focus mode for temp waypoints",
                config.focusTempWaypoints(), config::setFocusTempWaypoints,
                "Show only the newest temp waypoint in the active zone.");
        y2 += rowH;
        addBoolRow(col2, y2, "Temp waypoints expire",
                config.tempWaypointsExpireByDefault(), config::setTempWaypointsExpireByDefault,
                "New temp waypoints expire by default.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Temp duration (min)",
                config.tempDefaultDurationMin(), this::setTempDefaultDuration,
                config::tempWaypointsExpireByDefault,
                "Default lifetime for expiring temp waypoints.");
    }

        private void addChatPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Chat Detection";
        rightHeader = "Import / Export Defaults";

        int y = rowsY;
        addBoolRow(col1, y, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection,
                "Detect coordinates in chat for quick waypoint adds.");
        y += rowH;
        addBoolRow(col1, y, "Auto-add chat temp waypoints",
                config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                config::chatCoordDetection,
                "Create temp waypoints automatically from chat coordinates.");
        y += rowH;
        addBoolRow(col1, y, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection,
                "Detect Waypointer share codes in chat.");

        int y2 = rowsY;
        addImportedRouteColorModeRow(col2, y2, colW);
        y2 += rowH;
        addImportedRouteColorRow(col2, y2, colW, this::isImportedRouteStaticColorMode,
                "Default color for imported routes in one-color mode.");
        y2 += rowH;
        addBoolRow(col2, y2, "Include names in default export",
                config.exportIncludeNames(), config::setExportIncludeNames,
                "Include waypoint names in exported share codes.");
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
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(tooltip));
        String label = "Imported color (hex RRGGBB)";
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
                Component.literal("Imported color"));
        box.setMaxLength(6);
        box.setValue(String.format("%06X", config.importedRouteDefaultColor() & 0xFFFFFF));
        box.setTooltip(Tooltip.create(tooltipComponent));

        ColorSwatchButton[] swatchRef = new ColorSwatchButton[1];
        ColorSwatchButton swatch = new ColorSwatchButton(
                x + labelW + GAP + boxW + GAP, y + 2, swatchW, BTN_H,
                "Pick color", config.importedRouteDefaultColor(),
                                () -> ColorPickerScreen.open(this,
                "Imported Route Colour", config.importedRouteDefaultColor(),
                                picked -> {
                    config.setImportedRouteDefaultColor(picked);
                    box.setValue(String.format("%06X", picked & 0xFFFFFF));
                    if (swatchRef[0] != null) swatchRef[0].setColor(picked);
                }));
        swatchRef[0] = swatch;
        swatch.setTooltip(Tooltip.create(Component.literal("Pick imported route color.")));

        box.setResponder(
                                v -> {
            if (v.isEmpty()) return;
            String trimmed = v.trim();
            if (trimmed.length() != 6) return;
            try {
                int parsed = Integer.parseInt(trimmed, 16) & 0xFFFFFF;
                config.setImportedRouteDefaultColor(parsed);
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

        int y2 = rowsY;
        addUpdateControlsRow(col2, y2, colW);
        y2 += rowH;
        addUpdateStatusRow(col2, y2, colW);
    }

        private void addUpdateControlsRow(int x, int y, int colW) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(
                "Download saves the latest release jar to your Minecraft mods folder.\n"
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
                && UpdateChecker.isJarDownloadUri(updateCheckResult.downloadUri());
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
                        null, false, null, null, "Could not check GitHub releases.");
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
                        null, false, null, null, "Could not check GitHub releases.")
                : result;
        if (minecraft != null && minecraft.screen == this) {
            rebuildSettingsWidgets();
        }
    }

        private void downloadLatestUpdate(Button b) {
        if (!canDownloadUpdate()) return;
        long requestId = ++updateDownloadRequestSeq;
        updateDownloadInProgress = true;
        updateDownloadResult = null;
        rebuildSettingsWidgets();

        UpdateChecker.downloadLatestJarAsync(updateCheckResult).whenComplete(
                                (result, error) -> {
            UpdateChecker.DownloadResult safeResult = result;
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
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(tooltip));
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
        swatch.setTooltip(Tooltip.create(Component.literal("Pick tracer color.")));

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
        cb.setTooltip(Tooltip.create(tooltipComponent));
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

        private record ComponentLabelWidget(int x, int y, Component text, int maxW, int fallbackColor,
                                        BooleanSupplier enabled)
            implements net.minecraft.client.gui.components.Renderable {
                @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            Component safeText = text == null ? Component.empty() : text;
            if (font.width(safeText) <= maxW) {
                g.drawString(font, safeText, x, y, enabled.getAsBoolean() ? TEXT : TEXT_DIM, false);
                return;
            }
            String clipped = font.plainSubstrByWidth(safeText.getString(), maxW);
            g.drawString(font, clipped, x, y, enabled.getAsBoolean() ? fallbackColor : TEXT_DIM, false);
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
        protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partial) {
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
            g.drawString(font, REFRESH_ICON, 0, 0, color, false);
            g.pose().popMatrix();
        }
    }

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
