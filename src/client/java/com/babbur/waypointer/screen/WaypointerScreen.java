package com.babbur.waypointer.screen;

import com.babbur.waypointer.i18n.Counts;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.color.RouteColorPolicy;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.debug.ConfigChangeHistory;
import com.babbur.waypointer.crystal.CrystalHollowsProjection;
import com.babbur.waypointer.crystal.CrystalHollowsShare;
import com.babbur.waypointer.crystal.CrystalHollowsStructureFolder;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static com.babbur.waypointer.screen.WaypointerZoneCatalog.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MENU;

public final class WaypointerScreen extends Screen {

    static final int TEMPORARY_ACCENT = 0xFF58C878;
    static final int DUNGEON_ROOM_ACCENT = 0xFFFF8A8A;

    final ActiveGroupManager manager;
    final WaypointerConfig config;
    RoutePickerHost publishDraft;
    private Button addToPublishButton;
    private Button removeFromPublishButton;
    String selectedZoneId;
    String selectedDungeonRoomZoneId;
    int scrollOffset;
    String selectedGroupId;
    final LinkedHashSet<String> selectedGroupIds = new LinkedHashSet<>();
    private final WaypointerRouteList routeList;
    private String selectionAnchorGroupId;
    private String selectedFolderId;
    private String pendingFocusGroupId;
    private String pendingFocusRoomZoneId;
    private String lastObservedCurrentRoomZoneId;

    private static final long CONFIRM_WINDOW_MS = 2500L;
    private static final int SEARCH_CLEAR_BTN_W = 52;
    private static final int TOOL_ICON_BTN_W = 24;
    private static final int SEARCH_MIN_COMFORT_W = 120;
    private static final int BROWSE_BTN_W = 96;
    private static final int MOVE_ZONE_BTN_W = 92;
    private static final long MAIN_NOTICE_MS = 2500L;
    private static final int NEW_BTN_W = 64;
    private static final int HIDE_ALL_ROUTES_BTN_W = 80;
    private static final int IMPORT_EXPORT_BTN_W = 116;
    private static final int DELETE_BTN_W = 72;
    private static final int DONE_BTN_W = 56;
    private static final int REORDER_BTN_W = 28;
    private static final int SETTINGS_BTN_W = 76;
    private static final int ISLAND_DROPDOWN_W = 210;
    private static final int ISLAND_SELECTOR_W = ISLAND_DROPDOWN_W;

    private static final int HEADER_BTN_Y = PAD_OUTER - 6;
    private OverlayButton hideAllRoutesBtn;
    private Button deleteBtn;
    private Button importExportBtn;
    private Button newBtn;
    private Button moveUpBtn;
    private Button moveDownBtn;
    private Button islandSelectorBtn;
    private Button restoreStructuresBtn;
    private EditBox searchBox;
    private OverlayButton clearSearchButton;
    private OverlayButton browseButton;
    private OverlayButton moveZoneButton;
    private Button settingsButton;
    private EditBox zoneSearchBox;
    private String zoneSearchQuery = "";
    // The click that opens the list focuses the zone button afterwards; focus the search then.
    private boolean zoneSearchFocusPending;
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
    private final ContextMenuOverlay contextMenu = new ContextMenuOverlay();
    private boolean showAllMode;

    private enum DropdownTab { ISLANDS, DUNGEONS }

    private List<GuiTokens.ButtonSpec> footerActions() {
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        if (publishDraft != null) {
            left.add(new GuiTokens.ButtonSpec(
                    Component.translatable("waypointer.screen.route_publish.action.add_selected").getString(),
                    104, () -> {
                        publishDraft.addRoutes(selectedPublishGroups());
                        refreshActionButtons();
                    }));
            left.add(new GuiTokens.ButtonSpec(
                    Component.translatable("waypointer.screen.route_publish.action.remove_selected").getString(),
                    112, () -> {
                        publishDraft.removeRoutes(selectedPublishGroups());
                        refreshActionButtons();
                    }));
            return left;
        }
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.main.import_export").getString(),
                -1,
                this::toggleImportExportMenu,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.main.import_export.tooltip"))).withIcon(Icons.Icon.SHARE));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.main.new").getString(),
                -1, this::toggleNewMenu,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.main.new.tooltip"))).withIcon(Icons.Icon.PLUS));
        left.add(new GuiTokens.ButtonSpec(
                "\u25b2", REORDER_BTN_W, () -> moveSelectedRouteBy(-1),
                Tooltip.create(Component.translatable("waypointer.screen.main.move_up.tooltip"))));
        left.add(new GuiTokens.ButtonSpec(
                "\u25bc", REORDER_BTN_W, () -> moveSelectedRouteBy(1),
                Tooltip.create(Component.translatable("waypointer.screen.main.move_down.tooltip"))));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.main.delete").getString(),
                -1, this::onDeleteClicked).withIcon(Icons.Icon.TRASH));
        return left;
    }

    static int footerRequiredWidth() {
        return PAD_OUTER
                + IMPORT_EXPORT_BTN_W + NEW_BTN_W
                + REORDER_BTN_W * 2 + DELETE_BTN_W
                + GAP * 4 + GAP_SECTION + DONE_BTN_W + PAD_OUTER;
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
        newBtn = null;
        moveUpBtn = null;
        moveDownBtn = null;
        islandSelectorBtn = null;
        searchBox = null;
        clearSearchButton = null;
        browseButton = null;
        moveZoneButton = null;
        settingsButton = null;
        zoneSearchBox = null;
        addToPublishButton = null;
        removeFromPublishButton = null;
        routeList.resetNavigation();
        islandDropdownOpen = false;
        zoneMoveGroupId = null;
        importExportMenuOpen = false;
        contextMenu.close();

        Layout layout = layout();
        List<GuiTokens.ButtonSpec> left = footerActions();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec(
                Component.translatable(publishDraft == null ? "gui.done"
                        : "waypointer.screen.route_publish.action.review").getString(),
                publishDraft == null ? DONE_BTN_W : 104, this::onClose);

        GuiTokens.layoutFooter(width, footerY, left, done,
                b -> {
            if (publishDraft != null) {
                if (Component.translatable("waypointer.screen.route_publish.action.add_selected")
                        .getString().equals(b.getMessage().getString())) addToPublishButton = b;
                if (Component.translatable("waypointer.screen.route_publish.action.remove_selected")
                        .getString().equals(b.getMessage().getString())) removeFromPublishButton = b;
            }
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
            if (Component.translatable("waypointer.screen.main.new").getString()
                    .contentEquals(b.getMessage().getString())) {
                newBtn = b;
            } else if ("\u25b2".contentEquals(b.getMessage().getString())) {
                moveUpBtn = b;
            } else if ("\u25bc".contentEquals(b.getMessage().getString())) {
                moveDownBtn = b;
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
        clearSearchButton.icon(Icons.Icon.CLOSE);
        clearSearchButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.search.clear.tooltip")));
        updateClearSearchButton();
        syncSearchBoxGeometry();
        addRenderableWidget(clearSearchButton);

        browseButton = new OverlayButton(0, 0, BROWSE_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.browse"),
                b -> RouteCatalogScreen.open(this));
        browseButton.icon(Icons.Icon.GLOBE);
        browseButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.browse.tooltip")));
        syncSearchBoxGeometry();
        addRenderableWidget(browseButton);

        moveZoneButton = new OverlayButton(0, 0, MOVE_ZONE_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.move_zone"),
                this::startZoneMoveForSelection);
        moveZoneButton.icon(Icons.Icon.EXPORT);
        moveZoneButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.move_zone.tooltip")));
        syncSearchBoxGeometry();
        addRenderableWidget(moveZoneButton);

        OverlayButton hideAll = new OverlayButton(0, 0, HIDE_ALL_ROUTES_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.hide_all"),
                b -> onHideAllRoutesClicked());
        hideAll.icon(Icons.Icon.EYE_OFF);
        hideAllRoutesBtn = hideAll;
        syncSearchBoxGeometry();
        addRenderableWidget(hideAll);

        settingsButton = new GuiTokens.StyledButton(PAD_OUTER, HEADER_BTN_Y,
                SETTINGS_BTN_W, BTN_H,
                Component.translatable("waypointer.screen.main.settings"), b -> openSettings());
        ((GuiTokens.StyledButton) settingsButton).icon(Icons.Icon.GEAR);
        addRenderableWidget(settingsButton);

        islandSelectorBtn = new GuiTokens.StyledButton(
                width - PAD_OUTER - ISLAND_SELECTOR_W, HEADER_BTN_Y,
                ISLAND_SELECTOR_W, BTN_H, islandSelectorLabel(), b -> toggleIslandDropdown());
        addRenderableWidget(islandSelectorBtn);

        Component restoreLabel = Component.translatable("waypointer.screen.main.restore_structures");
        int restoreW = font.width(restoreLabel) + 16;
        restoreStructuresBtn = new GuiTokens.StyledButton(
                islandSelectorBtn.getX() - GAP - restoreW, HEADER_BTN_Y, restoreW, BTN_H, restoreLabel,
                b -> restoreStructuresFolder());
        restoreStructuresBtn.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.main.restore_structures.tooltip")));
        restoreStructuresBtn.visible = restoreStructuresShown();
        addRenderableWidget(restoreStructuresBtn);

        // While the island list is open, the zone button turns into its search field.
        zoneSearchBox = new EditBox(font, 0, 0, ISLAND_SELECTOR_W - GAP - 18, 10,
                Component.translatable("waypointer.screen.main.zone_search"));
        zoneSearchBox.setBordered(false);
        zoneSearchBox.setMaxLength(40);
        zoneSearchBox.setValue(zoneSearchQuery);
        zoneSearchBox.setResponder(this::onZoneSearchChanged);
        zoneSearchBox.visible = zoneSearchShown();
        addWidget(zoneSearchBox);
        if (zoneSearchBox.visible) setFocused(zoneSearchBox);

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
        boolean showBrowseButton = browseButton != null && publishDraft == null;
        boolean showMoveZoneButton = Zone.PRIVATE_WORLD.id().equals(selectedZoneId);
        int fullTools = (clearSearchButton == null ? 0 : SEARCH_CLEAR_BTN_W + GAP_TIGHT)
                + (showBrowseButton ? BROWSE_BTN_W + GAP : 0)
                + (showMoveZoneButton ? MOVE_ZONE_BTN_W + GAP : 0)
                + (hideAllRoutesBtn == null ? 0 : HIDE_ALL_ROUTES_BTN_W + GAP);
        // Narrow windows fold the toolbar into icon buttons before squeezing the search box.
        boolean compact = availableWidth - fullTools < SEARCH_MIN_COMFORT_W;
        int clearW = compact ? TOOL_ICON_BTN_W : SEARCH_CLEAR_BTN_W;
        int browseW = compact ? TOOL_ICON_BTN_W : BROWSE_BTN_W;
        int moveZoneW = compact ? TOOL_ICON_BTN_W : MOVE_ZONE_BTN_W;
        int hideAllW = compact ? TOOL_ICON_BTN_W : HIDE_ALL_ROUTES_BTN_W;
        int clearWidth = clearSearchButton == null ? 0 : clearW + GAP_TIGHT;
        int browseWidth = showBrowseButton ? browseW + GAP : 0;
        int hideAllWidth = hideAllRoutesBtn == null ? 0 : hideAllW + GAP;
        int moveZoneWidth = showMoveZoneButton ? moveZoneW + GAP : 0;
        int searchWidth = Math.max(80,
                Math.min(180, availableWidth - clearWidth - browseWidth - moveZoneWidth - hideAllWidth));
        searchBox.setWidth(searchWidth);
        if (clearSearchButton != null) {
            clearSearchButton.setIconOnly(compact);
            clearSearchButton.setX(left + searchWidth + GAP_TIGHT);
            clearSearchButton.setY(toolbarY);
            clearSearchButton.setWidth(clearW);
        }
        int afterSearch = clearSearchButton == null
                ? left + searchWidth + GAP
                : clearSearchButton.getX() + clearW + GAP;
        if (browseButton != null) {
            browseButton.setIconOnly(compact);
            browseButton.visible = showBrowseButton;
            browseButton.setX(afterSearch);
            browseButton.setY(toolbarY);
            browseButton.setWidth(browseW);
        }
        int rightEdge = layout.mainRight() - GAP;
        if (hideAllRoutesBtn != null) {
            hideAllRoutesBtn.setIconOnly(compact);
            hideAllRoutesBtn.setX(rightEdge - hideAllW);
            hideAllRoutesBtn.setY(toolbarY);
            hideAllRoutesBtn.setWidth(hideAllW);
            rightEdge -= hideAllW + GAP;
        }
        if (moveZoneButton != null) {
            moveZoneButton.setIconOnly(compact);
            moveZoneButton.visible = showMoveZoneButton;
            moveZoneButton.setX(rightEdge - moveZoneW);
            moveZoneButton.setY(toolbarY);
            moveZoneButton.setWidth(moveZoneW);
            if (showMoveZoneButton) rightEdge -= moveZoneW + GAP;
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

    private void onZoneSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(zoneSearchQuery)) return;
        zoneSearchQuery = next;
        dropdownScrollOffset = 0;
    }

    private boolean zoneSearchShown() {
        return islandDropdownOpen && dropdownTab == DropdownTab.ISLANDS;
    }

    private boolean zoneSearchActive() {
        return zoneSearchShown() && !zoneSearchQuery.isBlank();
    }

    private void syncZoneSearchVisibility() {
        if (zoneSearchBox == null) return;
        zoneSearchBox.visible = zoneSearchShown();
        if (!zoneSearchBox.visible && getFocused() == zoneSearchBox) setFocused(null);
    }

    private void resetZoneSearch() {
        zoneSearchQuery = "";
        if (zoneSearchBox != null) zoneSearchBox.setValue("");
        syncZoneSearchVisibility();
        if (zoneSearchBox != null && zoneSearchBox.visible) {
            setFocused(zoneSearchBox);
            zoneSearchFocusPending = true;
        }
    }

    private void openIslandDropdown() {
        islandDropdownOpen = true;
        resetZoneSearch();
    }

    private void closeIslandDropdown() {
        islandDropdownOpen = false;
        syncZoneSearchVisibility();
    }

    private boolean showEmptyIslandsToggle() {
        return zoneMoveGroupId == null && dropdownTab == DropdownTab.ISLANDS
                && !zoneSearchActive() && canToggleEmptyIslands();
    }

    static boolean zoneMatchesSearch(String zoneId, String label, String query) {
        return zoneMatchesSearch(zoneId, label, searchTokens(query));
    }

    static String[] searchTokens(String query) {
        if (query == null || query.isBlank()) return new String[0];
        return query.trim().toLowerCase(Locale.ROOT).split(" +");
    }

    static boolean zoneMatchesSearch(String zoneId, String label, String[] tokens) {
        if (tokens.length == 0) return true;
        String haystack = ((label == null ? "" : label) + " " + (zoneId == null ? "" : zoneId)
                + " " + (zoneId == null ? "" : zoneId.replace('_', ' '))).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    private List<String> islandDropdownIds() {
        boolean searching = zoneSearchActive();
        List<String> ids = new ArrayList<>(islandDropdownIdsForManager(
                manager, showAllIslands || zoneMoveGroupId != null || searching,
                currentStructureZoneId()));
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
        if (searching) {
            String[] tokens = searchTokens(zoneSearchQuery);
            ids.removeIf(id -> !zoneMatchesSearch(id, displayZoneLabel(id), tokens));
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
            for (WaypointGroup group : manager.allGroups()) {
                if (group != null && !group.temp() && group.enabled()
                        && Zone.sharesRoutes(group.zoneId(), selectedZoneId)) {
                    out.add(group);
                }
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
        if (isDungeonRoomsZone(zoneId)) return dungeonSecretRoomCount(manager);
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
        if (restoreStructuresBtn != null) restoreStructuresBtn.visible = restoreStructuresShown();
        focusCurrentDungeonRoomIfChanged();
        if (zoneSearchFocusPending) {
            zoneSearchFocusPending = false;
            if (zoneSearchBox != null && zoneSearchBox.visible) setFocused(zoneSearchBox);
        }
        if (islandSelectorBtn != null) {
            islandSelectorBtn.setMessage(islandSelectorLabel());
        }
        boolean allowBackgroundHover = backgroundHoverAllowed(
                islandDropdownOpen, importExportMenuOpen) && !contextMenu.isOpen();
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

        if (islandSelectorBtn != null) renderIslandSelectorDirection(g);
        String headerTitle = publishDraft == null ? getTitle().getString()
                : Component.translatable("waypointer.screen.route_publish.selection_title",
                        Counts.of("route", publishDraft.routeCount())).getString();
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
            renderIslandDropdown(g, mouseX, mouseY, partial);
        }
        if (importExportMenuOpen) {
            renderImportExportMenu(g, mouseX, mouseY);
        }
        if (contextMenu.isOpen()) {
            contextMenu.render(g, font, mouseX, mouseY);
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
        return Component.literal(label);
    }

    private void renderIslandSelectorDirection(GuiGraphicsExtractor g) {
        GuiTokens.drawDirectionGlyph(g,
                islandDropdownOpen ? GuiTokens.Direction.UP : GuiTokens.Direction.DOWN,
                islandSelectorBtn.getX() + islandSelectorBtn.getWidth() - GAP - 5,
                islandSelectorBtn.getY() + islandSelectorBtn.getHeight() / 2,
                islandDropdownOpen ? ACCENT : TEXT_DIM);
    }

    private void renderSearchBox(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        syncSearchBoxGeometry();
        if (searchBox != null) {
            searchBox.extractWidgetRenderState(g, mouseX, mouseY, partial);
        }
        if (clearSearchButton != null) {
            clearSearchButton.extractOverlay(g, mouseX, mouseY, partial);
        }
        if (browseButton != null && browseButton.visible) {
            browseButton.extractOverlay(g, mouseX, mouseY, partial);
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
                Component.translatable(publishDraft == null ? "gui.done"
                        : "waypointer.screen.route_publish.action.review").getString(),
                publishDraft == null ? DONE_BTN_W : 104, this::onClose);
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
        String structureZoneId = generatedStructureZoneId(group);
        if (structureZoneId != null) {
            selectZoneFromDropdown(structureZoneId);
            return;
        }
        if (group == null || group.temp() || group.runtimeOnly()) return;
        MinecraftCompat.setScreen(minecraft, new GroupEditScreen(this, manager, config, group));
    }

    boolean addToPublishDraft(WaypointGroup group) {
        if (publishDraft == null) return false;
        if (!CatalogPublishFormModel.eligible(group) || !acceptsPublishDraft(group)) return false;
        publishDraft.addRoutes(List.of(group));
        MinecraftCompat.setScreen(minecraft, publishDraft.screen());
        return true;
    }

    boolean togglePublishDraft(WaypointGroup group) {
        if (publishDraft == null) return false;
        if (publishDraft.contains(group)) {
            if (publishDraft.routeCount() > 1) publishDraft.removeRoutes(List.of(group));
        } else if (CatalogPublishFormModel.eligible(group) && acceptsPublishDraft(group)) {
            publishDraft.addRoutes(List.of(group));
        }
        refreshActionButtons();
        return true;
    }

    boolean acceptsPublishDraft(WaypointGroup group) {
        return publishDraft == null || publishDraft.routeKind() == null
                || group.routeKind() == publishDraft.routeKind();
    }

    @Override
    public void onClose() {
        if (publishDraft != null) MinecraftCompat.setScreen(minecraft, publishDraft.screen());
        else super.onClose();
    }

    record Layout(int top, int bottom, int mainLeft, int mainRight) {}

    private void syncHideAllIcon() {
        if (hideAllRoutesBtn instanceof GuiTokens.StyledButton styled) {
            styled.icon(showAllMode ? Icons.Icon.EYE : Icons.Icon.EYE_OFF);
        }
    }

    private static final class OverlayButton extends GuiTokens.StyledButton {
        private OverlayButton(int x, int y, int width, int height,
                              Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress);
        }

        private void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            extractWidgetRenderState(g, mouseX, mouseY, partial);
        }

    }

    private record DropdownGeometry(int x1, int y1, int x2, int y2,
                                    int tabsTop, int tabsBottom,
                                    int rowsTop, int rowsBottom) {}

    private DropdownGeometry islandDropdownGeometry(int rowCount) {
        int x2 = width - PAD_OUTER;
        int x1 = x2 - ISLAND_DROPDOWN_W;
        int y1 = HEADER_BTN_Y + BTN_H + GAP_TIGHT;
        int searchHeight = 0;
        int maxBottom = Math.max(y1 + BTN_H + searchHeight + ROW_H + 3, layout().bottom());
        int tabsTop = y1 + 1;
        int tabsBottom = tabsTop + BTN_H;
        int rowsTop = tabsBottom + 1 + searchHeight;
        int availableRows = Math.max(1, (maxBottom - rowsTop - 1) / ROW_H);
        int visibleRows = Math.max(1, Math.min(rowCount, availableRows));
        int y2 = rowsTop + visibleRows * ROW_H + 1;
        return new DropdownGeometry(x1, y1, x2, y2,
                tabsTop, tabsBottom, rowsTop, y2 - 1);
    }

    private void toggleIslandDropdown() {
        zoneMoveGroupId = null;
        importExportMenuOpen = false;
        if (islandDropdownOpen) {
            closeIslandDropdown();
            return;
        }
        dropdownTab = isDungeonRoomsZone(selectedZoneId)
                ? DropdownTab.DUNGEONS : DropdownTab.ISLANDS;
        showAllIslands = false;
        openIslandDropdown();
        dropdownScrollOffset = dropdownScrollForSelectedZone();
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

    private void renderIslandDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        List<String> ids = islandDropdownIds();
        boolean showToggle = showEmptyIslandsToggle();
        int rowCount = dropdownTab == DropdownTab.DUNGEONS ? 1 : ids.size() + (showToggle ? 1 : 0);
        DropdownGeometry geo = islandDropdownGeometry(rowCount);
        fillOutlinedOverlay(g, geo.x1(), geo.y1(), geo.x2(), geo.y2());
        renderDropdownTabs(g, geo, mouseX, mouseY);

        if (dropdownTab == DropdownTab.DUNGEONS) {
            String label = Component.translatable(
                    "waypointer.screen.main.dungeons.all_rooms").getString();
            int textX = geo.x1() + (geo.x2() - geo.x1() - font.width(label)) / 2;
            g.text(font, label, textX, geo.rowsTop() + 7,
                    DUNGEON_ROOM_ACCENT, false);
            return;
        }
        renderZoneSearch(g, geo, mouseX, mouseY, partial);
        if (ids.isEmpty() && zoneSearchActive()) {
            String label = Component.translatable(
                    "waypointer.screen.main.zone_search.empty").getString();
            int textX = geo.x1() + (geo.x2() - geo.x1() - font.width(label)) / 2;
            g.text(font, label, textX, geo.rowsTop() + 7, TEXT_MUTED, false);
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
            // The last row sits on the outline (or on Show more's own edge), so it needs no line.
            boolean last = i == ids.size() - 1;
            drawDropdownZoneRow(g, geo.x1() + 1, rowY, geo.x2() - 1, id, hovered, currentId, !last);
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
                g.fill(geo.x1() + 1, rowY, geo.x2() - 1, rowY + 1, BORDER);
                String label = Component.translatable(showAllIslands
                        ? "waypointer.screen.main.show_less"
                        : "waypointer.screen.main.show_more").getString();
                int textX = geo.x1() + (geo.x2() - geo.x1() - font.width(label)) / 2;
                g.text(font, label, textX, rowY + 7,
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

    /** Draws the search field over the zone button, keeping the button's arrow to close the list. */
    private void renderZoneSearch(GuiGraphicsExtractor g, DropdownGeometry geo,
                                  int mouseX, int mouseY, float partial) {
        if (zoneSearchBox == null || !zoneSearchShown() || islandSelectorBtn == null) return;
        int x1 = islandSelectorBtn.getX(), y1 = islandSelectorBtn.getY();
        int x2 = x1 + islandSelectorBtn.getWidth(), y2 = y1 + islandSelectorBtn.getHeight();
        g.fill(x1, y1, x2, y2, OVERLAY);
        g.fill(x1, y1, x2, y1 + 1, ACCENT);
        g.fill(x1, y2 - 1, x2, y2, ACCENT);
        g.fill(x1, y1, x1 + 1, y2, ACCENT);
        g.fill(x2 - 1, y1, x2, y2, ACCENT);
        // Text sorts above fills in a stratum; a new one keeps the zone name from showing through.
        g.nextStratum();
        zoneSearchBox.setX(x1 + GAP);
        zoneSearchBox.setY(y1 + (BTN_H - 8) / 2);
        zoneSearchBox.setWidth(x2 - x1 - GAP - 18);
        zoneSearchBox.extractWidgetRenderState(g, mouseX, mouseY, partial);
        renderIslandSelectorDirection(g);
    }

    private boolean overZoneSearch(double mx, double my) {
        return zoneSearchShown() && islandSelectorBtn != null
                && mx >= islandSelectorBtn.getX() && mx < islandSelectorBtn.getX() + islandSelectorBtn.getWidth() - 16
                && my >= islandSelectorBtn.getY() && my < islandSelectorBtn.getY() + islandSelectorBtn.getHeight();
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
        g.fill(split, geo.tabsTop(), split + 1, geo.tabsBottom() - 1, BORDER);
        g.fill(geo.x1() + 1, geo.tabsBottom(), geo.x2() - 1, geo.tabsBottom() + 1, BORDER);
        // The selected tab's underline runs the full width of its column, divider included.
        if (dropdownTab == DropdownTab.ISLANDS) {
            g.fill(geo.x1() + 1, geo.tabsBottom() - 1, split + 1, geo.tabsBottom(), ACCENT);
        } else {
            g.fill(split, geo.tabsBottom() - 1, geo.x2() - 1, geo.tabsBottom(), ACCENT);
        }
    }

    private void drawDropdownTab(GuiGraphicsExtractor g, int x1, int x2, int y1, int y2,
                                 String label, boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
        if (selected || hovered) g.fill(x1, y1, x2, y2, selected ? SELECTED : HOVER);
        int textX = x1 + (x2 - x1 - font.width(label)) / 2;
        g.text(font, label, textX, y1 + 6, selected || hovered ? TEXT : TEXT_DIM, false);
        if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void drawDropdownZoneRow(GuiGraphicsExtractor g, int x1, int y, int x2,
                                     String zoneId, boolean hovered, String currentId, boolean divider) {
        boolean selected = zoneId.equals(selectedZoneId);
        boolean temporary = isTemporaryZone(zoneId);
        boolean dungeonParent = isDungeonRoomsZone(zoneId);
        boolean currentStructure = zoneId.equals(currentStructureZoneId());
        boolean isCurrent = currentStructure || !temporary
                && (zoneId.equals(currentId) || (dungeonParent && isDungeonRoomZone(currentId)));
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);

        boolean isUnknown = Zone.UNKNOWN.id().equals(zoneId);
        int accent = temporary ? TEMPORARY_ACCENT
                : dungeonParent ? DUNGEON_ROOM_ACCENT
                : ACCENT;
        if (selected && (!isUnknown || temporary)) {
            g.fill(x1, y + ROW_H - 2, x2, y + ROW_H, accent);
        }

        String label = displayZoneLabel(zoneId);
        int count = temporary ? temporaryWaypointCount() : normalGroupCountForZone(zoneId);
        int textColor = isUnknown && !temporary ? TEXT_MUTED : selected ? TEXT : TEXT_DIM;
        boolean emptyZone = count == 0;
        if (emptyZone && !selected && !isCurrent) textColor = TEXT_MUTED;

        String countStr = Integer.toString(count);
        int countX = (isCurrent ? x2 - GAP - 12 : x2 - GAP) - font.width(countStr);
        int labelX = x1 + GAP;
        int labelMaxW = Math.max(12, countX - GAP_TIGHT - labelX);
        g.text(font, font.plainSubstrByWidth(label, labelMaxW), labelX, y + 7, textColor, false);

        if (isCurrent) {
            int dotX = x2 - GAP - 6;
            g.fill(dotX, y + ROW_H / 2 - 2, dotX + 4, y + ROW_H / 2 + 2,
                    currentStructure ? 0xFF5599FF : accent);
        }
        g.text(font, countStr, countX, y + 7, TEXT_MUTED, false);
        if (divider) g.fill(x1, y + ROW_H - 1, x2, y + ROW_H, BORDER);
    }

    static int maxDropdownScroll(int zoneCount, int viewportHeight) {
        return Math.max(0, zoneCount * ROW_H - Math.max(0, viewportHeight));
    }

    /** Offered while the Structures folder is hidden and a Crystal Hollows zone is on screen. */
    private boolean restoreStructuresShown() {
        // Only where it clears the centred title; the folder menu and settings still offer it.
        boolean fits = restoreStructuresBtn == null || restoreStructuresBtn.getX() > width / 2 + 60;
        return fits && config.crystalHollowsHideStructuresFolder() && selectedZoneId != null
                && ("crystal_hollows".equals(selectedZoneId)
                    || com.babbur.waypointer.crystal.CrystalHollowsStructureZones.isStructureZone(selectedZoneId));
    }

    private void restoreStructuresFolder() {
        config.setCrystalHollowsHideStructuresFolder(false);
        if (WaypointerClient.crystalHollowsTracker() != null) {
            WaypointerClient.crystalHollowsTracker().rebuildProjection();
        }
        if (restoreStructuresBtn != null) restoreStructuresBtn.visible = false;
    }

    private String currentStructureZoneId() {
        var tracker = WaypointerClient.crystalHollowsTracker();
        if (!"crystal_hollows".equals(currentZoneId(manager))
                || tracker == null || !tracker.active()) return null;
        return structureMenuZoneId(tracker.sidebarStructure());
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

    private boolean handleIslandDropdownClick(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        List<String> ids = islandDropdownIds();
        boolean showToggle = showEmptyIslandsToggle();
        int rowCount = dropdownTab == DropdownTab.DUNGEONS ? 1 : ids.size() + (showToggle ? 1 : 0);
        DropdownGeometry geo = islandDropdownGeometry(rowCount);
        if (overZoneSearch(mx, my)) {
            setFocused(zoneSearchBox);
            zoneSearchBox.mouseClicked(event, doubleClick);
            return true;
        }
        boolean inside = mx >= geo.x1() && mx < geo.x2() && my >= geo.y1() && my < geo.y2();
        if (!inside) {

            closeIslandDropdown();
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
            syncZoneSearchVisibility();
            if (zoneSearchBox != null && zoneSearchBox.visible) setFocused(zoneSearchBox);
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
        closeIslandDropdown();
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
                Math.max(font.width(importLabel), font.width(exportLabel)) + GAP * 2) + 2;
        int h = ROW_H * 2 + 2;
        int x1 = importExportBtn == null ? PAD_OUTER : importExportBtn.getX();
        int y2 = importExportBtn == null ? height - FOOTER_H : importExportBtn.getY() - GAP_TIGHT;
        x1 = Math.max(PAD_OUTER, Math.min(x1, width - PAD_OUTER - w));
        return new MenuGeometry(x1, y2 - h, x1 + w, y2);
    }

    private void toggleImportExportMenu() {
        importExportMenuOpen = !importExportMenuOpen;
        closeIslandDropdown();
        zoneMoveGroupId = null;
        contextMenu.close();
    }

    private void toggleNewMenu() {
        if (isStructureBrowseZone(selectedZoneId)) return;
        closeIslandDropdown();
        importExportMenuOpen = false;
        zoneMoveGroupId = null;
        if (contextMenu.isOpen()) {
            contextMenu.close();
            return;
        }
        boolean regularZone = !isTemporaryZone(selectedZoneId)
                && !isDungeonRoomsZone(selectedZoneId);
        List<ContextMenuOverlay.Item> items = new ArrayList<>();
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.main.new_route"),
                this::createGroup));
        items.add(regularZone
                ? ContextMenuOverlay.Item.of(
                        Component.translatable("waypointer.screen.main.folder"),
                        this::openNewFolderEditor)
                : ContextMenuOverlay.Item.disabled(
                        Component.translatable("waypointer.screen.main.folder"), null));
        if (minecraft != null && minecraft.player != null) {
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable("waypointer.screen.main.new_temp"),
                    () -> AddTempScreen.open(this, manager, config)));
        }
        int anchorX = newBtn == null ? PAD_OUTER : newBtn.getX();
        int anchorBottom = newBtn == null ? height - FOOTER_H : newBtn.getY() - GAP_TIGHT;
        contextMenu.openAbove(font, ContextMenuOverlay.withIcons(items, NEW_MENU_ICONS), anchorX, anchorBottom,
                PAD_OUTER, PAD_OUTER, width - PAD_OUTER);
    }

    void openRouteRowMenu(WaypointGroup group, double anchorX, double anchorY) {
        if (group == null) return;
        closeIslandDropdown();
        importExportMenuOpen = false;
        List<ContextMenuOverlay.Item> items = new ArrayList<>();
        if (group.runtimeOnly()) {
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable(group.enabled()
                            ? "waypointer.screen.main.menu.hide"
                            : "waypointer.screen.main.menu.show"),
                    ContextMenuOverlay.hint("space"), () -> toggleRouteEnabled(group)));
            String structureReference = CrystalHollowsProjection.structureReferenceForGroup(group.id());
            if (structureReference != null) {
                items.add(ContextMenuOverlay.Item.of(
                        Component.translatable("waypointer.crystal.action.share"),
                        null, () -> CrystalHollowsShare.send(
                                WaypointerClient.crystalHollowsTracker(), structureReference)));
            }
            openContextMenuAt(items, anchorX, anchorY);
            return;
        }
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.main.menu.edit"),
                ContextMenuOverlay.hint("double_click"), () -> openGroupEditor(group)));
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.main.menu.icon"),
                null, () -> RouteIconPickerScreen.open(this, group.icon(), group.zoneId(), spec -> {
                    group.setIcon(spec);
                    manager.noteRouteEdited(group);
                    manager.fireDataChanged();
                })).withIcon(Icons.Icon.TAG));
        AppearanceTarget look = AppearanceTarget.route(manager, config, group, manager::fireDataChanged);
        if (look != null) {
            int lookCount = look.scope().count();
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable("waypointer.screen.main.menu.appearance"),
                    lookCount == 0 ? null
                            : Component.translatable("waypointer.screen.appearance.set_count", lookCount).getString(),
                    () -> MinecraftCompat.setScreen(minecraft, SettingsScreen.forAppearance(
                            this, config, WaypointerClient.dungeonConfig(), look))));
        }
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable(group.enabled()
                        ? "waypointer.screen.main.menu.hide"
                        : "waypointer.screen.main.menu.show"),
                ContextMenuOverlay.hint("space"), () -> toggleRouteEnabled(group)));
        items.add(canMoveRouteZone(group)
                ? ContextMenuOverlay.Item.of(
                        Component.translatable("waypointer.screen.main.menu.move_zone"),
                        ContextMenuOverlay.hint("shift_right_click"), () -> startZoneMove(group))
                : ContextMenuOverlay.Item.disabled(
                        Component.translatable("waypointer.screen.main.menu.move_zone"),
                        ContextMenuOverlay.hint("shift_right_click")));
        int selectionSize = selectedGroupIds.contains(group.id()) ? selectedGroupIds.size() : 1;
        items.add(ContextMenuOverlay.Item.of(
                selectionSize > 1
                        ? Component.translatable(
                                "waypointer.screen.main.menu.delete.many", selectionSize)
                        : Component.translatable("waypointer.screen.main.menu.delete.one"),
                ContextMenuOverlay.hint("delete"), this::onDeleteClicked));
        openContextMenuAt(items, anchorX, anchorY);
    }

    void openFolderRowMenu(RouteFolder folder, double anchorX, double anchorY) {
        if (folder == null) return;
        closeIslandDropdown();
        importExportMenuOpen = false;
        List<ContextMenuOverlay.Item> items = new ArrayList<>();
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.main.folder.select"),
                null, () -> selectFolderRoutes(folder)));
        if (folder.runtimeOnly()
                && CrystalHollowsStructureFolder.FOLDER_ID.equals(folder.id())) {
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable(config.crystalHollowsHideStructuresFolder()
                            ? "waypointer.screen.main.menu.show"
                            : "waypointer.screen.main.menu.hide"),
                    null, () -> {
                        config.setCrystalHollowsHideStructuresFolder(
                                !config.crystalHollowsHideStructuresFolder());
                        if (WaypointerClient.crystalHollowsTracker() != null) {
                            WaypointerClient.crystalHollowsTracker().rebuildProjection();
                        }
                        clearRouteSelection();
                    }));
        }
        if (!folder.runtimeOnly()) {
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable("waypointer.screen.main.folder.edit"),
                    null, () -> openFolderEditor(folder, List.of())));
        }
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable(folder.collapsed()
                        ? "waypointer.screen.main.menu.expand"
                        : "waypointer.screen.main.menu.collapse"),
                ContextMenuOverlay.hint("space"), () -> routeList.toggleFolderCollapsed(folder)));
        openContextMenuAt(items, anchorX, anchorY);
    }

    private static final Map<String, Icons.Icon> NEW_MENU_ICONS = Map.of(
            "waypointer.screen.main.new_route", Icons.Icon.ROUTE,
            "waypointer.screen.main.folder", Icons.Icon.FOLDER,
            "waypointer.screen.main.new_temp", Icons.Icon.CLOCK);

    private static final Map<String, Icons.Icon> ROUTE_MENU_ICONS = Map.ofEntries(
            Map.entry("waypointer.screen.main.menu.edit", Icons.Icon.PENCIL),
            Map.entry("waypointer.screen.main.menu.appearance", Icons.Icon.BRUSH),
            Map.entry("waypointer.screen.main.menu.hide", Icons.Icon.EYE_OFF),
            Map.entry("waypointer.screen.main.menu.show", Icons.Icon.EYE),
            Map.entry("waypointer.screen.main.menu.move_zone", Icons.Icon.EXPORT),
            Map.entry("waypointer.screen.main.menu.delete.one", Icons.Icon.TRASH),
            Map.entry("waypointer.screen.main.menu.delete.many", Icons.Icon.TRASH),
            Map.entry("waypointer.crystal.action.share", Icons.Icon.SHARE),
            Map.entry("waypointer.screen.main.folder.select", Icons.Icon.CHECK),
            Map.entry("waypointer.screen.main.folder.edit", Icons.Icon.PENCIL),
            Map.entry("waypointer.screen.main.menu.expand", Icons.Icon.FOLDER_OPEN),
            Map.entry("waypointer.screen.main.menu.collapse", Icons.Icon.FOLDER));

    private void openContextMenuAt(List<ContextMenuOverlay.Item> items,
                                   double anchorX, double anchorY) {
        Layout layout = layout();
        contextMenu.openAt(font, ContextMenuOverlay.withIcons(items, ROUTE_MENU_ICONS), anchorX, anchorY,
                PAD_OUTER, PAD_OUTER, width - PAD_OUTER, layout.bottom());
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
            g.text(font, labels[i], geo.x1() + GAP, rowY + 7, hovered ? TEXT : TEXT_DIM, false);
        }
    }

    private boolean handleImportExportMenuClick(double mx, double my) {
        MenuGeometry geo = importExportMenuGeometry();
        importExportMenuOpen = false;
        boolean inside = mx >= geo.x1() && mx < geo.x2() && my >= geo.y1() && my < geo.y2();
        if (!inside) return true;
        int idx = (int) ((my - geo.y1() - 1) / ROW_H);
        if (idx == 0) {
            importFromClipboard();
        } else if (idx == 1) {
            exportZone();
        }
        return true;
    }

    private List<WaypointGroup> selectedPublishGroups() {
        return logicalSelectedGroups(manager, selectedZoneId, selectedGroupIds);
    }

    void fillOutlinedOverlay(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        GuiTokens.drawTooltipPanel(g, x1, y1, x2, y2);
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
        flashMainNotice(notice, MAIN_NOTICE_MS);
    }

    private void flashMainNotice(String notice, long durationMs) {
        mainNotice = notice == null ? "" : notice;
        mainNoticeUntil = System.currentTimeMillis() + durationMs;
    }

    private void flashMainNotice(Component notice) {
        flashMainNotice(notice == null ? "" : notice.getString());
    }

    private void flashMainNotice(Component notice, long durationMs) {
        flashMainNotice(notice == null ? "" : notice.getString(), durationMs);
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
        if (!group.temp()) {
            return name.isEmpty()
                    ? Component.translatableWithFallback(
                            "waypointer.screen.main.route.unnamed", "(unnamed)").getString()
                    : name;
        }
        if (name.isEmpty() || name.startsWith("Temp --")) return displayZoneLabel(TEMPORARY_ZONE_ID);
        return name;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {

        if (contextMenu.isOpen() && contextMenu.mouseClicked(event.x(), event.y())) return true;
        if (islandDropdownOpen && handleIslandDropdownClick(event, doubleClick)) return true;
        if (importExportMenuOpen && handleImportExportMenuClick(event.x(), event.y())) return true;
        if (super.mouseClicked(event, doubleClick)) return true;
        Layout layout = layout();
        return routeList.mouseClicked(event, doubleClick,
                layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        Layout layout = layout();
        if (routeList.mouseDragged(event,
                layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (routeList.mouseReleased(event)) return true;
        return super.mouseReleased(event);
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
                || InputConstants.isKeyDown(window, 344  );
    }

    static boolean routeSelectionControlDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, 341  )
                || InputConstants.isKeyDown(window, 345  );
    }

    private void replaceRouteSelection(Set<String> nextSelectionIds) {
        replaceRouteSelection(nextSelectionIds, null);
    }

    private void replaceRouteSelection(Set<String> nextSelectionIds, String folderId) {
        selectedGroupIds.clear();
        if (nextSelectionIds != null) {
            for (String id : nextSelectionIds) {
                if (id != null) selectedGroupIds.add(id);
            }
        }
        selectedFolderId = folderId;
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

    private void selectAllMatchingRoutes() {
        replaceRouteSelection(selectableRouteIds(routeList.visibleGroups()));
        selectionAnchorGroupId = selectedGroupId;
        syncAuthoringRouteFocus();
        refreshActionButtons();
    }

    static Set<String> selectableRouteIds(Iterable<WaypointGroup> matchingRoutes) {
        Set<String> ids = new LinkedHashSet<>();
        for (WaypointGroup route : matchingRoutes) {
            if (route != null && !route.runtimeOnly()) ids.add(route.id());
        }
        return ids;
    }

    void selectFolderRoutes(RouteFolder folder) {
        if (folder == null) return;
        LinkedHashSet<String> folderRoutes = new LinkedHashSet<>();
        for (String groupId : manager.groupIdsInFolder(folder.id())) {
            WaypointGroup group = manager.get(groupId);
            if (group != null && !group.temp()
                    && (!group.runtimeOnly() || folder.runtimeOnly())) {
                folderRoutes.add(groupId);
            }
        }
        replaceRouteSelection(folderRoutes, folder.id());
        selectionAnchorGroupId = folderRoutes.isEmpty() ? null : folderRoutes.iterator().next();
        syncAuthoringRouteFocus();
        refreshActionButtons();
    }

    void clearRouteSelection() {
        selectedGroupIds.clear();
        selectedGroupId = null;
        selectionAnchorGroupId = null;
        selectedFolderId = null;
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
        if (contextMenu.isOpen()) {
            contextMenu.close();
            return true;
        }
        if (islandDropdownOpen) {
            List<String> ids = islandDropdownIds();
            boolean showToggle = showEmptyIslandsToggle();
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
        if (contextMenu.keyPressed(event.key())) return true;
        if (event.key() == GLFW_KEY_ESCAPE && (islandDropdownOpen || importExportMenuOpen)) {
            closeIslandDropdown();
            importExportMenuOpen = false;
            zoneMoveGroupId = null;
            return true;
        }
        if (zoneSearchShown() && zoneSearchBox != null && getFocused() == zoneSearchBox) {
            if (event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER) {
                List<String> matches = islandDropdownIds();
                if (zoneSearchActive() && !matches.isEmpty()) selectZoneFromDropdown(matches.get(0));
                return true;
            }

            super.keyPressed(event);
            return true;
        }
        if (super.keyPressed(event)) return true;
        if (event.key() == GLFW_KEY_A && routeSelectionControlDown()
                && !islandDropdownOpen && !importExportMenuOpen) {
            selectAllMatchingRoutes();
            return true;
        }
        if (event.key() == GLFW_KEY_MENU && routeList.openContextMenuAtCursor()) return true;
        if (event.key() == GLFW_KEY_DELETE && !islandDropdownOpen && !importExportMenuOpen) {
            onDeleteClicked();
            return true;
        }
        return false;
    }

    private void createGroup() {
        if (isStructureBrowseZone(selectedZoneId)) return;
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
        }
        selectOnlyGroupId(g.id());
        MinecraftCompat.setScreen(minecraft, new GroupEditScreen(this, manager, config, g));
    }

    private List<WaypointGroup> hiddenRoutesForSelectedZone() {
        List<WaypointGroup> out = new ArrayList<>();
        if (isTemporaryZone(selectedZoneId)) return out;

        if (isDungeonRoomsZone(selectedZoneId)) {
            for (WaypointGroup group : manager.allGroups()) {
                if (group != null && !group.temp() && !group.enabled()
                        && group.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
                    out.add(group);
                }
            }
        } else {
            for (WaypointGroup group : manager.groupsForZone(selectedZoneId)) {
                if (group != null && !group.temp() && !group.enabled()) out.add(group);
            }
        }
        return out;
    }

    private void showAllHiddenRoutes() {
        List<WaypointGroup> hiddenRoutes = hiddenRoutesForSelectedZone();
        if (hiddenRoutes.isEmpty()) {
            refreshActionButtons();
            return;
        }
        if (isDungeonRoomsZone(selectedZoneId)) {
            DungeonRoomRouteSync.batched(() -> {
                for (WaypointGroup group : hiddenRoutes) {
                    DungeonRoomRouteLibrary.setRouteEnabled(
                            manager, WaypointerClient.dungeonConfig(), group, true);
                }
            });
        } else {
            for (WaypointGroup group : hiddenRoutes) {
                group.setEnabled(true);
            }
        }
        manager.fireDataChangedFor(persistenceTargets(hiddenRoutes));
        refreshActionButtons();
    }

    void toggleRouteEnabled(WaypointGroup group) {
        if (group == null) return;
        toggleRouteEnabled(manager, group);
        refreshActionButtons();
    }

    static void toggleRouteEnabled(ActiveGroupManager manager, WaypointGroup group) {
        if (manager == null || group == null) return;
        DungeonRoomRouteLibrary.setRouteEnabled(
                manager, WaypointerClient.dungeonConfig(), group, !group.enabled());
        manager.fireDataChangedFor(
                DungeonRoomRouteLibrary.durableEditTarget(manager, group));
    }

    private void onHideAllRoutesClicked() {
        if (showAllMode) {
            showAllHiddenRoutes();
            return;
        }
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
                hideAllRoutesBtn.setMessage(GuiTokens.colored(Component.translatable("waypointer.common.confirm"), GuiTokens.DANGER));
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
        manager.fireDataChangedFor(persistenceTargets(shownRoutes));
        refreshActionButtons();
    }

    private List<WaypointGroup> persistenceTargets(List<WaypointGroup> groups) {
        if (groups == null || groups.isEmpty()) return List.of();
        List<WaypointGroup> targets = new ArrayList<>(groups.size());
        for (WaypointGroup group : groups) {
            targets.add(DungeonRoomRouteLibrary.durableEditTarget(manager, group));
        }
        return targets;
    }

    void refreshActionButtons() {
        boolean browseOnly = isStructureBrowseZone(selectedZoneId);
        if (newBtn != null) newBtn.active = !browseOnly;
        if (importExportBtn != null) importExportBtn.active = !browseOnly;
        if (browseButton != null) browseButton.active = !browseOnly;
        if (publishDraft != null) {
            List<WaypointGroup> selectedForPublish = selectedPublishGroups();
            RoutePickerHost draft = publishDraft;
            if (addToPublishButton != null) {
                boolean compatible = selectedForPublish.stream().allMatch(draft::accepts);
                boolean mixed = draft.routeKind() != null && selectedForPublish.stream()
                        .anyMatch(group -> group.routeKind() != draft.routeKind());
                addToPublishButton.active = compatible && selectedForPublish.stream()
                        .anyMatch(group -> !draft.contains(group));
                addToPublishButton.setTooltip(mixed ? Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.validation.mixed_routes")) : null);
            }
            if (removeFromPublishButton != null) {
                long selectedCount = selectedForPublish.stream().filter(draft::contains).count();
                removeFromPublishButton.active = selectedCount > 0 && selectedCount < draft.routeCount();
            }
        }
        if (hideAllRoutesBtn != null) {
            boolean hasShownRoutes = !shownRoutesForSelectedZone().isEmpty();
            boolean hasHiddenRoutes = !hiddenRoutesForSelectedZone().isEmpty();
            showAllMode = !hasShownRoutes && hasHiddenRoutes;
            hideAllRoutesBtn.active = hasShownRoutes || hasHiddenRoutes;
            if (!hasShownRoutes) clearHideAllConfirmation();
            if (!hideAllConfirmation.isArmed(System.currentTimeMillis())) {
                hideAllRoutesBtn.setMessage(Component.translatable(showAllMode
                        ? "waypointer.screen.main.show_all"
                        : "waypointer.screen.main.hide_all"));
                syncHideAllIcon();
            }
        }
        if (moveZoneButton != null) {
            moveZoneButton.visible = Zone.PRIVATE_WORLD.id().equals(selectedZoneId);
            moveZoneButton.active = moveZoneButton.visible && selectedVisibleGroups().size() == 1;
        }
        List<WaypointGroup> selected = selectedVisibleGroups();
        ReorderActionState reorder = reorderActionState(
                manager, selectedZoneId, searchQuery, selected);
        if (moveUpBtn != null) {
            moveUpBtn.active = reorder.moveUp();
        }
        if (moveDownBtn != null) {
            moveDownBtn.active = reorder.moveDown();
        }
    }

    static ReorderActionState reorderActionState(
            ActiveGroupManager manager, String selectedZoneId, String searchQuery,
            List<WaypointGroup> selected) {
        if (manager == null || selected == null || selected.size() != 1
                || isTemporaryZone(selectedZoneId)
                || searchQuery != null && !searchQuery.isBlank()) {
            return ReorderActionState.DISABLED;
        }
        WaypointGroup group = selected.get(0);
        boolean correctView = group != null && (isDungeonRoomsZone(selectedZoneId)
                ? group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                : group.routeKind() == WaypointGroup.RouteKind.REGULAR
                && group.zoneId().equals(selectedZoneId));
        if (!correctView) return ReorderActionState.DISABLED;
        return new ReorderActionState(
                manager.canMoveGroupBy(group.id(), -1),
                manager.canMoveGroupBy(group.id(), 1));
    }

    record ReorderActionState(boolean moveUp, boolean moveDown) {
        private static final ReorderActionState DISABLED =
                new ReorderActionState(false, false);
    }

    private void openNewFolderEditor() {
        if (isStructureBrowseZone(selectedZoneId)) return;
        if (isTemporaryZone(selectedZoneId) || isDungeonRoomsZone(selectedZoneId)) return;
        openFolderEditor(null, List.copyOf(selectedGroupIds));
    }

    void openFolderEditor(RouteFolder folder, List<String> selectedIds) {
        if (folder != null && folder.runtimeOnly()) return;
        String zoneId = folder == null ? selectedZoneId : folder.zoneId();
        MinecraftCompat.setScreen(minecraft, new RouteFolderEditScreen(
                this, manager, zoneId, folder, selectedIds));
    }

    private void moveSelectedRouteBy(int delta) {
        if (!searchQuery.isBlank()) return;
        List<WaypointGroup> selected = selectedVisibleGroups();
        if (selected.size() != 1) return;
        WaypointGroup group = selected.get(0);
        if (!manager.moveGroupBy(group.id(), delta)) return;
        selectOnlyGroupId(group.id());
        refreshActionButtons();
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
        importExportMenuOpen = false;
        dropdownTab = DropdownTab.ISLANDS;
        showAllIslands = true;
        openIslandDropdown();
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
            deleteBtn.setMessage(GuiTokens.colored(Component.translatable("waypointer.common.confirm"), GuiTokens.DANGER));
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
        hideAllRoutesBtn.setMessage(Component.translatable(showAllMode
                ? "waypointer.screen.main.show_all"
                : "waypointer.screen.main.hide_all"));
        syncHideAllIcon();
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

        if (selectedFolderId != null) {
            RouteFolder folder = manager.folder(selectedFolderId);
            List<WaypointGroup> folderGroups = exportFolderGroups(folder);
            if (folder == null || folderGroups.isEmpty()) {
                flashMainNotice(emptyExportNotice(selectedZoneId));
                return;
            }
            ExportScreen.openForFolder(
                    this, config, manager, folder.name(), folderGroups);
            return;
        }

        List<WaypointGroup> selectedGroups = logicalSelectedGroups(
                manager, selectedZoneId, selectedGroupIds);
        if (!selectedGroupIds.isEmpty() && selectedGroups.isEmpty()) {
            flashMainNotice(emptyExportNotice(selectedZoneId));
            return;
        }
        if (selectedGroups.size() == 1) {
            ExportScreen.openForGroup(this, config, manager, selectedGroups.get(0));
            return;
        }
        if (selectedGroups.size() > 1) {
            ExportScreen.openForSelectedGroups(this, config, manager, selectedGroups);
            return;
        }

        List<WaypointGroup> groups = exportGroupsForSelection(
                selectedGroups, routeList.visibleGroups());
        if (groups.isEmpty()) {
            flashMainNotice(emptyExportNotice(selectedZoneId));
            return;
        }
        if (groups.size() == 1) {
            ExportScreen.openForGroup(this, config, manager, groups.getFirst());
            return;
        }
        String label = displayZoneLabel(selectedZoneId);
        ExportScreen.openForGroups(this, config, manager, groups, label);
    }

    private List<WaypointGroup> exportFolderGroups(RouteFolder folder) {
        return exportFolderGroups(manager, folder);
    }

    static List<WaypointGroup> exportFolderGroups(
            ActiveGroupManager manager, RouteFolder folder) {
        if (manager == null || folder == null) return List.of();
        List<WaypointGroup> groups = new ArrayList<>();
        for (String groupId : manager.groupIdsInFolder(folder.id())) {
            WaypointGroup group = manager.get(groupId);
            if (group != null && !group.temp() && !group.runtimeOnly()) groups.add(group);
        }
        return List.copyOf(groups);
    }

    static List<WaypointGroup> logicalSelectedGroups(
            ActiveGroupManager manager, String selectedZoneId, Set<String> selectedIds) {
        if (manager == null || selectedIds == null || selectedIds.isEmpty()) return List.of();
        List<WaypointGroup> groups = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group == null || group.temp() || group.runtimeOnly()
                    || !selectedIds.contains(group.id())) continue;
            boolean inZone = isDungeonRoomsZone(selectedZoneId)
                    ? group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    : Zone.sharesRoutes(group.zoneId(), selectedZoneId);
            if (inZone) groups.add(group);
        }
        return List.copyOf(groups);
    }

    private void exportDungeonRooms() {
        List<WaypointGroup> selectedGroups = logicalSelectedGroups(
                manager, selectedZoneId, selectedGroupIds);
        if (!selectedGroupIds.isEmpty() && selectedGroups.isEmpty()) {
            flashMainNotice(emptyExportNotice(selectedZoneId));
            return;
        }
        List<WaypointGroup> routeGroups =
                dungeonRouteGroupsForExport(selectedGroups, manager.allGroups());
        if (!routeGroups.isEmpty()) {
            int roomCount = DungeonRoomRouteLibrary.secretRoomCount(routeGroups);
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
        List<WaypointGroup> source = selectedGroups != null && !selectedGroups.isEmpty()
                ? selectedGroups
                : visibleGroups;
        if (source == null) return List.of();
        return source.stream().filter(group -> group != null && !group.runtimeOnly()).toList();
    }

    static String emptyExportNotice(String selectedZoneId) {
        return "Nothing to export in " + displayZoneLabel(selectedZoneId) + ".";
    }

    private String importTargetZoneId() {
        Zone current = manager.currentZone();
        String currentZoneId = current == null ? null : current.id();
        return WaypointerZoneCatalog.importTargetZoneId(selectedZoneId, currentZoneId);
    }

    private void importFromClipboard() {
        if (isStructureBrowseZone(selectedZoneId)) return;
        if (importInFlight) return;
        String text = minecraft.keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            ImportFeedback.failure("Clipboard is empty.");
            return;
        }
        importInFlight = true;
        String targetZoneId = importTargetZoneId();

        String defaultImportedRouteName = Component.translatable(
                "waypointer.import.default_route_name").getString();
        if (!CodecWorker.run(() -> decodeClipboardImport(text, defaultImportedRouteName), outcome -> {
            importInFlight = false;
            if (outcome != null && outcome.dungeonRoutes() != null) {
                installDecodedDungeonRooms(outcome.dungeonRoutes(), "clipboard");
                return;
            }
            if (outcome != null && outcome.config() != null) {
                reviewConfigImport(outcome.config());
                return;
            }
            if (outcome != null && outcome.catalogRouteId() != null) {
                CatalogRouteInstallScreen.open(this, outcome.catalogRouteId());
                return;
            }
            WaypointImporter.ImportResult result = outcome == null ? null : outcome.waypoints();
            if (result == null) {
                ImportFeedback.failure(outcome == null || outcome.error() == null
                        ? Component.translatable(
                                "waypointer.import.error.unrecognized").getString()
                        : outcome.error());
                return;
            }
            completeClipboardImport(result, targetZoneId);
        })) {
            importInFlight = false;
            ImportFeedback.failure(Component.translatable("waypointer.codec.busy").getString());
        }
    }

    private void completeClipboardImport(WaypointImporter.ImportResult result, String targetZoneId) {
        installImportedWaypointGroups(manager, config, result, targetZoneId);

        ImportFeedback.success(result.groups(), "clipboard");
        if (!result.groups().isEmpty()) {
            WaypointGroup first = result.groups().get(0);
            searchQuery = "";
            if (searchBox != null) searchBox.setValue("");
            selectedZoneId = first.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    ? DUNGEON_ROOMS_ZONE_ID : first.zoneId();
            selectGroupById(first.id());
        }
    }

    static ClipboardImportOutcome decodeClipboardImport(
            String text, String defaultImportedRouteName) {
        try {
            UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(text);
            if (decoded instanceof UniversalShareCodec.DungeonRoutes dungeonRoutes) {
                return new ClipboardImportOutcome(null, dungeonRoutes.result(), null, null, null);
            }
            if (decoded instanceof UniversalShareCodec.Configuration configuration) {
                return new ClipboardImportOutcome(null, null, configuration.config(), null, null);
            }
            if (decoded instanceof UniversalShareCodec.CatalogReference reference) {
                return new ClipboardImportOutcome(null, null, null, reference.routeId(), null);
            }

            return new ClipboardImportOutcome(
                    WaypointImporter.importAny(text, defaultImportedRouteName),
                    null, null, null, null);
        } catch (RuntimeException failure) {
            return new ClipboardImportOutcome(null, null, null, null, importFailureText(failure));
        }
    }

    record ClipboardImportOutcome(WaypointImporter.ImportResult waypoints,
                                  DungeonRouteImporter.Result dungeonRoutes,
                                  WaypointerConfig config,
                                  String catalogRouteId,
                                  String error) {}

    private void reviewConfigImport(WaypointerConfig imported) {
        ConfigImportConfirmation.open(this, config, imported, outcome -> {
            if (!outcome.confirmed()) return;
            ConfigChangeHistory.recordBulk("Imported config code ("
                    + outcome.changedSettings() + " changed)");
            ImportFeedback.successConfig(outcome.changedSettings(), "clipboard");
        });
    }

    static String importFailureText(RuntimeException failure) {
        String detail = failure == null ? null : failure.getMessage();
        if (detail == null || detail.isBlank()
                || detail.startsWith("unrecognized waypoint payload")) {
            return Component.translatable("waypointer.import.error.unrecognized").getString();
        }
        return detail;
    }

    static void installImportedWaypointGroups(
            ActiveGroupManager manager,
            WaypointerConfig config,
            WaypointImporter.ImportResult result,
            String targetZoneId) {
        retargetUnknownImportedGroups(result.groups(), targetZoneId);
        if (result.libraryMetadata().isEmpty()) {
            RouteColorPolicy.applyImportedRouteDefaults(result.groups(), config);
        }
        manager.addAll(result.groups());
        result.libraryMetadata().installFolders(manager, result.groups());
    }

    private void installDecodedDungeonRooms(DungeonRouteImporter.Result decoded, String source) {
        List<WaypointGroup> routes = DungeonRoomRouteLibrary.installRoutes(manager, decoded.groups());
        if (routes.isEmpty()) {
            ImportFeedback.failure(Component.translatable(
                    "waypointer.import.error.damaged").getString());
            return;
        }

        ImportFeedback.successDungeonRoutes(DungeonRoomRouteLibrary.secretRoomCount(routes),
                DungeonRoomShareCodec.waypointCount(routes), source);
        if (!decoded.unmatchedRooms().isEmpty()) {
            MinecraftCompat.addClientSystemMessage(minecraft, Component.translatable(
                    "waypointer.dungeon.routes.unmatched_rooms", decoded.unmatchedRooms().size()));
        }
        searchQuery = "";
        if (searchBox != null) searchBox.setValue("");
        selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        clearRouteSelection();
        pendingFocusGroupId = routes.getFirst().id();
        rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        if (GuiTokens.extractSeeThroughBackground(g, width, height)) return;
        super.extractBackground(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
