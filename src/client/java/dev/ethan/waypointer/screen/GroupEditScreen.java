package dev.ethan.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.ethan.waypointer.color.GradientColorizer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.RouteProgress;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.debug.DebugEventLog;
import dev.ethan.waypointer.dungeon.DungeonWaypointSkipRules;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.input.WaypointRepositionMode;
import dev.ethan.waypointer.text.AmpersandFormatting;
import dev.ethan.waypointer.input.WaypointAddFlow;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private String coordinateEditError;
    private static final int SUBWAY_ACCENT = 0xFF58C878;
    static final int SUBWAY_STYLE_ACTION_NONE = 0;
    static final int SUBWAY_STYLE_ACTION_SMALL = 1;
    static final int SUBWAY_STYLE_ACTION_FILLED = 2;
    static final int SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT = 3;
    static final int WAYPOINT_CONTROL_ACTION_NONE = 0;
    static final int WAYPOINT_CONTROL_ACTION_STAND_SKIP = 1;
    static final int WAYPOINT_CONTROL_ACTION_INTERACT_SKIP = 2;
    static final int WAYPOINT_CONTROL_ACTION_DEPTH_CHECK = 3;
    private static final int SUBWAY_STYLE_BUTTON_W = 26;
    private static final int SUBWAY_STYLE_BUTTON_H = 18;
    private static final int SUBWAY_STYLE_BUTTON_TOP_PAD = 2;
    private static final int SUBWAY_STYLE_BUTTON_ACTIVE = 0xFF2D6B3E;
    private static final int SUBWAY_STYLE_BUTTON_IDLE = 0xFF20242A;
    private static final int SUBWAY_STYLE_BUTTON_HOVER = 0xFF303844;
    private static final int DEPTH_CHECK_BUTTON_ACTIVE = 0xFF315F8F;
    private static final int HEADER_INFO_BUTTON_SIZE = 12;
    private static final String ROUTE_INFO_TITLE = "Route editor controls";
    private static final String[] ROUTE_INFO_LABELS = {
            "Click",
            "Double-click selected",
            "Right-click",
            "Shift-left-click",
            "Shift-right-click",
            "Color swatch",
            "Row buttons"
    };
    private static final String[] ROUTE_INFO_DESCRIPTIONS = {
            "select a waypoint row",
            "rename that waypoint",
            "set the current waypoint",
            "move in world",
            "toggle subwaypoint",
            "edit color; Shift-click unlocks",
            "small, filled, hidden-after-parent, depth"
    };
    private static final int[] ROUTE_INFO_LABEL_COLORS = {
            ACCENT,
            0xFFFFF080,
            0xFFFFC878,
            0xFF8ACBFF,
            SUBWAY_ACCENT,
            0xFFFF8A8A,
            TEXT_DIM
    };

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
        int resumeEditingIndex = editingIndex;
        String resumeEditingValue = labelEditor != null && editingIndex >= 0
                ? labelEditor.getValue()
                : "";

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
                "Route display name.\n"
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
                        "Toggle skipping waypoints for this route.")))
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
        // Added last so it paints on top of the row it's editing.
        labelEditor = new EditBox(font, 0, 0, 100, BTN_H, Component.literal("Label"));
        labelEditor.setMaxLength(64);
        labelEditor.setVisible(false);
        addRenderableWidget(labelEditor);
        if (resumeEditingIndex >= 0 && resumeEditingIndex < group.size()) {
            editingIndex = resumeEditingIndex;
            labelEditor.setValue(resumeEditingValue);
            labelEditor.setVisible(true);
            setFocused(labelEditor);
            labelEditor.setFocused(true);
            positionLabelEditor(editingIndex);
        } else {
            editingIndex = -1;
        }
        syncCoordinateEditors();

        // Footer
        int footerY = height - FOOTER_H;

        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("+ Add Here", -1, this::addHere,
                Tooltip.create(Component.literal("Add a waypoint at your current position."))));
        left.add(new GuiTokens.ButtonSpec("+ Add Named", -1, this::addNamedHere,
                Tooltip.create(Component.literal("Name a new waypoint at your current position."))));
        left.add(new GuiTokens.ButtonSpec(tempWaypointActionLabel(), -1, this::addTempHere,
                Tooltip.create(Component.literal(tempWaypointActionTooltip()))));
        left.add(new GuiTokens.ButtonSpec("Export", this::export));
        left.add(new GuiTokens.ButtonSpec(
                WaypointRepositionMode.isEditModeEnabled() ? "Exit Edit" : "Edit Mode",
                92,
                this::toggleEditModeFromEditor,
                Tooltip.create(Component.literal(
                        "Enter or exit world edit mode.\n"
                      + "Left click an existing waypoint in-world to move it."))));
        left.add(new GuiTokens.ButtonSpec("Remove", this::removeSelected));
        left.add(new GuiTokens.ButtonSpec("^", 24, () -> moveSelected(-1)));
        left.add(new GuiTokens.ButtonSpec("v", 24, () -> moveSelected(+1)));
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);

        GuiTokens.layoutFooter(width, footerY, left, done, this::addRenderableWidget, font);
    }

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

    static String colorModeName(WaypointGroup.GradientMode mode) {
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

    private void toggleColorMode(Button b) {
        group.setGradientMode(nextColorMode(group.gradientMode()));
        manager.fireDataChanged();
        rebuildWidgets();
    }

    static WaypointGroup.GradientMode nextColorMode(WaypointGroup.GradientMode mode) {
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
        if (!hasSelectedWaypoint()) {
            coordinateEditError = null;
            return;
        }

        Integer value = parseCoordinateInput(raw);
        if (value == null) {
            coordinateEditError = coordinateErrorMessage(axis, raw);
            return;
        }

        Waypoint w = group.get(selectedIndex);
        int x = axis == 0 ? value : w.x();
        int y = axis == 1 ? value : w.y();
        int z = axis == 2 ? value : w.z();
        group.set(selectedIndex, w.withPos(x, y, z));
        coordinateEditError = null;
        manager.fireDataChanged();
    }

    static Integer parseCoordinateInput(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String coordinateErrorMessage(int axis, String raw) {
        String axisLabel = axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
        if (raw == null || raw.trim().isEmpty()) {
            return axisLabel + " coordinate is required.";
        }
        return axisLabel + " coordinate must be a whole number.";
    }

    // --- actions ----------------------------------------------------------------------------

    private boolean hasSelectedWaypoint() {
        return selectedIndex >= 0 && selectedIndex < group.size();
    }

    private void selectWaypoint(int index) {
        selectedIndex = index >= 0 && index < group.size() ? index : -1;
        coordinateEditError = null;
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

    static String tempWaypointActionLabel() {
        return "+ Temp Marker";
    }

    static String tempWaypointActionTooltip() {
        return "Creates a temporary marker in the Temporary list, not this route.";
    }

    private void addNamedHere() {
        AddNamedWaypointScreen.open(this, manager, config, group);
    }

    private int defaultNewWaypointFlags(int x, int y, int z) {
        return isDungeonRoomGroup()
                ? DungeonWaypointSkipRules.defaultFlagsAt(x, y, z)
                : 0;
    }

    private void addHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        int flags = defaultNewWaypointFlags(pos.x(), pos.y(), pos.z());
        group.add(new Waypoint(
                pos.x(), pos.y(), pos.z(),
                "", config.defaultWaypointColor(), flags, 0.0));
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
        int removedIndex = selectedIndex;
        group.remove(selectedIndex);
        coordinateEditorIndex = -1;
        selectWaypoint(selectedIndexAfterRemoval(removedIndex, group.size()));
        manager.fireDataChanged();
    }

    static int selectedIndexAfterRemoval(int removedIndex, int sizeAfterRemoval) {
        if (removedIndex < 0 || sizeAfterRemoval <= 0) return -1;
        return Math.min(removedIndex, sizeAfterRemoval - 1);
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

    private void toggleEditModeFromEditor() {
        boolean enabled = WaypointRepositionMode.toggleEditMode(manager, config);
        if (minecraft == null) return;
        if (enabled) {
            minecraft.setScreen(null);
            return;
        }
        rebuildWidgets();
    }

    // --- render -----------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        renderHeader(g, mouseX, mouseY);

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP_SECTION;
        int mainRight = width - PAD_OUTER;

        renderSidebarPanel(g, sidebarLeft, top, sidebarRight, bottom);
        renderMain(g, mainLeft, top, mainRight, bottom, mouseX, mouseY);
        if (isHeaderInfoButtonHovered(mouseX, mouseY)) {
            renderRouteInfoTooltip(g, mouseX, mouseY);
        } else {
            String tooltip = waypointControlTooltipAt(mouseX, mouseY);
            if (tooltip == null) {
                tooltip = rowSupplementalTooltipAt(mouseX, mouseY);
            }
            if (tooltip != null) {
                renderInlineTooltip(g, tooltip, mouseX, mouseY);
            }
        }
    }

    private void renderHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String title = headerTitleText();
        String status = headerStatusText();
        String clippedTitle = clippedHeaderTitle(title, status);
        g.text(font, clippedTitle, PAD_OUTER, PAD_OUTER, TEXT, false);

        int infoX = headerInfoButtonX(clippedTitle);
        boolean infoHovered = isHeaderInfoButtonHovered(mouseX, mouseY);
        if (infoHovered) g.requestCursor(CursorTypes.POINTING_HAND);
        renderHeaderInfoButton(g, infoX, headerInfoButtonY(), infoHovered);

        g.text(font, status, width - PAD_OUTER - font.width(status), PAD_OUTER, TEXT_DIM, false);
        if (coordinateEditError != null) {
            g.text(font, coordinateEditError, PAD_OUTER, PAD_OUTER + 14,
                    0xFFFF8A8A, false);
        }
    }

    private String headerTitleText() {
        return "Edit: " + group.name();
    }

    private String headerStatusText() {
        return group.mainWaypointCount() + " main / " + group.size() + " pts  .  "
                + RouteProgress.summary(group) + "  .  @"
                + group.currentMainOrdinal()
                + "  .  radius " + String.format("%.1f", group.defaultRadius());
    }

    private String clippedHeaderTitle(String title, String status) {
        String safeTitle = title == null ? "" : title;
        String safeStatus = status == null ? "" : status;
        int statusLeft = width - PAD_OUTER - font.width(safeStatus);
        int available = statusLeft - PAD_OUTER - HEADER_INFO_BUTTON_SIZE
                - GAP_TIGHT - GAP_SECTION;
        if (available <= 0) return "";
        return font.plainSubstrByWidth(safeTitle, available);
    }

    private int headerInfoButtonX(String clippedTitle) {
        return PAD_OUTER + font.width(clippedTitle == null ? "" : clippedTitle) + GAP_TIGHT;
    }

    private int headerInfoButtonY() {
        return PAD_OUTER + (font.lineHeight - HEADER_INFO_BUTTON_SIZE) / 2;
    }

    private boolean isHeaderInfoButtonHovered(int mouseX, int mouseY) {
        String clippedTitle = clippedHeaderTitle(headerTitleText(), headerStatusText());
        int x = headerInfoButtonX(clippedTitle);
        return isInside(mouseX, mouseY, x, headerInfoButtonY(),
                HEADER_INFO_BUTTON_SIZE, HEADER_INFO_BUTTON_SIZE);
    }

    private void renderHeaderInfoButton(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
        int border = hovered ? 0xFFFFFFFF : BORDER;
        int fill = hovered ? 0xFF26343A : 0xFF1A1F24;
        g.fill(x, y, x + HEADER_INFO_BUTTON_SIZE, y + HEADER_INFO_BUTTON_SIZE, border);
        g.fill(x + 1, y + 1, x + HEADER_INFO_BUTTON_SIZE - 1,
                y + HEADER_INFO_BUTTON_SIZE - 1, fill);
        String glyph = "i";
        int glyphX = x + (HEADER_INFO_BUTTON_SIZE - font.width(glyph)) / 2;
        g.text(font, glyph, glyphX, y + 2, hovered ? ACCENT : TEXT_DIM, false);
    }

    private void renderRouteInfoTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int lineCount = Math.min(ROUTE_INFO_LABELS.length, ROUTE_INFO_DESCRIPTIONS.length);
        int pad = 7;
        int lineGap = 3;
        int maxLabelWidth = 0;
        int maxLineWidth = font.width(ROUTE_INFO_TITLE);
        for (int i = 0; i < lineCount; i++) {
            maxLabelWidth = Math.max(maxLabelWidth, font.width(ROUTE_INFO_LABELS[i]));
        }
        for (int i = 0; i < lineCount; i++) {
            int lineWidth = maxLabelWidth + GAP
                    + font.width(ROUTE_INFO_DESCRIPTIONS[i]);
            maxLineWidth = Math.max(maxLineWidth, lineWidth);
        }

        int tooltipW = maxLineWidth + pad * 2;
        int tooltipH = pad * 2 + font.lineHeight + 5
                + lineCount * font.lineHeight + Math.max(0, lineCount - 1) * lineGap;
        int x = Math.min(mouseX + 12, Math.max(PAD_OUTER, width - PAD_OUTER - tooltipW));
        int y = Math.min(mouseY + 12, Math.max(PAD_OUTER, height - FOOTER_H - tooltipH));
        x = Math.max(PAD_OUTER, x);
        y = Math.max(PAD_OUTER, y);

        g.fill(x, y, x + tooltipW, y + tooltipH, 0xF0101216);
        g.fill(x, y, x + tooltipW, y + 1, BORDER);
        g.fill(x, y + tooltipH - 1, x + tooltipW, y + tooltipH, BORDER);
        g.fill(x, y, x + 1, y + tooltipH, BORDER);
        g.fill(x + tooltipW - 1, y, x + tooltipW, y + tooltipH, BORDER);

        int textX = x + pad;
        int textY = y + pad;
        g.text(font, ROUTE_INFO_TITLE, textX, textY, ACCENT, false);
        int separatorY = textY + font.lineHeight + 2;
        g.fill(textX, separatorY, x + tooltipW - pad, separatorY + 1, 0x55FFFFFF);

        int rowY = separatorY + 4;
        for (int i = 0; i < lineCount; i++) {
            int labelColor = i < ROUTE_INFO_LABEL_COLORS.length
                    ? ROUTE_INFO_LABEL_COLORS[i]
                    : TEXT;
            g.text(font, ROUTE_INFO_LABELS[i], textX, rowY, labelColor, false);
            g.text(font, ROUTE_INFO_DESCRIPTIONS[i],
                    textX + maxLabelWidth + GAP, rowY, TEXT_DIM, false);
            rowY += font.lineHeight + lineGap;
        }
    }

    private void renderInlineTooltip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
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
        g.text(font, text, x + pad, y + pad, TEXT, false);
    }

    private void renderSidebarPanel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        // A faint white-wash instead of another dark fill. Stacking SURFACE / SURFACE_SUBTLE
        // (both dark) behind already-dark EditBox/Button widgets made the whole sidebar
        // read as a black slab against the world. A gentle light overlay separates the
        // region from the scene without compounding darkness on the controls.
        g.fill(x1, y1, x2, y2, SIDEBAR_BG);
        g.fill(x2, y1, x2 + 1, y2, BORDER);
        g.text(font, "Route", x1 + GAP, y1 + 10, TEXT, false);

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
            g.text(font, text, textX, rowMidY, TEXT, false);
        }
    }

    /** Lighter sidebar wash -- mild white overlay, roughly 12% alpha. */
    private static final int SIDEBAR_BG = 0x20FFFFFF;

    private void renderMain(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        List<Waypoint> pts = group.waypoints();
        if (pts.isEmpty()) {
            g.text(font, "No waypoints yet.", x1, y1 + 8, TEXT, false);
            g.text(font, "Walk somewhere and click \"+ Add Here\".",
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
        // EditBox widget draw is handled by super.extractRenderState -> addRenderableWidget.
        if (editingIndex >= 0 && editingIndex < pts.size()) {
            positionLabelEditor(editingIndex);
        }
    }

    private void renderWaypointConnectors(GuiGraphicsExtractor g, List<Waypoint> pts,
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

    private static void drawVerticalGradientLine(GuiGraphicsExtractor g, int centerX, int y1, int y2,
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

    private static void drawHorizontalGradientLine(GuiGraphicsExtractor g, int x1, int x2, int centerY,
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

    private void renderWaypointRow(GuiGraphicsExtractor g, Waypoint w, int index,
                                   int x1, int y1, int x2, int mouseX, int mouseY,
                                   boolean hasSubwaypoints) {
        boolean selected = index == selectedIndex;
        boolean subwaypoint = group.isSubwaypoint(index);
        boolean isCurrent = index == group.currentIndex();
        boolean visuallyActive = isWaypointRowVisuallyActive(group, index);
        boolean hovered = mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y1 + ROW_H;
        if (hovered && !(index == editingIndex && isOverLabelEditor(mouseX, mouseY))) {
            g.requestCursor(CursorTypes.POINTING_HAND);
        }

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
        int textColor = visuallyActive ? 0xFFFFF080
                : index < group.currentIndex() ? TEXT_MUTED
                : subwaypoint ? TEXT_DIM
                : TEXT;
        boolean showDungeonWaypointControls = isDungeonRoomGroup();
        int textX = sx + 20;
        int textRightX = waypointRowTextRightEdge(x2, subwaypoint,
                showDungeonWaypointControls,
                waypointRightMetadataWidth(w, subwaypoint, isCurrent));
        renderWaypointRowLabelAndName(g, label, w, index, textX, textRightX, y1, textColor);

        renderWaypointControlButtons(g, w, x2, y1, mouseX, mouseY, showDungeonWaypointControls);
        int rightTextX = waypointControlButtonsLeft(x2, showDungeonWaypointControls) - GAP;
        if (subwaypoint) {
            renderSubwaypointStyleButtons(g, w, x2, y1, mouseX, mouseY,
                    showDungeonWaypointControls);
            rightTextX = subwaypointStyleButtonsLeft(x2, showDungeonWaypointControls) - GAP;
            String tag = "subwaypoint";
            int tagW = font.width(tag);
            g.text(font, tag, rightTextX - tagW, y1 + 7, SUBWAY_ACCENT, false);
            rightTextX -= tagW + GAP;
        }
        if (w.customRadius() > 0) {
            String r = "r=" + String.format("%.1f", w.customRadius());
            g.text(font, r, rightTextX - font.width(r), y1 + 7, TEXT_DIM, false);
        } else if (isCurrent) {
            String tag = "current";
            g.text(font, tag, rightTextX - font.width(tag), y1 + 7, 0xFFFFF080, false);
        }
    }

    static boolean isWaypointRowVisuallyActive(WaypointGroup group, int index) {
        if (group == null || index < 0 || index >= group.size()) return false;
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) return true;

        int currentIndex = group.currentIndex();
        int activeSubwaypointParent = group.activeSubwaypointParentIndex();
        if (group.isSubwaypoint(index)) {
            if (index == currentIndex) return true;
            int parent = group.parentMainIndex(index);
            return parent == activeSubwaypointParent || parent == currentIndex;
        }
        return index == currentIndex || index == activeSubwaypointParent;
    }

    private void renderWaypointRowLabelAndName(GuiGraphicsExtractor g, String label,
                                               Waypoint waypoint, int index,
                                               int textX, int textRightX,
                                               int rowY, int labelColor) {
        int labelWidth = Math.max(0, textRightX - textX);
        if (labelWidth <= 0) return;

        String clippedLabel = font.plainSubstrByWidth(label, labelWidth);
        if (!clippedLabel.isEmpty()) {
            g.text(font, clippedLabel, textX, rowY + 7, labelColor, false);
        }

        // Skip the static name while the row is being renamed -- the EditBox widget sits
        // on top of this slot and drawing the old name behind it leaks through at the
        // edges of the edit box when the caret is mid-text.
        if (!waypoint.hasName() || index == editingIndex) return;

        int nameX = textX + font.width(clippedLabel) + GAP;
        int nameWidth = Math.max(0, textRightX - nameX);
        if (nameWidth <= 0) return;

        String clippedName = font.plainSubstrByWidth(
                AmpersandFormatting.translate(waypoint.name()), nameWidth);
        if (!clippedName.isEmpty()) {
            g.text(font, clippedName, nameX, rowY + 7, TEXT_DIM, false);
        }
    }

    private int waypointRightMetadataWidth(Waypoint waypoint, boolean subwaypoint,
                                           boolean isCurrent) {
        int width = 0;
        if (subwaypoint) {
            width += font.width("subwaypoint");
        }

        String trailing = waypointTrailingMetadata(waypoint, isCurrent);
        if (!trailing.isEmpty()) {
            if (width > 0) width += GAP;
            width += font.width(trailing);
        }
        return width;
    }

    private static String waypointTrailingMetadata(Waypoint waypoint, boolean isCurrent) {
        if (waypoint.customRadius() > 0) {
            return "r=" + String.format("%.1f", waypoint.customRadius());
        }
        return isCurrent ? "current" : "";
    }

    static int waypointRowTextRightEdge(int rowRight, boolean subwaypoint,
                                        boolean showDungeonControls,
                                        int rightMetadataWidth) {
        int right = subwaypoint
                ? subwaypointStyleButtonsLeft(rowRight, showDungeonControls) - GAP
                : waypointControlButtonsLeft(rowRight, showDungeonControls) - GAP;
        if (rightMetadataWidth > 0) {
            right -= rightMetadataWidth + GAP;
        }
        return right;
    }

    static int waypointRowTextWidth(int textLeft, int rowRight, boolean subwaypoint,
                                    boolean showDungeonControls, int rightMetadataWidth) {
        return Math.max(0, waypointRowTextRightEdge(rowRight, subwaypoint,
                showDungeonControls, rightMetadataWidth) - textLeft);
    }

    private void renderWaypointControlButtons(GuiGraphicsExtractor g, Waypoint waypoint,
                                              int rowRight, int rowY,
                                              int mouseX, int mouseY,
                                              boolean showDungeonControls) {
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        if (showDungeonControls) {
            int standX = standSkipButtonX(rowRight);
            renderWaypointControlButton(g, standX, y,
                    waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_STAND),
                    isInside(mouseX, mouseY, standX, y,
                            SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                    WAYPOINT_CONTROL_ACTION_STAND_SKIP);

            int interactX = interactSkipButtonX(rowRight);
            renderWaypointControlButton(g, interactX, y,
                    waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT),
                    isInside(mouseX, mouseY, interactX, y,
                            SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                    WAYPOINT_CONTROL_ACTION_INTERACT_SKIP);
        }
        renderDepthCheckButton(g, waypoint, rowRight, rowY, mouseX, mouseY);
    }

    private void renderWaypointControlButton(GuiGraphicsExtractor g, int x, int y,
                                             boolean active, boolean hovered,
                                             int action) {
        int bg = active ? DEPTH_CHECK_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xFFFFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_W, y + SUBWAY_STYLE_BUTTON_H, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_W - 1, y + SUBWAY_STYLE_BUTTON_H - 1, bg);

        int cx = x + SUBWAY_STYLE_BUTTON_W / 2;
        int cy = y + SUBWAY_STYLE_BUTTON_H / 2;
        int primary = active ? 0xFFFFFFFF : TEXT;
        int secondary = active ? 0xFFFFFFFF : TEXT_MUTED;
        if (action == WAYPOINT_CONTROL_ACTION_STAND_SKIP) {
            g.fill(cx - 6, cy - 5, cx - 2, cy + 1, primary);
            g.fill(cx - 4, cy + 2, cx, cy + 5, secondary);
            g.fill(cx + 2, cy - 3, cx + 6, cy + 3, primary);
            g.fill(cx, cy + 4, cx + 4, cy + 7, secondary);
            return;
        }

        g.fill(cx - 6, cy - 4, cx + 4, cy - 2, primary);
        g.fill(cx - 2, cy - 2, cx, cy + 5, primary);
        g.fill(cx, cy + 3, cx + 6, cy + 5, secondary);
        g.fill(cx + 5, cy - 6, cx + 7, cy - 4, active ? 0xFFFFFFFF : ACCENT);
        g.fill(cx + 7, cy - 4, cx + 9, cy - 2, active ? 0xFFFFFFFF : ACCENT);
    }

    private void renderDepthCheckButton(GuiGraphicsExtractor g, Waypoint waypoint,
                                        int rowRight, int rowY,
                                        int mouseX, int mouseY) {
        int x = depthCheckButtonX(rowRight);
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        boolean active = waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);
        boolean hovered = isInside(mouseX, mouseY, x, y,
                SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H);
        int bg = active ? DEPTH_CHECK_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xFFFFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_W, y + SUBWAY_STYLE_BUTTON_H, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_W - 1, y + SUBWAY_STYLE_BUTTON_H - 1, bg);

        int cx = x + SUBWAY_STYLE_BUTTON_W / 2;
        int cy = y + SUBWAY_STYLE_BUTTON_H / 2;
        g.fill(cx - 7, cy - 5, cx + 2, cy - 4, TEXT_MUTED);
        g.fill(cx - 5, cy - 1, cx + 5, cy, TEXT);
        g.fill(cx - 3, cy + 3, cx + 8, cy + 4, TEXT_MUTED);
        int markerColor = active ? 0xFFFFFFFF : ACCENT;
        g.fill(cx + 5, cy - 6, cx + 7, cy + 6, markerColor);
    }

    private void renderSubwaypointStyleButtons(GuiGraphicsExtractor g, Waypoint waypoint,
                                               int rowRight, int rowY,
                                               int mouseX, int mouseY,
                                               boolean showDungeonControls) {
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        int smallX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_SMALL,
                showDungeonControls);
        int filledX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_FILLED,
                showDungeonControls);
        int hideX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT,
                showDungeonControls);
        renderSubwaypointStyleButton(g, smallX, y,
                waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT),
                isInside(mouseX, mouseY, smallX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                SUBWAY_STYLE_ACTION_SMALL);
        renderSubwaypointStyleButton(g, filledX, y,
                waypoint.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT),
                isInside(mouseX, mouseY, filledX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                SUBWAY_STYLE_ACTION_FILLED);
        renderSubwaypointStyleButton(g, hideX, y,
                waypoint.hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED),
                isInside(mouseX, mouseY, hideX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H),
                SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT);
    }

    private void renderSubwaypointStyleButton(GuiGraphicsExtractor g, int x, int y,
                                              boolean active, boolean hovered,
                                              int action) {
        int bg = active ? SUBWAY_STYLE_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xFFFFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_W, y + SUBWAY_STYLE_BUTTON_H, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_W - 1, y + SUBWAY_STYLE_BUTTON_H - 1, bg);

        if (action == SUBWAY_STYLE_ACTION_SMALL) {
            int cx = x + SUBWAY_STYLE_BUTTON_W / 2;
            int cy = y + SUBWAY_STYLE_BUTTON_H / 2;
            g.fill(cx - 2, cy - 2, cx + 3, cy - 1, TEXT);
            g.fill(cx - 2, cy + 2, cx + 3, cy + 3, TEXT);
            g.fill(cx - 2, cy - 2, cx - 1, cy + 3, TEXT);
            g.fill(cx + 2, cy - 2, cx + 3, cy + 3, TEXT);
            g.fill(cx, cy, cx + 1, cy + 1, active ? 0xFFFFFFFF : SUBWAY_ACCENT);
            return;
        }

        if (action == SUBWAY_STYLE_ACTION_FILLED) {
            int left = x + SUBWAY_STYLE_BUTTON_W / 2 - 4;
            int top = y + SUBWAY_STYLE_BUTTON_H / 2 - 4;
            int right = left + 8;
            int bottom = top + 8;
            g.fill(left - 1, top - 1, right + 1, bottom + 1, TEXT);
            g.fill(left, top, right, bottom, active ? SUBWAY_ACCENT : TEXT_MUTED);
            return;
        }

        int cx = x + SUBWAY_STYLE_BUTTON_W / 2;
        int cy = y + SUBWAY_STYLE_BUTTON_H / 2;
        g.fill(cx - 6, cy - 1, cx + 7, cy + 1, TEXT_MUTED);
        g.fill(cx - 3, cy - 4, cx + 4, cy - 2, TEXT_MUTED);
        g.fill(cx - 3, cy + 2, cx + 4, cy + 4, TEXT_MUTED);
        int slashColor = active ? 0xFFFFFFFF : SUBWAY_ACCENT;
        for (int d = -5; d <= 5; d++) {
            g.fill(cx + d, cy + d - 1, cx + d + 1, cy + d, slashColor);
        }
    }

    private static int subwaypointStyleButtonsLeft(int rowRight, boolean showDungeonControls) {
        return waypointControlButtonsLeft(rowRight, showDungeonControls) - GAP_TIGHT
                - SUBWAY_STYLE_BUTTON_W * 3 - GAP_TIGHT * 2;
    }

    private static int waypointControlButtonsLeft(int rowRight, boolean showDungeonControls) {
        return showDungeonControls ? standSkipButtonX(rowRight) : depthCheckButtonX(rowRight);
    }

    private static int standSkipButtonX(int rowRight) {
        return depthCheckButtonX(rowRight) - (SUBWAY_STYLE_BUTTON_W + GAP_TIGHT) * 2;
    }

    private static int interactSkipButtonX(int rowRight) {
        return depthCheckButtonX(rowRight) - SUBWAY_STYLE_BUTTON_W - GAP_TIGHT;
    }

    private static int depthCheckButtonX(int rowRight) {
        return rowRight - GAP - SUBWAY_STYLE_BUTTON_W;
    }

    private static int subwaypointStyleButtonX(int rowRight, int action,
                                               boolean showDungeonControls) {
        int left = subwaypointStyleButtonsLeft(rowRight, showDungeonControls);
        int pitch = SUBWAY_STYLE_BUTTON_W + GAP_TIGHT;
        if (action == SUBWAY_STYLE_ACTION_FILLED) return left + pitch;
        if (action == SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT) return left + pitch * 2;
        return left;
    }

    /*
     * The old two-button layout helpers used to live here. The three-button
     * versions above intentionally replace them so render, hover, and click
     * geometry cannot drift apart.
     */

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean isDungeonRoomGroup() {
        return DungeonRoomData.definition(group.zoneId()) != null;
    }

    // --- input -------------------------------------------------------------------------------

    /** GLFW mouse buttons we care about. Inlined so this file doesn't pull in LWJGL. */
    private static final int MOUSE_BUTTON_LEFT  = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int selectedBeforeClick = selectedIndex;
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
                String action;
                if (hasShiftDown()) {
                    if (group.toggleSubwaypoint(idx)) {
                        if (group.isSubwaypoint(idx)) {
                            disableSkipAheadForSequencingEdit();
                        }
                        coordinateEditorIndex = -1;
                        syncCoordinateEditors();
                        manager.fireDataChanged();
                    }
                    action = "toggle-subwaypoint";
                } else {
                    group.setCurrentIndex(idx);
                    manager.fireDataChanged();
                    action = "goto";
                }
                DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(idx), idx,
                        selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                        doubleClick, hasShiftDown(), false, "right-click-row", action);
                return true;
            }
        }

        if (event.button() == MOUSE_BUTTON_LEFT) {
            int waypointControlAction = waypointControlActionAt(event.x(), event.y());
            if (waypointControlAction != WAYPOINT_CONTROL_ACTION_NONE) {
                int idx = rowIndexAt(event.x(), event.y());
                toggleWaypointControl(idx, waypointControlAction);
                selectWaypoint(idx);
                DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(idx), idx,
                        selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                        doubleClick, hasShiftDown(), false, "waypoint-control",
                        "toggle-control-" + waypointControlAction);
                return true;
            }

            int subwaypointStyleAction = subwaypointStyleActionAt(event.x(), event.y());
            if (subwaypointStyleAction != SUBWAY_STYLE_ACTION_NONE) {
                int idx = rowIndexAt(event.x(), event.y());
                toggleSubwaypointStyle(idx, subwaypointStyleAction);
                selectWaypoint(idx);
                DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(idx), idx,
                        selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                        doubleClick, hasShiftDown(), false, "subwaypoint-style",
                        "toggle-style-" + subwaypointStyleAction);
                return true;
            }
        }

        if (event.button() == MOUSE_BUTTON_LEFT && hasShiftDown()) {
            int idx = rowIndexAt(event.x(), event.y());
            if (idx >= 0 && swatchIndexAt(event.x(), event.y()) < 0) {
                selectWaypoint(idx);
                WaypointRepositionMode.start(manager, config, group, idx);
            DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(idx), idx,
                    selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                    doubleClick, true, false, "shift-row", "start-edit-move");
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
                DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(swatchIdx), swatchIdx,
                        selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                        doubleClick, hasShiftDown(), false, "color-swatch",
                        hasShiftDown() ? "unlock-or-edit-color" : "edit-color");
                return true;
            }
        }

        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != MOUSE_BUTTON_LEFT) return false;

        int idx = rowIndexAt(event.x(), event.y());
        if (idx < 0) return false;
        boolean wasAlreadySelected = idx == selectedIndex;
        selectWaypoint(idx);

        boolean startRename = shouldStartRenameFromRowClick(doubleClick, wasAlreadySelected);
        if (startRename) beginLabelEdit(idx);
        DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(idx), idx,
                selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                doubleClick, false, false, "waypoint-row",
                startRename ? "rename" : doubleClick
                        ? "double-click ignored: waypoint was not already selected"
                        : "select");
        return true;
    }

    static boolean shouldStartRenameFromRowClick(boolean doubleClick, boolean wasAlreadySelected) {
        return doubleClick && wasAlreadySelected;
    }

    private String selectionDebugLabel(int index) {
        if (index < 0 || index >= group.size()) return "(none)";
        return "#" + index;
    }

    private String waypointDebugId(int index) {
        if (index < 0 || index >= group.size()) return "(none)";
        Waypoint waypoint = group.get(index);
        return "#" + index + "@" + waypoint.x() + "," + waypoint.y() + "," + waypoint.z();
    }

    private void disableSkipAheadForSequencingEdit() {
        if (group.skipAheadEnabled()) {
            group.setSkipAheadEnabled(false);
        }
        if (skipAheadBtn != null) {
            skipAheadBtn.setMessage(skipAheadLabel());
        }
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

    private int waypointControlActionAt(double mx, double my) {
        int idx = rowIndexAt(mx, my);
        if (idx < 0) return WAYPOINT_CONTROL_ACTION_NONE;

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int rowY = top + 4 - scrollOffset + idx * (ROW_H + 2);
        int rowRight = width - PAD_OUTER - 2;
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        if (isDungeonRoomGroup()) {
            int standX = standSkipButtonX(rowRight);
            if (isInside(mx, my, standX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
                return WAYPOINT_CONTROL_ACTION_STAND_SKIP;
            }
            int interactX = interactSkipButtonX(rowRight);
            if (isInside(mx, my, interactX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
                return WAYPOINT_CONTROL_ACTION_INTERACT_SKIP;
            }
        }

        int depthX = depthCheckButtonX(rowRight);
        if (isInside(mx, my, depthX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
            return WAYPOINT_CONTROL_ACTION_DEPTH_CHECK;
        }
        return WAYPOINT_CONTROL_ACTION_NONE;
    }

    private int subwaypointStyleActionAt(double mx, double my) {
        int idx = rowIndexAt(mx, my);
        if (idx < 0 || !group.isSubwaypoint(idx)) return SUBWAY_STYLE_ACTION_NONE;

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int rowY = top + 4 - scrollOffset + idx * (ROW_H + 2);
        int rowRight = width - PAD_OUTER - 2;
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        boolean showDungeonControls = isDungeonRoomGroup();
        int smallX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_SMALL,
                showDungeonControls);
        if (isInside(mx, my, smallX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
            return SUBWAY_STYLE_ACTION_SMALL;
        }
        int filledX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_FILLED,
                showDungeonControls);
        if (isInside(mx, my, filledX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
            return SUBWAY_STYLE_ACTION_FILLED;
        }
        int hideX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT,
                showDungeonControls);
        if (isInside(mx, my, hideX, y, SUBWAY_STYLE_BUTTON_W, SUBWAY_STYLE_BUTTON_H)) {
            return SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT;
        }
        return SUBWAY_STYLE_ACTION_NONE;
    }

    private String waypointControlTooltipAt(double mouseX, double mouseY) {
        int action = waypointControlActionAt(mouseX, mouseY);
        if (action == WAYPOINT_CONTROL_ACTION_STAND_SKIP) {
            return dungeonStandSkipTooltipText();
        }
        if (action == WAYPOINT_CONTROL_ACTION_INTERACT_SKIP) {
            return dungeonInteractSkipTooltipText();
        }
        if (action == WAYPOINT_CONTROL_ACTION_DEPTH_CHECK) {
            return "Render in LOS only";
        }
        return subwaypointStyleTooltipAt(mouseX, mouseY);
    }

    private String rowSupplementalTooltipAt(double mouseX, double mouseY) {
        if (editingIndex >= 0) return null;
        int rowIndex = rowIndexAt(mouseX, mouseY);
        if (rowIndex < 0) return null;
        if (waypointControlActionAt(mouseX, mouseY) != WAYPOINT_CONTROL_ACTION_NONE) return null;
        if (subwaypointStyleActionAt(mouseX, mouseY) != SUBWAY_STYLE_ACTION_NONE) return null;
        if (swatchIndexAt(mouseX, mouseY) >= 0) {
            return swatchGestureTooltipText(hasShiftDown());
        }
        return null;
    }

    static String swatchGestureTooltipText(boolean shiftDown) {
        return shiftDown
                ? "Shift-click unlocks locked color"
                : "Click to edit waypoint color";
    }

    static String dungeonStandSkipTooltipText() {
        return "Dungeons: Stand to skip";
    }

    static String dungeonInteractSkipTooltipText() {
        return "Dungeons: Interact to skip";
    }

    private String subwaypointStyleTooltipAt(double mouseX, double mouseY) {
        int action = subwaypointStyleActionAt(mouseX, mouseY);
        if (action == SUBWAY_STYLE_ACTION_SMALL) {
            return "Tiny";
        }
        if (action == SUBWAY_STYLE_ACTION_FILLED) {
            return "Filled";
        }
        if (action == SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT) {
            return "Hide after parent is reached";
        }
        return null;
    }

    private void toggleWaypointControl(int index, int action) {
        if (index < 0 || index >= group.size()) return;
        int flag = waypointControlFlagForAction(action, isDungeonRoomGroup());
        if (flag == 0) return;
        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withFlags(waypoint.flags() ^ flag));
        manager.fireDataChanged();
    }

    static int waypointControlFlagForAction(int action, boolean dungeonRoomGroup) {
        if (action == WAYPOINT_CONTROL_ACTION_STAND_SKIP && dungeonRoomGroup) {
            return Waypoint.FLAG_SKIP_ON_STAND;
        }
        if (action == WAYPOINT_CONTROL_ACTION_INTERACT_SKIP && dungeonRoomGroup) {
            return Waypoint.FLAG_SKIP_ON_INTERACT;
        }
        if (action == WAYPOINT_CONTROL_ACTION_DEPTH_CHECK) {
            return Waypoint.FLAG_DEPTH_CHECKED;
        }
        return 0;
    }

    private void toggleSubwaypointStyle(int index, int action) {
        if (index < 0 || index >= group.size() || !group.isSubwaypoint(index)) return;
        int flag = subwaypointStyleFlagForAction(action);
        if (flag == 0) return;

        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withFlags(waypoint.flags() ^ flag));
        manager.fireDataChanged();
    }

    static int subwaypointStyleFlagForAction(int action) {
        if (action == SUBWAY_STYLE_ACTION_SMALL) {
            return Waypoint.FLAG_SMALL_SUBWAYPOINT;
        }
        if (action == SUBWAY_STYLE_ACTION_FILLED) {
            return Waypoint.FLAG_FILLED_SUBWAYPOINT;
        }
        if (action == SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT) {
            return Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED;
        }
        return 0;
    }

        private void openWaypointColorPicker(int idx) {
        if (idx < 0 || idx >= group.size()) return;
        waypointColorPickerIndex = idx;
        Waypoint w = group.get(idx);
        ColorPickerScreen.open(this, "Waypoint #" + (idx + 1) + " Colour",
                w.color(), this::onWaypointColorPicked);
    }

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
        int k = event.key();
        if (editingIndex >= 0) {
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
        if (k == GLFW_KEY_ESCAPE
                && (getFocused() == nameBox
                || getFocused() == coordXBox
                || getFocused() == coordYBox
                || getFocused() == coordZBox)) {
            setFocused(null);
            if (nameBox != null) nameBox.setFocused(false);
            if (coordXBox != null) coordXBox.setFocused(false);
            if (coordYBox != null) coordYBox.setFocused(false);
            if (coordZBox != null) coordZBox.setFocused(false);
            return true;
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
     * the remaining row text lane before metadata and controls. Called every frame while
     * editing so the widget tracks
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
        boolean subwaypoint = group.isSubwaypoint(index);
        boolean isCurrent = !subwaypoint && index == group.currentIndex();
        int textRightX = waypointRowTextRightEdge(rowX2, subwaypoint,
                isDungeonRoomGroup(), waypointRightMetadataWidth(w, subwaypoint, isCurrent));
        int editorX = Math.min(labelStart + font.width(prefix) + GAP, textRightX);
        int editorW = labelEditorWidth(editorX, textRightX);

        labelEditor.setX(editorX);
        labelEditor.setY(rowY + 1);
        labelEditor.setWidth(editorW);
    }

    static int labelEditorWidth(int editorX, int textRightX) {
        return Math.max(0, textRightX - editorX);
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
