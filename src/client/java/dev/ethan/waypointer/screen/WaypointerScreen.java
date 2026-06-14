package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.color.RouteColorPolicy;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.RouteProgress;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private String selectedZoneId;
    private int scrollOffset;
    private int selectedIndex = -1;
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

    public WaypointerScreen(ActiveGroupManager manager, WaypointerConfig config) {
        super(Component.literal("Waypointer"));
        this.manager = manager;
        this.config = config;
        Zone current = manager.currentZone();
        // Prefer the detected zone. When none is detected (non-Skyblock server or pre-resolve)
        // default to "unknown" rather than a stale known zone: that way "New Group"
        // creates the group in the zone that ActiveGroupManager.activeGroups() actually
        // renders when currentZone is null, and the user sees their waypoints immediately.
        this.selectedZoneId = current == null ? Zone.UNKNOWN.id() : current.id();
    }

    public static void open(ActiveGroupManager manager, WaypointerConfig config) {
        Minecraft.getInstance().setScreen(new WaypointerScreen(manager, config));
    }

    /**
     * Open the editor pre-focused on {@code focus}: switches to the group's
     * zone tab, highlights the group in the list, and scrolls it into view
     * once the screen's first {@link #init()} has run. Used by the import
     * flow so users see the newly-added group without hunting for it.
     */
    public static void openFocused(ActiveGroupManager manager, WaypointerConfig config,
                                   WaypointGroup focus) {
        WaypointerScreen screen = new WaypointerScreen(manager, config);
        if (focus != null) {
            // Select by id rather than by index -- index lookups into
            // visibleGroups() are fragile when groups added mid-list shift
            // indices. The init() pass will resolve the id to a current
            // selectedIndex after it knows the list ordering for the zone.
            screen.selectedZoneId = focus.temp() ? TEMPORARY_ZONE_ID : focus.zoneId();
            screen.pendingFocusGroupId = focus.id();
        }
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    protected void init() {
        int footerY = height - FOOTER_H;
        deleteArmedUntil = 0L;
        editBtn = null;
        deleteBtn = null;
        searchBox = null;

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

    private void openSettings() {
        minecraft.setScreen(new ConfigScreen(this, config));
    }

    private List<String> zoneIds() {
        List<String> zones = new ArrayList<>();
        zones.add(TEMPORARY_ZONE_ID);
        for (String zoneId : manager.knownZoneIds()) {
            if (normalGroupCountForZone(zoneId) > 0 && !zones.contains(zoneId)) {
                zones.add(zoneId);
            }
        }
        Zone currentZone = manager.currentZone();
        if (currentZone != null && !zones.contains(currentZone.id())) zones.add(1, currentZone.id());
        if (zones.size() == 1) zones.add(Zone.UNKNOWN.id());
        return zones;
    }

    private List<WaypointGroup> visibleGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        if (isTemporaryZone(selectedZoneId)) {
            out.addAll(temporaryGroups());
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

    private boolean groupMatchesSearch(WaypointGroup group, String query) {
        if (containsSearch(group.name(), query)) return true;
        if (containsSearch(group.zoneId(), query)) return true;
        if (containsSearch(Zone.fromId(group.zoneId()).displayName(), query)) return true;
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

    private int normalGroupCountForZone(String zoneId) {
        int count = 0;
        for (WaypointGroup group : manager.groupsForZone(zoneId)) {
            if (!group.temp()) count++;
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

    // --- render ------------------------------------------------------------------------------

    @Override
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
            status = Zone.fromId(selectedZoneId).displayName() + "  ."
                    + "  " + groupCount + " group" + (groupCount == 1 ? "" : "s");
        }
        g.drawString(font, status, width - PAD_OUTER - font.width(status), PAD_OUTER, TEXT_DIM, false);

        // Region geometry
        Layout layout = layout();

        renderSidebar(g, layout.sidebarLeft(), layout.top(), layout.sidebarRight(),
                layout.bottom(), mouseX, mouseY);
        renderMain(g, layout.mainLeft(), layout.top(), layout.mainRight(),
                layout.bottom(), mouseX, mouseY);
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

    private void renderSidebar(GuiGraphics g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, SURFACE);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        int labelY = y1 + 10;
        g.drawString(font, "Zones", x1 + GAP, labelY, TEXT_DIM, false);

        int rowY = labelY + 14;
        List<String> ids = zoneIds();
        String currentId = manager.currentZone() == null ? null : manager.currentZone().id();
        for (String id : ids) {
            boolean selected = id.equals(selectedZoneId);
            boolean temporary = isTemporaryZone(id);
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= rowY + ROW_H;
            drawZoneRow(g, x1, rowY, x2, id, selected, hovered,
                    !temporary && id.equals(currentId), temporary);
            rowY += ROW_H;
        }
    }

    private void drawZoneRow(GuiGraphics g, int x1, int y, int x2,
                             String zoneId, boolean selected, boolean hovered,
                             boolean isCurrent, boolean temporary) {
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);

        // "unknown" is intentionally quiet -- it's a placeholder zone, not the focal
        // point of an empty state. So it never gets the accent bar and renders in muted text.
        boolean isUnknown = Zone.UNKNOWN.id().equals(zoneId);
        int accent = temporary ? TEMPORARY_ACCENT : ACCENT;
        if (selected && (!isUnknown || temporary)) {
            g.fill(x1, y, x1 + 2, y + ROW_H, accent);
        }

        String label = temporary ? TEMPORARY_ZONE_LABEL : Zone.fromId(zoneId).displayName();
        int count = temporary ? temporaryWaypointCount() : normalGroupCountForZone(zoneId);
        int textColor = isUnknown && !temporary ? TEXT_MUTED : selected ? TEXT : TEXT_DIM;
        if (temporary && count == 0 && !selected) textColor = TEXT_MUTED;
        g.drawString(font, label, x1 + GAP + 2, y + 6, textColor, false);

        // live "current zone" indicator -- a tiny filled dot, no color, just a glyph
        if (isCurrent) {
            int dotX = x2 - GAP - 6;
            g.fill(dotX, y + ROW_H / 2 - 2, dotX + 4, y + ROW_H / 2 + 2, ACCENT);
        }

        // Group count, right-aligned next to the dot (or at the edge if no dot)
        String countStr = Integer.toString(count);
        int countX = (isCurrent ? x2 - GAP - 12 : x2 - GAP) - font.width(countStr);
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

    private void renderGroupRow(GuiGraphics g, WaypointGroup group, int index,
                                int x1, int y1, int x2, int listW,
                                boolean hovered, boolean selected) {
        int rowBot = y1 + ROW_H + 2;
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBot, bg);
        int accent = group.temp() ? TEMPORARY_ACCENT : ACCENT;
        if (selected) g.fill(x1, y1, x1 + 2, rowBot, accent);

        int textColor = group.enabled() ? TEXT : TEXT_MUTED;
        String name = displayGroupName(group);
        g.drawString(font, name, x1 + GAP + 2, y1 + 4, textColor, false);

        String sub = group.temp()
                ? group.size() + " temp pts  " + Zone.fromId(group.zoneId()).displayName()
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
            String hint = "(" + zid + ")";
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
            List<String> ids = zoneIds();
            int idx = (int) ((my - rowY) / ROW_H);
            if (idx >= 0 && idx < ids.size()) {
                selectedZoneId = ids.get(idx);
                scrollOffset = 0;
                selectedIndex = -1;
                refreshActionButtons();
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

    private void createGroup() {
        if (isTemporaryZone(selectedZoneId)) {
            Zone current = manager.currentZone();
            selectedZoneId = current == null ? Zone.UNKNOWN.id() : current.id();
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

    private void exportZone() {
        List<WaypointGroup> groups = visibleGroups();
        if (groups.isEmpty()) return;
        String label = isTemporaryZone(selectedZoneId)
                ? TEMPORARY_ZONE_LABEL
                : Zone.fromId(selectedZoneId).displayName();
        ExportScreen.openForGroups(this, config, groups, label);
    }

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
            if (!Zone.UNKNOWN.id().equals(selectedZoneId)) {
                for (WaypointGroup g : result.groups()) {
                    if (Zone.UNKNOWN.id().equals(g.zoneId())) g.setZoneId(selectedZoneId);
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
                selectedZoneId = first.zoneId();
                selectGroupById(first.id());
            }
        } catch (IllegalArgumentException e) {
            ImportFeedback.failure(e.getMessage());
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
