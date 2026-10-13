package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.crystal.CrystalHollowsStructureRoutes;
import com.babbur.waypointer.debug.DebugEventLog;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import com.babbur.waypointer.dungeon.DungeonWaypointSkipRules;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import com.babbur.waypointer.text.AmpersandFormatting;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.screen.GroupEditGeometry.*;
import static com.babbur.waypointer.screen.GroupEditInputRouter.MOUSE_BUTTON_LEFT;
import static com.babbur.waypointer.screen.GroupEditPolicy.*;
import static com.babbur.waypointer.screen.GuiTokens.*;

public final class GroupEditScreen extends Screen {

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup group;
    private String lastPublishedName;
    private final EditHistory<RouteHistoryEntry> editHistory;
    private HistoryButton undoButton;
    private HistoryButton redoButton;

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
    private ColorSwatchButton staticColorBtn;
    private ColorSwatchButton gradientStartBtn;
    private ColorSwatchButton gradientEndBtn;
    private WaypointColorPickerState waypointColorPickerState;
    private int dungeonTypePickerIndex = -1;
    private boolean dungeonTypeIconsAvailable;

    private int scrollOffset;
    private int sidebarScrollOffset;
    private int sidebarContentHeight;
    private final List<SidebarWidget> sidebarWidgets = new ArrayList<>();
    private List<GuiTokens.ButtonSpec> footerActionSpecs = List.of();
    private GuiTokens.ButtonSpec footerDoneSpec;
    private int selectedIndex = -1;
    private int coordinateEditorIndex = -1;
    private boolean syncingCoordinateEditors;
    private String coordinateEditError;
    private static final int SUBWAY_ACCENT = 0xFF58C878;
    private static final int SUBWAY_STYLE_BUTTON_SIZE = 20;
    private static final int SUBWAY_STYLE_BUTTON_TOP_PAD = 1;
    private static final int WAYPOINT_CONTROL_ICON_SIZE = 16;
    private static final int WAYPOINT_CONTROL_ICON_ATLAS_W = WAYPOINT_CONTROL_ICON_SIZE * 7;
    private static final Identifier WAYPOINT_CONTROL_ICONS = Identifier.fromNamespaceAndPath(
            Waypointer.MOD_ID, "textures/gui/waypoint_controls.png");

    private static final Identifier DUNGEON_TYPE_ICONS = Identifier.fromNamespaceAndPath(
            Waypointer.MOD_ID, "textures/gui/dungeon_waypoint_types.png");
    private static final int DUNGEON_TYPE_BUTTON_SIZE = 20;
    private static final int DUNGEON_TYPE_PICKER_COLUMNS = 4;
    private static final int DUNGEON_TYPE_PICKER_ROWS = 2;
    private static final int DUNGEON_TYPE_PICKER_PAD = 4;
    private static final int DUNGEON_TYPE_PICKER_GAP = 4;
    private static final int DUNGEON_TYPE_PICKER_TITLE_H = 14;
    private static final int DUNGEON_TYPE_PICKER_W = DUNGEON_TYPE_PICKER_PAD * 2
            + DUNGEON_TYPE_PICKER_COLUMNS * DUNGEON_TYPE_BUTTON_SIZE
            + (DUNGEON_TYPE_PICKER_COLUMNS - 1) * DUNGEON_TYPE_PICKER_GAP;
    private static final int DUNGEON_TYPE_PICKER_H = DUNGEON_TYPE_PICKER_PAD * 2
            + DUNGEON_TYPE_PICKER_TITLE_H
            + DUNGEON_TYPE_PICKER_ROWS * DUNGEON_TYPE_BUTTON_SIZE
            + (DUNGEON_TYPE_PICKER_ROWS - 1) * DUNGEON_TYPE_PICKER_GAP;
    private static final int SUBWAY_STYLE_BUTTON_ACTIVE = 0xFF2D6B3E;
    private static final int SUBWAY_STYLE_BUTTON_IDLE = 0xFF20242A;
    private static final int SUBWAY_STYLE_BUTTON_HOVER = 0xFF303844;
    private static final int DEPTH_CHECK_BUTTON_ACTIVE = 0xFF315F8F;
    private static final int HEADER_INFO_BUTTON_SIZE = 12;
    private static final int PUBLISH_BUTTON_W = 92;
    private static final int SUBWAYPOINT_INDENT = 16;
    private static final int ROUTE_SCROLLBAR_W = 2;
    private static final int ROUTE_SCROLLBAR_HIT_W = 6;
    private static final int ROUTE_SCROLLBAR_MIN_THUMB_H = 12;
    private static final int[] ROUTE_INFO_LABEL_COLORS = {
            ACCENT,
            0xFFFFF080,
            0xFFFFC878,
            0xFF8ACBFF,
            SUBWAY_ACCENT,
            0xFFFF8A8A
    };

    private EditBox labelEditor;
    private int editingIndex = -1;
    private boolean draggingRouteScrollbar;
    private int dragWaypointIndex = -1;
    private double waypointDragStartX;
    private double waypointDragStartY;
    private boolean draggingWaypoint;
    private int waypointDropSlot = -1;
    private int routeScrollbarDragOffset;

    private final GroupEditInputRouter.Geometry inputGeometry =
            new GroupEditInputRouter.Geometry() {
                @Override
                public int dungeonPickerTypeAt(double x, double y) {
                    return dungeonTypePickerTypeAt(x, y);
                }

                @Override
                public boolean dungeonPickerAnchorAt(double x, double y) {
                    return dungeonTypeButtonIndexAt(x, y) == dungeonTypePickerIndex;
                }

                @Override
                public boolean insideDungeonPicker(double x, double y) {
                    DungeonTypePickerBounds picker = dungeonTypePickerBounds();
                    return picker != null && picker.contains(x, y);
                }

                @Override
                public boolean overRouteScrollbar(double x, double y) {
                    Layout current = layout();
                    return isOverRouteScrollbar(x, y, current)
                            && routeScrollbarGeometry(current) != null;
                }

                @Override
                public int rowIndexAt(double x, double y) {
                    return GroupEditScreen.this.rowIndexAt(x, y);
                }

                @Override
                public int dungeonTypeButtonIndexAt(double x, double y) {
                    return GroupEditScreen.this.dungeonTypeButtonIndexAt(x, y);
                }

                @Override
                public int waypointControlActionAt(double x, double y) {
                    return GroupEditScreen.this.waypointControlActionAt(x, y);
                }

                @Override
                public int subwaypointStyleActionAt(double x, double y) {
                    return GroupEditScreen.this.subwaypointStyleActionAt(x, y);
                }

                @Override
                public int swatchIndexAt(double x, double y) {
                    return GroupEditScreen.this.swatchIndexAt(x, y);
                }

                @Override
                public boolean lockedColorAt(int rowIndex) {
                    return rowIndex >= 0 && rowIndex < group.size()
                            && group.get(rowIndex).hasFlag(Waypoint.FLAG_LOCKED_COLOR);
                }
            };

    private static final int GLFW_KEY_ESCAPE   = 256;
    private static final int GLFW_KEY_ENTER    = 257;
    private static final int GLFW_KEY_TAB      = 258;
    private static final int GLFW_KEY_KP_ENTER = 335;
    private static final int GLFW_KEY_MENU     = 348;

    public GroupEditScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config, WaypointGroup group) {
        super(Component.translatable("waypointer.screen.group_edit.title", group.name()));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.group = group;
        this.lastPublishedName = group.name();
        this.editHistory = new EditHistory<>(captureEdit());
    }

    public GroupEditScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config,
                           WaypointGroup group, int initialSelectedIndex) {
        this(parent, manager, config, group);
        this.selectedIndex = initialSelectedIndex >= 0 && initialSelectedIndex < group.size()
                ? initialSelectedIndex
                : -1;
        if (selectedIndex >= 0) {
            this.scrollOffset = Math.max(0, selectedIndex * waypointRowPitch() - ROW_H);
        }
    }

    public static void openFocused(Screen parent, ActiveGroupManager manager, WaypointerConfig config,
                                   WaypointGroup group, int waypointIndex) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new GroupEditScreen(parent, manager, config, group, waypointIndex));
    }

    @Override
    protected void init() {
        editHistory.record(captureEdit());
        clearWaypointDrag();
        dungeonTypePickerIndex = -1;
        contextMenu.close();
        dungeonTypeIconsAvailable = minecraft != null
                && minecraft.getResourceManager().getResource(DUNGEON_TYPE_ICONS).isPresent();
        int resumeEditingIndex = editingIndex;
        String resumeEditingValue = labelEditor != null && editingIndex >= 0
                ? labelEditor.getValue()
                : "";

        sidebarWidgets.clear();
        footerActionSpecs = buildFooterActions();
        footerDoneSpec = new GuiTokens.ButtonSpec(
                Component.translatable("gui.done").getString(), this::onClose);
        Layout layout = layout();
        int top = layout.top();
        int sidebarLeft = layout.sidebarLeft();
        int sidebarInner = sidebarLeft + GAP;
        int fieldW = SIDEBAR_W - GAP * 2;

        int y = top + GAP;

        nameBox = new EditBox(font, sidebarInner, y, fieldW - 52, BTN_H,
                Component.translatable("waypointer.screen.group_edit.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(group.name());
        nameBox.setResponder(value -> {
            group.setName(value);
            editHistory.record(captureEdit());
            refreshHistoryButtons();
        });
        addSidebarWidget(nameBox);
        undoButton = addSidebarWidget(new HistoryButton(sidebarInner + fieldW - 48, y, false, () -> restoreEdit(false)));
        redoButton = addSidebarWidget(new HistoryButton(sidebarInner + fieldW - 22, y, true, () -> restoreEdit(true)));
        refreshHistoryButtons();
        y += BTN_H + GAP;

        colorModeBtn = styledButton(sidebarInner, y, fieldW, BTN_H,
                colorModeLabel(), this::toggleColorMode, colorModeTooltip(routeColorMode()));
        addSidebarWidget(colorModeBtn);
        y += BTN_H + GAP_TIGHT;

        y = addColorModeControls(sidebarInner, y, fieldW);

        modeBtn = styledButton(sidebarInner, y, fieldW, BTN_H,
                modeLabel(), this::toggleLoadMode, modeTooltip(group.loadMode()));
        addSidebarWidget(modeBtn);
        y += BTN_H + GAP;

        int bumpW = 24;
        radiusMinusBtn = styledButton(sidebarInner, y, bumpW, BTN_H,
                Component.literal("-"), b -> bumpRadius(-0.5),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.group_edit.radius.shrink.tooltip")));
        radiusPlusBtn = styledButton(sidebarInner + fieldW - bumpW, y, bumpW, BTN_H,
                Component.literal("+"), b -> bumpRadius(0.5),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.group_edit.radius.grow.tooltip")));
        addSidebarWidget(radiusMinusBtn);
        addSidebarWidget(radiusPlusBtn);
        y += BTN_H + GAP;

        skipAheadBtn = styledButton(sidebarInner, y, fieldW, BTN_H,
                skipAheadLabel(), this::toggleSkipAhead,
                Tooltip.create(Component.translatable(isDungeonRoomGroup()
                        ? "waypointer.screen.group_edit.skip_ahead.tooltip.dungeon"
                        : "waypointer.screen.group_edit.skip_ahead.tooltip")));
        addSidebarWidget(skipAheadBtn);
        y += BTN_H + GAP;

        addCoordinateEditors(sidebarInner, y, fieldW);
        y += BTN_H + GAP;

        moveSelectedHereBtn = styledButton(sidebarInner, y, fieldW, BTN_H,
                Component.translatable("waypointer.screen.group_edit.move_here"),
                b -> moveSelectedHere(), null);
        addSidebarWidget(moveSelectedHereBtn);

        int naturalResetY = y + BTN_H + GAP;
        int viewportBottom = sidebarViewportBottom(layout);
        sidebarContentHeight = naturalResetY + BTN_H - sidebarViewportTop(layout);
        int maxSidebarScroll = maxSidebarScroll(
                sidebarContentHeight, viewportBottom - sidebarViewportTop(layout));
        int resetY = maxSidebarScroll == 0
                ? viewportBottom - BTN_H
                : naturalResetY;
        addSidebarWidget(styledButton(sidebarInner, resetY, fieldW, BTN_H,
                Component.translatable("waypointer.screen.group_edit.reset_progress"), b -> {
            DungeonRoomRouteLibrary.resetManualProgress(manager, group);
            recordEdit();
        }, null));
        sidebarScrollOffset = Math.max(0, Math.min(maxSidebarScroll, sidebarScrollOffset));
        refreshSidebarWidgets(layout);

        labelEditor = new EditBox(font, 0, 0, 100, BTN_H,
                Component.translatable("waypointer.screen.group_edit.label"));
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

        boolean dungeonRoute = CatalogPublishFormModel.unsafeDungeonRoute(group);
        boolean incompatibleDraft = parent instanceof WaypointerScreen main
                && !main.acceptsPublishDraft(group);
        Button publishButton = styledButton(width - PAD_OUTER - PUBLISH_BUTTON_W, PAD_OUTER - 6,
                PUBLISH_BUTTON_W, BTN_H,
                Component.translatable("waypointer.screen.group_edit.publish"),
                b -> publish(),
                Tooltip.create(Component.translatable(dungeonRoute
                        ? "waypointer.screen.route_publish.validation.dungeon"
                        : incompatibleDraft ? "waypointer.screen.route_publish.validation.mixed_routes"
                        : "waypointer.screen.group_edit.publish.tooltip")));
        publishButton.active = !dungeonRoute && !incompatibleDraft;
        addRenderableWidget(publishButton);

        AppearanceTarget look = routeAppearanceTarget();
        int appearanceW = appearanceButtonWidth();
        Button appearanceButton = styledButton(
                width - PAD_OUTER - PUBLISH_BUTTON_W - GAP - appearanceW, PAD_OUTER - 6,
                appearanceW, BTN_H, appearanceButtonLabel(look),
                b -> openAppearance(routeAppearanceTarget()),
                Tooltip.create(Component.translatable(look == null
                        ? "waypointer.screen.group_edit.downloaded_route_read_only"
                        : "waypointer.screen.group_edit.appearance.tooltip")));
        appearanceButton.active = look != null;
        addRenderableWidget(appearanceButton);

        int peekW = peekButtonWidth();
        addRenderableWidget(styledButton(
                appearanceButton.getX() - GAP - peekW, PAD_OUTER - 6, peekW, BTN_H,
                Component.translatable(peeking
                        ? "waypointer.screen.group_edit.peek.off"
                        : "waypointer.screen.group_edit.peek"),
                b -> {
                    commitLabelEdit();
                    peeking = !peeking;
                    contextMenu.close();
                    rebuildWidgets();
                },
                Tooltip.create(Component.translatable("waypointer.screen.group_edit.peek.tooltip"))));

        GuiTokens.layoutFooter(width, height - FOOTER_H, footerActionSpecs, footerDoneSpec,
                this::addRenderableWidget, font);
    }

    private AppearanceTarget routeAppearanceTarget() {
        return AppearanceTarget.route(manager, config, group, this::afterAppearanceWrite);
    }

    private AppearanceTarget waypointAppearanceTarget(int index) {
        return AppearanceTarget.waypoint(manager, config, group, index, this::afterAppearanceWrite);
    }

    private void afterAppearanceWrite() {
        manager.noteRouteEdited(group);
        manager.fireDataChanged();
    }

    private void openAppearance(AppearanceTarget look) {
        if (look == null) return;
        MinecraftCompat.setScreen(minecraft, SettingsScreen.forAppearance(
                this, config, WaypointerClient.dungeonConfig(), look));
    }

    private Component appearanceButtonLabel(AppearanceTarget look) {
        int count = look == null ? 0 : look.scope().count();
        return count == 0
                ? Component.translatable("waypointer.screen.group_edit.appearance")
                : Component.translatable("waypointer.screen.group_edit.appearance.count", count);
    }

    private int peekButtonWidth() {
        return Math.max(font.width(Component.translatable("waypointer.screen.group_edit.peek")),
                font.width(Component.translatable("waypointer.screen.group_edit.peek.off"))) + 16;
    }

    private boolean peeking;

    private boolean inPeekedList(double mouseX, double mouseY) {
        if (!peeking) return false;
        Layout layout = layout();
        return mouseX >= layout.mainLeft() && mouseX <= layout.mainRight()
                && mouseY >= layout.top() && mouseY <= layout.bottom();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        if (peeking && minecraft != null && minecraft.level != null) return;
        super.extractBackground(g, mouseX, mouseY, partial);
    }

    private int appearanceButtonWidth() {
        return font.width(Component.translatable("waypointer.screen.group_edit.appearance.count", 99))
                + 16;
    }

    record RouteHistoryEntry(RouteEditState visible, WaypointGroup source, RouteEditState stored) {
        static RouteHistoryEntry capture(ActiveGroupManager manager, WaypointGroup group) {
            WaypointGroup source = DungeonRoomRouteLibrary.durableEditTarget(manager, group);
            if (source == group) source = null;
            return new RouteHistoryEntry(RouteEditState.capture(group), source,
                    source == null ? null : RouteEditState.capture(source));
        }

        void restore(WaypointGroup group) {
            visible.restore(group);
            if (source != null) stored.restore(source);
        }
    }

    private RouteHistoryEntry captureEdit() {
        return RouteHistoryEntry.capture(manager, group);
    }

    private void recordEdit() {
        editHistory.record(captureEdit());
        refreshHistoryButtons();
        manager.noteRouteEdited(group);
        manager.fireDataChanged();
    }

    private void refreshHistoryButtons() {
        if (undoButton != null) undoButton.active = editHistory.canUndo();
        if (redoButton != null) redoButton.active = editHistory.canRedo();
    }

    private void restoreEdit(boolean redo) {
        if (redo ? !editHistory.canRedo() : !editHistory.canUndo()) return;
        cancelLabelEdit();
        (redo ? editHistory.redo() : editHistory.undo()).restore(group);
        selectedIndex = Math.min(selectedIndex, group.size() - 1);
        manager.fireDataChanged();
        rebuildWidgets();
    }

    private List<GuiTokens.ButtonSpec> buildFooterActions() {
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.group_edit.add").getString(),
                this::addHere));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.group_edit.add_named").getString(),
                this::addNamedHere));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.group_edit.export").getString(),
                this::export));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.group_edit.remove").getString(),
                this::removeSelected));
        left.add(new GuiTokens.ButtonSpec("\u25b2", 24, () -> moveSelected(-1),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.group_edit.move_up.tooltip"))));
        left.add(new GuiTokens.ButtonSpec("\u25bc", 24, () -> moveSelected(1),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.group_edit.move_down.tooltip"))));
        return left;
    }

    private <T extends AbstractWidget> T addSidebarWidget(T widget) {
        sidebarWidgets.add(new SidebarWidget(widget, widget.getY()));
        addRenderableWidget(widget);
        return widget;
    }

    private Layout layout() {
        int footerSpace = GuiTokens.footerHeight(width, footerActionSpecs, footerDoneSpec, font);
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = contentBottom(height, footerSpace);
        int sidebarLeft = PAD_OUTER;
        int sidebarRight = sidebarLeft + SIDEBAR_W;
        int mainLeft = sidebarRight + GAP_SECTION;
        int mainRight = width - PAD_OUTER;
        return new Layout(top, bottom, sidebarLeft, sidebarRight, mainLeft, mainRight);
    }

    private static int sidebarViewportTop(Layout layout) {
        return layout.top() + GAP;
    }

    private static int sidebarViewportBottom(Layout layout) {
        return Math.max(sidebarViewportTop(layout), layout.bottom() - GAP);
    }

    private void refreshSidebarWidgets(Layout layout) {
        int viewportTop = sidebarViewportTop(layout);
        int viewportBottom = sidebarViewportBottom(layout);
        for (SidebarWidget slot : sidebarWidgets) {
            int y = slot.homeY() - sidebarScrollOffset;
            slot.widget().setY(y);
            slot.widget().visible = y >= viewportTop
                    && y + slot.widget().getHeight() <= viewportBottom;
        }
    }

    private boolean focusAdjacentSidebarWidget(boolean backwards) {
        int focusedIndex = -1;
        for (int i = 0; i < sidebarWidgets.size(); i++) {
            if (sidebarWidgets.get(i).widget() == getFocused()) {
                focusedIndex = i;
                break;
            }
        }
        if (focusedIndex < 0) return false;

        int step = backwards ? -1 : 1;
        for (int i = focusedIndex + step; i >= 0 && i < sidebarWidgets.size(); i += step) {
            SidebarWidget target = sidebarWidgets.get(i);
            if (!target.widget().active) continue;

            Layout layout = layout();
            int viewportTop = sidebarViewportTop(layout);
            int viewportBottom = sidebarViewportBottom(layout);
            int maxScroll = maxSidebarScroll(sidebarContentHeight, viewportBottom - viewportTop);
            sidebarScrollOffset = sidebarScrollOffsetToReveal(
                    sidebarScrollOffset, target.homeY(), target.widget().getHeight(),
                    viewportTop, viewportBottom, maxScroll);
            refreshSidebarWidgets(layout);

            if (getFocused() instanceof AbstractWidget focused) focused.setFocused(false);
            setFocused(target.widget());
            target.widget().setFocused(true);
            return true;
        }
        return false;
    }

    private int addColorModeControls(int sidebarInner, int y, int fieldW) {
        staticColorBtn = null;
        gradientStartBtn = null;
        gradientEndBtn = null;

        RouteColorMode routeMode = routeColorMode();
        if (routeMode == RouteColorMode.PAINT) {
            addSidebarWidget(styledButton(sidebarInner, y, fieldW, BTN_H,
                    Component.translatable("waypointer.screen.group_edit.paint"),
                    b -> openWaypointPainter(), Tooltip.create(Component.translatable(
                            "waypointer.screen.group_edit.paint.tooltip"))));
            return y + BTN_H + GAP_TIGHT;
        }

        if (routeMode == RouteColorMode.ONE) {
            staticColorBtn = new ColorSwatchButton(sidebarInner, y, fieldW, BTN_H,
                    Component.translatable("waypointer.screen.group_edit.color.one").getString(),
                    group.staticColor(), this::openStaticColorPicker);
            staticColorBtn.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.group_edit.color.one.tooltip")));
            addSidebarWidget(staticColorBtn);
            return y + BTN_H + GAP_TIGHT;
        }

        if (routeMode == RouteColorMode.GRADIENT) {
            int swatchW = (fieldW - GAP_TIGHT) / 2;
            gradientStartBtn = new ColorSwatchButton(sidebarInner, y, swatchW, BTN_H,
                    Component.translatable("waypointer.screen.group_edit.color.start").getString(),
                    group.gradientStartColor(), this::openGradientStartPicker);
            gradientStartBtn.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.group_edit.color.start.tooltip")));
            gradientEndBtn = new ColorSwatchButton(sidebarInner + swatchW + GAP_TIGHT, y,
                    fieldW - swatchW - GAP_TIGHT, BTN_H,
                    Component.translatable("waypointer.screen.group_edit.color.end").getString(),
                    group.gradientEndColor(), this::openGradientEndPicker);
            gradientEndBtn.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.group_edit.color.end.tooltip")));
            addSidebarWidget(gradientStartBtn);
            addSidebarWidget(gradientEndBtn);
            return y + BTN_H + GAP_TIGHT;
        }

        return y;
    }

    private Component colorModeLabel() {
        return Component.translatable("waypointer.screen.group_edit.color_mode",
                Component.translatable("waypointer.screen.group_edit.color_mode."
                        + colorModeName(routeColorMode()).toLowerCase(java.util.Locale.ROOT)));
    }

    private RouteColorMode routeColorMode() {
        boolean paintActive = group.paintEnabled()
                && (group.paint() != null || config.waypointPainterDefaultPaint() != null);
        return GroupEditPolicy.routeColorMode(group.gradientMode(), paintActive);
    }

    private static Tooltip colorModeTooltip(RouteColorMode mode) {
        return Tooltip.create(Component.translatableWithFallback(
                colorModeTooltipKey(mode), colorModeTooltipFallback(mode)));
    }

    private void toggleColorMode(Button b) {
        RouteColorMode next = nextColorMode(routeColorMode());
        group.setPaintEnabled(next == RouteColorMode.PAINT);
        switch (next) {
            case COLOR -> group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            case GRADIENT -> group.setGradientMode(WaypointGroup.GradientMode.AUTO);
            case ONE -> group.setGradientMode(WaypointGroup.GradientMode.STATIC);
            case PAINT -> {
                if (group.paint() == null && config.waypointPainterDefaultPaint() == null) {
                    group.setPaint(new WaypointPaint(
                            config.waypointPainterPalette(),
                            new byte[WaypointPaint.PIXEL_COUNT]));
                }
            }
        }
        recordEdit();
        rebuildWidgets();
    }

    private void openWaypointPainter() {
        WaypointGroup editTarget = durableEditTarget();
        if (editTarget == null) return;
        MinecraftCompat.setScreen(minecraft,
                new WaypointPainterScreen(this, config, manager, editTarget));
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
        ColorPickerScreen.open(this,
                Component.translatable("waypointer.screen.group_edit.picker.route"),
                group.staticColor(), this::onStaticColorPicked);
    }

    private void onStaticColorPicked(int picked) {
        group.setStaticColor(picked);
        group.setPaintEnabled(false);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        updateColorModeButtons();
        recordEdit();
    }

    private void openGradientStartPicker() {
        openGradientPicker(true);
    }

    private void openGradientEndPicker() {
        openGradientPicker(false);
    }

    private void openGradientPicker(boolean start) {
        if (start) {
            ColorPickerScreen.open(this,
                    Component.translatable("waypointer.screen.group_edit.picker.gradient_start"),
                    group.gradientStartColor(), this::onGradientStartPicked);
        } else {
            ColorPickerScreen.open(this,
                    Component.translatable("waypointer.screen.group_edit.picker.gradient_end"),
                    group.gradientEndColor(), this::onGradientEndPicked);
        }
    }

    private void onGradientStartPicked(int picked) {
        group.setGradientStartColor(picked);
        group.setPaintEnabled(false);
        updateColorModeButtons();
        recordEdit();
    }

    private void onGradientEndPicked(int picked) {
        group.setGradientEndColor(picked);
        group.setPaintEnabled(false);
        updateColorModeButtons();
        recordEdit();
    }

    private Component modeLabel() {
        return Component.translatable("waypointer.screen.group_edit.mode",
                Component.translatable(group.loadMode() == WaypointGroup.LoadMode.STATIC
                        ? "waypointer.screen.group_edit.mode.static"
                        : "waypointer.screen.group_edit.mode.sequence"));
    }

    private static Tooltip modeTooltip(WaypointGroup.LoadMode mode) {
        return Tooltip.create(Component.translatableWithFallback(
                modeTooltipKey(mode), modeTooltipFallback(mode)));
    }

    private void toggleLoadMode(Button b) {
        group.setLoadMode(group.loadMode() == WaypointGroup.LoadMode.STATIC
                ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        b.setMessage(modeLabel());
        b.setTooltip(modeTooltip(group.loadMode()));
        recordEdit();
    }

    private void bumpRadius(double delta) {
        group.setDefaultRadius(group.defaultRadius() + delta);
        recordEdit();
    }

    private Component skipAheadLabel() {
        return Component.translatable("waypointer.screen.group_edit.skip_ahead",
                Component.translatable(group.skipAheadEnabled() ? "options.on" : "options.off"));
    }

    private void toggleSkipAhead(Button b) {
        group.setSkipAheadEnabled(!group.skipAheadEnabled());
        b.setMessage(skipAheadLabel());
        recordEdit();
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

        addSidebarWidget(coordXBox);
        addSidebarWidget(coordYBox);
        addSidebarWidget(coordZBox);
    }

    private EditBox createCoordinateEditor(String label, int axis) {
        EditBox box = new EditBox(font, 0, 0, 40, BTN_H, Component.literal(label));
        box.setMaxLength(12);
        box.setHint(Component.literal(label));
        box.setResponder(v -> updateSelectedCoordinate(axis, v));
        return box;
    }

    private int coordinateAxisAt(double mouseX, double mouseY) {
        if (coordXBox != null && coordXBox.visible && coordXBox.active
                && coordXBox.isMouseOver(mouseX, mouseY)) return 0;
        if (coordYBox != null && coordYBox.visible && coordYBox.active
                && coordYBox.isMouseOver(mouseX, mouseY)) return 1;
        if (coordZBox != null && coordZBox.visible && coordZBox.active
                && coordZBox.isMouseOver(mouseX, mouseY)) return 2;
        return -1;
    }

    private void scrollCoordinate(int axis, double verticalScroll) {
        Waypoint waypoint = group.get(selectedIndex);
        int current = axis == 0 ? waypoint.x() : axis == 1 ? waypoint.y() : waypoint.z();
        int next = coordinateAfterScroll(current, verticalScroll);
        if (next == current) return;
        EditBox box = axis == 0 ? coordXBox : axis == 1 ? coordYBox : coordZBox;
        box.setValue(displayNumbers().integer(next));
    }

    private void updateSelectedCoordinate(int axis, String raw) {
        if (syncingCoordinateEditors) return;
        if (!hasSelectedWaypoint()) {
            coordinateEditError = null;
            return;
        }

        Integer value = parseCoordinate(raw);
        if (value == null) {
            String axisLabel = axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
            coordinateEditError = Component.translatable(
                    raw == null || raw.trim().isEmpty()
                            ? "waypointer.screen.group_edit.coordinate.required"
                            : "waypointer.screen.group_edit.coordinate.whole_number",
                    axisLabel).getString();
            return;
        }

        Waypoint w = group.get(selectedIndex);
        int x = axis == 0 ? value : w.x();
        int y = axis == 1 ? value : w.y();
        int z = axis == 2 ? value : w.z();
        group.set(selectedIndex, w.withPos(x, y, z));
        coordinateEditError = null;
        recordEdit();
    }

    private boolean hasSelectedWaypoint() {
        return selectedIndex >= 0 && selectedIndex < group.size();
    }

    private void selectWaypoint(int index) {
        int next = index >= 0 && index < group.size() ? index : -1;
        if (next != selectedIndex) dungeonTypePickerIndex = -1;
        selectedIndex = next;
        coordinateEditError = null;
        syncCoordinateEditors();
    }

    private void syncCoordinateEditors() {
        if (coordXBox == null || coordYBox == null || coordZBox == null) return;

        boolean hasSelection = hasSelectedWaypoint();
        coordXBox.active = hasSelection;
        coordYBox.active = hasSelection;
        coordZBox.active = hasSelection;
        if (moveSelectedHereBtn != null) moveSelectedHereBtn.active = hasSelection
                && DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(group);

        if (!hasSelection) {
            coordinateEditorIndex = -1;
            setCoordinateEditorValues("", "", "");
            return;
        }

        if (coordinateEditorIndex == selectedIndex) return;
        Waypoint w = group.get(selectedIndex);
        LocalizedNumberFormatter numbers = displayNumbers();
        coordinateEditorIndex = selectedIndex;
        setCoordinateEditorValues(
                numbers.integer(w.x()), numbers.integer(w.y()), numbers.integer(w.z()));
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

        WaypointGroup editTarget = durableEditTarget();
        if (editTarget == null || selectedIndex >= editTarget.size()) return;

        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;

        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        if (!DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(
                editTarget, selectedIndex, pos.x(), pos.y(), pos.z())) {
            coordinateEditError = DungeonRoomWaypointPlacement.unavailableMessage(editTarget).getString();
            return;
        }
        coordinateEditorIndex = -1;
        syncCoordinateEditors();
        recordEdit();
    }

    private void addNamedHere() {
        if (!CrystalHollowsStructureRoutes.isStructureRoute(group)
                && !DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(group)) {
            coordinateEditError = DungeonRoomWaypointPlacement.unavailableMessage(group).getString();
            return;
        }
        AddNamedWaypointScreen.open(this, manager, config, group);
    }

    private int defaultNewWaypointFlags(int x, int y, int z) {
        return isDungeonRoomGroup()
                ? DungeonWaypointSkipRules.defaultFlagsAt(x, y, z)
                : 0;
    }

    private void addHere() {
        WaypointGroup editTarget = durableEditTarget();
        if (editTarget == null) return;

        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        int flags = defaultNewWaypointFlags(pos.x(), pos.y(), pos.z());
        Waypoint stored = DungeonRoomWaypointPlacement.editorDefaultWaypoint(editTarget, new Waypoint(
                pos.x(), pos.y(), pos.z(),
                "", config.defaultWaypointColor(), flags, 0.0));
        if (stored == null) {
            coordinateEditError = DungeonRoomWaypointPlacement.unavailableMessage(editTarget).getString();
            return;
        }
        editTarget.add(stored);
        int newIndex = editTarget.size() - 1;
        new WaypointAddFlow().afterWaypointAdded(editTarget, newIndex,
                config.showWaypointChatShareButtons());
        if (skipAheadBtn != null) skipAheadBtn.setMessage(skipAheadLabel());
        if (editTarget == group) selectWaypoint(newIndex);
        recordEdit();
    }

    private void addSubwaypointHere(int parent) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || parent < 0 || parent >= group.size() || group.isSubwaypoint(parent)) return;
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        Waypoint stored = DungeonRoomWaypointPlacement.editorDefaultWaypoint(group, new Waypoint(
                pos.x(), pos.y(), pos.z(), "", config.defaultWaypointColor(),
                defaultNewWaypointFlags(pos.x(), pos.y(), pos.z()), 0.0));
        if (stored == null) {
            coordinateEditError = DungeonRoomWaypointPlacement.unavailableMessage(group).getString();
            return;
        }
        int at = group.childEndExclusive(parent);
        group.insert(at, stored.withSubwaypoint(true));
        new WaypointAddFlow().afterWaypointAdded(group, at, config.showWaypointChatShareButtons());
        if (skipAheadBtn != null) skipAheadBtn.setMessage(skipAheadLabel());
        selectWaypoint(at);
        recordEdit();
    }

    private WaypointGroup durableEditTarget() {
        WaypointGroup editTarget = DungeonRoomRouteLibrary.durableEditTarget(manager, group);
        if (editTarget == null) {
            coordinateEditError = Component.translatableWithFallback(
                    "waypointer.screen.group_edit.downloaded_route_read_only",
                    "Convert downloaded dungeon secrets to an editable route first.").getString();
        }
        return editTarget;
    }

    private void removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= group.size()) return;
        dungeonTypePickerIndex = -1;
        int removedIndex = selectedIndex;
        group.remove(selectedIndex);
        coordinateEditorIndex = -1;
        selectWaypoint(selectedIndexAfterRemoval(removedIndex, group.size()));
        recordEdit();
    }

    private void moveSelected(int delta) {
        if (editingIndex >= 0) commitLabelEdit();
        dungeonTypePickerIndex = -1;
        int movedTo = moveWaypointSelection(group, selectedIndex, delta);
        if (movedTo == selectedIndex) return;
        selectWaypoint(movedTo);
        recordEdit();
    }

    private void export() {
        ExportScreen.openForGroup(this, config, group);
    }

    private void publish() {
        if (CatalogPublishFormModel.unsafeDungeonRoute(group)) return;
        WaypointGroup editTarget = durableEditTarget();
        if (editTarget == null || editTarget.isEmpty()) return;
        if (parent instanceof WaypointerScreen main && !main.acceptsPublishDraft(editTarget)) return;
        if (parent instanceof WaypointerScreen main && main.addToPublishDraft(editTarget)) return;
        RoutePublishScreen.open(this, config, editTarget);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {

        boolean overlayOpen = contextMenu.isOpen() || dungeonTypePickerIndex >= 0;
        int backgroundMouseX = overlayOpen ? -1 : mouseX;
        int backgroundMouseY = overlayOpen ? -1 : mouseY;
        super.extractRenderState(g, backgroundMouseX, backgroundMouseY, partial);

        renderHeader(g, backgroundMouseX, backgroundMouseY);
        Layout layout = layout();
        renderSidebarPanel(g, layout.sidebarLeft(), layout.top(),
                layout.sidebarRight(), layout.bottom());
        if (!peeking) {
            renderMain(g, layout.mainLeft(), layout.top(),
                    layout.mainRight(), layout.bottom(), backgroundMouseX, backgroundMouseY);
        }
        renderDungeonTypePicker(g, mouseX, mouseY);

        for (SidebarWidget slot : sidebarWidgets) {
            if (!slot.widget().visible) continue;
            if (slot.widget() instanceof GuiTokens.StyledButton button) {
                button.extractOverPanel(g, backgroundMouseX, backgroundMouseY, partial);
            } else if (slot.widget() instanceof ColorSwatchButton swatch) {
                swatch.extractOverPanel(g, backgroundMouseX, backgroundMouseY, partial);
            } else if (slot.widget() instanceof HistoryButton historyButton) {
                historyButton.extractOverPanel(g, backgroundMouseX, backgroundMouseY, partial);
            } else if (slot.widget() instanceof EditBox editBox) {
                editBox.extractWidgetRenderState(g, backgroundMouseX, backgroundMouseY, partial);
            }
        }
        if (labelEditor != null && labelEditor.visible) {
            labelEditor.extractWidgetRenderState(g, backgroundMouseX, backgroundMouseY, partial);
        }
        if (contextMenu.isOpen()) {
            contextMenu.render(g, font, mouseX, mouseY);
        } else if (isHeaderInfoButtonHovered(backgroundMouseX, backgroundMouseY)) {
            renderRouteInfoTooltip(g, backgroundMouseX, backgroundMouseY);
        } else if (!peeking) {
            String tooltip = dungeonTypeTooltipAt(backgroundMouseX, backgroundMouseY);
            if (tooltip == null) {
                tooltip = waypointControlTooltipAt(backgroundMouseX, backgroundMouseY);
            }
            if (tooltip != null) {
                renderInlineTooltip(g, tooltip, backgroundMouseX, backgroundMouseY);
            }
        }
    }

    private void renderHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String title = headerTitleText();
        String clippedTitle = clippedHeaderTitle(title);
        g.text(font, clippedTitle, PAD_OUTER, PAD_OUTER, TEXT, false);

        int infoX = headerInfoButtonX(clippedTitle);
        boolean infoHovered = isHeaderInfoButtonHovered(mouseX, mouseY);
        if (infoHovered) g.requestCursor(CursorTypes.POINTING_HAND);
        renderHeaderInfoButton(g, infoX, headerInfoButtonY(), infoHovered);

        if (coordinateEditError != null) {
            g.text(font, coordinateEditError, PAD_OUTER, PAD_OUTER + 14,
                    GuiTokens.DANGER, false);
        } else if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            g.text(font, Component.translatableWithFallback(
                    "waypointer.screen.group_edit.structure_coordinates",
                    "Coordinates are relative to the NPC start position. Add starts at 0, 0, 0 until the structure is located."),
                    PAD_OUTER, PAD_OUTER + 14, TEXT_DIM, false);
        }
    }

    private String headerTitleText() {
        return Component.translatable("waypointer.screen.group_edit.title",
                group.name()).getString();
    }

    private String clippedHeaderTitle(String title) {
        String safeTitle = title == null ? "" : title;
        int publishButtonLeft = width - PAD_OUTER - PUBLISH_BUTTON_W - GAP - appearanceButtonWidth()
                - GAP - peekButtonWidth();
        int available = publishButtonLeft - PAD_OUTER - HEADER_INFO_BUTTON_SIZE
                - GAP_TIGHT - GAP_SECTION;
        if (available <= 0) return "";
        return font.plainSubstrByWidth(safeTitle, available);
    }

    private int headerInfoButtonX(String clippedTitle) {
        return PAD_OUTER + font.width(clippedTitle == null ? "" : clippedTitle) + GAP_TIGHT;
    }

    private int headerInfoButtonY() {
        return GuiTokens.opticalInfoButtonY(PAD_OUTER);
    }

    private boolean isHeaderInfoButtonHovered(int mouseX, int mouseY) {
        String clippedTitle = clippedHeaderTitle(headerTitleText());
        int x = headerInfoButtonX(clippedTitle);
        return isInside(mouseX, mouseY, x, headerInfoButtonY(),
                HEADER_INFO_BUTTON_SIZE, HEADER_INFO_BUTTON_SIZE);
    }

    private void renderHeaderInfoButton(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
        GuiTokens.drawInfoButton(g, font, x, y, HEADER_INFO_BUTTON_SIZE, hovered);
    }

    private void renderRouteInfoTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String infoTitle = Component.translatableWithFallback(
                "waypointer.screen.group_edit.info.title", "Route editor controls").getString();
        List<String> labels = routeInfoLabels(isDungeonRoomGroup());
        List<String> descriptions = routeInfoDescriptions(isDungeonRoomGroup());
        GuiTokens.drawInfoTooltip(g, font, infoTitle,
                labels.toArray(new String[0]), descriptions.toArray(new String[0]),
                ROUTE_INFO_LABEL_COLORS, mouseX, mouseY,
                width - PAD_OUTER, layout().bottom());
    }

    private void renderInlineTooltip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
        int pad = 4;
        int tooltipW = font.width(text) + pad * 2;
        int tooltipH = font.lineHeight + pad * 2;
        int x = Math.min(mouseX + 12, Math.max(PAD_OUTER, width - PAD_OUTER - tooltipW));
        int y = Math.min(mouseY + 12,
                Math.max(PAD_OUTER, layout().bottom() - tooltipH));
        x = Math.max(PAD_OUTER, x);
        y = Math.max(PAD_OUTER, y);

        GuiTokens.drawTooltipPanel(g, x, y, x + tooltipW, y + tooltipH);
        g.text(font, text, x + pad, y + pad, TEXT, false);
    }

    private void renderSidebarPanel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, SIDEBAR_BG);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        if (radiusMinusBtn != null && radiusPlusBtn != null
                && radiusMinusBtn.visible && radiusPlusBtn.visible) {
            int rowMidY = radiusMinusBtn.getY() + BTN_H / 2 - 4;
            int inlineLeft = radiusMinusBtn.getX() + radiusMinusBtn.getWidth();
            int inlineRight = radiusPlusBtn.getX();
            String text = Component.translatable("waypointer.screen.group_edit.radius",
                    displayNumbers().oneDecimal(group.defaultRadius())).getString();
            int textW = font.width(text);
            int textX = inlineLeft + ((inlineRight - inlineLeft) - textW) / 2;
            g.text(font, text, textX, rowMidY, TEXT, false);
        }

        Layout layout = layout();
        renderSidebarScrollbar(g, x2 - 3, sidebarViewportTop(layout),
                sidebarViewportBottom(layout));
    }

    private void renderSidebarScrollbar(GuiGraphicsExtractor g, int x, int y1, int y2) {
        int viewportHeight = y2 - y1;
        int maxScroll = maxSidebarScroll(sidebarContentHeight, viewportHeight);
        if (viewportHeight <= 0 || maxScroll <= 0) return;

        int thumbHeight = Math.max(12, viewportHeight * viewportHeight / sidebarContentHeight);
        int travel = viewportHeight - thumbHeight;
        if (travel <= 0) return;

        int thumbY = y1 + Math.max(0, Math.min(maxScroll, sidebarScrollOffset))
                * travel / maxScroll;
        g.fill(x, y1, x + 2, y2, BORDER);
        g.fill(x, thumbY, x + 2, thumbY + thumbHeight, TEXT_MUTED);
    }

    private static final int SIDEBAR_BG = 0xD0101216;
    private static final int ROUTE_LIST_BG = 0xB0101216;

    private void renderMain(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        coordinateColumnOffset = coordinateColumnOffset();
        List<Waypoint> pts = group.waypoints();
        g.fill(x1, y1, x2, y2, ROUTE_LIST_BG);
        if (pts.isEmpty()) {
            g.text(font, Component.translatable("waypointer.screen.group_edit.empty"),
                    x1 + GAP, y1 + 8, TEXT, false);
            Component hint = CrystalHollowsStructureRoutes.isStructureRoute(group)
                    ? Component.translatableWithFallback("waypointer.screen.group_edit.empty.structure_hint",
                            "Click \"+ Add\", then edit the NPC-relative X, Y, and Z coordinates.")
                    : Component.translatable("waypointer.screen.group_edit.empty.hint");
            g.text(font, hint,
                    x1 + GAP, y1 + 8 + 14, TEXT_DIM, false);
            return;
        }

        int viewportHeight = y2 - y1;
        scrollOffset = Math.max(0, Math.min(
                routeListMaxScroll(pts.size(), viewportHeight), scrollOffset));
        g.enableScissor(x1, y1, x2, y2);
        int y = y1 + ROUTE_LIST_INSET - scrollOffset;
        int pitch = waypointRowPitch();
        boolean hasSubwaypoints = group.hasSubwaypoints();
        boolean dragActive = draggingWaypoint && dragWaypointIndex >= 0 && dragWaypointIndex < group.size();
        int blockSize = dragActive ? (group.isSubwaypoint(dragWaypointIndex) ? 1
                : group.childEndExclusive(dragWaypointIndex) - dragWaypointIndex) : 0;
        if (dragActive) updateWaypointDropSlot(mouseX, mouseY);
        int landingIndex = dragWaypointIndex;
        if (waypointDropSlot >= 0) {
            landingIndex = waypointDropSlot > dragWaypointIndex
                    ? Math.max(dragWaypointIndex, waypointDropSlot - blockSize) : waypointDropSlot;
        }
        if (!dragActive) renderWaypointConnectors(g, x1 + ROUTE_LIST_INSET, y1, y2, y, pitch);
        for (int i = 0; i < pts.size(); i++) {
            if (dragActive && i >= dragWaypointIndex && i < dragWaypointIndex + blockSize) continue;
            int visualIndex = i;
            if (dragActive) {
                if (i > dragWaypointIndex) visualIndex -= blockSize;
                if (visualIndex >= landingIndex) visualIndex += blockSize;
            }
            int rowTop = y1 + ROUTE_LIST_INSET - scrollOffset + visualIndex * pitch;
            if (rowTop + ROW_H < y1 || rowTop > y2) continue;
            renderWaypointRow(g, pts.get(i), i, x1 + ROUTE_LIST_INSET, rowTop,
                    x2 - ROUTE_LIST_INSET,
                    mouseX, mouseY, hasSubwaypoints);
        }
        if (dragActive) {
            int sourceTop = (int) mouseY - ROW_H / 2;
            int sourceBottom = sourceTop + (blockSize - 1) * pitch + ROW_H;
            g.fill(x1 + ROUTE_LIST_INSET, sourceTop, x2 - ROUTE_LIST_INSET, sourceBottom, OVERLAY);
            for (int i = 0; i < blockSize; i++) {
                int index = dragWaypointIndex + i;
                renderWaypointRow(g, pts.get(index), index, x1 + ROUTE_LIST_INSET,
                        sourceTop + i * pitch, x2 - ROUTE_LIST_INSET, -1, -1, hasSubwaypoints);
            }
            drawDragOutline(g, x1 + ROUTE_LIST_INSET, sourceTop,
                    x2 - ROUTE_LIST_INSET, sourceBottom, ACCENT, false);
            if (waypointDropSlot >= 0) {
                int targetTop = y1 + ROUTE_LIST_INSET - scrollOffset + landingIndex * pitch;
                drawDragOutline(g, x1 + ROUTE_LIST_INSET, targetTop,
                        x2 - ROUTE_LIST_INSET, targetTop + (blockSize - 1) * pitch + ROW_H, ACCENT, true);
            }
            g.requestCursor(CursorTypes.POINTING_HAND);
        }
        g.disableScissor();
        renderRouteScrollbar(g, layout(), mouseX, mouseY);

        if (editingIndex >= 0 && editingIndex < pts.size()) {
            positionLabelEditor(editingIndex);
        }
    }

    private void renderRouteScrollbar(GuiGraphicsExtractor g, Layout layout,
                                      int mouseX, int mouseY) {
        RouteScrollbarGeometry scrollbar = routeScrollbarGeometry(layout);
        if (scrollbar == null) return;

        boolean hovered = isOverRouteScrollbar(mouseX, mouseY, layout);
        if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
        g.fill(scrollbar.x(), scrollbar.trackTop(),
                scrollbar.x() + ROUTE_SCROLLBAR_W, scrollbar.trackBottom(), BORDER);
        g.fill(scrollbar.x(), scrollbar.thumbTop(),
                scrollbar.x() + ROUTE_SCROLLBAR_W,
                scrollbar.thumbTop() + scrollbar.thumbHeight(),
                hovered || draggingRouteScrollbar ? TEXT_DIM : TEXT_MUTED);
    }

    private RouteScrollbarGeometry routeScrollbarGeometry(Layout layout) {
        int trackTop = layout.top() + ROUTE_LIST_INSET;
        int trackBottom = layout.bottom() - ROUTE_LIST_INSET;
        int trackHeight = Math.max(0, trackBottom - trackTop);
        int maxScroll = routeListMaxScroll(group.size(), layout.bottom() - layout.top());
        if (trackHeight <= 0 || maxScroll <= 0) return null;

        long contentHeight = Math.max(1L, (long) group.size() * waypointRowPitch());
        int thumbHeight = Math.min(trackHeight, Math.max(ROUTE_SCROLLBAR_MIN_THUMB_H,
                (int) ((long) trackHeight * trackHeight / contentHeight)));
        int travel = trackHeight - thumbHeight;
        int thumbTop = trackTop + (travel <= 0 ? 0
                : Math.max(0, Math.min(maxScroll, scrollOffset)) * travel / maxScroll);
        int x = layout.mainRight() - ROUTE_LIST_INSET - ROUTE_SCROLLBAR_W;
        return new RouteScrollbarGeometry(x, trackTop, trackBottom,
                thumbTop, thumbHeight, maxScroll);
    }

    private boolean isOverRouteScrollbar(double mouseX, double mouseY, Layout layout) {
        RouteScrollbarGeometry scrollbar = routeScrollbarGeometry(layout);
        if (scrollbar == null) return false;
        int hitLeft = layout.mainRight() - ROUTE_LIST_INSET - ROUTE_SCROLLBAR_HIT_W;
        return mouseX >= hitLeft && mouseX < layout.mainRight() - ROUTE_LIST_INSET
                && mouseY >= scrollbar.trackTop() && mouseY < scrollbar.trackBottom();
    }

    private void renderWaypointConnectors(GuiGraphicsExtractor g,
                                          int rowX, int clipTop, int clipBottom,
                                          int firstRowY, int pitch) {
        int mainCenterX = rowX + GAP + 2 + 7;
        int childCenterX = mainCenterX + 16;

        for (ConnectorSegment segment : connectorSegments(group)) {
            if (segment.horizontal()) {
                int centerY = firstRowY + segment.toIndex() * pitch + ROW_H / 2;
                drawHorizontalGradientLine(g, mainCenterX + 1, childCenterX, centerY,
                        segment.color1(), segment.color2(), clipTop, clipBottom);
            } else {
                int fromY = firstRowY + segment.fromIndex() * pitch + ROW_H / 2;
                int toY = firstRowY + segment.toIndex() * pitch + ROW_H / 2;
                drawVerticalGradientLine(g, mainCenterX, fromY, toY,
                        segment.color1(), segment.color2(), clipTop, clipBottom);
            }
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
        return 0xCC000000 | interpolateRgb(color1, color2, t);
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

        int indent = subwaypoint ? SUBWAYPOINT_INDENT : 0;

        int sx = x1 + GAP + 2 + indent;
        int sy = y1 + 4;
        int swatchColor = 0xFF000000 | (w.color() & 0xFFFFFF);
        g.fill(sx, sy, sx + 14, sy + 14, swatchColor);
        if (w.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
            g.fill(sx - 1, sy - 1, sx + 15, sy,      0xFFFFFFFF);
            g.fill(sx - 1, sy + 14, sx + 15, sy + 15, 0xFFFFFFFF);
            g.fill(sx - 1, sy, sx, sy + 14,           0xFFFFFFFF);
            g.fill(sx + 14, sy, sx + 15, sy + 14,     0xFFFFFFFF);
        }

        LocalizedNumberFormatter numbers = displayNumbers();
        String ordinal = numbers.waypointOrdinal(group.displayIndexLabel(index));
        String coordinates = coordinateLabel(w, numbers);
        int textColor = w.isDisabled() ? TEXT_MUTED
                : visuallyActive ? 0xFFFFF080
                : index < group.currentIndex() ? TEXT_MUTED
                : subwaypoint ? TEXT_DIM
                : TEXT;
        boolean showDungeonWaypointControls = isDungeonRoomGroup();
        int textX = sx + 20;
        boolean showInlineControls = shouldShowWaypointControls(index, selectedIndex);
        String controlSummary = showInlineControls
                ? ""
                : waypointControlSummary(w, subwaypoint, showDungeonWaypointControls);
        int textRightX = waypointRowTextRightEdge(x2, subwaypoint,
                showDungeonWaypointControls, showInlineControls,
                waypointRightMetadataWidth(w, subwaypoint, isCurrent, controlSummary));
        renderWaypointRowLabelAndName(g, ordinal, coordinates, w, index, textX,
                x1 + coordinateColumnOffset, textRightX, y1, textColor);

        int rightTextX = x2 - GAP;
        if (showInlineControls) {
            renderWaypointControlButtons(g, w, x2, y1, mouseX, mouseY,
                    showDungeonWaypointControls);
            rightTextX = waypointControlButtonsLeft(x2, showDungeonWaypointControls) - GAP;
            if (subwaypoint) {
                renderSubwaypointStyleButtons(g, w, x2, y1, mouseX, mouseY,
                        showDungeonWaypointControls);
                rightTextX = subwaypointStyleButtonsLeft(x2, showDungeonWaypointControls) - GAP;
            }
        }
        if (!controlSummary.isEmpty()) {
            int summaryW = font.width(controlSummary);
            g.text(font, controlSummary, rightTextX - summaryW, y1 + 7, TEXT_MUTED, false);
            rightTextX -= summaryW + GAP;
        }
        if (subwaypoint) {
            String tag = subwaypointTagText();
            int tagW = font.width(tag);
            g.text(font, tag, rightTextX - tagW, y1 + 7, SUBWAY_ACCENT, false);
            rightTextX -= tagW + GAP;
        }
        if (w.isDisabled()) {
            String tag = disabledTagText();
            g.text(font, tag, rightTextX - font.width(tag), y1 + 7, GuiTokens.DANGER, false);
        } else if (w.customRadius() > 0) {
            String r = Component.translatable("waypointer.screen.group_edit.row.radius",
                    numbers.oneDecimal(w.customRadius())).getString();
            g.text(font, r, rightTextX - font.width(r), y1 + 7, TEXT_DIM, false);
        } else if (isCurrent) {
            String tag = currentTagText();
            g.text(font, tag, rightTextX - font.width(tag), y1 + 7, 0xFFFFF080, false);
        }
    }

    private void renderWaypointRowLabelAndName(GuiGraphicsExtractor g, String ordinal,
                                               String coordinates, Waypoint waypoint, int index,
                                               int textX, int coordinateX, int textRightX,
                                               int rowY, int labelColor) {
        if (textRightX - textX <= 0) return;

        String clippedOrdinal = font.plainSubstrByWidth(ordinal, Math.max(0, textRightX - textX));
        if (!clippedOrdinal.isEmpty()) {
            g.text(font, clippedOrdinal, textX, rowY + 7, labelColor, false);
        }
        String clippedCoordinates = font.plainSubstrByWidth(coordinates,
                Math.max(0, textRightX - coordinateX));
        if (!clippedCoordinates.isEmpty()) {
            g.text(font, clippedCoordinates, coordinateX, rowY + 7, labelColor, false);
        }

        if (!waypoint.hasName() || index == editingIndex) return;

        int nameX = coordinateX + font.width(clippedCoordinates) + GAP;
        int nameWidth = Math.max(0, textRightX - nameX);
        if (nameWidth <= 0) return;

        String clippedName = font.plainSubstrByWidth(
                AmpersandFormatting.translate(waypoint.name()), nameWidth);
        if (!clippedName.isEmpty()) {
            g.text(font, clippedName, nameX, rowY + 7, TEXT_DIM, false);
        }
    }

    private int waypointRightMetadataWidth(Waypoint waypoint, boolean subwaypoint,
                                           boolean isCurrent, String controlSummary) {
        int width = 0;
        if (subwaypoint) {
            width += font.width(subwaypointTagText());
        }

        String trailing = waypointTrailingMetadata(waypoint, isCurrent);
        if (!trailing.isEmpty()) {
            if (width > 0) width += GAP;
            width += font.width(trailing);
        }
        if (controlSummary != null && !controlSummary.isEmpty()) {
            if (width > 0) width += GAP;
            width += font.width(controlSummary);
        }
        return width;
    }

    private static String waypointTrailingMetadata(Waypoint waypoint, boolean isCurrent) {
        if (waypoint.isDisabled()) return disabledTagText();
        if (waypoint.customRadius() > 0) {
            return Component.translatable("waypointer.screen.group_edit.row.radius",
                    displayNumbers().oneDecimal(waypoint.customRadius())).getString();
        }
        return isCurrent ? currentTagText() : "";
    }

    private static String subwaypointTagText() {
        return Component.translatableWithFallback(
                "waypointer.screen.group_edit.row.subwaypoint", "subwaypoint").getString();
    }

    private static String disabledTagText() {
        return Component.translatableWithFallback(
                "waypointer.screen.group_edit.row.disabled", "disabled").getString();
    }

    private static String currentTagText() {
        return Component.translatableWithFallback(
                "waypointer.screen.group_edit.row.current", "current").getString();
    }

    private static LocalizedNumberFormatter displayNumbers() {
        return LocalizedNumberFormatter.active();
    }

    private static String coordinateLabel(
            Waypoint waypoint, LocalizedNumberFormatter numbers) {
        return "(" + numbers.integer(waypoint.x()) + ", "
                + numbers.integer(waypoint.y()) + ", "
                + numbers.integer(waypoint.z()) + ")";
    }

    static int waypointRowTextRightEdge(int rowRight, boolean subwaypoint,
                                        boolean showDungeonControls,
                                        boolean showInlineControls,
                                        int rightMetadataWidth) {
        int right = rowRight - GAP;
        if (showInlineControls) {
            right = subwaypoint
                    ? subwaypointStyleButtonsLeft(rowRight, showDungeonControls) - GAP
                    : waypointControlButtonsLeft(rowRight, showDungeonControls) - GAP;
        }
        if (rightMetadataWidth > 0) {
            right -= rightMetadataWidth + GAP;
        }
        return right;
    }

    static int waypointRowTextWidth(int textLeft, int rowRight, boolean subwaypoint,
                                    boolean showDungeonControls, boolean showInlineControls,
                                    int rightMetadataWidth) {
        return Math.max(0, waypointRowTextRightEdge(rowRight, subwaypoint,
                showDungeonControls, showInlineControls, rightMetadataWidth) - textLeft);
    }

    private void renderWaypointControlButtons(GuiGraphicsExtractor g, Waypoint waypoint,
                                              int rowRight, int rowY,
                                              int mouseX, int mouseY,
                                              boolean showDungeonControls) {
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        if (showDungeonControls) {
            int typeX = dungeonTypeButtonX(rowRight);
            renderDungeonTypeButton(g, waypoint, typeX, y,
                    isInside(mouseX, mouseY, typeX, y,
                            DUNGEON_TYPE_BUTTON_SIZE, DUNGEON_TYPE_BUTTON_SIZE));

            int standX = standSkipButtonX(rowRight);
            renderWaypointControlButton(g, standX, y,
                    waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_STAND),
                    isInside(mouseX, mouseY, standX, y,
                            SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE),
                    WAYPOINT_CONTROL_ACTION_STAND_SKIP);

            int interactX = interactSkipButtonX(rowRight);
            renderWaypointControlButton(g, interactX, y,
                    waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT),
                    isInside(mouseX, mouseY, interactX, y,
                            SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE),
                    WAYPOINT_CONTROL_ACTION_INTERACT_SKIP);

            int mineX = mineSkipButtonX(rowRight);
            renderWaypointControlButton(g, mineX, y,
                    waypoint.hasFlag(Waypoint.FLAG_SKIP_ON_MINE),
                    isInside(mouseX, mouseY, mineX, y,
                            SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE),
                    WAYPOINT_CONTROL_ACTION_MINE_SKIP);
        }
        renderDepthCheckButton(g, waypoint, rowRight, rowY, mouseX, mouseY);
    }

    private void renderDungeonTypeButton(GuiGraphicsExtractor g, Waypoint waypoint,
                                         int x, int y, boolean hovered) {
        boolean open = dungeonTypePickerIndex == selectedIndex;
        int border = hovered || open ? 0xB0FFFFFF : BORDER;
        int fill = open ? 0xFF315F8F : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        g.fill(x, y, x + DUNGEON_TYPE_BUTTON_SIZE, y + DUNGEON_TYPE_BUTTON_SIZE, border);
        g.fill(x + 1, y + 1, x + DUNGEON_TYPE_BUTTON_SIZE - 1,
                y + DUNGEON_TYPE_BUTTON_SIZE - 1, fill);
        List<DungeonWaypointType> active = DungeonWaypointType.activeTypes(waypoint);
        if (active.isEmpty()) {
            renderDungeonTypeAtlasIcon(g, x, y, DungeonWaypointType.WAYPOINT_TYPE_ICON_INDEX, TEXT_DIM);
        } else {
            renderDungeonTypeIcon(g, x, y, active.getFirst(), TEXT);
            if (active.size() > 1) {
                String count = displayNumbers().integer(active.size());
                g.text(font, count, x + DUNGEON_TYPE_BUTTON_SIZE - font.width(count) - 1,
                        y + DUNGEON_TYPE_BUTTON_SIZE - font.lineHeight, ACCENT, false);
            }
        }
    }

    private void renderDungeonTypeIcon(GuiGraphicsExtractor g, int x, int y,
                                       DungeonWaypointType type, int color) {
        renderDungeonTypeAtlasIcon(g, x, y, type.iconIndex(), type.fallbackGlyph(), color);
    }

    private void renderDungeonTypeAtlasIcon(GuiGraphicsExtractor g, int x, int y,
                                             int iconIndex, int color) {
        renderDungeonTypeAtlasIcon(g, x, y, iconIndex, "T", color);
    }

    private void renderDungeonTypeAtlasIcon(GuiGraphicsExtractor g, int x, int y,
                                             int iconIndex, String fallbackGlyph, int color) {
        int inset = (DUNGEON_TYPE_BUTTON_SIZE - DungeonWaypointType.ICON_SIZE) / 2;
        if (dungeonTypeIconsAvailable) {
            g.blit(RenderPipelines.GUI_TEXTURED, DUNGEON_TYPE_ICONS,
                    x + inset, y + inset,
                    iconIndex * (float) DungeonWaypointType.ICON_SIZE, 0f,
                    DungeonWaypointType.ICON_SIZE, DungeonWaypointType.ICON_SIZE,
                    DungeonWaypointType.ICON_ATLAS_WIDTH, DungeonWaypointType.ICON_SIZE);
            return;
        }
        renderDungeonTypeFallbackGlyph(g, x, y, fallbackGlyph, color);
    }

    private void renderDungeonTypeFallbackGlyph(GuiGraphicsExtractor g, int x, int y,
                                                String glyph, int color) {
        g.text(font, glyph,
                x + (DUNGEON_TYPE_BUTTON_SIZE - font.width(glyph)) / 2,
                y + (DUNGEON_TYPE_BUTTON_SIZE - font.lineHeight) / 2,
                color, false);
    }

    private void renderDungeonTypePicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        DungeonTypePickerBounds bounds = dungeonTypePickerBounds();
        if (bounds == null || dungeonTypePickerIndex < 0
                || dungeonTypePickerIndex >= group.size()) return;

        GuiTokens.drawTooltipPanel(g, bounds.left(), bounds.top(),
                bounds.right(), bounds.bottom());
        g.text(font, Component.translatableWithFallback(
                        "waypointer.screen.group_edit.control.types.title", "Waypoint types"),
                bounds.left() + DUNGEON_TYPE_PICKER_PAD,
                bounds.top() + DUNGEON_TYPE_PICKER_PAD, TEXT, false);

        Waypoint waypoint = group.get(dungeonTypePickerIndex);
        DungeonWaypointType[] types = DungeonWaypointType.values();
        for (int i = 0; i < types.length; i++) {
            DungeonTypePickerCell cell = dungeonTypePickerCell(bounds, i);
            DungeonWaypointType type = types[i];
            boolean active = type.isSet(waypoint);
            boolean hovered = cell.contains(mouseX, mouseY);
            int border = hovered ? 0xB0FFFFFF : BORDER;
            int fill = active ? SUBWAY_STYLE_BUTTON_ACTIVE
                    : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
            g.fill(cell.left(), cell.top(), cell.right(), cell.bottom(), border);
            g.fill(cell.left() + 1, cell.top() + 1, cell.right() - 1, cell.bottom() - 1, fill);
            renderDungeonTypeIcon(g, cell.left(), cell.top(), type, active ? TEXT : TEXT_DIM);
        }
    }

    private DungeonTypePickerBounds dungeonTypePickerBounds() {
        if (!isDungeonRoomGroup() || dungeonTypePickerIndex < 0
                || dungeonTypePickerIndex >= group.size()
                || dungeonTypePickerIndex != selectedIndex) return null;
        Layout layout = layout();
        int rowY = layout.top() + ROUTE_LIST_INSET - scrollOffset
                + dungeonTypePickerIndex * waypointRowPitch();
        if (rowY + ROW_H < layout.top() || rowY > layout.bottom()) return null;
        int rowRight = layout.mainRight() - ROUTE_LIST_INSET;
        return dungeonTypePickerBounds(dungeonTypeButtonX(rowRight), rowY,
                layout.mainLeft(), layout.mainRight(), layout.top(), layout.bottom());
    }

    static DungeonTypePickerBounds dungeonTypePickerBounds(
            int anchorX, int rowY, int panelLeft, int panelRight, int panelTop, int panelBottom) {
        int x = Math.max(panelLeft + ROUTE_LIST_INSET,
                Math.min(anchorX + DUNGEON_TYPE_BUTTON_SIZE - DUNGEON_TYPE_PICKER_W,
                        panelRight - ROUTE_LIST_INSET - DUNGEON_TYPE_PICKER_W));
        int below = rowY + ROW_H + DUNGEON_TYPE_PICKER_GAP;
        int above = rowY - DUNGEON_TYPE_PICKER_GAP - DUNGEON_TYPE_PICKER_H;
        int y = below + DUNGEON_TYPE_PICKER_H <= panelBottom - ROUTE_LIST_INSET
                ? below : Math.max(panelTop + ROUTE_LIST_INSET, above);
        return new DungeonTypePickerBounds(x, y,
                x + DUNGEON_TYPE_PICKER_W, y + DUNGEON_TYPE_PICKER_H);
    }

    static DungeonTypePickerCell dungeonTypePickerCell(DungeonTypePickerBounds bounds, int index) {
        if (bounds == null || index < 0 || index >= DungeonWaypointType.values().length) return null;
        int column = index % DUNGEON_TYPE_PICKER_COLUMNS;
        int row = index / DUNGEON_TYPE_PICKER_COLUMNS;
        int left = bounds.left() + DUNGEON_TYPE_PICKER_PAD
                + column * (DUNGEON_TYPE_BUTTON_SIZE + DUNGEON_TYPE_PICKER_GAP);
        int top = bounds.top() + DUNGEON_TYPE_PICKER_PAD + DUNGEON_TYPE_PICKER_TITLE_H
                + row * (DUNGEON_TYPE_BUTTON_SIZE + DUNGEON_TYPE_PICKER_GAP);
        return new DungeonTypePickerCell(left, top,
                left + DUNGEON_TYPE_BUTTON_SIZE, top + DUNGEON_TYPE_BUTTON_SIZE, index);
    }

    private int dungeonTypePickerTypeAt(double mouseX, double mouseY) {
        DungeonTypePickerBounds bounds = dungeonTypePickerBounds();
        if (bounds == null || !bounds.contains(mouseX, mouseY)) return -1;
        for (int i = 0; i < DungeonWaypointType.values().length; i++) {
            DungeonTypePickerCell cell = dungeonTypePickerCell(bounds, i);
            if (cell != null && cell.contains(mouseX, mouseY)) return i;
        }
        return -1;
    }

    static class DungeonTypePickerBounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        DungeonTypePickerBounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int left() { return left; }
        int top() { return top; }
        int right() { return right; }
        int bottom() { return bottom; }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    static final class DungeonTypePickerCell extends DungeonTypePickerBounds {
        private final int typeIndex;

        DungeonTypePickerCell(int left, int top, int right, int bottom, int typeIndex) {
            super(left, top, right, bottom);
            this.typeIndex = typeIndex;
        }

        int typeIndex() { return typeIndex; }
    }

    private void renderWaypointControlButton(GuiGraphicsExtractor g, int x, int y,
                                             boolean active, boolean hovered,
                                             int action) {
        int bg = active ? DEPTH_CHECK_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xB0FFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_SIZE, y + SUBWAY_STYLE_BUTTON_SIZE, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_SIZE - 1,
                y + SUBWAY_STYLE_BUTTON_SIZE - 1, bg);
        renderWaypointControlIcon(g, x, y, waypointControlIconIndex(action));
    }

    private void renderDepthCheckButton(GuiGraphicsExtractor g, Waypoint waypoint,
                                        int rowRight, int rowY,
                                        int mouseX, int mouseY) {
        int x = depthCheckButtonX(rowRight);
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        boolean active = waypoint.hasFlag(Waypoint.FLAG_DEPTH_CHECKED);
        boolean hovered = isInside(mouseX, mouseY, x, y,
                SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE);
        int bg = active ? DEPTH_CHECK_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xB0FFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_SIZE, y + SUBWAY_STYLE_BUTTON_SIZE, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_SIZE - 1,
                y + SUBWAY_STYLE_BUTTON_SIZE - 1, bg);
        renderWaypointControlIcon(g, x, y,
                waypointControlIconIndex(WAYPOINT_CONTROL_ACTION_DEPTH_CHECK));
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
                isInside(mouseX, mouseY, smallX, y,
                        SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE),
                SUBWAY_STYLE_ACTION_SMALL);
        renderSubwaypointStyleButton(g, filledX, y,
                waypoint.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT),
                isInside(mouseX, mouseY, filledX, y,
                        SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE),
                SUBWAY_STYLE_ACTION_FILLED);
        renderSubwaypointStyleButton(g, hideX, y,
                waypoint.hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED),
                isInside(mouseX, mouseY, hideX, y,
                        SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE),
                SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT);
    }

    private void renderSubwaypointStyleButton(GuiGraphicsExtractor g, int x, int y,
                                              boolean active, boolean hovered,
                                              int action) {
        int bg = active ? SUBWAY_STYLE_BUTTON_ACTIVE
                : hovered ? SUBWAY_STYLE_BUTTON_HOVER : SUBWAY_STYLE_BUTTON_IDLE;
        int border = hovered ? 0xB0FFFFFF : BORDER;
        g.fill(x, y, x + SUBWAY_STYLE_BUTTON_SIZE, y + SUBWAY_STYLE_BUTTON_SIZE, border);
        g.fill(x + 1, y + 1, x + SUBWAY_STYLE_BUTTON_SIZE - 1,
                y + SUBWAY_STYLE_BUTTON_SIZE - 1, bg);
        renderWaypointControlIcon(g, x, y, subwaypointStyleIconIndex(action));
    }

    private static void renderWaypointControlIcon(GuiGraphicsExtractor g, int x, int y,
                                                   int iconIndex) {
        int inset = (SUBWAY_STYLE_BUTTON_SIZE - WAYPOINT_CONTROL_ICON_SIZE) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, WAYPOINT_CONTROL_ICONS,
                x + inset, y + inset,
                iconIndex * (float) WAYPOINT_CONTROL_ICON_SIZE, 0f,
                WAYPOINT_CONTROL_ICON_SIZE, WAYPOINT_CONTROL_ICON_SIZE,
                WAYPOINT_CONTROL_ICON_ATLAS_W, WAYPOINT_CONTROL_ICON_SIZE);
    }

    static int subwaypointStyleIconIndex(int action) {
        return action - SUBWAY_STYLE_ACTION_SMALL;
    }

    static int waypointControlIconIndex(int action) {
        return 3 + action - WAYPOINT_CONTROL_ACTION_STAND_SKIP;
    }

    private static int subwaypointStyleButtonsLeft(int rowRight, boolean showDungeonControls) {
        return waypointControlButtonX(rowRight, showDungeonControls ? 7 : 3);
    }

    private static int waypointControlButtonsLeft(int rowRight, boolean showDungeonControls) {
        return showDungeonControls ? dungeonTypeButtonX(rowRight) : depthCheckButtonX(rowRight);
    }

    static int dungeonTypeButtonX(int rowRight) {
        return waypointControlButtonX(rowRight, 4);
    }

    private static int standSkipButtonX(int rowRight) {
        return waypointControlButtonX(rowRight, 3);
    }

    private static int interactSkipButtonX(int rowRight) {
        return waypointControlButtonX(rowRight, 2);
    }

    private static int mineSkipButtonX(int rowRight) {
        return waypointControlButtonX(rowRight, 1);
    }

    private static int depthCheckButtonX(int rowRight) {
        return waypointControlButtonX(rowRight, 0);
    }

    static int waypointControlButtonX(int rowRight, int indexFromRight) {
        return rowRight - GAP - SUBWAY_STYLE_BUTTON_SIZE
                - indexFromRight * (SUBWAY_STYLE_BUTTON_SIZE + GAP_TIGHT);
    }

    private static int subwaypointStyleButtonX(int rowRight, int action,
                                               boolean showDungeonControls) {
        int left = subwaypointStyleButtonsLeft(rowRight, showDungeonControls);
        int pitch = SUBWAY_STYLE_BUTTON_SIZE + GAP_TIGHT;
        if (action == SUBWAY_STYLE_ACTION_FILLED) return left + pitch;
        if (action == SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT) return left + pitch * 2;
        return left;
    }

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean isDungeonRoomGroup() {
        return group != null && group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        clearWaypointDrag();
        if (contextMenu.isOpen() && contextMenu.mouseClicked(event.x(), event.y())) return true;
        if (inPeekedList(event.x(), event.y())) return true;
        int selectedBeforeClick = selectedIndex;
        if (editingIndex >= 0 && !isOverLabelEditor(event.x(), event.y())) {
            commitLabelEdit();
        }

        GroupEditInputRouter.Pointer pointer = new GroupEditInputRouter.Pointer(
                event.x(), event.y(), event.button(), hasShiftDown(), doubleClick);
        GroupEditInputRouter.State state = new GroupEditInputRouter.State(
                dungeonTypePickerIndex >= 0, selectedIndex);
        GroupEditInputRouter.Action action = GroupEditInputRouter.beforeWidgets(
                pointer, state, inputGeometry);
        if (action.handledBeforeWidgets()) {
            return applyInputAction(action, pointer, selectedBeforeClick);
        }
        if (super.mouseClicked(event, doubleClick)) return true;

        GroupEditInputRouter.Action rowAction = GroupEditInputRouter.afterWidgets(
                pointer,
                new GroupEditInputRouter.State(dungeonTypePickerIndex >= 0, selectedIndex),
                inputGeometry);
        return applyInputAction(rowAction, pointer, selectedBeforeClick);
    }

    private boolean applyInputAction(
            GroupEditInputRouter.Action action,
            GroupEditInputRouter.Pointer pointer,
            int selectedBeforeClick) {
        int index = action.rowIndex();
        switch (action.type()) {
            case NONE -> {
                return false;
            }
            case CONSUME -> {
                return true;
            }
            case SELECT_DUNGEON_TYPE -> {
                toggleDungeonType(action.value());
                playUiClickSound();
            }
            case CLOSE_DUNGEON_PICKER_WITH_SOUND -> {
                dungeonTypePickerIndex = -1;
                playUiClickSound();
            }
            case CLOSE_DUNGEON_PICKER -> dungeonTypePickerIndex = -1;
            case OPEN_CONTEXT_MENU -> {
                selectWaypoint(index);
                openWaypointMenu(index, pointer.x(), pointer.y());
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "right-click-row", "open-menu");
            }
            case BEGIN_ROUTE_SCROLLBAR_DRAG -> {
                return beginRouteScrollbarDrag(pointer.x(), pointer.y());
            }
            case TOGGLE_DISABLED -> {
                selectWaypoint(index);
                if (group.toggleWaypointDisabled(index)) {
                    recordEdit();
                    playUiClickSound();
                }
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "middle-click-row",
                        group.isWaypointDisabled(index) ? "disable" : "enable");
            }
            case TOGGLE_SUBWAYPOINT -> {
                selectWaypoint(index);
                if (group.toggleSubwaypoint(index)) {
                    if (group.isSubwaypoint(index)) disableSkipAheadForSequencingEdit();
                    coordinateEditorIndex = -1;
                    syncCoordinateEditors();
                    recordEdit();
                }
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "right-click-row", "toggle-subwaypoint");
            }
            case OPEN_DUNGEON_TYPE_PICKER -> {
                selectWaypoint(index);
                dungeonTypePickerIndex = index;
                playUiClickSound();
            }
            case TOGGLE_WAYPOINT_CONTROL -> {
                toggleWaypointControl(index, action.value());
                selectWaypoint(index);
                playUiClickSound();
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "waypoint-control", "toggle-control-" + action.value());
            }
            case TOGGLE_SUBWAYPOINT_STYLE -> {
                toggleSubwaypointStyle(index, action.value());
                selectWaypoint(index);
                playUiClickSound();
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "subwaypoint-style", "toggle-style-" + action.value());
            }
            case REPOSITION -> {
                selectWaypoint(index);
                WaypointRepositionMode.start(manager, config, group, index);
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "shift-row", "start-edit-move");
            }
            case UNLOCK_COLOR, EDIT_COLOR -> {
                playUiClickSound();
                if (action.type() == GroupEditInputRouter.ActionType.UNLOCK_COLOR) {
                    unlockWaypointColor(index);
                } else {
                    openWaypointColorPicker(index);
                }
                selectWaypoint(index);
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "color-swatch",
                        pointer.shiftDown() ? "unlock-or-edit-color" : "edit-color");
            }
            case SELECT, RENAME -> {
                selectWaypoint(index);
                if (action.type() == GroupEditInputRouter.ActionType.RENAME) {
                    beginLabelEdit(index);
                } else if (!pointer.shiftDown()) {
                    dragWaypointIndex = index;
                    waypointDragStartX = pointer.x();
                    waypointDragStartY = pointer.y();
                }
                recordRowClick(index, selectedBeforeClick, pointer.doubleClick(),
                        "waypoint-row",
                        action.type() == GroupEditInputRouter.ActionType.RENAME
                                ? "rename"
                                : pointer.doubleClick()
                                        ? "double-click ignored: waypoint was not already selected"
                                        : "select");
            }
        }
        return true;
    }

    private final ContextMenuOverlay contextMenu = new ContextMenuOverlay();

    private int coordinateColumnOffset;

    private int coordinateColumnOffset() {
        LocalizedNumberFormatter numbers = displayNumbers();
        int widest = 0;
        int main = 0;
        int child = 0;
        for (int i = 0; i < group.size(); i++) {
            boolean subwaypoint = group.isSubwaypoint(i);
            if (subwaypoint) child++;
            else {
                main++;
                child = 0;
            }
            String label = "#" + main + (subwaypoint ? "." + child : "");
            int indent = subwaypoint ? SUBWAYPOINT_INDENT : 0;
            widest = Math.max(widest, indent + font.width(numbers.waypointOrdinal(label)));
        }
        return GAP + 2 + 20 + widest + GAP;
    }

    private void openWaypointMenu(int index, double anchorX, double anchorY) {
        if (index < 0 || index >= group.size()) return;
        Waypoint waypoint = group.get(index);
        boolean subwaypoint = group.isSubwaypoint(index);
        List<ContextMenuOverlay.Item> items = new ArrayList<>();
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.group_edit.menu.goto"),
                null, () -> setCurrentWaypoint(index)));
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.group_edit.menu.rename"),
                "double-click", () -> beginLabelEdit(index)));
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable(waypoint.isDisabled()
                        ? "waypointer.screen.group_edit.menu.enable"
                        : "waypointer.screen.group_edit.menu.disable"),
                "middle-click", () -> {
            if (group.toggleWaypointDisabled(index)) recordEdit();
        }));
        Component subwaypointLabel = Component.translatable(subwaypoint
                ? "waypointer.screen.group_edit.menu.subwaypoint_off"
                : "waypointer.screen.group_edit.menu.subwaypoint_on");
        if (!subwaypoint && !group.canMakeSubwaypoint(index)) items.add(ContextMenuOverlay.Item.disabled(
                subwaypointLabel, Component.translatable(index == 0
                        ? "waypointer.screen.group_edit.menu.subwaypoint_first"
                        : "waypointer.screen.group_edit.menu.subwaypoint_no_main").getString()));
        else items.add(ContextMenuOverlay.Item.of(subwaypointLabel,
                "shift-right-click", () -> {
            if (group.toggleSubwaypoint(index)) {
                if (group.isSubwaypoint(index)) disableSkipAheadForSequencingEdit();
                coordinateEditorIndex = -1;
                syncCoordinateEditors();
                recordEdit();
            }
        }));
        int subwaypointParent = subwaypoint ? group.parentMainIndex(index) : index;
        Component addSubLabel = Component.translatable("waypointer.screen.group_edit.menu.add_subwaypoint");
        items.add(subwaypointParent >= 0 && DungeonRoomRouteLibrary.durableEditTarget(manager, group) == group
                ? ContextMenuOverlay.Item.of(addSubLabel, null, () -> addSubwaypointHere(subwaypointParent))
                : ContextMenuOverlay.Item.disabled(addSubLabel, Component.translatable(
                        "waypointer.screen.group_edit.menu.appearance.room_copy").getString()));
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.group_edit.menu.reposition"),
                "shift-click", () -> WaypointRepositionMode.start(manager, config, group, index)));
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.group_edit.menu.color"),
                null, () -> openWaypointColorPicker(index)));
        if (waypoint.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable("waypointer.screen.group_edit.menu.color_reset"),
                    "shift-click color", () -> unlockWaypointColor(index)));
        }
        AppearanceTarget look = waypointAppearanceTarget(index);
        int lookCount = look == null ? 0 : look.scope().count();
        Component lookLabel = Component.translatable("waypointer.screen.group_edit.menu.appearance");
        items.add(look == null
                ? ContextMenuOverlay.Item.disabled(lookLabel, Component.translatable(
                        "waypointer.screen.group_edit.menu.appearance.room_copy").getString())
                : ContextMenuOverlay.Item.of(lookLabel, lookCount == 0 ? null
                        : Component.translatable("waypointer.screen.appearance.set_count", lookCount).getString(),
                        () -> openAppearance(look)));
        if (isDungeonRoomGroup()) {
            items.add(ContextMenuOverlay.Item.of(
                    Component.translatable("waypointer.screen.group_edit.menu.types"),
                    null, () -> dungeonTypePickerIndex = index));
        }
        items.add(ContextMenuOverlay.Item.of(
                Component.translatable("waypointer.screen.group_edit.menu.remove"),
                null, () -> {
            selectWaypoint(index);
            removeSelected();
        }));

        Layout layout = layout();
        contextMenu.openAt(font, items, anchorX, anchorY,
                layout.mainLeft(), layout.top(), layout.mainRight(), layout.bottom());
    }

    private void setCurrentWaypoint(int index) {
        selectWaypoint(index);
        DungeonRoomRouteLibrary.setManualCurrentIndex(manager, group, index);
        recordEdit();
    }

    private void recordRowClick(
            int index, int selectedBeforeClick, boolean doubleClick,
            String gesture, String action) {
        DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(index), index,
                selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                doubleClick, hasShiftDown(), false, gesture, action);
    }

    private boolean beginRouteScrollbarDrag(double mouseX, double mouseY) {
        Layout layout = layout();
        if (!isOverRouteScrollbar(mouseX, mouseY, layout)) return false;
        RouteScrollbarGeometry scrollbar = routeScrollbarGeometry(layout);
        if (scrollbar == null) return false;

        draggingRouteScrollbar = true;
        if (mouseY >= scrollbar.thumbTop()
                && mouseY < scrollbar.thumbTop() + scrollbar.thumbHeight()) {
            routeScrollbarDragOffset = (int) mouseY - scrollbar.thumbTop();
        } else {
            routeScrollbarDragOffset = scrollbar.thumbHeight() / 2;
            updateRouteScrollbarDrag(mouseY);
        }
        return true;
    }

    private void updateRouteScrollbarDrag(double mouseY) {
        RouteScrollbarGeometry scrollbar = routeScrollbarGeometry(layout());
        if (scrollbar == null) {
            scrollOffset = 0;
            return;
        }
        scrollOffset = routeScrollOffsetForPointer(mouseY, routeScrollbarDragOffset,
                scrollbar.trackTop(), scrollbar.trackBottom(), scrollbar.thumbHeight(),
                scrollbar.maxScroll());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingRouteScrollbar && event.button() == MOUSE_BUTTON_LEFT) {
            updateRouteScrollbarDrag(event.y());
            return true;
        }
        if (dragWaypointIndex >= 0 && event.button() == MOUSE_BUTTON_LEFT) {
            double distanceX = event.x() - waypointDragStartX;
            double distanceY = event.y() - waypointDragStartY;
            if (!draggingWaypoint && distanceX * distanceX + distanceY * distanceY < 16) return false;
            draggingWaypoint = true;
            updateWaypointDropSlot(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragWaypointIndex >= 0 && event.button() == MOUSE_BUTTON_LEFT) {
            boolean consumed = draggingWaypoint;
            if (consumed) {
                updateWaypointDropSlot(event.x(), event.y());
                int movedTo = dropWaypointSelection(group, dragWaypointIndex, waypointDropSlot);
                if (movedTo != dragWaypointIndex) {
                    coordinateEditorIndex = -1;
                    selectWaypoint(movedTo);
                    recordEdit();
                }
            }
            clearWaypointDrag();
            if (consumed) return true;
        }
        if (draggingRouteScrollbar && event.button() == MOUSE_BUTTON_LEFT) {
            draggingRouteScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void clearWaypointDrag() {
        dragWaypointIndex = -1;
        draggingWaypoint = false;
        waypointDropSlot = -1;
    }

    private void updateWaypointDropSlot(double mouseX, double mouseY) {
        Layout layout = layout();
        waypointDropSlot = -1;
        if (mouseX < layout.mainLeft() || mouseX >= layout.mainRight()
                || mouseY < layout.top() || mouseY >= layout.bottom()) return;
        int slot = (int) Math.floor((mouseY - layout.top() - ROUTE_LIST_INSET
                + scrollOffset + waypointRowPitch() / 2.0) / waypointRowPitch());
        waypointDropSlot = waypointDropSlot(group, dragWaypointIndex,
                Math.max(0, Math.min(group.size(), slot)));
    }

    private void playUiClickSound() {
        if (minecraft == null || minecraft.getSoundManager() == null) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
                || InputConstants.isKeyDown(win, 344  );
    }

    private int swatchIndexAt(double mx, double my) {
        Layout layout = layout();
        if (mx < layout.mainLeft() || mx > layout.mainRight()
                || my < layout.top() || my > layout.bottom()) return -1;

        int pitch = waypointRowPitch();
        int idx = (int) ((my - (layout.top() + ROUTE_LIST_INSET) + scrollOffset) / pitch);
        if (idx < 0 || idx >= group.size()) return -1;

        int rowY = layout.top() + ROUTE_LIST_INSET - scrollOffset + idx * pitch;
        int sx = (layout.mainLeft() + ROUTE_LIST_INSET) + GAP + 2
                + (group.isSubwaypoint(idx) ? SUBWAYPOINT_INDENT : 0);
        int sy = rowY + 4;
        if (mx >= sx && mx < sx + 14 && my >= sy && my < sy + 14) return idx;
        return -1;
    }

    private int dungeonTypeButtonIndexAt(double mx, double my) {
        int idx = rowIndexAt(mx, my);
        if (!isDungeonRoomGroup() || !shouldShowWaypointControls(idx, selectedIndex)) return -1;
        Layout layout = layout();
        int rowY = layout.top() + ROUTE_LIST_INSET
                - scrollOffset + idx * waypointRowPitch();
        int rowRight = layout.mainRight() - ROUTE_LIST_INSET;
        int x = dungeonTypeButtonX(rowRight);
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        return isInside(mx, my, x, y, DUNGEON_TYPE_BUTTON_SIZE, DUNGEON_TYPE_BUTTON_SIZE)
                ? idx : -1;
    }

    private void toggleDungeonType(int typeIndex) {
        if (dungeonTypePickerIndex < 0 || dungeonTypePickerIndex >= group.size()
                || typeIndex < 0 || typeIndex >= DungeonWaypointType.values().length) return;
        Waypoint waypoint = group.get(dungeonTypePickerIndex);
        group.set(dungeonTypePickerIndex,
                DungeonWaypointType.values()[typeIndex].selectExclusive(waypoint));
        recordEdit();
    }

    private int waypointControlActionAt(double mx, double my) {
        int idx = rowIndexAt(mx, my);
        if (!shouldShowWaypointControls(idx, selectedIndex)) {
            return WAYPOINT_CONTROL_ACTION_NONE;
        }

        Layout layout = layout();
        int rowY = layout.top() + ROUTE_LIST_INSET
                - scrollOffset + idx * waypointRowPitch();
        int rowRight = layout.mainRight() - ROUTE_LIST_INSET;
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        if (isDungeonRoomGroup()) {
            int standX = standSkipButtonX(rowRight);
            if (isInside(mx, my, standX, y,
                    SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
                return WAYPOINT_CONTROL_ACTION_STAND_SKIP;
            }
            int interactX = interactSkipButtonX(rowRight);
            if (isInside(mx, my, interactX, y,
                    SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
                return WAYPOINT_CONTROL_ACTION_INTERACT_SKIP;
            }
            int mineX = mineSkipButtonX(rowRight);
            if (isInside(mx, my, mineX, y,
                    SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
                return WAYPOINT_CONTROL_ACTION_MINE_SKIP;
            }
        }

        int depthX = depthCheckButtonX(rowRight);
        if (isInside(mx, my, depthX, y,
                SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
            return WAYPOINT_CONTROL_ACTION_DEPTH_CHECK;
        }
        return WAYPOINT_CONTROL_ACTION_NONE;
    }

    private int subwaypointStyleActionAt(double mx, double my) {
        int idx = rowIndexAt(mx, my);
        if (!shouldShowWaypointControls(idx, selectedIndex)
                || !group.isSubwaypoint(idx)) return SUBWAY_STYLE_ACTION_NONE;

        Layout layout = layout();
        int rowY = layout.top() + ROUTE_LIST_INSET
                - scrollOffset + idx * waypointRowPitch();
        int rowRight = layout.mainRight() - ROUTE_LIST_INSET;
        int y = rowY + SUBWAY_STYLE_BUTTON_TOP_PAD;
        boolean showDungeonControls = isDungeonRoomGroup();
        int smallX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_SMALL,
                showDungeonControls);
        if (isInside(mx, my, smallX, y,
                SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
            return SUBWAY_STYLE_ACTION_SMALL;
        }
        int filledX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_FILLED,
                showDungeonControls);
        if (isInside(mx, my, filledX, y,
                SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
            return SUBWAY_STYLE_ACTION_FILLED;
        }
        int hideX = subwaypointStyleButtonX(rowRight, SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT,
                showDungeonControls);
        if (isInside(mx, my, hideX, y,
                SUBWAY_STYLE_BUTTON_SIZE, SUBWAY_STYLE_BUTTON_SIZE)) {
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
        if (action == WAYPOINT_CONTROL_ACTION_MINE_SKIP) {
            return dungeonMineSkipTooltipText();
        }
        if (action == WAYPOINT_CONTROL_ACTION_DEPTH_CHECK) {
            return Component.translatableWithFallback(
                    "waypointer.screen.group_edit.control.los.tooltip",
                    "Render in LOS only").getString();
        }
        return subwaypointStyleTooltipAt(mouseX, mouseY);
    }

    private String dungeonTypeTooltipAt(double mouseX, double mouseY) {
        int pickerType = dungeonTypePickerTypeAt(mouseX, mouseY);
        if (pickerType >= 0 && dungeonTypePickerIndex >= 0
                && dungeonTypePickerIndex < group.size()) {
            DungeonWaypointType type = DungeonWaypointType.values()[pickerType];
            return dungeonTypePickerTooltip(type);
        }
        int row = dungeonTypeButtonIndexAt(mouseX, mouseY);
        if (row < 0) return null;
        String summary = DungeonWaypointType.activeTypes(group.get(row)).stream()
                .map(GroupEditPolicy::dungeonWaypointTypeLabel)
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
        return summary.isEmpty()
                ? Component.translatableWithFallback(
                        "waypointer.screen.group_edit.control.types.none",
                        "Waypoint types: None").getString()
                : Component.translatable(
                        "waypointer.screen.group_edit.control.types.summary", summary).getString();
    }

    static String dungeonTypePickerTooltip(DungeonWaypointType type) {
        return GroupEditPolicy.dungeonWaypointTypeLabel(type);
    }

    private String subwaypointStyleTooltipAt(double mouseX, double mouseY) {
        int action = subwaypointStyleActionAt(mouseX, mouseY);
        if (action == SUBWAY_STYLE_ACTION_SMALL) {
            return Component.translatableWithFallback(
                    "waypointer.screen.group_edit.control.subwaypoint.tiny", "Tiny").getString();
        }
        if (action == SUBWAY_STYLE_ACTION_FILLED) {
            return Component.translatableWithFallback(
                    "waypointer.screen.group_edit.control.subwaypoint.filled", "Filled").getString();
        }
        if (action == SUBWAY_STYLE_ACTION_HIDE_AFTER_PARENT) {
            return Component.translatableWithFallback(
                    "waypointer.screen.group_edit.control.subwaypoint.hide_after_parent",
                    "Hide after parent is reached").getString();
        }
        return null;
    }

    private void toggleWaypointControl(int index, int action) {
        if (index < 0 || index >= group.size()) return;
        int flag = waypointControlFlagForAction(action, isDungeonRoomGroup());
        if (flag == 0) return;
        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withFlags(waypoint.flags() ^ flag));
        recordEdit();
    }

    public void toggleSelectedWaypointControl(int action) {
        toggleWaypointControl(selectedIndex, action);
    }

    private void toggleSubwaypointStyle(int index, int action) {
        if (index < 0 || index >= group.size() || !group.isSubwaypoint(index)) return;
        int flag = subwaypointStyleFlagForAction(action);
        if (flag == 0) return;

        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withFlags(waypoint.flags() ^ flag));
        recordEdit();
    }

    public void toggleSelectedSubwaypointStyle(int action) {
        toggleSubwaypointStyle(selectedIndex, action);
    }

    private void openWaypointColorPicker(int idx) {
        if (idx < 0 || idx >= group.size()) return;
        waypointColorPickerState = WaypointColorPickerState.forTarget(group, idx);
        Waypoint w = group.get(idx);
        ColorPickerScreen.openWaypoint(this, Component.translatable(
                        "waypointer.screen.group_edit.picker.waypoint",
                        displayNumbers().integer(idx + 1)),
                w.color(), waypointColorPickerState, this::onWaypointColorPicked);
    }

    private void onWaypointColorPicked(int picked) {
        WaypointColorPickerState state = waypointColorPickerState;
        waypointColorPickerState = null;
        if (state == null || !state.applyColor(group, picked)) return;
        group.setPaintEnabled(false);
        recordEdit();
        rebuildWidgets();
    }

    private void unlockWaypointColor(int idx) {
        group.unlockWaypointColor(idx);
        recordEdit();
    }

    private int rowIndexAt(double mx, double my) {
        Layout layout = layout();
        if (mx < layout.mainLeft() || mx > layout.mainRight()
                || my < layout.top() + ROUTE_LIST_INSET
                || my >= layout.bottom() - ROUTE_LIST_INSET) return -1;
        if (isOverRouteScrollbar(mx, my, layout)) return -1;

        int pitch = waypointRowPitch();
        int idx = (int) ((my - (layout.top() + ROUTE_LIST_INSET) + scrollOffset) / pitch);
        return (idx >= 0 && idx < group.size()) ? idx : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        dungeonTypePickerIndex = -1;
        contextMenu.close();
        if (inPeekedList(mouseX, mouseY)) return true;
        Layout layout = layout();
        int coordinateAxis = coordinateAxisAt(mouseX, mouseY);
        if (coordinateAxis >= 0) {
            scrollCoordinate(coordinateAxis, vert);
            return true;
        }
        if (mouseX >= layout.sidebarLeft() && mouseX <= layout.sidebarRight()
                && mouseY >= layout.top() && mouseY <= layout.bottom()) {
            int viewportHeight = sidebarViewportBottom(layout) - sidebarViewportTop(layout);
            int maxScroll = maxSidebarScroll(sidebarContentHeight, viewportHeight);
            sidebarScrollOffset = Math.max(0, Math.min(maxScroll,
                    sidebarScrollOffset - (int) (vert * (BTN_H + GAP_TIGHT))));
            refreshSidebarWidgets(layout);
            if (getFocused() instanceof AbstractWidget focused && !focused.visible) {
                focused.setFocused(false);
                setFocused(null);
            }
            return true;
        }

        if (mouseX < layout.mainLeft() || mouseX > layout.mainRight()
                || mouseY < layout.top() || mouseY > layout.bottom()) return false;

        if (editingIndex >= 0) commitLabelEdit();

        int pitch = waypointRowPitch();
        int maxScroll = routeListMaxScroll(group.size(), layout.bottom() - layout.top());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (vert * pitch)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (!(getFocused() instanceof EditBox)
                && (InputConstants.isKeyDown(minecraft.getWindow(), 341)
                || InputConstants.isKeyDown(minecraft.getWindow(), 345))
                && (k == 90 || k == 89)) {
            restoreEdit(k == 89 || hasShiftDown());
            return true;
        }
        if (contextMenu.keyPressed(k)) return true;
        if (!peeking && k == GLFW_KEY_MENU && hasSelectedWaypoint()) {
            Layout layout = layout();
            int rowBottom = layout.top() + ROUTE_LIST_INSET - scrollOffset
                    + selectedIndex * waypointRowPitch() + ROW_H;
            openWaypointMenu(selectedIndex, layout.mainLeft() + GAP_SECTION, rowBottom);
            return true;
        }
        if (k == GLFW_KEY_ESCAPE && dungeonTypePickerIndex >= 0) {
            dungeonTypePickerIndex = -1;
            return true;
        }
        if (editingIndex >= 0) {
            if (k == GLFW_KEY_ESCAPE) {
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
        if (k == GLFW_KEY_TAB && focusAdjacentSidebarWidget(hasShiftDown())) return true;
        return super.keyPressed(event);
    }

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
                recordEdit();
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

    private void positionLabelEditor(int index) {
        Layout layout = layout();

        int pitch = waypointRowPitch();
        int rowY = layout.top() + ROUTE_LIST_INSET - scrollOffset + index * pitch;

        int rowX1 = layout.mainLeft() + ROUTE_LIST_INSET;
        int rowX2 = layout.mainRight() - ROUTE_LIST_INSET;
        Waypoint w = group.get(index);
        LocalizedNumberFormatter numbers = displayNumbers();
        int coordinateX = rowX1 + coordinateColumnOffset();
        boolean subwaypoint = group.isSubwaypoint(index);
        boolean isCurrent = !subwaypoint && index == group.currentIndex();
        int textRightX = waypointRowTextRightEdge(rowX2, subwaypoint,
                isDungeonRoomGroup(), shouldShowWaypointControls(index, selectedIndex),
                waypointRightMetadataWidth(w, subwaypoint, isCurrent, ""));
        int editorX = Math.min(coordinateX + font.width(coordinateLabel(w, numbers)) + GAP, textRightX);
        int editorW = labelEditorWidth(editorX, textRightX);

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
    public void removed() {
        lastPublishedName = publishNameChangeIfNeeded(manager, group, lastPublishedName);
        super.removed();
    }

    private record SidebarWidget(AbstractWidget widget, int homeY) {
    }

    private record RouteScrollbarGeometry(int x, int trackTop, int trackBottom,
                                          int thumbTop, int thumbHeight, int maxScroll) {
    }

    private record Layout(int top, int bottom,
                          int sidebarLeft, int sidebarRight,
                          int mainLeft, int mainRight) {
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }
}
