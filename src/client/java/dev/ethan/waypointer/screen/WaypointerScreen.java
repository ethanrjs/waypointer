package dev.ethan.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.color.RouteColorPolicy;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.RouteProgress;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.debug.DebugEventLog;
import dev.ethan.waypointer.dungeon.DungeonRoomRouteSync;
import dev.ethan.waypointer.dungeon.DungeonRouteDownloader;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.dungeon.data.DungeonRoomShareCodec;
import dev.ethan.waypointer.util.MathUtil;
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
import java.util.Set;

import static dev.ethan.waypointer.screen.GuiTokens.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;

/**
 * Top-level editor screen.
 *
 * Layout (clinical / utility aesthetic):
 *   +------------------------------------------+
 *   | Waypointer                   Hub -- 3 gp |
 *   |                                          |
 *   | [ Zones      ] | group list ...          |
 *   | > Hub        3|                          |
 *   |   Garden     1|                          |
 *   |   Unknown    0|                          |
 *   |                                          |
 *   | [New Group][Edit][Delete]...      [Done] |
 *   +------------------------------------------+
 *
 * The sidebar replaces the old horizontal tab strip so the "Unknown" zone stops
 * being a lone aqua pill in the corner, and so adding many zones doesn't force
 * users to horizontal-scroll mentally.
 *
 * Footer uses {@link GuiTokens#layoutFooter} -- primary actions on the left,
 * Done pinned right, with wrap-above when the screen is narrow. This is what
 * fixes the overlap bug at small GUI scales.
 *
 * Hand-rolled list (rather than ObjectSelectionList) so we can render custom
 * row content. The whole list fits in a few hundred lines and handles clicks
 * and scroll explicitly, which is easier to debug than the vanilla widget.
 */
public final class WaypointerScreen extends Screen {

    static final String TEMPORARY_ZONE_ID = "__temporary__";
    private static final String TEMPORARY_ZONE_LABEL = "Temporary";
    private static final int TEMPORARY_ACCENT = 0xFF58C878;
    static final String DUNGEON_ROOMS_ZONE_ID = "__dungeon_rooms__";
    private static final String DUNGEON_ROOMS_LABEL = "Dungeon Rooms";
    private static final String DUNGEON_ROOM_LABEL_PREFIX = "Dungeons: ";
    private static final int DUNGEON_ROOM_ACCENT = 0xFFFF8A8A;
    private static final int ROUTE_ROW_PITCH = ROW_H + 4;
    private static final int ROUTE_TOGGLE_CHIP_W = 54;
    private static final int ROUTE_TOGGLE_CHIP_H = 14;
    private static final int ROUTE_TOGGLE_HIT_PAD = 2;
    private static final int DUNGEON_ROUTE_CHILD_INDENT = 18;
    private static final int MOUSE_BUTTON_LEFT = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;
    private static String lastSelectedZoneId;
    private static String lastCurrentZoneIdWhenRemembered;
    private static final Set<String> expandedDungeonRoomZoneIds = new HashSet<>();

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private String selectedZoneId;
    private int scrollOffset;
    private int sidebarScrollOffset;
    private String selectedGroupId;
    private final LinkedHashSet<String> selectedGroupIds = new LinkedHashSet<>();
    private String selectionAnchorGroupId;
    /**
     * Group id the screen should focus on its next {@link #init()} pass --
     * set by {@link #openFocused} and consumed on first init. Nullable by design
     * so {@code init()} after window resize doesn't re-snap the scroll offset.
     */
    private String pendingFocusGroupId;
    /**
     * Dungeon room zone id whose section should be brought on screen during the
     * next {@link #init()} pass. This is separate from pendingFocusGroupId so
     * opening Waypointer inside a room can highlight the room without selecting
     * a specific route.
     */
    private String pendingFocusRoomZoneId;

    // Delete uses a two-click confirm: first click arms, second within CONFIRM_WINDOW_MS
    // commits. A full modal would be more intrusive than this class of action warrants;
    // undo is cheap (re-add the group) but accidental taps shouldn't silently destroy data.
    //
    // The armed state reuses the same button label ("Confirm?") regardless of which group
    // is selected -- stuffing the group name into the label overflowed the button bounds
    // at long names, and the name belongs in the tooltip where wrapping is free.
    private static final long CONFIRM_WINDOW_MS = 2500L;
    private static final String DELETE_LABEL  = "Delete";
    private static final String HIDE_ALL_ROUTES_LABEL = "Hide All";
    private static final String HIDE_ALL_ROUTES_TOOLTIP_DEFAULT =
            "Hide every shown route in this zone.\n"
          + "Double click to confirm.";
    private static final String CONFIRM_LABEL = "Confirm?";
    private static final String NO_SEL_LABEL  = "Pick route";
    private static final String DELETE_TOOLTIP_DEFAULT =
            "Remove the selected route permanently.\n"
          + "Double click to confirm.";
    private static final int SEARCH_CLEAR_BTN_W = 52;
    private static final long MAIN_NOTICE_MS = 2500L;
    // Sized for the widest transient state label ("Confirm?") so the button doesn't
    // visibly grow or shrink when arming/disarming. Leave some horizontal slack so
    // vanilla's "hover" narration arrow has room without clipping the text.
    private static final int DELETE_BTN_W = 72;
    private static final int HIDE_ALL_ROUTES_BTN_W = 76;
    private static final int DOWNLOAD_ROUTES_BTN_W = 112;
    private static final int SETTINGS_BTN_W = 76;
    /**
     * Cap on the route-list content width. Rows anchor text left and the
     * Shown/Hidden chip right; on wide screens an uncapped row separates the
     * two by over a thousand pixels and the chip stops reading as part of its
     * row.
     */
    private static final int MAIN_CONTENT_MAX_W = 660;
    private Button editBtn;
    private Button hideAllRoutesBtn;
    private Button deleteBtn;
    private EditBox searchBox;
    private OverlayButton clearSearchButton;
    private OverlayButton downloadRoutesButton;
    private OverlayButton settingsButton;
    private String searchQuery = "";
    private long hideAllArmedUntil = 0L;
    private final LinkedHashSet<String> hideAllArmedGroupIds = new LinkedHashSet<>();
    private long deleteArmedUntil = 0L;
    private final LinkedHashSet<String> deleteArmedGroupIds = new LinkedHashSet<>();
    private String mainNotice = "";
    private long mainNoticeUntil = 0L;

    private List<GuiTokens.ButtonSpec> footerActions() {
        // Constructive actions first, Delete isolated at the end of the
        // cluster; Settings lives in the header (it navigates, it doesn't act
        // on route data).
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("New Route", 92, this::createGroup));
        left.add(new GuiTokens.ButtonSpec("Edit", 64, this::editSelected));
        left.add(new GuiTokens.ButtonSpec(HIDE_ALL_ROUTES_LABEL, HIDE_ALL_ROUTES_BTN_W,
                this::onHideAllRoutesClicked,
                Tooltip.create(Component.literal(HIDE_ALL_ROUTES_TOOLTIP_DEFAULT))));
        left.add(new GuiTokens.ButtonSpec("Import", 74, this::importFromClipboard));
        left.add(new GuiTokens.ButtonSpec("Export", 74, this::exportZone,
                Tooltip.create(Component.literal(
                        "Export the selected route.\n"
                      + "If none is selected, export every visible route."))));
        left.add(new GuiTokens.ButtonSpec(DELETE_LABEL, DELETE_BTN_W, this::onDeleteClicked));
        return left;
    }

    public WaypointerScreen(ActiveGroupManager manager, WaypointerConfig config) {
        super(Component.literal("Waypointer"));
        this.manager = manager;
        this.config = config;
        this.selectedZoneId = initialSelectedZoneId(manager);
    }

    public static void open(ActiveGroupManager manager, WaypointerConfig config) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        screen.focusCurrentDungeonRoomOnOpen();
        Minecraft.getInstance().setScreen(screen);
    }

    public static void openFocused(ActiveGroupManager manager, WaypointerConfig config,
                                   WaypointGroup focus) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        if (focus != null) {
            // Select by id rather than by index -- index lookups into
            // visible rows are fragile when groups added mid-list shift
            // indices. The init() pass will resolve the id to a current
            // route row after it knows the list ordering for the zone.
            screen.selectedZoneId = focus.temp()
                    ? TEMPORARY_ZONE_ID
                    : sidebarSelectionForZoneId(focus.zoneId());
            if (!focus.temp() && isDungeonRoomZone(focus.zoneId())) {
                screen.pendingFocusRoomZoneId = focus.zoneId();
                expandedDungeonRoomZoneIds.add(focus.zoneId());
            }
            screen.pendingFocusGroupId = focus.id();
        }
        Minecraft.getInstance().setScreen(screen);
    }

    private void focusCurrentDungeonRoomOnOpen() {
        String roomZoneId = currentDungeonRoomZoneId(manager);
        if (roomZoneId == null) return;
        selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        expandedDungeonRoomZoneIds.add(roomZoneId);
        clearRouteSelection();
        pendingFocusRoomZoneId = roomZoneId;
    }

    @Override
    protected void init() {
        int footerY = height - FOOTER_H;
        hideAllArmedUntil = 0L;
        hideAllArmedGroupIds.clear();
        deleteArmedUntil = 0L;
        deleteArmedGroupIds.clear();
        editBtn = null;
        hideAllRoutesBtn = null;
        deleteBtn = null;
        searchBox = null;
        clearSearchButton = null;
        downloadRoutesButton = null;
        settingsButton = null;

        // Fixed width so the label can toggle between "Delete" and "Confirm?" without
        // the footer re-flowing or the text sliding past the bevel.
        List<GuiTokens.ButtonSpec> left = footerActions();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);

        // We need references to stateful footer buttons so we can refresh labels and
        // enabled state after selection or route visibility changes. Intercept every
        // built button and still register all of them exactly once.
        GuiTokens.layoutFooter(width, footerY, left, done,
                b -> {
            if ("Edit".contentEquals(b.getMessage().getString())) {
                editBtn = b;
            }
            if (HIDE_ALL_ROUTES_LABEL.contentEquals(b.getMessage().getString())) {
                hideAllRoutesBtn = b;
            }
            if (DELETE_LABEL.contentEquals(b.getMessage().getString())) {
                deleteBtn = b;
                deleteBtn.setTooltip(Tooltip.create(Component.literal(DELETE_TOOLTIP_DEFAULT)));
            }
            addRenderableWidget(b);
        }, font);

        searchBox = new EditBox(font, 0, 0, 100, BTN_H, Component.literal("Search routes"));
        searchBox.setMaxLength(80);
        searchBox.setValue(searchQuery);
        searchBox.setHint(Component.literal("Search routes"));
        searchBox.setTooltip(Tooltip.create(Component.literal("Filter routes by name, zone, waypoint, or progress.")));
        searchBox.setResponder(this::onSearchChanged);
        syncSearchBoxGeometry();
        addRenderableWidget(searchBox);
        clearSearchButton = new OverlayButton(0, 0, SEARCH_CLEAR_BTN_W, BTN_H,
                Component.literal("Clear"), this::clearRouteSearch);
        clearSearchButton.setTooltip(Tooltip.create(Component.literal("Clear route search.")));
        updateClearSearchButton();
        syncSearchBoxGeometry();
        addRenderableWidget(clearSearchButton);

        downloadRoutesButton = new OverlayButton(0, 0, DOWNLOAD_ROUTES_BTN_W, BTN_H,
                Component.literal("Download routes"), b -> startRouteDownload());
        downloadRoutesButton.setTooltip(Tooltip.create(Component.literal(
                "Download the community secret-route set into your local route library.\n\n"
                        + DungeonRouteDownloader.attributionText())));
        syncSearchBoxGeometry();
        addRenderableWidget(downloadRoutesButton);

        settingsButton = new OverlayButton(width - PAD_OUTER - SETTINGS_BTN_W,
                PAD_OUTER - 5, SETTINGS_BTN_W, BTN_H,
                Component.literal("Settings"), b -> openSettings());
        settingsButton.setTooltip(Tooltip.create(Component.literal("Open Waypointer settings.")));
        addRenderableWidget(settingsButton);

        // Resolve pending focus requests from open/openFocused(). We do this here
        // rather than in the constructor because the zone's group list can
        // only be meaningfully indexed after the screen knows its current
        // zone -- the rendered row list is keyed off selectedZoneId, which is
        // settled by the time init() runs.
        if (pendingFocusRoomZoneId != null) {
            focusRoomByZoneId(pendingFocusRoomZoneId);
            pendingFocusRoomZoneId = null;
        }
        if (pendingFocusGroupId != null) {
            selectGroupById(pendingFocusGroupId);
            pendingFocusGroupId = null;
        }
        refreshActionButtons();
    }

    private static String initialSelectedZoneId(ActiveGroupManager manager) {
        List<String> ids = zoneIdsForManager(manager);
        String currentZoneId = currentZoneId(manager);
        String fallback = sidebarSelectionForZoneId(currentZoneId);
        if (rememberedCurrentZoneChanged(currentZoneId) && ids.contains(fallback)) {
            return fallback;
        }
        if (lastSelectedZoneId != null && ids.contains(lastSelectedZoneId)) {
            return lastSelectedZoneId;
        }
        if (ids.contains(fallback)) {
            return fallback;
        }
        return ids.isEmpty() ? Zone.UNKNOWN.id() : ids.get(0);
    }

    private static String currentZoneId(ActiveGroupManager manager) {
        Zone current = manager.currentZone();
        return current == null ? Zone.UNKNOWN.id() : current.id();
    }

    private static boolean rememberedCurrentZoneChanged(String currentZoneId) {
        String normalized = currentZoneId == null ? Zone.UNKNOWN.id() : currentZoneId;
        return lastCurrentZoneIdWhenRemembered != null
                && !lastCurrentZoneIdWhenRemembered.equals(normalized);
    }

    private void selectGroupById(String id) {
        if (id == null) return;
        WaypointGroup storedGroup = manager.get(id);
        if (storedGroup != null && !storedGroup.temp() && isDungeonRoomZone(storedGroup.zoneId())) {
            selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
            expandedDungeonRoomZoneIds.add(storedGroup.zoneId());
        }
        List<RouteListRow> rows = routeListRows();
        for (int i = 0; i < rows.size(); i++) {
            RouteListRow row = rows.get(i);
            if (!row.roomHeader && row.group != null && row.group.id().equals(id)) {
                selectOnlyGroupId(id);
                scrollRowIndexIntoView(i);
                return;
            }
        }
    }

    private void focusRoomByZoneId(String roomZoneId) {
        if (!isDungeonRoomZone(roomZoneId)) return;
        selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        expandedDungeonRoomZoneIds.add(roomZoneId);
        clearRouteSelection();
        List<RouteListRow> rows = routeListRows();
        for (int i = 0; i < rows.size(); i++) {
            RouteListRow row = rows.get(i);
            if (row.roomHeader && roomZoneId.equals(row.roomZoneId)) {
                scrollRowIndexIntoView(i);
                return;
            }
        }
    }

    private void scrollRowIndexIntoView(int rowIndex) {
        if (rowIndex < 0) return;
        List<RouteListRow> rows = routeListRows();
        Layout layout = layout();
        int rowsTop = mainRowsTop(layout.top());
        int listHeight = Math.max(0, layout.bottom() - rowsTop);
        int rowTop = rowIndex * ROUTE_ROW_PITCH;
        int rowBottom = rowTop + ROW_H + 2;
        if (rowTop < scrollOffset) {
            scrollOffset = rowTop;
        } else if (rowBottom > scrollOffset + listHeight) {
            scrollOffset = rowBottom - listHeight + GAP;
        }
        scrollOffset = MathUtil.clamp(scrollOffset, 0, maxMainScroll(rows.size(), listHeight));
    }

    private static int maxMainScroll(int rowCount, int listHeight) {
        int contentHeight = rowCount * ROUTE_ROW_PITCH;
        return Math.max(0, contentHeight - Math.max(0, listHeight) + 8);
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
        searchBox.setX(left);
        searchBox.setY(layout.top() + 4);
        int availableWidth = layout.mainRight() - layout.mainLeft() - GAP * 2;
        int clearWidth = clearSearchButton == null ? 0 : SEARCH_CLEAR_BTN_W + GAP_TIGHT;
        boolean showDownloadButton = isDungeonRoomsZone(selectedZoneId)
                && WaypointerClient.dungeonRouteDownloader() != null;
        int downloadWidth = showDownloadButton ? DOWNLOAD_ROUTES_BTN_W + GAP : 0;
        int searchWidth = Math.max(80, Math.min(180, availableWidth - clearWidth - downloadWidth));
        searchBox.setWidth(searchWidth);
        if (clearSearchButton != null) {
            clearSearchButton.setX(left + searchWidth + GAP_TIGHT);
            clearSearchButton.setY(layout.top() + 4);
            clearSearchButton.setWidth(SEARCH_CLEAR_BTN_W);
        }
        if (downloadRoutesButton != null) {
            downloadRoutesButton.visible = showDownloadButton;
            downloadRoutesButton.setX(layout.mainRight() - GAP - DOWNLOAD_ROUTES_BTN_W);
            downloadRoutesButton.setY(layout.top() + 4);
            downloadRoutesButton.setWidth(DOWNLOAD_ROUTES_BTN_W);
        }
    }

    private void startRouteDownload() {
        DungeonRouteDownloader downloader = WaypointerClient.dungeonRouteDownloader();
        if (downloader == null) return;
        downloader.download(message -> flashMainNotice(message.getString()));
    }

    private void updateClearSearchButton() {
        if (clearSearchButton != null) {
            clearSearchButton.active = searchQuery != null && !searchQuery.isEmpty();
        }
    }

    private void openSettings() {
        minecraft.setScreen(new SettingsScreen(this, config, WaypointerClient.dungeonConfig()));
    }

    private List<String> zoneIds() {
        return zoneIdsForManager(manager);
    }

    private static List<String> zoneIdsForManager(ActiveGroupManager manager) {
        List<String> zones = new ArrayList<>();
        zones.add(TEMPORARY_ZONE_ID);
        List<String> dungeonRooms = new ArrayList<>();
        for (String zoneId : manager.knownZoneIds()) {
            if (isDungeonRoomZone(zoneId)) {
                if (normalGroupCountForZone(manager, zoneId) > 0
                        && !dungeonRooms.contains(zoneId)) {
                    dungeonRooms.add(zoneId);
                }
            } else if (normalGroupCountForZone(manager, zoneId) > 0 && !zones.contains(zoneId)) {
                zones.add(zoneId);
            }
        }
        Zone currentZone = manager.currentZone();
        if (currentZone != null) {
            String currentId = currentZone.id();
            if (isDungeonRoomZone(currentId)) {
                if (!dungeonRooms.contains(currentId)) dungeonRooms.add(0, currentId);
            } else if (!zones.contains(currentId)) {
                zones.add(1, currentId);
            }
        }
        for (DungeonRoomDefinition definition : dungeonDefinitionsForExport(DungeonRoomData.customDefinitions())) {
            if (!dungeonRooms.contains(definition.id())) dungeonRooms.add(definition.id());
        }
        if (!dungeonRooms.isEmpty()) {
            zones.add(DUNGEON_ROOMS_ZONE_ID);
        }
        if (zones.size() == 1) zones.add(Zone.UNKNOWN.id());
        return zones;
    }

    private List<WaypointGroup> visibleGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        if (isTemporaryZone(selectedZoneId)) {
            out.addAll(temporaryGroups());
        } else if (isDungeonRoomsZone(selectedZoneId)) {
            out.addAll(dungeonRoomGroups());
        } else {
            for (WaypointGroup group : manager.groupsForZone(selectedZoneId)) {
                if (!group.temp()) out.add(group);
            }
        }

        String query = normalizedSearchQuery();
        if (query.isEmpty()) return out;

        List<WaypointGroup> filtered = new ArrayList<>();
        for (WaypointGroup group : out) {
            if (groupMatchesSearch(group, query)) filtered.add(group);
        }
        return filtered;
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
                    && isDungeonRoomZone(group.zoneId())) {
                out.add(group);
            }
        }
        return out;
    }

    static int hideRoutes(Iterable<WaypointGroup> groups) {
        int changed = 0;
        if (groups == null) return changed;
        for (WaypointGroup group : groups) {
            if (group == null || !group.enabled()) continue;
            group.setEnabled(false);
            changed++;
        }
        return changed;
    }

    private List<RouteListRow> routeListRows() {
        List<RouteListRow> rows = new ArrayList<>();
        if (!isDungeonRoomsZone(selectedZoneId)) {
            for (WaypointGroup group : visibleGroups()) {
                rows.add(new RouteListRow(false, null, group, 0, 0, false, false,
                        false, false, false));
            }
            return rows;
        }

        String query = normalizedSearchQuery();
        boolean searching = !query.isEmpty();
        String currentRoomZoneId = currentDungeonRoomZoneId(manager);
        for (String roomZoneId : dungeonRoomZoneIdsForMainList(currentRoomZoneId)) {
            List<WaypointGroup> roomGroups = dungeonRoomGroupsForZone(roomZoneId);
            boolean roomMatches = searching && containsSearch(displayZoneLabel(roomZoneId), query);
            List<WaypointGroup> displayGroups = new ArrayList<>();
            for (WaypointGroup group : roomGroups) {
                if (!searching || roomMatches || groupMatchesSearch(group, query)) {
                    displayGroups.add(group);
                }
            }
            if (searching && !roomMatches && displayGroups.isEmpty()) continue;

            boolean expanded = expandedDungeonRoomZoneIds.contains(roomZoneId);
            boolean currentRoom = roomZoneId.equals(currentRoomZoneId);
            boolean revealRoutes = expanded || searching;
            int secretCount = installedSecretCountForRoom(roomZoneId);
            rows.add(new RouteListRow(true, roomZoneId, null, roomGroups.size(),
                    secretCount, false, false, revealRoutes, currentRoom, searching));
            if (revealRoutes) {
                for (WaypointGroup group : displayGroups) {
                    rows.add(new RouteListRow(false, roomZoneId, group, 0, 0, false, false,
                            false, currentRoom, false));
                }
                // Installed secrets list immediately after an import — no room
                // visit needed. The live generated group takes this row's place
                // while the player is actually inside the room.
                boolean hasLiveGenerated = false;
                boolean hasUserRoute = false;
                for (WaypointGroup group : roomGroups) {
                    if (group.runtimeOnly()) hasLiveGenerated = true;
                    else if (!group.isEmpty()) hasUserRoute = true;
                }
                if (secretCount > 0 && !hasLiveGenerated && (!searching || roomMatches)) {
                    rows.add(new RouteListRow(false, roomZoneId, null, 0, secretCount,
                            true, hasUserRoute, false, currentRoom, false));
                }
            }
        }
        return rows;
    }

    private List<String> dungeonRoomZoneIdsForMainList(String currentRoomZoneId) {
        List<String> roomIds = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && isDungeonRoomZone(group.zoneId())
                    && !roomIds.contains(group.zoneId())) {
                roomIds.add(group.zoneId());
            }
        }
        for (DungeonRoomDefinition definition : dungeonDefinitionsForExport(DungeonRoomData.customDefinitions())) {
            if (!roomIds.contains(definition.id())) roomIds.add(definition.id());
        }
        if (isDungeonRoomZone(currentRoomZoneId) && !roomIds.contains(currentRoomZoneId)) {
            roomIds.add(0, currentRoomZoneId);
        }
        return roomIds;
    }

    private List<WaypointGroup> dungeonRoomGroupsForZone(String roomZoneId) {
        List<WaypointGroup> out = new ArrayList<>();
        boolean hasStoredRoute = false;
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && roomZoneId != null && roomZoneId.equals(group.zoneId())) {
                out.add(group);
                if (!group.runtimeOnly() && !group.isEmpty()) hasStoredRoute = true;
            }
        }
        // While inside the room, a stored route also has a projected runtime
        // mirror under the generated id. The stored group is the canonical GUI
        // object; listing the mirror too would show the same route twice.
        if (hasStoredRoute) {
            out.removeIf(DungeonRoomRouteSync::isGeneratedGroup);
        }
        return out;
    }

    private String normalizedSearchQuery() {
        return searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
    }

    static boolean groupMatchesSearch(WaypointGroup group, String query) {
        if (containsSearch(group.name(), query)) return true;
        if (containsSearch(group.zoneId(), query)) return true;
        if (containsSearch(displayZoneLabel(group.zoneId()), query)) return true;
        if (containsSearch(group.loadMode().name(), query)) return true;
        if (containsSearch(RouteProgress.summary(group), query)) return true;

        for (int i = 0; i < group.size(); i++) {
            if (waypointMatchesSearch(group, i, query)) return true;
        }
        return false;
    }

    static boolean waypointMatchesSearch(WaypointGroup group, int index, String query) {
        var waypoint = group.get(index);
        if (containsSearch(waypoint.name(), query)) return true;
        if (containsSearch(group.displayIndexLabel(index), query)) return true;
        String coords = waypoint.x() + "," + waypoint.y() + "," + waypoint.z();
        return containsSearch(coords, query);
    }

    private static boolean containsSearch(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    private List<WaypointGroup> temporaryGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() && !group.isEmpty()) out.add(group);
        }
        return out;
    }

    private List<WaypointGroup> dungeonRoomGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && isDungeonRoomZone(group.zoneId())) out.add(group);
        }
        return out;
    }

    private int normalGroupCountForZone(String zoneId) {
        if (isDungeonRoomsZone(zoneId)) return dungeonRoomGroupCount(manager);
        return normalGroupCountForZone(manager, zoneId);
    }

    private static int normalGroupCountForZone(ActiveGroupManager manager, String zoneId) {
        int count = 0;
        for (WaypointGroup group : manager.groupsForZone(zoneId)) {
            if (!group.temp()) count++;
        }
        return count;
    }

    private static int dungeonRoomGroupCount(ActiveGroupManager manager) {
        int count = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && isDungeonRoomZone(group.zoneId())) count++;
        }
        return count;
    }

    private int temporaryWaypointCount() {
        int count = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp()) count += group.size();
        }
        return count;
    }

    private static boolean isTemporaryZone(String zoneId) {
        return TEMPORARY_ZONE_ID.equals(zoneId);
    }

    private static boolean isDungeonRoomsZone(String zoneId) {
        return DUNGEON_ROOMS_ZONE_ID.equals(zoneId);
    }

    private static boolean isDungeonRoomZone(String zoneId) {
        return DungeonRoomData.definition(zoneId) != null;
    }

    private static String currentDungeonRoomZoneId(ActiveGroupManager manager) {
        if (manager == null || manager.currentZone() == null) return null;
        String zoneId = manager.currentZone().id();
        return isDungeonRoomZone(zoneId) ? zoneId : null;
    }

    static String sidebarSelectionForZoneId(String zoneId) {
        if (zoneId != null && isDungeonRoomZone(zoneId)) {
            return DUNGEON_ROOMS_ZONE_ID;
        }
        return zoneId;
    }

    private static String displayZoneLabel(String zoneId) {
        if (isTemporaryZone(zoneId)) return TEMPORARY_ZONE_LABEL;
        if (isDungeonRoomsZone(zoneId)) return DUNGEON_ROOMS_LABEL;
        DungeonRoomDefinition definition = DungeonRoomData.definition(zoneId);
        if (definition != null) return DUNGEON_ROOM_LABEL_PREFIX + definition.displayName();
        return Zone.fromId(zoneId).displayName();
    }

    // --- render ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        // Reset the Delete button label once the confirm/flash window elapses.
        // Doing this in render (rather than tick) keeps the screen dependency-free
        // and runs every frame which is plenty for a short confirmation transition.
        long now = System.currentTimeMillis();
        if (hideAllRoutesBtn != null && hideAllArmedUntil != 0 && now > hideAllArmedUntil) {
            hideAllArmedUntil = 0L;
            hideAllArmedGroupIds.clear();
            resetHideAllRoutesButton();
        }
        if (deleteBtn != null) {
            if (deleteArmedUntil != 0 && now > deleteArmedUntil) {
                deleteArmedUntil = 0;
                resetDeleteButton();
            }
            if (labelFlashUntil != 0 && now > labelFlashUntil) {
                labelFlashUntil = 0;
                if (deleteArmedUntil == 0) resetDeleteButton();
            }
        }

        // Header
        g.text(font, "Waypointer", PAD_OUTER, PAD_OUTER, TEXT, false);
        String status;
        if (isTemporaryZone(selectedZoneId)) {
            int waypointCount = temporaryWaypointCount();
            status = TEMPORARY_ZONE_LABEL + "  .  " + waypointCount
                    + " waypoint" + (waypointCount == 1 ? "" : "s");
        } else {
            int routeCount = visibleGroups().size();
            status = displayZoneLabel(selectedZoneId) + "  ."
                    + "  " + routeCount + " route" + (routeCount == 1 ? "" : "s");
        }
        int statusRight = settingsButton != null
                ? settingsButton.getX() - GAP
                : width - PAD_OUTER;
        g.text(font, status, statusRight - font.width(status), PAD_OUTER, TEXT_DIM, false);

        // Region geometry
        Layout layout = layout();

        renderSidebar(g, layout.sidebarLeft(), layout.top(), layout.sidebarRight(),
                layout.bottom(), mouseX, mouseY);
        renderMain(g, layout.mainLeft(), layout.top(), layout.mainRight(),
                layout.bottom(), mouseX, mouseY);
        renderSearchBox(g, mouseX, mouseY, partial);
    }

    private void renderSearchBox(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        syncSearchBoxGeometry();
        if (searchBox != null) {
            searchBox.extractWidgetRenderState(g, mouseX, mouseY, partial);
        }
        if (clearSearchButton != null) {
            clearSearchButton.extractOverlay(g, mouseX, mouseY, partial);
        }
        if (downloadRoutesButton != null && downloadRoutesButton.visible) {
            downloadRoutesButton.extractOverlay(g, mouseX, mouseY, partial);
        }
        if (settingsButton != null) {
            settingsButton.extractOverlay(g, mouseX, mouseY, partial);
        }
    }

    private Layout layout() {
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);
        int footerSpace = GuiTokens.footerHeight(width, footerActions(), done, font);
        int top = PAD_OUTER + font.lineHeight + GAP;
        int bottom = height - footerSpace - GAP_SECTION;
        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP;
        int mainRight = width - PAD_OUTER;
        return new Layout(top, bottom, sidebarLeft, sidebarRight, mainLeft, mainRight);
    }

    private record Layout(int top, int bottom, int sidebarLeft, int sidebarRight,
                          int mainLeft, int mainRight) {}

    private static final class OverlayButton extends Button {
        private OverlayButton(int x, int y, int width, int height,
                              Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        private void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            extractWidgetRenderState(g, mouseX, mouseY, partial);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            var font = Minecraft.getInstance().font;
            String label = getMessage().getString();
            String clipped = font.plainSubstrByWidth(label, getWidth() - 8);
            int textX = getX() + (getWidth() - font.width(clipped)) / 2;
            int textY = getY() + (getHeight() - font.lineHeight) / 2;
            g.text(font, clipped, textX, textY, active ? TEXT : TEXT_MUTED, false);
        }
    }

    private static final class RouteListRow {
        private final boolean roomHeader;
        private final String roomZoneId;
        private final WaypointGroup group;
        private final int roomRouteCount;
        private final int roomSecretCount;
        /** Installed secret-route row backed by the room definition, not a live group. */
        private final boolean secretRoute;
        /** The room has a user-authored route, which outranks the installed secrets. */
        private final boolean secretSuppressed;
        private final boolean expanded;
        private final boolean currentRoom;
        private final boolean searchReveal;

        private RouteListRow(boolean roomHeader, String roomZoneId, WaypointGroup group,
                             int roomRouteCount, int roomSecretCount, boolean secretRoute,
                             boolean secretSuppressed, boolean expanded,
                             boolean currentRoom, boolean searchReveal) {
            this.roomHeader = roomHeader;
            this.roomZoneId = roomZoneId;
            this.group = group;
            this.roomRouteCount = roomRouteCount;
            this.roomSecretCount = roomSecretCount;
            this.secretRoute = secretRoute;
            this.secretSuppressed = secretSuppressed;
            this.expanded = expanded;
            this.currentRoom = currentRoom;
            this.searchReveal = searchReveal;
        }
    }

    private void renderSidebar(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, SURFACE);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        int labelY = y1 + 10;
        g.text(font, "Zones", x1 + GAP, labelY, TEXT_DIM, false);

        int rowsTop = sidebarRowsTop(y1);
        int rowsBottom = y2 - GAP_TIGHT;
        if (rowsBottom <= rowsTop) return;

        List<String> ids = zoneIds();
        String currentId = manager.currentZone() == null ? null : manager.currentZone().id();
        sidebarScrollOffset = MathUtil.clamp(sidebarScrollOffset, 0,
                maxSidebarScroll(ids.size(), rowsBottom - rowsTop));
        g.enableScissor(x1, rowsTop, x2, rowsBottom);
        for (int i = 0; i < ids.size(); i++) {
            int rowY = rowsTop - sidebarScrollOffset + i * ROW_H;
            if (rowY + ROW_H <= rowsTop) continue;
            if (rowY >= rowsBottom) break;
            String id = ids.get(i);
            boolean selected = id.equals(selectedZoneId);
            boolean temporary = isTemporaryZone(id);
            boolean dungeonParent = isDungeonRoomsZone(id);
            boolean dungeonRoom = isDungeonRoomZone(id);
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= Math.min(rowsBottom, rowY + ROW_H);
            if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
            boolean current = id.equals(currentId)
                    || (dungeonParent && isDungeonRoomZone(currentId));
            drawZoneRow(g, x1, rowY, x2, id, selected, hovered,
                    !temporary && current, temporary, dungeonParent, dungeonRoom);
        }
        g.disableScissor();
    }

    static int sidebarRowsTop(int panelTop) {
        return panelTop + 24;
    }

    static int maxSidebarScroll(int zoneCount, int viewportHeight) {
        return Math.max(0, zoneCount * ROW_H - Math.max(0, viewportHeight));
    }

    static int sidebarScrollAfterWheel(int currentOffset, double wheelDelta,
                                       int zoneCount, int viewportHeight) {
        int maxScroll = maxSidebarScroll(zoneCount, viewportHeight);
        return MathUtil.clamp(currentOffset - (int) (wheelDelta * ROW_H), 0, maxScroll);
    }

    static int sidebarIndexAt(double mouseY, int rowsTop, int rowsBottom,
                              int scrollOffset, int zoneCount) {
        if (mouseY < rowsTop || mouseY >= rowsBottom) return -1;
        int index = (int) ((mouseY - rowsTop + scrollOffset) / ROW_H);
        return index >= 0 && index < zoneCount ? index : -1;
    }

    private void drawZoneRow(GuiGraphicsExtractor g, int x1, int y, int x2,
                             String zoneId, boolean selected, boolean hovered,
                             boolean isCurrent, boolean temporary,
                             boolean dungeonParent, boolean dungeonRoom) {
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);

        // "unknown" is intentionally quiet -- it's a placeholder zone, not the focal
        // point of an empty state. So it never gets the accent bar and renders in muted text.
        boolean isUnknown = Zone.UNKNOWN.id().equals(zoneId);
        int accent = temporary ? TEMPORARY_ACCENT
                : (dungeonParent || dungeonRoom) ? DUNGEON_ROOM_ACCENT
                : ACCENT;
        if (selected && (!isUnknown || temporary)) {
            g.fill(x1, y, x1 + 2, y + ROW_H, accent);
        }

        String label = displayZoneLabel(zoneId);
        int count = temporary ? temporaryWaypointCount() : normalGroupCountForZone(zoneId);
        int textColor = isUnknown && !temporary ? TEXT_MUTED : selected ? TEXT : TEXT_DIM;
        // Zones with nothing in them recede so the populated ones carry the
        // sidebar's hierarchy. (Dungeon Rooms keeps normal weight while it has
        // installed secrets even with zero user routes.)
        boolean emptyZone = count == 0
                && !(dungeonParent && !DungeonRoomData.customDefinitions().isEmpty());
        if (emptyZone && !selected && !isCurrent) textColor = TEXT_MUTED;

        String countStr = Integer.toString(count);
        int countX = (isCurrent ? x2 - GAP - 12 : x2 - GAP) - font.width(countStr);
        int labelX = x1 + GAP + 2 + (dungeonRoom ? 8 : 0);
        int labelMaxW = Math.max(12, countX - GAP_TIGHT - labelX);
        String clippedLabel = font.plainSubstrByWidth(label, labelMaxW);
        g.text(font, clippedLabel, labelX, y + 6, textColor, false);

        // live "current zone" indicator -- a tiny filled dot, no color, just a glyph
        if (isCurrent) {
            int dotX = x2 - GAP - 6;
            g.fill(dotX, y + ROW_H / 2 - 2, dotX + 4, y + ROW_H / 2 + 2, accent);
        }

        // Group count, right-aligned next to the dot (or at the edge if no dot)
        g.text(font, countStr, countX, y + 6, TEXT_MUTED, false);
    }

    private void renderMain(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        List<RouteListRow> rows = routeListRows();
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        int rowsTop = mainRowsTop(y1);
        renderMainNotice(g, x1, y1, x2);
        if (rows.isEmpty()) {
            renderEmptyState(g, x1, rowsTop);
            return;
        }

        g.enableScissor(x1, rowsTop, x2, y2);

        // Rows render inside the capped content column so a row's text and its
        // Shown/Hidden chip stay one visual unit on wide screens.
        int rowRight = mainContentRight(x1, x2);
        int y = rowsTop - scrollOffset;
        int listW = rowRight - x1;
        for (int i = 0; i < rows.size(); i++, y += ROUTE_ROW_PITCH) {
            int rowTop = y;
            int rowBot = y + ROW_H + 2;
            if (rowBot < rowsTop || rowTop > y2) continue;

            boolean hovered = mouseX >= x1 + 2 && mouseX <= rowRight - 2
                    && mouseY >= rowTop && mouseY <= rowBot;
            RouteListRow row = rows.get(i);
            if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
            if (row.roomHeader) {
                renderRoomHeader(g, row, x1 + 2, rowTop, rowRight - 2, hovered);
            } else if (row.secretRoute) {
                renderSecretRouteRow(g, row, x1 + 2, rowTop, rowRight - 2, hovered);
            } else if (row.group != null) {
                boolean selected = selectedGroupIds.contains(row.group.id());
                renderGroupRow(g, row.group, i, x1 + 2, rowTop, rowRight - 2, listW,
                        hovered, selected, row.roomZoneId != null);
            }
        }
        g.disableScissor();
        renderListScrollbar(g, rowRight - 4, rowsTop, y2, rows.size(), scrollOffset);
    }

    static int mainContentRight(int mainLeft, int mainRight) {
        return Math.min(mainRight, mainLeft + MAIN_CONTENT_MAX_W);
    }

    private static int mainRowsTop(int panelTop) {
        return panelTop + 4 + BTN_H + GAP;
    }

    private void renderEmptyState(GuiGraphicsExtractor g, int x1, int y1) {
        int textX = x1 + GAP;
        if (!normalizedSearchQuery().isEmpty()) {
            g.text(font, "No routes match search.",
                    textX, y1 + 8, TEXT, false);
            g.text(font, "Clear the search field to show all routes.",
                    textX, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }
        if (isTemporaryZone(selectedZoneId)) {
            g.text(font, "No temporary waypoints.",
                    textX, y1 + 8, TEXT, false);
            g.text(font, "Chat coords and Add Temp markers will appear here.",
                    textX, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }
        if (isDungeonRoomsZone(selectedZoneId)) {
            g.text(font, "No dungeon room routes.",
                    textX, y1 + 8, TEXT, false);
            g.text(font, "Stand in a detected room, then click \"New Route\".",
                    textX, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }
        g.text(font, "No routes in this zone.",
                textX, y1 + 8, TEXT, false);
        g.text(font, "Click \"New Route\" to start, or paste a share code into chat.",
                textX, y1 + 8 + 14, TEXT_DIM, false);
    }

    private void flashMainNotice(String notice) {
        mainNotice = notice == null ? "" : notice;
        mainNoticeUntil = System.currentTimeMillis() + MAIN_NOTICE_MS;
    }

    private void renderMainNotice(GuiGraphicsExtractor g, int x1, int y1, int x2) {
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
        int rightLimit = downloadRoutesButton != null && downloadRoutesButton.visible
                ? downloadRoutesButton.getX() - GAP
                : x2 - GAP;
        int maxWidth = rightLimit - noticeX;
        if (maxWidth < 24) return;

        String clipped = font.plainSubstrByWidth(mainNotice, maxWidth);
        g.text(font, clipped, noticeX, y1 + 10, TEXT_DIM, false);
    }

    private void renderRoomHeader(GuiGraphicsExtractor g, RouteListRow row,
                                  int x1, int y1, int x2, boolean hovered) {
        int rowBot = y1 + ROW_H + 2;
        boolean selected = row.currentRoom && !hasSelectedGroupInRoom(row.roomZoneId);
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBot, bg);
        if (selected) g.fill(x1, y1, x1 + 2, rowBot, DUNGEON_ROOM_ACCENT);

        int markerX = x1 + GAP + 2;
        String marker = row.expanded ? "v" : ">";
        int textColor = row.currentRoom ? TEXT : TEXT_DIM;
        g.text(font, marker, markerX, y1 + 4, textColor, false);

        int labelX = markerX + font.width(marker) + 5;
        int labelMaxW = Math.max(24, x2 - GAP - labelX);
        String label = font.plainSubstrByWidth(displayZoneLabel(row.roomZoneId), labelMaxW);
        g.text(font, label, labelX, y1 + 4, textColor, false);

        String subtitle = roomHeaderSubtitle(row.roomRouteCount, row.roomSecretCount,
                row.currentRoom, row.searchReveal && !row.expanded);
        String clippedSubtitle = font.plainSubstrByWidth(subtitle, labelMaxW);
        g.text(font, clippedSubtitle, labelX, y1 + 14, TEXT_MUTED, false);
    }

    /**
     * Downloaded/authored room secrets are definitions, not live groups — the
     * route group materializes only while standing in the room. Surfacing the
     * installed secret count keeps a freshly imported library from reading as
     * "0 routes" everywhere.
     */
    static String roomHeaderSubtitle(int routeCount, int secretCount,
                                     boolean currentRoom, boolean searchOnly) {
        StringBuilder subtitle = new StringBuilder();
        subtitle.append(routeCount).append(" route").append(routeCount == 1 ? "" : "s");
        if (secretCount > 0) {
            subtitle.append("  ").append(secretCount)
                    .append(" secret").append(secretCount == 1 ? "" : "s");
        }
        if (currentRoom) subtitle.append("  current");
        if (searchOnly) subtitle.append("  search");
        return subtitle.toString();
    }

    private static int installedSecretCountForRoom(String roomZoneId) {
        DungeonRoomDefinition definition = DungeonRoomData.customDefinition(roomZoneId);
        return definition == null ? 0 : definition.waypoints().size();
    }

    /**
     * Definition-backed row for a room's installed secret route. Dungeon room
     * positions change every run, so unlike normal groups there are no world
     * coordinates to show — the row represents the imported data itself and
     * hands off to the live generated group once the player is in the room.
     */
    private void renderSecretRouteRow(GuiGraphicsExtractor g, RouteListRow row,
                                      int x1, int y1, int x2, boolean hovered) {
        int rowBot = y1 + ROW_H + 2;
        if (hovered) g.fill(x1, y1, x2, rowBot, HOVER);
        g.fill(x1 + GAP, y1 + 2, x1 + GAP + 2, rowBot - 2, DUNGEON_ROOM_ACCENT);

        int labelX = x1 + GAP + 8;
        String pts = row.roomSecretCount + " pt" + (row.roomSecretCount == 1 ? "" : "s");
        int ptsX = x2 - GAP - font.width(pts);
        g.text(font, pts, ptsX, y1 + 4, TEXT_MUTED, false);

        int labelMaxW = Math.max(24, ptsX - GAP_TIGHT - labelX);
        g.text(font, font.plainSubstrByWidth("Secret route", labelMaxW),
                labelX, y1 + 4, TEXT_DIM, false);
        g.text(font, font.plainSubstrByWidth(secretRouteSubtitle(row.secretSuppressed), labelMaxW),
                labelX, y1 + 14, TEXT_MUTED, false);
    }

    static String secretRouteSubtitle(boolean suppressedByUserRoute) {
        return suppressedByUserRoute
                ? "installed - your own route takes priority"
                : "shows in-room - double-click to edit";
    }

    /**
     * Turn the room's installed secret-route definition into a normal,
     * persisted route group (room-local coordinates; the dungeon sync projects
     * it into each run's room placement) and jump straight into the editor.
     * The definition itself is untouched — deleting the converted route later
     * brings the installed secrets back.
     */
    private void convertSecretRouteToEditable(String roomZoneId) {
        DungeonRoomDefinition definition = DungeonRoomData.customDefinition(roomZoneId);
        if (definition == null || definition.waypoints().isEmpty()) return;
        WaypointGroup route = DungeonRoomRouteSync.editableRouteFromDefinition(definition);
        manager.add(route);
        expandedDungeonRoomZoneIds.add(roomZoneId);
        selectOnlyGroupId(route.id());
        DebugEventLog.record("WaypointerScreen", "secret-route", roomZoneId,
                -1, "(none)", route.id(), true, false, false,
                "secret-route-row", "convert-to-editable");
        minecraft.setScreen(new GroupEditScreen(this, manager, config, route));
    }

    private boolean hasSelectedGroupInRoom(String roomZoneId) {
        if (roomZoneId == null || selectedGroupIds.isEmpty()) return false;
        for (String selectedId : selectedGroupIds) {
            WaypointGroup group = manager.get(selectedId);
            if (group != null && roomZoneId.equals(group.zoneId())) return true;
        }
        return false;
    }

    private void renderListScrollbar(GuiGraphicsExtractor g, int x, int y1, int y2,
                                     int rowCount, int currentScrollOffset) {
        int viewportHeight = y2 - y1;
        int contentHeight = rowCount * ROUTE_ROW_PITCH;
        if (viewportHeight <= 0 || contentHeight <= viewportHeight) return;

        int thumbHeight = Math.max(12, viewportHeight * viewportHeight / contentHeight);
        int maxScroll = maxMainScroll(rowCount, viewportHeight);
        int travel = viewportHeight - thumbHeight;
        if (maxScroll <= 0 || travel <= 0) return;

        int thumbY = y1 + currentScrollOffset * travel / maxScroll;
        g.fill(x, y1 + 2, x + 2, y2 - 2, BORDER);
        g.fill(x, thumbY, x + 2, thumbY + thumbHeight, TEXT_MUTED);
    }

    private void renderGroupRow(GuiGraphicsExtractor g, WaypointGroup group, int index,
                                int x1, int y1, int x2, int listW,
                                boolean hovered, boolean selected, boolean dungeonRoomChild) {
        int rowBot = y1 + ROW_H + 2;
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBot, bg);
        int accent = group.temp() ? TEMPORARY_ACCENT
                : isDungeonRoomZone(group.zoneId()) ? DUNGEON_ROOM_ACCENT
                : ACCENT;
        if (selected) g.fill(x1, y1, x1 + 2, rowBot, accent);

        int chipX = routeToggleChipX(x2);
        int textX = routeRowTextX(x1, dungeonRoomChild);
        int textMaxW = Math.max(12, chipX - GAP - textX);
        int textColor = group.enabled() ? TEXT : TEXT_MUTED;
        String name = displayGroupName(group);
        g.text(font, font.plainSubstrByWidth(name, textMaxW), textX, y1 + 4,
                textColor, false);

        String sub = routeRowSubtitle(group);
        g.text(font, font.plainSubstrByWidth(sub, textMaxW), textX, y1 + 14,
                TEXT_DIM, false);

        // Right-aligned toggle pill -- kept as the one exception to "no button chrome",
        // because it's genuinely a tap target with two states and the pill shape
        // communicates that more clearly than a checkbox in a dense row.
        String toggle = routeToggleLabel(group.enabled());
        int chipY = y1 + 5;
        int chipColor = group.enabled() ? 0xFF2D7A2D : 0xFF555555;
        g.fill(chipX, chipY, chipX + ROUTE_TOGGLE_CHIP_W,
                chipY + ROUTE_TOGGLE_CHIP_H, chipColor);
        int tw = font.width(toggle);
        g.text(font, toggle, chipX + (ROUTE_TOGGLE_CHIP_W - tw) / 2,
                chipY + 3, 0xFFFFFFFF, false);

        // Cross-zone hint (rare, but possible if a group's zone id drifts)
        String zid = group.zoneId();
        if (!group.temp() && !zid.equals(selectedZoneId) && !isDungeonRoomsZone(selectedZoneId)) {
            String hint = "(" + displayZoneLabel(zid) + ")";
            String clippedHint = font.plainSubstrByWidth(hint, Math.max(0, chipX - GAP - textX));
            g.text(font, clippedHint, chipX - GAP - font.width(clippedHint), y1 + 10,
                    TEXT_MUTED, false);
        }
    }

    static int routeToggleChipX(int rowRight) {
        return rowRight - ROUTE_TOGGLE_CHIP_W - GAP;
    }

    static int routeToggleHitLeft(int rowRight) {
        return routeToggleChipX(rowRight) - ROUTE_TOGGLE_HIT_PAD;
    }

    static int routeRowTextX(int rowLeft, boolean dungeonRoomChild) {
        return rowLeft + GAP + 2 + (dungeonRoomChild ? DUNGEON_ROUTE_CHILD_INDENT : 0);
    }

    static String routeToggleLabel(boolean enabled) {
        return enabled ? "Shown" : "Hidden";
    }

    /**
     * Empty routes previously rendered "0 pts 0 pts sequenced" (size and
     * progress summary both report zero); tell the user what to do instead.
     */
    String routeRowSubtitle(WaypointGroup group) {
        if (group.temp()) {
            return group.size() + " temp pts  " + displayZoneLabel(group.zoneId());
        }
        if (group.isEmpty()) {
            return "empty - select and press Edit to add waypoints";
        }
        return group.size() + " pts  " + RouteProgress.summary(group)
                + "  " + loadModeLabel(group);
    }

    private static String displayGroupName(WaypointGroup group) {
        String name = group.name().trim();
        if (!group.temp()) return name.isEmpty() ? "(unnamed)" : name;
        if (name.isEmpty() || name.startsWith("Temp --")) return TEMPORARY_ZONE_LABEL;
        return name;
    }

    // --- input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        boolean leftClick = event.button() == MOUSE_BUTTON_LEFT;
        boolean rightClick = event.button() == MOUSE_BUTTON_RIGHT;
        if (!leftClick && !rightClick) return false;

        double mx = event.x();
        double my = event.y();

        Layout layout = layout();

        // Sidebar click -> select zone
        if (leftClick && mx >= layout.sidebarLeft() && mx <= layout.sidebarRight()
                && my >= layout.top() && my <= layout.bottom()) {
            int rowY = sidebarRowsTop(layout.top());
            int rowsBottom = layout.bottom() - GAP_TIGHT;
            if (my >= rowY && my < rowsBottom) {
                List<String> ids = zoneIds();
                sidebarScrollOffset = MathUtil.clamp(sidebarScrollOffset, 0,
                        maxSidebarScroll(ids.size(), rowsBottom - rowY));
                int idx = sidebarIndexAt(my, rowY, rowsBottom, sidebarScrollOffset, ids.size());
                if (idx >= 0 && idx < ids.size()) {
                    selectedZoneId = ids.get(idx);
                    scrollOffset = 0;
                    clearRouteSelection();
                    refreshActionButtons();
                }
            }
            return true;
        }

        // Main area click -> select group row (and toggle chip if within the right edge)
        if (mx < layout.mainLeft() || mx > layout.mainRight()
                || my < layout.top() || my > layout.bottom()) return false;

        List<RouteListRow> rows = routeListRows();
        if (rows.isEmpty()) return false;
        int rowsTop = mainRowsTop(layout.top());
        if (my < rowsTop) return false;

        double yInList = my - rowsTop + scrollOffset;
        int idx = (int) (yInList / ROUTE_ROW_PITCH);
        if (idx < 0 || idx >= rows.size()) return false;
        int rowTop = rowsTop - scrollOffset + idx * ROUTE_ROW_PITCH;
        int rowBottom = rowTop + ROW_H + 2;
        if (my > rowBottom) return false;

        RouteListRow row = rows.get(idx);
        if (row.secretRoute) {
            if (rightClick) return false;
            if (doubleClick && !row.secretSuppressed) {
                convertSecretRouteToEditable(row.roomZoneId);
                return true;
            }
            flashMainNotice(row.secretSuppressed
                    ? "Your own route in " + displayZoneLabel(row.roomZoneId)
                            + " takes priority over the installed secrets."
                    : "Shows in-world inside " + displayZoneLabel(row.roomZoneId)
                            + ". Double-click to edit as your own route.");
            return true;
        }
        if (row.roomHeader) {
            if (rightClick) return false;
            String selectedBefore = selectedGroupId == null ? "(none)" : selectedGroupId;
            toggleDungeonRoomSection(row.roomZoneId);
            clearRouteSelection();
            int listHeight = layout.bottom() - rowsTop;
            scrollOffset = MathUtil.clamp(scrollOffset, 0,
                    maxMainScroll(routeListRows().size(), listHeight));
            refreshActionButtons();
            DebugEventLog.record("WaypointerScreen", "room-header", row.roomZoneId,
                    idx, selectedBefore, "(none)", doubleClick,
                    routeSelectionShiftDown(), routeSelectionControlDown(),
                    "room-header", "toggle-section");
            return true;
        }

        WaypointGroup group = row.group;
        if (group == null) return false;
        String selectedBefore = selectedGroupId == null ? "(none)" : selectedGroupId;
        if (rightClick) {
            hideAllRoutesInSelectedZone();
            String selectedAfter = selectedGroupId == null ? "(none)" : selectedGroupId;
            DebugEventLog.record("WaypointerScreen", "route", group.id(), idx,
                    selectedBefore, selectedAfter, doubleClick, false, false,
                    "route-row", "hide-all-routes");
            return true;
        }
        boolean wasAlreadyPrimarySelected = group.id().equals(selectedGroupId)
                && selectedGroupIds.contains(group.id());
        boolean shiftDown = routeSelectionShiftDown();
        boolean controlDown = routeSelectionControlDown();
        applyRouteRowSelection(group, rows);
        refreshActionButtons();
        String selectedAfter = selectedGroupId == null ? "(none)" : selectedGroupId;

        // Toggle-chip hit test -- rightmost region of the (capped) row.
        int rowRight = mainContentRight(layout.mainLeft(), layout.mainRight()) - 2;
        if (mx >= routeToggleHitLeft(rowRight) && mx <= rowRight) {
            group.setEnabled(!group.enabled());
            manager.fireDataChanged();
            DebugEventLog.record("WaypointerScreen", "route", group.id(), idx,
                    selectedBefore, selectedAfter, doubleClick, shiftDown, controlDown,
                    "toggle-chip", group.enabled() ? "show-route" : "hide-route");
            return true;
        }

        boolean openEditor = shouldOpenGroupEditorFromRouteDoubleClick(
                doubleClick, wasAlreadyPrimarySelected, shiftDown, controlDown);
        if (openEditor) {
            DebugEventLog.record("WaypointerScreen", "route", group.id(), idx,
                    selectedBefore, selectedAfter, true, false, false,
                    "route-row", "open-editor");
            minecraft.setScreen(new GroupEditScreen(this, manager, config, group));
        } else {
            DebugEventLog.record("WaypointerScreen", "route", group.id(), idx,
                    selectedBefore, selectedAfter, doubleClick, shiftDown, controlDown,
                    "route-row", doubleClick
                            ? "double-click ignored: route was not already primary selected"
                            : "select");
        }
        return true;
    }

    static boolean shouldOpenGroupEditorFromRouteDoubleClick(boolean doubleClick,
                                                            boolean wasAlreadyPrimarySelected,
                                                            boolean shiftDown,
                                                            boolean controlDown) {
        return doubleClick && wasAlreadyPrimarySelected && !shiftDown && !controlDown;
    }

    private static void toggleDungeonRoomSection(String roomZoneId) {
        if (!isDungeonRoomZone(roomZoneId)) return;
        if (!expandedDungeonRoomZoneIds.remove(roomZoneId)) {
            expandedDungeonRoomZoneIds.add(roomZoneId);
        }
    }

    private void applyRouteRowSelection(WaypointGroup group, List<RouteListRow> rows) {
        if (group == null) return;
        List<String> visibleGroupIds = visibleGroupIds(rows);
        String clickedGroupId = group.id();
        boolean shiftDown = routeSelectionShiftDown();
        boolean controlDown = routeSelectionControlDown();
        LinkedHashSet<String> nextSelection = routeSelectionAfterClick(
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
            selectedGroupId = firstVisibleSelectedGroupId(visibleGroupIds, selectedGroupIds);
        }
    }

    private static boolean routeSelectionShiftDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, 344 /* GLFW_KEY_RIGHT_SHIFT */);
    }

    private static boolean routeSelectionControlDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var window = mc.getWindow();
        return InputConstants.isKeyDown(window, 341 /* GLFW_KEY_LEFT_CONTROL */)
                || InputConstants.isKeyDown(window, 345 /* GLFW_KEY_RIGHT_CONTROL */);
    }

    static LinkedHashSet<String> routeSelectionAfterClick(List<String> visibleGroupIds,
                                                          Set<String> previousSelectionIds,
                                                          String anchorGroupId,
                                                          String clickedGroupId,
                                                          boolean controlDown,
                                                          boolean shiftDown) {
        LinkedHashSet<String> orderedVisibleIds = new LinkedHashSet<>();
        if (visibleGroupIds != null) {
            for (String id : visibleGroupIds) {
                if (id != null) orderedVisibleIds.add(id);
            }
        }

        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (clickedGroupId == null || clickedGroupId.isBlank()) return next;
        if (!orderedVisibleIds.contains(clickedGroupId)) {
            next.add(clickedGroupId);
            return next;
        }

        if (shiftDown) {
            List<String> ordered = new ArrayList<>(orderedVisibleIds);
            String rangeAnchor = orderedVisibleIds.contains(anchorGroupId)
                    ? anchorGroupId
                    : clickedGroupId;
            int anchorIndex = ordered.indexOf(rangeAnchor);
            int clickedIndex = ordered.indexOf(clickedGroupId);
            int start = Math.min(anchorIndex, clickedIndex);
            int end = Math.max(anchorIndex, clickedIndex);
            for (int i = start; i <= end; i++) {
                next.add(ordered.get(i));
            }
            return next;
        }

        if (controlDown) {
            if (previousSelectionIds != null) {
                for (String id : orderedVisibleIds) {
                    if (previousSelectionIds.contains(id)) next.add(id);
                }
            }
            if (!next.remove(clickedGroupId)) {
                next.add(clickedGroupId);
            }
            return next;
        }

        next.add(clickedGroupId);
        return next;
    }

    private static List<String> visibleGroupIds(List<RouteListRow> rows) {
        List<String> ids = new ArrayList<>();
        if (rows == null) return ids;
        for (RouteListRow row : rows) {
            if (row == null || row.roomHeader || row.group == null) continue;
            ids.add(row.group.id());
        }
        return ids;
    }

    private static String firstVisibleSelectedGroupId(List<String> visibleGroupIds, Set<String> selectedIds) {
        if (visibleGroupIds == null || selectedIds == null) return null;
        for (String id : visibleGroupIds) {
            if (selectedIds.contains(id)) return id;
        }
        return null;
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

    private void selectOnlyGroupId(String groupId) {
        if (groupId == null) {
            clearRouteSelection();
            return;
        }
        LinkedHashSet<String> singleton = new LinkedHashSet<>();
        singleton.add(groupId);
        replaceRouteSelection(singleton);
        selectionAnchorGroupId = groupId;
    }

    private void clearRouteSelection() {
        selectedGroupIds.clear();
        selectedGroupId = null;
        selectionAnchorGroupId = null;
        clearHideAllConfirmation();
        clearDeleteConfirmation();
    }

    private void clearHideAllConfirmation() {
        hideAllArmedUntil = 0L;
        hideAllArmedGroupIds.clear();
        resetHideAllRoutesButton();
    }

    private void clearDeleteConfirmation() {
        deleteArmedUntil = 0L;
        deleteArmedGroupIds.clear();
    }

    @Override
    public void onClose() {
        rememberSelectedZone();
        super.onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        Layout layout = layout();
        if (mouseX >= layout.sidebarLeft() && mouseX <= layout.sidebarRight()
                && mouseY >= layout.top() && mouseY <= layout.bottom()) {
            int rowsTop = sidebarRowsTop(layout.top());
            int listHeight = layout.bottom() - GAP_TIGHT - rowsTop;
            sidebarScrollOffset = sidebarScrollAfterWheel(sidebarScrollOffset, vert,
                    zoneIds().size(), listHeight);
            return true;
        }
        int rowsTop = mainRowsTop(layout.top());
        int listHeight = layout.bottom() - rowsTop;
        int maxScroll = maxMainScroll(routeListRows().size(), listHeight);
        scrollOffset = MathUtil.clamp(scrollOffset - (int) (vert * ROUTE_ROW_PITCH), 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (super.keyPressed(event)) return true;
        if (event.key() == GLFW_KEY_DELETE) {
            onDeleteClicked();
            return true;
        }
        return false;
    }

    // --- actions -----------------------------------------------------------------------------

    private void rememberSelectedZone() {
        if (selectedZoneId != null && zoneIds().contains(selectedZoneId)) {
            lastSelectedZoneId = selectedZoneId;
            lastCurrentZoneIdWhenRemembered = currentZoneId(manager);
        }
    }

    private void createGroup() {
        Zone current = manager.currentZone();
        String currentZoneId = current == null ? null : current.id();
        String targetZoneId = newRouteTargetZoneId(selectedZoneId, currentZoneId);
        if (targetZoneId == null) {
            flashMainNotice(newRouteBlockedNotice(selectedZoneId));
            return;
        }
        WaypointGroup g = WaypointGroup.create(
                nextRouteName(manager.groupsForZone(targetZoneId)),
                targetZoneId, config.skipAheadMechanicEnabled());
        g.setDefaultRadius(config.defaultReachRadius());
        manager.add(g);
        selectedZoneId = sidebarSelectionForZoneId(targetZoneId);
        if (isDungeonRoomZone(targetZoneId)) expandedDungeonRoomZoneIds.add(targetZoneId);
        selectOnlyGroupId(g.id());
        minecraft.setScreen(new GroupEditScreen(this, manager, config, g));
    }

    /**
     * Smallest free "Route N" in the zone, so two fresh routes never share a
     * name and a row is identifiable without opening it.
     */
    static String nextRouteName(Iterable<WaypointGroup> zoneGroups) {
        Set<String> taken = new HashSet<>();
        for (WaypointGroup group : zoneGroups) {
            taken.add(group.name().trim());
        }
        for (int n = 1; ; n++) {
            String candidate = "Route " + n;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    static String newRouteTargetZoneId(String selectedZoneId, String currentZoneId) {
        if (isTemporaryZone(selectedZoneId)) {
            return currentZoneId == null ? Zone.UNKNOWN.id() : currentZoneId;
        }
        if (isDungeonRoomsZone(selectedZoneId)) {
            return isDungeonRoomZone(currentZoneId) ? currentZoneId : null;
        }
        return selectedZoneId == null ? Zone.UNKNOWN.id() : selectedZoneId;
    }

    static String newRouteBlockedNotice(String selectedZoneId) {
        if (isDungeonRoomsZone(selectedZoneId)) {
            return "Stand in a detected dungeon room to create a room route.";
        }
        return "Choose a route zone first.";
    }

    private void editSelected() {
        WaypointGroup g = currentSelection();
        if (g != null) minecraft.setScreen(new GroupEditScreen(this, manager, config, g));
    }

    private void onHideAllRoutesClicked() {
        List<WaypointGroup> shownRoutes = shownRoutesForSelectedZone();
        if (shownRoutes.isEmpty()) {
            clearHideAllConfirmation();
            refreshActionButtons();
            return;
        }

        long now = System.currentTimeMillis();
        if (!hideAllConfirmationMatches(shownRoutes, hideAllArmedGroupIds, now, hideAllArmedUntil)) {
            hideAllArmedUntil = now + CONFIRM_WINDOW_MS;
            hideAllArmedGroupIds.clear();
            hideAllArmedGroupIds.addAll(routeIds(shownRoutes));
            if (hideAllRoutesBtn != null) {
                int count = shownRoutes.size();
                hideAllRoutesBtn.setMessage(Component.literal(CONFIRM_LABEL));
                hideAllRoutesBtn.setTooltip(Tooltip.create(Component.literal(
                        "Click again to hide " + count + " shown route" + (count == 1 ? "" : "s") + ".")));
            }
            return;
        }

        hideShownRoutes(shownRoutes);
    }

    private void hideAllRoutesInSelectedZone() {
        hideShownRoutes(shownRoutesForSelectedZone());
    }

    private void hideShownRoutes(List<WaypointGroup> shownRoutes) {
        int hidden = hideRoutes(shownRoutes);
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

    private void refreshActionButtons() {
        if (editBtn != null) editBtn.active = currentSelection() != null;
        if (hideAllRoutesBtn != null) {
            boolean hasShownRoutes = !shownRoutesForSelectedZone().isEmpty();
            hideAllRoutesBtn.active = hasShownRoutes;
            if (!hasShownRoutes) clearHideAllConfirmation();
        }
    }

    static boolean hideAllConfirmationMatches(List<WaypointGroup> shownRoutes,
                                              Set<String> armedGroupIds,
                                              long now,
                                              long armedUntil) {
        if (shownRoutes == null || shownRoutes.isEmpty() || armedGroupIds == null) return false;
        return now < armedUntil && routeIds(shownRoutes).equals(armedGroupIds);
    }

    static LinkedHashSet<String> routeIds(Collection<WaypointGroup> groups) {
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
            // Nothing selected. Don't silently no-op -- briefly borrow the button label
            // to tell the user what they need to do.
            flashDeleteLabel(NO_SEL_LABEL,
                    "Select a route from the list on the right first.");
            return;
        }
        LinkedHashSet<String> currentSelectedIds = new LinkedHashSet<>();
        for (WaypointGroup group : selectedGroups) {
            currentSelectedIds.add(group.id());
        }
        long now = System.currentTimeMillis();
        if (now < deleteArmedUntil && deleteArmedGroupIds.equals(currentSelectedIds)) {
            // Second click inside the confirm window -- commit.
            deleteGroups(selectedGroups);
            clearRouteSelection();
            Layout layout = layout();
            int rowsTop = mainRowsTop(layout.top());
            int listHeight = layout.bottom() - rowsTop;
            scrollOffset = MathUtil.clamp(scrollOffset, 0,
                    maxMainScroll(routeListRows().size(), listHeight));
            refreshActionButtons();
            resetDeleteButton();
            return;
        }
        // First click -- arm. render() resets the label after the confirm window elapses.
        // Group name lives in the tooltip (which wraps freely) so the button stays a
        // fixed width and the dangerous state is discoverable on hover.
        deleteArmedUntil = now + CONFIRM_WINDOW_MS;
        deleteArmedGroupIds.clear();
        deleteArmedGroupIds.addAll(currentSelectedIds);
        if (deleteBtn != null) {
            String targetText = selectedGroups.size() == 1
                    ? "\"" + selectedGroups.get(0).name() + "\""
                    : selectedGroups.size() + " selected routes";
            deleteBtn.setMessage(Component.literal(CONFIRM_LABEL));
            deleteBtn.setTooltip(Tooltip.create(Component.literal(
                    "Double click to permanently delete " + targetText + ".")));
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
        if (!DungeonRoomRouteSync.isGeneratedGroup(group)) return false;
        DungeonRoomData.clearWaypoints(group.zoneId());
        return true;
    }

    private void resetDeleteButton() {
        if (deleteBtn == null) return;
        deleteBtn.setMessage(Component.literal(DELETE_LABEL));
        deleteBtn.setTooltip(Tooltip.create(Component.literal(DELETE_TOOLTIP_DEFAULT)));
    }

    private void resetHideAllRoutesButton() {
        if (hideAllRoutesBtn == null) return;
        hideAllRoutesBtn.setMessage(Component.literal(HIDE_ALL_ROUTES_LABEL));
        hideAllRoutesBtn.setTooltip(Tooltip.create(Component.literal(HIDE_ALL_ROUTES_TOOLTIP_DEFAULT)));
    }

    private long labelFlashUntil = 0L;

    private void flashDeleteLabel(String msg, String tooltipText) {
        if (deleteBtn == null) return;
        deleteBtn.setMessage(Component.literal(msg));
        deleteBtn.setTooltip(Tooltip.create(Component.literal(tooltipText)));
        labelFlashUntil = System.currentTimeMillis() + 1500L;
    }

    private WaypointGroup currentSelection() {
        List<WaypointGroup> selectedGroups = selectedVisibleGroups();
        return selectedGroups.size() == 1 ? selectedGroups.get(0) : null;
    }

    private List<WaypointGroup> selectedVisibleGroups() {
        List<WaypointGroup> selectedGroups = new ArrayList<>();
        if (selectedGroupIds.isEmpty()) return selectedGroups;
        for (RouteListRow row : routeListRows()) {
            if (!row.roomHeader && row.group != null && selectedGroupIds.contains(row.group.id())) {
                selectedGroups.add(row.group);
            }
        }
        return selectedGroups;
    }

    private static String loadModeLabel(WaypointGroup group) {
        return group.loadMode() == WaypointGroup.LoadMode.SEQUENCE ? "sequenced" : "static";
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

        List<WaypointGroup> groups = exportGroupsForSelection(selectedGroups, visibleGroups());
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
        if (selectedGroups.size() == 1) {
            ExportScreen.openForGroup(this, config, selectedGroups.get(0));
            return;
        }
        if (selectedGroups.size() > 1) {
            ExportScreen.openForGroups(this, config, selectedGroups, "Selected routes");
            return;
        }
        if (!routeGroups.isEmpty()) {
            ExportScreen.openForGroups(this, config, routeGroups, displayZoneLabel(selectedZoneId));
            return;
        }

        List<DungeonRoomDefinition> definitions =
                dungeonDefinitionsForExport(DungeonRoomData.customDefinitions());
        if (definitions.isEmpty()) {
            flashMainNotice(emptyExportNotice(selectedZoneId));
            return;
        }

        String payload = DungeonRoomShareCodec.encode(definitions);
        DungeonRoomExportScreen.open(this, payload, definitions.size(), dungeonWaypointCount(definitions));
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

    static List<DungeonRoomDefinition> dungeonDefinitionsForExport(
            Collection<DungeonRoomDefinition> definitions) {
        if (definitions == null) return List.of();
        List<DungeonRoomDefinition> out = new ArrayList<>();
        for (DungeonRoomDefinition definition : definitions) {
            if (definition != null && !definition.waypoints().isEmpty()) out.add(definition);
        }
        out.sort(Comparator
                .comparing((DungeonRoomDefinition d) -> d.displayName().toLowerCase(Locale.ROOT))
                .thenComparing(DungeonRoomDefinition::id));
        return out;
    }

    static int dungeonWaypointCount(Collection<DungeonRoomDefinition> definitions) {
        return DungeonRoomShareCodec.waypointCount(definitions);
    }

    static String emptyExportNotice(String selectedZoneId) {
        return "Nothing to export in " + displayZoneLabel(selectedZoneId) + ".";
    }

    private String importTargetZoneId() {
        Zone current = manager.currentZone();
        String currentZoneId = current == null ? null : current.id();
        return importTargetZoneId(selectedZoneId, currentZoneId);
    }

    static String importTargetZoneId(String selectedZoneId, String currentZoneId) {
        if (isTemporaryZone(selectedZoneId)) {
            return currentZoneId == null ? Zone.UNKNOWN.id() : currentZoneId;
        }
        if (isDungeonRoomsZone(selectedZoneId)) {
            return isDungeonRoomZone(currentZoneId) ? currentZoneId : Zone.UNKNOWN.id();
        }
        return selectedZoneId == null ? Zone.UNKNOWN.id() : selectedZoneId;
    }

    static void retargetUnknownImportedGroups(List<WaypointGroup> groups, String targetZoneId) {
        if (groups == null || Zone.UNKNOWN.id().equals(targetZoneId)) return;
        for (WaypointGroup group : groups) {
            if (group != null && Zone.UNKNOWN.id().equals(group.zoneId())) {
                group.setZoneId(targetZoneId);
            }
        }
    }

    private void importFromClipboard() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            ImportFeedback.failure("Clipboard is empty.");
            return;
        }
        try {
            if (DungeonRoomShareCodec.isPayload(text)) {
                importDungeonRoomsFromClipboard(text);
                return;
            }

            WaypointImporter.ImportResult result = WaypointImporter.importAny(text);
            // Retarget unknown-zone groups to the zone the user is actively
            // viewing, not the player's live position. Using selectedZoneId
            // matches intent better from the GUI: if the user navigated to
            // "The Park" and then pasted, that's where the import goes.
            String targetZoneId = importTargetZoneId();
            retargetUnknownImportedGroups(result.groups(), targetZoneId);
            RouteColorPolicy.applyImportedRouteDefaults(result.groups(), config);
            manager.addAll(result.groups());

            ImportFeedback.success(result.groups(), "clipboard");
            // Navigate the user to the first imported group so the import
            // result is visible immediately -- no more "did it work?" moments
            // where the user has to hunt through zone tabs.
            if (!result.groups().isEmpty()) {
                WaypointGroup first = result.groups().get(0);
                searchQuery = "";
                if (searchBox != null) searchBox.setValue("");
                selectedZoneId = sidebarSelectionForZoneId(first.zoneId());
                selectGroupById(first.id());
            }
        } catch (IllegalArgumentException e) {
            ImportFeedback.failure(e.getMessage());
        }
    }

    private void importDungeonRoomsFromClipboard(String text) {
        DungeonRoomShareCodec.Decoded decoded = DungeonRoomShareCodec.decode(text);
        int importedRooms = DungeonRoomData.importCustomDefinitions(decoded.definitions());
        if (importedRooms == 0) {
            throw new IllegalArgumentException("dungeon route import contained no rooms");
        }

        ImportFeedback.successDungeonRoutes(importedRooms, decoded.waypointCount(), "clipboard");
        searchQuery = "";
        if (searchBox != null) searchBox.setValue("");
        selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        if (!decoded.definitions().isEmpty()) {
            expandedDungeonRoomZoneIds.add(decoded.definitions().get(0).id());
        }
        clearRouteSelection();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
