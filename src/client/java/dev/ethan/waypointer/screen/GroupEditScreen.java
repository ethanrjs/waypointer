package dev.ethan.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ethan.waypointer.color.GradientColorizer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.RouteProgress;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.input.WaypointRepositionMode;
import dev.ethan.waypointer.text.AmpersandFormatting;
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
    private Button colorModeBtn;
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
    private ColorSwatchButton staticColorBtn;
    private ColorSwatchButton gradientStartBtn;
    private ColorSwatchButton gradientEndBtn;
    private int waypointColorPickerIndex = -1;

    private int scrollOffset;
    private int selectedIndex = -1;
    private int coordinateEditorIndex = -1;
    private boolean syncingCoordinateEditors;
    private static final int SUBWAY_ACCENT = 0xFF58C878;
    private static final int SUBWAY_STYLE_ACTION_NONE = 0;
    private static final int SUBWAY_STYLE_ACTION_SMALL = 1;
    private static final int SUBWAY_STYLE_ACTION_FILLED = 2;
    private static final int SUBWAY_STYLE_BUTTON_W = 26;
    private static final int SUBWAY_STYLE_BUTTON_H = 18;
    private static final int SUBWAY_STYLE_BUTTON_TOP_PAD = 2;
    private static final int SUBWAY_STYLE_BUTTON_ACTIVE = 0xFF2D6B3E;
    private static final int SUBWAY_STYLE_BUTTON_IDLE = 0xFF20242A;
    private static final int SUBWAY_STYLE_BUTTON_HOVER = 0xFF303844;

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

    public GroupEditScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config,
                           WaypointGroup group, int initialSelectedIndex) {
        this(parent, manager, config, group);
        this.selectedIndex = initialSelectedIndex >= 0 && initialSelectedIndex < group.size()
                ? initialSelectedIndex
                : -1;
        if (selectedIndex >= 0) {
            this.scrollOffset = Math.max(0, selectedIndex * (ROW_H + 2) - ROW_H);
        }
    }

    public static void openFocused(Screen parent, ActiveGroupManager manager, WaypointerConfig config,
                                   WaypointGroup group, int waypointIndex) {
        Minecraft.getInstance().setScreen(
                new GroupEditScreen(parent, manager, config, group, waypointIndex));
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

        // Route color mode
        colorModeBtn = Button.builder(colorModeLabel(), this::toggleColorMode)
                .bounds(sidebarInner, y, fieldW, BTN_H)
                .tooltip(colorModeTooltip())
                .build();
        addRenderableWidget(colorModeBtn);
        y += BTN_H + GAP_TIGHT;

        y = addColorModeControls(sidebarInner, y, fieldW);

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

        moveSelectedHereBtn = Button.builder(Component.literal("Move Waypoint Here"), b -> moveSelectedHere())
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
        left.add(new GuiTokens.ButtonSpec("+ Add Here", -1, this::addHere,
                Tooltip.create(Component.literal("Add a waypoint at your current position."))));
        left.add(new GuiTokens.ButtonSpec("+ Add Named", -1, this::addNamedHere,
                Tooltip.create(Component.literal("Name a new waypoint at your current position."))));
        left.add(new GuiTokens.ButtonSpec("+ Add Temp", -1, this::addTempHere,
                Tooltip.create(Component.literal("Add a temporary waypoint at your current position."))));
        left.add(new GuiTokens.ButtonSpec("Export", this::export));
        left.add(new GuiTokens.ButtonSpec("Remove", this::removeSelected));
        left.add(new GuiTokens.ButtonSpec("^", 24, () -> moveSelected(-1)));
        left.add(new GuiTokens.ButtonSpec("v", 24, () -> moveSelected(+1)));
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);

        GuiTokens.layoutFooter(width, footerY, left, done, this::addRenderableWidget, font);
    }

    /*[[AI-FN-DOC
Function:
addColorModeControls
Purpose:
Add only the route-level color controls that match the group's current color mode.
Why this exists:
Showing one-color and gradient endpoint buttons at the same time made the sidebar misleading; each mode should expose only the controls that apply.
When to use:
Call during init after the color mode button has been added. Do not call outside widget rebuild/layout.
Inputs:
sidebarInner is the left x coordinate for sidebar controls; y is the next free row y coordinate; fieldW is the full sidebar control width.
Outputs:
Returns the next free y coordinate after any mode-specific controls.
Side effects:
Creates and registers ColorSwatchButton widgets, assigns their field references, and leaves hidden-mode button references null.
Failure modes:
Unknown/null gradient modes fall through to the one-color behavior through colorModeName/setters elsewhere.
Important invariants:
STATIC shows one full-width swatch, AUTO shows two half-width endpoint swatches, and MANUAL shows no route-level swatches.
Internal logic:
Clear existing swatch references, inspect group.gradientMode, add the matching widgets, and return y advanced only for visible controls.
Pseudocode:
clear static/start/end fields
if mode is STATIC:
  create full-width static color swatch and return y + row height
if mode is AUTO:
  create Start and End swatches side by side and return y + row height
return original y for MANUAL
Implementation notes:
The screen rebuilds when the mode changes so controls physically disappear instead of merely disabling and leaving gaps.
AI self-check:
Verify manual mode leaves no color swatch row and gradient mode never shows the static swatch.
]]*/
    private int addColorModeControls(int sidebarInner, int y, int fieldW) {
        staticColorBtn = null;
        gradientStartBtn = null;
        gradientEndBtn = null;

        WaypointGroup.GradientMode mode = group.gradientMode();
        if (mode == WaypointGroup.GradientMode.STATIC) {
            staticColorBtn = new ColorSwatchButton(sidebarInner, y, fieldW, BTN_H,
                    "One color", group.staticColor(), this::openStaticColorPicker);
            staticColorBtn.setTooltip(Tooltip.create(Component.literal(
                    "One route color.\n"
                  + "Applies in One color mode.")));
            addRenderableWidget(staticColorBtn);
            return y + BTN_H + GAP_TIGHT;
        }

        if (mode == WaypointGroup.GradientMode.AUTO) {
            int swatchW = (fieldW - GAP_TIGHT) / 2;
            gradientStartBtn = new ColorSwatchButton(sidebarInner, y, swatchW, BTN_H,
                    "Start", group.gradientStartColor(), this::openGradientStartPicker);
            gradientStartBtn.setTooltip(Tooltip.create(Component.literal(
                    "Gradient start colour.\n"
                  + "Applies in Gradient mode.")));
            gradientEndBtn = new ColorSwatchButton(sidebarInner + swatchW + GAP_TIGHT, y,
                    fieldW - swatchW - GAP_TIGHT, BTN_H,
                    "End", group.gradientEndColor(), this::openGradientEndPicker);
            gradientEndBtn.setTooltip(Tooltip.create(Component.literal(
                    "Gradient end colour.\n"
                  + "Applies in Gradient mode.")));
            addRenderableWidget(gradientStartBtn);
            addRenderableWidget(gradientEndBtn);
            return y + BTN_H + GAP_TIGHT;
        }

        return y;
    }

    // --- sidebar toggles ---------------------------------------------------------------------

        private Component colorModeLabel() {
        return Component.literal("Color: " + colorModeName(group.gradientMode()));
    }

        private static String colorModeName(WaypointGroup.GradientMode mode) {
        if (mode == WaypointGroup.GradientMode.AUTO) return "Gradient";
        if (mode == WaypointGroup.GradientMode.MANUAL) return "Manual";
        return "One";
    }

        private static Tooltip colorModeTooltip() {
        return Tooltip.create(Component.literal(
                "Waypoint colour mode.\n"
              + "One color: repaint whole route.\n"
              + "Gradient: sweep between endpoints.\n"
              + "Manual: edit waypoint swatches."));
    }

    /*[[AI-FN-DOC
Function:
toggleColorMode
Purpose:
Advance the route color mode and rebuild the sidebar so only relevant color controls remain visible.
Why this exists:
One color, gradient, and manual modes expose different controls; simply disabling old buttons left misleading gaps and stale controls.
When to use:
Used as the Color mode button callback in the route editor sidebar.
Inputs:
b is the pressed button from Minecraft's UI event. It is not mutated directly because the full screen is rebuilt.
Outputs:
No return value.
Side effects:
Mutates group.gradientMode, fires manager data changed, and rebuilds widgets.
Failure modes:
None expected; nextColorMode handles null/unknown modes by cycling back to STATIC through its fallback.
Important invariants:
STATIC shows only the One color swatch, AUTO shows only Start/End swatches, and MANUAL shows no route-level swatches after rebuild.
Internal logic:
Compute the next mode from the current mode, store it on the group, notify persistence/listeners, then rebuild the screen layout.
Pseudocode:
next = nextColorMode(current mode)
group.setGradientMode(next)
manager.fireDataChanged()
rebuildWidgets()
Implementation notes:
Rebuilding instead of updating button active state physically removes hidden-mode rows and prevents stale layout gaps.
AI self-check:
Verify the color mode label and swatch rows match after every click.
]]*/
    private void toggleColorMode(Button b) {
        group.setGradientMode(nextColorMode(group.gradientMode()));
        manager.fireDataChanged();
        rebuildWidgets();
    }

        private static WaypointGroup.GradientMode nextColorMode(WaypointGroup.GradientMode mode) {
        if (mode == WaypointGroup.GradientMode.STATIC) return WaypointGroup.GradientMode.AUTO;
        if (mode == WaypointGroup.GradientMode.AUTO) return WaypointGroup.GradientMode.MANUAL;
        return WaypointGroup.GradientMode.STATIC;
    }

        private void updateColorModeButtons() {
        WaypointGroup.GradientMode mode = group.gradientMode();
        if (colorModeBtn != null) colorModeBtn.setMessage(colorModeLabel());
        if (staticColorBtn != null) {
            staticColorBtn.setColor(group.staticColor());
            staticColorBtn.active = mode == WaypointGroup.GradientMode.STATIC;
        }
        boolean gradientActive = mode == WaypointGroup.GradientMode.AUTO;
        if (gradientStartBtn != null) {
            gradientStartBtn.setColor(group.gradientStartColor());
            gradientStartBtn.active = gradientActive;
        }
        if (gradientEndBtn != null) {
            gradientEndBtn.setColor(group.gradientEndColor());
            gradientEndBtn.active = gradientActive;
        }
    }

        private void openStaticColorPicker() {
        ColorPickerScreen.open(this, "Route Colour", group.staticColor(), this::onStaticColorPicked);
    }

        private void onStaticColorPicked(int picked) {
        group.setStaticColor(picked);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        updateColorModeButtons();
        manager.fireDataChanged();
    }

        private void openGradientStartPicker() {
        openGradientPicker(true);
    }

        private void openGradientEndPicker() {
        openGradientPicker(false);
    }

        private void openGradientPicker(boolean start) {
        if (start) {
            ColorPickerScreen.open(this, "Gradient Start Colour",
                    group.gradientStartColor(), this::onGradientStartPicked);
        } else {
            ColorPickerScreen.open(this, "Gradient End Colour",
                    group.gradientEndColor(), this::onGradientEndPicked);
        }
    }

        private void onGradientStartPicked(int picked) {
        group.setGradientStartColor(picked);
        updateColorModeButtons();
        manager.fireDataChanged();
    }

        private void onGradientEndPicked(int picked) {
        group.setGradientEndColor(picked);
        updateColorModeButtons();
        manager.fireDataChanged();
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

    /*[[AI-FN-DOC
Function:
addHere
Purpose:
Add a new waypoint at the player's configured placement position using the current default waypoint color.
Why this exists:
The group editor needs a direct add button that behaves like /wp add and keybind creation while honoring UI placement and color defaults.
When to use:
Used by the + Add Here footer button in GroupEditScreen.
Inputs:
No parameters. Reads Minecraft player position, WaypointerConfig placement/default color settings, and the current group.
Outputs:
No return value. Adds and selects a waypoint when a player is available.
Side effects:
Mutates the group, runs WaypointAddFlow, updates selected index and skip-ahead label, and fires manager data changed.
Failure modes:
If no local player exists, returns without changes.
Important invariants:
New manually-created waypoints use config.defaultWaypointColor and imported routes remain governed by import color policy.
Internal logic:
Read player, derive placement block, add a new waypoint with default color, run shared post-add handling, select it, refresh relevant UI, and persist notification.
Pseudocode:
player = Minecraft player
if player null return
pos = PlayerWaypointPlacement.fromPlayer(player, config)
add waypoint at pos with empty name and defaultWaypointColor
newIndex = group size - 1
afterWaypointAdded(group, newIndex)
refresh skip-ahead label if present
select newIndex
fire data changed
Implementation notes:
The shared post-add flow keeps this path aligned with command and keybind behavior.
AI self-check:
Verify the color comes from config.defaultWaypointColor rather than Waypoint.DEFAULT_COLOR.
]]*/
    private void addHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        group.add(new Waypoint(
                pos.x(), pos.y(), pos.z(),
                "", config.defaultWaypointColor(), 0, 0.0));
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
        int movedTo = group.moveBy(selectedIndex, delta);
        if (movedTo == selectedIndex) return;
        selectWaypoint(movedTo);
        manager.fireDataChanged();
    }

    private void export() {
        ExportScreen.openForGroup(this, config, group);
    }

    // --- render -----------------------------------------------------------------------------

    /*[[AI-FN-DOC
Function:
render
Purpose:
Render the group editor screen, including header, sidebar, waypoint list, and hover tooltips for custom row controls.
Why this exists:
GroupEditScreen has custom row rendering in addition to normal Minecraft widgets, so it needs one render method to compose both layers correctly.
When to use:
Called by Minecraft every frame while the screen is open. Do not call directly.
Inputs:
g is the GUI graphics target; mouseX/mouseY are current pointer coordinates; partial is the frame partial tick value.
Outputs:
No return value. Draws the current screen frame.
Side effects:
Draws widgets, custom panels, rows, and a tooltip for hovered subwaypoint style buttons.
Failure modes:
None expected. Empty routes render an empty-state message through renderMain.
Important invariants:
Tooltips render after the scissored waypoint list so they are not clipped by the list region.
Internal logic:
Let superclass draw widgets, draw title/status/hints, compute layout bounds, render sidebar and main list, then render any subwaypoint-style tooltip.
Pseudocode:
super.render
draw title, status, and gesture hint
compute sidebar and main bounds
render sidebar
render main list
if subwaypoint style tooltip exists, render tooltip at mouse
Implementation notes:
The tooltip is tied to custom-painted row buttons because vanilla Tooltip only works automatically for widgets.
AI self-check:
Verify tooltip text appears above the scissor region and does not affect row click handling.
]]*/
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        // Header
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        String status = group.mainWaypointCount() + " main / " + group.size() + " pts  .  "
                + RouteProgress.summary(group) + "  .  @"
                + group.currentMainOrdinal()
                + "  .  radius " + String.format("%.1f", group.defaultRadius());
        g.drawString(font, status, width - PAD_OUTER - font.width(status), PAD_OUTER, TEXT_DIM, false);

        // Discoverability hint for the two non-obvious list gestures. Placed under
        // the title in TEXT_MUTED so it reads as ambient help rather than UI chrome.
        // A tooltip would hide these behind a hover the user has to guess at; an
        // always-visible line is cheaper than documentation they won't read.
        String hint = "double-click: rename  .  shift-left: reposition  .  right-click: set current  .  shift-right-click: subwaypoint";
        g.drawString(font, hint, PAD_OUTER, PAD_OUTER + 11, TEXT_MUTED, false);

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP_SECTION;
        int mainRight = width - PAD_OUTER;

        renderSidebarPanel(g, sidebarLeft, top, sidebarRight, bottom);
        renderMain(g, mainLeft, top, mainRight, bottom, mouseX, mouseY);
        String subwaypointStyleTooltip = subwaypointStyleTooltipAt(mouseX, mouseY);
        if (subwaypointStyleTooltip != null) {
            renderInlineTooltip(g, subwaypointStyleTooltip, mouseX, mouseY);
        }
    }

    /*[[AI-FN-DOC
Function:
renderInlineTooltip
Purpose:
Draw a compact tooltip for custom-painted controls that are not Minecraft widget instances.
Why this exists:
The subwaypoint style controls are rendered inside a scrolled custom row list, so vanilla widget Tooltip wiring cannot attach to them.
When to use:
Use from render after custom controls have been drawn and a hover hit-test returns tooltip text.
Inputs:
g is the GUI graphics target; text is the tooltip text to display; mouseX/mouseY are pointer coordinates used to place the tooltip.
Outputs:
No return value. Draws a bounded tooltip panel and text.
Side effects:
Draws into the current GUI frame.
Failure modes:
Very long text is not wrapped, but current callers provide short one-line labels.
Important invariants:
The tooltip must stay inside the screen bounds and render after the list scissor is disabled.
Internal logic:
Measure text, place the tooltip offset from the pointer, clamp to screen bounds, draw surface and border, then draw text.
Pseudocode:
measure width and height from font
x = mouseX + offset clamped to screen
y = mouseY + offset clamped above footer
draw tooltip background
draw border
draw text
Implementation notes:
This intentionally mirrors the app's translucent surface language rather than trying to recreate vanilla's full rich-tooltip API.
AI self-check:
Verify tooltip placement cannot go past the right or bottom screen edge.
]]*/
    private void renderInlineTooltip(GuiGraphics g, String text, int mouseX, int mouseY) {
        int pad = 4;
        int tooltipW = font.width(text) + pad * 2;
        int tooltipH = font.lineHeight + pad * 2;
        int x = Math.min(mouseX + 12, Math.max(PAD_OUTER, width - PAD_OUTER - tooltipW));
        int y = Math.min(mouseY + 12, Math.max(PAD_OUTER, height - FOOTER_H - tooltipH));
        x = Math.max(PAD_OUTER, x);
        y = Math.max(PAD_OUTER, y);

        g.fill(x, y, x + tooltipW, y + tooltipH, 0xF0101216);
        g.fill(x, y, x + tooltipW, y + 1, BORDER);
        g.fill(x, y + tooltipH - 1, x + tooltipW, y + tooltipH, BORDER);
        g.fill(x, y, x + 1, y + tooltipH, BORDER);
        g.fill(x + tooltipW - 1, y, x + tooltipW, y + tooltipH, BORDER);
        g.drawString(font, text, x + pad, y + pad, TEXT, false);
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
        boolean hasSubwaypoints = group.hasSubwaypoints();
        renderWaypointConnectors(g, pts, x1 + 2, y1, y2, y, pitch);
        for (int i = 0; i < pts.size(); i++, y += pitch) {
            if (y + ROW_H < y1 || y > y2) continue;
            renderWaypointRow(g, pts.get(i), i, x1 + 2, y, x2 - 2,
                    mouseX, mouseY, hasSubwaypoints);
        }
        g.disableScissor();

        // Re-place the editor each frame so it follows the row through layout changes
        // (window resize changes column widths; scroll shifts the row Y). The actual
        // EditBox widget draw is handled by super.render -> addRenderableWidget.
        if (editingIndex >= 0 && editingIndex < pts.size()) {
            positionLabelEditor(editingIndex);
        }
    }

    private void renderWaypointConnectors(GuiGraphics g, List<Waypoint> pts,
                                          int rowX, int clipTop, int clipBottom,
                                          int firstRowY, int pitch) {
        if (pts.size() < 2) return;

        int mainCenterX = rowX + GAP + 2 + 7;
        int childCenterX = mainCenterX + 16;
        int previousCenterY = firstRowY + ROW_H / 2;
        int previousColor = pts.get(0).color();

        for (int i = 1; i < pts.size(); i++) {
            int centerY = firstRowY + i * pitch + ROW_H / 2;
            int color = pts.get(i).color();
            drawVerticalGradientLine(g, mainCenterX, previousCenterY, centerY,
                    previousColor, color, clipTop, clipBottom);
            if (group.isSubwaypoint(i)) {
                drawHorizontalGradientLine(g, mainCenterX, childCenterX, centerY,
                        previousColor, color, clipTop, clipBottom);
            }
            previousCenterY = centerY;
            previousColor = color;
        }
    }

    private static void drawVerticalGradientLine(GuiGraphics g, int centerX, int y1, int y2,
                                                 int color1, int color2,
                                                 int clipTop, int clipBottom) {
        if (y1 == y2) return;

        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        int clippedTop = Math.max(top, clipTop);
        int clippedBottom = Math.min(bottom, clipBottom);
        if (clippedTop > clippedBottom) return;

        int denom = Math.max(1, bottom - top);
        for (int y = clippedTop; y <= clippedBottom; y++) {
            double t = (y - top) / (double) denom;
            g.fill(centerX - 1, y, centerX + 1, y + 1, gradientLineColor(color1, color2, t));
        }
    }

    private static void drawHorizontalGradientLine(GuiGraphics g, int x1, int x2, int centerY,
                                                   int color1, int color2,
                                                   int clipTop, int clipBottom) {
        if (centerY < clipTop || centerY > clipBottom) return;

        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int denom = Math.max(1, right - left);
        for (int x = left; x <= right; x++) {
            double t = (x - left) / (double) denom;
            g.fill(x, centerY - 1, x + 1, centerY + 1, gradientLineColor(color1, color2, t));
        }
    }

    private static int gradientLineColor(int color1, int color2, double t) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;
        int r = (int) Math.round(r1 + (r2 - r1) * t);
        int green = (int) Math.round(g1 + (g2 - g1) * t);
        int b = (int) Math.round(b1 + (b2 - b1) * t);
        return 0xCC000000 | (r << 16) | (green << 8) | b;
    }

    /*[[AI-FN-DOC
Function:
renderWaypointRow
Purpose:
Render one waypoint row in the group editor list, including selection state, labels, color swatch, metadata, and subwaypoint style buttons.
Why this exists:
Waypoint rows are dense custom UI with route-specific affordances that cannot be represented by a simple vanilla list widget.
When to use:
Call from renderMain for each visible row in the scrolled waypoint list.
Inputs:
g is the GUI graphics target; w is the waypoint for the row; index is its group index; x1/y1/x2 define row bounds; mouseX/mouseY are pointer coordinates; hasSubwaypoints controls ordinal label formatting.
Outputs:
No return value. Paints the row.
Side effects:
Draws row backgrounds, text, swatches, and custom subwaypoint style buttons.
Failure modes:
Long names can run toward right-side metadata, matching the existing compact row behavior.
Important invariants:
Subwaypoint style buttons appear only on subwaypoint rows and reserve right-side space before metadata text is drawn.
Internal logic:
Compute state, draw selection/hover background, draw color swatch and labels, render optional name, then draw right-aligned metadata and subwaypoint style buttons.
Pseudocode:
compute selected/subwaypoint/current/hovered
draw background and selected accent
draw color swatch and lock ring
draw ordinal/coordinates and optional name
rightTextX = row right
if subwaypoint:
  draw small/filled style buttons
  reserve text space before buttons
  draw subwaypoint tag
draw custom radius or current tag
Implementation notes:
The style controls are custom-painted because the row list is scrolled and clipped manually.
AI self-check:
Verify main waypoint rows are visually unchanged.
]]*/
    private void renderWaypointRow(GuiGraphics g, Waypoint w, int index,
                                   int x1, int y1, int x2, int mouseX, int mouseY,
                                   boolean hasSubwaypoints) {
        boolean selected = index == selectedIndex;
        boolean subwaypoint = group.isSubwaypoint(index);
        boolean isCurrent = !subwaypoint && index == group.currentIndex();
        boolean subwaypointActive = subwaypoint && group.parentMainIndex(index) == group.currentIndex();
        boolean hovered = mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y1 + ROW_H;

        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, y1 + ROW_H, bg);
        if (selected) g.fill(x1, y1, x1 + 2, y1 + ROW_H, subwaypoint ? SUBWAY_ACCENT : ACCENT);

        int indent = subwaypoint ? 16 : 0;

        // Color swatch. Clickable: opens ColorPickerScreen for a per-waypoint colour
        // override. A thin lock ring is drawn around the swatch when the waypoint's
        // colour is locked so users know the gradient won't repaint this one.
        int sx = x1 + GAP + 2 + indent;
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

        // GUI rows keep the route ordinal visible even for named waypoints.
        // Custom names only replace the number in world-rendered labels.
        String ordinal = hasSubwaypoints ? group.displayIndexLabel(index) : "#" + (index + 1);
        String label = ordinal + "  (" + w.x() + ", " + w.y() + ", " + w.z() + ")";
        int textColor = isCurrent || subwaypointActive ? 0xFFFFF080
                : index < group.currentIndex() ? TEXT_MUTED
                : subwaypoint ? TEXT_DIM
                : TEXT;
        g.drawString(font, label, sx + 20, y1 + 7, textColor, false);

        // Skip the static name while the row is being renamed -- the EditBox widget sits
        // on top of this slot and drawing the old name behind it leaks through at the
        // edges of the edit box when the caret is mid-text.
        if (w.hasName() && index != editingIndex) {
            g.drawString(font, AmpersandFormatting.translate(w.name()),
                    sx + 20 + font.width(label) + GAP, y1 + 7, TEXT_DIM, false);
        }

        int rightTextX = x2 - GAP;
        if (subwaypoint) {
            renderSubwaypointStyleButtons(g, w, x2, y1, mouseX, mouseY);
            rightTextX = subwaypointStyleButtonsLeft(x2) - GAP;
            String tag = "subwaypoint";
            int tagW = font.width(tag);
            g.drawString(font, tag, rightTextX - tagW, y1 + 7, SUBWAY_ACCENT, false);
            rightTextX -= tagW + GAP;
        }
        if (w.customRadius() > 0) {
            String r = "r=" + String.format("%.1f", w.customRadius());
            g.drawString(font, r, rightTextX - font.width(r), y1 + 7, TEXT_DIM, false);
        } else if (isCurrent) {
            String tag = "current";
            g.drawString(font, tag, rightTextX - font.width(tag), y1 + 7, 0xFFFFF080, false);
        }
    }

    /*[[AI-FN-DOC
Function:
renderSubwaypointStyleButtons
Purpose:
Draw the far-right inline buttons that toggle small and filled subwaypoint rendering.
Why this exists:
Subwaypoint style needs to be editable per row without opening a modal or crowding the sidebar.
When to use:
Call from renderWaypointRow only for rows already known to be subwaypoints.
Inputs:
g is the GUI graphics target; waypoint is the row waypoint; rowRight is the row's right edge; rowY is the row top; mouseX/mouseY are current pointer coordinates.
Outputs:
No return value. Paints two button-like controls in the row.
Side effects:
Draws into the current GUI frame.
Failure modes:
If a stale non-subwaypoint waypoint is passed, active state reads false but the caller normally prevents that.
Important invariants:
The small button is left of the filled button, both buttons stay within the row, and icon state reflects waypoint flags.
Internal logic:
Compute button positions, evaluate hover and active states, then render each icon button.
Pseudocode:
smallX = button x for small action
filledX = button x for filled action
render small icon button with FLAG_SMALL_SUBWAYPOINT state
render filled icon button with FLAG_FILLED_SUBWAYPOINT state
Implementation notes:
These are custom-painted row controls because the waypoint list is a scrolled custom render surface, not a widget list.
AI self-check:
Verify the hit-test helper uses the same button x/y math.
]]*/
    private void renderSubwaypointStyleButtons(GuiGraphics g, Waypoint waypoint,
                                               int rowRight, int rowY,
                                               int mouseX, int mouseY) {
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        int smallX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_SMALL);
        int filledX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_FILLED);
        renderSubwaypointStyleButton(g, smallX, y,
                waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT),
                isInside(mouseX, mouseY, smallX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                true);
        renderSubwaypointStyleButton(g, filledX, y,
                waypoint.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT),
                isInside(mouseX, mouseY, filledX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                false);
    }

    /*[[AI-FN-DOC
Function:
renderSubwaypointStyleButton
Purpose:
Paint one large inline icon button for a subwaypoint style toggle.
Why this exists:
The row list does not host real Button widgets, so the style toggles need a consistent custom button visual.
When to use:
Use from renderSubwaypointStyleButtons for the small and filled controls.
Inputs:
g is the GUI graphics target; x/y are button coordinates; active is whether the represented flag is enabled; hovered is whether the pointer is inside the button; smallIcon selects the tiny-cube icon versus filled-square icon.
Outputs:
No return value. Paints the button background, border, and icon.
Side effects:
Draws into the current GUI frame.
Failure modes:
None.
Important invariants:
Active buttons use the subwaypoint green accent; inactive buttons remain subdued; hover has a visible border and background change.
Internal logic:
Choose fill color from active/hover state, draw a one-pixel border and inner fill, then draw the selected icon.
Pseudocode:
background = active ? active color : hovered ? hover color : idle color
border = hovered ? white : translucent border
draw border rect
draw inner rect
if smallIcon draw tiny outlined cube icon else draw filled square icon
Implementation notes:
The icons are drawn with rectangles instead of font glyphs so they remain legible in Minecraft's pixel font and do not depend on Unicode coverage.
AI self-check:
Verify both active and inactive states remain readable against selected and hovered row backgrounds.
]]*/
    private void renderSubwaypointStyleButton(GuiGraphics g, int x, int y,
                                              boolean active, boolean hovered,
                                              boolean smallIcon) {
        int bg = active ? SUBWAY_STYLE_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xFFFFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_W, y + SUBWAY_STYLE_BUTTON_H, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_W - 1, y + SUBWAY_STYLE_BUTTON_H - 1, bg);

        if (smallIcon) {
            int cx = x + SUBWAY_STYLE_BUTTON_W / 2;
            int cy = y + SUBWAY_STYLE_BUTTON_H / 2;
            g.fill(cx - 2, cy - 2, cx + 3, cy - 1, TEXT);
            g.fill(cx - 2, cy + 2, cx + 3, cy + 3, TEXT);
            g.fill(cx - 2, cy - 2, cx - 1, cy + 3, TEXT);
            g.fill(cx + 2, cy - 2, cx + 3, cy + 3, TEXT);
            g.fill(cx, cy, cx + 1, cy + 1, active ? 0xFFFFFFFF : SUBWAY_ACCENT);
            return;
        }

        int left = x + SUBWAY_STYLE_BUTTON_W / 2 - 4;
        int top = y + SUBWAY_STYLE_BUTTON_H / 2 - 4;
        int right = left + 8;
        int bottom = top + 8;
        g.fill(left - 1, top - 1, right + 1, bottom + 1, TEXT);
        g.fill(left, top, right, bottom, active ? SUBWAY_ACCENT : TEXT_MUTED);
    }

    /*[[AI-FN-DOC
Function:
subwaypointStyleButtonsLeft
Purpose:
Return the left edge of the two-button subwaypoint style control cluster.
Why this exists:
Row text needs to reserve space before the style buttons so labels do not overlap the controls.
When to use:
Use while rendering subwaypoint rows before drawing right-aligned text.
Inputs:
rowRight is the right edge of the waypoint row.
Outputs:
Returns the x coordinate where the small button starts.
Side effects:
None.
Failure modes:
None.
Important invariants:
The returned x coordinate must match subwaypointStyleButtonX for the small action.
Internal logic:
Subtract outer gap, both button widths, and the tight gap between buttons from rowRight.
Pseudocode:
return rowRight - GAP - button width * 2 - GAP_TIGHT
Implementation notes:
Centralizing this avoids mismatches between text layout, button drawing, and hit testing.
AI self-check:
Verify filled button still ends at rowRight - GAP.
]]*/
    private static int subwaypointStyleButtonsLeft(int rowRight) {
        return rowRight - GAP - SUBWAY_STYLE_BUTTON_W * 2 - GAP_TIGHT;
    }

    /*[[AI-FN-DOC
Function:
subwaypointStyleButtonX
Purpose:
Compute the x coordinate for one subwaypoint style button.
Why this exists:
Render and hit-test paths must agree exactly on where the small and filled buttons live.
When to use:
Use for SUBWAY_STYLE_ACTION_SMALL and SUBWAY_STYLE_ACTION_FILLED only.
Inputs:
rowRight is the row's right edge; action identifies which style button is being positioned.
Outputs:
Returns the x coordinate for the requested button, defaulting unknown actions to the small button position.
Side effects:
None.
Failure modes:
Unknown action values return the small button x so callers do not compute nonsense off-screen positions.
Important invariants:
The filled button is immediately to the right of the small button with GAP_TIGHT spacing.
Internal logic:
Start at the cluster left edge and add one button width plus gap for the filled action.
Pseudocode:
left = subwaypointStyleButtonsLeft(rowRight)
if action is FILLED return left + button width + GAP_TIGHT
return left
Implementation notes:
The action constants avoid creating a small enum solely for two row controls.
AI self-check:
Verify hit testing checks the same coordinates that rendering uses.
]]*/
    private static int subwaypointStyleButtonX(int rowRight, int action) {
        int left = subwaypointStyleButtonsLeft(rowRight);
        return action == SUBWAY_STYLE_ACTION_FILLED
                ? left + SUBWAY_STYLE_BUTTON_W + GAP_TIGHT
                : left;
    }

    /*[[AI-FN-DOC
Function:
isInside
Purpose:
Check whether a point is inside an axis-aligned GUI rectangle.
Why this exists:
The custom subwaypoint row buttons need lightweight hover and click hit testing without constructing widget objects.
When to use:
Use for custom-painted row controls in this screen.
Inputs:
mx/my are pointer coordinates; x/y/w/h define the rectangle.
Outputs:
Returns true when the point is inside the half-open rectangle [x,x+w) and [y,y+h).
Side effects:
None.
Failure modes:
None.
Important invariants:
The right and bottom edges are exclusive, matching common GUI hit-test behavior and avoiding double hits between adjacent controls.
Internal logic:
Compare the point against the rectangle bounds.
Pseudocode:
return mx >= x and mx < x+w and my >= y and my < y+h
Implementation notes:
Kept local because these dimensions are tied to GroupEditScreen's row controls.
AI self-check:
Verify adjacent small/filled buttons cannot both be hovered at the shared gap.
]]*/
    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // --- input -------------------------------------------------------------------------------

    /** GLFW mouse buttons we care about. Inlined so this file doesn't pull in LWJGL. */
    private static final int MOUSE_BUTTON_LEFT  = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;

    /*[[AI-FN-DOC
Function:
mouseClicked
Purpose:
Handle custom waypoint-list mouse interactions before falling back to normal widget and row selection behavior.
Why this exists:
The waypoint list has non-widget interactions, including row selection, right-click progress, shift gestures, color swatches, and subwaypoint style buttons.
When to use:
Called by Minecraft for mouse button presses while the screen is open. Do not call directly.
Inputs:
event contains mouse coordinates and button id; doubleClick indicates Minecraft's double-click detection.
Outputs:
Returns true when this screen consumed the click.
Side effects:
May commit label edits, mutate waypoint structure/style/color/progress, select rows, open color picker, start reposition mode, or delegate to child widgets.
Failure modes:
Clicks outside actionable areas return false or fall through to default handling.
Important invariants:
Subwaypoint style buttons are handled before row selection and shift-left repositioning so icon clicks only toggle the intended style.
Internal logic:
Commit open edits when needed, handle right-click route actions, handle subwaypoint style button clicks, handle shift-left repositioning, handle swatch color clicks, delegate to widgets, then handle row select/double-click rename.
Pseudocode:
if editing and click outside editor, commit edit
if right click on row, select and either toggle subwaypoint with shift or set current
if left click on style button, toggle style and select row
if shift-left on row outside swatch, start reposition
if left click on swatch, open picker or unlock color
if super consumes, return true
if left click on row, select and maybe begin rename
return whether consumed
Implementation notes:
Ordering is the main safety property here; custom row buttons must win over broader row gestures.
AI self-check:
Verify each early return consumes only the click it handled.
]]*/
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A click that lands outside the live editor means the user is done with it.
        // Commit first so the click itself still performs whatever it would have done
        // (select another row, open a double-click edit on a new row, press a button).
        if (editingIndex >= 0 && !isOverLabelEditor(event.x(), event.y())) {
            commitLabelEdit();
        }

        // Right-click on a waypoint row sets progress to that waypoint. Holding Shift
        // turns the same gesture into the structural subwaypoint toggle.
        // before super.mouseClicked because widgets (buttons, EditBoxes) ignore
        // right clicks anyway, and running super first would swallow the event
        // over any widget that happens to sit under the list area.
        if (event.button() == MOUSE_BUTTON_RIGHT) {
            int idx = rowIndexAt(event.x(), event.y());
            if (idx >= 0) {
                selectWaypoint(idx);
                if (hasShiftDown()) {
                    if (group.toggleSubwaypoint(idx)) {
                        coordinateEditorIndex = -1;
                        syncCoordinateEditors();
                        manager.fireDataChanged();
                    }
                } else {
                    group.setCurrentIndex(idx);
                    manager.fireDataChanged();
                }
                return true;
            }
        }

        if (event.button() == MOUSE_BUTTON_LEFT) {
            int subwaypointStyleAction = subwaypointStyleActionAt(event.x(), event.y());
            if (subwaypointStyleAction != SUBWAY_STYLE_ACTION_NONE) {
                int idx = rowIndexAt(event.x(), event.y());
                toggleSubwaypointStyle(idx, subwaypointStyleAction);
                selectWaypoint(idx);
                return true;
            }
        }

        if (event.button() == MOUSE_BUTTON_LEFT && hasShiftDown()) {
            int idx = rowIndexAt(event.x(), event.y());
            if (idx >= 0 && swatchIndexAt(event.x(), event.y()) < 0) {
                selectWaypoint(idx);
                WaypointRepositionMode.start(manager, config, group, idx);
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
                if (hasShiftDown() && group.get(swatchIdx).hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
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

    private static boolean hasShiftDown() {
        var win = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(win, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(win, 344 /* GLFW_KEY_RIGHT_SHIFT */);
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
        int sx = (mainLeft + 2) + GAP + 2 + (group.isSubwaypoint(idx) ? 16 : 0);
        int sy = rowY + 4;
        if (mx >= sx && mx < sx + 14 && my >= sy && my < sy + 14) return idx;
        return -1;
    }

    /*[[AI-FN-DOC
Function:
subwaypointStyleActionAt
Purpose:
Identify which subwaypoint style button, if any, is under the pointer.
Why this exists:
Custom-painted row buttons need a matching click and tooltip hit-test that only activates on subwaypoint rows.
When to use:
Use from mouseClicked and tooltip lookup before applying row selection or other list interactions.
Inputs:
mx and my are pointer coordinates in screen space.
Outputs:
Returns SUBWAY_STYLE_ACTION_SMALL, SUBWAY_STYLE_ACTION_FILLED, or SUBWAY_STYLE_ACTION_NONE.
Side effects:
None.
Failure modes:
Returns NONE for points outside the list, non-subwaypoint rows, or gaps between buttons.
Important invariants:
Only structural subwaypoints expose style actions; main waypoints cannot accidentally receive subwaypoint-only styling through the GUI.
Internal logic:
Find the row index, reject invalid/non-subwaypoint rows, compute the row y and button x coordinates, then test filled and small button rectangles.
Pseudocode:
idx = rowIndexAt(mx,my)
if idx invalid or not subwaypoint return NONE
rowY = row top for idx
smallX = small button x
filledX = filled button x
if point inside small button return SMALL
if point inside filled button return FILLED
return NONE
Implementation notes:
Small is tested before filled because it is leftmost and the two buttons are separated by a gap.
AI self-check:
Verify this mirrors renderSubwaypointStyleButtons geometry.
]]*/
    private int subwaypointStyleActionAt(double mx, double my) {
        int idx = rowIndexAt(mx, my);
        if (idx < 0 || !group.isSubwaypoint(idx)) return SUBWAY_STYLE_ACTION_NONE;

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int rowY = top + 4 - scrollOffset + idx * (ROW_H + 2);
        int rowRight = width - PAD_OUTER - 2;
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        int smallX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_SMALL);
        if (isInside(mx, my, smallX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
            return SUBWAY_STYLE_ACTION_SMALL;
        }
        int filledX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_FILLED);
        if (isInside(mx, my, filledX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
            return SUBWAY_STYLE_ACTION_FILLED;
        }
        return SUBWAY_STYLE_ACTION_NONE;
    }

    /*[[AI-FN-DOC
Function:
subwaypointStyleTooltipAt
Purpose:
Return explanatory hover text for the custom subwaypoint style buttons.
Why this exists:
The buttons are icon-only, so hover text is needed to make the small and filled controls discoverable.
When to use:
Call from render after row rendering so the tooltip appears above the list.
Inputs:
mouseX and mouseY are current pointer coordinates.
Outputs:
Returns tooltip text for a hovered style button, or null when no style button is hovered.
Side effects:
None.
Failure modes:
Returns null for non-subwaypoint rows or gaps.
Important invariants:
Tooltip copy names the action in plain terms and avoids changing state.
Internal logic:
Resolve hovered action through subwaypointStyleActionAt and map it to the corresponding tooltip text.
Pseudocode:
action = subwaypointStyleActionAt(mouseX,mouseY)
if action SMALL return small tooltip
if action FILLED return filled tooltip
return null
Implementation notes:
The tooltip is deliberately short so it does not cover too much of the waypoint list.
AI self-check:
Verify every icon-only button has tooltip text.
]]*/
    private String subwaypointStyleTooltipAt(double mouseX, double mouseY) {
        int action = subwaypointStyleActionAt(mouseX, mouseY);
        if (action == SUBWAY_STYLE_ACTION_SMALL) {
            return "Tiny subwaypoint marker";
        }
        if (action == SUBWAY_STYLE_ACTION_FILLED) {
            return "Filled subwaypoint marker";
        }
        return null;
    }

    /*[[AI-FN-DOC
Function:
toggleSubwaypointStyle
Purpose:
Toggle one subwaypoint-only visual style flag on a waypoint row.
Why this exists:
The new inline buttons need one mutation path that flips the correct flag, preserves other waypoint data, and notifies persistence.
When to use:
Use after subwaypointStyleActionAt returns a non-NONE action for a clicked row.
Inputs:
index is the waypoint row index; action is SUBWAY_STYLE_ACTION_SMALL or SUBWAY_STYLE_ACTION_FILLED.
Outputs:
No return value. The waypoint is updated when the index/action are valid.
Side effects:
Mutates one waypoint in the group and fires manager data changed.
Failure modes:
Invalid indices, non-subwaypoint rows, and unknown actions return without changes.
Important invariants:
Only subwaypoint rows can receive these style flags. Other flags, color, radius, and name are preserved.
Internal logic:
Validate index and subwaypoint structure, choose the target flag from action, XOR it into the waypoint flags, update the group row, and notify listeners.
Pseudocode:
if index invalid or row not subwaypoint return
if action SMALL flag = FLAG_SMALL_SUBWAYPOINT
else if action FILLED flag = FLAG_FILLED_SUBWAYPOINT
else return
waypoint = group.get(index)
group.set(index, waypoint.withFlags(waypoint.flags XOR flag))
manager.fireDataChanged()
Implementation notes:
Using XOR gives a true toggle and leaves the two style options independent.
AI self-check:
Verify toggling filled does not implicitly toggle small, and vice versa.
]]*/
    private void toggleSubwaypointStyle(int index, int action) {
        if (index < 0 || index >= group.size() || !group.isSubwaypoint(index)) return;
        int flag;
        if (action == SUBWAY_STYLE_ACTION_SMALL) {
            flag = Waypoint.FLAG_SMALL_SUBWAYPOINT;
        } else if (action == SUBWAY_STYLE_ACTION_FILLED) {
            flag = Waypoint.FLAG_FILLED_SUBWAYPOINT;
        } else {
            return;
        }

        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withFlags(waypoint.flags() ^ flag));
        manager.fireDataChanged();
    }

        private void openWaypointColorPicker(int idx) {
        if (idx < 0 || idx >= group.size()) return;
        waypointColorPickerIndex = idx;
        Waypoint w = group.get(idx);
        ColorPickerScreen.open(this, "Waypoint #" + (idx + 1) + " Colour",
                w.color(), this::onWaypointColorPicked);
    }

    /*[[AI-FN-DOC
Function:
onWaypointColorPicked
Purpose:
Apply a manually picked per-waypoint color and switch the route into Manual mode when needed.
Why this exists:
Editing a waypoint swatch is a per-point workflow; if the route was in One color or Gradient mode, the UI should become Manual and hide route-level color controls.
When to use:
Used as the ColorPickerScreen callback after opening a waypoint row swatch.
Inputs:
picked is the RGB color selected in the picker.
Outputs:
No return value.
Side effects:
Consumes waypointColorPickerIndex, mutates one waypoint color and locked-color flag, may change group.gradientMode to MANUAL, fires data changed, and may rebuild widgets.
Failure modes:
Out-of-range or missing picker index is ignored after clearing the pending index.
Important invariants:
The selected waypoint keeps FLAG_LOCKED_COLOR so automatic gradient/static recoloring does not immediately overwrite the user's manual pick.
Internal logic:
Read and clear the pending index, validate it, switch to MANUAL and rebuild if the group was not already manual, otherwise update the waypoint and refresh color-mode controls.
Pseudocode:
idx = waypointColorPickerIndex
clear waypointColorPickerIndex
if idx invalid return
if group mode is not MANUAL:
  set mode MANUAL
  replace waypoint idx with picked color and locked flag
  fire data changed
  rebuild widgets
  return
replace waypoint idx with picked color and locked flag
update color-mode buttons
fire data changed
Implementation notes:
The rebuild path makes the One color or Gradient swatches disappear immediately after the first manual per-waypoint edit.
AI self-check:
Verify manual edits preserve only the selected waypoint and do not recolor the whole route.
]]*/
    private void onWaypointColorPicked(int picked) {
        int idx = waypointColorPickerIndex;
        waypointColorPickerIndex = -1;
        if (idx < 0 || idx >= group.size()) return;
        if (group.gradientMode() != WaypointGroup.GradientMode.MANUAL) {
            group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            Waypoint cur = group.get(idx);
            group.set(idx, cur.withColor(picked).withFlags(cur.flags() | Waypoint.FLAG_LOCKED_COLOR));
            manager.fireDataChanged();
            rebuildWidgets();
            return;
        }
        Waypoint cur = group.get(idx);
        group.set(idx, cur.withColor(picked).withFlags(cur.flags() | Waypoint.FLAG_LOCKED_COLOR));
        updateColorModeButtons();
        manager.fireDataChanged();
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
     * Extracted because both the click handler and the right-click row actions
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
        int sx = rowX1 + GAP + 2 + (group.isSubwaypoint(index) ? 16 : 0);
        int labelStart = sx + 20;
        Waypoint w = group.get(index);
        String prefix = group.displayIndexLabel(index) + "  (" + w.x() + ", " + w.y() + ", " + w.z() + ")";
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
