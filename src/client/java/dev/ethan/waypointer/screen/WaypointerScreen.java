package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Top-level editor screen.
 *
 * Layout (clinical / utility aesthetic, see .impeccable.md):
 *   +------------------------------------------+
 *   | Waypointer                   Hub -- 3 gp |
 *   |                                          |
 *   | [ Zones      ] | group list ...          |
 *   | > Hub        3|                          |
 *   |   Garden     1|                          |
 *   |   Unknown    0|                          |
 *   |                                          |
 *   | [New Waypoints][Edit][Delete]...  [Done] |
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
          + "Click twice within 2.5s to confirm.";
    // Sized for the widest transient state label ("Confirm?") so the button doesn't
    // visibly grow or shrink when arming/disarming. Leave some horizontal slack so
    // vanilla's "hover" narration arrow has room without clipping the text.
    private static final int DELETE_BTN_W = 72;
    private Button deleteBtn;
    private long deleteArmedUntil = 0L;

    public WaypointerScreen(ActiveGroupManager manager, WaypointerConfig config) {
        super(Component.literal("Waypointer"));
        this.manager = manager;
        this.config = config;
        Zone current = manager.currentZone();
        // Prefer the detected zone. When none is detected (non-Skyblock server or pre-resolve)
        // default to "unknown" rather than a stale known zone: that way "+ New Waypoints"
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
            screen.selectedZoneId = focus.zoneId();
            screen.pendingFocusGroupId = focus.id();
        }
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    protected void init() {
        int footerY = height - FOOTER_H;
        deleteArmedUntil = 0L;
        deleteBtn = null;

        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("New Waypoints", this::createGroup));
        left.add(new GuiTokens.ButtonSpec("Edit", this::editSelected));
        // Fixed width so the label can toggle between "Delete" and "Confirm?" without
        // the footer re-flowing or the text sliding past the bevel.
        left.add(new GuiTokens.ButtonSpec(DELETE_LABEL, DELETE_BTN_W, this::onDeleteClicked));
        left.add(new GuiTokens.ButtonSpec("Import", this::importFromClipboard));
        left.add(new GuiTokens.ButtonSpec("Export Zone", this::exportZone));
        left.add(new GuiTokens.ButtonSpec("Settings", this::openSettings));
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);

        // We need a reference to the Delete button so we can repaint its label when it
        // arms/disarms. Intercept every built button and stash Delete; addRenderableWidget
        // still runs for all of them.
        GuiTokens.layoutFooter(width, footerY, left, done, b -> {
            if (DELETE_LABEL.contentEquals(b.getMessage().getString())) {
                deleteBtn = b;
                deleteBtn.setTooltip(Tooltip.create(Component.literal(DELETE_TOOLTIP_DEFAULT)));
            }
            addRenderableWidget(b);
        }, font);

        // Resolve a pending focus request from openFocused(). We do this here
        // rather than in the constructor because the zone's group list can
        // only be meaningfully indexed after the screen knows its current
        // zone -- the visibleGroups() list is keyed off selectedZoneId, which
        // is settled by the time init() runs.
        if (pendingFocusGroupId != null) {
            selectGroupById(pendingFocusGroupId);
            pendingFocusGroupId = null;
        }
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

    private void openSettings() {
        minecraft.setScreen(new ConfigScreen(this, config));
    }

    private List<String> zoneIds() {
        List<String> zones = new ArrayList<>(manager.knownZoneIds());
        Zone currentZone = manager.currentZone();
        if (currentZone != null && !zones.contains(currentZone.id())) zones.add(0, currentZone.id());
        if (zones.isEmpty()) zones.add(Zone.UNKNOWN.id());
        return zones;
    }

    private List<WaypointGroup> visibleGroups() {
        return manager.groupsForZone(selectedZoneId);
    }

    // --- render ------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        // Reset the Delete button label once the confirm/flash window elapses.
        // Doing this in render (rather than tick) keeps the screen dependency-free
        // and runs every frame which is plenty for a ~2.5s transition.
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
        String status = Zone.fromId(selectedZoneId).displayName() + "  ."
                + "  " + visibleGroups().size() + " group" + (visibleGroups().size() == 1 ? "" : "s");
        g.drawString(font, status, width - PAD_OUTER - font.width(status), PAD_OUTER, TEXT_DIM, false);

        // Region geometry
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP_SECTION;
        int mainRight = width - PAD_OUTER;

        renderSidebar(g, sidebarLeft, top, sidebarRight, bottom, mouseX, mouseY);
        renderMain(g, mainLeft, top, mainRight, bottom, mouseX, mouseY);
    }

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
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= rowY + ROW_H;
            drawZoneRow(g, x1, rowY, x2, id, selected, hovered, id.equals(currentId));
            rowY += ROW_H;
        }
    }

    private void drawZoneRow(GuiGraphics g, int x1, int y, int x2,
                             String zoneId, boolean selected, boolean hovered, boolean isCurrent) {
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);

        // "unknown" is intentionally quiet -- it's a placeholder zone, not the focal
        // point of an empty state. So it never gets the accent bar and renders in muted text.
        boolean isUnknown = Zone.UNKNOWN.id().equals(zoneId);
        if (selected && !isUnknown) {
            g.fill(x1, y, x1 + 2, y + ROW_H, ACCENT);
        }

        Zone z = Zone.fromId(zoneId);
        int textColor = isUnknown ? TEXT_MUTED : selected ? TEXT : TEXT_DIM;
        g.drawString(font, z.displayName(), x1 + GAP + 2, y + 6, textColor, false);

        // live "current zone" indicator -- a tiny filled dot, no color, just a glyph
        if (isCurrent) {
            int dotX = x2 - GAP - 6;
            g.fill(dotX, y + ROW_H / 2 - 2, dotX + 4, y + ROW_H / 2 + 2, ACCENT);
        }

        // Group count, right-aligned next to the dot (or at the edge if no dot)
        int count = manager.groupsForZone(zoneId).size();
        String countStr = Integer.toString(count);
        int countX = (isCurrent ? x2 - GAP - 12 : x2 - GAP) - font.width(countStr);
        g.drawString(font, countStr, countX, y + 6, TEXT_MUTED, false);
    }

    private void renderMain(GuiGraphics g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        List<WaypointGroup> groups = visibleGroups();
        if (groups.isEmpty()) {
            renderEmptyState(g, x1, y1);
            return;
        }

        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        g.enableScissor(x1, y1, x2, y2);

        int y = y1 + 4 - scrollOffset;
        int listW = x2 - x1;
        for (int i = 0; i < groups.size(); i++, y += ROW_H + 4) {
            int rowTop = y;
            int rowBot = y + ROW_H + 2;
            if (rowBot < y1 || rowTop > y2) continue;

            boolean hovered = mouseX >= x1 + 2 && mouseX <= x2 - 2
                    && mouseY >= rowTop && mouseY <= rowBot;
            renderGroupRow(g, groups.get(i), i, x1 + 2, rowTop, x2 - 2, listW,
                    hovered, i == selectedIndex);
        }
        g.disableScissor();
    }

    private void renderEmptyState(GuiGraphics g, int x1, int y1) {
        g.drawString(font, "No waypoint groups in this zone.",
                x1, y1 + 8, TEXT, false);
        g.drawString(font, "Click \"New Waypoints\" to start, or paste a codec into chat.",
                x1, y1 + 8 + 14, TEXT_DIM, false);
    }

    private void renderGroupRow(GuiGraphics g, WaypointGroup group, int index,
                                int x1, int y1, int x2, int listW,
                                boolean hovered, boolean selected) {
        int rowBot = y1 + ROW_H + 2;
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBot, bg);
        if (selected) g.fill(x1, y1, x1 + 2, rowBot, ACCENT);

        int textColor = group.enabled() ? TEXT : TEXT_MUTED;
        String name = group.name().isEmpty() ? "(unnamed)" : group.name();
        g.drawString(font, name, x1 + GAP + 2, y1 + 4, textColor, false);

        String sub = group.size() + " pts  @" + group.currentIndex();
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
        if (!zid.equals(selectedZoneId)) {
            String hint = "(" + zid + ")";
            g.drawString(font, hint, chipX - GAP - font.width(hint), y1 + 10,
                    TEXT_MUTED, false);
        }
    }

    // --- input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;

        double mx = event.x();
        double my = event.y();

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        // Sidebar click -> select zone
        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        if (mx >= sidebarLeft && mx <= sidebarRight && my >= top && my <= bottom) {
            int labelY = top + 10;
            int rowY = labelY + 14;
            List<String> ids = zoneIds();
            int idx = (int) ((my - rowY) / ROW_H);
            if (idx >= 0 && idx < ids.size()) {
                selectedZoneId = ids.get(idx);
                scrollOffset = 0;
                selectedIndex = -1;
            }
            return true;
        }

        // Main area click -> select group row (and toggle chip if within the right edge)
        int mainLeft = sidebarRight + GAP_SECTION;
        int mainRight = width - PAD_OUTER;
        if (mx < mainLeft || mx > mainRight || my < top || my > bottom) return false;

        List<WaypointGroup> groups = visibleGroups();
        if (groups.isEmpty()) return false;

        double yInList = my - (top + 4) + scrollOffset;
        int idx = (int) (yInList / (ROW_H + 4));
        if (idx < 0 || idx >= groups.size()) return false;

        WaypointGroup group = groups.get(idx);
        selectedIndex = idx;

        // Toggle-chip hit test -- rightmost region of the row.
        if (mx > mainRight - 40) {
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
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;
        int listHeight = bottom - top;
        int rowPitch = ROW_H + 4;
        int content = visibleGroups().size() * rowPitch;
        int maxScroll = Math.max(0, content - listHeight + 8);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (vert * rowPitch)));
        return true;
    }

    // --- actions -----------------------------------------------------------------------------

    private void createGroup() {
        WaypointGroup g = WaypointGroup.create("New group", selectedZoneId);
        g.setDefaultRadius(config.defaultReachRadius());
        manager.add(g);
        minecraft.setScreen(new GroupEditScreen(this, manager, config, g));
    }

    private void editSelected() {
        WaypointGroup g = currentSelection();
        if (g != null) minecraft.setScreen(new GroupEditScreen(this, manager, config, g));
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
                    "Click again to permanently delete \"" + g.name() + "\".\n"
                  + "Times out in 2.5 seconds.")));
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

    private void exportZone() {
        List<WaypointGroup> groups = visibleGroups();
        if (groups.isEmpty()) return;
        ExportScreen.openForGroups(this, config, groups, Zone.fromId(selectedZoneId).displayName());
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
            for (WaypointGroup g : result.groups()) manager.add(g);

            ImportFeedback.success(result.groups(), "clipboard");
            // Navigate the user to the first imported group so the import
            // result is visible immediately -- no more "did it work?" moments
            // where the user has to hunt through zone tabs.
            if (!result.groups().isEmpty()) {
                WaypointGroup first = result.groups().get(0);
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
