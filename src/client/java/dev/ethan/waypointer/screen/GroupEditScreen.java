package dev.ethan.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ethan.waypointer.color.GradientColorizer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.input.WaypointAddFlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Edits a single group.
 *
 * Layout (same clinical shape as WaypointerScreen):
 *   +------------------------------------------+
 *   | Edit: Dungeon F7           6 pts  @2     |
 *   |                                          |
 *   | [ Name           ] | waypoint list ...   |
 *   | [Gradient: AUTO  ]|                      |
 *   | [Mode: STATIC    ]|                      |
 *   | [-]  Radius 6.0  [+]                     |
 *   | [Sort: Nearest   ]|                      |
 *   | [Reset Progress  ]|                      |
 *   |                                          |
 *   | [+Add Here][Export][Remove][^][v]  [Done]|
 *   +------------------------------------------+
 *
 * All of the toggles that used to live in a horizontal button wall at the top
 * (Gradient, Mode, the 3 sort buttons, Radius -/+, Reset Progress, +Add Here)
 * are collapsed into a metadata sidebar, except +Add Here which moves to the
 * footer because it's a primary action rather than a setting.
 *
 * Every sidebar control carries a Tooltip because single-word labels like
 * "Mode" or "Gradient" hide real decisions behind jargon -- a hover tooltip
 * is a near-zero-cost way to explain the tradeoff before the player clicks.
 *
 * Sort is now one cycling button: Manual -> Nearest -> Y asc -> Y desc -> Manual.
 */
public final class GroupEditScreen extends Screen {

    private enum SortMode {
        MANUAL("Manual"), NEAREST("Nearest"), Y_ASC("Y asc"), Y_DESC("Y desc");
        final String label;
        SortMode(String label) { this.label = label; }
    }

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup group;

    private EditBox nameBox;
    private Button gradientBtn;
    private Button modeBtn;
    private Button sortBtn;
    private Button radiusMinusBtn;
    private Button radiusPlusBtn;
    // Two small colour swatch buttons for the gradient endpoints. Stored so
    // the colour-picker callback can push the new colour back onto the
    // correct widget without chasing it through the widget tree.
    private ColorSwatchButton gradientStartBtn;
    private ColorSwatchButton gradientEndBtn;

    private SortMode sortMode = SortMode.MANUAL;
    private int scrollOffset;
    private int selectedIndex = -1;

    // Inline per-row label editor: shown only while the user is renaming a waypoint,
    // positioned in render() so it tracks the row through scroll. We hold one EditBox
    // for the life of the screen rather than creating/destroying it per edit so focus
    // and caret handling route through the same widget Minecraft already knows about.
    private EditBox labelEditor;
    private int editingIndex = -1;

    // GLFW key constants -- inlined to avoid dragging in LWJGL for three numbers.
    private static final int GLFW_KEY_ESCAPE   = 256;
    private static final int GLFW_KEY_ENTER    = 257;
    private static final int GLFW_KEY_KP_ENTER = 335;

    /**
     * Snapshot of the manual state so cycling through the sort modes never loses
     * the route the player hand-arranged. Order AND current progress index are both
     * captured so an accidental cycle restores the editor to the exact state the
     * player would expect -- order alone would resurrect the route but leave them
     * at progress 0 after Nearest reset it on the way through.
     *
     * Captured on the first transition away from MANUAL, refreshed whenever the
     * player makes a manual edit (add/move/remove), restored when the cycle wraps
     * back to MANUAL. {@code null} means "nothing to restore": we don't pretend
     * there's a saved order before the user ever pressed the sort button.
     */
    private record ManualSnapshot(List<Waypoint> order, int currentIndex) {}
    private ManualSnapshot manualSnapshot = null;

    public GroupEditScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config, WaypointGroup group) {
        super(Component.literal("Edit: " + group.name()));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.group = group;
    }

    @Override
    protected void init() {
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int sidebarLeft = PAD_OUTER;
        int sidebarInner = sidebarLeft + GAP;
        int fieldW = SIDEBAR_W - GAP * 2;

        int y = top + 20;

        // Name field
        nameBox = new EditBox(font, sidebarInner, y, fieldW, BTN_H, Component.literal("Name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(group.name());
        nameBox.setResponder(group::setName);
        nameBox.setTooltip(Tooltip.create(Component.literal(
                "The group's display name. Shows in the zone list and in exports.")));
        addRenderableWidget(nameBox);
        y += BTN_H + GAP;

        // Gradient toggle
        gradientBtn = Button.builder(gradientLabel(), this::toggleGradientMode)
                .bounds(sidebarInner, y, fieldW, BTN_H)
                .tooltip(gradientTooltip())
                .build();
        addRenderableWidget(gradientBtn);
        y += BTN_H + GAP_TIGHT;

        // Gradient endpoint swatches. The button IS the colour rather than text
        // that describes it -- seeing both swatches side by side lets the player
        // eyeball the range of the gradient before opening either picker. The
        // full hex code is rendered just below the row for reference without
        // stealing the button face.
        int swatchW = (fieldW - GAP_TIGHT) / 2;
        gradientStartBtn = new ColorSwatchButton(sidebarInner, y, swatchW, BTN_H,
                "Start", group.gradientStartColor(), () -> openGradientPicker(true));
        gradientStartBtn.setTooltip(Tooltip.create(Component.literal(
                "First waypoint's colour. Gradient interpolates from here\n"
              + "to the end colour across the route. Only applies in AUTO\n"
              + "mode.")));
        gradientEndBtn = new ColorSwatchButton(sidebarInner + swatchW + GAP_TIGHT, y,
                fieldW - swatchW - GAP_TIGHT, BTN_H,
                "End", group.gradientEndColor(), () -> openGradientPicker(false));
        gradientEndBtn.setTooltip(Tooltip.create(Component.literal(
                "Last waypoint's colour. The gradient fades into this\n"
              + "colour. Only applies in AUTO mode.")));
        addRenderableWidget(gradientStartBtn);
        addRenderableWidget(gradientEndBtn);
        y += BTN_H + GAP_TIGHT;

        // Mode toggle
        modeBtn = Button.builder(modeLabel(), this::toggleLoadMode)
                .bounds(sidebarInner, y, fieldW, BTN_H)
                .tooltip(modeTooltip())
                .build();
        addRenderableWidget(modeBtn);
        y += BTN_H + GAP;

        // Radius row: [-]  Radius 6.0  [+]
        //
        // The label and value live on a transparent strip between the two bump buttons
        // rather than floating above the row -- detached labels read as disconnected
        // metadata and users miss them. Keeping the label inline also lets us drop the
        // extra 10px gap that used to pad the old floating-label layout.
        int bumpW = 24;
        radiusMinusBtn = Button.builder(Component.literal("-"), b -> bumpRadius(-0.5))
                .bounds(sidebarInner, y, bumpW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Decrease reach radius by 0.5 blocks.\n"
                      + "Radius sets how close you must stand to mark a waypoint reached.")))
                .build();
        radiusPlusBtn = Button.builder(Component.literal("+"), b -> bumpRadius(0.5))
                .bounds(sidebarInner + fieldW - bumpW, y, bumpW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Increase reach radius by 0.5 blocks.\n"
                      + "Radius sets how close you must stand to mark a waypoint reached.")))
                .build();
        addRenderableWidget(radiusMinusBtn);
        addRenderableWidget(radiusPlusBtn);
        y += BTN_H + GAP;

        // Sort cycle
        sortBtn = Button.builder(sortLabel(), this::cycleSort)
                .bounds(sidebarInner, y, fieldW, BTN_H)
                .tooltip(sortTooltip())
                .build();
        addRenderableWidget(sortBtn);
        y += BTN_H + GAP;

        // Reset progress (de-emphasized)
        addRenderableWidget(Button.builder(Component.literal("Reset Progress"), b -> {
            group.resetProgress();
            manager.fireDataChanged();
        }).bounds(sidebarInner, y, fieldW, BTN_H)
          .tooltip(Tooltip.create(Component.literal(
                  "Jump the 'current' marker back to the first waypoint.\n"
                + "Use this to retrace a route without deleting progress history.")))
          .build());

        // Inline label editor -- kept invisible until the user double-clicks a row.
        // Added last so it paints on top of the row it's editing. A fresh widget per
        // init() is fine: init() runs on resize and we drop any in-progress edit there.
        editingIndex = -1;
        labelEditor = new EditBox(font, 0, 0, 100, BTN_H, Component.literal("Label"));
        labelEditor.setMaxLength(64);
        labelEditor.setVisible(false);
        addRenderableWidget(labelEditor);

        // Footer
        int footerY = height - FOOTER_H;

        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("+ Add Here", this::addHere));
        left.add(new GuiTokens.ButtonSpec("+ Add Temp", this::addTempHere));
        left.add(new GuiTokens.ButtonSpec("Export", this::export));
        left.add(new GuiTokens.ButtonSpec("Remove", this::removeSelected));
        left.add(new GuiTokens.ButtonSpec("^", 24, () -> moveSelected(-1)));
        left.add(new GuiTokens.ButtonSpec("v", 24, () -> moveSelected(+1)));
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);

        GuiTokens.layoutFooter(width, footerY, left, done, this::addRenderableWidget, font);
    }

    // --- sidebar toggles ---------------------------------------------------------------------

    private Component gradientLabel() {
        return Component.literal("Gradient: "
                + (group.gradientMode() == WaypointGroup.GradientMode.AUTO ? "AUTO" : "MANUAL"));
    }

    // Tooltip content is duplicated on both branches (not just the "current" mode) so the
    // hover surface always explains both options -- a user who doesn't know what AUTO does
    // wouldn't know what to compare it to if we only described the state they're not in.
    private static Tooltip gradientTooltip() {
        return Tooltip.create(Component.literal(
                "How waypoint colors are assigned.\n"
              + "AUTO: interpolate a gradient across the route.\n"
              + "MANUAL: keep each waypoint's custom color as-is."));
    }

    private void toggleGradientMode(Button b) {
        group.setGradientMode(group.gradientMode() == WaypointGroup.GradientMode.AUTO
                ? WaypointGroup.GradientMode.MANUAL : WaypointGroup.GradientMode.AUTO);
        b.setMessage(gradientLabel());
        manager.fireDataChanged();
    }

    private void openGradientPicker(boolean start) {
        int current = start ? group.gradientStartColor() : group.gradientEndColor();
        String title = (start ? "Gradient Start" : "Gradient End") + " Colour";
        ColorPickerScreen.open(this, title, current, picked -> {
            if (start) group.setGradientStartColor(picked);
            else       group.setGradientEndColor(picked);
            // Push the new colour onto the swatch so the sidebar reflects the
            // change immediately without re-running init().
            if (gradientStartBtn != null) gradientStartBtn.setColor(group.gradientStartColor());
            if (gradientEndBtn   != null) gradientEndBtn.setColor(group.gradientEndColor());
            manager.fireDataChanged();
        });
    }

    private Component modeLabel() {
        return Component.literal("Mode: "
                + (group.loadMode() == WaypointGroup.LoadMode.STATIC ? "STATIC" : "SEQUENCE"));
    }

    private static Tooltip modeTooltip() {
        return Tooltip.create(Component.literal(
                "Which waypoints are drawn in the world.\n"
              + "STATIC: show every waypoint at once.\n"
              + "SEQUENCE: show only the previous, current, and next waypoint --\n"
              + "  best for ordered routes where clutter gets in the way."));
    }

    private void toggleLoadMode(Button b) {
        group.setLoadMode(group.loadMode() == WaypointGroup.LoadMode.STATIC
                ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        b.setMessage(modeLabel());
        manager.fireDataChanged();
    }

    private void bumpRadius(double delta) {
        group.setDefaultRadius(group.defaultRadius() + delta);
        manager.fireDataChanged();
    }

    private Component sortLabel() {
        return Component.literal("Sort: " + sortMode.label);
    }

    private static Tooltip sortTooltip() {
        return Tooltip.create(Component.literal(
                "Cycles the route order. Click to advance:\n"
              + "Manual -> Nearest -> Y ascending -> Y descending -> Manual.\n"
              + "Nearest / Y asc / Y desc re-sort the list immediately.\n"
              + "Manual restores the hand-arranged order you had the last time\n"
              + "you edited or entered Manual -- cycling through the sort modes\n"
              + "never wipes your custom order."));
    }

    // Cycles through MANUAL -> NEAREST -> Y asc -> Y desc -> MANUAL. Leaving MANUAL
    // snapshots the current order so cycling back restores it verbatim; auto-sort
    // modes apply immediately. A Manual -> Manual cycle is impossible by construction.
    private void cycleSort(Button b) {
        SortMode next = switch (sortMode) {
            case MANUAL  -> SortMode.NEAREST;
            case NEAREST -> SortMode.Y_ASC;
            case Y_ASC   -> SortMode.Y_DESC;
            case Y_DESC  -> SortMode.MANUAL;
        };

        // The transition out of MANUAL is the last safe moment to capture what the
        // user considers "manual" -- any auto-sort below would overwrite the list,
        // and we need something to hand back when the cycle lands on MANUAL again.
        if (sortMode == SortMode.MANUAL && next != SortMode.MANUAL) {
            manualSnapshot = new ManualSnapshot(new ArrayList<>(group.waypoints()), group.currentIndex());
        }

        sortMode = next;
        b.setMessage(sortLabel());

        switch (sortMode) {
            case NEAREST -> sortByDistance();
            case Y_ASC   -> sortByY(true);
            case Y_DESC  -> sortByY(false);
            case MANUAL  -> restoreManualOrder();
        }
    }

    /**
     * Puts the group back into the player's last hand-arranged order AND progress
     * index. If we never snapshotted (i.e. the player never left MANUAL this
     * session) the existing state IS the manual state, and we no-op rather than
     * clobber it.
     */
    private void restoreManualOrder() {
        if (manualSnapshot == null) return;
        replaceAll(manualSnapshot.order());
        group.setCurrentIndex(manualSnapshot.currentIndex());
        manager.fireDataChanged();
    }

    // --- actions ----------------------------------------------------------------------------

    private void addTempHere() {
        // Temps always land in the per-zone temp bucket regardless of which
        // group we opened this screen from -- see AddTempScreen for the
        // rationale (keeps real routes free of expiring entries).
        AddTempScreen.open(this, manager, config);
    }

    private void addHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        group.add(new Waypoint(
                (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()),
                "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        // Run the shared post-add flow (auto-disable skip-ahead + toast) so the
        // GUI add button behaves identically to /wp add and the keybind path.
        new WaypointAddFlow(config).afterWaypointAdded(group);
        manager.fireDataChanged();
        onManualEdit();
    }

    /**
     * Called after any manual mutation of the list (add / move / remove). Drops the
     * sort label back to MANUAL and refreshes the snapshot so the new order IS the
     * manual state: cycling Nearest -> Y asc -> Y desc -> Manual will hand it back
     * unchanged. Without this, the snapshot would go stale and "restore" to a
     * pre-edit list, silently undoing the player's edit.
     */
    private void onManualEdit() {
        if (sortMode != SortMode.MANUAL) {
            sortMode = SortMode.MANUAL;
            if (sortBtn != null) sortBtn.setMessage(sortLabel());
        }
        manualSnapshot = new ManualSnapshot(new ArrayList<>(group.waypoints()), group.currentIndex());
    }

    private void sortByDistance() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || group.size() <= 1) return;
        // Nearest-neighbour greedy sort: does what the player means by "put the nearest
        // one first" better than raw distance-to-player (which would reorder globally
        // even when the current head is already fine).
        double cx = p.getX(), cy = p.getY(), cz = p.getZ();
        List<Waypoint> pts = new ArrayList<>(group.waypoints());
        List<Waypoint> sorted = new ArrayList<>(pts.size());
        while (!pts.isEmpty()) {
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                Waypoint w = pts.get(i);
                double dx = (w.x() + 0.5) - cx;
                double dy = (w.y() + 0.5) - cy;
                double dz = (w.z() + 0.5) - cz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestDist) { bestDist = d; best = i; }
            }
            Waypoint w = pts.remove(best);
            sorted.add(w);
            cx = w.x(); cy = w.y(); cz = w.z();
        }
        replaceAll(sorted);
    }

    private void sortByY(boolean ascending) {
        if (group.size() <= 1) return;
        List<Waypoint> pts = new ArrayList<>(group.waypoints());
        pts.sort((a, b) -> ascending ? Integer.compare(a.y(), b.y()) : Integer.compare(b.y(), a.y()));
        replaceAll(pts);
    }

    private void replaceAll(List<Waypoint> pts) {
        // Preserve gradient mode + progress intentionally: user expects the colors to
        // re-gradient across the new order, and starting progress over is the least
        // surprising behaviour after a sort.
        WaypointGroup.GradientMode mode = group.gradientMode();
        while (group.size() > 0) group.remove(group.size() - 1);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (Waypoint w : pts) group.add(w);
        group.setGradientMode(mode);
        group.setCurrentIndex(0);
        manager.fireDataChanged();
        if (mode == WaypointGroup.GradientMode.AUTO) GradientColorizer.apply(group);
    }

    private void removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= group.size()) return;
        group.remove(selectedIndex);
        selectedIndex = Math.min(selectedIndex, group.size() - 1);
        manager.fireDataChanged();
        onManualEdit();
    }

    private void moveSelected(int delta) {
        if (selectedIndex < 0 || selectedIndex >= group.size()) return;
        int to = Math.max(0, Math.min(group.size() - 1, selectedIndex + delta));
        if (to == selectedIndex) return;
        group.move(selectedIndex, to);
        selectedIndex = to;
        manager.fireDataChanged();
        onManualEdit();
    }

    private void export() {
        ExportScreen.openForGroup(this, config, group);
    }

    // --- render -----------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        // Header
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        String status = group.size() + " pts  .  @" + group.currentIndex()
                + "  .  radius " + String.format("%.1f", group.defaultRadius());
        g.drawString(font, status, width - PAD_OUTER - font.width(status), PAD_OUTER, TEXT_DIM, false);

        // Discoverability hint for the two non-obvious list gestures. Placed under
        // the title in TEXT_MUTED so it reads as ambient help rather than UI chrome.
        // A tooltip would hide these behind a hover the user has to guess at; an
        // always-visible line is cheaper than documentation they won't read.
        String hint = "double-click: rename  .  right-click: set current";
        g.drawString(font, hint, PAD_OUTER, PAD_OUTER + 11, TEXT_MUTED, false);

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP_SECTION;
        int mainRight = width - PAD_OUTER;

        renderSidebarPanel(g, sidebarLeft, top, sidebarRight, bottom);
        renderMain(g, mainLeft, top, mainRight, bottom, mouseX, mouseY);
    }

    private void renderSidebarPanel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        // A faint white-wash instead of another dark fill. Stacking SURFACE / SURFACE_SUBTLE
        // (both dark) behind already-dark EditBox/Button widgets made the whole sidebar
        // read as a black slab against the world. A gentle light overlay separates the
        // region from the scene without compounding darkness on the controls.
        g.fill(x1, y1, x2, y2, SIDEBAR_BG);
        g.fill(x2, y1, x2 + 1, y2, BORDER);
        g.drawString(font, "Group", x1 + GAP, y1 + 10, TEXT, false);

        // Inline "Radius 3.0" readout spanning the space between the two bump buttons.
        // The label is co-located with the value so there's no detached header for the
        // user to miss, and the whole row visually reads as one control.
        if (radiusMinusBtn != null && radiusPlusBtn != null) {
            int rowMidY = radiusMinusBtn.getY() + BTN_H / 2 - 4;
            int inlineLeft = radiusMinusBtn.getX() + radiusMinusBtn.getWidth();
            int inlineRight = radiusPlusBtn.getX();
            String text = "Radius " + String.format("%.1f", group.defaultRadius());
            int textW = font.width(text);
            int textX = inlineLeft + ((inlineRight - inlineLeft) - textW) / 2;
            g.drawString(font, text, textX, rowMidY, TEXT, false);
        }
    }

    /** Lighter sidebar wash -- mild white overlay, roughly 12% alpha. */
    private static final int SIDEBAR_BG = 0x20FFFFFF;

    private void renderMain(GuiGraphics g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        List<Waypoint> pts = group.waypoints();
        if (pts.isEmpty()) {
            g.drawString(font, "No waypoints yet.", x1, y1 + 8, TEXT, false);
            g.drawString(font, "Walk somewhere and click \"+ Add Here\".",
                    x1, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }

        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        g.enableScissor(x1, y1, x2, y2);
        int y = y1 + 4 - scrollOffset;
        int pitch = ROW_H + 2;
        for (int i = 0; i < pts.size(); i++, y += pitch) {
            if (y + ROW_H < y1 || y > y2) continue;
            renderWaypointRow(g, pts.get(i), i, x1 + 2, y, x2 - 2, mouseX, mouseY);
        }
        g.disableScissor();

        // Re-place the editor each frame so it follows the row through layout changes
        // (window resize changes column widths; scroll shifts the row Y). The actual
        // EditBox widget draw is handled by super.render -> addRenderableWidget.
        if (editingIndex >= 0 && editingIndex < pts.size()) {
            positionLabelEditor(editingIndex);
        }
    }

    private void renderWaypointRow(GuiGraphics g, Waypoint w, int index,
                                   int x1, int y1, int x2, int mouseX, int mouseY) {
        boolean selected = index == selectedIndex;
        boolean isCurrent = index == group.currentIndex();
        boolean hovered = mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y1 + ROW_H;

        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, y1 + ROW_H, bg);
        if (selected) g.fill(x1, y1, x1 + 2, y1 + ROW_H, ACCENT);

        // Color swatch. Clickable: opens ColorPickerScreen for a per-waypoint colour
        // override. A thin lock ring is drawn around the swatch when the waypoint's
        // colour is locked so users know the gradient won't repaint this one.
        int sx = x1 + GAP + 2;
        int sy = y1 + 4;
        int swatchColor = 0xFF000000 | (w.color() & 0xFFFFFF);
        g.fill(sx, sy, sx + 14, sy + 14, swatchColor);
        if (w.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
            // 1px white inset so the lock state is visible on any swatch colour --
            // a coloured border would disappear against similar colours.
            g.fill(sx - 1, sy - 1, sx + 15, sy,      0xFFFFFFFF);
            g.fill(sx - 1, sy + 14, sx + 15, sy + 15, 0xFFFFFFFF);
            g.fill(sx - 1, sy, sx, sy + 14,           0xFFFFFFFF);
            g.fill(sx + 14, sy, sx + 15, sy + 14,     0xFFFFFFFF);
        }

        // Row labels use 1-indexed numbers so they line up with the world labels
        // emitted by WaypointRenderer ("#1", "#2", ...). Coords are parenthesised so
        // the ordinal reads as "item N" rather than being eaten by the first number
        // in a raw "[0] 123, 64, -77" string.
        String label = "#" + (index + 1) + "  (" + w.x() + ", " + w.y() + ", " + w.z() + ")";
        int textColor = isCurrent ? 0xFFFFF080
                : index < group.currentIndex() ? TEXT_MUTED
                : TEXT;
        g.drawString(font, label, sx + 20, y1 + 7, textColor, false);

        // Skip the static name while the row is being renamed -- the EditBox widget sits
        // on top of this slot and drawing the old name behind it leaks through at the
        // edges of the edit box when the caret is mid-text.
        if (w.hasName() && index != editingIndex) {
            g.drawString(font, w.name(), sx + 20 + font.width(label) + GAP, y1 + 7, TEXT_DIM, false);
        }

        if (w.customRadius() > 0) {
            String r = "r=" + String.format("%.1f", w.customRadius());
            g.drawString(font, r, x2 - GAP - font.width(r), y1 + 7, TEXT_DIM, false);
        } else if (isCurrent) {
            String tag = "current";
            g.drawString(font, tag, x2 - GAP - font.width(tag), y1 + 7, 0xFFFFF080, false);
        }
    }

    // --- input -------------------------------------------------------------------------------

    /** GLFW mouse buttons we care about. Inlined so this file doesn't pull in LWJGL. */
    private static final int MOUSE_BUTTON_LEFT  = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A click that lands outside the live editor means the user is done with it.
        // Commit first so the click itself still performs whatever it would have done
        // (select another row, open a double-click edit on a new row, press a button).
        if (editingIndex >= 0 && !isOverLabelEditor(event.x(), event.y())) {
            commitLabelEdit();
        }

        // Right-click on a waypoint row sets progress to that waypoint. Handled
        // before super.mouseClicked because widgets (buttons, EditBoxes) ignore
        // right clicks anyway, and running super first would swallow the event
        // over any widget that happens to sit under the list area.
        if (event.button() == MOUSE_BUTTON_RIGHT) {
            int idx = rowIndexAt(event.x(), event.y());
            if (idx >= 0) {
                group.setCurrentIndex(idx);
                selectedIndex = idx;
                manager.fireDataChanged();
                return true;
            }
        }

        // Swatch-click: open per-waypoint colour picker. Checked before super
        // because the list area doesn't host widgets, so super would fall
        // through to the row-click path and we'd lose the shift-click affordance
        // for "unlock colour" below.
        if (event.button() == MOUSE_BUTTON_LEFT) {
            int swatchIdx = swatchIndexAt(event.x(), event.y());
            if (swatchIdx >= 0) {
                // Poll the shift key directly off the window rather than through a
                // Screen helper: the old `Screen.hasShiftDown()` helper was split
                // into per-event Modifiers in 1.21.11 and isn't reachable from a
                // mouse callback without the event's modifier bits, which aren't
                // currently exposed on MouseButtonInfo's public API.
                var win = Minecraft.getInstance().getWindow();
                boolean shift = InputConstants.isKeyDown(win, InputConstants.KEY_LSHIFT)
                        || InputConstants.isKeyDown(win, 344 /* GLFW_KEY_RIGHT_SHIFT */);
                if (shift && group.get(swatchIdx).hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
                    unlockWaypointColor(swatchIdx);
                } else {
                    openWaypointColorPicker(swatchIdx);
                }
                selectedIndex = swatchIdx;
                return true;
            }
        }

        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != MOUSE_BUTTON_LEFT) return false;

        int idx = rowIndexAt(event.x(), event.y());
        if (idx < 0) return false;
        selectedIndex = idx;

        if (doubleClick) beginLabelEdit(idx);
        return true;
    }

    /**
     * Hit-test the 14px colour swatch on row {@code idx}. Returns the row index
     * if {@code (mx, my)} is inside a swatch, else -1. Mirrors the geometry
     * used by {@link #renderWaypointRow}: row pitch, list clip, swatch X/Y offsets.
     */
    private int swatchIndexAt(double mx, double my) {
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;
        int mainLeft = PAD_OUTER + SIDEBAR_W + GAP_SECTION;
        int mainRight = width - PAD_OUTER;
        if (mx < mainLeft || mx > mainRight || my < top || my > bottom) return -1;

        int pitch = ROW_H + 2;
        int idx = (int) ((my - (top + 4) + scrollOffset) / pitch);
        if (idx < 0 || idx >= group.size()) return -1;

        int rowY = top + 4 - scrollOffset + idx * pitch;
        int sx = (mainLeft + 2) + GAP + 2;
        int sy = rowY + 4;
        if (mx >= sx && mx < sx + 14 && my >= sy && my < sy + 14) return idx;
        return -1;
    }

    private void openWaypointColorPicker(int idx) {
        Waypoint w = group.get(idx);
        ColorPickerScreen.open(this, "Waypoint #" + (idx + 1) + " Colour", w.color(), picked -> {
            // Picking a colour implicitly locks the waypoint -- otherwise the
            // next gradient recolour would wipe the user's choice. Users who
            // want to re-gradient an individual waypoint can shift-click the
            // swatch to clear the lock.
            Waypoint cur = group.get(idx);
            group.set(idx, cur.withColor(picked).withFlags(cur.flags() | Waypoint.FLAG_LOCKED_COLOR));
            manager.fireDataChanged();
        });
    }

    private void unlockWaypointColor(int idx) {
        Waypoint w = group.get(idx);
        int cleared = w.flags() & ~Waypoint.FLAG_LOCKED_COLOR;
        group.set(idx, w.withFlags(cleared));
        // Re-run the gradient so the just-unlocked waypoint immediately picks
        // up its place in the sweep instead of lingering on its old manual colour.
        if (group.gradientMode() == WaypointGroup.GradientMode.AUTO) {
            GradientColorizer.apply(group);
        }
        manager.fireDataChanged();
    }

    /**
     * Maps a screen-space point to a waypoint list index, or {@code -1} if the point
     * is outside the list area or on an empty row past the last waypoint.
     *
     * Extracted because both the click handler and the right-click progress
     * shortcut need the exact same hit-test, and keeping the math in one place
     * means a future layout change (sidebar width, row height) only has to be
     * updated once.
     */
    private int rowIndexAt(double mx, double my) {
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;
        int mainLeft = PAD_OUTER + SIDEBAR_W + GAP_SECTION;
        int mainRight = width - PAD_OUTER;
        if (mx < mainLeft || mx > mainRight || my < top || my > bottom) return -1;

        int pitch = ROW_H + 2;
        int idx = (int) ((my - (top + 4) + scrollOffset) / pitch);
        return (idx >= 0 && idx < group.size()) ? idx : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        // Scrolling while editing would drift the EditBox away from its row (the widget
        // x/y is cached by focus/caret logic mid-frame). Committing keeps the edit tied
        // to the row the user aimed at -- less surprising than silently cancelling it.
        if (editingIndex >= 0) commitLabelEdit();

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;
        int listHeight = bottom - top;
        int pitch = ROW_H + 2;
        int content = group.size() * pitch;
        int maxScroll = Math.max(0, content - listHeight + 8);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (vert * pitch)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editingIndex >= 0) {
            int k = event.key();
            if (k == GLFW_KEY_ESCAPE) {
                // Intercept ESC so it cancels the edit instead of closing the screen.
                cancelLabelEdit();
                return true;
            }
            if (k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) {
                commitLabelEdit();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    // --- inline label editing ---------------------------------------------------------------

    /**
     * Opens the inline EditBox over the given row's name slot, preloaded with the current
     * name and focused so the player can start typing immediately. The row is force-selected
     * so the visual focus (accent bar + SELECTED fill) matches where the editor sits.
     */
    private void beginLabelEdit(int index) {
        if (index < 0 || index >= group.size()) return;
        selectedIndex = index;
        editingIndex = index;

        Waypoint w = group.get(index);
        labelEditor.setValue(w.name());
        labelEditor.moveCursorToEnd(false);
        labelEditor.setVisible(true);
        setFocused(labelEditor);
        labelEditor.setFocused(true);
        positionLabelEditor(index);
    }

    private void commitLabelEdit() {
        if (editingIndex < 0) return;
        int idx = editingIndex;
        if (idx < group.size()) {
            Waypoint w = group.get(idx);
            String newName = labelEditor.getValue();
            if (!w.name().equals(newName)) {
                group.set(idx, w.withName(newName));
                manager.fireDataChanged();
            }
        }
        stopLabelEdit();
    }

    private void cancelLabelEdit() {
        stopLabelEdit();
    }

    private void stopLabelEdit() {
        editingIndex = -1;
        labelEditor.setVisible(false);
        labelEditor.setFocused(false);
        setFocused(null);
        labelEditor.setValue("");
    }

    /**
     * Places the EditBox over the name slot of the row at {@code index}, sized to fill
     * the remaining row width. Called every frame while editing so the widget tracks
     * any layout change (resize) and stays aligned with the row number/coord prefix.
     */
    private void positionLabelEditor(int index) {
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int mainLeft = PAD_OUTER + SIDEBAR_W + GAP_SECTION;
        int mainRight = width - PAD_OUTER;

        int pitch = ROW_H + 2;
        int rowY = top + 4 - scrollOffset + index * pitch;

        int rowX1 = mainLeft + 2;
        int rowX2 = mainRight - 2;
        int sx = rowX1 + GAP + 2;
        int labelStart = sx + 20;
        Waypoint w = group.get(index);
        String prefix = "#" + (index + 1) + "  (" + w.x() + ", " + w.y() + ", " + w.z() + ")";
        int editorX = labelStart + font.width(prefix) + GAP;
        int editorW = Math.max(80, rowX2 - GAP - editorX);

        labelEditor.setX(editorX);
        labelEditor.setY(rowY + 1);
        labelEditor.setWidth(editorW);
    }

    private boolean isOverLabelEditor(double mx, double my) {
        if (!labelEditor.visible) return false;
        int x1 = labelEditor.getX();
        int y1 = labelEditor.getY();
        int x2 = x1 + labelEditor.getWidth();
        int y2 = y1 + BTN_H;
        return mx >= x1 && mx <= x2 && my >= y1 && my <= y2;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
