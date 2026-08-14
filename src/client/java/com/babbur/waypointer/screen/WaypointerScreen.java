package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.color.RouteColorPolicy;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteProgress;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.debug.DebugEventLog;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.dungeon.DungeonRoomRouteProjection;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static com.babbur.waypointer.screen.WaypointerZoneCatalog.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

public final class WaypointerScreen extends Screen {

    static final int TEMPORARY_ACCENT = 0xFF58C878;
    static final int DUNGEON_ROOM_ACCENT = 0xFFFF8A8A;
    private static final Set<String> expandedDungeonRoomZoneIds = new HashSet<>();

    final ActiveGroupManager manager;
    final WaypointerConfig config;
    String selectedZoneId;
    String selectedDungeonRoomZoneId;
    int scrollOffset;
    String selectedGroupId;
    final LinkedHashSet<String> selectedGroupIds = new LinkedHashSet<>();
    private final WaypointerRouteList routeList;
    private String selectionAnchorGroupId;
    private String pendingFocusGroupId;
    private String pendingFocusRoomZoneId;
    private String lastObservedCurrentRoomZoneId;

    private static final long CONFIRM_WINDOW_MS = 2500L;
    private static final String DELETE_LABEL  = "Delete";
    private static final String HIDE_ALL_ROUTES_LABEL = "Hide All";
    private static final String HIDE_ALL_ROUTES_TOOLTIP_DEFAULT =
            "Hide every shown route in this zone.\n"
          + "Double click to confirm.";
    private static final String CONFIRM_LABEL = "Confirm?";
    private static final String NO_SEL_LABEL  = "Select";
    private static final String DELETE_TOOLTIP_DEFAULT =
            "Remove the selected route permanently.\n"
          + "Double click to confirm.";
    private static final int SEARCH_CLEAR_BTN_W = 52;
    private static final int MOVE_ZONE_BTN_W = 76;
    private static final long MAIN_NOTICE_MS = 2500L;
    private static final int NEW_ROUTE_BTN_W = 72;
    private static final int HIDE_ALL_ROUTES_BTN_W = 64;
    private static final int IMPORT_EXPORT_BTN_W = 96;
    private static final int DELETE_BTN_W = 56;
    private static final int DONE_BTN_W = 56;
    private static final int SETTINGS_BTN_W = 76;
    private static final int ISLAND_SELECTOR_W = 150;
    private static final int ISLAND_DROPDOWN_W = 210;
    private static final int HEADER_BTN_Y = PAD_OUTER - 5;
    private static final String IMPORT_EXPORT_LABEL = "Import/Export";
    private static final String MENU_IMPORT_LABEL = "Import from clipboard";
    private static final String MENU_EXPORT_LABEL = "Export...";
    private OverlayButton hideAllRoutesBtn;
    private Button deleteBtn;
    private Button importExportBtn;
    private Button islandSelectorBtn;
    private EditBox searchBox;
    private OverlayButton clearSearchButton;
    private OverlayButton moveZoneButton;
    private Button settingsButton;
    String searchQuery = "";
    private boolean importInFlight;
    private boolean islandDropdownOpen;
    private String zoneMoveGroupId;
    private DropdownTab dropdownTab = DropdownTab.ISLANDS;
    private boolean showAllIslands;
    private int dropdownScrollOffset;
    private boolean importExportMenuOpen;
    private final TimedIdConfirmation hideAllConfirmation = new TimedIdConfirmation();
    private final TimedIdConfirmation deleteConfirmation = new TimedIdConfirmation();
    private String mainNotice = "";
    private long mainNoticeUntil = 0L;

    private enum DropdownTab { ISLANDS, DUNGEONS }

    private List<GuiTokens.ButtonSpec> footerActions() {
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.main.import_export").getString(),
                IMPORT_EXPORT_BTN_W,
                this::toggleImportExportMenu,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.main.import_export.tooltip"))));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.main.new_route").getString(),
                NEW_ROUTE_BTN_W, this::createGroup));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.main.delete").getString(),
                DELETE_BTN_W, this::onDeleteClicked));
        return left;
    }

    static int footerRequiredWidth() {
        return PAD_OUTER
                + IMPORT_EXPORT_BTN_W + NEW_ROUTE_BTN_W + DELETE_BTN_W
                + GAP * 2 + GAP_SECTION + DONE_BTN_W + PAD_OUTER;
    }

    public WaypointerScreen(ActiveGroupManager manager, WaypointerConfig config) {
        super(Component.translatable("waypointer.screen.main.title"));
        this.manager = manager;
        this.config = config;
        this.routeList = new WaypointerRouteList(this);
        this.selectedZoneId = initialSelectedZoneId(manager);
        this.lastObservedCurrentRoomZoneId = currentDungeonRoomZoneId(manager);
    }

    public static void open(ActiveGroupManager manager, WaypointerConfig config) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        screen.focusCurrentDungeonRoomOnOpen();
        MinecraftCompat.setScreen(Minecraft.getInstance(), screen);
    }

    public static void openDungeonRooms(ActiveGroupManager manager, WaypointerConfig config) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        screen.selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        String roomZoneId = currentDungeonRoomZoneId(manager);
        if (roomZoneId != null) {
            screen.lastObservedCurrentRoomZoneId = roomZoneId;
            screen.selectedDungeonRoomZoneId = roomZoneId;
            expandedDungeonRoomZoneIds.add(roomZoneId);
            screen.pendingFocusRoomZoneId = roomZoneId;
        }
        screen.clearRouteSelection();
        MinecraftCompat.setScreen(Minecraft.getInstance(), screen);
    }

    public static void openFocused(ActiveGroupManager manager, WaypointerConfig config,
                                   WaypointGroup focus) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        if (focus != null) {
            screen.selectedZoneId = focus.temp()
                    ? TEMPORARY_ZONE_ID
                    : focus.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    ? DUNGEON_ROOMS_ZONE_ID
                    : focus.zoneId();
            if (!focus.temp() && focus.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
                screen.pendingFocusRoomZoneId = focus.zoneId();
                screen.selectedDungeonRoomZoneId = focus.zoneId();
                expandedDungeonRoomZoneIds.add(focus.zoneId());
            }
            screen.pendingFocusGroupId = focus.id();
        }
        MinecraftCompat.setScreen(Minecraft.getInstance(), screen);
    }

    private void focusCurrentDungeonRoomOnOpen() {
        String roomZoneId = currentDungeonRoomZoneId(manager);
        if (roomZoneId == null) return;
        lastObservedCurrentRoomZoneId = roomZoneId;
        selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        selectedDungeonRoomZoneId = roomZoneId;
        expandedDungeonRoomZoneIds.add(roomZoneId);
        clearRouteSelection();
        pendingFocusRoomZoneId = roomZoneId;
    }

    @Override
    protected void init() {
        int footerY = height - FOOTER_H;
        hideAllConfirmation.clear();
        deleteConfirmation.clear();
        hideAllRoutesBtn = null;
        deleteBtn = null;
        importExportBtn = null;
        islandSelectorBtn = null;
        searchBox = null;
        clearSearchButton = null;
        moveZoneButton = null;
        settingsButton = null;
        routeList.resetNavigation();
        islandDropdownOpen = false;
        zoneMoveGroupId = null;
        importExportMenuOpen = false;

        Layout layout = layout();
        List<GuiTokens.ButtonSpec> left = footerActions();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec(
                Component.translatable("gui.done").getString(),
                DONE_BTN_W, this::onClose);

        GuiTokens.layoutFooter(width, footerY, left, done,
                b -> {
            if (Component.translatable("waypointer.screen.main.import_export").getString()
                    .contentEquals(b.getMessage().getString())) {
                importExportBtn = b;
            }
            if (Component.translatable("waypointer.screen.main.delete").getString()
                    .contentEquals(b.getMessage().getString())) {
                deleteBtn = b;
                deleteBtn.setTooltip(Tooltip.create(Component.translatable(
                        "waypointer.screen.main.delete.tooltip")));
            }
            addRenderableWidget(b);
        }, font, layout.mainLeft(), width - layout.mainRight());

        searchBox = new EditBox(font, 0, 0, 100, BTN_H,
                Component.translatable("waypointer.screen.main.search"));
        searchBox.setMaxLength(80);
        searchBox.setValue(searchQuery);
        searchBox.setHint(Component.translatable("waypointer.screen.main.search"));
        searchBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.search.tooltip")));
        searchBox.setResponder(this::onSearchChanged);
        syncSearchBoxGeometry();
        addRenderableWidget(searchBox);
        clearSearchButton = new OverlayButton(0, 0, SEARCH_CLEAR_BTN_W, BTN_H,
                Component.translatable("waypointer.common.clear"), this::clearRouteSearch);
        clearSearchButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.search.clear.tooltip")));
        updateClearSearchButton();
        syncSearchBoxGeometry();
        addRenderableWidget(clearSearchButton);

        moveZoneButton = new OverlayButton(0, 0, MOVE_ZONE_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.move_zone"),
                this::startZoneMoveForSelection);
        moveZoneButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.move_zone.tooltip")));
        syncSearchBoxGeometry();
        addRenderableWidget(moveZoneButton);

        OverlayButton hideAll = new OverlayButton(0, 0, HIDE_ALL_ROUTES_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.hide_all"),
                b -> onHideAllRoutesClicked());
        hideAll.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.hide_all.tooltip")));
        hideAllRoutesBtn = hideAll;
        syncSearchBoxGeometry();
        addRenderableWidget(hideAll);

        settingsButton = new GuiTokens.StyledButton(PAD_OUTER, HEADER_BTN_Y,
                SETTINGS_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.settings"), b -> openSettings());
        settingsButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.settings.tooltip")));
        addRenderableWidget(settingsButton);

        islandSelectorBtn = new GuiTokens.StyledButton(
                width - PAD_OUTER - ISLAND_SELECTOR_W, HEADER_BTN_Y,
                ISLAND_SELECTOR_W, BTN_H, islandSelectorLabel(), b -> toggleIslandDropdown());
        addRenderableWidget(islandSelectorBtn);

        if (pendingFocusRoomZoneId != null) {
            focusRoomByZoneId(pendingFocusRoomZoneId);
            pendingFocusRoomZoneId = null;
        }
        if (pendingFocusGroupId != null) {
            selectGroupById(pendingFocusGroupId);
            pendingFocusGroupId = null;
        }
        routeList.buildNavigation(
                layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom());
        refreshActionButtons();
    }

    private void selectGroupById(String id) {
        routeList.selectGroupById(id);
    }

    private void focusRoomByZoneId(String roomZoneId) {
        routeList.focusRoomByZoneId(roomZoneId);
    }

    private void onSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(searchQuery)) return;
        searchQuery = next;
        scrollOffset = 0;
        clearRouteSelection();
        refreshActionButtons();
        updateClearSearchButton();
    }

    private void clearRouteSearch(Button button) {
        if (searchBox != null) {
            searchBox.setValue("");
        }
        onSearchChanged("");
    }

    private void syncSearchBoxGeometry() {
        if (searchBox == null) return;
        Layout layout = layout();
        int left = layout.mainLeft() + GAP;
        int toolbarY = layout.top() + 4;
        searchBox.setX(left);
        searchBox.setY(toolbarY);
        int availableWidth = layout.mainRight() - layout.mainLeft() - GAP * 2;
        int clearWidth = clearSearchButton == null ? 0 : SEARCH_CLEAR_BTN_W + GAP_TIGHT;
        boolean showMoveZoneButton = Zone.PRIVATE_WORLD.id().equals(selectedZoneId);
        int hideAllWidth = hideAllRoutesBtn == null ? 0 : HIDE_ALL_ROUTES_BTN_W + GAP;
        int moveZoneWidth = showMoveZoneButton ? MOVE_ZONE_BTN_W + GAP : 0;
        int searchWidth = Math.max(80,
                Math.min(180, availableWidth - clearWidth - moveZoneWidth - hideAllWidth));
        searchBox.setWidth(searchWidth);
        if (clearSearchButton != null) {
            clearSearchButton.setX(left + searchWidth + GAP_TIGHT);
            clearSearchButton.setY(toolbarY);
            clearSearchButton.setWidth(SEARCH_CLEAR_BTN_W);
        }
        int rightEdge = layout.mainRight() - GAP;
        if (hideAllRoutesBtn != null) {
            hideAllRoutesBtn.setX(rightEdge - HIDE_ALL_ROUTES_BTN_W);
            hideAllRoutesBtn.setY(toolbarY);
            hideAllRoutesBtn.setWidth(HIDE_ALL_ROUTES_BTN_W);
            rightEdge -= HIDE_ALL_ROUTES_BTN_W + GAP;
        }
        if (moveZoneButton != null) {
            moveZoneButton.visible = showMoveZoneButton;
            moveZoneButton.setX(rightEdge - MOVE_ZONE_BTN_W);
            moveZoneButton.setY(toolbarY);
            moveZoneButton.setWidth(MOVE_ZONE_BTN_W);
            if (showMoveZoneButton) rightEdge -= MOVE_ZONE_BTN_W + GAP;
        }
    }

    private void updateClearSearchButton() {
        if (clearSearchButton != null) {
            clearSearchButton.active = searchQuery != null && !searchQuery.isEmpty();
        }
    }

    private void openSettings() {
        MinecraftCompat.setScreen(minecraft,
                new SettingsScreen(this, config, WaypointerClient.dungeonConfig()));
    }

    private List<String> islandDropdownIds() {
        List<String> ids = new ArrayList<>(islandDropdownIdsForManager(
                manager, showAllIslands || zoneMoveGroupId != null));
        if (zoneMoveGroupId != null) {
            WaypointGroup moving = manager.get(zoneMoveGroupId);
            ids.removeIf(id -> !canRetargetRoute(moving, id));
        }
        if (zoneMoveGroupId == null
                && !isTemporaryZone(selectedZoneId)
                && !isDungeonRoomsZone(selectedZoneId)
                && !ids.contains(selectedZoneId)) {
            ids.add(0, selectedZoneId);
        }
        return ids;
    }

    private boolean canToggleEmptyIslands() {
        return islandDropdownIdsForManager(manager, true).size()
                > islandDropdownIdsForManager(manager, false).size();
    }

    private List<WaypointGroup> shownRoutesForSelectedZone() {
        List<WaypointGroup> out = new ArrayList<>();
        if (isTemporaryZone(selectedZoneId)) return out;

        if (isDungeonRoomsZone(selectedZoneId)) {
            out.addAll(shownDungeonRoomGroups(manager.allGroups()));
        } else {
            for (WaypointGroup group : manager.groupsForZone(selectedZoneId)) {
                if (group != null && !group.temp() && group.enabled()) out.add(group);
            }
        }
        return out;
    }

    static List<WaypointGroup> shownDungeonRoomGroups(Iterable<WaypointGroup> groups) {
        List<WaypointGroup> out = new ArrayList<>();
        if (groups == null) return out;
        for (WaypointGroup group : groups) {
            if (group != null
                    && !group.temp()
                    && group.enabled()
                    && group.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
                out.add(group);
            }
        }
        return out;
    }

    private int normalGroupCountForZone(String zoneId) {
        if (isDungeonRoomsZone(zoneId)) return dungeonRoomGroupCount(manager);
        return WaypointerZoneCatalog.normalGroupCountForZone(manager, zoneId);
    }

    private int temporaryWaypointCount() {
        int count = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp()) count += group.size();
        }
        return count;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        focusCurrentDungeonRoomIfChanged();
        boolean allowBackgroundHover = backgroundHoverAllowed(
                islandDropdownOpen, importExportMenuOpen);
        int backgroundMouseX = allowBackgroundHover ? mouseX : -1;
        int backgroundMouseY = allowBackgroundHover ? mouseY : -1;
        super.extractRenderState(g, backgroundMouseX, backgroundMouseY, partial);

        long now = System.currentTimeMillis();
        if (hideAllRoutesBtn != null && hideAllConfirmation.expire(now)) {
            resetHideAllRoutesButton();
        }
        if (deleteBtn != null) {
            if (deleteConfirmation.expire(now)) {
                resetDeleteButton();
            }
            if (labelFlashUntil != 0 && now > labelFlashUntil) {
                labelFlashUntil = 0;
                if (!deleteConfirmation.isArmed(now)) resetDeleteButton();
            }
        }

        if (islandSelectorBtn != null) {
            islandSelectorBtn.setMessage(islandSelectorLabel());
        }
        String headerTitle = getTitle().getString();
        int titleLeftLimit = PAD_OUTER + SETTINGS_BTN_W + GAP;
        int titleRightLimit = width - PAD_OUTER - ISLAND_SELECTOR_W - GAP;
        int infoSpan = GAP_TIGHT + 1 + WaypointerRouteList.INFO_BUTTON_SIZE;
        String clippedTitle = font.plainSubstrByWidth(headerTitle,
                Math.max(0, titleRightLimit - titleLeftLimit - infoSpan));
        int titleW = font.width(clippedTitle);
        int titleX = MathUtil.clamp((width - titleW - infoSpan) / 2,
                titleLeftLimit, Math.max(titleLeftLimit, titleRightLimit - titleW - infoSpan));
        boolean infoHovered = false;
        if (!clippedTitle.isEmpty()) {
            g.text(font, clippedTitle, titleX, PAD_OUTER, TEXT, false);
            int infoX = headerInfoButtonX(titleX, titleW);
            int infoY = headerInfoButtonY();
            infoHovered = isInside(backgroundMouseX, backgroundMouseY, infoX, infoY,
                    WaypointerRouteList.INFO_BUTTON_SIZE,
                    WaypointerRouteList.INFO_BUTTON_SIZE);
            routeList.renderInfoButton(g, infoX, infoY, infoHovered);
        }

        Layout layout = layout();

        routeList.render(g, layout.mainLeft(), layout.top(), layout.mainRight(),
                layout.bottom(), backgroundMouseX, backgroundMouseY);
        routeList.renderNavigationOverlay(g, backgroundMouseX, backgroundMouseY, partial);
        renderSearchBox(g, backgroundMouseX, backgroundMouseY, partial);

        if (islandDropdownOpen) {
            renderIslandDropdown(g, mouseX, mouseY);
        }
        if (importExportMenuOpen) {
            renderImportExportMenu(g, mouseX, mouseY);
        }
        if (infoHovered) {
            routeList.renderInfoTooltip(g, mouseX, mouseY, width, layout.bottom());
        }
    }

    private void focusCurrentDungeonRoomIfChanged() {
        String currentRoomZoneId = currentDungeonRoomZoneId(manager);
        if (currentRoomZoneId == null
                ? lastObservedCurrentRoomZoneId == null
                : currentRoomZoneId.equals(lastObservedCurrentRoomZoneId)) {
            return;
        }
        lastObservedCurrentRoomZoneId = currentRoomZoneId;
        if (currentRoomZoneId == null) return;
        focusRoomByZoneId(currentRoomZoneId);
        refreshActionButtons();
    }

    private Component islandSelectorLabel() {
        String label = isDungeonRoomsZone(selectedZoneId)
                ? Component.translatable("waypointer.screen.main.dungeon_routes").getString()
                : displayZoneLabel(selectedZoneId);
        return Component.literal((islandDropdownOpen ? "v " : "> ") + label);
    }

    private void renderSearchBox(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        syncSearchBoxGeometry();
        if (searchBox != null) {
            searchBox.extractWidgetRenderState(g, mouseX, mouseY, partial);
        }
        if (clearSearchButton != null) {
            clearSearchButton.extractOverlay(g, mouseX, mouseY, partial);
        }
        if (moveZoneButton != null && moveZoneButton.visible) {
            moveZoneButton.extractOverlay(g, mouseX, mouseY, partial);
        }
        if (hideAllRoutesBtn != null) {
            hideAllRoutesBtn.extractOverlay(g, mouseX, mouseY, partial);
        }
    }

    private Layout layout() {
        int mainLeft = PAD_OUTER;
        int mainRight = width - PAD_OUTER;
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec(
                Component.translatable("gui.done").getString(),
                DONE_BTN_W, this::onClose);
        int footerSpace = GuiTokens.footerHeight(width, footerActions(), done, font,
                mainLeft, width - mainRight);
        int top = HEADER_BTN_Y + BTN_H + GAP;
        int bottom = height - footerSpace - GAP_SECTION;
        return new Layout(top, bottom, mainLeft, mainRight);
    }

    static boolean backgroundHoverAllowed(boolean islandDropdownOpen,
                                          boolean importExportMenuOpen) {
        return !islandDropdownOpen && !importExportMenuOpen;
    }

    Layout layoutForRouteList() {
        return layout();
    }

    net.minecraft.client.gui.Font routeListFont() {
        return font;
    }

    void registerRouteListNavigation(ListNavigationWidget navigation) {
        addRenderableWidget(navigation);
    }

    void openGroupEditor(WaypointGroup group) {
        MinecraftCompat.setScreen(minecraft, new GroupEditScreen(this, manager, config, group));
    }

    void expandDungeonRoom(String roomZoneId) {
        if (roomZoneId != null) expandedDungeonRoomZoneIds.add(roomZoneId);
    }

    record Layout(int top, int bottom, int mainLeft, int mainRight) {}

    private static final class OverlayButton extends GuiTokens.StyledButton {
        private OverlayButton(int x, int y, int width, int height,
                              Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress);
        }

        private void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            extractWidgetRenderState(g, mouseX, mouseY, partial);
        }

    }

    private static final int OVERLAY_SURFACE = 0xF0101216;

    private record DropdownGeometry(int x1, int y1, int x2, int y2,
                                    int tabsTop, int tabsBottom,
                                    int rowsTop, int rowsBottom) {}

    private DropdownGeometry islandDropdownGeometry(int rowCount) {
        int x2 = width - PAD_OUTER;
        int x1 = x2 - ISLAND_DROPDOWN_W;
        int y1 = HEADER_BTN_Y + BTN_H + GAP_TIGHT;
        int maxBottom = Math.max(y1 + BTN_H + ROW_H + 3, layout().bottom());
        int tabsTop = y1 + 1;
        int tabsBottom = tabsTop + BTN_H;
        int rowsTop = tabsBottom + 1;
        int y2 = Math.min(rowsTop + rowCount * ROW_H + 1, maxBottom);
        return new DropdownGeometry(x1, y1, x2, y2,
                tabsTop, tabsBottom, rowsTop, y2 - 1);
    }

    private void toggleIslandDropdown() {
        zoneMoveGroupId = null;
        islandDropdownOpen = !islandDropdownOpen;
        importExportMenuOpen = false;
        if (islandDropdownOpen) {
            dropdownTab = isDungeonRoomsZone(selectedZoneId)
                    ? DropdownTab.DUNGEONS : DropdownTab.ISLANDS;
            showAllIslands = false;
            dropdownScrollOffset = dropdownScrollForSelectedZone();
        }
    }

    private int dropdownScrollForSelectedZone() {
        if (dropdownTab == DropdownTab.DUNGEONS) return 0;
        List<String> ids = islandDropdownIds();
        int index = ids.indexOf(selectedZoneId);
        if (index <= 0) return 0;
        int rowCount = ids.size() + (canToggleEmptyIslands() ? 1 : 0);
        DropdownGeometry geo = islandDropdownGeometry(rowCount);
        int viewport = geo.rowsBottom() - geo.rowsTop();
        int target = index * ROW_H - Math.max(0, viewport / 2 - ROW_H / 2);
        return MathUtil.clamp(target, 0, maxDropdownScroll(rowCount, viewport));
    }

    private void renderIslandDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<String> ids = islandDropdownIds();
        boolean showToggle = zoneMoveGroupId == null
                && dropdownTab == DropdownTab.ISLANDS && canToggleEmptyIslands();
        int rowCount = dropdownTab == DropdownTab.DUNGEONS ? 1 : ids.size() + (showToggle ? 1 : 0);
        DropdownGeometry geo = islandDropdownGeometry(rowCount);
        fillOutlinedOverlay(g, geo.x1(), geo.y1(), geo.x2(), geo.y2());
        renderDropdownTabs(g, geo, mouseX, mouseY);

        if (dropdownTab == DropdownTab.DUNGEONS) {
            g.text(font, Component.translatable(
                            "waypointer.screen.main.dungeons.all_rooms"),
                    geo.x1() + GAP,
                    geo.rowsTop() + 6, DUNGEON_ROOM_ACCENT, false);
            return;
        }

        int viewport = geo.rowsBottom() - geo.rowsTop();
        dropdownScrollOffset = MathUtil.clamp(dropdownScrollOffset, 0,
                maxDropdownScroll(rowCount, viewport));
        String currentId = manager.currentZone() == null ? null : manager.currentZone().id();
        g.enableScissor(geo.x1(), geo.rowsTop(), geo.x2(), geo.rowsBottom());
        for (int i = 0; i < ids.size(); i++) {
            int rowY = geo.rowsTop() - dropdownScrollOffset + i * ROW_H;
            if (rowY + ROW_H <= geo.rowsTop()) continue;
            if (rowY >= geo.rowsBottom()) break;
            String id = ids.get(i);
            boolean hovered = mouseX >= geo.x1() && mouseX < geo.x2()
                    && mouseY >= Math.max(rowY, geo.rowsTop())
                    && mouseY < Math.min(rowY + ROW_H, geo.rowsBottom());
            if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
            drawDropdownZoneRow(g, geo.x1() + 1, rowY, geo.x2() - 1, id, hovered, currentId);
        }
        if (showToggle) {
            int rowY = geo.rowsTop() - dropdownScrollOffset + ids.size() * ROW_H;
            if (rowY < geo.rowsBottom() && rowY + ROW_H > geo.rowsTop()) {
                boolean hovered = mouseX >= geo.x1() && mouseX < geo.x2()
                        && mouseY >= Math.max(rowY, geo.rowsTop())
                        && mouseY < Math.min(rowY + ROW_H, geo.rowsBottom());
                if (hovered) {
                    g.fill(geo.x1() + 1, rowY, geo.x2() - 1, rowY + ROW_H, HOVER);
                    g.requestCursor(CursorTypes.POINTING_HAND);
                }
                String label = Component.translatable(showAllIslands
                        ? "waypointer.screen.main.show_less"
                        : "waypointer.screen.main.show_more").getString();
                g.text(font, label, geo.x1() + GAP + 2, rowY + 6,
                        hovered ? TEXT : ACCENT, false);
            }
        }
        g.disableScissor();

        int contentHeight = rowCount * ROW_H;
        if (contentHeight > viewport) {
            int thumbH = Math.max(8, viewport * viewport / contentHeight);
            int travel = viewport - thumbH;
            int maxScroll = maxDropdownScroll(rowCount, viewport);
            int thumbY = geo.rowsTop()
                    + (maxScroll == 0 ? 0 : dropdownScrollOffset * travel / maxScroll);
            g.fill(geo.x2() - 3, thumbY, geo.x2() - 1, thumbY + thumbH, TEXT_MUTED);
        }
    }

    private void renderDropdownTabs(GuiGraphicsExtractor g, DropdownGeometry geo,
                                    int mouseX, int mouseY) {
        if (zoneMoveGroupId != null) {
            String label = Component.translatable(
                    "waypointer.screen.main.move_route_to").getString();
            int textX = geo.x1() + (geo.x2() - geo.x1() - font.width(label)) / 2;
            g.fill(geo.x1() + 1, geo.tabsTop(), geo.x2() - 1, geo.tabsBottom(), SELECTED);
            g.text(font, label, textX, geo.tabsTop() + 6, TEXT, false);
            g.fill(geo.x1() + 1, geo.tabsBottom(), geo.x2() - 1, geo.tabsBottom() + 1, BORDER);
            return;
        }
        int split = (geo.x1() + geo.x2()) / 2;
        drawDropdownTab(g, geo.x1() + 1, split, geo.tabsTop(), geo.tabsBottom(),
                Component.translatable("waypointer.screen.main.islands").getString(),
                dropdownTab == DropdownTab.ISLANDS, mouseX, mouseY);
        drawDropdownTab(g, split, geo.x2() - 1, geo.tabsTop(), geo.tabsBottom(),
                Component.translatable("waypointer.screen.main.dungeons").getString(),
                dropdownTab == DropdownTab.DUNGEONS, mouseX, mouseY);
        g.fill(geo.x1() + 1, geo.tabsBottom(), geo.x2() - 1, geo.tabsBottom() + 1, BORDER);
    }

    private void drawDropdownTab(GuiGraphicsExtractor g, int x1, int x2, int y1, int y2,
                                 String label, boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
        if (selected || hovered) g.fill(x1, y1, x2, y2, selected ? SELECTED : HOVER);
        if (selected) g.fill(x1, y2 - 1, x2, y2, ACCENT);
        int textX = x1 + (x2 - x1 - font.width(label)) / 2;
        g.text(font, label, textX, y1 + 6, selected || hovered ? TEXT : TEXT_DIM, false);
        if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void drawDropdownZoneRow(GuiGraphicsExtractor g, int x1, int y, int x2,
                                     String zoneId, boolean hovered, String currentId) {
        boolean selected = zoneId.equals(selectedZoneId);
        boolean temporary = isTemporaryZone(zoneId);
        boolean dungeonParent = isDungeonRoomsZone(zoneId);
        boolean isCurrent = !temporary
                && (zoneId.equals(currentId) || (dungeonParent && isDungeonRoomZone(currentId)));
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);

        boolean isUnknown = Zone.UNKNOWN.id().equals(zoneId);
        int accent = temporary ? TEMPORARY_ACCENT
                : dungeonParent ? DUNGEON_ROOM_ACCENT
                : ACCENT;
        if (selected && (!isUnknown || temporary)) {
            g.fill(x1 + 1, y + ROW_H - 2, x2 - 1, y + ROW_H, accent);
        }

        String label = displayZoneLabel(zoneId);
        int count = temporary ? temporaryWaypointCount() : normalGroupCountForZone(zoneId);
        int textColor = isUnknown && !temporary ? TEXT_MUTED : selected ? TEXT : TEXT_DIM;
        boolean emptyZone = count == 0;
        if (emptyZone && !selected && !isCurrent) textColor = TEXT_MUTED;

        String countStr = Integer.toString(count);
        int countX = (isCurrent ? x2 - GAP - 12 : x2 - GAP) - font.width(countStr);
        int labelX = x1 + GAP + 2;
        int labelMaxW = Math.max(12, countX - GAP_TIGHT - labelX);
        g.text(font, font.plainSubstrByWidth(label, labelMaxW), labelX, y + 6, textColor, false);

        if (isCurrent) {
            int dotX = x2 - GAP - 6;
            g.fill(dotX, y + ROW_H / 2 - 2, dotX + 4, y + ROW_H / 2 + 2, accent);
        }
        g.text(font, countStr, countX, y + 6, TEXT_MUTED, false);
    }

    static int maxDropdownScroll(int zoneCount, int viewportHeight) {
        return Math.max(0, zoneCount * ROW_H - Math.max(0, viewportHeight));
    }

    static int dropdownScrollAfterWheel(int currentOffset, double wheelDelta,
                                        int zoneCount, int viewportHeight) {
        int maxScroll = maxDropdownScroll(zoneCount, viewportHeight);
        return MathUtil.clamp(currentOffset - (int) (wheelDelta * ROW_H), 0, maxScroll);
    }

    static int dropdownRowIndexAt(double mouseY, int rowsTop, int rowsBottom,
                                  int scrollOffset, int zoneCount) {
        if (mouseY < rowsTop || mouseY >= rowsBottom) return -1;
        int index = (int) ((mouseY - rowsTop + scrollOffset) / ROW_H);
        return index >= 0 && index < zoneCount ? index : -1;
    }

    private boolean handleIslandDropdownClick(double mx, double my) {
        List<String> ids = islandDropdownIds();
        boolean showToggle = zoneMoveGroupId == null
                && dropdownTab == DropdownTab.ISLANDS && canToggleEmptyIslands();
        int rowCount = dropdownTab == DropdownTab.DUNGEONS ? 1 : ids.size() + (showToggle ? 1 : 0);
        DropdownGeometry geo = islandDropdownGeometry(rowCount);
        boolean inside = mx >= geo.x1() && mx < geo.x2() && my >= geo.y1() && my < geo.y2();
        if (!inside) {
            // Consume outside clicks so the selector cannot reopen immediately.
            islandDropdownOpen = false;
            zoneMoveGroupId = null;
            return true;
        }
        if (my >= geo.tabsTop() && my < geo.tabsBottom()) {
            if (zoneMoveGroupId != null) return true;
            int split = (geo.x1() + geo.x2()) / 2;
            if (mx < split) {
                dropdownTab = DropdownTab.ISLANDS;
                if (isDungeonRoomsZone(selectedZoneId)) {
                    String currentIsland = currentZoneId(manager);
                    if (isDungeonRoomZone(currentIsland)) {
                        List<String> visibleIslands = islandDropdownIdsForManager(manager);
                        currentIsland = visibleIslands.isEmpty()
                                ? Zone.UNKNOWN.id() : visibleIslands.get(0);
                    }
                    selectedZoneId = currentIsland;
                    selectedDungeonRoomZoneId = null;
                    clearRouteSelection();
                    scrollOffset = 0;
                    refreshActionButtons();
                    syncSearchBoxGeometry();
                }
            } else {
                dropdownTab = DropdownTab.DUNGEONS;
                selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
                selectedDungeonRoomZoneId = currentDungeonRoomZoneId(manager);
                clearRouteSelection();
                scrollOffset = 0;
                refreshActionButtons();
                syncSearchBoxGeometry();
            }
            dropdownScrollOffset = 0;
            return true;
        }
        if (dropdownTab == DropdownTab.DUNGEONS) return true;
        int idx = dropdownRowIndexAt(my, geo.rowsTop(), geo.rowsBottom(),
                dropdownScrollOffset, rowCount);
        if (idx >= 0 && idx < ids.size()) {
            selectZoneFromDropdown(ids.get(idx));
        } else if (showToggle && idx == ids.size()) {
            showAllIslands = !showAllIslands;
            dropdownScrollOffset = 0;
        }
        return true;
    }

    private void selectZoneFromDropdown(String zoneId) {
        islandDropdownOpen = false;
        if (zoneMoveGroupId != null) {
            WaypointGroup group = manager.get(zoneMoveGroupId);
            zoneMoveGroupId = null;
            if (retargetRoute(group, zoneId)) {
                manager.fireDataChangedFor(group);
                clearRouteSelection();
                flashMainNotice(Component.translatable("waypointer.screen.main.moved",
                        displayGroupName(group), displayZoneLabel(group.zoneId())));
                refreshActionButtons();
                syncSearchBoxGeometry();
            }
            return;
        }
        if (zoneId.equals(selectedZoneId)) return;
        selectedZoneId = zoneId;
        selectedDungeonRoomZoneId = null;
        scrollOffset = 0;
        clearRouteSelection();
        refreshActionButtons();
        syncSearchBoxGeometry();
    }

    private record MenuGeometry(int x1, int y1, int x2, int y2) {}

    private MenuGeometry importExportMenuGeometry() {
        String importLabel = Component.translatable(
                "waypointer.screen.main.menu.import_clipboard").getString();
        String exportLabel = Component.translatable(
                "waypointer.screen.main.menu.export").getString();
        int w = Math.max(importExportBtn == null ? 0 : importExportBtn.getWidth(),
                Math.max(font.width(importLabel), font.width(exportLabel))
                        + GAP * 2) + 2;
        int h = ROW_H * 2 + 2;
        int x1 = importExportBtn == null ? PAD_OUTER : importExportBtn.getX();
        int y2 = importExportBtn == null ? height - FOOTER_H : importExportBtn.getY() - GAP_TIGHT;
        x1 = Math.max(PAD_OUTER, Math.min(x1, width - PAD_OUTER - w));
        return new MenuGeometry(x1, y2 - h, x1 + w, y2);
    }

    private void toggleImportExportMenu() {
        importExportMenuOpen = !importExportMenuOpen;
        islandDropdownOpen = false;
        zoneMoveGroupId = null;
    }

    private void renderImportExportMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        MenuGeometry geo = importExportMenuGeometry();
        fillOutlinedOverlay(g, geo.x1(), geo.y1(), geo.x2(), geo.y2());
        String[] labels = {
                Component.translatable("waypointer.screen.main.menu.import_clipboard").getString(),
                Component.translatable("waypointer.screen.main.menu.export").getString()
        };
        for (int i = 0; i < labels.length; i++) {
            int rowY = geo.y1() + 1 + i * ROW_H;
            boolean hovered = mouseX >= geo.x1() && mouseX < geo.x2()
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hovered) {
                g.fill(geo.x1() + 1, rowY, geo.x2() - 1, rowY + ROW_H, HOVER);
                g.requestCursor(CursorTypes.POINTING_HAND);
            }
            g.text(font, labels[i], geo.x1() + GAP, rowY + 6, hovered ? TEXT : TEXT_DIM, false);
        }
    }

    private boolean handleImportExportMenuClick(double mx, double my) {
        MenuGeometry geo = importExportMenuGeometry();
        importExportMenuOpen = false;
        boolean inside = mx >= geo.x1() && mx < geo.x2() && my >= geo.y1() && my < geo.y2();
        if (!inside) return true; // swallow, so the anchor button doesn't re-toggle
        int idx = (int) ((my - geo.y1() - 1) / ROW_H);
        if (idx == 0) {
            importFromClipboard();
        } else if (idx == 1) {
            exportZone();
        }
        return true;
    }

    void fillOutlinedOverlay(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, OVERLAY_SURFACE);
        g.fill(x1, y1, x2, y1 + 1, BORDER);
        g.fill(x1, y2 - 1, x2, y2, BORDER);
        g.fill(x1, y1, x1 + 1, y2, BORDER);
        g.fill(x2 - 1, y1, x2, y2, BORDER);
    }

    private int headerInfoButtonX(int titleX, int titleW) {
        return titleX + titleW + GAP_TIGHT + 1;
    }

    private int headerInfoButtonY() {
        return GuiTokens.opticalInfoButtonY(PAD_OUTER);
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void flashMainNotice(String notice) {
        mainNotice = notice == null ? "" : notice;
        mainNoticeUntil = System.currentTimeMillis() + MAIN_NOTICE_MS;
    }

    private void flashMainNotice(Component notice) {
        flashMainNotice(notice == null ? "" : notice.getString());
    }

    void renderMainNotice(GuiGraphicsExtractor g, int x1, int y1, int x2) {
        if (mainNotice == null || mainNotice.isEmpty()) return;
        if (System.currentTimeMillis() > mainNoticeUntil) {
            mainNotice = "";
            mainNoticeUntil = 0L;
            return;
        }

        int searchRight = searchBox == null ? x1 + GAP : searchBox.getX() + searchBox.getWidth();
        int noticeX = searchRight + GAP;
        if (clearSearchButton != null) {
            noticeX = Math.max(noticeX, clearSearchButton.getX() + clearSearchButton.getWidth() + GAP);
        }
        int rightLimit = x2 - GAP;
        if (hideAllRoutesBtn != null) {
            rightLimit = Math.min(rightLimit, hideAllRoutesBtn.getX() - GAP);
        }
        int maxWidth = rightLimit - noticeX;
        if (maxWidth < 24) return;

        String clipped = font.plainSubstrByWidth(mainNotice, maxWidth);
        g.text(font, clipped, noticeX, y1 + 10, TEXT_DIM, false);
    }

    private static String displayGroupName(WaypointGroup group) {
        String name = group.name().trim();
        if (!group.temp()) return name.isEmpty() ? "(unnamed)" : name;
        if (name.isEmpty() || name.startsWith("Temp --")) return displayZoneLabel(TEMPORARY_ZONE_ID);
        return name;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Hit-test overlays before the widgets beneath them.
        if (islandDropdownOpen && handleIslandDropdownClick(event.x(), event.y())) return true;
        if (importExportMenuOpen && handleImportExportMenuClick(event.x(), event.y())) return true;
        if (super.mouseClicked(event, doubleClick)) return true;
        Layout layout = layout();
        return routeList.mouseClicked(event, doubleClick,
                layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom());
    }

    private static void toggleDungeonRoomSection(String roomZoneId) {
        if (!isDungeonRoomZone(roomZoneId)) return;
        if (!expandedDungeonRoomZoneIds.remove(roomZoneId)) {
            expandedDungeonRoomZoneIds.add(roomZoneId);
        }
    }

    void applyRouteRowSelection(WaypointGroup group, List<String> visibleGroupIds) {
        if (group == null) return;
        String clickedGroupId = group.id();
        boolean shiftDown = routeSelectionShiftDown();
        boolean controlDown = routeSelectionControlDown();
        LinkedHashSet<String> nextSelection = RouteSelectionPolicy.afterClick(
                visibleGroupIds, selectedGroupIds, selectionAnchorGroupId,
                clickedGroupId, controlDown, shiftDown);
        replaceRouteSelection(nextSelection);

        if (shiftDown) {
            if (selectionAnchorGroupId == null || !visibleGroupIds.contains(selectionAnchorGroupId)) {
                selectionAnchorGroupId = clickedGroupId;
            }
        } else {
            selectionAnchorGroupId = clickedGroupId;
        }

        if (selectedGroupIds.contains(clickedGroupId)) {
            selectedGroupId = clickedGroupId;
        } else {
            selectedGroupId = RouteSelectionPolicy.firstVisibleSelection(
                    visibleGroupIds, selectedGroupIds);
        }
        syncAuthoringRouteFocus();
    }

    static boolean routeSelectionShiftDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, 344 /* GLFW_KEY_RIGHT_SHIFT */);
    }

    static boolean routeSelectionControlDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, 341 /* GLFW_KEY_LEFT_CONTROL */)
                || InputConstants.isKeyDown(window, 345 /* GLFW_KEY_RIGHT_CONTROL */);
    }

    private void replaceRouteSelection(Set<String> nextSelectionIds) {
        selectedGroupIds.clear();
        if (nextSelectionIds != null) {
            for (String id : nextSelectionIds) {
                if (id != null) selectedGroupIds.add(id);
            }
        }
        selectedGroupId = selectedGroupIds.isEmpty() ? null : selectedGroupIds.iterator().next();
        clearHideAllConfirmation();
        clearDeleteConfirmation();
    }

    void selectOnlyGroupId(String groupId) {
        if (groupId == null) {
            clearRouteSelection();
            return;
        }
        LinkedHashSet<String> singleton = new LinkedHashSet<>();
        singleton.add(groupId);
        replaceRouteSelection(singleton);
        selectionAnchorGroupId = groupId;
        syncAuthoringRouteFocus();
    }

    void clearRouteSelection() {
        selectedGroupIds.clear();
        selectedGroupId = null;
        selectionAnchorGroupId = null;
        syncAuthoringRouteFocus();
        clearHideAllConfirmation();
        clearDeleteConfirmation();
    }

    private void syncAuthoringRouteFocus() {
        manager.focusRouteForAuthoring(selectedGroupId == null ? null : manager.get(selectedGroupId));
    }

    private void clearHideAllConfirmation() {
        hideAllConfirmation.clear();
        resetHideAllRoutesButton();
    }

    private void clearDeleteConfirmation() {
        deleteConfirmation.clear();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (islandDropdownOpen) {
            List<String> ids = islandDropdownIds();
            boolean showToggle = zoneMoveGroupId == null
                    && dropdownTab == DropdownTab.ISLANDS && canToggleEmptyIslands();
            int rowCount = dropdownTab == DropdownTab.DUNGEONS
                    ? 1 : ids.size() + (showToggle ? 1 : 0);
            DropdownGeometry geo = islandDropdownGeometry(rowCount);
            if (mouseX >= geo.x1() && mouseX < geo.x2()
                    && mouseY >= geo.y1() && mouseY < geo.y2()) {
                dropdownScrollOffset = dropdownScrollAfterWheel(dropdownScrollOffset, vert,
                        rowCount, geo.rowsBottom() - geo.rowsTop());
                return true;
            }
        }
        Layout layout = layout();
        routeList.scroll(vert, layout.top(), layout.bottom());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW_KEY_ESCAPE && (islandDropdownOpen || importExportMenuOpen)) {
            islandDropdownOpen = false;
            importExportMenuOpen = false;
            zoneMoveGroupId = null;
            return true;
        }
        if (super.keyPressed(event)) return true;
        if (event.key() == GLFW_KEY_DELETE) {
            onDeleteClicked();
            return true;
        }
        return false;
    }

    private void createGroup() {
        Zone current = manager.currentZone();
        String currentZoneId = current == null ? null : current.id();
        String targetZoneId = newRouteTargetZoneId(
                selectedZoneId, selectedDungeonRoomZoneId, currentZoneId);
        if (targetZoneId == null) {
            flashMainNotice(newRouteBlockedNotice(selectedZoneId));
            return;
        }
        WaypointGroup g = WaypointGroup.create(
                RouteListPresentation.nextRouteName(manager.groupsForZone(targetZoneId)),
                targetZoneId, config.skipAheadMechanicEnabled());
        if (isDungeonRoomsZone(selectedZoneId)) {
            g.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        }
        g.setDefaultRadius(config.defaultReachRadius());
        manager.add(g);
        selectedZoneId = g.routeKind() == WaypointGroup.RouteKind.DUNGEON
                ? DUNGEON_ROOMS_ZONE_ID : targetZoneId;
        if (g.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
            selectedDungeonRoomZoneId = targetZoneId;
            expandedDungeonRoomZoneIds.add(targetZoneId);
        }
        selectOnlyGroupId(g.id());
        MinecraftCompat.setScreen(minecraft, new GroupEditScreen(this, manager, config, g));
    }

    private void onHideAllRoutesClicked() {
        List<WaypointGroup> shownRoutes = shownRoutesForSelectedZone();
        if (shownRoutes.isEmpty()) {
            clearHideAllConfirmation();
            refreshActionButtons();
            return;
        }

        long now = System.currentTimeMillis();
        LinkedHashSet<String> shownRouteIds = routeIds(shownRoutes);
        if (!hideAllConfirmation.matches(shownRouteIds, now)) {
            hideAllConfirmation.arm(shownRouteIds, now, CONFIRM_WINDOW_MS);
            if (hideAllRoutesBtn != null) {
                int count = shownRoutes.size();
                hideAllRoutesBtn.setMessage(Component.translatable("waypointer.common.confirm"));
                hideAllRoutesBtn.setTooltip(Tooltip.create(Component.translatable(
                        count == 1
                                ? "waypointer.screen.main.hide_all.confirm.one"
                                : "waypointer.screen.main.hide_all.confirm.many",
                        count)));
            }
            return;
        }

        hideShownRoutes(shownRoutes);
    }

    private void hideShownRoutes(List<WaypointGroup> shownRoutes) {
        int hidden = 0;
        if (isDungeonRoomsZone(selectedZoneId)) {
            int[] count = { 0 };
            DungeonRoomRouteSync.batched(() -> {
                for (WaypointGroup group : shownRoutes) {
                    if (group == null || !group.enabled()) continue;
                    DungeonRoomRouteLibrary.setRouteEnabled(
                            manager, WaypointerClient.dungeonConfig(), group, false);
                    count[0]++;
                }
            });
            hidden = count[0];
        } else {
                hidden = RouteListPresentation.hideRoutes(shownRoutes);
        }
        if (hidden == 0) {
            clearHideAllConfirmation();
            refreshActionButtons();
            return;
        }
        clearHideAllConfirmation();
        clearDeleteConfirmation();
        manager.fireDataChanged();
        refreshActionButtons();
    }

    void refreshActionButtons() {
        if (hideAllRoutesBtn != null) {
            boolean hasShownRoutes = !shownRoutesForSelectedZone().isEmpty();
            hideAllRoutesBtn.active = hasShownRoutes;
            if (!hasShownRoutes) clearHideAllConfirmation();
        }
        if (moveZoneButton != null) {
            moveZoneButton.visible = Zone.PRIVATE_WORLD.id().equals(selectedZoneId);
            moveZoneButton.active = moveZoneButton.visible && selectedVisibleGroups().size() == 1;
        }
    }

    private void startZoneMoveForSelection(Button ignored) {
        List<WaypointGroup> selected = selectedVisibleGroups();
        if (selected.size() != 1) {
            flashMainNotice(Component.translatable(
                    "waypointer.screen.main.move_zone.select_one"));
            return;
        }
        startZoneMove(selected.get(0));
    }

    void startZoneMove(WaypointGroup group) {
        if (!canMoveRouteZone(group)) {
            flashMainNotice(Component.translatable(
                    group != null && group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                            ? "waypointer.screen.main.move_zone.dungeon_blocked"
                            : "waypointer.screen.main.move_zone.generated_blocked"));
            return;
        }
        selectOnlyGroupId(group.id());
        zoneMoveGroupId = group.id();
        islandDropdownOpen = true;
        importExportMenuOpen = false;
        dropdownTab = DropdownTab.ISLANDS;
        showAllIslands = true;
        dropdownScrollOffset = 0;
    }

    private static LinkedHashSet<String> routeIds(Collection<WaypointGroup> groups) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (groups == null) return ids;
        for (WaypointGroup group : groups) {
            if (group != null) ids.add(group.id());
        }
        return ids;
    }

    private void onDeleteClicked() {
        List<WaypointGroup> selectedGroups = selectedVisibleGroups();
        if (selectedGroups.isEmpty()) {
            flashDeleteLabel(
                    Component.translatable("waypointer.screen.main.delete.select"),
                    Component.translatable("waypointer.screen.main.delete.select.tooltip"));
            return;
        }
        LinkedHashSet<String> currentSelectedIds = new LinkedHashSet<>();
        for (WaypointGroup group : selectedGroups) {
            currentSelectedIds.add(group.id());
        }
        long now = System.currentTimeMillis();
        if (deleteConfirmation.matches(currentSelectedIds, now)) {
            deleteGroups(selectedGroups);
            clearRouteSelection();
            Layout layout = layout();
            routeList.scroll(0, layout.top(), layout.bottom());
            refreshActionButtons();
            resetDeleteButton();
            return;
        }
        deleteConfirmation.arm(currentSelectedIds, now, CONFIRM_WINDOW_MS);
        if (deleteBtn != null) {
            String targetText = selectedGroups.size() == 1
                    ? "\"" + selectedGroups.get(0).name() + "\""
                    : selectedGroups.size() + " selected routes";
            deleteBtn.setMessage(Component.translatable("waypointer.common.confirm"));
            deleteBtn.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.main.delete.confirm.tooltip", targetText)));
        }
    }

    private void deleteGroups(List<WaypointGroup> groups) {
        if (groups == null) return;
        for (WaypointGroup group : groups) {
            clearGeneratedDungeonRouteBeforeDelete(group);
            manager.remove(group.id());
        }
    }

    static boolean clearGeneratedDungeonRouteBeforeDelete(WaypointGroup group) {
        return false;
    }

    private void resetDeleteButton() {
        if (deleteBtn == null) return;
        deleteBtn.setMessage(Component.translatable("waypointer.screen.main.delete"));
        deleteBtn.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.delete.tooltip")));
    }

    private void resetHideAllRoutesButton() {
        if (hideAllRoutesBtn == null) return;
        hideAllRoutesBtn.setMessage(Component.translatable("waypointer.screen.main.hide_all"));
        hideAllRoutesBtn.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.hide_all.tooltip")));
    }

    private long labelFlashUntil = 0L;

    private void flashDeleteLabel(Component msg, Component tooltipText) {
        if (deleteBtn == null) return;
        deleteBtn.setMessage(msg);
        deleteBtn.setTooltip(Tooltip.create(tooltipText));
        labelFlashUntil = System.currentTimeMillis() + 1500L;
    }

    private List<WaypointGroup> selectedVisibleGroups() {
        return routeList.selectedVisibleGroups();
    }

    private void exportZone() {
        if (isDungeonRoomsZone(selectedZoneId)) {
            exportDungeonRooms();
            return;
        }

        List<WaypointGroup> selectedGroups = selectedVisibleGroups();
        if (selectedGroups.size() == 1) {
            ExportScreen.openForGroup(this, config, selectedGroups.get(0));
            return;
        }
        if (selectedGroups.size() > 1) {
            ExportScreen.openForGroups(this, config, selectedGroups, "Selected routes");
            return;
        }

        List<WaypointGroup> groups = exportGroupsForSelection(
                selectedGroups, routeList.visibleGroups());
        if (groups.isEmpty()) {
            flashMainNotice(emptyExportNotice(selectedZoneId));
            return;
        }
        String label = displayZoneLabel(selectedZoneId);
        ExportScreen.openForGroups(this, config, groups, label);
    }

    private void exportDungeonRooms() {
        List<WaypointGroup> selectedGroups = selectedVisibleGroups();
        List<WaypointGroup> routeGroups =
                dungeonRouteGroupsForExport(selectedGroups, manager.allGroups());
        if (!routeGroups.isEmpty()) {
            int roomCount = (int) routeGroups.stream().map(WaypointGroup::zoneId).distinct().count();
            DungeonRoomExportScreen.open(this, routeGroups, roomCount,
                    DungeonRoomShareCodec.waypointCount(routeGroups));
            return;
        }
        flashMainNotice(emptyExportNotice(selectedZoneId));
    }

    static List<WaypointGroup> dungeonRouteGroupsForExport(List<WaypointGroup> selectedGroups,
                                                           Iterable<WaypointGroup> allGroups) {
        if (selectedGroups != null && !selectedGroups.isEmpty()) return selectedGroups;
        return shownDungeonRoomGroups(allGroups);
    }

    static List<WaypointGroup> exportGroupsForSelection(List<WaypointGroup> selectedGroups,
                                                        List<WaypointGroup> visibleGroups) {
        if (selectedGroups != null && !selectedGroups.isEmpty()) return selectedGroups;
        return visibleGroups == null ? List.of() : visibleGroups;
    }

    static String emptyExportNotice(String selectedZoneId) {
        return "Nothing to export in " + displayZoneLabel(selectedZoneId) + ".";
    }

    private String importTargetZoneId() {
        Zone current = manager.currentZone();
        String currentZoneId = current == null ? null : current.id();
        return WaypointerZoneCatalog.importTargetZoneId(selectedZoneId, currentZoneId);
    }

    /** Decodes on the codec worker and installs the new groups on the client thread. */
    private void importFromClipboard() {
        if (importInFlight) return;
        String text = minecraft.keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            ImportFeedback.failure("Clipboard is empty.");
            return;
        }
        importInFlight = true;
        String targetZoneId = importTargetZoneId();

        if (DungeonRoomShareCodec.isPayload(text)) {
            if (!CodecWorker.run(() -> DungeonRoomShareCodec.decode(text), decoded -> {
                importInFlight = false;
                if (decoded == null) {
                    ImportFeedback.failure("Invalid import text.");
                    return;
                }
                installDecodedDungeonRooms(decoded);
            })) {
                importInFlight = false;
                ImportFeedback.failure(Component.translatable("waypointer.codec.busy").getString());
            }
            return;
        }

        String defaultImportedRouteName = Component.translatable(
                "waypointer.import.default_route_name").getString();
        if (!CodecWorker.run(() -> WaypointImporter.importAny(text, defaultImportedRouteName), result -> {
            importInFlight = false;
            if (result == null) {
                ImportFeedback.failure("Invalid import text.");
                return;
            }
            retargetUnknownImportedGroups(result.groups(), targetZoneId);
            RouteColorPolicy.applyImportedRouteDefaults(result.groups(), config);
            manager.addAll(result.groups());

            ImportFeedback.success(result.groups(), "clipboard");
            if (!result.groups().isEmpty()) {
                WaypointGroup first = result.groups().get(0);
                searchQuery = "";
                if (searchBox != null) searchBox.setValue("");
                selectedZoneId = first.routeKind() == WaypointGroup.RouteKind.DUNGEON
                        ? DUNGEON_ROOMS_ZONE_ID : first.zoneId();
                selectGroupById(first.id());
            }
        })) {
            importInFlight = false;
            ImportFeedback.failure(Component.translatable("waypointer.codec.busy").getString());
        }
    }

    private void installDecodedDungeonRooms(DungeonRoomShareCodec.Decoded decoded) {
        List<WaypointGroup> routes = DungeonRoomRouteLibrary.installRoutes(manager, decoded.routes());
        if (routes.isEmpty()) {
            ImportFeedback.failure("Invalid import text.");
            return;
        }

        ImportFeedback.successDungeonRoutes(routes.size(),
                DungeonRoomShareCodec.waypointCount(routes), "clipboard");
        searchQuery = "";
        if (searchBox != null) searchBox.setValue("");
        selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        if (!routes.isEmpty()) {
            expandedDungeonRoomZoneIds.add(routes.get(0).zoneId());
        }
        clearRouteSelection();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
