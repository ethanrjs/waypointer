package dev.ethan.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ethan.waypointer.color.GradientColorizer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.input.WaypointAddFlow;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
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

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup group;

    private EditBox nameBox;
    private Button gradientBtn;
    private Button modeBtn;
    private Button radiusMinusBtn;
    private Button radiusPlusBtn;
    private Button skipAheadBtn;
    private Button moveSelectedHereBtn;
    private EditBox coordXBox;
    private EditBox coordYBox;
    private EditBox coordZBox;
    // Two small colour swatch buttons for the gradient endpoints. Stored so
    // the colour-picker callback can push the new colour back onto the
    // correct widget without chasing it through the widget tree.
    private ColorSwatchButton gradientStartBtn;
    private ColorSwatchButton gradientEndBtn;

    private int scrollOffset;
    private int selectedIndex = -1;
    private int coordinateEditorIndex = -1;
    private boolean syncingCoordinateEditors;

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
                "Group display name.\n"
              + "Used in lists and exports.")));
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
                "Gradient start colour.\n"
              + "Applies in AUTO mode.")));
        gradientEndBtn = new ColorSwatchButton(sidebarInner + swatchW + GAP_TIGHT, y,
                fieldW - swatchW - GAP_TIGHT, BTN_H,
                "End", group.gradientEndColor(), () -> openGradientPicker(false));
        gradientEndBtn.setTooltip(Tooltip.create(Component.literal(
                "Gradient end colour.\n"
              + "Applies in AUTO mode.")));
        addRenderableWidget(gradientStartBtn);
        addRenderableWidget(gradientEndBtn);
        updateGradientButtons();
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
                        "Shrink reach radius.\n"
                      + "-0.5 blocks.")))
                .build();
        radiusPlusBtn = Button.builder(Component.literal("+"), b -> bumpRadius(0.5))
                .bounds(sidebarInner + fieldW - bumpW, y, bumpW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Grow reach radius.\n"
                      + "+0.5 blocks.")))
                .build();
        addRenderableWidget(radiusMinusBtn);
        addRenderableWidget(radiusPlusBtn);
        y += BTN_H + GAP;

        skipAheadBtn = Button.builder(skipAheadLabel(), this::toggleSkipAhead)
                .bounds(sidebarInner, y, fieldW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Toggle skipping waypoints for this group.")))
                .build();
        addRenderableWidget(skipAheadBtn);
        y += BTN_H + GAP;

        addCoordinateEditors(sidebarInner, y, fieldW);
        y += BTN_H + GAP;

        moveSelectedHereBtn = Button.builder(Component.literal("Move Selected Here"), b -> moveSelectedHere())
                .bounds(sidebarInner, y, fieldW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Replace the selected waypoint's coordinates with your\n"
                      + "current block position.")))
                .build();
        addRenderableWidget(moveSelectedHereBtn);

        int sidebarBottom = height - FOOTER_H - GAP_SECTION;
        int resetY = sidebarBottom - BTN_H - GAP;
        addRenderableWidget(Button.builder(Component.literal("Reset Progress"), b -> {
            group.resetProgress();
            manager.fireDataChanged();
        }).bounds(sidebarInner, resetY, fieldW, BTN_H)
          .tooltip(Tooltip.create(Component.literal(
                  "Set current waypoint to #1.")))
          .build());

        // Inline label editor -- kept invisible until the user double-clicks a row.
        // Added last so it paints on top of the row it's editing. A fresh widget per
        // init() is fine: init() runs on resize and we drop any in-progress edit there.
        editingIndex = -1;
        labelEditor = new EditBox(font, 0, 0, 100, BTN_H, Component.literal("Label"));
        labelEditor.setMaxLength(64);
        labelEditor.setVisible(false);
        addRenderableWidget(labelEditor);
        syncCoordinateEditors();

        // Footer
        int footerY = height - FOOTER_H;

        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("+ Add Here", this::addHere));
        left.add(new GuiTokens.ButtonSpec("+ Add Named", this::addNamedHere));
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
                "Waypoint colour mode.\n"
              + "AUTO: gradient across route.\n"
              + "MANUAL: keep custom colours."));
    }

    private void toggleGradientMode(Button b) {
        group.setGradientMode(group.gradientMode() == WaypointGroup.GradientMode.AUTO
                ? WaypointGroup.GradientMode.MANUAL : WaypointGroup.GradientMode.AUTO);
        b.setMessage(gradientLabel());
        updateGradientButtons();
        manager.fireDataChanged();
    }

    private void updateGradientButtons() {
        boolean active = group.gradientMode() == WaypointGroup.GradientMode.AUTO;
        if (gradientStartBtn != null) gradientStartBtn.active = active;
        if (gradientEndBtn != null) gradientEndBtn.active = active;
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
                "Static: Show all waypoints\n"
              + "Sequenced: Go one-by-one"));
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

    private Component skipAheadLabel() {
        return Component.literal("Skip Ahead: " + (group.skipAheadEnabled() ? "ON" : "OFF"));
    }

    private void toggleSkipAhead(Button b) {
        group.setSkipAheadEnabled(!group.skipAheadEnabled());
        b.setMessage(skipAheadLabel());
        manager.fireDataChanged();
    }

    private void addCoordinateEditors(int x, int y, int width) {
        int boxW = (width - GAP_TIGHT * 2) / 3;
        coordXBox = createCoordinateEditor("X", 0);
        coordYBox = createCoordinateEditor("Y", 1);
        coordZBox = createCoordinateEditor("Z", 2);

        coordXBox.setX(x);
        coordYBox.setX(x + boxW + GAP_TIGHT);
        coordZBox.setX(x + (boxW + GAP_TIGHT) * 2);
        coordXBox.setY(y);
        coordYBox.setY(y);
        coordZBox.setY(y);
        coordXBox.setWidth(boxW);
        coordYBox.setWidth(boxW);
        coordZBox.setWidth(width - boxW * 2 - GAP_TIGHT * 2);

        addRenderableWidget(coordXBox);
        addRenderableWidget(coordYBox);
        addRenderableWidget(coordZBox);
    }

    private EditBox createCoordinateEditor(String label, int axis) {
        EditBox box = new EditBox(font, 0, 0, 40, BTN_H, Component.literal(label));
        box.setMaxLength(12);
        box.setHint(Component.literal(label));
        box.setTooltip(Tooltip.create(Component.literal(
                "Edit the selected waypoint's " + label + " coordinate.")));
        box.setResponder(v -> updateSelectedCoordinate(axis, v));
        return box;
    }

    private void updateSelectedCoordinate(int axis, String raw) {
        if (syncingCoordinateEditors) return;
        if (!hasSelectedWaypoint() || raw.isBlank()) return;

        try {
            int value = Integer.parseInt(raw.trim());
            Waypoint w = group.get(selectedIndex);
            int x = axis == 0 ? value : w.x();
            int y = axis == 1 ? value : w.y();
            int z = axis == 2 ? value : w.z();
            group.set(selectedIndex, w.withPos(x, y, z));
            manager.fireDataChanged();
        } catch (NumberFormatException ignored) {
            // Partial integer edits such as "-" are expected while typing.
        }
    }

    // --- actions ----------------------------------------------------------------------------

    private boolean hasSelectedWaypoint() {
        return selectedIndex >= 0 && selectedIndex < group.size();
    }

    private void selectWaypoint(int index) {
        selectedIndex = index >= 0 && index < group.size() ? index : -1;
        syncCoordinateEditors();
    }

    private void syncCoordinateEditors() {
        if (coordXBox == null || coordYBox == null || coordZBox == null) return;

        boolean hasSelection = hasSelectedWaypoint();
        coordXBox.active = hasSelection;
        coordYBox.active = hasSelection;
        coordZBox.active = hasSelection;
        if (moveSelectedHereBtn != null) moveSelectedHereBtn.active = hasSelection;

        if (!hasSelection) {
            coordinateEditorIndex = -1;
            setCoordinateEditorValues("", "", "");
            return;
        }

        if (coordinateEditorIndex == selectedIndex) return;
        Waypoint w = group.get(selectedIndex);
        coordinateEditorIndex = selectedIndex;
        setCoordinateEditorValues(Integer.toString(w.x()), Integer.toString(w.y()), Integer.toString(w.z()));
    }

    private void setCoordinateEditorValues(String x, String y, String z) {
        syncingCoordinateEditors = true;
        coordXBox.setValue(x);
        coordYBox.setValue(y);
        coordZBox.setValue(z);
        syncingCoordinateEditors = false;
    }

    private void moveSelectedHere() {
        if (!hasSelectedWaypoint()) return;

        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;

        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        group.moveWaypointTo(selectedIndex, pos.x(), pos.y(), pos.z());
        coordinateEditorIndex = -1;
        syncCoordinateEditors();
        manager.fireDataChanged();
    }

    private void addTempHere() {
        // Temps always land in the per-zone temp bucket regardless of which
        // group we opened this screen from -- see AddTempScreen for the
        // rationale (keeps real routes free of expiring entries).
        AddTempScreen.open(this, manager, config);
    }

    private void addNamedHere() {
        AddNamedWaypointScreen.open(this, manager, config, group);
    }

    private void addHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        group.add(new Waypoint(
                pos.x(), pos.y(), pos.z(),
                "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        int newIndex = group.size() - 1;
        // Run the shared post-add flow (focus + mode/toast updates) so the
        // GUI add button behaves identically to /wp add and the keybind path.
        new WaypointAddFlow().afterWaypointAdded(group, newIndex);
        if (skipAheadBtn != null) skipAheadBtn.setMessage(skipAheadLabel());
        selectWaypoint(newIndex);
        manager.fireDataChanged();
    }

    private void removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= group.size()) return;
        group.remove(selectedIndex);
        coordinateEditorIndex = -1;
        selectWaypoint(Math.min(selectedIndex, group.size() - 1));
        manager.fireDataChanged();
    }

    private void moveSelected(int delta) {
        if (selectedIndex < 0 || selectedIndex >= group.size()) return;
        int to = Math.max(0, Math.min(group.size() - 1, selectedIndex + delta));
        if (to == selectedIndex) return;
        group.move(selectedIndex, to);
        selectWaypoint(to);
        manager.fireDataChanged();
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
                selectWaypoint(idx);
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
                selectWaypoint(swatchIdx);
                return true;
            }
        }

        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != MOUSE_BUTTON_LEFT) return false;

        int idx = rowIndexAt(event.x(), event.y());
        if (idx < 0) return false;
        selectWaypoint(idx);

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
        selectWaypoint(index);
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
