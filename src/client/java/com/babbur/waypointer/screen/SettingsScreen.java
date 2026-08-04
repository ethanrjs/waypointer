package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.WaypointerClient;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.debug.ConfigChangeHistory;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.screen.settings.PerfStressTestController;
import com.babbur.waypointer.screen.settings.RecentSettings;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import com.babbur.waypointer.screen.settings.SettingsPresets;
import com.babbur.waypointer.screen.settings.SettingsSearch;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;

/**
 * Catalog-driven settings screen: category sidebar on the left, one scrollable
 * settings column on the right (the WaypointerScreen shape, and the MoulConfig
 * shape SkyHanni/NEU users already know).
 *
 * <p>Replaces the old tabbed two-fixed-column ConfigScreen, whose pages could
 * not scroll and overflowed past the footer at common GUI scales. Every row
 * here comes from {@link SettingsCatalog}; this class only owns geometry,
 * scrolling, widget lifecycle, and chrome.
 *
 * <p>Scroll mechanism: row control widgets are registered via
 * {@code addWidget} (input + narration only) and rendered manually inside a
 * scissor each frame at {@code homeY - scrollOffset}. Rows outside the
 * viewport get {@code visible = false}, which kills both clicks and tab-focus
 * ({@code isActive() = visible && active}). Scrolling never rebuilds widgets,
 * so EditBox focus and caret survive; structural changes (category switch,
 * collapse toggles) rebuild through a tick-deferred flag, never from inside a
 * widget callback.
 */
public final class SettingsScreen extends Screen {

    private static final int SETTING_ROW_PITCH = 24;
    private static final int GROUP_CAPTION_H = 18;
    private static final int GROUP_SPACER_H = 8;
    private static final int CHILD_INDENT = 12;
    private static final int CONTROL_RIGHT_INSET = 10;
    private static final int ENUM_BUTTON_W = 140;
    private static final int NUMBER_BOX_W = 80;
    private static final int COLOR_BOX_W = 76;
    private static final int COLOR_SWATCH_W = 72;
    private static final int SCROLL_BOTTOM_SLACK = 8;
    private static final int SEARCH_W_MAX = 240;
    private static final int SEARCH_CLEAR_W = 52;
    private static final int DOT_AREA_W = 14;
    private static final long HIGHLIGHT_MS = 1_500L;

    /** Sidebar pseudo-category backed by {@link RecentSettings}, not the catalog. */
    static final String RECENT_ID = "recent";

    /** Settings whose value changes the row structure (collapse parents / masters). */
    private static final Set<String> STRUCTURAL_IDS = computeStructuralIds();

    /** Category the user last had open; restored when the screen reopens. */
    private static String lastCategoryId;

    private final Screen parent;
    private final WaypointerConfig config;
    private final DungeonConfig dungeonConfig;
    private final WaypointerConfig defaultsMain = new WaypointerConfig();
    private final DungeonConfig defaultsDungeon = new DungeonConfig();

    private String activeCategoryId;
    private int scrollOffset;
    private int sidebarScrollOffset;
    private boolean rebuildPending;
    private final List<Row> rows = new ArrayList<>();
    private int contentHeight;

    private EditBox searchBox;
    private Button searchClearButton;
    private Button perfCancelButton;
    private String searchQuery = "";
    private boolean refocusSearchAfterRebuild;
    private String pendingScrollToSettingId;
    private String highlightSettingId;
    private long highlightUntilMillis;
    private Map<String, Integer> searchCategoryCounts = Map.of();

    // Destructive actions arm on first click and commit on a second within the window.
    private static final int CONFIRM_NONE = 0;
    private static final int CONFIRM_DISABLE_ALL = 1;
    private static final int CONFIRM_RESET_DEFAULTS = 2;
    private static final long CONFIRMATION_WINDOW_MS = 3_000L;
    private int pendingConfirmationAction = CONFIRM_NONE;
    private long pendingConfirmationUntilMillis;

    private Component configCodeStatus;

    public SettingsScreen(Screen parent, WaypointerConfig config, DungeonConfig dungeonConfig) {
        super(Component.translatable("waypointer.screen.settings.title"));
        this.parent = parent;
        this.config = config;
        this.dungeonConfig = dungeonConfig;
        boolean lastStillValid = categoryById(lastCategoryId) != null
                || (RECENT_ID.equals(lastCategoryId) && !RecentSettings.isEmpty());
        this.activeCategoryId = lastStillValid
                ? lastCategoryId
                : SettingsCatalog.categories().get(0).id();
        // If a stress test was interrupted by a crash, put the user's settings back.
        PerfStressTestController.recoverInterruptedTest(config);
    }

    /**
     * A settings screen opened directly at (and briefly highlighting) one
     * setting's row — the deep-link entry point other screens use, e.g.
     * {@code /wp debug}'s Perf test button.
     */
    public static SettingsScreen atSetting(Screen parent, WaypointerConfig config,
                                           DungeonConfig dungeonConfig, String settingId) {
        SettingsScreen screen = new SettingsScreen(parent, config, dungeonConfig);
        SettingsCatalog.Category home = categoryOf(settingId);
        if (home != null) {
            screen.activeCategoryId = home.id();
            lastCategoryId = home.id();
            screen.pendingScrollToSettingId = settingId;
            screen.highlightSettingId = settingId;
            screen.highlightUntilMillis = System.currentTimeMillis() + HIGHLIGHT_MS;
        }
        return screen;
    }

    // --- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void init() {
        rows.clear();
        searchBox = null;
        searchClearButton = null;
        perfCancelButton = null;
        Layout layout = layout();
        buildRows(layout);
        addSearchBox(layout);

        GuiTokens.ButtonSpec done = doneSpec();
        GuiTokens.layoutFooter(width, height - FOOTER_H, List.of(), done,
                this::addRenderableWidget, font);
        perfCancelButton = styledButton((width - 88) / 2,
                PAD_OUTER + font.lineHeight * 2 + GAP, 88, BTN_H,
                Component.translatable("waypointer.screen.settings.perf.cancel"), button -> {
                    PerfStressTestController.cancelIfRunning();
                    rebuildPending = true;
                }, Tooltip.create(Component.translatable(
                        "waypointer.screen.settings.perf.cancel.tooltip")));
        perfCancelButton.visible = false;
        addRenderableWidget(perfCancelButton);
    }

    @Override
    protected void setInitialFocus() {
        // Screen.rebuildWidgets() applies initial focus after init(), so restore
        // the search field here rather than while its widgets are being built.
        if (refocusSearchAfterRebuild && searchBox != null) {
            refocusSearchAfterRebuild = false;
            setInitialFocus(searchBox);
            return;
        }
        super.setInitialFocus();
    }

    // --- search box (chrome) -------------------------------------------------------------------

    private void addSearchBox(Layout layout) {
        int x = layout.mainLeft() + GAP;
        int available = layout.mainRight() - GAP - x;
        int searchW = Math.min(SEARCH_W_MAX, available - SEARCH_CLEAR_W - GAP_TIGHT);
        if (searchW < 60) {
            refocusSearchAfterRebuild = false;
            return;
        }
        searchBox = new EditBox(font, x, layout.top() + 4, searchW, BTN_H,
                Component.translatable("waypointer.screen.settings.search"));
        searchBox.setMaxLength(80);
        searchBox.setValue(searchQuery);
        searchBox.setHint(Component.translatable("waypointer.screen.settings.search"));
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        searchClearButton = styledButton(x + searchW + GAP_TIGHT, layout.top() + 4,
                SEARCH_CLEAR_W, BTN_H, Component.translatable("waypointer.common.clear"),
                b -> clearSearch(), Tooltip.create(Component.translatable(
                        "waypointer.screen.settings.search.clear.tooltip")));
        updateClearButton();
        addRenderableWidget(searchClearButton);
    }

    private void onSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(searchQuery)) return;
        searchQuery = next;
        rebuildPending = true;
        refocusSearchAfterRebuild = true;
        updateClearButton();
    }

    private void clearSearch() {
        if (searchBox != null) {
            searchBox.setValue("");
        }
        onSearchChanged("");
    }

    private void updateClearButton() {
        if (searchClearButton != null) {
            searchClearButton.active = settingsSearchClearButtonActive(searchQuery);
        }
    }

    static boolean settingsSearchClearButtonActive(String query) {
        return query != null && !query.isEmpty();
    }

    private boolean searchActive() {
        return !searchQuery.trim().isEmpty();
    }

    private GuiTokens.ButtonSpec doneSpec() {
        return new GuiTokens.ButtonSpec(
                Component.translatable("gui.done").getString(), -1, this::onClose,
                Tooltip.create(Component.translatable("waypointer.screen.settings.done.tooltip")));
    }

    @Override
    public void tick() {
        super.tick();
        if (rebuildPending) {
            rebuildPending = false;
            rebuildWidgets();
            return;
        }
        if (clearExpiredConfirmation()) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        PerfStressTestController.cancelIfRunning();
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        PerfStressTestController.cancelIfRunning();
        super.removed();
    }

    // --- layout ------------------------------------------------------------------------------

    private Layout layout() {
        int footerSpace = GuiTokens.footerHeight(width, List.of(), doneSpec(), font);
        int top = PAD_OUTER + font.lineHeight + GAP;
        int bottom = height - footerSpace - GAP_SECTION;
        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP;
        int mainRight = width - PAD_OUTER;
        int rowsTop = top + 4 + BTN_H + GAP;
        return new Layout(top, bottom, sidebarLeft, sidebarRight, mainLeft, mainRight, rowsTop);
    }

    private record Layout(int top, int bottom, int sidebarLeft, int sidebarRight,
                          int mainLeft, int mainRight, int rowsTop) {}

    static int maxScrollFor(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - Math.max(0, viewportHeight) + SCROLL_BOTTOM_SLACK);
    }

    /**
     * Clicks in the content column outside the scissored viewport must be
     * swallowed: input hit-testing has no scissor, so a row widget half-hidden
     * at a viewport edge would otherwise still take clicks through the chrome
     * strips above and below the list.
     */
    static boolean inContentDeadStrip(double mx, double my, int mainLeft, int mainRight,
                                      int top, int rowsTop, int bottom, int footerTop) {
        if (mx < mainLeft || mx > mainRight) return false;
        if (my >= top && my < rowsTop) return true;
        return my > bottom && my < footerTop;
    }

    // --- row model ---------------------------------------------------------------------------

    private static final class Row {
        final Setting setting;      // null for group-caption rows
        final String caption;       // non-null for group-caption rows
        final boolean header;       // parent/master row with a disclosure glyph
        final boolean expanded;     // headers only: children currently visible
        final boolean child;        // indented under a header
        final int y;                // content-local top (before scroll)
        final int height;
        final List<WidgetSlot> widgets = new ArrayList<>();
        int controlLeft;            // leftmost control x, for label clipping
        String chip;                // "Tracers >" category chip on search/Recent rows
        String jumpTargetId;        // label click jumps to this setting in its category
        WidgetSlot dot;             // modified-from-default indicator / per-row reset
        Supplier<Component> status; // live status line (config code / performance results)

        Row(Setting setting, String caption, boolean header, boolean expanded, boolean child,
            int y, int height) {
            this.setting = setting;
            this.caption = caption;
            this.header = header;
            this.expanded = expanded;
            this.child = child;
            this.y = y;
            this.height = height;
            this.controlLeft = Integer.MAX_VALUE;
        }
    }

    /**
     * A control widget plus its unscrolled Y; each frame it renders at
     * {@code homeY - scrollOffset}. When {@code activeWhen} is non-null it owns
     * the widget's {@code active} state each frame instead of the row's shared
     * enabled predicate for controls whose availability is independent of the
     * surrounding setting row.
     */
    private record WidgetSlot(AbstractWidget widget, int homeY, Supplier<Boolean> activeWhen) {
        WidgetSlot(AbstractWidget widget, int homeY) {
            this(widget, homeY, null);
        }
    }

    private void buildRows(Layout layout) {
        searchCategoryCounts = Map.of();
        if (RECENT_ID.equals(activeCategoryId) && RecentSettings.isEmpty()) {
            activeCategoryId = SettingsCatalog.categories().get(0).id();
        }

        if (searchActive()) {
            buildSearchRows(layout);
        } else if (RECENT_ID.equals(activeCategoryId)) {
            buildRecentRows(layout);
        } else {
            buildCategoryRows(layout);
        }

        consumePendingScroll(layout);
        scrollOffset = MathUtil.clamp(scrollOffset, 0,
                maxScrollFor(contentHeight, layout.bottom() - layout.rowsTop()));
    }

    private void buildCategoryRows(Layout layout) {
        SettingsCatalog.Category category = categoryById(activeCategoryId);
        if (category == null) return;

        boolean bodyVisible = category.bodyVisibleWhen() == null
                || category.bodyVisibleWhen().test(config, dungeonConfig);

        int y = 0;
        boolean first = true;
        for (SettingsCatalog.Group group : category.groups()) {
            List<Setting> settings = visibleSettings(category, group, bodyVisible);
            if (settings.isEmpty()) continue;
            if (!first) y += GROUP_SPACER_H;
            first = false;

            if (group.label() != null && bodyVisible) {
                rows.add(new Row(null,
                        Component.translatableWithFallback(
                                SettingsCatalog.groupTranslationKey(category, group),
                                group.label()).getString(),
                        false, false, false, y, GROUP_CAPTION_H));
                y += GROUP_CAPTION_H;
            }

            boolean groupExpanded = group.childrenVisibleWhen() == null
                    || group.childrenVisibleWhen().test(config, dungeonConfig);
            for (int i = 0; i < settings.size(); i++) {
                Setting setting = settings.get(i);
                boolean isHeader = isHeaderRow(category, group, setting, i);
                boolean expanded = isHeader && headerExpanded(category, group, setting, bodyVisible, groupExpanded);
                boolean isChild = !isHeader && group.parentSettingId() != null;
                Row row = new Row(setting, null, isHeader, expanded, isChild, y, SETTING_ROW_PITCH);
                buildRowWidgets(row, layout);
                buildResetDot(row, layout);
                rows.add(row);
                y += SETTING_ROW_PITCH;
                y = addStatusRowFor(setting, y);
            }
        }
        contentHeight = y;
    }

    private int addStatusRowFor(Setting setting, int y) {
        Supplier<Component> status = switch (setting.id()) {
            case SettingsCatalog.ACTION_CONFIG_CODE -> this::configCodeStatusComponent;
            case SettingsCatalog.ACTION_PERF_TEST -> PerfStressTestController::statusComponent;
            default -> null;
        };
        if (status == null) return y;
        Row row = new Row(null, null, false, false, false, y, GROUP_CAPTION_H);
        row.status = status;
        rows.add(row);
        return y + GROUP_CAPTION_H;
    }

    /**
     * Search results render as one flat scrollable list — every match, no
     * truncation. Each row carries a category chip and stays live-editable.
     */
    private void buildSearchRows(Layout layout) {
        List<SettingsSearch.Match> matches =
                SettingsSearch.search(searchQuery, SettingsCatalog.categories());
        Map<String, Integer> counts = new HashMap<>();
        int y = 0;
        for (SettingsSearch.Match match : matches) {
            counts.merge(match.categoryId(), 1, Integer::sum);
            SettingsCatalog.Category category = categoryById(match.categoryId());
            y = addChippedRow(match.setting(),
                    category == null ? "" : categoryLabel(category), y, layout);
        }
        contentHeight = y;
        searchCategoryCounts = counts;
    }

    /** The Recent pseudo-category: most recently changed settings first. */
    private void buildRecentRows(Layout layout) {
        int y = 0;
        for (String id : RecentSettings.mostRecentFirst()) {
            Setting setting = SettingsCatalog.byId(id);
            if (setting == null) continue;
            if (setting.kind() == Setting.Kind.ACTION || setting.kind() == Setting.Kind.HIDDEN) continue;
            if (setting.store() == Setting.Store.DUNGEON && dungeonConfig == null) continue;
            SettingsCatalog.Category home = categoryOf(id);
            y = addChippedRow(setting, home == null ? "" : categoryLabel(home), y, layout);
        }
        contentHeight = y;
    }

    private int addChippedRow(Setting setting, String categoryLabel, int y, Layout layout) {
        Row row = new Row(setting, null, false, false, false, y, SETTING_ROW_PITCH);
        row.chip = categoryLabel.isEmpty() ? null : categoryLabel + " >";
        row.jumpTargetId = setting.id();
        buildRowWidgets(row, layout);
        buildResetDot(row, layout);
        rows.add(row);
        return y + SETTING_ROW_PITCH;
    }

    /** After a jump, land the target row a third of the way down the viewport. */
    private void consumePendingScroll(Layout layout) {
        if (pendingScrollToSettingId == null) return;
        Row target = rowBySettingId(pendingScrollToSettingId);
        if (target == null) {
            // Collapsed child (its parent is off): land on the parent instead.
            target = rowBySettingId(parentIdOf(pendingScrollToSettingId));
        }
        if (target != null) {
            int viewportH = layout.bottom() - layout.rowsTop();
            scrollOffset = MathUtil.clamp(
                    target.y - Math.max(0, (viewportH - target.height) / 3),
                    0, maxScrollFor(contentHeight, viewportH));
        }
        pendingScrollToSettingId = null;
    }

    private Row rowBySettingId(String id) {
        if (id == null) return null;
        for (Row row : rows) {
            if (row.setting != null && row.setting.id().equals(id)) return row;
        }
        return null;
    }

    /** Group parent (or category master) that gates the given setting's row. */
    private static String parentIdOf(String settingId) {
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) {
                    if (!setting.id().equals(settingId)) continue;
                    if (group.parentSettingId() != null
                            && !group.parentSettingId().equals(settingId)) {
                        return group.parentSettingId();
                    }
                    return category.masterSettingId();
                }
            }
        }
        return null;
    }

    private static SettingsCatalog.Category categoryOf(String settingId) {
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) {
                    if (setting.id().equals(settingId)) return category;
                }
            }
        }
        return null;
    }

    /** Which of the group's settings get rows right now (collapse logic). */
    private List<Setting> visibleSettings(SettingsCatalog.Category category,
                                          SettingsCatalog.Group group, boolean bodyVisible) {
        List<Setting> out = new ArrayList<>();
        boolean groupExpanded = group.childrenVisibleWhen() == null
                || group.childrenVisibleWhen().test(config, dungeonConfig);
        for (int i = 0; i < group.settings().size(); i++) {
            Setting setting = group.settings().get(i);
            if (setting.kind() == Setting.Kind.HIDDEN) continue;
            if (setting.store() == Setting.Store.DUNGEON && dungeonConfig == null) continue;
            boolean isMaster = setting.id().equals(category.masterSettingId());
            if (!bodyVisible && !isMaster) continue;
            boolean isGroupParent = group.parentSettingId() != null && i == 0;
            if (!isGroupParent && group.parentSettingId() != null && !groupExpanded) continue;
            out.add(setting);
        }
        return out;
    }

    private static boolean isHeaderRow(SettingsCatalog.Category category,
                                       SettingsCatalog.Group group, Setting setting, int index) {
        if (setting.id().equals(category.masterSettingId())) return true;
        return group.parentSettingId() != null && index == 0;
    }

    private boolean headerExpanded(SettingsCatalog.Category category, SettingsCatalog.Group group,
                                   Setting setting, boolean bodyVisible, boolean groupExpanded) {
        if (setting.id().equals(category.masterSettingId())) return bodyVisible;
        return groupExpanded;
    }

    private static SettingsCatalog.Category categoryById(String id) {
        if (id == null) return null;
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            if (category.id().equals(id)) return category;
        }
        return null;
    }

    private static Set<String> computeStructuralIds() {
        Set<String> out = new HashSet<>();
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            if (category.masterSettingId() != null) out.add(category.masterSettingId());
            for (SettingsCatalog.Group group : category.groups()) {
                if (group.parentSettingId() != null) out.add(group.parentSettingId());
            }
        }
        return out;
    }

    // --- widget construction -------------------------------------------------------------------

    private void buildRowWidgets(Row row, Layout layout) {
        Setting setting = row.setting;
        int controlRight = layout.mainRight() - CONTROL_RIGHT_INSET;
        int rowTop = layout.rowsTop() + row.y;

        switch (setting.kind()) {
            case BOOL -> buildBoolControl(row, setting, controlRight, rowTop);
            case NUMBER -> buildNumberControl(row, setting, controlRight, rowTop);
            case ENUM -> buildEnumControl(row, setting, controlRight, rowTop);
            case COLOR -> buildColorControl(row, setting, controlRight, rowTop);
            case ACTION -> buildActionControls(row, setting, controlRight, rowTop);
            default -> { }
        }
    }

    private void buildActionControls(Row row, Setting setting, int controlRight, int rowTop) {
        switch (setting.id()) {
            case SettingsCatalog.ACTION_CONFIG_CODE -> buildConfigCodeControls(row, setting, controlRight, rowTop);
            case SettingsCatalog.ACTION_PRESETS -> buildPresetControls(row, setting, controlRight, rowTop);
            case SettingsCatalog.ACTION_DISABLE_ALL -> buildDialogActionControl(row, setting,
                    controlRight, rowTop, "waypointer.screen.settings.action.disable_all",
                    this::confirmDisableAll);
            case SettingsCatalog.ACTION_RESET_DEFAULTS -> buildConfirmActionControl(row, setting,
                    controlRight, rowTop, CONFIRM_RESET_DEFAULTS,
                    "waypointer.screen.settings.action.reset_defaults",
                    "waypointer.screen.settings.action.confirm_reset",
                    this::confirmedResetDefaults);
            case SettingsCatalog.ACTION_PERF_TEST -> buildPerfTestControls(row, setting, controlRight, rowTop);
            case SettingsCatalog.ACTION_WAYPOINT_PAINT -> buildDialogActionControl(row, setting,
                    controlRight, rowTop, "waypointer.screen.settings.action.open_painter",
                    this::openWaypointPainter);
            case SettingsCatalog.ACTION_WAYPOINT_EDITOR_KEYBINDS -> buildWaypointEditorKeybindControl(
                    row, setting, controlRight, rowTop);
            default -> { }
        }
    }

    private void buildWaypointEditorKeybindControl(Row row, Setting setting,
                                                    int controlRight, int rowTop) {
        int buttonW = 92;
        Button button = styledButton(controlRight - buttonW, 0, buttonW, BTN_H,
                Component.literal("Controls..."), b -> MinecraftCompat.setScreen(minecraft,
                        new KeyBindsScreen(this, minecraft.options)), tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private void buildPerfTestControls(Row row, Setting setting, int controlRight, int rowTop) {
        int buttonW = 100;
        Button copy = styledButton(controlRight - buttonW, 0, buttonW, BTN_H,
                Component.translatable("waypointer.screen.settings.perf.copy_report"), b -> {
                    if (PerfStressTestController.hasReport()) {
                        minecraft.keyboardHandler.setClipboard(PerfStressTestController.report());
                        PerfStressTestController.noteReportCopied();
                    }
                }, tooltipFor(setting, Component.translatable(
                        "waypointer.screen.settings.perf.copy_report.tooltip")));

        boolean running = PerfStressTestController.running();
        Button run = styledButton(controlRight - buttonW * 2 - GAP, 0, buttonW, BTN_H,
                Component.translatable(running
                        ? "waypointer.screen.settings.perf.cancel"
                        : "waypointer.screen.settings.perf.run"), b -> {
                    if (PerfStressTestController.running()) {
                        PerfStressTestController.cancelIfRunning();
                    } else {
                        PerfStressTestController.start(config);
                    }
                    rebuildPending = true;
                }, tooltipOrNull(setting));

        registerRowWidget(row, run, rowTop + 2);
        registerRowWidget(row, copy, rowTop + 2, PerfStressTestController::hasReport);
    }

    private void openWaypointPainter() {
        if (WaypointerClient.manager() == null) return;
        MinecraftCompat.setScreen(minecraft,
                new WaypointPainterScreen(this, config, WaypointerClient.manager()));
    }

    private void buildConfigCodeControls(Row row, Setting setting, int controlRight, int rowTop) {
        int buttonW = 112;
        Button importButton = styledButton(controlRight - buttonW, 0, buttonW, BTN_H,
                Component.translatable("waypointer.screen.settings.config.import"),
                this::importConfigCode, tooltipFor(setting, Component.translatable(
                        "waypointer.screen.settings.config.import.tooltip")));
        Button copyButton = styledButton(controlRight - buttonW * 2 - GAP, 0, buttonW, BTN_H,
                Component.translatable("waypointer.screen.settings.config.copy"),
                this::copyConfigCode, tooltipOrNull(setting));
        registerRowWidget(row, copyButton, rowTop + 2);
        registerRowWidget(row, importButton, rowTop + 2);
    }

    private void buildPresetControls(Row row, Setting setting, int controlRight, int rowTop) {
        int x = controlRight;
        String[][] presets = {
                {"waypointer.screen.settings.preset.everything", "everything"},
                {"waypointer.screen.settings.preset.default", "default"},
                {"waypointer.screen.settings.preset.minimal", "minimal"},
        };
        for (String[] preset : presets) {
            Component label = Component.translatable(preset[0]);
            String id = preset[1];
            int w = Math.max(60, font.width(label) + 16);
            x -= w;
            Button button = styledButton(x, 0, w, BTN_H, label,
                    b -> applyPreset(id), tooltipOrNull(setting));
            registerRowWidget(row, button, rowTop + 2);
            x -= GAP_TIGHT;
        }
    }

    /** Action button that opens a modal confirmation dialog (the apply-defaults pattern). */
    private void buildDialogActionControl(Row row, Setting setting, int controlRight, int rowTop,
                                          String labelKey, Runnable onConfirmFlow) {
        Button button = styledButton(controlRight - ENUM_BUTTON_W, 0, ENUM_BUTTON_W, BTN_H,
                Component.translatable(labelKey), b -> onConfirmFlow.run(), tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private void buildConfirmActionControl(Row row, Setting setting, int controlRight, int rowTop,
                                           int action, String labelKey, String armedLabelKey,
                                           Runnable confirmed) {
        String current = confirmationActive(action) ? armedLabelKey : labelKey;
        Button button = styledButton(controlRight - ENUM_BUTTON_W, 0, ENUM_BUTTON_W, BTN_H,
                Component.translatable(current), b -> {
                    if (!consumeOrArmConfirmation(action)) return;
                    confirmed.run();
                }, tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private void buildBoolControl(Row row, Setting setting, int controlRight, int rowTop) {
        GuiTokens.StyledCheckbox checkbox = styledCheckbox(0, 0, BTN_H,
                settingLabel(setting),
                Boolean.TRUE.equals(setting.get(config, dungeonConfig)),
                v -> applySetting(setting, v), tooltipOrNull(setting));
        checkbox.setX(controlRight - checkbox.getWidth());
        int homeY = rowTop + (row.height - checkbox.getHeight()) / 2;
        registerRowWidget(row, checkbox, homeY);
    }

    private void buildNumberControl(Row row, Setting setting, int controlRight, int rowTop) {
        EditBox box = new EditBox(font, controlRight - NUMBER_BOX_W, 0, NUMBER_BOX_W, BTN_H,
                settingLabel(setting));
        box.setMaxLength(24);
        box.setValue(setting.formatValue(setting.get(config, dungeonConfig)));
        box.setResponder(v -> {
            if (v.isEmpty()) return;
            try {
                applySetting(setting, Double.parseDouble(v.trim()));
            } catch (NumberFormatException ignored) {
                // Partial edits are expected while typing; keep the last valid value.
            }
        });
        box.setTooltip(tooltipOrNull(setting));
        registerRowWidget(row, box, rowTop + 2);
    }

    private void buildEnumControl(Row row, Setting setting, int controlRight, int rowTop) {
        Button button = styledButton(controlRight - ENUM_BUTTON_W, 0, ENUM_BUTTON_W, BTN_H,
                enumLabelFor(setting),
                b -> {
                    Object next = nextEnumValue(setting, setting.get(config, dungeonConfig));
                    applySetting(setting, next);
                    b.setMessage(enumLabelFor(setting));
                }, tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private Component enumLabelFor(Setting setting) {
        Object current = setting.get(config, dungeonConfig);
        for (int i = 0; i < setting.enumOptions().size(); i++) {
            if (Objects.equals(setting.enumOptions().get(i).value(), current)) {
                return Component.translatable(setting.enumOptionTranslationKey(i));
            }
        }
        return Component.literal(String.valueOf(current));
    }

    static Object nextEnumValue(Setting setting, Object current) {
        List<Setting.EnumOption> options = setting.enumOptions();
        for (int i = 0; i < options.size(); i++) {
            if (Objects.equals(options.get(i).value(), current)) {
                return options.get((i + 1) % options.size()).value();
            }
        }
        return options.get(0).value();
    }

    private void buildColorControl(Row row, Setting setting, int controlRight, int rowTop) {
        int swatchX = controlRight - COLOR_SWATCH_W;
        int boxX = swatchX - GAP - COLOR_BOX_W;
        int currentColor = ((Number) setting.get(config, dungeonConfig)).intValue();

        EditBox box = new EditBox(font, boxX, 0, COLOR_BOX_W, BTN_H,
                settingLabel(setting));
        box.setMaxLength(6);
        box.setValue(String.format("%06X", currentColor & 0xFFFFFF));
        box.setTooltip(tooltipOrNull(setting));

        ColorSwatchButton[] swatchRef = new ColorSwatchButton[1];
        ColorSwatchButton swatch = new ColorSwatchButton(
                swatchX, 0, COLOR_SWATCH_W, BTN_H,
                Component.translatable("waypointer.screen.settings.color.pick").getString(),
                currentColor,
                () -> {
                    int pickerColor = swatchRef[0] == null ? currentColor : swatchRef[0].getColor();
                    ColorPickerScreen.open(this,
                            Component.translatable(setting.colorPickerTitleTranslationKey()),
                            pickerColor,
                            picked -> {
                                applySetting(setting, picked);
                                box.setValue(String.format("%06X", picked & 0xFFFFFF));
                                if (swatchRef[0] != null) swatchRef[0].setColor(picked);
                            });
                });
        swatchRef[0] = swatch;
        swatch.setTooltip(tooltipFor(setting, Component.translatable(
                setting.colorSwatchTooltipTranslationKey())));

        box.setResponder(v -> {
            Integer parsed = parseRgbHexColor(v);
            if (parsed == null) return;
            applySetting(setting, parsed);
            swatch.setColor(parsed);
        });

        registerRowWidget(row, box, rowTop + 2);
        registerRowWidget(row, swatch, rowTop + 2);
    }

    private void registerRowWidget(Row row, AbstractWidget widget, int homeY) {
        registerRowWidget(row, widget, homeY, null);
    }

    /** As above, but {@code activeWhen} takes over the widget's per-frame active state. */
    private void registerRowWidget(Row row, AbstractWidget widget, int homeY, Supplier<Boolean> activeWhen) {
        widget.visible = false; // first frame's refresh decides real visibility
        row.widgets.add(new WidgetSlot(widget, homeY, activeWhen));
        row.controlLeft = Math.min(row.controlLeft, widget.getX());
        addWidget(widget);
    }

    /**
     * Small accent square left of the control: shown only when the setting
     * differs from its default; hovering names the default, clicking restores
     * it. Answers "what did I change?" without a separate diff view.
     */
    private void buildResetDot(Row row, Layout layout) {
        Setting setting = row.setting;
        if (setting == null || setting.store() == Setting.Store.NONE) return;
        if (row.controlLeft == Integer.MAX_VALUE) return;

        Object defaultValue = setting.defaultValue(defaultsMain, defaultsDungeon);
        ResetDotButton dot = new ResetDotButton(row.controlLeft - DOT_AREA_W, 0, 12, BTN_H,
                b -> {
                    applySetting(setting, defaultValue);
                    rebuildPending = true; // controls re-read config on rebuild
                });
        dot.setTooltip(tooltipFor(setting, Component.translatable(
                "waypointer.screen.settings.reset.tooltip",
                localizedSettingValue(setting, defaultValue))));
        dot.visible = false;
        row.dot = new WidgetSlot(dot, layout.rowsTop() + row.y + 2);
        addWidget(dot);
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

    // --- mutation funnel -----------------------------------------------------------------------

    /**
     * Every control routes its change through here so recent-change tracking
     * and structural rebuilds cannot be forgotten per-widget. Rebuilds are
     * tick-deferred: callbacks fire while the widget list is being iterated.
     */
    private void applySetting(Setting setting, Object value) {
        String before = setting.formatValue(setting.get(config, dungeonConfig));
        setting.set(config, dungeonConfig, value);
        String after = setting.formatValue(setting.get(config, dungeonConfig));
        ConfigChangeHistory.recordSetting(setting.id(), before, after);
        RecentSettings.record(setting.id());
        if (STRUCTURAL_IDS.contains(setting.id())) {
            rebuildPending = true;
        }
    }

    /** Bulk operations rewrite many settings at once; per-setting history goes stale. */
    private void afterBulkConfigChange() {
        RecentSettings.clear();
        searchQuery = "";
        clearPendingConfirmation();
        rebuildPending = true;
    }

    // --- two-click confirmations ----------------------------------------------------------------

    private boolean consumeOrArmConfirmation(int action) {
        long now = System.currentTimeMillis();
        if (pendingConfirmationAction == action && now <= pendingConfirmationUntilMillis) {
            return true;
        }
        pendingConfirmationAction = action;
        pendingConfirmationUntilMillis = now + CONFIRMATION_WINDOW_MS;
        rebuildPending = true; // relabel the armed button
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

    /** Same modal-dialog confirmation shape as applying the Default preset. */
    private void confirmDisableAll() {
        WaypointerConfig preview = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(config));
        preview.disableAllSettings();
        int changed = SettingsCatalog.countChangedSettings(config, preview);
        if (dungeonConfig.enabled()) changed++;
        if (dungeonConfig.hideCompletedRooms()) changed++;
        if (dungeonConfig.autoCompleteRoomsOnGreenCheckmark()) changed++;
        ConfirmScreen confirmScreen = new ConfirmScreen(
                confirmed -> {
                    if (confirmed) confirmedDisableAll();
                    MinecraftCompat.setScreen(minecraft, this);
                },
                Component.translatable("waypointer.screen.settings.disable.confirm.title"),
                Component.translatable(changed == 1
                        ? "waypointer.screen.settings.changed.one"
                        : "waypointer.screen.settings.changed.many", changed),
                Component.translatable("waypointer.screen.settings.action.disable_all"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirmScreen);
    }

    private void confirmedDisableAll() {
        config.disableAllSettings(dungeonConfig);
        ConfigChangeHistory.recordBulk("Disabled all settings");
        afterBulkConfigChange();
    }

    private void confirmedResetDefaults() {
        config.resetToDefaults();
        dungeonConfig.resetToDefaults();
        ConfigChangeHistory.recordBulk("Reset settings to defaults");
        afterBulkConfigChange();
    }

    // --- config codes ---------------------------------------------------------------------------

    private void copyConfigCode(Button b) {
        try {
            String code = WaypointerConfigCodec.encode(config);
            minecraft.keyboardHandler.setClipboard(code);
            setConfigCodeStatus(Component.translatable(
                    "waypointer.screen.settings.config.copied").withStyle(ChatFormatting.GREEN));
        } catch (Throwable t) {
            setConfigCodeStatus(Component.translatable(
                    "waypointer.screen.settings.config.copy_failed").withStyle(ChatFormatting.RED));
        }
    }

    private void importConfigCode(Button b) {
        String text;
        try {
            text = minecraft.keyboardHandler.getClipboard();
        } catch (Throwable t) {
            setConfigCodeStatus(Component.translatable(
                    "waypointer.screen.settings.config.clipboard_failed").withStyle(ChatFormatting.RED));
            return;
        }
        if (text == null || text.isBlank()) {
            setConfigCodeStatus(Component.translatable(
                    "waypointer.screen.settings.config.clipboard_empty").withStyle(ChatFormatting.RED));
            return;
        }

        try {
            WaypointerConfig decoded = WaypointerConfigCodec.decode(text);
            int changedSettings = SettingsCatalog.countChangedSettings(config, decoded);
            showImportConfigConfirmation(decoded, changedSettings);
        } catch (RuntimeException e) {
            setConfigCodeStatus(Component.translatable(
                    "waypointer.screen.settings.config.invalid").withStyle(ChatFormatting.RED));
        }
    }

    private void showImportConfigConfirmation(WaypointerConfig decoded, int changedSettings) {
        if (decoded == null) {
            setConfigCodeStatus(Component.translatable(
                    "waypointer.screen.settings.config.invalid").withStyle(ChatFormatting.RED));
            return;
        }

        Component title = Component.translatable(
                "waypointer.screen.settings.config.confirm.title");
        Component message = Component.translatable(changedSettings == 1
                ? "waypointer.screen.settings.config.confirm.one"
                : "waypointer.screen.settings.config.confirm.many", changedSettings);
        ConfirmScreen confirmScreen = new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        applyConfirmedConfigImport(decoded, changedSettings);
                    } else {
                        setConfigCodeStatus(Component.translatable(
                                "waypointer.screen.settings.config.cancelled")
                                .withStyle(ChatFormatting.GRAY));
                    }
                    MinecraftCompat.setScreen(minecraft, this);
                }, title, message,
                Component.translatable("waypointer.screen.settings.config.import_settings"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirmScreen);
    }

    private void applyConfirmedConfigImport(WaypointerConfig decoded, int changedSettings) {
        config.replaceWith(decoded);
        ConfigChangeHistory.recordBulk("Imported config code (" + changedSettings + " changed)");
        afterBulkConfigChange();
        setConfigCodeStatus(Component.translatable(changedSettings == 1
                ? "waypointer.screen.settings.config.imported.one"
                : "waypointer.screen.settings.config.imported.many", changedSettings)
                .withStyle(ChatFormatting.GREEN));
    }

    private void setConfigCodeStatus(Component status) {
        configCodeStatus = status;
        rebuildPending = true;
    }

    private Component configCodeStatusComponent() {
        return configCodeStatus == null
                ? Component.translatable("waypointer.screen.settings.config.status")
                        .withStyle(ChatFormatting.GRAY)
                : configCodeStatus;
    }

    // --- presets --------------------------------------------------------------------------------

    private void applyPreset(String presetId) {
        if ("default".equals(presetId)) {
            WaypointerConfig defaultMain = new WaypointerConfig();
            DungeonConfig defaultDungeon = new DungeonConfig();
            int changed = SettingsCatalog.countChangedSettings(
                    config, dungeonConfig, defaultMain, defaultDungeon);
            confirmPreset("waypointer.screen.settings.preset.default", changed, confirmed -> {
                if (confirmed) confirmedResetDefaults();
            });
            return;
        }
        WaypointerConfig preset = "minimal".equals(presetId)
                ? SettingsPresets.minimal(config)
                : SettingsPresets.everything(config);
        String nameKey = "minimal".equals(presetId)
                ? "waypointer.screen.settings.preset.minimal"
                : "waypointer.screen.settings.preset.everything";
        confirmPreset(nameKey, SettingsCatalog.countChangedSettings(config, preset), confirmed -> {
            if (confirmed) {
                config.replaceWith(preset);
                ConfigChangeHistory.recordBulk("Applied " + presetId + " preset");
                afterBulkConfigChange();
            }
        });
    }

    private void confirmPreset(String nameKey, int changed,
                               java.util.function.Consumer<Boolean> onResult) {
        ConfirmScreen confirmScreen = new ConfirmScreen(
                confirmed -> {
                    onResult.accept(confirmed);
                    MinecraftCompat.setScreen(minecraft, this);
                },
                Component.translatable("waypointer.screen.settings.preset.confirm.title",
                        Component.translatable(nameKey)),
                Component.translatable(changed == 1
                        ? "waypointer.screen.settings.changed.one"
                        : "waypointer.screen.settings.changed.many", changed),
                Component.translatable("waypointer.screen.settings.preset.apply"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirmScreen);
    }

    // --- render ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Layout layout = layout();
        // Advance the performance stress test by one frame; scenario switches
        // change config values, so controls rebuild to reflect them.
        if (PerfStressTestController.onFrame()) {
            rebuildPending = true;
        }
        if (PerfStressTestController.running()) {
            hideWidgetsDuringPerformanceTest();
            String status = font.plainSubstrByWidth(
                    PerfStressTestController.statusLine(), Math.max(0, width - PAD_OUTER * 2));
            String hint = font.plainSubstrByWidth(Component.translatable(
                    "waypointer.screen.settings.perf.restore_hint").getString(),
                    Math.max(0, width - PAD_OUTER * 2));
            g.text(font, status, Math.max(PAD_OUTER, (width - font.width(status)) / 2),
                    PAD_OUTER, TEXT, true);
            g.text(font, hint, Math.max(PAD_OUTER, (width - font.width(hint)) / 2),
                    PAD_OUTER + font.lineHeight + 2, TEXT_DIM, true);
            perfCancelButton.visible = true;
            perfCancelButton.extractRenderState(g, mouseX, mouseY, partial);
            return;
        }
        refreshRowStates(layout);

        g.fill(0, 0, width, height, SURFACE);
        g.text(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        Component saveHint = Component.translatable("waypointer.screen.settings.auto_save");
        g.text(font, saveHint,
                width - PAD_OUTER - font.width(saveHint),
                PAD_OUTER, TEXT_DIM, false);

        renderSidebar(g, layout, mouseX, mouseY);

        // Content panel + header strip.
        g.fill(layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom(), SURFACE_SUBTLE);
        renderHeaderStrip(g, layout);

        super.extractRenderState(g, mouseX, mouseY, partial);

        g.enableScissor(layout.mainLeft(), layout.rowsTop(), layout.mainRight(), layout.bottom());
        for (Row row : rows) {
            int rowTop = layout.rowsTop() + row.y - scrollOffset;
            if (rowTop + row.height <= layout.rowsTop() || rowTop >= layout.bottom()) continue;
            renderRow(g, row, rowTop, layout, mouseX, mouseY, partial);
        }
        g.disableScissor();

        renderScrollbar(g, layout.mainRight() - 4, layout.rowsTop(), layout.bottom());
    }

    private void hideWidgetsDuringPerformanceTest() {
        for (var child : children()) {
            if (child instanceof AbstractWidget widget) widget.visible = false;
        }
    }

    /** Per-frame widget upkeep: scroll position, viewport culling, dependency graying. */
    private void refreshRowStates(Layout layout) {
        for (Row row : rows) {
            int rowTop = layout.rowsTop() + row.y - scrollOffset;
            boolean inViewport = rowTop + row.height > layout.rowsTop() && rowTop < layout.bottom();
            boolean enabled = row.setting == null
                    || row.setting.isEnabled(config, dungeonConfig);
            for (WidgetSlot slot : row.widgets) {
                slot.widget().setY(slot.homeY() - scrollOffset);
                slot.widget().visible = inViewport;
                slot.widget().active = slot.activeWhen() != null ? slot.activeWhen().get() : enabled;
            }
            if (row.dot != null) {
                row.dot.widget().setY(row.dot.homeY() - scrollOffset);
                row.dot.widget().visible = inViewport
                        && row.setting.isModified(config, dungeonConfig, defaultsMain, defaultsDungeon);
            }
        }
    }

    private void renderHeaderStrip(GuiGraphicsExtractor g, Layout layout) {
        Component info;
        if (searchActive()) {
            int matches = rows.size();
            info = Component.translatable(matches == 0
                    ? "waypointer.screen.settings.matches.none"
                    : matches == 1
                    ? "waypointer.screen.settings.matches.one"
                    : "waypointer.screen.settings.matches.many", matches);
        } else if (RECENT_ID.equals(activeCategoryId)) {
            info = Component.translatable("waypointer.screen.settings.recent");
        } else {
            SettingsCatalog.Category category = categoryById(activeCategoryId);
            info = category == null ? Component.empty() : categoryComponent(category);
        }
        g.text(font, info, layout.mainRight() - GAP - font.width(info),
                layout.top() + 10, TEXT_DIM, false);

        if (rows.isEmpty() && searchActive()) {
            int textX = layout.mainLeft() + GAP;
            g.text(font, Component.translatable("waypointer.screen.settings.empty"),
                    textX, layout.rowsTop() + 8, TEXT, false);
            g.text(font, Component.translatable("waypointer.screen.settings.empty.hint"),
                    textX, layout.rowsTop() + 8 + 14, TEXT_DIM, false);
        }
    }

    /** Sidebar entries: the Recent pseudo-category (when non-empty) + catalog categories. */
    private List<SidebarEntry> sidebarEntries() {
        List<SidebarEntry> out = new ArrayList<>();
        if (!RecentSettings.isEmpty()) {
            out.add(new SidebarEntry(RECENT_ID,
                    Component.translatable("waypointer.screen.settings.recent").getString()));
        }
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            out.add(new SidebarEntry(category.id(), categoryLabel(category)));
        }
        return out;
    }

    private record SidebarEntry(String id, String label) {}

    private void renderSidebar(GuiGraphicsExtractor g, Layout layout, int mouseX, int mouseY) {
        int x1 = layout.sidebarLeft();
        int x2 = layout.sidebarRight();
        g.fill(x1, layout.top(), x2, layout.bottom(), SURFACE);
        g.fill(x2, layout.top(), x2 + 1, layout.bottom(), BORDER);
        g.text(font, Component.translatable("waypointer.screen.settings.categories"),
                x1 + GAP, layout.top() + 10, TEXT_DIM, false);

        int rowsTop = sidebarRowsTop(layout.top());
        int rowsBottom = layout.bottom() - GAP_TIGHT;
        if (rowsBottom <= rowsTop) return;

        List<SidebarEntry> entries = sidebarEntries();
        boolean searching = searchActive();
        sidebarScrollOffset = MathUtil.clamp(sidebarScrollOffset, 0,
                maxSidebarScroll(entries.size(), rowsBottom - rowsTop));
        g.enableScissor(x1, rowsTop, x2, rowsBottom);
        for (int i = 0; i < entries.size(); i++) {
            int rowY = rowsTop - sidebarScrollOffset + i * ROW_H;
            if (rowY + ROW_H <= rowsTop) continue;
            if (rowY >= rowsBottom) break;
            SidebarEntry entry = entries.get(i);
            boolean selected = !searching && entry.id().equals(activeCategoryId);
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= Math.min(rowsBottom, rowY + ROW_H);
            if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);

            int bg = selected ? SELECTED : hovered ? HOVER : 0;
            if (bg != 0) g.fill(x1, rowY, x2, rowY + ROW_H, bg);
            if (selected) g.fill(x1, rowY, x1 + 2, rowY + ROW_H, ACCENT);

            int labelX = x1 + GAP + 2;
            int labelMaxW = x2 - GAP - labelX;
            if (searching) {
                Integer count = searchCategoryCounts.get(entry.id());
                if (count != null && count > 0) {
                    String countStr = Integer.toString(count);
                    int countX = x2 - GAP - font.width(countStr);
                    g.text(font, countStr, countX, rowY + 6, TEXT_MUTED, false);
                    labelMaxW = countX - GAP_TIGHT - labelX;
                }
            }
            String clipped = font.plainSubstrByWidth(entry.label(), Math.max(12, labelMaxW));
            g.text(font, clipped, labelX, rowY + 6, selected ? TEXT : TEXT_DIM, false);
        }
        g.disableScissor();
    }

    static int sidebarRowsTop(int panelTop) {
        return panelTop + 24;
    }

    static int maxSidebarScroll(int categoryCount, int viewportHeight) {
        return Math.max(0, categoryCount * ROW_H - Math.max(0, viewportHeight));
    }

    private void renderRow(GuiGraphicsExtractor g, Row row, int rowTop, Layout layout,
                           int mouseX, int mouseY, float partial) {
        int labelX = layout.mainLeft() + GAP + (row.child ? CHILD_INDENT : 0);

        if (row.status != null) {
            Component status = row.status.get();
            int maxW = layout.mainRight() - CONTROL_RIGHT_INSET - labelX;
            if (font.width(status) <= maxW) {
                g.text(font, status, labelX, rowTop + 5, TEXT_DIM, false);
            } else {
                g.text(font, font.plainSubstrByWidth(status.getString(), maxW),
                        labelX, rowTop + 5, TEXT_DIM, false);
            }
            return;
        }

        if (row.caption != null) {
            g.text(font, row.caption, labelX, rowTop + 5, TEXT_MUTED, false);
            return;
        }

        Setting setting = row.setting;
        boolean enabled = setting.isEnabled(config, dungeonConfig);

        if (isHighlighted(setting)) {
            g.fill(layout.mainLeft() + 1, rowTop, layout.mainRight() - 8,
                    rowTop + row.height, SELECTED);
            g.fill(layout.mainLeft() + 1, rowTop, layout.mainLeft() + 3,
                    rowTop + row.height, ACCENT);
        }

        boolean jumpHovered = row.jumpTargetId != null
                && mouseX >= labelX && mouseX < labelLimit(row, layout)
                && mouseY >= rowTop && mouseY < rowTop + row.height
                && mouseY >= layout.rowsTop() && mouseY < layout.bottom();
        if (jumpHovered) g.requestCursor(CursorTypes.POINTING_HAND);

        if (row.chip != null) {
            g.text(font, row.chip, labelX, rowTop + 8, TEXT_MUTED, false);
            labelX += font.width(row.chip) + 6;
        }

        if (row.header) {
            String glyph = row.expanded ? "v" : ">";
            g.text(font, glyph, labelX, rowTop + 8, TEXT_DIM, false);
            labelX += font.width(glyph) + 5;
        }

        int labelMaxW = labelLimit(row, layout) - labelX;
        String clipped = font.plainSubstrByWidth(
                settingLabel(setting).getString(), Math.max(12, labelMaxW));
        int labelColor = row.header ? TEXT : enabled ? TEXT : TEXT_DIM;
        g.text(font, clipped, labelX, rowTop + 8, labelColor, false);

        for (WidgetSlot slot : row.widgets) {
            slot.widget().extractRenderState(g, mouseX, mouseY, partial);
        }
        if (row.dot != null) {
            row.dot.widget().extractRenderState(g, mouseX, mouseY, partial);
        }
    }

    private boolean isHighlighted(Setting setting) {
        return setting != null
                && setting.id().equals(highlightSettingId)
                && System.currentTimeMillis() < highlightUntilMillis;
    }

    private int labelLimit(Row row, Layout layout) {
        int controlLeft = row.controlLeft == Integer.MAX_VALUE
                ? layout.mainRight() - CONTROL_RIGHT_INSET
                : row.controlLeft;
        return controlLeft - GAP_TIGHT - DOT_AREA_W;
    }

    private void renderScrollbar(GuiGraphicsExtractor g, int x, int y1, int y2) {
        int viewportHeight = y2 - y1;
        if (viewportHeight <= 0 || contentHeight <= viewportHeight) return;

        int thumbHeight = Math.max(12, viewportHeight * viewportHeight / contentHeight);
        int maxScroll = maxScrollFor(contentHeight, viewportHeight);
        int travel = viewportHeight - thumbHeight;
        if (maxScroll <= 0 || travel <= 0) return;

        int thumbY = y1 + MathUtil.clamp(scrollOffset, 0, maxScroll) * travel / maxScroll;
        g.fill(x, y1 + 2, x + 2, y2 - 2, BORDER);
        g.fill(x, thumbY, x + 2, thumbY + thumbHeight, TEXT_MUTED);
    }

    // --- tooltips ------------------------------------------------------------------------------

    static Component tooltipFor(Setting setting) {
        Component description = setting.tooltip().isBlank()
                ? Component.empty()
                : Component.translatableWithFallback(
                        setting.tooltipTranslationKey(),
                        normalizeTooltipText(setting.tooltip()));
        return tooltipComponent(setting, description);
    }

    private static Tooltip tooltipFor(Setting setting, Component text) {
        return Tooltip.create(tooltipComponent(setting, text));
    }

    private static Component tooltipComponent(Setting setting, Component text) {
        MutableComponent out = settingLabel(setting).copy().withStyle(ChatFormatting.GRAY);
        if (text != null && !text.getString().isEmpty()) {
            out.append(Component.literal("\n"));
            out.append(text.copy().withStyle(ChatFormatting.WHITE));
        }
        if (setting.impact() != null) {
            out.append(Component.literal("\n\n"));
            out.append(Component.translatable("waypointer.settings.impact.line",
                    Component.translatableWithFallback(
                            setting.impact().wordTranslationKey(),
                            setting.impact().word()))
                    .withStyle(chatColor(setting.impact())));
        }
        return out;
    }

    private static Component settingLabel(Setting setting) {
        return Component.translatableWithFallback(
                setting.labelTranslationKey(), setting.label());
    }

    private static Component categoryComponent(SettingsCatalog.Category category) {
        return Component.translatableWithFallback(
                SettingsCatalog.categoryTranslationKey(category), category.label());
    }

    private static String categoryLabel(SettingsCatalog.Category category) {
        return categoryComponent(category).getString();
    }

    private static Component localizedSettingValue(Setting setting, Object value) {
        if (value == null) return Component.empty();
        if (setting.kind() == Setting.Kind.BOOL) {
            return Component.translatable(Boolean.TRUE.equals(value)
                    ? "options.on" : "options.off");
        }
        if (setting.kind() == Setting.Kind.ENUM) {
            for (int i = 0; i < setting.enumOptions().size(); i++) {
                if (Objects.equals(setting.enumOptions().get(i).value(), value)) {
                    return Component.translatableWithFallback(
                            setting.enumOptionTranslationKey(i),
                            setting.enumOptions().get(i).label());
                }
            }
        }
        return Component.literal(setting.formatValue(value));
    }

    /** Tooltip for a setting's control, or null when there is nothing to show. */
    private static Tooltip tooltipOrNull(Setting setting) {
        Component tooltip = tooltipFor(setting);
        return tooltip.getString().isEmpty() ? null : Tooltip.create(tooltip);
    }

    private static ChatFormatting chatColor(Setting.Impact impact) {
        return switch (impact) {
            case HIGH -> ChatFormatting.RED;
            case MEDIUM -> ChatFormatting.GOLD;
            case LOW -> ChatFormatting.GREEN;
        };
    }

    static String normalizeTooltipText(String raw) {
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

    // --- input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (PerfStressTestController.running()) {
            return perfCancelButton != null && perfCancelButton.isMouseOver(event.x(), event.y())
                    ? perfCancelButton.mouseClicked(event, doubleClick)
                    : true;
        }
        double mx = event.x();
        double my = event.y();
        Layout layout = layout();

        // The search field and its clear button sit in the header strip, which the
        // content dead strip would otherwise swallow before super.mouseClicked can
        // focus them — leaving the field unclickable/untypeable. Give them the click first.
        if (searchBox != null && searchBox.isMouseOver(mx, my)) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return searchBox.mouseClicked(event, doubleClick);
        }
        if (searchClearButton != null && searchClearButton.isMouseOver(mx, my)) {
            return searchClearButton.mouseClicked(event, doubleClick);
        }

        if (inContentDeadStrip(mx, my, layout.mainLeft(), layout.mainRight(),
                layout.top(), layout.rowsTop(), layout.bottom(), height - FOOTER_H)) {
            return true;
        }

        if (super.mouseClicked(event, doubleClick)) return true;

        // Sidebar click -> switch category.
        if (mx >= layout.sidebarLeft() && mx <= layout.sidebarRight()
                && my >= layout.top() && my <= layout.bottom()) {
            int rowsTop = sidebarRowsTop(layout.top());
            int rowsBottom = layout.bottom() - GAP_TIGHT;
            if (my >= rowsTop && my < rowsBottom) {
                List<SidebarEntry> entries = sidebarEntries();
                int index = (int) ((my - rowsTop + sidebarScrollOffset) / ROW_H);
                if (index >= 0 && index < entries.size()) {
                    selectCategory(entries.get(index).id());
                }
            }
            return true;
        }

        // Label click on a search/Recent row -> jump to the setting in its home category.
        if (mx >= layout.mainLeft() && mx <= layout.mainRight()
                && my >= layout.rowsTop() && my <= layout.bottom()) {
            Row row = rowAt(my, layout);
            if (row != null && row.jumpTargetId != null && mx < labelLimit(row, layout)) {
                jumpToSetting(row.jumpTargetId);
                return true;
            }
        }
        return false;
    }

    private Row rowAt(double my, Layout layout) {
        for (Row row : rows) {
            int rowTop = layout.rowsTop() + row.y - scrollOffset;
            if (my >= rowTop && my < rowTop + row.height) return row;
        }
        return null;
    }

    private void jumpToSetting(String settingId) {
        SettingsCatalog.Category home = categoryOf(settingId);
        if (home == null) return;
        activeCategoryId = home.id();
        lastCategoryId = home.id();
        searchQuery = "";
        pendingScrollToSettingId = settingId;
        highlightSettingId = settingId;
        highlightUntilMillis = System.currentTimeMillis() + HIGHLIGHT_MS;
        rebuildWidgets();
    }

    private void selectCategory(String categoryId) {
        if (categoryId.equals(activeCategoryId) && !searchActive()) return;
        activeCategoryId = categoryId;
        if (!RECENT_ID.equals(categoryId)) lastCategoryId = categoryId;
        searchQuery = "";
        scrollOffset = 0;
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (PerfStressTestController.running()) {
            if (event.key() == GLFW_KEY_ESCAPE) {
                PerfStressTestController.cancelIfRunning();
                rebuildPending = true;
                return true;
            }
            return super.keyPressed(event);
        }
        if (event.key() == GLFW_KEY_F && controlDown() && searchBox != null) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (super.keyPressed(event)) return true; // focused EditBoxes keep Home/End

        Layout layout = layout();
        int viewportH = layout.bottom() - layout.rowsTop();
        int maxScroll = maxScrollFor(contentHeight, viewportH);
        int page = Math.max(SETTING_ROW_PITCH, viewportH - SETTING_ROW_PITCH);
        switch (event.key()) {
            case GLFW_KEY_PAGE_UP -> {
                scrollOffset = MathUtil.clamp(scrollOffset - page, 0, maxScroll);
                return true;
            }
            case GLFW_KEY_PAGE_DOWN -> {
                scrollOffset = MathUtil.clamp(scrollOffset + page, 0, maxScroll);
                return true;
            }
            case GLFW_KEY_HOME -> {
                scrollOffset = 0;
                return true;
            }
            case GLFW_KEY_END -> {
                scrollOffset = maxScroll;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // Poll the window directly: per-event modifier bits aren't exposed on this
    // version's KeyEvent (same workaround as GroupEditScreen's hasShiftDown).
    private static boolean controlDown() {
        var win = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(win, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(win, GLFW_KEY_RIGHT_CONTROL);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (PerfStressTestController.running()) return true;
        Layout layout = layout();
        if (mouseX >= layout.sidebarLeft() && mouseX <= layout.sidebarRight()
                && mouseY >= layout.top() && mouseY <= layout.bottom()) {
            int rowsTop = sidebarRowsTop(layout.top());
            int listHeight = layout.bottom() - GAP_TIGHT - rowsTop;
            int maxScroll = maxSidebarScroll(sidebarEntries().size(), listHeight);
            sidebarScrollOffset = MathUtil.clamp(
                    sidebarScrollOffset - (int) (vert * ROW_H), 0, maxScroll);
            return true;
        }
        if (mouseX >= layout.mainLeft() && mouseX <= layout.mainRight()
                && mouseY >= layout.top() && mouseY <= layout.bottom()) {
            int maxScroll = maxScrollFor(contentHeight, layout.bottom() - layout.rowsTop());
            scrollOffset = MathUtil.clamp(
                    scrollOffset - (int) (vert * SETTING_ROW_PITCH), 0, maxScroll);
            return true;
        }
        return false;
    }

    /** Modified-from-default indicator that doubles as a one-click reset. */
    private static final class ResetDotButton extends Button {
        private ResetDotButton(int x, int y, int width, int height, OnPress onPress) {
            super(x, y, width, height,
                    Component.translatable("waypointer.screen.settings.reset"),
                    onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            int cx = getX() + getWidth() / 2;
            int cy = getY() + getHeight() / 2;
            g.fill(cx - 2, cy - 2, cx + 2, cy + 2, isHoveredOrFocused() ? TEXT : ACCENT);
        }
    }
}
