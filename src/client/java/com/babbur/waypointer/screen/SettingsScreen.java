package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.crystal.CrystalHollowsProjection;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.config.AppearanceScope;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.debug.ConfigChangeHistory;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;

public final class SettingsScreen extends Screen {

    private static final List<PresetOption> PRESET_OPTIONS = List.of(
            new PresetOption("minimal", "Minimal", ""),
            new PresetOption("default", "Default", ""),
            new PresetOption("nothing", "Disable All", "Disable all settings."));

    private static final int SETTING_ROW_PITCH = 24;
    static final int CHECKBOX_SIZE = 20;
    private static final int GROUP_CAPTION_H = 18;
    private static final int CHILD_INDENT = 28;
    private static final int CONTROL_RIGHT_INSET = 10;
    private static final int MIN_CONTROL_W = 140;
    private static final int MIN_SETTING_LABEL_W = 40;
    private static final int BUTTON_TEXT_PADDING = 16;
    private static final int SCROLL_BOTTOM_SLACK = 8;
    private static final int SEARCH_W_MAX = 240;
    private static final int SEARCH_CLEAR_W = 52;
    private static final int DOT_AREA_W = 14;
    private static final long HIGHLIGHT_MS = 1_500L;

    static final String RECENT_ID = "recent";
    static final String CHANGED_ID = "changed";
    static final String SCOPED_SECTION_PREFIX = "appearance.";
    static final String SCOPED_VISIBILITY_ID = "scope.visibility";

    private static final int SCOPED_BACKDROP = 0x20000000;
    private static final int SCOPED_PANEL = 0x800E1014;

    private static final Set<String> STRUCTURAL_IDS = computeStructuralIds();

    private static String lastCategoryId;

    private final Screen parent;

    private final WaypointerConfig config;
    private final WaypointerConfig globalConfig;
    private final AppearanceTarget target;
    private final List<SettingsCatalog.Category> categories;
    private WaypointerConfig inheritedSnapshot;
    private Map<String, Component> zoneOverrideTags = Map.of();
    private Button resetLookButton;
    private Button scopeSwitcher;
    private final ContextMenuOverlay contextMenu = new ContextMenuOverlay();
    private final DungeonConfig dungeonConfig;
    private final WaypointerConfig defaultsMain = new WaypointerConfig();
    private final DungeonConfig defaultsDungeon = new DungeonConfig();

    private String activeCategoryId;
    private int scrollOffset;
    private int sidebarScrollOffset;
    private boolean rebuildPending;
    private final List<Row> rows = new ArrayList<>();
    private int contentHeight;
    private int controlWidth = MIN_CONTROL_W;
    private int actionClusterWidth;

    private EditBox searchBox;
    private Button searchClearButton;
    private ListNavigationWidget sidebarNavigation;
    private String searchQuery = "";
    private boolean refocusSearchAfterRebuild;
    private boolean refocusSidebarAfterRebuild;
    private String pendingScrollToSettingId;
    private String highlightSettingId;
    private long highlightUntilMillis;
    private Map<String, Integer> searchCategoryCounts = Map.of();
    private Component railTooltip;

    private static final int CONFIRM_NONE = 0;
    private static final int CONFIRM_RESET_DEFAULTS = 2;
    private static final int CONFIRM_RESET_LOOK = 3;
    private static final long CONFIRMATION_WINDOW_MS = 3_000L;
    private int pendingConfirmationAction = CONFIRM_NONE;
    private long pendingConfirmationUntilMillis;

    private Component configCodeStatus;

    public SettingsScreen(Screen parent, WaypointerConfig config, DungeonConfig dungeonConfig) {
        this(parent, config, dungeonConfig, null);
    }

    private SettingsScreen(Screen parent, WaypointerConfig config, DungeonConfig dungeonConfig,
                           AppearanceTarget target) {
        super(target == null
                ? Component.translatable("waypointer.screen.settings.title")
                : Component.translatable("waypointer.screen.appearance.title", target.name()));
        this.parent = parent;
        this.target = target;
        this.globalConfig = config;
        this.config = target == null ? config : target.scope().effective();
        this.dungeonConfig = dungeonConfig;
        this.categories = target == null
                ? SettingsCatalog.categories()
                : appearanceCategories(target::includes);
        if (target != null) {
            this.activeCategoryId = target.scope().count() > 0 ? CHANGED_ID : categories.getFirst().id();
            return;
        }
        boolean lastStillValid = categoryById(lastCategoryId) != null
                && categoryAvailable(lastCategoryId, config)
                || (RECENT_ID.equals(lastCategoryId) && !RecentSettings.isEmpty());
        this.activeCategoryId = lastStillValid
                ? lastCategoryId
                : categories.getFirst().id();
    }

    static SettingsScreen forAppearance(Screen parent, WaypointerConfig config,
                                        DungeonConfig dungeonConfig, AppearanceTarget target) {
        return new SettingsScreen(parent, config, dungeonConfig, Objects.requireNonNull(target));
    }

    /** Opens a route's look; false when the route has no editable look (a downloaded room copy). */
    public static boolean openRouteAppearance(com.babbur.waypointer.core.ActiveGroupManager manager,
                                              WaypointerConfig config, WaypointGroup route) {
        AppearanceTarget target = AppearanceTarget.route(manager, config, route, manager::fireDataChanged);
        if (target == null) return false;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> MinecraftCompat.setScreen(client,
                forAppearance(null, config, WaypointerClient.dungeonConfig(), target)));
        return true;
    }

    /** Opens one waypoint's look; false when it has no editable look. */
    public static boolean openWaypointAppearance(com.babbur.waypointer.core.ActiveGroupManager manager,
                                                 WaypointerConfig config, WaypointGroup route, int index) {
        AppearanceTarget target = AppearanceTarget.waypoint(manager, config, route, index, manager::fireDataChanged);
        if (target == null) return false;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> MinecraftCompat.setScreen(client,
                forAppearance(null, config, WaypointerClient.dungeonConfig(), target)));
        return true;
    }

    /** Opens the settings screen. */
    public static void open(WaypointerConfig config) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> MinecraftCompat.setScreen(client,
                new SettingsScreen(null, config, WaypointerClient.dungeonConfig())));
    }

    static List<SettingsCatalog.Category> appearanceCategories(Predicate<String> include) {
        Set<String> supported = WaypointStyles.supportedProperties();
        Predicate<Setting> keep = setting -> supported.contains(setting.id())
                && include.test(setting.id())
                && setting.store() == Setting.Store.MAIN
                && setting.kind() != Setting.Kind.HIDDEN && setting.kind() != Setting.Kind.ACTION;
        List<SettingsCatalog.Category> out = new ArrayList<>();
        List<SettingsCatalog.Group> elsewhere = new ArrayList<>();
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            if ("appearance".equals(category.id())) {
                for (Section section : sections(category)) {
                    List<SettingsCatalog.Group> groups = new ArrayList<>();
                    for (SettingsCatalog.Group group : section.groups()) {
                        SettingsCatalog.Group kept = keptGroup(group, keep);
                        if (kept != null) groups.add(kept);
                    }
                    if (groups.isEmpty()) continue;
                    String label = section.heading().label() == null
                            ? category.label() : section.heading().label();
                    out.add(new SettingsCatalog.Category(SCOPED_SECTION_PREFIX + section.id(),
                            label, null, null, List.copyOf(groups)));
                }
            } else {
                for (SettingsCatalog.Group group : category.groups()) {
                    SettingsCatalog.Group kept = keptGroup(group, keep);
                    if (kept != null) elsewhere.add(kept);
                }
            }
        }
        if (!elsewhere.isEmpty()) {
            out.add(new SettingsCatalog.Category(SCOPED_VISIBILITY_ID, "Visibility", null, null,
                    List.copyOf(elsewhere)));
        }
        return List.copyOf(out);
    }

    private static SettingsCatalog.Group keptGroup(SettingsCatalog.Group group, Predicate<Setting> keep) {
        List<Setting> settings = group.settings().stream().filter(keep).toList();
        if (settings.isEmpty()) return null;
        boolean parentKept = group.parentSettingId() != null
                && settings.getFirst().id().equals(group.parentSettingId());
        return parentKept
                ? new SettingsCatalog.Group(null, null, group.parentSettingId(),
                        group.childrenVisibleWhen(), settings)
                : new SettingsCatalog.Group(null, null, null, null, settings);
    }

    public static SettingsScreen atSetting(Screen parent, WaypointerConfig config,
                                           DungeonConfig dungeonConfig, String settingId) {
        SettingsScreen screen = new SettingsScreen(parent, config, dungeonConfig);
        SettingsCatalog.Category home = screen.categoryOf(settingId);
        if (home != null && categoryAvailable(home.id(), config)) {
            screen.activeCategoryId = home.id();
            lastCategoryId = home.id();
            screen.pendingScrollToSettingId = settingId;
            screen.highlightSettingId = settingId;
            screen.highlightUntilMillis = System.currentTimeMillis() + HIGHLIGHT_MS;
        }
        return screen;
    }

    @Override
    protected void init() {
        rows.clear();
        searchBox = null;
        searchClearButton = null;
        sidebarNavigation = null;
        resetLookButton = null;
        scopeSwitcher = null;
        contextMenu.close();
        inheritedSnapshot = target == null ? null : target.scope().inherited();
        zoneOverrideTags = target == null ? zoneOverrideTags(globalConfig) : Map.of();
        Layout layout = layout();
        controlWidth = measuredControlWidth(layout);
        actionClusterWidth = measuredActionClusterWidth(layout);
        buildRows(layout);
        addScopeSwitcher(layout);
        addSearchBox(layout);
        addSidebarNavigation(layout);

        GuiTokens.ButtonSpec done = doneSpec();
        List<Button> footer = new ArrayList<>();
        GuiTokens.layoutFooter(width, height - FOOTER_H, footerActions(), done,
                button -> {
                    footer.add(button);
                    addRenderableWidget(button);
                }, font);
        if (target != null && footer.size() > 1) resetLookButton = footer.getFirst();
    }

    private List<GuiTokens.ButtonSpec> footerActions() {
        if (target == null) return List.of();
        return List.of(new GuiTokens.ButtonSpec(
                Component.translatable(confirmationActive(CONFIRM_RESET_LOOK)
                        ? "waypointer.screen.appearance.reset.confirm"
                        : "waypointer.screen.appearance.reset", target.name()).getString(),
                -1, this::resetLook,
                Tooltip.create(Component.translatable("waypointer.screen.appearance.reset.tooltip"))));
    }

    private void resetLook() {
        if (target.scope().count() == 0) return;
        if (!consumeOrArmConfirmation(CONFIRM_RESET_LOOK)) return;
        clearPendingConfirmation();
        target.scope().clear();
        afterScopedWrite();
        rebuildPending = true;
    }

    private static Map<String, Component> zoneOverrideTags(WaypointerConfig config) {
        Map<String, List<String>> zonesByProperty = new HashMap<>();
        config.zoneStyles().forEach((zone, style) -> style.values().keySet().forEach(
                property -> zonesByProperty.computeIfAbsent(property, k -> new ArrayList<>()).add(zone)));
        Map<String, Component> tags = new HashMap<>();
        zonesByProperty.forEach((property, zones) -> tags.put(property, zones.size() == 1
                ? Component.translatable("waypointer.screen.appearance.zone_overrides",
                        AppearanceTarget.zoneLabel(zones.getFirst()))
                : Component.translatable("waypointer.screen.appearance.zones_override", zones.size())));
        return tags;
    }

    private void addScopeSwitcher(Layout layout) {
        boolean zoneScope = target != null && target.scope().level() == AppearanceScope.Level.ZONE;
        boolean globalAppearance = target == null && "appearance".equals(activeCategoryId);
        if (!zoneScope && !globalAppearance) return;
        Component label = Component.translatable("waypointer.screen.appearance.editing",
                zoneScope ? target.name()
                        : Component.translatable("waypointer.screen.appearance.everywhere"));
        int w = Math.min(font.width(label) + BUTTON_TEXT_PADDING + 8, (layout.mainRight() - layout.mainLeft()) / 2);
        scopeSwitcher = styledButton(layout.mainRight() - GAP - w, layout.top() + 4, w, BTN_H,
                Component.literal(font.plainSubstrByWidth(label.getString(), w - BUTTON_TEXT_PADDING) + " \u25be"),
                b -> openScopeMenu(b),
                Tooltip.create(Component.translatable("waypointer.screen.appearance.editing.tooltip")));
        addRenderableWidget(scopeSwitcher);
    }

    private void openScopeMenu(Button anchor) {
        List<ContextMenuOverlay.Item> items = new ArrayList<>();
        boolean zoneScope = target != null;
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.appearance.everywhere"),
                zoneScope ? null : "\u2713", zoneScope ? this::onClose : () -> { }));
        var manager = WaypointerClient.manager();
        List<String> routeZones = manager == null ? List.of() : manager.allGroups().stream()
                .filter(group -> !group.temp() && !group.runtimeOnly())
                .map(WaypointGroup::zoneId).distinct().toList();
        for (String key : AppearanceTarget.switcherZoneKeys(manager == null ? null : manager.currentZone(),
                routeZones, globalConfig.zoneStyles().keySet())) {
            boolean current = zoneScope && key.equals(target.scope().key());
            int count = WaypointStyles.setCount(globalConfig.zoneStyle(key));
            String hint = current ? "\u2713" : count > 0
                    ? Component.translatable("waypointer.screen.appearance.set_count", count).getString() : null;
            items.add(ContextMenuOverlay.Item.of(AppearanceTarget.zoneLabel(key), hint, () -> {
                if (current) return;
                Screen back = zoneScope ? parent : this;
                MinecraftCompat.setScreen(minecraft, forAppearance(back, globalConfig, dungeonConfig,
                        AppearanceTarget.zone(globalConfig, key)));
            }));
        }
        Layout layout = layout();
        contextMenu.openAt(font, items, anchor.getX(), anchor.getY() + anchor.getHeight(),
                layout.mainLeft(), layout.top(), layout.mainRight(), height - FOOTER_H);
    }

    @Override
    protected void setInitialFocus() {
        if (refocusSearchAfterRebuild && searchBox != null) {
            refocusSearchAfterRebuild = false;
            setInitialFocus(searchBox);
            return;
        }
        if (refocusSidebarAfterRebuild && sidebarNavigation != null) {
            refocusSidebarAfterRebuild = false;
            setInitialFocus(sidebarNavigation);
            return;
        }
        super.setInitialFocus();
    }

    private void addSearchBox(Layout layout) {
        int x = layout.mainLeft() + GAP;
        int available = (scopeSwitcher == null ? layout.mainRight() : scopeSwitcher.getX()) - GAP - x;
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
        searchBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.settings.search.tooltip")));
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
            searchClearButton.active = SettingsValuePolicy.searchClearActive(searchQuery);
        }
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
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return target == null && super.isPauseScreen();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {

        if (target != null && minecraft != null && minecraft.level != null) return;
        if (GuiTokens.extractSeeThroughBackground(g, width, height)) return;
        super.extractBackground(g, mouseX, mouseY, partial);
    }

    @Override
    public void removed() {
        super.removed();
    }

    private Layout layout() {
        int footerSpace = GuiTokens.footerHeight(width, footerActions(), doneSpec(), font);
        int top = PAD_OUTER + font.lineHeight + GAP;
        int bottom = height - footerSpace - GAP_SECTION;
        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + sidebarWidth(width);
        int mainLeft = sidebarRight + GAP;
        int mainRight = width - PAD_OUTER;
        int rowsTop = top + 4 + BTN_H + GAP;
        return new Layout(top, bottom, sidebarLeft, sidebarRight, mainLeft, mainRight, rowsTop);
    }

    private record Layout(int top, int bottom, int sidebarLeft, int sidebarRight,
                          int mainLeft, int mainRight, int rowsTop) {
        boolean rail() {
            return sidebarRight - sidebarLeft < SIDEBAR_W;
        }
    }

    static final int RAIL_W = Icons.SIZE + GAP * 2 + 4;
    /** Below this settings width the category list folds into an icon rail. */
    static final int MIN_MAIN_WITH_FULL_SIDEBAR = 340;

    static int sidebarWidth(int screenWidth) {
        int mainWithFull = screenWidth - PAD_OUTER * 2 - SIDEBAR_W - GAP;
        return mainWithFull >= MIN_MAIN_WITH_FULL_SIDEBAR ? SIDEBAR_W : RAIL_W;
    }

    static int maxScrollFor(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - Math.max(0, viewportHeight) + SCROLL_BOTTOM_SLACK);
    }

    static boolean inContentDeadStrip(double mx, double my, int mainLeft, int mainRight,
                                      int top, int rowsTop, int bottom, int footerTop) {
        if (mx < mainLeft || mx > mainRight) return false;
        if (my >= top && my < rowsTop) return true;
        return my > bottom && my < footerTop;
    }

    private static final class Row {
        final Setting setting;
        final String caption;
        final boolean header;
        final boolean expanded;
        final boolean child;
        boolean lastChild;
        final int y;
        final int height;
        final List<WidgetSlot> widgets = new ArrayList<>();
        int controlLeft;
        String chip;
        String jumpTargetId;
        WidgetSlot dot;
        Supplier<Component> status;

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

    private record WidgetSlot(AbstractWidget widget, int homeY, Supplier<Boolean> activeWhen) {
        WidgetSlot(AbstractWidget widget, int homeY) {
            this(widget, homeY, null);
        }
    }

    private void buildRows(Layout layout) {
        searchCategoryCounts = Map.of();
        if (!categoryAvailable(activeCategoryId, config)
                || RECENT_ID.equals(activeCategoryId) && RecentSettings.isEmpty()
                || CHANGED_ID.equals(activeCategoryId) && (target == null || target.scope().count() == 0)) {
            activeCategoryId = categories.getFirst().id();
        }

        if (searchActive()) {
            buildSearchRows(layout);
        } else if (RECENT_ID.equals(activeCategoryId)) {
            buildRecentRows(layout);
        } else if (CHANGED_ID.equals(activeCategoryId)) {
            buildChangedRows(layout);
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
            if (!first) y += GAP_SECTION;
            first = false;

            if (group.label() != null) {
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
                row.lastChild = isChild && i == settings.size() - 1;
                buildRowWidgets(row, layout);
                buildResetDot(row, layout);
                rows.add(row);
                y += SETTING_ROW_PITCH;
                y = addStatusRowFor(setting, y);
            }
        }
        contentHeight = y;
    }

    record Section(String id, SettingsCatalog.Group heading, List<SettingsCatalog.Group> groups) {}

    static List<Section> sections(SettingsCatalog.Category category) {
        List<Section> sections = new ArrayList<>();
        for (SettingsCatalog.Group group : category.groups()) {
            if (group.id() != null || sections.isEmpty()) {
                sections.add(new Section(group.id() == null ? "general" : group.id(),
                        group, new ArrayList<>()));
            }
            sections.getLast().groups().add(group);
        }
        return sections;
    }

    static String sectionForSetting(SettingsCatalog.Category category, String settingId) {
        for (Section section : sections(category)) {
            for (SettingsCatalog.Group group : section.groups()) {
                if (group.settings().stream().anyMatch(setting -> setting.id().equals(settingId))) {
                    return section.id();
                }
            }
        }
        return null;
    }

    private int addStatusRowFor(Setting setting, int y) {
        Supplier<Component> status = switch (setting.id()) {
            case SettingsCatalog.ACTION_CONFIG_CODE -> configCodeStatus == null
                    ? null
                    : this::configCodeStatusComponent;
            default -> null;
        };
        if (status == null) return y;
        Row row = new Row(null, null, false, false, false, y, GROUP_CAPTION_H);
        row.status = status;
        rows.add(row);
        return y + GROUP_CAPTION_H;
    }

    private void buildSearchRows(Layout layout) {
        List<SettingsSearch.Match> matches =
                SettingsSearch.search(searchQuery, categories,
                        (key, fallback) -> Component.translatableWithFallback(
                                key, fallback).getString());
        Map<String, Integer> counts = new HashMap<>();
        int y = 0;
        for (SettingsSearch.Match match : matches) {
            if (!featureAvailable(match.setting(), config)) continue;
            counts.merge(match.categoryId(), 1, Integer::sum);
            SettingsCatalog.Category category = categoryById(match.categoryId());
            y = addChippedRow(match.setting(),
                    category == null ? "" : categoryLabel(category), y, layout);
        }
        contentHeight = y;
        searchCategoryCounts = counts;
    }

    private void buildChangedRows(Layout layout) {
        int y = 0;
        for (SettingsCatalog.Category category : categories) {
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) {
                    if (target.scope().isSetHere(setting.id())) {
                        y = addChippedRow(setting, categoryLabel(category), y, layout);
                    }
                }
            }
        }
        contentHeight = y;
    }

    private void buildRecentRows(Layout layout) {
        int y = 0;
        for (String id : RecentSettings.mostRecentFirst()) {
            Setting setting = SettingsCatalog.byId(id);
            if (setting == null) continue;
            if (!featureAvailable(setting, config)) continue;
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

    private void consumePendingScroll(Layout layout) {
        if (pendingScrollToSettingId == null) return;
        Row target = rowBySettingId(pendingScrollToSettingId);
        if (target == null) {
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

    private String parentIdOf(String settingId) {
        for (SettingsCatalog.Category category : categories) {
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

    private SettingsCatalog.Category categoryOf(String settingId) {
        for (SettingsCatalog.Category category : categories) {
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) {
                    if (setting.id().equals(settingId)) return category;
                }
            }
        }
        return null;
    }

    private List<Setting> visibleSettings(SettingsCatalog.Category category,
                                          SettingsCatalog.Group group, boolean bodyVisible) {
        List<Setting> out = new ArrayList<>();
        boolean groupExpanded = group.childrenVisibleWhen() == null
                || group.childrenVisibleWhen().test(config, dungeonConfig);
        for (int i = 0; i < group.settings().size(); i++) {
            Setting setting = group.settings().get(i);
            if (!featureAvailable(setting, config)) continue;
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

    private SettingsCatalog.Category categoryById(String id) {
        if (id == null) return null;
        for (SettingsCatalog.Category category : categories) {
            if (category.id().equals(id)) return category;
        }
        return null;
    }

    private static Set<String> computeStructuralIds() {
        Set<String> out = new HashSet<>();
        out.add("enableFeatureBloat");
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            if (category.masterSettingId() != null) out.add(category.masterSettingId());
            for (SettingsCatalog.Group group : category.groups()) {
                if (group.parentSettingId() != null) out.add(group.parentSettingId());
            }
        }
        return out;
    }

    private String categoryLabel(SettingsCatalog.Category category) {
        if (target == null) return SettingsText.categoryLabel(category);
        if (SCOPED_VISIBILITY_ID.equals(category.id())) {
            return Component.translatable("waypointer.screen.appearance.section.visibility").getString();
        }
        return Component.translatableWithFallback(
                "waypointer.settings.group." + category.id(), category.label()).getString();
    }

    static boolean categoryAvailable(String categoryId, WaypointerConfig config) {
        return !"feature_bloat".equals(categoryId) || config.enableFeatureBloat();
    }

    static int childGuideHeight(int rowHeight, boolean lastChild) {
        return lastChild ? 13 : rowHeight;
    }

    static boolean featureAvailable(Setting setting, WaypointerConfig config) {
        return config.enableFeatureBloat() || !setting.id().equals("showExportRoutePreview");
    }

    private int measuredControlWidth(Layout layout) {
        int measured = MIN_CONTROL_W;
        measured = Math.max(measured, buttonWidth(
                Component.translatable("waypointer.screen.settings.color.pick")));
        for (Setting setting : SettingsCatalog.allSettings()) {
            for (int i = 0; i < setting.enumOptions().size(); i++) {
                measured = Math.max(measured, buttonWidth(SettingsText.enumOption(setting, i)));
            }
        }
        for (String key : List.of(
                "waypointer.screen.settings.action.disable_all",
                "waypointer.screen.settings.action.reset_defaults",
                "waypointer.screen.settings.action.confirm_reset")) {
            measured = Math.max(measured, buttonWidth(Component.translatable(key)));
        }

        int mainW = layout.mainRight() - CONTROL_RIGHT_INSET - layout.mainLeft() - GAP;
        int labelRoom = Math.max(MIN_SETTING_LABEL_W, mainW / 2);
        int available = mainW - DOT_AREA_W - GAP_TIGHT - labelRoom;
        return Math.min(measured, Math.max(60, available));
    }

    private int measuredActionClusterWidth(Layout layout) {
        int twoColumnCell = 60;
        for (String key : List.of(
                "waypointer.screen.settings.config.copy",
                "waypointer.screen.settings.config.import")) {
            twoColumnCell = Math.max(twoColumnCell, buttonWidth(Component.translatable(key)));
        }

        int threeColumnCell = 60;
        for (PresetOption preset : PRESET_OPTIONS) {
            Component label = Component.translatableWithFallback(
                    "waypointer.screen.settings.preset." + preset.id(), preset.label());
            threeColumnCell = Math.max(threeColumnCell, buttonWidth(label));
        }

        int wanted = snapActionClusterWidthUp(Math.max(
                twoColumnCell * 2 + GAP,
                threeColumnCell * 3 + GAP * 2));
        int available = layout.mainRight() - CONTROL_RIGHT_INSET - layout.mainLeft() - GAP;
        return Math.min(wanted, snapActionClusterWidthDown(Math.max(64, available)));
    }

    private int buttonWidth(Component label) {
        return Math.max(60, font.width(label) + BUTTON_TEXT_PADDING);
    }

    private static int snapActionClusterWidthUp(int width) {
        return width + Math.floorMod(4 - width, 6);
    }

    private static int snapActionClusterWidthDown(int width) {
        return width - Math.floorMod(width - 4, 6);
    }

    static int actionGridButtonWidth(int clusterWidth, int columns) {
        if (columns < 1) throw new IllegalArgumentException("columns must be positive");
        return (clusterWidth - GAP * (columns - 1)) / columns;
    }

    static int actionGridButtonX(int controlRight, int clusterWidth, int columns, int column) {
        return controlRight - clusterWidth
                + column * (actionGridButtonWidth(clusterWidth, columns) + GAP);
    }

    private void buildRowWidgets(Row row, Layout layout) {
        Setting setting = row.setting;
        int controlRight = layout.mainRight() - CONTROL_RIGHT_INSET;
        int rowTop = layout.rowsTop() + row.y;

        switch (setting.kind()) {
            case BOOL -> buildBoolControl(row, setting, controlRight, rowTop);
            case NUMBER -> buildNumberControl(row, setting, controlRight, rowTop);
            case TEXT -> buildTextControl(row, setting, controlRight, rowTop);
            case ENUM -> buildEnumControl(row, setting, controlRight, rowTop);
            case COLOR -> buildColorControl(row, setting, controlRight, rowTop);
            case ACTION -> buildActionControls(row, setting, controlRight, rowTop);
            default -> { }
        }
    }

    private void buildActionControls(Row row, Setting setting, int controlRight, int rowTop) {
        switch (setting.id()) {
            case SettingsCatalog.ACTION_MINIMAP_EDITOR -> buildDialogActionControl(row, setting,
                    controlRight, rowTop, "waypointer.screen.settings.action.minimap_editor",
                    () -> MinecraftCompat.setScreen(minecraft, new CrystalHollowsMinimapEditorScreen(this, config)));
            case SettingsCatalog.ACTION_WAYPOINT_PAINT -> buildDialogActionControl(row, setting,
                    controlRight, rowTop, "waypointer.screen.settings.action.open_painter",
                    this::openWaypointPainter);
            case SettingsCatalog.ACTION_FEEDBACK -> buildDialogActionControl(row, setting,
                    controlRight, rowTop, "waypointer.screen.settings.action.feedback",
                    () -> MinecraftCompat.setScreen(minecraft, new FeedbackScreen(this, config)));
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
            default -> { }
        }
    }

    private void openWaypointPainter() {
        if (WaypointerClient.manager() == null) return;
        MinecraftCompat.setScreen(minecraft,
                new WaypointPainterScreen(this, config, WaypointerClient.manager()));
    }

    private void buildConfigCodeControls(Row row, Setting setting, int controlRight, int rowTop) {
        int columns = 2;
        int buttonW = actionGridButtonWidth(actionClusterWidth, columns);
        Button copyButton = styledButton(actionGridButtonX(
                        controlRight, actionClusterWidth, columns, 0), 0, buttonW, BTN_H,
                Component.translatable("waypointer.screen.settings.config.copy"),
                this::copyConfigCode, SettingsText.tooltipOrNull(setting));
        Button importButton = styledButton(actionGridButtonX(
                        controlRight, actionClusterWidth, columns, 1), 0, buttonW, BTN_H,
                Component.translatable("waypointer.screen.settings.config.import"),
                this::importConfigCode, SettingsText.tooltip(setting, Component.translatable(
                        "waypointer.screen.settings.config.import.tooltip")));
        registerRowWidget(row, copyButton, rowTop + 2);
        registerRowWidget(row, importButton, rowTop + 2);
    }

    private void buildPresetControls(Row row, Setting setting, int controlRight, int rowTop) {
        int columns = PRESET_OPTIONS.size();
        int buttonW = actionGridButtonWidth(actionClusterWidth, columns);
        for (int i = 0; i < columns; i++) {
            PresetOption preset = PRESET_OPTIONS.get(i);
            Component label = Component.translatableWithFallback(
                    "waypointer.screen.settings.preset." + preset.id(), preset.label());
            Button button = styledButton(actionGridButtonX(
                            controlRight, actionClusterWidth, columns, i), 0, buttonW, BTN_H, label,
                    b -> applyPreset(preset.id()), SettingsText.tooltip(setting,
                            Component.translatableWithFallback(
                                    "waypointer.screen.settings.preset." + preset.id() + ".tooltip",
                                    preset.description())));
            registerRowWidget(row, button, rowTop + 2);
        }
    }

    private void buildDialogActionControl(Row row, Setting setting, int controlRight, int rowTop,
                                          String labelKey, Runnable onConfirmFlow) {
        Button button = styledButton(controlRight - controlWidth, 0, controlWidth, BTN_H,
                Component.translatable(labelKey), b -> onConfirmFlow.run(), SettingsText.tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private void buildConfirmActionControl(Row row, Setting setting, int controlRight, int rowTop,
                                           int action, String labelKey, String armedLabelKey,
                                           Runnable confirmed) {
        String current = confirmationActive(action) ? armedLabelKey : labelKey;
        Button button = styledButton(controlRight - controlWidth, 0, controlWidth, BTN_H,
                Component.translatable(current), b -> {
                    if (!consumeOrArmConfirmation(action)) return;
                    confirmed.run();
                }, SettingsText.tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private void buildBoolControl(Row row, Setting setting, int controlRight, int rowTop) {
        GuiTokens.StyledCheckbox checkbox = styledCheckbox(0, 0, CHECKBOX_SIZE,
                SettingsText.label(setting),
                Boolean.TRUE.equals(setting.get(config, dungeonConfig)),
                v -> applySetting(setting, v), SettingsText.tooltipOrNull(setting));
        checkbox.setX(controlRight - checkbox.getWidth());
        int homeY = rowTop + (row.height - checkbox.getHeight()) / 2;
        registerRowWidget(row, checkbox, homeY);
    }

    private void buildNumberControl(Row row, Setting setting, int controlRight, int rowTop) {
        boolean[] normalizing = { false };
        EditBox box = new EditBox(font, controlRight - controlWidth, 0, controlWidth, BTN_H,
                SettingsText.label(setting)) {
            @Override
            public void setFocused(boolean focused) {
                boolean wasFocused = isFocused();
                super.setFocused(focused);
                if (!wasFocused || focused) return;
                normalizing[0] = true;
                setValue(SettingsText.localizedValue(
                        setting, setting.get(config, dungeonConfig)).getString());
                normalizing[0] = false;
            }
        };
        box.setMaxLength(24);
        box.setValue(SettingsText.localizedValue(
                setting, setting.get(config, dungeonConfig)).getString());
        box.setResponder(v -> {
            if (normalizing[0]) return;
            Double value = SettingsValuePolicy.acceptedNumberValue(setting, v);
            if (value != null) applySetting(setting, value);
        });
        box.setTooltip(SettingsText.tooltipOrNull(setting));
        registerRowWidget(row, box, rowTop + 2);
    }

    private void buildEnumControl(Row row, Setting setting, int controlRight, int rowTop) {
        Button button = styledButton(controlRight - controlWidth, 0, controlWidth, BTN_H,
                enumLabelFor(setting),
                b -> {
                    Object next = SettingsValuePolicy.nextEnumValue(
                            setting, setting.get(config, dungeonConfig));
                    applySetting(setting, next);
                    b.setMessage(enumLabelFor(setting));
                }, SettingsText.tooltipOrNull(setting));
        registerRowWidget(row, button, rowTop + 2);
    }

    private void buildTextControl(Row row, Setting setting, int controlRight, int rowTop) {
        EditBox box = new EditBox(font, controlRight - controlWidth, 0, controlWidth, BTN_H,
                SettingsText.label(setting)) {
            @Override
            public void setFocused(boolean focused) {
                boolean wasFocused = isFocused();
                super.setFocused(focused);
                if (wasFocused && !focused) setValue(String.valueOf(setting.get(config, dungeonConfig)));
            }
        };
        box.setMaxLength(256);
        box.setValue(String.valueOf(setting.get(config, dungeonConfig)));
        box.setResponder(value -> {
            boolean valid = setting.acceptsText(value);
            box.setTextColor(valid ? TEXT : 0xFF5555);
            if (valid) applySetting(setting, value);
        });
        box.setTooltip(SettingsText.tooltipOrNull(setting));
        registerRowWidget(row, box, rowTop + 2);
    }

    private Component enumLabelFor(Setting setting) {
        Object current = setting.id().equals("boxStyle")
                ? config.effectiveBoxStyle() : setting.get(config, dungeonConfig);
        for (int i = 0; i < setting.enumOptions().size(); i++) {
            if (Objects.equals(setting.enumOptions().get(i).value(), current)) {
                return SettingsText.enumOption(setting, i);
            }
        }
        return Component.literal(String.valueOf(current));
    }

    private void buildColorControl(Row row, Setting setting, int controlRight, int rowTop) {
        int currentColor = ((Number) setting.get(config, dungeonConfig)).intValue();

        ColorSwatchButton[] swatchRef = new ColorSwatchButton[1];
        ColorSwatchButton swatch = new ColorSwatchButton(
                controlRight - controlWidth, 0, controlWidth, BTN_H,
                "",
                currentColor,
                () -> {
                    int pickerColor = swatchRef[0] == null ? currentColor : swatchRef[0].getColor();
                    ColorPickerScreen.open(this,
                            Component.translatable(setting.colorPickerTitleTranslationKey()),
                            pickerColor,
                            picked -> {
                                applySetting(setting, picked);
                                if (swatchRef[0] != null) swatchRef[0].setColor(picked);
                            });
                });
        swatchRef[0] = swatch;
        swatch.setTooltip(setting.colorSwatchTooltip().isBlank()
                ? SettingsText.tooltipOrNull(setting)
                : SettingsText.tooltip(setting, Component.translatableWithFallback(
                        setting.colorSwatchTooltipTranslationKey(), setting.colorSwatchTooltip())));

        registerRowWidget(row, swatch, rowTop + 2);
    }

    private void registerRowWidget(Row row, AbstractWidget widget, int homeY) {
        registerRowWidget(row, widget, homeY, null);
    }

    private void registerRowWidget(Row row, AbstractWidget widget, int homeY, Supplier<Boolean> activeWhen) {
        widget.visible = false;
        row.widgets.add(new WidgetSlot(widget, homeY, activeWhen));
        row.controlLeft = Math.min(row.controlLeft, widget.getX());
        addWidget(widget);
    }

    private void buildResetDot(Row row, Layout layout) {
        Setting setting = row.setting;
        if (setting == null || setting.store() == Setting.Store.NONE) return;
        if (row.controlLeft == Integer.MAX_VALUE) return;
        if (target != null) {
            buildInheritDot(row, layout);
            return;
        }

        Object defaultValue = setting.defaultValue(defaultsMain, defaultsDungeon);
        ResetDotButton dot = new ResetDotButton(row.controlLeft - DOT_AREA_W, 0, 12, BTN_H,
                b -> {
                    applySetting(setting, defaultValue);
                    rebuildPending = true;
                });
        dot.setTooltip(SettingsText.tooltip(setting, Component.translatable(
                "waypointer.screen.settings.reset.tooltip",
                SettingsText.localizedValue(setting, defaultValue))));
        dot.visible = false;
        row.dot = new WidgetSlot(dot, layout.rowsTop() + row.y + 2);
        addWidget(dot);
    }

    private void buildInheritDot(Row row, Layout layout) {
        Setting setting = row.setting;
        Object inherited = setting.get(inheritedSnapshot, dungeonConfig);
        ResetDotButton dot = new ResetDotButton(row.controlLeft - DOT_AREA_W, 0, 12, BTN_H,
                b -> inherit(setting));
        dot.setMessage(Component.translatable("waypointer.screen.appearance.inherit"));
        dot.setTooltip(SettingsText.tooltip(setting, Component.translatable(
                "waypointer.screen.appearance.inherit.tooltip",
                SettingsText.localizedValue(setting, inherited),
                target.sourceLabel(target.scope().source(setting.id())))));
        dot.visible = false;
        row.dot = new WidgetSlot(dot, layout.rowsTop() + row.y + 2);
        addWidget(dot);
    }

    private void inherit(Setting setting) {
        target.scope().inherit(setting.id());
        afterScopedWrite();
        rebuildPending = true;
    }

    private void afterScopedWrite() {
        WaypointStyles.applyTo(config, WaypointStyles.capture(target.scope().effective()));
        target.afterWrite().run();
    }

    private record PresetOption(String id, String label, String description) {
    }

    private void applySetting(Setting setting, Object value) {
        if (target != null) {
            setting.set(config, dungeonConfig, value);
            target.scope().capture(config, setting.id());
            target.afterWrite().run();
            if (STRUCTURAL_IDS.contains(setting.id())
                    || CHANGED_ID.equals(activeCategoryId) && !target.scope().isSetHere(setting.id())) {
                rebuildPending = true;
            }
            return;
        }
        String before = setting.formatValue(setting.get(config, dungeonConfig));
        setting.set(config, dungeonConfig, value);
        String after = setting.formatValue(setting.get(config, dungeonConfig));
        ConfigChangeHistory.recordSetting(setting.id(), before, after);
        RecentSettings.record(setting.id());
        if (STRUCTURAL_IDS.contains(setting.id())) {
            rebuildPending = true;
        }
    }

    private void afterBulkConfigChange() {
        CrystalHollowsProjection.refreshStyles(WaypointerClient.manager(), config);
        RecentSettings.clear();
        searchQuery = "";
        clearPendingConfirmation();
        rebuildPending = true;
    }

    private boolean consumeOrArmConfirmation(int action) {
        long now = System.currentTimeMillis();
        if (pendingConfirmationAction == action && now <= pendingConfirmationUntilMillis) {
            return true;
        }
        pendingConfirmationAction = action;
        pendingConfirmationUntilMillis = now + CONFIRMATION_WINDOW_MS;
        rebuildPending = true;
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

    private void confirmDisableAll() {
        WaypointerConfig preview = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(config));
        preview.disableAllSettings();
        int changed = SettingsCatalog.countChangedSettings(config, preview)
                + changedDungeonSettingsWhenDisabled(dungeonConfig);
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

    private void copyConfigCode(Button b) {
        try {
            String code = UniversalShareCodec.encodeConfig(config);
            minecraft.keyboardHandler.setClipboard(code);
            setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                    "waypointer.screen.settings.config.copied"), GuiTokens.SUCCESS));
        } catch (Throwable t) {
            setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                    "waypointer.screen.settings.config.copy_failed"), GuiTokens.DANGER));
        }
    }

    private void importConfigCode(Button b) {
        String text;
        try {
            text = minecraft.keyboardHandler.getClipboard();
        } catch (Throwable t) {
            setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                    "waypointer.screen.settings.config.clipboard_failed"), GuiTokens.DANGER));
            return;
        }
        if (text == null || text.isBlank()) {
            setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                    "waypointer.screen.settings.config.clipboard_empty"), GuiTokens.DANGER));
            return;
        }

        try {
            UniversalShareCodec.Decoded share = UniversalShareCodec.decode(text);
            if (!(share instanceof UniversalShareCodec.Configuration configuration)) {
                throw new IllegalArgumentException("clipboard share is not a configuration");
            }
            WaypointerConfig decoded = configuration.config();
            showImportConfigConfirmation(decoded);
        } catch (RuntimeException e) {
            setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                    "waypointer.screen.settings.config.invalid"), GuiTokens.DANGER));
        }
    }

    private void showImportConfigConfirmation(WaypointerConfig decoded) {
        if (decoded == null) {
            setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                    "waypointer.screen.settings.config.invalid"), GuiTokens.DANGER));
            return;
        }

        ConfigImportConfirmation.open(this, config, decoded, outcome -> {
                    if (outcome.confirmed()) {
                        applyConfirmedConfigImport(outcome.changedSettings());
                    } else {
                        setConfigCodeStatus(GuiTokens.colored(Component.translatable(
                                "waypointer.screen.settings.config.cancelled"),
                                GuiTokens.TEXT_DIM));
                    }
                });
    }

    private void applyConfirmedConfigImport(int changedSettings) {
        ConfigChangeHistory.recordBulk("Imported config code (" + changedSettings + " changed)");
        afterBulkConfigChange();
        setConfigCodeStatus(GuiTokens.colored(Component.translatable(changedSettings == 1
                ? "waypointer.screen.settings.config.imported.one"
                : "waypointer.screen.settings.config.imported.many", changedSettings),
                GuiTokens.SUCCESS));
    }

    private void setConfigCodeStatus(Component status) {
        configCodeStatus = status;
        rebuildPending = true;
    }

    private Component configCodeStatusComponent() {
        return configCodeStatus == null
                ? Component.empty()
                : configCodeStatus;
    }

    private void applyPreset(String presetId) {
        if ("nothing".equals(presetId)) {
            WaypointerConfig preview = WaypointerConfigCodec.decode(
                    WaypointerConfigCodec.encode(config));
            preview.disableAllSettings();
            int changed = SettingsCatalog.countChangedSettings(config, preview)
                    + changedDungeonSettingsWhenDisabled(dungeonConfig);
            confirmPreset("waypointer.screen.settings.preset.nothing", changed, confirmed -> {
                if (confirmed) {
                    SettingsPresets.applyDisableAll(config, dungeonConfig);
                    ConfigChangeHistory.recordBulk("Applied disable-all preset");
                    afterBulkConfigChange();
                }
            });
            return;
        }
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
        if (!"minimal".equals(presetId)) return;
        WaypointerConfig preset = SettingsPresets.minimal(config);
        String nameKey = "waypointer.screen.settings.preset.minimal";
        confirmPreset(nameKey, SettingsCatalog.countImportedSettings(config, preset), confirmed -> {
            if (confirmed) {
                config.replaceWith(preset);
                ConfigChangeHistory.recordBulk("Applied " + presetId + " preset");
                afterBulkConfigChange();
            }
        });
    }

    static int changedDungeonSettingsWhenDisabled(DungeonConfig dungeonConfig) {
        if (dungeonConfig == null) return 0;
        int changed = 0;
        if (dungeonConfig.enabled()) changed++;
        if (dungeonConfig.debugLogRoomChanges()) changed++;
        if (dungeonConfig.hideCompletedRooms()) changed++;
        if (dungeonConfig.showDungeonRouteLines()) changed++;
        if (dungeonConfig.showDungeonTracers()) changed++;
        if (dungeonConfig.secretCompletionSound()) changed++;
        return changed;
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Layout layout = layout();
        refreshRowStates(layout);

        boolean menuOpen = contextMenu.isOpen();
        int widgetMouseX = menuOpen ? -1 : mouseX;
        int widgetMouseY = menuOpen ? -1 : mouseY;
        g.fill(0, 0, width, height, GuiTokens.panel(target == null ? SURFACE : SCOPED_BACKDROP));
        if (target != null) {
            g.fill(0, 0, width, layout.top(), GuiTokens.panel(SCOPED_PANEL));
            g.fill(0, layout.bottom(), width, height, GuiTokens.panel(SCOPED_PANEL));
        }
        String title = font.plainSubstrByWidth(getTitle().getString(), Math.max(0, width / 2 - PAD_OUTER));
        g.text(font, title, PAD_OUTER, PAD_OUTER, TEXT, false);
        Component hint = target == null ? Component.empty() : breadcrumb();
        String hintText = font.plainSubstrByWidth(hint.getString(),
                Math.max(0, width - PAD_OUTER * 2 - font.width(title) - GAP_SECTION));
        g.text(font, hintText, width - PAD_OUTER - font.width(hintText), PAD_OUTER, TEXT_DIM, false);

        railTooltip = null;
        renderSidebar(g, layout, widgetMouseX, widgetMouseY);

        g.fill(layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom(),
                GuiTokens.panel(target == null ? SURFACE_SUBTLE : SCOPED_PANEL));
        Component headerTooltip = renderHeaderStrip(g, layout, widgetMouseX, widgetMouseY);
        if (resetLookButton != null) resetLookButton.active = target.scope().count() > 0;

        super.extractRenderState(g, widgetMouseX, widgetMouseY, partial);

        g.enableScissor(layout.mainLeft(), layout.rowsTop(), layout.mainRight(), layout.bottom());
        for (Row row : rows) {
            int rowTop = layout.rowsTop() + row.y - scrollOffset;
            if (rowTop + row.height <= layout.rowsTop() || rowTop >= layout.bottom()) continue;
            renderRow(g, row, rowTop, layout, widgetMouseX, widgetMouseY, partial);
        }
        g.disableScissor();

        renderScrollbar(g, layout.mainRight() - 4, layout.rowsTop(), layout.bottom());
        if (menuOpen) {
            contextMenu.render(g, font, mouseX, mouseY);
        } else if (railTooltip != null) {
            g.setTooltipForNextFrame(font, railTooltip, mouseX, mouseY);
        } else if (headerTooltip != null) {
            g.setTooltipForNextFrame(font, font.split(headerTooltip, Math.max(120, width / 3)), mouseX, mouseY);
        }
    }

    private Component breadcrumb() {
        MutableComponent out = Component.empty();
        List<Component> crumbs = target.breadcrumb();
        for (int i = 0; i < crumbs.size(); i++) {
            if (i > 0) out.append(" \u203a ");
            out.append(crumbs.get(i));
        }
        return out;
    }

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
                row.dot.widget().visible = inViewport && (target != null
                        ? target.scope().isSetHere(row.setting.id())
                        : row.setting.isModified(config, dungeonConfig, defaultsMain, defaultsDungeon));
            }
        }
    }

    /** Draws the strip above the rows; returns the full text to show when a clipped info line is hovered. */
    private Component renderHeaderStrip(GuiGraphicsExtractor g, Layout layout, int mouseX, int mouseY) {
        Component info;
        if (searchActive()) {
            int matches = rows.size();
            info = Component.translatable(matches == 0
                    ? "waypointer.screen.settings.matches.none"
                    : matches == 1
                    ? "waypointer.screen.settings.matches.one"
                    : "waypointer.screen.settings.matches.many", matches);
        } else if (target != null) {
            info = Component.empty();
        } else {
            info = Component.empty();
        }
        int infoRight = (scopeSwitcher == null ? layout.mainRight() : scopeSwitcher.getX()) - GAP;
        int infoLeft = searchClearButton == null ? layout.mainLeft() + GAP
                : searchClearButton.getX() + searchClearButton.getWidth() + GAP;
        String infoText = GuiTokens.ellipsize(font, info.getString(), Math.max(0, infoRight - infoLeft));
        int infoX = infoRight - font.width(infoText);
        g.text(font, infoText, infoX, layout.top() + 10, target != null ? TEXT : TEXT_DIM, false);
        boolean clipped = !infoText.equals(info.getString());
        Component tooltip = clipped && mouseX >= infoX && mouseX <= infoRight
                && mouseY >= layout.top() + 6 && mouseY <= layout.top() + 20 ? info : null;

        if (rows.isEmpty() && searchActive()) {
            int textX = layout.mainLeft() + GAP;
            g.text(font, Component.translatable("waypointer.screen.settings.empty"),
                    textX, layout.rowsTop() + 8, TEXT, false);
            g.text(font, Component.translatable("waypointer.screen.settings.empty.hint"),
                    textX, layout.rowsTop() + 8 + 14, TEXT_DIM, false);
        }
        return tooltip;
    }

    private List<SidebarEntry> sidebarEntries() {
        List<SidebarEntry> out = new ArrayList<>();
        if (target != null) {
            int count = target.scope().count();
            if (count > 0) {
                out.add(new SidebarEntry(CHANGED_ID, Component.translatable(
                        "waypointer.screen.appearance.changed_here", count).getString()));
            }
            for (SettingsCatalog.Category category : categories) {
                out.add(new SidebarEntry(category.id(), categoryLabel(category)));
            }
            return out;
        }
        if (!RecentSettings.isEmpty()) {
            out.add(new SidebarEntry(RECENT_ID,
                    Component.translatable("waypointer.screen.settings.recent").getString()));
        }
        for (SettingsCatalog.Category category : categories) {
            if (categoryAvailable(category.id(), config)) {
                out.add(new SidebarEntry(category.id(), categoryLabel(category)));
            }
        }
        return out;
    }

    private record SidebarEntry(String id, String label) {}

    private void addSidebarNavigation(Layout layout) {
        int rowsTop = sidebarRowsTop(layout.top());
        int rowsBottom = layout.bottom() - GAP_TIGHT;
        if (rowsBottom <= rowsTop) return;
        sidebarNavigation = new ListNavigationWidget(
                layout.sidebarLeft(), rowsTop,
                layout.sidebarRight() - layout.sidebarLeft(), rowsBottom - rowsTop,
                0, ROW_H, ROW_H,
                () -> sidebarEntries().size(), this::activeSidebarIndex,
                index -> {
                    List<SidebarEntry> entries = sidebarEntries();
                    return index >= 0 && index < entries.size()
                            ? Component.literal(entries.get(index).label())
                            : Component.empty();
                },
                this::selectSidebarEntry, () -> sidebarScrollOffset,
                index -> revealSidebarEntry(index, rowsBottom - rowsTop));
        addRenderableWidget(sidebarNavigation);
    }

    private int activeSidebarIndex() {
        List<SidebarEntry> entries = sidebarEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(activeCategoryId)) return i;
        }
        return 0;
    }

    private void selectSidebarEntry(int index) {
        List<SidebarEntry> entries = sidebarEntries();
        if (index < 0 || index >= entries.size()) return;
        String categoryId = entries.get(index).id();
        refocusSidebarAfterRebuild = !categoryId.equals(activeCategoryId) || searchActive();
        selectCategory(categoryId);
    }

    private void revealSidebarEntry(int index, int viewportHeight) {
        int rowTop = index * ROW_H;
        int rowBottom = rowTop + ROW_H;
        if (rowTop < sidebarScrollOffset) {
            sidebarScrollOffset = rowTop;
        } else if (rowBottom > sidebarScrollOffset + viewportHeight) {
            sidebarScrollOffset = rowBottom - viewportHeight;
        }
        sidebarScrollOffset = MathUtil.clamp(sidebarScrollOffset, 0,
                maxSidebarScroll(sidebarEntries().size(), viewportHeight));
    }

    private void renderSidebar(GuiGraphicsExtractor g, Layout layout, int mouseX, int mouseY) {
        int x1 = layout.sidebarLeft();
        int x2 = layout.sidebarRight();
        g.fill(x1, layout.top(), x2, layout.bottom(), GuiTokens.panel(target == null ? SURFACE : SCOPED_PANEL));
        g.fill(x2, layout.top(), x2 + 1, layout.bottom(), BORDER);
        boolean rail = layout.rail();
        if (!rail) {
            g.text(font, Component.translatable(target == null
                            ? "waypointer.screen.settings.categories"
                            : "waypointer.screen.appearance.sections"),
                    x1 + GAP, layout.top() + 10, TEXT_DIM, false);
        }

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

            Icons.Icon icon = sidebarIcon(entry.id());
            if (rail) {
                boolean empty = searching && searchCategoryCounts.getOrDefault(entry.id(), 0) == 0;
                int tint = selected ? ACCENT : hovered ? TEXT : empty ? 0x60FFFFFF : TEXT_MUTED;
                if (icon != null) {
                    Icons.drawCentered(g, icon, x1 + 2, rowY, x2 - x1 - 2, ROW_H, tint);
                } else {
                    String initial = entry.label().isEmpty() ? "?" : entry.label().substring(0, 1);
                    g.text(font, initial, x1 + (x2 - x1 - font.width(initial)) / 2, rowY + 7, tint, false);
                }
                if (hovered) railTooltip = Component.literal(entry.label());
                continue;
            }
            int labelX = x1 + GAP + 2;
            if (icon != null) {
                Icons.draw(g, icon, labelX, rowY + (ROW_H - Icons.SIZE) / 2,
                        selected ? ACCENT : hovered ? TEXT : TEXT_MUTED);
                labelX += Icons.SIZE + GAP_TIGHT;
            }
            int labelMaxW = x2 - GAP - labelX;
            if (searching) {
                Integer count = searchCategoryCounts.get(entry.id());
                if (count != null && count > 0) {
                    String countStr = Integer.toString(count);
                    int countX = x2 - GAP - font.width(countStr);
                    g.text(font, countStr, countX, rowY + 7, TEXT_MUTED, false);
                    labelMaxW = countX - GAP_TIGHT - labelX;
                }
            }
            String clipped = font.plainSubstrByWidth(entry.label(), Math.max(12, labelMaxW));
            g.text(font, clipped, labelX, rowY + 7, selected ? TEXT : TEXT_DIM, false);
        }
        g.disableScissor();
    }

    static Icons.Icon sidebarIcon(String id) {
        return switch (id) {
            case RECENT_ID -> Icons.Icon.CLOCK;
            case CHANGED_ID -> Icons.Icon.SPARKLE;
            case SCOPED_VISIBILITY_ID -> Icons.Icon.EYE;
            case "waypoints" -> Icons.Icon.PIN;
            case "appearance" -> Icons.Icon.BRUSH;
            case "routes" -> Icons.Icon.ROUTE;
            case "dungeons" -> Icons.Icon.SKULL;
            case "mining" -> Icons.Icon.DUNGEON_BREAKER;
            case "chat" -> Icons.Icon.CHAT;
            case "sharing" -> Icons.Icon.SHARE;
            case "feature_bloat" -> Icons.Icon.STAR;
            case "system" -> Icons.Icon.GEAR;
            case "feedback" -> Icons.Icon.MAIL;
            case SCOPED_SECTION_PREFIX + "waypoint_marker" -> Icons.Icon.MARKER;
            case SCOPED_SECTION_PREFIX + "sequenced" -> Icons.Icon.PREV_NEXT;
            case SCOPED_SECTION_PREFIX + "etherwarp" -> Icons.Icon.DUNGEON_ETHERWARP;
            case SCOPED_SECTION_PREFIX + "labels" -> Icons.Icon.TAG;
            case SCOPED_SECTION_PREFIX + "tracers" -> Icons.Icon.TRACER;
            case SCOPED_SECTION_PREFIX + "beacon_beams" -> Icons.Icon.BEACON;
            case SCOPED_SECTION_PREFIX + "route_lines" -> Icons.Icon.ROUTE;
            default -> null;
        };
    }

    static int sidebarRowsTop(int panelTop) {

        return panelTop + 4 + BTN_H + GAP;
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
            g.text(font, Component.literal(row.caption).withStyle(ChatFormatting.BOLD),
                    labelX, rowTop + 5, TEXT_MUTED, false);
            return;
        }

        Setting setting = row.setting;
        if (setting == null) {
            for (WidgetSlot slot : row.widgets) slot.widget().extractRenderState(g, mouseX, mouseY, partial);
            return;
        }
        boolean enabled = setting.isEnabled(config, dungeonConfig);

        if (isHighlighted(setting)) {
            g.fill(layout.mainLeft() + 1, rowTop, layout.mainRight() - 8,
                    rowTop + row.height, SELECTED);
            g.fill(layout.mainLeft() + 1, rowTop, layout.mainLeft() + 3,
                    rowTop + row.height, ACCENT);
        }

        if (target != null && target.scope().isSetHere(setting.id())) {
            g.fill(layout.mainLeft() + 1, rowTop + 3, layout.mainLeft() + 3,
                    rowTop + row.height - 3, ACCENT);
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
            GuiTokens.drawDirectionGlyph(g,
                    row.expanded ? GuiTokens.Direction.DOWN : GuiTokens.Direction.RIGHT,
                    labelX + 4, rowTop + 12, TEXT_DIM);
            labelX += 14;
        }

        if (row.child) {
            int guideX = layout.mainLeft() + GAP + 14;
            g.fill(guideX, rowTop, guideX + 1,
                    rowTop + childGuideHeight(row.height, row.lastChild), BORDER);
            g.fill(guideX, rowTop + 12, labelX - 6, rowTop + 13, BORDER);
        }

        int labelMaxW = labelLimit(row, layout) - labelX;
        Component tag = sourceTag(row);
        if (tag != null) {
            String tagText = font.plainSubstrByWidth(tag.getString(), Math.max(0, labelMaxW / 2));
            int tagX = labelLimit(row, layout) - font.width(tagText);
            g.text(font, tagText, tagX, rowTop + 8, TEXT_MUTED, false);
            labelMaxW = tagX - GAP_TIGHT - labelX;
        }
        Component label = row.header
                ? SettingsText.label(setting).copy().withStyle(ChatFormatting.BOLD)
                : SettingsText.label(setting);
        var clipped = font.substrByWidth(label, Math.max(12, labelMaxW));
        int labelColor = row.header ? TEXT : !enabled ? TEXT_MUTED : row.child ? TEXT_DIM : TEXT;
        g.text(font, Language.getInstance().getVisualOrder(clipped),
                labelX, setting.kind() == Setting.Kind.ACTION && !row.widgets.isEmpty()
                        ? opticalTextY(row.widgets.getFirst().widget().getY(), row.widgets.getFirst().widget().getHeight())
                        : rowTop + 8, labelColor, false);

        for (WidgetSlot slot : row.widgets) {
            slot.widget().extractRenderState(g, mouseX, mouseY, partial);
        }
        if (row.dot != null) {
            row.dot.widget().extractRenderState(g, mouseX, mouseY, partial);
        }
    }

    private Component sourceTag(Row row) {
        if (row.chip != null) return null;
        String id = row.setting.id();
        if (target == null) return zoneOverrideTags.get(id);
        if (target.scope().isSetHere(id)) return null;
        AppearanceScope.Layer source = target.scope().source(id);
        return source == null ? null : target.sourceLabel(source);
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
        g.fill(x, y1, x + 2, y2, BORDER);
        g.fill(x, thumbY, x + 2, thumbY + thumbHeight, TEXT_MUTED);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        Layout layout = layout();
        if (contextMenu.isOpen() && contextMenu.mouseClicked(mx, my)) return true;

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

        if (mx >= layout.sidebarLeft() && mx <= layout.sidebarRight()
                && my >= layout.top() && my <= layout.bottom()) {
            int rowsTop = sidebarRowsTop(layout.top());
            int rowsBottom = layout.bottom() - GAP_TIGHT;
            if (my >= rowsTop && my < rowsBottom) {
                List<SidebarEntry> entries = sidebarEntries();
                int index = (int) ((my - rowsTop + sidebarScrollOffset) / ROW_H);
                if (index >= 0 && index < entries.size()) {
                    if (sidebarNavigation != null) {
                        sidebarNavigation.setCursor(index);
                        sidebarNavigation.playDownSound(minecraft.getSoundManager());
                    }
                    selectCategory(entries.get(index).id());
                }
            }
            return true;
        }

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
        if (home == null || !categoryAvailable(home.id(), config)) return;
        activeCategoryId = home.id();
        if (target == null) lastCategoryId = home.id();
        searchQuery = "";
        pendingScrollToSettingId = settingId;
        highlightSettingId = settingId;
        highlightUntilMillis = System.currentTimeMillis() + HIGHLIGHT_MS;
        rebuildWidgets();
    }

    private void selectCategory(String categoryId) {
        if (!categoryAvailable(categoryId, config)
                || categoryId.equals(activeCategoryId) && !searchActive()) return;
        activeCategoryId = categoryId;
        if (target == null && !RECENT_ID.equals(categoryId)) lastCategoryId = categoryId;
        searchQuery = "";
        scrollOffset = 0;
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (contextMenu.keyPressed(event.key())) return true;
        if (event.key() == GLFW_KEY_F && controlDown() && searchBox != null) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (super.keyPressed(event)) return true;

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

    private static boolean controlDown() {
        var win = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(win, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(win, GLFW_KEY_RIGHT_CONTROL);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
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
