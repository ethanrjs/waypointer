package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.config.WaypointerConfigCodec;
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

    /*[[AI-FN-DOC
Function:
init
Purpose:
Build the Waypointer settings screen, including tab navigation, search, page rows, and footer actions.
Why this exists:
Minecraft screens are rebuilt from scratch whenever state changes, so this method is the single place that translates current settings UI state into live widgets.
When to use:
Called by Minecraft when the screen opens or rebuildWidgets requests a rebuild. Do not call directly from row callbacks except through rebuildSettingsWidgets/rebuildWidgets.
Inputs:
No parameters. Reads the screen width, height, selected page, current search query, confirmation state, update state, and config-code status from instance fields.
Outputs:
No return value. Populates renderable widgets and header positions for the current screen state.
Side effects:
Clears and recreates widgets, resets dependent-control tracking, may clear expired confirmation state, and records header coordinates for render.
Failure modes:
Very narrow screens may skip the search box; search mode may omit non-matching rows. Existing config values are not mutated while the layout is built.
Important invariants:
The footer stays below page content, tab wrapping reserves room for search, and search rendering uses the same page builders as normal page rendering.
Internal logic:
Reset transient layout collections, compute navigation and row geometry, add wrapped tabs plus optional search, render either filtered search results or the selected page, then add footer actions.
Pseudocode:
clear dependent controls and search state
compute navY
add tabs and read their first-row end plus bottom y
add search box beside first-row tabs when space allows
compute content top below the deepest tab row
if search query active render search results else render selected page
build footer actions, including destructive actions on Other only
record header coordinates for render
Implementation notes:
Tabs can wrap to a second row so Colors and Import / Export do not crowd the top bar or squeeze search into an unusable width.
AI self-check:
Verify every Page enum value is handled in both normal rendering and search rendering.
]]*/
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

    /*[[AI-FN-DOC
Function:
addPageTabs
Purpose:
Create the page-tab buttons and wrap them before they collide with the search field.
Why this exists:
The settings screen now has enough top-level pages that a single fixed tab row can become crowded on smaller GUI scales.
When to use:
Call during init before computing the content rows. Do not call after rows are laid out because the returned bottom y determines content placement.
Inputs:
y is the first tab-row y coordinate.
Outputs:
Returns an int array where index 0 is the end x of first-row tabs for search placement and index 1 is the y coordinate below the deepest tab row.
Side effects:
Adds tab button widgets to the screen.
Failure modes:
Extremely narrow screens may still force a large tab onto a row by itself. Search placement handles insufficient space separately.
Important invariants:
The first row reserves at least SETTINGS_SEARCH_MIN_W plus padding for search; later rows can use the full content width.
Internal logic:
Iterate pages, compute each tab width, wrap when the next tab would exceed the current row limit, add the button, and track first-row end plus bottom y.
Pseudocode:
x = outer padding
rowY = y
firstRowLimit = right edge minus search minimum
for each page:
  compute width
  if current row has content and tab would exceed row limit, move to next row
  add button at x,rowY
  if on first row update firstRowEnd
  advance x
return firstRowEnd and rowY + button height
Implementation notes:
The return is a tiny primitive array instead of a record to avoid adding extra generated methods for a one-use layout carrier.
AI self-check:
Confirm the Import / Export tab can wrap cleanly and the search box still appears when the first row leaves enough space.
]]*/
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
        addBoolRow(col2, y2, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                config::showTracer,
                "Use active waypoint color for tracer lines.");
    }

    /*[[AI-FN-DOC
Function:
addColorsPage
Purpose:
Lay out global color defaults and inheritance controls in one dedicated settings tab.
Why this exists:
Color settings were scattered across Visuals, Routes, and Chat, making it hard to audit defaults or understand which colors affect future waypoints versus imported routes.
When to use:
Called from init and search-result rendering when the Colors page or matching color settings are being built. Do not use for per-route color editing, which belongs in GroupEditScreen.
Inputs:
col1 and col2 are column x positions; colW is each column width; rowsY is the first row y coordinate; rowH is the vertical spacing per row.
Outputs:
No return value. Adds color setting widgets and updates section headers.
Side effects:
Mutates screen widget lists, dependent controls, and the live config through row callbacks.
Failure modes:
Rows can be skipped by search filtering. Color rows ignore invalid partial hex text while the user is typing.
Important invariants:
This page edits defaults and global inheritance only; it never bulk-recolors existing saved routes.
Internal logic:
Place direct color defaults in the left column and inheritance/color-mode toggles in the right column.
Pseudocode:
set headers to Defaults and Behavior
left column: default waypoint color, tracer fallback color, imported route default color, route connector color
right column: waypoint text inheritance, tracer inheritance, imported-route color mode, route connector visibility
Implementation notes:
The rows reuse the same swatch plus hex helper used elsewhere so color editing is visually and behaviorally consistent.
AI self-check:
Verify new default waypoint color is described as future-created only and imported route color remains separate.
]]*/
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

    /*[[AI-FN-DOC
Function:
addRoutesPage
Purpose:
Lay out route progression and route display settings on the Routes tab.
Why this exists:
Route behavior controls are numerous enough to need a dedicated tab with two clear columns rather than being mixed into visuals or chat settings.
When to use:
Called from init and search-result rendering when the Routes page or matching settings are being built. Do not call after widget layout is finalized except through screen rebuilds.
Inputs:
col1 and col2 are the left and right column x coordinates; colW is the column width; rowsY is the first row y coordinate; rowH is the vertical spacing per row.
Outputs:
No return value. Adds route-related widgets to the screen and updates section headers.
Side effects:
Mutates screen widget/renderable lists, dependent-control tracking, and left/right header text.
Failure modes:
Rows may be skipped by active search filtering. Dependent controls are disabled rather than removed when their parent setting is off.
Important invariants:
Progression controls stay in the left column and display-density controls stay in the right column. Route connector color is disabled unless connector lines are enabled.
Internal logic:
Set headers, add progression rows top-to-bottom in the left column, then add route display/temp rows top-to-bottom in the right column.
Pseudocode:
set left header to Progression and right header to Route Display
y = rowsY
add default radius, skip-ahead, visible-only skip, reset, restart, and placement rows
y2 = rowsY
add progress, dimming, tracer/static, near-hide, static-hide, route-line, route-line-color, temp focus, temp expiry, and temp duration rows
Implementation notes:
The route line color row reuses the swatch-plus-hex picker pattern so it matches tracer and imported-route color controls.
AI self-check:
Verify row dependencies line up with their parent booleans and no controls overlap after adding the new rows.
]]*/
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
                "Hide reached static waypoints until the route resets.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show route connector lines",
                config.showRouteLines(), config::setShowRouteLines,
                "Draw lines between the centers of visible route waypoints.");
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
                "New temp waypoints expire by default.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Temp duration (min)",
                config.tempDefaultDurationMin(), this::setTempDefaultDuration,
                config::tempWaypointsExpireByDefault,
                "Default lifetime for expiring temp waypoints.");
    }

    /*[[AI-FN-DOC
Function:
addRouteLineColorRow
Purpose:
Add the settings row that edits the world route connector line color.
Why this exists:
Route connector lines have their own color setting, and the UI should match the existing swatch-plus-hex color picker pattern used elsewhere.
When to use:
Use from the Routes settings page when laying out route connector controls. Do not use for imported route defaults or tracer colors because those have separate labels and setters.
Inputs:
x and y are row coordinates; colW is the column width; enabled determines whether the row controls are active; tooltip is the explanatory text shown on hover.
Outputs:
No return value. Adds a label, hex input, and color swatch widget to the screen when the row matches current search filtering.
Side effects:
Mutates the screen's widget list, updates config.routeLineColor from text input or color picker callbacks, and tracks enabled-state dependencies.
Failure modes:
Invalid partial hex input is ignored while typing. Search filtering can skip rendering the row entirely.
Important invariants:
The swatch and hex field must stay synchronized, and the row must disable when route connector lines are off.
Internal logic:
Delegate to the shared RGB color-row helper with route-line labels, current value, setter, picker title, and dependency.
Pseudocode:
addRgbColorRow with route-line label, current routeLineColor, setRouteLineColor callback, dependency, and tooltip
Implementation notes:
The shared helper keeps this row visually identical to default waypoint, tracer, and imported-route color rows.
AI self-check:
Confirm the callback masks colors through WaypointerConfig and the swatch visually updates after both picker and hex edits.
]]*/
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

    /*[[AI-FN-DOC
Function:
addDefaultWaypointColorRow
Purpose:
Add the settings row that edits the default color for future manually-created waypoints.
Why this exists:
Users need one central default waypoint color that applies to new route points and temp waypoints without changing existing saved routes.
When to use:
Use from the Colors page when laying out global color defaults. Do not use for imported-route colors because imports have their own policy.
Inputs:
x and y are row coordinates; colW is the column width; tooltip explains the future-only behavior.
Outputs:
No return value. Adds label, hex input, and swatch controls when the row passes search filtering.
Side effects:
Updates WaypointerConfig.defaultWaypointColor from either the picker or six-digit hex input.
Failure modes:
Invalid partial hex input is ignored. The setter masks alpha bits.
Important invariants:
This setting affects future waypoint creation paths only and must not recolor existing route data.
Internal logic:
Delegate to the shared RGB color-row helper with the default waypoint color getter value and setter callback.
Pseudocode:
addRgbColorRow with default-waypoint label, current defaultWaypointColor, setDefaultWaypointColor callback, always enabled, and color picker metadata
Implementation notes:
The row intentionally uses the same picker mechanics as imported/tracer/connector color rows for muscle memory.
AI self-check:
Confirm imported routes still use importedRouteDefaultColor instead of this default.
]]*/
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

    /*[[AI-FN-DOC
Function:
addRgbColorRow
Purpose:
Render a consistent swatch-plus-hex settings row for a 24-bit RGB config color.
Why this exists:
Several settings expose colors, and sharing the control wiring keeps text parsing, picker behavior, disabled states, and visual affordances identical.
When to use:
Use for global/settings colors whose value is a single RGB integer and whose setter persists immediately. Do not use for per-waypoint route swatches inside GroupEditScreen.
Inputs:
x and y are row coordinates; colW is the column width; label is visible row text; editorName labels the hex box; currentColor is the initial RGB value; setter persists parsed colors; enabled controls active state; tooltip is hover text; pickerTitle names ColorPickerScreen; swatchTooltip labels the swatch.
Outputs:
No return value. Adds a label renderable, an EditBox, and a ColorSwatchButton when the row is visible.
Side effects:
Adds widgets, tracks dependent enabled state, opens ColorPickerScreen from the swatch, and calls setter when a valid color is picked or typed.
Failure modes:
Invalid or incomplete hex text is ignored while the user types. A null enabled supplier is not expected by callers.
Important invariants:
Only exactly six typed hex digits commit. The swatch and text box must stay synchronized after both picker and text edits.
Internal logic:
Normalize tooltip, apply search filtering, compute row geometry, create label/box/swatch, wire picker callback, wire parser callback, track dependencies, and add widgets.
Pseudocode:
tooltipComponent = normalized tooltip
if row is filtered out, return
geometry = settingRowGeometry
create label
create edit box initialized to currentColor as RRGGBB
create swatch initialized to currentColor
picker callback calls setter, updates box, updates swatch
box callback ignores empty/non-six-digit values; parses hex; calls setter; updates swatch
track box and swatch dependency
add box and swatch
Implementation notes:
The helper accepts currentColor as a value because settings screens are rebuilt after structural state changes; live synchronization inside a single row is handled locally.
AI self-check:
Verify all callers pass a setter that masks or validates RGB values at the config boundary.
]]*/
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
                    ColorPickerScreen.open(this, pickerTitle, pickerColor, picked -> {
                            setter.accept(picked);
                            box.setValue(String.format("%06X", picked & 0xFFFFFF));
                            if (swatchRef[0] != null) swatchRef[0].setColor(picked);
                    });
                });
        swatchRef[0] = swatch;
        swatch.setTooltip(Tooltip.create(Component.literal(swatchTooltip)));

        box.setResponder(v -> {
            if (v.isEmpty()) return;
            String trimmed = v.trim();
            if (trimmed.length() != 6) return;
            try {
                int parsed = Integer.parseInt(trimmed, 16) & 0xFFFFFF;
                setter.accept(parsed);
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

    /*[[AI-FN-DOC
Function:
addChatPage
Purpose:
Lay out settings that control chat scanning and chat-originated temporary waypoints.
Why this exists:
Chat detection is a distinct workflow from route import/export defaults, so it stays focused instead of sharing a mixed defaults page.
When to use:
Called from init and search rendering when the Chat page or matching chat settings are being built.
Inputs:
col1 and col2 are column x positions; colW is each column width; rowsY is the first row y coordinate; rowH is the vertical spacing per row.
Outputs:
No return value. Adds chat-related widgets and updates section headers.
Side effects:
Adds widgets and mutates WaypointerConfig when the user clicks rows.
Failure modes:
Rows may be skipped by search filtering. Dependent rows disable when chat coordinate detection is off.
Important invariants:
This page controls detection behavior only; import route color and export include defaults live on Import / Export.
Internal logic:
Place scan toggles in the left column and chat-created temp waypoint behavior in the right column.
Pseudocode:
set headers
add chat coordinate detection row
add chat code detection row
add auto-add chat temp waypoint row dependent on coordinate detection
Implementation notes:
The auto-add row remains here because it is triggered by chat coordinate detection, not by manual import.
AI self-check:
Verify no imported-route color or export include rows are left on this page.
]]*/
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

        int y2 = rowsY;
        addBoolRow(col2, y2, "Auto-add chat temp waypoints",
                config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                config::chatCoordDetection,
                "Create temp waypoints automatically from chat coordinates.");
    }

    /*[[AI-FN-DOC
Function:
addImportExportPage
Purpose:
Lay out settings that decide how imports are recolored and what metadata exports include by default.
Why this exists:
Import/export behavior grew beyond chat detection and needs one predictable page for route sharing defaults.
When to use:
Called from init and search rendering for the Import / Export page or matching rows.
Inputs:
col1 and col2 are column x coordinates; colW is the width of each settings column; rowsY is the first row y coordinate; rowH is the vertical spacing per row.
Outputs:
No return value. Adds import/export widgets and updates section headers.
Side effects:
Adds widgets and mutates WaypointerConfig from row callbacks.
Failure modes:
Rows can be skipped by search filtering. The imported color row disables outside One color import mode.
Important invariants:
Imported route color policy remains separate from the new default waypoint color, and export toggles only affect future share-code generation.
Internal logic:
Put import recolor defaults in the left column and export include toggles in the right column.
Pseudocode:
set headers
left column add imported route color mode and static color
right column add export include names, colors, radii, waypoint flags, and group metadata toggles
Implementation notes:
Keeping these rows together makes the route-sharing behavior clear without putting unrelated color defaults into Chat.
AI self-check:
Verify every export include flag has a row and imported route color mode still controls whether the color picker is active.
]]*/
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

    /*[[AI-FN-DOC
Function:
addImportedRouteColorRow
Purpose:
Add the settings row that edits the static default color applied to imported routes.
Why this exists:
Imported route recoloring is a separate policy from normal waypoint creation, so it needs a clearly labeled color row.
When to use:
Use from Import / Export or Colors pages when showing imported-route color defaults. Do not use for route connector or tracer colors.
Inputs:
x and y are row coordinates; colW is the column width; enabled controls whether one-color import mode is active; tooltip describes when the value applies.
Outputs:
No return value. Adds color controls if the row passes search filtering.
Side effects:
Updates WaypointerConfig.importedRouteDefaultColor through the shared RGB row helper.
Failure modes:
Invalid hex text is ignored by the helper. The row disables outside one-color import mode.
Important invariants:
Changing this value affects import policy only and must not alter existing saved route waypoint colors.
Internal logic:
Delegate to addRgbColorRow with imported-route labels, current default color, setter, dependency, and picker metadata.
Pseudocode:
addRgbColorRow with imported color label, importedRouteDefaultColor, setImportedRouteDefaultColor, enabled supplier, tooltip, and picker strings
Implementation notes:
This row intentionally uses the same helper as the default waypoint color row so the two defaults are visually comparable but behaviorally distinct.
AI self-check:
Confirm the row stays disabled unless importedRouteColorMode is STATIC.
]]*/
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
        addConfigCodeControlsRow(col1, y, colW);
        y += rowH;
        addConfigCodeStatusRow(col1, y, colW);

        int y2 = rowsY;
        addUpdateControlsRow(col2, y2, colW);
        y2 += rowH;
        addUpdateStatusRow(col2, y2, colW);
    }

    /*[[AI-FN-DOC
Function:
addConfigCodeControlsRow
Purpose:
Add the Copy config code and Import config code buttons to the Other settings page.
Why this exists:
Users asked for compact WPC: config import/export controls that live with maintenance actions rather than route sharing.
When to use:
Call from addOtherPage while laying out maintenance rows. Do not call from Import / Export because these controls replace settings, not routes.
Inputs:
x and y are row coordinates; colW is the available column width.
Outputs:
No return value. Adds two buttons when the row passes search filtering.
Side effects:
Button callbacks read/write the clipboard, encode/decode settings, may replace the live config, save settings, update status, and rebuild the screen.
Failure modes:
The row can be hidden by search filtering. Clipboard failures or malformed codes are reported in the status row without partial config mutation.
Important invariants:
Copy and import actions are explicit button presses; malformed imports must not modify the current config.
Internal logic:
Create equal-width buttons with clear labels and tooltips, wiring them to copyConfigCode and importConfigCode.
Pseudocode:
build tooltip
if filtered, return
compute row geometry
buttonW = half width after gap
add Copy config code button
add Import config code button
Implementation notes:
The buttons occupy the full row instead of using a left label so their text remains legible on compact settings widths.
AI self-check:
Verify both buttons remain reachable after tab wrapping and search filtering.
]]*/
    private void addConfigCodeControlsRow(int x, int y, int colW) {
        Component tooltipComponent = normalizedTooltipComponent(Component.literal(
                "Copy exports only Waypointer settings as a compact WPC: code.\n"
              + "Import reads a WPC: code from your clipboard and replaces all Waypointer settings."));
        String label = "Config code";
        if (!shouldRenderSettingRow(label, tooltipComponent)) return;
        int[] geometry = settingRowGeometry(x, y, colW);
        x = geometry[0];
        y = geometry[1];
        colW = geometry[2];
        int buttonW = (colW - GAP) / 2;

        Button copy = Button.builder(Component.literal("Copy config code"), this::copyConfigCode)
                .bounds(x, y, buttonW, BTN_H)
                .tooltip(Tooltip.create(tooltipComponent))
                .build();
        Button importButton = Button.builder(Component.literal("Import config code"), this::importConfigCode)
                .bounds(x + buttonW + GAP, y, colW - buttonW - GAP, BTN_H)
                .tooltip(Tooltip.create(tooltipComponent))
                .build();
        addRenderableWidget(copy);
        addRenderableWidget(importButton);
    }

    /*[[AI-FN-DOC
Function:
addConfigCodeStatusRow
Purpose:
Render inline feedback for the most recent config-code copy/import action.
Why this exists:
Clipboard and decode actions need immediate success or error feedback without opening a modal.
When to use:
Call directly below addConfigCodeControlsRow on the Other page.
Inputs:
x and y are row coordinates; colW is the maximum status text width.
Outputs:
No return value. Adds a text-only renderable if the row passes search filtering.
Side effects:
Mutates renderable-only widget list.
Failure modes:
Long status text is clipped by ComponentLabelWidget. Search filtering can hide the row.
Important invariants:
Status display never performs import/export work; it only reflects configCodeStatus.
Internal logic:
Choose the saved status component or a neutral clipboard hint, then add a component label.
Pseudocode:
if filtered, return
geometry = settingRowGeometry
status = configCodeStatus or neutral hint
add component label with status
Implementation notes:
The neutral hint keeps the controls discoverable before any action has run.
AI self-check:
Verify success and error statuses are styled by their Component formatting.
]]*/
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

    /*[[AI-FN-DOC
Function:
copyConfigCode
Purpose:
Encode current Waypointer settings as a compact WPC: code and copy it to the Minecraft clipboard.
Why this exists:
Users wanted a short, fun config export path that does not include route data.
When to use:
Used only as the Copy config code button callback.
Inputs:
b is the pressed button supplied by Minecraft; it is not mutated.
Outputs:
No return value. The clipboard receives a WPC: code on success.
Side effects:
Reads the live config, writes Minecraft clipboard, updates inline status, and rebuilds widgets to display feedback.
Failure modes:
Encoding or clipboard errors are caught and reported as an error status.
Important invariants:
Copying config never changes settings.
Internal logic:
Try to encode config and write clipboard; set green success status on success, otherwise set red failure status; rebuild widgets.
Pseudocode:
try code = encode config
  clipboard = code
  status success
catch runtime/throwable
  status failure
rebuild settings widgets
Implementation notes:
The broad catch keeps GUI button callbacks from crashing the screen on platform clipboard issues.
AI self-check:
Verify status is updated for both success and failure paths.
]]*/
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

    /*[[AI-FN-DOC
Function:
importConfigCode
Purpose:
Read a WPC: config code from the clipboard, validate it completely, and replace current settings with the decoded snapshot.
Why this exists:
Config import must be compact and convenient while still avoiding partial application of malformed user input.
When to use:
Used only as the Import config code button callback.
Inputs:
b is the pressed button supplied by Minecraft; it is not mutated.
Outputs:
No return value. On success, the live config is replaced and saved; on failure, status explains the issue.
Side effects:
Reads Minecraft clipboard, may replace and save WaypointerConfig, clears search/confirmation state after success, updates status, and rebuilds widgets.
Failure modes:
Clipboard read errors, empty clipboard, bad prefix, unsupported version, corrupt body, or unknown fields all produce red status without mutating the live config.
Important invariants:
Decode must complete before config.replaceWith is called, so malformed codes never partially apply.
Internal logic:
Read clipboard text, reject clipboard failures/blanks, decode into a fresh config, replace live config, clear transient UI filters, set success status, or report failure.
Pseudocode:
text = clipboard, or report clipboard read failure
if blank, set red status and rebuild
try decoded = WaypointerConfigCodec.decode(text)
  config.replaceWith(decoded)
  clear search and confirmations
  set green status
catch runtime
  set red status
rebuild widgets
Implementation notes:
Import intentionally resets omitted fields to defaults because decoded config starts from a fresh WaypointerConfig.
AI self-check:
Verify config.replaceWith is only reachable after decode returns successfully.
]]*/
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
            config.replaceWith(decoded);
            settingsSearchQuery = "";
            clearPendingConfirmation();
            setConfigCodeStatus(Component.literal("Config imported. Settings replaced.").withStyle(ChatFormatting.GREEN));
        } catch (RuntimeException e) {
            setConfigCodeStatus(Component.literal("Invalid config code.").withStyle(ChatFormatting.RED));
        }
        rebuildSettingsWidgets();
    }

    /*[[AI-FN-DOC
Function:
setConfigCodeStatus
Purpose:
Store the formatted status text shown under config-code controls.
Why this exists:
Copy and import callbacks both need to update the same inline feedback row before rebuilding the screen.
When to use:
Call after a config-code action succeeds or fails. Do not use for update-check status, which has separate state.
Inputs:
status is the formatted component to render; null clears the status and restores the neutral hint.
Outputs:
No return value.
Side effects:
Mutates the configCodeStatus field.
Failure modes:
None. Null is explicitly supported.
Important invariants:
The component may carry color formatting that ComponentLabelWidget should preserve when unclipped.
Internal logic:
Assign the field directly.
Pseudocode:
configCodeStatus = status
Implementation notes:
Separated mostly to keep copy/import callbacks symmetrical and easy to audit.
AI self-check:
Verify no unrelated status fields are touched.
]]*/
    private void setConfigCodeStatus(Component status) {
        configCodeStatus = status;
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

    /*[[AI-FN-DOC
Function:
addTracerColorRow
Purpose:
Add the settings row that edits the fallback tracer color.
Why this exists:
Tracer color is only used when tracer color inheritance is off, but users need a consistent place to set that fallback.
When to use:
Use from Colors or related settings pages when showing tracer fallback color. Do not use for waypoint labels or route connector colors.
Inputs:
x and y are row coordinates; colW is the column width; enabled controls whether the row can currently be edited; tooltip describes when the color applies.
Outputs:
No return value. Adds the color row if it matches current search filtering.
Side effects:
Updates WaypointerConfig.tracerColor through the shared RGB row helper.
Failure modes:
Invalid hex input is ignored by the helper; disabled state prevents interaction while preserving visibility.
Important invariants:
Changing this value must not disable tracer inheritance or change waypoint colors.
Internal logic:
Delegate to addRgbColorRow with tracer labels, current tracer color, setter, dependency, and picker metadata.
Pseudocode:
addRgbColorRow with tracer color label, tracerColor, setTracerColor, enabled supplier, tooltip, and picker strings
Implementation notes:
The helper keeps parsing and swatch synchronization consistent with other settings colors.
AI self-check:
Verify the row is disabled when tracer inheritance makes this fallback inactive.
]]*/
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
