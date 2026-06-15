package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.color.RouteColorPolicy;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.RouteProgress;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.ethan.waypointer.screen.GuiTokens.*;

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

    private static final String TEMPORARY_ZONE_ID = "__temporary__";
    private static final String TEMPORARY_ZONE_LABEL = "Temporary";
    private static final int TEMPORARY_ACCENT = 0xFF58C878;
    private static final String DUNGEON_ROOMS_ZONE_ID = "__dungeon_rooms__";
    private static final String DUNGEON_ROOMS_LABEL = "Dungeon Rooms";
    private static final String DUNGEON_ROOM_LABEL_PREFIX = "Dungeons: ";
    private static final int DUNGEON_ROOM_ACCENT = 0xFFFF8A8A;
    private static final URI ROUTE_DOWNLOADS_URI =
            URI.create("https://github.com/ethanrjs/waypointer/releases");
    private static String lastSelectedZoneId;
    private static String lastCurrentZoneIdWhenRemembered;
    private static boolean dungeonRoomsExpanded = true;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private String selectedZoneId;
    private int scrollOffset;
    private int selectedIndex = -1;
    private Button downloadRoutesBtn;
    /**
     * Group id the screen should focus on its next {@link #init()} pass --
     * set by {@link #openFocused} and consumed on first init. Nullable by design
     * so {@code init()} after window resize doesn't re-snap the scroll offset.
     */
    private String pendingFocusGroupId;

    // Delete uses a two-click confirm: first click arms, second within CONFIRM_WINDOW_MS
    // commits. A full modal would be more intrusive than this class of action warrants;
    // undo is cheap (re-add the group) but accidental taps shouldn't silently destroy data.
    //
    // The armed state reuses the same button label ("Confirm?") regardless of which group
    // is selected -- stuffing the group name into the label overflowed the button bounds
    // at long names, and the name belongs in the tooltip where wrapping is free.
    private static final long CONFIRM_WINDOW_MS = 2500L;
    private static final String DELETE_LABEL  = "Delete";
    private static final String CONFIRM_LABEL = "Confirm?";
    private static final String NO_SEL_LABEL  = "Pick group";
    private static final String DELETE_TOOLTIP_DEFAULT =
            "Remove the selected group permanently.\n"
          + "Double click to confirm.";
    // Sized for the widest transient state label ("Confirm?") so the button doesn't
    // visibly grow or shrink when arming/disarming. Leave some horizontal slack so
    // vanilla's "hover" narration arrow has room without clipping the text.
    private static final int DELETE_BTN_W = 72;
    private Button editBtn;
    private Button deleteBtn;
    private EditBox searchBox;
    private String searchQuery = "";
    private long deleteArmedUntil = 0L;

    private List<GuiTokens.ButtonSpec> footerActions() {
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("New Route", 92, this::createGroup));
        left.add(new GuiTokens.ButtonSpec("Edit", 64, this::editSelected));
        left.add(new GuiTokens.ButtonSpec("Import", 74, this::importFromClipboard));
        left.add(new GuiTokens.ButtonSpec("Export", 74, this::exportZone,
                Tooltip.create(Component.literal("Export all saved routes on an island"))));
        left.add(new GuiTokens.ButtonSpec("Settings", 88, this::openSettings));
        left.add(new GuiTokens.ButtonSpec(DELETE_LABEL, DELETE_BTN_W, this::onDeleteClicked));
        return left;
    }

    /*[[AI-FN-DOC
Function:
WaypointerScreen constructor.
Purpose:
Create the top-level route manager screen with the best initial zone already selected.
Why this exists:
The screen needs the shared route manager and config references plus an initial zone; keeping the zone selection decision here prevents callers from duplicating UI startup policy.
When to use:
Use whenever opening the normal Waypointer route manager. Do not use it for importing focus without also applying openFocused's pending group selection.
Inputs:
manager is the active group manager and must not be null; config is the live Waypointer configuration and must not be null.
Outputs:
Constructs a screen instance with selectedZoneId set to the remembered visible zone, the live detected zone, or a safe fallback.
Side effects:
No external side effects; stores object references and initial local UI state.
Failure modes:
Null inputs would fail later just as before; zone resolution tolerates missing current zone and empty route collections.
Important invariants:
selectedZoneId must always name a zone that zoneIdsForManager can surface, so the sidebar and main list stay in sync.
Internal logic:
Store constructor inputs, ask initialSelectedZoneId for the correct remembered/current/fallback id, and leave all widget creation to init.
Pseudocode:
Set title to Waypointer.
Store manager and config fields.
Resolve selectedZoneId through initialSelectedZoneId(manager).
Implementation notes:
The remembered zone is static and session-scoped; this matches reopening the menu after closing it without writing transient UI focus into the config file.
AI self-check:
Verify the constructor only establishes state, selectedZoneId is never left null, and init remains responsible for widget layout.
]]*/
    public WaypointerScreen(ActiveGroupManager manager, WaypointerConfig config) {
        super(Component.literal("Waypointer"));
        this.manager = manager;
        this.config = config;
        this.selectedZoneId = initialSelectedZoneId(manager);
    }

    public static void open(ActiveGroupManager manager, WaypointerConfig config) {
        Minecraft.getInstance().setScreen(new WaypointerScreen(manager, config));
    }

    /*[[AI-FN-DOC
Function:
openFocused.
Purpose:
Open the top-level editor with a specific route group selected and visible.
Why this exists:
Import and creation flows should land the user on the affected route immediately instead of leaving them to search through zones manually.
When to use:
Use after a route group has just been imported or otherwise needs to be highlighted in the main route manager. Do not use when there is no specific group to focus; use open instead.
Inputs:
manager is the active group manager; config is the live Waypointer config; focus may be null, temporary, a normal island group, or a dungeon room group.
Outputs:
No return value; opens a new WaypointerScreen instance in Minecraft.
Side effects:
Mutates Minecraft's current screen, seeds pendingFocusGroupId, and may map a focused dungeon room group to the virtual Dungeon Rooms parent when the room bucket is collapsed.
Failure modes:
If focus is null, the screen opens normally. If the focused group is later missing, init's selectGroupById no-ops safely.
Important invariants:
Temporary groups select the Temporary bucket, dungeon room groups stay findable even when room children are collapsed, and normal groups keep their exact zone id.
Internal logic:
Create a screen, map focus to the visible sidebar selection, store the group id for init-time selection, and set the screen.
Pseudocode:
screen = new WaypointerScreen(manager, config)
if focus exists:
  if focus is temp, selectedZoneId = Temporary
  else selectedZoneId = sidebarSelectionForZoneId(focus.zoneId)
  pendingFocusGroupId = focus.id
set Minecraft screen to screen
Implementation notes:
The actual selectedIndex is resolved in init because visibleGroups depends on final widget/screen state.
AI self-check:
Verify collapsed dungeon room groups remain discoverable through the parent bucket and no route data is mutated.
]]*/
    public static void openFocused(ActiveGroupManager manager, WaypointerConfig config,
                                   WaypointGroup focus) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        if (focus != null) {
            // Select by id rather than by index -- index lookups into
            // visibleGroups() are fragile when groups added mid-list shift
            // indices. The init() pass will resolve the id to a current
            // selectedIndex after it knows the list ordering for the zone.
            screen.selectedZoneId = focus.temp()
                    ? TEMPORARY_ZONE_ID
                    : sidebarSelectionForZoneId(focus.zoneId());
            screen.pendingFocusGroupId = focus.id();
        }
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    /*[[AI-FN-DOC
Function:
init.
Purpose:
Build and position all interactive controls for the Waypointer route manager.
Why this exists:
Minecraft recreates screen widgets during screen setup and resize, so this method centralizes button, search, and focus initialization.
When to use:
Called by Minecraft when the screen opens or is reinitialized after size changes. Do not call manually for route data changes; use rebuild or state-specific refresh methods instead.
Inputs:
No explicit parameters; reads current screen width, height, manager state, and stored UI selection fields.
Outputs:
Registers footer buttons, the search field, and the route download button with this screen.
Side effects:
Mutates widget fields, clears transient delete confirmation state, registers renderable widgets, may consume pendingFocusGroupId, and refreshes button enabled state.
Failure modes:
Tiny screen sizes can constrain widgets, but layout helpers clamp widths so controls remain usable instead of throwing.
Important invariants:
downloadRoutesBtn and searchBox geometry must be synchronized before rendering; pending focus is consumed once so resize does not snap selection repeatedly.
Internal logic:
Clear transient widget references, lay out footer buttons, create search and download widgets, resolve any pending import focus, then refresh action enabled states.
Pseudocode:
Compute footer y.
Reset delete and widget fields.
Create footer button specs and add built widgets, saving Edit/Delete references.
Create search box, restore query, attach responder, sync geometry, add widget.
Create Download Routes button, sync geometry, add widget.
If pending focus exists, select that group and clear the pending id.
Refresh action buttons.
Implementation notes:
The route button is also rendered manually after custom panels because Screen.super renders widgets before this screen paints its sidebar surface.
AI self-check:
Verify every widget field is either null or points at the current init pass's widget, and pending focus remains one-shot.
]]*/
    protected void init() {
        int footerY = height - FOOTER_H;
        deleteArmedUntil = 0L;
        editBtn = null;
        deleteBtn = null;
        searchBox = null;
        downloadRoutesBtn = null;

        // Fixed width so the label can toggle between "Delete" and "Confirm?" without
        // the footer re-flowing or the text sliding past the bevel.
        List<GuiTokens.ButtonSpec> left = footerActions();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);

        // We need a reference to the Delete button so we can repaint its label when it
        // arms/disarms. Intercept every built button and stash Delete; addRenderableWidget
        // still runs for all of them.
        GuiTokens.layoutFooter(width, footerY, left, done, b -> {
            if ("Edit".contentEquals(b.getMessage().getString())) {
                editBtn = b;
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

        downloadRoutesBtn = Button.builder(Component.literal("Download Routes"), this::openRouteDownloads)
                .bounds(0, 0, SIDEBAR_W - GAP * 2, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Open Waypointer route downloads in your browser.")))
                .build();
        syncDownloadRoutesButtonGeometry();
        addRenderableWidget(downloadRoutesBtn);

        // Resolve a pending focus request from openFocused(). We do this here
        // rather than in the constructor because the zone's group list can
        // only be meaningfully indexed after the screen knows its current
        // zone -- the visibleGroups() list is keyed off selectedZoneId, which
        // is settled by the time init() runs.
        if (pendingFocusGroupId != null) {
            selectGroupById(pendingFocusGroupId);
            pendingFocusGroupId = null;
        }
        refreshActionButtons();
    }

    /*[[AI-FN-DOC
Function:
initialSelectedZoneId.
Purpose:
Choose which zone the route manager should show when a fresh screen instance opens.
Why this exists:
The screen remembers the last zone a user had open, but switching islands should make the live current island win over stale UI memory.
When to use:
Use during construction only. Do not use while the screen is already open because it can intentionally prefer an older static selection over the user's current click.
Inputs:
manager is the active group manager used to inspect known zones and current detected zone.
Outputs:
Returns a non-null zone id that can be displayed in the sidebar.
Side effects:
None.
Failure modes:
If no remembered or current zone is visible, falls back to the first computed sidebar zone or UNKNOWN as a final guard.
Important invariants:
Returned ids must come from zoneIdsForManager when possible so selection, counts, and click hit testing agree.
Internal logic:
Build the sidebar zone list, compute the current zone id, map it through sidebarSelectionForZoneId for collapsed dungeon rooms, prefer that visible current selection when the live island changed since the remembered selection, otherwise prefer the remembered id, then use current/fallback ordering.
Pseudocode:
Compute ids with zoneIdsForManager.
Compute currentZoneId as current zone id or UNKNOWN.
Compute fallback as sidebarSelectionForZoneId(currentZoneId).
If rememberedCurrentZoneChanged returns true and ids contains fallback, return fallback.
If lastSelectedZoneId is in ids, return it.
If ids contains fallback, return it.
If ids is not empty, return its first element.
Return UNKNOWN.
Implementation notes:
This deliberately does not persist to config; it is a UI affordance for reopening the menu during a play session while respecting island switches.
AI self-check:
Verify null current zones and empty managers still produce a stable id.
]]*/
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

    /*[[AI-FN-DOC
Function:
currentZoneId.
Purpose:
Return the live island zone id used for menu-open selection decisions.
Why this exists:
Remembered sidebar state must be compared against the island the player is currently on, and null current-zone detection needs one stable fallback.
When to use:
Use when deciding whether the screen should honor remembered sidebar state or open to the current island.
Inputs:
manager is the active group manager whose currentZone may be null.
Outputs:
Returns the current zone id, or Zone.UNKNOWN.id() when no current zone is detected.
Side effects:
None.
Failure modes:
None expected; null current zone is explicitly handled.
Important invariants:
The fallback must match the constructor's historical unknown-zone behavior so non-SkyBlock or unresolved states remain predictable.
Internal logic:
Read manager.currentZone and return its id when present, otherwise UNKNOWN.
Pseudocode:
current = manager.currentZone
if current is null return UNKNOWN
return current.id
Implementation notes:
Keeping this as a helper prevents close/open comparison code from drifting around null current-zone handling.
AI self-check:
Verify null current zones do not throw and use the same UNKNOWN id as the sidebar.
]]*/
    private static String currentZoneId(ActiveGroupManager manager) {
        Zone current = manager.currentZone();
        return current == null ? Zone.UNKNOWN.id() : current.id();
    }

    /*[[AI-FN-DOC
Function:
rememberedCurrentZoneChanged.
Purpose:
Report whether the live current island differs from the island context that produced the remembered sidebar zone.
Why this exists:
The user wants reopening within the same island to preserve their last viewed zone, but reopening after switching islands should start on the new current island.
When to use:
Use during initialSelectedZoneId before applying lastSelectedZoneId.
Inputs:
currentZoneId is the currently detected zone id or UNKNOWN fallback.
Outputs:
Returns true when a previous remembered context exists and differs from currentZoneId.
Side effects:
None.
Failure modes:
Null currentZoneId is normalized to UNKNOWN for comparison.
Important invariants:
No remembered context means no island switch has been observed, so this returns false and normal fallback logic handles first open.
Internal logic:
Normalize currentZoneId, check that lastCurrentZoneIdWhenRemembered is non-null, and compare strings.
Pseudocode:
normalized = currentZoneId == null ? UNKNOWN : currentZoneId
return lastCurrentZoneIdWhenRemembered != null and not equal to normalized
Implementation notes:
This keeps the remembered selected zone valid only inside the same live island context.
AI self-check:
Verify first opens and same-island reopens still honor remembered sidebar state.
]]*/
    private static boolean rememberedCurrentZoneChanged(String currentZoneId) {
        String normalized = currentZoneId == null ? Zone.UNKNOWN.id() : currentZoneId;
        return lastCurrentZoneIdWhenRemembered != null
                && !lastCurrentZoneIdWhenRemembered.equals(normalized);
    }

    /**
     * Point the selection at the group with {@code id} if it lives in the
     * currently-viewed zone. No-op when the group isn't in view: the caller
     * already set {@code selectedZoneId} before invoking us so the group is
     * expected to resolve, but robustness against stale ids is cheap.
     */
    private void selectGroupById(String id) {
        List<WaypointGroup> groups = visibleGroups();
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id().equals(id)) {
                selectedIndex = i;
                // Scroll so the row is visible. Row height + pad mirrors
                // renderMain's y step; centering on one row is enough -- the
                // list doesn't need pixel-perfect placement.
                scrollOffset = Math.max(0, i * (ROW_H + 4) - ROW_H);
                return;
            }
        }
    }

    private void onSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(searchQuery)) return;
        searchQuery = next;
        scrollOffset = 0;
        selectedIndex = -1;
        refreshActionButtons();
    }

    private void syncSearchBoxGeometry() {
        if (searchBox == null) return;
        Layout layout = layout();
        searchBox.setX(layout.mainLeft() + GAP);
        searchBox.setY(layout.top() + 4);
        int availableWidth = layout.mainRight() - layout.mainLeft() - GAP * 2;
        searchBox.setWidth(Math.max(80, Math.min(180, availableWidth)));
    }

    /*[[AI-FN-DOC
Function:
syncDownloadRoutesButtonGeometry.
Purpose:
Keep the Download Routes button pinned to the bottom of the zone sidebar.
Why this exists:
The sidebar is custom-painted after normal widget rendering, so the button needs explicit geometry sync before both regular clicks and manual rendering.
When to use:
Use after creating downloadRoutesBtn and before manually rendering it each frame. Do not use when the button field is null.
Inputs:
No explicit parameters; reads current layout, screen size, and downloadRoutesBtn.
Outputs:
No return value; mutates the button's x, y, and width.
Side effects:
Changes widget geometry only.
Failure modes:
If the button has not been created yet, returns without doing anything.
Important invariants:
The button must stay within the sidebar x bounds and align to the same bottom panel edge used by zone row clipping.
Internal logic:
Resolve the current layout, inset by GAP on both sides, set y to the bottom-aligned route button top, and clamp width to a usable minimum.
Pseudocode:
If downloadRoutesBtn is null, return.
Compute layout.
Set x to sidebarLeft + GAP.
Set y to routeDownloadButtonTop(layout top, layout bottom).
Set width to max(80, sidebar width minus side gaps).
Implementation notes:
Height is fixed by the button's construction; only x, y, and width need to move when the GUI scale changes.
AI self-check:
Verify geometry is derived from layout each time so footer wrapping or window resize cannot leave the button floating.
]]*/
    private void syncDownloadRoutesButtonGeometry() {
        if (downloadRoutesBtn == null) return;
        Layout layout = layout();
        downloadRoutesBtn.setX(layout.sidebarLeft() + GAP);
        downloadRoutesBtn.setY(routeDownloadButtonTop(layout.top(), layout.bottom()));
        downloadRoutesBtn.setWidth(Math.max(80, layout.sidebarRight() - layout.sidebarLeft() - GAP * 2));
    }

    private void openSettings() {
        minecraft.setScreen(new ConfigScreen(this, config));
    }

    /*[[AI-FN-DOC
Function:
zoneIds.
Purpose:
Return the ordered list of zone ids displayed in this screen's sidebar.
Why this exists:
The sidebar and initial-zone selection need a shared ordering rule that includes temporary routes, known route zones, the current zone, and a fallback unknown zone.
When to use:
Use whenever rendering, hit-testing, or filtering the top-level Waypointer zone list. Do not use for storage enumeration because it intentionally hides empty non-current zones.
Inputs:
No explicit parameters; reads this screen's ActiveGroupManager.
Outputs:
Returns a mutable list of sidebar zone ids in display order.
Side effects:
None.
Failure modes:
None expected; manager methods are assumed to return stable collections for the current tick.
Important invariants:
Temporary is always first, current zone appears near the top when available, and UNKNOWN is present when no normal zones exist.
Internal logic:
Delegate to the static zoneIdsForManager helper so construction and rendering use exactly the same policy.
Pseudocode:
Return zoneIdsForManager(manager).
Implementation notes:
Keeping the helper static lets the constructor validate lastSelectedZoneId before instance methods depend on selectedZoneId.
AI self-check:
Verify no extra filtering is added here that would diverge from constructor behavior.
]]*/
    private List<String> zoneIds() {
        return zoneIdsForManager(manager);
    }

    /*[[AI-FN-DOC
Function:
zoneIdsForManager.
Purpose:
Compute the sidebar zone list for a given active group manager.
Why this exists:
The remembered-zone constructor path needs the same visible-zone policy as the live sidebar before instance methods are fully initialized.
When to use:
Use for top-level Waypointer screen zone visibility decisions. Do not use for rendering route groups inside a specific zone.
Inputs:
manager is the active group manager; it must expose known zone ids, current zone, and groups by zone.
Outputs:
Returns a mutable ordered list of zone ids with TEMPORARY first.
Side effects:
None.
Failure modes:
If the manager has no known normal zones and no current zone, UNKNOWN is added as the only normal fallback.
Important invariants:
No duplicate ids are returned, current zone is inserted after TEMPORARY when missing, and empty non-current zones stay hidden.
Internal logic:
Start with TEMPORARY, append normal non-dungeon zones, insert the live non-dungeon current zone near the top, append the Dungeon Rooms parent when any room zone exists, append room children when expanded, and add UNKNOWN when no normal or room zone was found.
Pseudocode:
Create list.
Add TEMPORARY.
Read current zone.
For each known zone id:
  if it is a dungeon room zone, remember it for the room bucket.
  else add it if it has normal groups and is not already present.
If current zone exists, add it to either the room bucket or the normal zone list.
If room bucket has entries, append Dungeon Rooms and expanded room children.
If only TEMPORARY exists, append UNKNOWN.
Return list.
Implementation notes:
The duplicate checks are intentionally linear because the zone count is tiny and preserving readable ordering matters more than a set.
AI self-check:
Verify temp groups are excluded, room zones are not duplicated as top-level islands, and current room detection still leaves a visible parent row when collapsed.
]]*/
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
        if (!dungeonRooms.isEmpty()) {
            zones.add(DUNGEON_ROOMS_ZONE_ID);
            if (dungeonRoomsExpanded) zones.addAll(dungeonRooms);
        }
        if (zones.size() == 1) zones.add(Zone.UNKNOWN.id());
        return zones;
    }

    /*[[AI-FN-DOC
Function:
visibleGroups.
Purpose:
Return the route groups currently visible in the main list for the selected sidebar row.
Why this exists:
The main list must interpret real zones, Temporary, and the virtual Dungeon Rooms parent differently while still applying one shared search filter.
When to use:
Use for rendering, hit testing, selection, export, and empty-state decisions in this screen. Do not use for storage enumeration outside the GUI.
Inputs:
No explicit parameters; reads selectedZoneId, manager groups, and searchQuery.
Outputs:
Returns a mutable list of route groups visible under the selected sidebar row after search filtering.
Side effects:
None.
Failure modes:
Unknown selected zones naturally produce an empty list unless the manager has matching groups. Search misses return an empty filtered list.
Important invariants:
Temporary shows only non-empty temp groups, Dungeon Rooms shows all non-temp room groups, normal zones show only non-temp groups for that exact zone, and search filtering never mutates the underlying groups.
Internal logic:
Collect the base list from the selected bucket, normalize the search query, return the base list when search is empty, otherwise keep groups matching group metadata or waypoint data.
Pseudocode:
out = empty list
if selected is Temporary, add temporaryGroups
else if selected is Dungeon Rooms, add dungeonRoomGroups
else add non-temp groups for selectedZoneId
query = normalizedSearchQuery
if query empty, return out
filtered = empty list
for each group in out:
  if groupMatchesSearch, add to filtered
return filtered
Implementation notes:
The virtual parent branches before manager.groupsForZone so the sentinel id is never treated as persisted storage.
AI self-check:
Verify collapsing Dungeon Rooms changes the sidebar rows but not this parent bucket's aggregate contents.
]]*/
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

    private String normalizedSearchQuery() {
        return searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
    }

    /*[[AI-FN-DOC
Function:
groupMatchesSearch.
Purpose:
Report whether a route group should remain visible for the current sidebar search query.
Why this exists:
Search needs to cover route names, zone labels, progress/load metadata, and individual waypoint labels/coordinates without duplicating that logic in visibleGroups.
When to use:
Use only from visibleGroups after the query has been normalized and the candidate list has already been scoped to the selected sidebar row.
Inputs:
group is the candidate route group; query is a non-null lowercase search string.
Outputs:
Returns true when any searchable group or waypoint field contains the query.
Side effects:
None.
Failure modes:
Null text fields are handled by containsSearch. Empty queries are normally filtered before this method is called and would match only fields containing an empty string if called directly.
Important invariants:
Dungeon room groups must be searchable by their prefixed display label, not just their raw room id.
Internal logic:
Check group name, raw zone id, display zone label, load mode, route progress summary, then each waypoint's searchable fields.
Pseudocode:
if group name contains query, return true
if group zone id contains query, return true
if displayZoneLabel contains query, return true
if load mode contains query, return true
if route summary contains query, return true
for each waypoint index:
  if waypointMatchesSearch, return true
return false
Implementation notes:
displayZoneLabel makes searches like "Dungeons: Entrance" or "Entrance" work for room routes.
AI self-check:
Verify this remains read-only and does not allocate more than the existing per-search scan requires.
]]*/
    private boolean groupMatchesSearch(WaypointGroup group, String query) {
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

    private boolean waypointMatchesSearch(WaypointGroup group, int index, String query) {
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

    /*[[AI-FN-DOC
Function:
dungeonRoomGroups.
Purpose:
Collect every saved non-temporary route group whose zone id belongs to a named dungeon room definition.
Why this exists:
The virtual Dungeon Rooms sidebar row needs to show and export all room-scoped routes without treating the virtual parent id as a real storage zone.
When to use:
Use when selectedZoneId is the Dungeon Rooms parent. Do not use for a single room child row because manager.groupsForZone is cheaper and preserves exact zone scope.
Inputs:
No explicit parameters; reads all groups from this screen's ActiveGroupManager.
Outputs:
Returns a mutable list of non-temp route groups scoped to dungeon room ids.
Side effects:
None.
Failure modes:
If dungeon data is unavailable or no room groups exist, returns an empty list.
Important invariants:
Temporary waypoints are never included, and only ids known to DungeonRoomData count as room zones.
Internal logic:
Iterate all groups, filter out temp groups, keep groups whose zone id resolves to a dungeon room definition, and return them.
Pseudocode:
out = empty list
for each group in manager.allGroups:
  if group is temp, continue
  if group.zoneId is a dungeon room zone, add it
return out
Implementation notes:
This is O(total groups), which is fine for a menu render path because route counts are small and the parent bucket is user-facing convenience.
AI self-check:
Verify this never returns groups stored under the virtual Dungeon Rooms id.
]]*/
    private List<WaypointGroup> dungeonRoomGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && isDungeonRoomZone(group.zoneId())) out.add(group);
        }
        return out;
    }

    /*[[AI-FN-DOC
Function:
normalGroupCountForZone.
Purpose:
Count saved non-temporary route groups for a zone or the virtual Dungeon Rooms parent in this screen.
Why this exists:
The sidebar displays normal route counts separately from temporary waypoints, and the Dungeon Rooms parent needs the aggregate count of its hidden children.
When to use:
Use for this screen's sidebar labels. Do not use for temporary waypoint totals; temporaryWaypointCount handles those separately.
Inputs:
zoneId is the real zone id or DUNGEON_ROOMS_ZONE_ID to inspect.
Outputs:
Returns the number of groups in that real zone whose temp flag is false, or the aggregate room route count for the virtual parent.
Side effects:
None.
Failure modes:
None expected; missing or unknown zones naturally count as zero through the manager.
Important invariants:
Temporary groups must never be included in normal zone counts, and the virtual parent must never be queried as if it were persisted storage.
Internal logic:
Return dungeonRoomGroupCount for the virtual parent; otherwise delegate to the static manager-aware helper so constructor and instance code cannot drift.
Pseudocode:
If zoneId is Dungeon Rooms, return dungeonRoomGroupCount(manager).
Return normalGroupCountForZone(manager, zoneId).
Implementation notes:
This overload keeps call sites short inside instance rendering code while preserving a reusable static path for initial selection.
AI self-check:
Verify the helper still excludes temp groups and that virtual parent counts match the expanded children.
]]*/
    private int normalGroupCountForZone(String zoneId) {
        if (isDungeonRoomsZone(zoneId)) return dungeonRoomGroupCount(manager);
        return normalGroupCountForZone(manager, zoneId);
    }

    /*[[AI-FN-DOC
Function:
normalGroupCountForZone static helper.
Purpose:
Count non-temporary route groups for a zone using an explicit manager.
Why this exists:
Initial zone restoration happens during construction, before relying on instance helpers is desirable, but it must use the same group-count policy as the sidebar.
When to use:
Use from static or constructor-time code that needs normal route counts. Do not use for temp waypoint totals.
Inputs:
manager is the active group manager; zoneId is the zone id to count.
Outputs:
Returns the number of non-temp groups for the zone.
Side effects:
None.
Failure modes:
None expected; manager lookups for empty zones simply produce no counted groups.
Important invariants:
Only groups with temp() == false contribute to the result.
Internal logic:
Iterate manager.groupsForZone(zoneId), increment for each group that is not temporary, and return the total.
Pseudocode:
Set count to zero.
For each group in manager.groupsForZone(zoneId):
If group is not temporary, increment count.
Return count.
Implementation notes:
This avoids copying the visible-zone logic into the constructor while keeping the operation O(number of groups in the zone).
AI self-check:
Verify temp groups are excluded and no route state is mutated.
]]*/
    private static int normalGroupCountForZone(ActiveGroupManager manager, String zoneId) {
        int count = 0;
        for (WaypointGroup group : manager.groupsForZone(zoneId)) {
            if (!group.temp()) count++;
        }
        return count;
    }

    /*[[AI-FN-DOC
Function:
dungeonRoomGroupCount.
Purpose:
Count all saved route groups that belong to any named dungeon room zone.
Why this exists:
The virtual Dungeon Rooms sidebar row needs a count that represents its collapsed children instead of looking up groups under its virtual id.
When to use:
Use for sidebar counts on the Dungeon Rooms parent. Do not use for a specific room child row.
Inputs:
manager is the active group manager whose full group collection should be scanned.
Outputs:
Returns the number of non-temporary groups with zone ids known to DungeonRoomData.
Side effects:
None.
Failure modes:
If no dungeon room definitions match group ids, returns zero.
Important invariants:
Temporary groups are excluded, and the virtual Dungeon Rooms id is never counted as a room definition.
Internal logic:
Iterate all groups, increment for each non-temp group whose zone id resolves to a room definition, and return the total.
Pseudocode:
count = 0
for each group in manager.allGroups:
  if group is not temp and group.zoneId is dungeon room, count++
return count
Implementation notes:
Keeping the count separate from dungeonRoomGroups avoids allocating a list just to draw the sidebar number.
AI self-check:
Verify this count matches dungeonRoomGroups().size() for the same manager state.
]]*/
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

    /*[[AI-FN-DOC
Function:
isDungeonRoomsZone.
Purpose:
Identify the virtual sidebar id that represents the collapsible Dungeon Rooms parent row.
Why this exists:
The UI needs one sentinel id that is selectable and renderable but must never be mistaken for a persisted route zone.
When to use:
Use before storage, import, export, count, render, or click logic that needs to special-case the parent bucket. Do not use to identify actual room child zones.
Inputs:
zoneId may be null or any string.
Outputs:
Returns true only for the exact Dungeon Rooms virtual id.
Side effects:
None.
Failure modes:
Null inputs return false through String.equals.
Important invariants:
Only DUNGEON_ROOMS_ZONE_ID is treated as the virtual parent.
Internal logic:
Compare the constant virtual id to the provided string.
Pseudocode:
return DUNGEON_ROOMS_ZONE_ID.equals(zoneId)
Implementation notes:
The constant is intentionally impossible to collide with normal Hypixel ids.
AI self-check:
Verify callers do not persist this id as a real route zone.
]]*/
    private static boolean isDungeonRoomsZone(String zoneId) {
        return DUNGEON_ROOMS_ZONE_ID.equals(zoneId);
    }

    /*[[AI-FN-DOC
Function:
isDungeonRoomZone.
Purpose:
Detect whether a zone id corresponds to a named dungeon room definition.
Why this exists:
Room zones come from the Odin-backed dungeon room catalog rather than Zone.KNOWN, so normal island lookup cannot classify them.
When to use:
Use for UI grouping, red accent selection, room labels, and virtual parent aggregation. Do not use for broad Catacombs zone ids like dungeon or dungeon_hub.
Inputs:
zoneId may be null, blank, a normal island id, a broad dungeon id, or a room definition id.
Outputs:
Returns true when DungeonRoomData has a definition for the normalized id.
Side effects:
May trigger DungeonRoomData's already-static bundled definition lookup; does not mutate route data.
Failure modes:
Unknown ids return false.
Important invariants:
The result must agree with DungeonRoomData.definition so labels, counts, and room matching use the same catalog.
Internal logic:
Ask DungeonRoomData for a definition and return whether it exists.
Pseudocode:
return DungeonRoomData.definition(zoneId) is not null
Implementation notes:
This avoids string-prefix heuristics and keeps custom room definitions compatible.
AI self-check:
Verify broad Catacombs remains a normal zone and only named room ids collapse under Dungeon Rooms.
]]*/
    private static boolean isDungeonRoomZone(String zoneId) {
        return DungeonRoomData.definition(zoneId) != null;
    }

    /*[[AI-FN-DOC
Function:
sidebarSelectionForZoneId.
Purpose:
Map a real zone id to the visible sidebar row that should be selected.
Why this exists:
When Dungeon Rooms is collapsed, a focused room route still needs to be visible through the parent row instead of selecting a hidden child id.
When to use:
Use when opening or refocusing the Waypointer screen from an external route group or current zone id. Do not use for storage writes because it can return a virtual id.
Inputs:
zoneId may be null, a normal zone id, a dungeon room id, or a virtual id.
Outputs:
Returns DUNGEON_ROOMS_ZONE_ID for collapsed room ids, otherwise returns the input unchanged.
Side effects:
None.
Failure modes:
Null inputs return null unchanged.
Important invariants:
The helper must only rewrite actual room ids when dungeonRoomsExpanded is false.
Internal logic:
If the id is a dungeon room and the parent is collapsed, return the parent id; otherwise return the original id.
Pseudocode:
if zoneId is non-null and room zone and rooms are collapsed, return Dungeon Rooms id
return zoneId
Implementation notes:
This keeps collapsed-room focus discoverable while preserving exact child selection when expanded.
AI self-check:
Verify import/open focus still lets selectGroupById find the route through visibleGroups.
]]*/
    private static String sidebarSelectionForZoneId(String zoneId) {
        if (zoneId != null && isDungeonRoomZone(zoneId) && !dungeonRoomsExpanded) {
            return DUNGEON_ROOMS_ZONE_ID;
        }
        return zoneId;
    }

    /*[[AI-FN-DOC
Function:
displayZoneLabel.
Purpose:
Return the human-readable label Waypointer should show for sidebar, status, export, search, and cross-zone hints.
Why this exists:
Named dungeon room zones are not normal SkyBlock islands, so they need catalog names and the explicit "Dungeons:" prefix instead of Zone.fromId prettification.
When to use:
Use for UI-facing zone labels in this screen. Do not use for persisted ids, codec fields, or protocol-facing zone identifiers.
Inputs:
zoneId may be the Temporary virtual id, the Dungeon Rooms virtual id, a dungeon room id, a normal zone id, or unknown.
Outputs:
Returns "Temporary", "Dungeon Rooms", "Dungeons: <room name>", or Zone.fromId(zoneId).displayName().
Side effects:
May read DungeonRoomData's catalog; does not mutate state.
Failure modes:
Unknown ids fall back through Zone.fromId's normal prettifier.
Important invariants:
Every visible room child label must include the "Dungeons:" prefix requested by the user.
Internal logic:
Check virtual ids first, check dungeon room definition next, and fall back to normal Zone display names.
Pseudocode:
if temp id, return Temporary
if dungeon parent id, return Dungeon Rooms
definition = DungeonRoomData.definition(zoneId)
if definition exists, return "Dungeons: " + definition.displayName
return Zone.fromId(zoneId).displayName
Implementation notes:
Virtual ids are handled before catalog lookup so they cannot accidentally be prettified.
AI self-check:
Verify no persisted data ever uses the prefixed label as an id.
]]*/
    private static String displayZoneLabel(String zoneId) {
        if (isTemporaryZone(zoneId)) return TEMPORARY_ZONE_LABEL;
        if (isDungeonRoomsZone(zoneId)) return DUNGEON_ROOMS_LABEL;
        DungeonRoomDefinition definition = DungeonRoomData.definition(zoneId);
        if (definition != null) return DUNGEON_ROOM_LABEL_PREFIX + definition.displayName();
        return Zone.fromId(zoneId).displayName();
    }

    // --- render ------------------------------------------------------------------------------

    @Override
    /*[[AI-FN-DOC
Function:
render.
Purpose:
Draw the Waypointer route manager, including custom panels and manually rendered widgets.
Why this exists:
The screen uses custom list/sidebar painting that vanilla widgets alone cannot express, so rendering is orchestrated explicitly.
When to use:
Called by Minecraft every frame while this screen is active. Do not call directly from state mutation methods.
Inputs:
g is the GuiGraphics draw context; mouseX and mouseY are current mouse coordinates; partial is the frame interpolation value.
Outputs:
No return value; pixels are drawn to the current GUI frame.
Side effects:
May reset delete confirmation labels when timers expire, sync widget geometry, and render child widgets manually after custom surfaces.
Failure modes:
No expected hard failures; empty data renders an empty state.
Important invariants:
Custom panel fills must draw before search and Download Routes widgets are manually rendered, otherwise the panel surfaces cover them.
Internal logic:
Let the base screen process its widget pass, update timer-driven labels, draw header/status, render sidebar/main panels, then redraw widgets that live on top of custom panels.
Pseudocode:
Call super.render.
Check delete and flash timers; reset labels when expired.
Draw title and status text.
Compute layout.
Render sidebar.
Render main list.
Render Download Routes button above the sidebar surface.
Render search box above the main surface.
Implementation notes:
The double widget render is intentional for widgets embedded in painted panels; the first pass handles vanilla state and the manual pass restores visual z-order.
AI self-check:
Verify the route download button is painted after the sidebar fill and before nothing later covers it.
]]*/
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        // Reset the Delete button label once the confirm/flash window elapses.
        // Doing this in render (rather than tick) keeps the screen dependency-free
        // and runs every frame which is plenty for a short confirmation transition.
        long now = System.currentTimeMillis();
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
        g.drawString(font, "Waypointer", PAD_OUTER, PAD_OUTER, TEXT, false);
        String status;
        if (isTemporaryZone(selectedZoneId)) {
            int waypointCount = temporaryWaypointCount();
            status = TEMPORARY_ZONE_LABEL + "  .  " + waypointCount
                    + " waypoint" + (waypointCount == 1 ? "" : "s");
        } else {
            int groupCount = visibleGroups().size();
            status = displayZoneLabel(selectedZoneId) + "  ."
                    + "  " + groupCount + " group" + (groupCount == 1 ? "" : "s");
        }
        g.drawString(font, status, width - PAD_OUTER - font.width(status), PAD_OUTER, TEXT_DIM, false);

        // Region geometry
        Layout layout = layout();

        renderSidebar(g, layout.sidebarLeft(), layout.top(), layout.sidebarRight(),
                layout.bottom(), mouseX, mouseY);
        renderMain(g, layout.mainLeft(), layout.top(), layout.mainRight(),
                layout.bottom(), mouseX, mouseY);
        renderDownloadRoutesButton(g, mouseX, mouseY, partial);
        renderSearchBox(g, mouseX, mouseY, partial);
    }

    private void renderSearchBox(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (searchBox == null) return;
        syncSearchBoxGeometry();
        searchBox.renderWidget(g, mouseX, mouseY, partial);
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

    /*[[AI-FN-DOC
Function:
renderDownloadRoutesButton.
Purpose:
Draw the bottom-pinned Download Routes button above the custom sidebar surface.
Why this exists:
Screen.super renders widgets before this screen paints its panels, so sidebar widgets need a manual second render pass to remain visible.
When to use:
Use during render after renderSidebar has painted the sidebar background. Do not call before downloadRoutesBtn is created.
Inputs:
g is the draw context; mouseX and mouseY are current cursor coordinates; partial is the frame interpolation value forwarded to the widget.
Outputs:
No return value; the button is drawn if present.
Side effects:
Synchronizes the button geometry before rendering.
Failure modes:
If the button has not been initialized, returns without drawing.
Important invariants:
The button must appear bottom-aligned inside the zone sidebar and must not overlap the zone rows' clickable/rendered area.
Internal logic:
Check for a button, sync geometry from the current layout, and ask the button to render itself.
Pseudocode:
If downloadRoutesBtn is null, return.
Sync download routes button geometry.
Render the button widget.
Implementation notes:
Using the vanilla Button renderer keeps hover, disabled, narration, and tooltip behavior consistent with the rest of the UI.
AI self-check:
Verify this does not create a second widget or mutate route data.
]]*/
    private void renderDownloadRoutesButton(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (downloadRoutesBtn == null) return;
        syncDownloadRoutesButtonGeometry();
        downloadRoutesBtn.render(g, mouseX, mouseY, partial);
    }

    /*[[AI-FN-DOC
Function:
renderSidebar.
Purpose:
Draw the Zones sidebar, clipping zone rows above the bottom Download Routes button.
Why this exists:
The sidebar is a custom compact list rather than a vanilla list widget, and the bottom action must stay visually anchored instead of scrolling with zone rows.
When to use:
Called from render whenever the Waypointer screen is visible.
Inputs:
g is the draw context; x1/y1/x2/y2 define the sidebar panel bounds; mouseX and mouseY are current cursor coordinates.
Outputs:
No return value; sidebar background, label, and visible zone rows are drawn.
Side effects:
Temporarily enables a scissor rectangle while drawing rows, then disables it.
Failure modes:
If the panel is too short, the row region clamps to an empty range and only the header/background/button remain.
Important invariants:
Zone rows must not render under the Download Routes button, and the selected row must still be highlighted when visible.
Internal logic:
Draw background and separator, draw the Zones label, compute the clipped row region above the route button, then draw each row until the region is full, using the virtual dungeon parent to indent room rows and show the expansion marker.
Pseudocode:
Fill sidebar surface and right border.
Draw Zones label.
Compute rowY below label and rowsBottom above route button.
If rowsBottom is below rowY, return.
Enable scissor for row area.
For each zone id:
If rowY is at or below rowsBottom, stop.
Compute selected/current/hover/temp/dungeon-parent/dungeon-child state.
Draw the row with the right accent and indentation.
Advance rowY.
Disable scissor.
Implementation notes:
The list still does not scroll; this change simply protects the anchored button from visual overlap on crowded zone sets.
AI self-check:
Verify scissor is always disabled on the normal path, hover uses the clipped row bounds, and collapsed room rows are absent from zoneIds.
]]*/
    private void renderSidebar(GuiGraphics g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, SURFACE);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        int labelY = y1 + 10;
        g.drawString(font, "Zones", x1 + GAP, labelY, TEXT_DIM, false);

        int rowY = labelY + 14;
        int rowsBottom = routeDownloadButtonTop(y1, y2) - GAP_TIGHT;
        if (rowsBottom <= rowY) return;

        List<String> ids = zoneIds();
        String currentId = manager.currentZone() == null ? null : manager.currentZone().id();
        g.enableScissor(x1, rowY, x2, rowsBottom);
        for (String id : ids) {
            if (rowY >= rowsBottom) break;
            boolean selected = id.equals(selectedZoneId);
            boolean temporary = isTemporaryZone(id);
            boolean dungeonParent = isDungeonRoomsZone(id);
            boolean dungeonRoom = isDungeonRoomZone(id);
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= Math.min(rowsBottom, rowY + ROW_H);
            boolean current = id.equals(currentId)
                    || (dungeonParent && isDungeonRoomZone(currentId));
            drawZoneRow(g, x1, rowY, x2, id, selected, hovered,
                    !temporary && current, temporary, dungeonParent, dungeonRoom);
            rowY += ROW_H;
        }
        g.disableScissor();
    }

    /*[[AI-FN-DOC
Function:
routeDownloadButtonTop.
Purpose:
Calculate the y coordinate for the bottom-aligned Download Routes button.
Why this exists:
Both rendering and click hit testing need a single source of truth for where the fixed sidebar action begins.
When to use:
Use whenever laying out the Download Routes button or clipping zone rows above it.
Inputs:
panelTop is the top y coordinate of the sidebar panel; panelBottom is the bottom y coordinate of that panel.
Outputs:
Returns the y coordinate where the button should start.
Side effects:
None.
Failure modes:
Very small panels may force the button close to the header, but the result remains inside or near the panel instead of throwing.
Important invariants:
The returned position must leave GAP pixels above the normal bottom edge on normal-sized screens.
Internal logic:
Bottom-align the button by subtracting GAP and BTN_H from the panel bottom, clamping no higher than panelTop.
Pseudocode:
Compute y as panelBottom - GAP - BTN_H.
Return max(panelTop, y).
Implementation notes:
This intentionally favors bottom stickiness over reserving a large header gap because the user asked for the button to be stuck to the bottom.
AI self-check:
Verify the same helper is used by widget geometry and zone row clipping.
]]*/
    private static int routeDownloadButtonTop(int panelTop, int panelBottom) {
        return Math.max(panelTop, panelBottom - GAP - BTN_H);
    }

    /*[[AI-FN-DOC
Function:
drawZoneRow.
Purpose:
Render one row in the left Zones sidebar, including temporary, dungeon parent, dungeon room, current-zone, selected, and hover states.
Why this exists:
The sidebar is not a vanilla list widget, so every visual state must be drawn by hand in one place to keep row colors, counts, and labels consistent.
When to use:
Use only from renderSidebar while drawing a visible row returned by zoneIds. Do not call for route groups in the main panel.
Inputs:
g is the current GuiGraphics context; x1/y/x2 define row bounds; zoneId is either a real zone id or a virtual sidebar id; selected and hovered describe UI interaction state; isCurrent marks the live detected zone or dungeon parent; temporary, dungeonParent, and dungeonRoom classify the row.
Outputs:
No return value; draws the row background, accent strip, label, current-dot, and count.
Side effects:
Draws pixels to the current GUI frame only.
Failure modes:
Long labels are clipped to the available row width so they cannot overlap the count or current indicator.
Important invariants:
Temporary rows use green, dungeon rows use light red, normal island rows use the shared blue accent, and Unknown remains muted unless it is the current selected fallback.
Internal logic:
Choose the background, accent color, label, count, and text color; draw a narrow selected strip; draw a small expansion marker for the dungeon parent; indent dungeon child labels; clip the label before the count; draw the current dot and right-aligned count.
Pseudocode:
if selected or hovered, fill row background.
compute whether zone is Unknown.
choose accent from row type.
if selected and row should be accented, draw left strip.
build label through displayZoneLabel.
compute count through temporary or normal group counters.
choose text color from Unknown, selected, temporary-empty, and normal states.
compute count text and right edge.
if dungeon parent, draw expanded/collapsed marker.
clip label to available space and draw it.
if current, draw the dot in the row accent.
draw the count right-aligned.
Implementation notes:
The parent row stays selectable and toggleable; when collapsed it carries the current-room dot so users still see that the live dungeon context is inside the hidden bucket.
AI self-check:
Verify label clipping accounts for count width, child indentation, and expansion marker width.
]]*/
    private void drawZoneRow(GuiGraphics g, int x1, int y, int x2,
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
        if (temporary && count == 0 && !selected) textColor = TEXT_MUTED;

        String countStr = Integer.toString(count);
        int countX = (isCurrent ? x2 - GAP - 12 : x2 - GAP) - font.width(countStr);
        int labelX = x1 + GAP + 2 + (dungeonRoom ? 8 : 0);
        if (dungeonParent) {
            String marker = dungeonRoomsExpanded ? "v" : ">";
            g.drawString(font, marker, labelX, y + 6, textColor, false);
            labelX += font.width(marker) + 4;
        }
        int labelMaxW = Math.max(12, countX - GAP_TIGHT - labelX);
        String clippedLabel = font.plainSubstrByWidth(label, labelMaxW);
        g.drawString(font, clippedLabel, labelX, y + 6, textColor, false);

        // live "current zone" indicator -- a tiny filled dot, no color, just a glyph
        if (isCurrent) {
            int dotX = x2 - GAP - 6;
            g.fill(dotX, y + ROW_H / 2 - 2, dotX + 4, y + ROW_H / 2 + 2, accent);
        }

        // Group count, right-aligned next to the dot (or at the edge if no dot)
        g.drawString(font, countStr, countX, y + 6, TEXT_MUTED, false);
    }

    private void renderMain(GuiGraphics g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        List<WaypointGroup> groups = visibleGroups();
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        int rowsTop = mainRowsTop(y1);
        if (groups.isEmpty()) {
            renderEmptyState(g, x1, rowsTop);
            return;
        }

        g.enableScissor(x1, rowsTop, x2, y2);

        int y = rowsTop - scrollOffset;
        int listW = x2 - x1;
        for (int i = 0; i < groups.size(); i++, y += ROW_H + 4) {
            int rowTop = y;
            int rowBot = y + ROW_H + 2;
            if (rowBot < rowsTop || rowTop > y2) continue;

            boolean hovered = mouseX >= x1 + 2 && mouseX <= x2 - 2
                    && mouseY >= rowTop && mouseY <= rowBot;
            renderGroupRow(g, groups.get(i), i, x1 + 2, rowTop, x2 - 2, listW,
                    hovered, i == selectedIndex);
        }
        g.disableScissor();
    }

    private static int mainRowsTop(int panelTop) {
        return panelTop + 4 + BTN_H + GAP;
    }

    private void renderEmptyState(GuiGraphics g, int x1, int y1) {
        int textX = x1 + GAP;
        if (!normalizedSearchQuery().isEmpty()) {
            g.drawString(font, "No routes match search.",
                    textX, y1 + 8, TEXT, false);
            g.drawString(font, "Clear the search field to show all routes.",
                    textX, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }
        if (isTemporaryZone(selectedZoneId)) {
            g.drawString(font, "No temporary waypoints.",
                    textX, y1 + 8, TEXT, false);
            g.drawString(font, "Chat coords and Add Temp markers will appear here.",
                    textX, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }
        g.drawString(font, "No waypoint groups in this zone.",
                textX, y1 + 8, TEXT, false);
        g.drawString(font, "Click \"New Group\" to start, or paste a codec into chat.",
                textX, y1 + 8 + 14, TEXT_DIM, false);
    }

    /*[[AI-FN-DOC
Function:
renderGroupRow.
Purpose:
Draw one route group row in the main list, including selection, hover, enabled state, route metadata, and the ON/OFF toggle.
Why this exists:
The route list is custom-rendered so it can show compact route progress, temp status, and per-route toggles without the overhead and styling limits of a vanilla list widget.
When to use:
Use only from renderMain for groups returned by visibleGroups. Do not use for sidebar zone rows.
Inputs:
g is the draw context; group is the route group to render; index is its visible index; x1/y1/x2/listW describe the row/list geometry; hovered and selected are the current interaction states.
Outputs:
No return value; draws row pixels.
Side effects:
Draws to the current GUI frame only.
Failure modes:
Very long route names or cross-zone hints can be clipped by available row width as before; route data itself is not affected.
Important invariants:
Temporary groups use green, dungeon room groups use light red, normal groups use the shared blue accent, and disabled groups use muted text.
Internal logic:
Draw selected/hover background, choose accent, draw title and subtitle, draw the enabled toggle chip, then draw a cross-zone hint when the group is being shown through an aggregate or mismatched selected zone.
Pseudocode:
rowBot = y1 + row height
if selected or hovered, fill background
choose accent from temp, dungeon room, or normal group
if selected, draw left accent strip
draw displayGroupName
build temp or normal subtitle with displayZoneLabel where needed
draw ON/OFF chip
if non-temp group zone differs from selectedZoneId, draw displayZoneLabel hint
Implementation notes:
Using displayZoneLabel in hints makes Dungeon Rooms aggregate rows identify their actual room instead of exposing raw ids.
AI self-check:
Verify this method does not mutate group enabled state; toggling is handled in mouseClicked.
]]*/
    private void renderGroupRow(GuiGraphics g, WaypointGroup group, int index,
                                int x1, int y1, int x2, int listW,
                                boolean hovered, boolean selected) {
        int rowBot = y1 + ROW_H + 2;
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBot, bg);
        int accent = group.temp() ? TEMPORARY_ACCENT
                : isDungeonRoomZone(group.zoneId()) ? DUNGEON_ROOM_ACCENT
                : ACCENT;
        if (selected) g.fill(x1, y1, x1 + 2, rowBot, accent);

        int textColor = group.enabled() ? TEXT : TEXT_MUTED;
        String name = displayGroupName(group);
        g.drawString(font, name, x1 + GAP + 2, y1 + 4, textColor, false);

        String sub = group.temp()
                ? group.size() + " temp pts  " + displayZoneLabel(group.zoneId())
                : group.size() + " pts  " + RouteProgress.summary(group)
                        + "  " + loadModeLabel(group);
        g.drawString(font, sub, x1 + GAP + 2, y1 + 14, TEXT_DIM, false);

        // Right-aligned toggle pill -- kept as the one exception to "no button chrome",
        // because it's genuinely a tap target with two states and the pill shape
        // communicates that more clearly than a checkbox in a dense row.
        String toggle = group.enabled() ? "ON" : "OFF";
        int chipW = 28;
        int chipX = x2 - chipW - GAP;
        int chipY = y1 + 5;
        int chipColor = group.enabled() ? 0xFF2D7A2D : 0xFF555555;
        g.fill(chipX, chipY, chipX + chipW, chipY + 14, chipColor);
        int tw = font.width(toggle);
        g.drawString(font, toggle, chipX + (chipW - tw) / 2, chipY + 3, 0xFFFFFFFF, false);

        // Cross-zone hint (rare, but possible if a group's zone id drifts)
        String zid = group.zoneId();
        if (!group.temp() && !zid.equals(selectedZoneId)) {
            String hint = "(" + displayZoneLabel(zid) + ")";
            g.drawString(font, hint, chipX - GAP - font.width(hint), y1 + 10,
                    TEXT_MUTED, false);
        }
    }

    private static String displayGroupName(WaypointGroup group) {
        String name = group.name().trim();
        if (!group.temp()) return name.isEmpty() ? "(unnamed)" : name;
        if (name.isEmpty() || name.startsWith("Temp --")) return TEMPORARY_ZONE_LABEL;
        return name;
    }

    // --- input -------------------------------------------------------------------------------

    @Override
    /*[[AI-FN-DOC
Function:
mouseClicked.
Purpose:
Handle custom sidebar and route-list mouse interactions that vanilla widgets do not cover.
Why this exists:
The zone list and route rows are hand-rendered, so clicks must be translated into selected zones, selected groups, toggles, and double-click edits manually.
When to use:
Called by Minecraft for mouse clicks while this screen is active. Do not call directly from button callbacks.
Inputs:
event contains mouse position and button id; doubleClick is true when Minecraft detected a double-click gesture.
Outputs:
Returns true when the click was handled by widgets, the sidebar, or the route list; false when outside handled regions.
Side effects:
May change selectedZoneId, scrollOffset, selectedIndex, group enabled state, current screen, and button enabled state.
Failure modes:
Clicks outside visible row regions are ignored or absorbed by the sidebar; stale selection indices are guarded by range checks.
Important invariants:
Sidebar row clicks must only apply to the visible row area above Download Routes, and route row toggles must persist through manager.fireDataChanged.
Internal logic:
Let vanilla widgets handle first, ignore non-left clicks, check clipped sidebar rows, toggle/select the Dungeon Rooms parent when clicked, then check main list rows and optional toggle/double-click behavior.
Pseudocode:
If super handles the click, return true.
If button is not left mouse, return false.
Compute mouse coordinates and layout.
If inside sidebar bounds:
Compute row start and rowsBottom.
If click is in visible row area, map y to zone index and select it when valid.
If clicked row is Dungeon Rooms, flip expanded state.
Return true for sidebar clicks.
If outside main bounds, return false.
If no visible groups or click is above rows, return false.
Map y to group index with scroll offset.
If index invalid, return false.
Select group and refresh actions.
If click is on toggle chip, flip enabled and save.
If double-click, open editor.
Return true.
Implementation notes:
The Download Routes button is a vanilla widget, so super.mouseClicked handles it before the custom sidebar logic can absorb the click.
AI self-check:
Verify clicking the route download button cannot accidentally select a hidden zone row underneath it.
]]*/
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;

        double mx = event.x();
        double my = event.y();

        Layout layout = layout();

        // Sidebar click -> select zone
        if (mx >= layout.sidebarLeft() && mx <= layout.sidebarRight()
                && my >= layout.top() && my <= layout.bottom()) {
            int labelY = layout.top() + 10;
            int rowY = labelY + 14;
            int rowsBottom = routeDownloadButtonTop(layout.top(), layout.bottom()) - GAP_TIGHT;
            if (my >= rowY && my < rowsBottom) {
                List<String> ids = zoneIds();
                int idx = (int) ((my - rowY) / ROW_H);
                if (idx >= 0 && idx < ids.size()) {
                    selectedZoneId = ids.get(idx);
                    if (isDungeonRoomsZone(selectedZoneId)) {
                        dungeonRoomsExpanded = !dungeonRoomsExpanded;
                    }
                    scrollOffset = 0;
                    selectedIndex = -1;
                    refreshActionButtons();
                }
            }
            return true;
        }

        // Main area click -> select group row (and toggle chip if within the right edge)
        if (mx < layout.mainLeft() || mx > layout.mainRight()
                || my < layout.top() || my > layout.bottom()) return false;

        List<WaypointGroup> groups = visibleGroups();
        if (groups.isEmpty()) return false;
        int rowsTop = mainRowsTop(layout.top());
        if (my < rowsTop) return false;

        double yInList = my - rowsTop + scrollOffset;
        int idx = (int) (yInList / (ROW_H + 4));
        if (idx < 0 || idx >= groups.size()) return false;

        WaypointGroup group = groups.get(idx);
        selectedIndex = idx;
        refreshActionButtons();

        // Toggle-chip hit test -- rightmost region of the row.
        if (mx > layout.mainRight() - 40) {
            group.setEnabled(!group.enabled());
            manager.fireDataChanged();
            return true;
        }

        if (doubleClick) {
            minecraft.setScreen(new GroupEditScreen(this, manager, config, group));
        }
        return true;
    }

    @Override
    /*[[AI-FN-DOC
Function:
onClose.
Purpose:
Remember the currently open zone before the Waypointer menu closes.
Why this exists:
Users expect reopening the route manager to return to the zone they were working in instead of snapping back to the live detected zone every time.
When to use:
Called by Minecraft when the user closes this screen via Done, Escape, or equivalent screen-close flow.
Inputs:
No explicit parameters; reads selectedZoneId and the current sidebar zone list.
Outputs:
No return value.
Side effects:
Updates the static lastSelectedZoneId field and then delegates to Screen.onClose to actually close the screen.
Failure modes:
If the selected id is stale or no longer visible, rememberSelectedZone ignores it so the next open falls back safely.
Important invariants:
Closing the screen must still perform vanilla close behavior after storing the UI preference.
Internal logic:
Remember the selected zone if valid, then call the superclass implementation.
Pseudocode:
Call rememberSelectedZone.
Call super.onClose.
Implementation notes:
The remembered value is not saved to disk; it lasts for the client session and avoids adding a config knob for transient UI focus.
AI self-check:
Verify this does not interfere with opening child screens, because those use setScreen directly rather than closing this screen.
]]*/
    public void onClose() {
        rememberSelectedZone();
        super.onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        Layout layout = layout();
        int rowsTop = mainRowsTop(layout.top());
        int listHeight = layout.bottom() - rowsTop;
        int rowPitch = ROW_H + 4;
        int content = visibleGroups().size() * rowPitch;
        int maxScroll = Math.max(0, content - listHeight + 8);
        scrollOffset = MathUtil.clamp(scrollOffset - (int) (vert * rowPitch), 0, maxScroll);
        return true;
    }

    // --- actions -----------------------------------------------------------------------------

    /*[[AI-FN-DOC
Function:
rememberSelectedZone.
Purpose:
Store the current visible zone id for the next Waypointer screen instance.
Why this exists:
The screen should reopen where the user left it during the play session, but only if that zone can still be shown.
When to use:
Use immediately before closing the top-level Waypointer screen. Do not use for every hover or render event.
Inputs:
No explicit parameters; reads selectedZoneId and the computed visible zone list.
Outputs:
No return value.
Side effects:
Mutates the static lastSelectedZoneId and lastCurrentZoneIdWhenRemembered fields when selectedZoneId is currently visible.
Failure modes:
Stale, null, or hidden ids are ignored so future opens can use the normal fallback path.
Important invariants:
lastSelectedZoneId should never be updated to a zone id that the sidebar would not show.
Internal logic:
Check selectedZoneId for null, verify it exists in zoneIds(), assign it to the static memory field, and store the live current-zone context.
Pseudocode:
If selectedZoneId is null, return.
If zoneIds contains selectedZoneId, assign it to lastSelectedZoneId.
Assign currentZoneId(manager) to lastCurrentZoneIdWhenRemembered.
Implementation notes:
This validation matters after imports or data deletion because the selected zone may become empty between frames.
AI self-check:
Verify no manager data is mutated and no config file is written.
]]*/
    private void rememberSelectedZone() {
        if (selectedZoneId != null && zoneIds().contains(selectedZoneId)) {
            lastSelectedZoneId = selectedZoneId;
            lastCurrentZoneIdWhenRemembered = currentZoneId(manager);
        }
    }

    /*[[AI-FN-DOC
Function:
openRouteDownloads.
Purpose:
Open the Waypointer route downloads page from the bottom of the Zones sidebar.
Why this exists:
Route discovery should be reachable directly from the in-game route manager instead of requiring users to remember or paste a URL manually.
When to use:
Used as the Download Routes button callback. Do not use for importing a route payload; this only opens the external downloads page.
Inputs:
button is the pressed Minecraft Button widget; it is not mutated.
Outputs:
No return value.
Side effects:
Requests the operating system to open ROUTE_DOWNLOADS_URI in the user's default browser; on failure, shows a system toast.
Failure modes:
The OS/browser handoff can fail or throw a runtime exception; the catch path reports a short toast without crashing the screen.
Important invariants:
The URL must remain a trusted project-owned route/download location and this method must not download or execute files itself.
Internal logic:
Attempt to open the URI through Minecraft's Util platform helper, catch runtime failures, and show a failure toast if needed.
Pseudocode:
Try Util.getPlatform().openUri(ROUTE_DOWNLOADS_URI).
Catch RuntimeException.
Show a route download failure toast.
Implementation notes:
Opening a browser is safer than silently downloading route content because routes still flow through the existing import validation path.
AI self-check:
Verify the button callback has no hidden data mutation and failure is visible to the user.
]]*/
    private void openRouteDownloads(Button button) {
        try {
            Util.getPlatform().openUri(ROUTE_DOWNLOADS_URI);
        } catch (RuntimeException e) {
            showRouteDownloadOpenFailure();
        }
    }

    /*[[AI-FN-DOC
Function:
showRouteDownloadOpenFailure.
Purpose:
Show a short toast when the route downloads page cannot be opened.
Why this exists:
Browser handoff failures otherwise look like a dead button, which is frustrating in an in-game GUI.
When to use:
Use only from openRouteDownloads when the OS open call throws.
Inputs:
No explicit parameters; reads the current Minecraft toast manager from this screen.
Outputs:
No return value.
Side effects:
Adds or updates a periodic system toast.
Failure modes:
If the minecraft reference is unavailable, returns silently because there is no UI surface available for the toast.
Important invariants:
The toast should not claim a route import failed; it is specifically about opening the downloads page.
Internal logic:
Check the minecraft client reference, then add/update a SystemToast with a concise title and body.
Pseudocode:
If minecraft is null, return.
Call SystemToast.addOrUpdate with title "Routes" and body "Could not open route downloads."
Implementation notes:
PERIODIC_NOTIFICATION is reused elsewhere in the mod for lightweight status and avoids adding a new toast id.
AI self-check:
Verify no exception from the error path escapes the button callback.
]]*/
    private void showRouteDownloadOpenFailure() {
        if (minecraft == null) return;
        SystemToast.addOrUpdate(
                minecraft.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Routes"),
                Component.literal("Could not open route downloads."));
    }

    /*[[AI-FN-DOC
Function:
createGroup.
Purpose:
Create a new persistent route group in the currently selected real zone.
Why this exists:
The New Route button should work from normal zones, Temporary, and the virtual Dungeon Rooms bucket without ever persisting a route to a virtual sidebar id.
When to use:
Use only as the New Route button callback. Do not use for imports, which preserve or retarget route zone ids through importFromClipboard.
Inputs:
No explicit parameters; reads selectedZoneId, manager.currentZone, and config.skipAheadMechanicEnabled.
Outputs:
No return value; opens GroupEditScreen for the newly created group.
Side effects:
May remap selectedZoneId away from Temporary or Dungeon Rooms, creates and adds a WaypointGroup, sets its default radius, and changes the current Minecraft screen.
Failure modes:
If Dungeon Rooms is selected but the live zone is not a named room, falls back to Unknown instead of writing the virtual id.
Important invariants:
Routes are never created under TEMPORARY_ZONE_ID or DUNGEON_ROOMS_ZONE_ID, and the created group is persisted through manager.add.
Internal logic:
Resolve the target zone for Temporary and Dungeon Rooms, create a group with the selected real zone id, apply default radius, add it to the manager, and open the editor.
Pseudocode:
if selected zone is Temporary, replace with current zone id or Unknown
else if selected zone is Dungeon Rooms, replace with current room id or Unknown
create group named New group in selectedZoneId
set default radius from config
manager.add(group)
open GroupEditScreen for group
Implementation notes:
The virtual parent is useful for browsing/exporting room routes, but route authoring still needs a concrete room id.
AI self-check:
Verify no virtual sidebar id can reach WaypointGroup.create.
]]*/
    private void createGroup() {
        if (isTemporaryZone(selectedZoneId)) {
            Zone current = manager.currentZone();
            selectedZoneId = current == null ? Zone.UNKNOWN.id() : current.id();
        } else if (isDungeonRoomsZone(selectedZoneId)) {
            Zone current = manager.currentZone();
            selectedZoneId = current != null && isDungeonRoomZone(current.id())
                    ? current.id()
                    : Zone.UNKNOWN.id();
        }
        WaypointGroup g = WaypointGroup.create(
                "New group", selectedZoneId, config.skipAheadMechanicEnabled());
        g.setDefaultRadius(config.defaultReachRadius());
        manager.add(g);
        minecraft.setScreen(new GroupEditScreen(this, manager, config, g));
    }

    private void editSelected() {
        WaypointGroup g = currentSelection();
        if (g != null) minecraft.setScreen(new GroupEditScreen(this, manager, config, g));
    }

    private void refreshActionButtons() {
        if (editBtn != null) editBtn.active = currentSelection() != null;
    }

    private void onDeleteClicked() {
        WaypointGroup g = currentSelection();
        if (g == null) {
            // Nothing selected. Don't silently no-op -- briefly borrow the button label
            // to tell the user what they need to do.
            flashDeleteLabel(NO_SEL_LABEL,
                    "Select a group from the list on the right first.");
            return;
        }
        long now = System.currentTimeMillis();
        if (now < deleteArmedUntil) {
            // Second click inside the confirm window -- commit.
            deleteArmedUntil = 0L;
            manager.remove(g.id());
            selectedIndex = Math.min(selectedIndex, visibleGroups().size() - 1);
            refreshActionButtons();
            resetDeleteButton();
            return;
        }
        // First click -- arm. render() resets the label after the confirm window elapses.
        // Group name lives in the tooltip (which wraps freely) so the button stays a
        // fixed width and the dangerous state is discoverable on hover.
        deleteArmedUntil = now + CONFIRM_WINDOW_MS;
        if (deleteBtn != null) {
            deleteBtn.setMessage(Component.literal(CONFIRM_LABEL));
            deleteBtn.setTooltip(Tooltip.create(Component.literal(
                    "Double click to permanently delete \"" + g.name() + "\".")));
        }
    }

    private void resetDeleteButton() {
        if (deleteBtn == null) return;
        deleteBtn.setMessage(Component.literal(DELETE_LABEL));
        deleteBtn.setTooltip(Tooltip.create(Component.literal(DELETE_TOOLTIP_DEFAULT)));
    }

    private long labelFlashUntil = 0L;
    private void flashDeleteLabel(String msg, String tooltipText) {
        if (deleteBtn == null) return;
        deleteBtn.setMessage(Component.literal(msg));
        deleteBtn.setTooltip(Tooltip.create(Component.literal(tooltipText)));
        labelFlashUntil = System.currentTimeMillis() + 1500L;
    }

    private WaypointGroup currentSelection() {
        List<WaypointGroup> groups = visibleGroups();
        if (selectedIndex < 0 || selectedIndex >= groups.size()) return null;
        return groups.get(selectedIndex);
    }

    private static String loadModeLabel(WaypointGroup group) {
        return group.loadMode() == WaypointGroup.LoadMode.SEQUENCE ? "sequenced" : "static";
    }

    /*[[AI-FN-DOC
Function:
exportZone.
Purpose:
Open the export review screen for every visible route in the selected sidebar bucket.
Why this exists:
The Export button operates at the sidebar scope, which can be a real island zone, Temporary, a single dungeon room, or the virtual Dungeon Rooms aggregate.
When to use:
Use as the sidebar Export button callback. Do not use for single-group exports from GroupEditScreen.
Inputs:
No explicit parameters; reads visibleGroups and selectedZoneId.
Outputs:
No return value; opens ExportScreen when at least one group is visible.
Side effects:
May change the current Minecraft screen to ExportScreen.
Failure modes:
If no groups are visible, returns without opening an empty export.
Important invariants:
The user-facing export label must use displayZoneLabel so room zones show "Dungeons: <room>" and the parent shows "Dungeon Rooms".
Internal logic:
Collect visible groups, return if empty, compute the display label, and open ExportScreen for those groups.
Pseudocode:
groups = visibleGroups()
if groups empty, return
label = displayZoneLabel(selectedZoneId)
ExportScreen.openForGroups(this, config, groups, label)
Implementation notes:
For the Dungeon Rooms parent, visibleGroups already expands to all saved room routes, and ExportScreen's route choices wrap across rows.
AI self-check:
Verify export never passes a virtual id, only the actual group list and a display label.
]]*/
    private void exportZone() {
        List<WaypointGroup> groups = visibleGroups();
        if (groups.isEmpty()) return;
        String label = displayZoneLabel(selectedZoneId);
        ExportScreen.openForGroups(this, config, groups, label);
    }

    /*[[AI-FN-DOC
Function:
importTargetZoneId.
Purpose:
Choose the real zone id that unknown-zone imported groups should target from the current sidebar selection.
Why this exists:
Imports retarget Unknown groups to the zone the user is viewing, but the virtual Dungeon Rooms parent is not a real storage zone.
When to use:
Use only from importFromClipboard before mutating imported group zone ids. Do not use for focus or display selection because it intentionally avoids virtual ids.
Inputs:
No explicit parameters; reads selectedZoneId and manager.currentZone.
Outputs:
Returns selectedZoneId for normal zones, the live named room id when Dungeon Rooms is selected inside a room, or Unknown as a safe fallback.
Side effects:
None.
Failure modes:
If there is no current named room while the parent is selected, returns Unknown so imports keep safe fallback semantics.
Important invariants:
The return value must never be DUNGEON_ROOMS_ZONE_ID.
Internal logic:
If selectedZoneId is not Dungeon Rooms, return it. Otherwise inspect current zone and return it only if it is a named room, falling back to Unknown.
Pseudocode:
if selected is not Dungeon Rooms, return selectedZoneId
current = manager.currentZone
if current exists and is dungeon room, return current.id
return Unknown
Implementation notes:
This keeps paste/import behavior predictable even when the parent bucket is used only as a browser for many room routes.
AI self-check:
Verify imported Unknown routes cannot be stored under the virtual parent.
]]*/
    private String importTargetZoneId() {
        if (!isDungeonRoomsZone(selectedZoneId)) return selectedZoneId;
        Zone current = manager.currentZone();
        if (current != null && isDungeonRoomZone(current.id())) return current.id();
        return Zone.UNKNOWN.id();
    }

    /*[[AI-FN-DOC
Function:
importFromClipboard.
Purpose:
Import a Waypointer-compatible route payload from the clipboard into the route manager.
Why this exists:
The GUI import action lets users paste route exports without using commands and should land the imported result in the zone they are intentionally viewing.
When to use:
Use as the Import footer button callback. Do not use for chat auto-import, which has its own confirmation and feedback flow.
Inputs:
No explicit parameters; reads the Minecraft clipboard and selected sidebar zone.
Outputs:
No return value.
Side effects:
Reads clipboard text, may mutate imported group zone ids, applies imported route color defaults, adds groups to the manager, shows import feedback, clears search, changes selectedZoneId, and selects the first imported group.
Failure modes:
Empty clipboard shows failure feedback. Invalid payloads throw IllegalArgumentException from the importer and are reported through ImportFeedback.failure. The Dungeon Rooms parent retargets only to a real current room or Unknown.
Important invariants:
Unknown-zone groups are retargeted to a real selected zone, never to Temporary or the virtual Dungeon Rooms id, and the first imported group remains visible after import.
Internal logic:
Read clipboard, validate nonblank, import payload, compute real target zone, retarget Unknown groups, apply color policy, add groups, show success, clear search, map first group's zone to the visible sidebar row, and select it.
Pseudocode:
text = clipboard
if blank, show failure and return
try importAny(text)
target = importTargetZoneId()
if target is not Unknown:
  for imported groups with Unknown zone, set zone to target
apply imported color defaults
add each group to manager
show success
if any group imported:
  clear search
  selectedZoneId = sidebarSelectionForZoneId(first.zoneId)
  selectGroupById(first.id)
catch invalid payload and show failure
Implementation notes:
Mapping through sidebarSelectionForZoneId keeps imported room routes findable when the Dungeon Rooms bucket is collapsed.
AI self-check:
Verify clipboard failures do not mutate manager data and successful imports are visible immediately.
]]*/
    private void importFromClipboard() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            ImportFeedback.failure("Clipboard is empty.");
            return;
        }
        try {
            WaypointImporter.ImportResult result = WaypointImporter.importAny(text);
            // Retarget unknown-zone groups to the zone the user is actively
            // viewing, not the player's live position. Using selectedZoneId
            // matches intent better from the GUI: if the user navigated to
            // "The Park" and then pasted, that's where the import goes.
            String targetZoneId = importTargetZoneId();
            if (!Zone.UNKNOWN.id().equals(targetZoneId)) {
                for (WaypointGroup g : result.groups()) {
                    if (Zone.UNKNOWN.id().equals(g.zoneId())) g.setZoneId(targetZoneId);
                }
            }
            RouteColorPolicy.applyImportedRouteDefaults(result.groups(), config);
            for (WaypointGroup g : result.groups()) manager.add(g);

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

    @Override
    public boolean isPauseScreen() { return false; }
}
