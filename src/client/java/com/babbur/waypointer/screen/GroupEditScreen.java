package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.color.GradientColorizer;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.debug.DebugEventLog;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import com.babbur.waypointer.dungeon.DungeonWaypointSkipRules;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
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
import static com.babbur.waypointer.screen.GroupEditPolicy.*;
import static com.babbur.waypointer.screen.GuiTokens.*;

public final class GroupEditScreen extends Screen {

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup group;
    private String lastPublishedName;

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
    private int waypointColorPickerIndex = -1;
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
    /**
     * Optional user-authored atlas: eight horizontal 12x12 RGBA cells, no gutters.
     * Order: Secret, Etherwarp, Dungeonbreaker, Superboom, Pearl, Pearl target, Item, Bat.
     */
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
    private int routeScrollbarDragOffset;

    private static final int GLFW_KEY_ESCAPE   = 256;
    private static final int GLFW_KEY_ENTER    = 257;
    private static final int GLFW_KEY_TAB      = 258;
    private static final int GLFW_KEY_KP_ENTER = 335;

    public GroupEditScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config, WaypointGroup group) {
        super(Component.translatable("waypointer.screen.group_edit.title", group.name()));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.group = group;
        this.lastPublishedName = group.name();
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
        dungeonTypePickerIndex = -1;
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

        nameBox = new EditBox(font, sidebarInner, y, fieldW, BTN_H,
                Component.translatable("waypointer.screen.group_edit.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(group.name());
        nameBox.setResponder(group::setName);
        addSidebarWidget(nameBox);
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
            manager.fireDataChanged();
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

        Button publishButton = styledButton(width - PAD_OUTER - PUBLISH_BUTTON_W, PAD_OUTER - 5,
                PUBLISH_BUTTON_W, BTN_H,
                Component.translatable("waypointer.screen.group_edit.publish"),
                b -> publish(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.group_edit.publish.tooltip")));
        addRenderableWidget(publishButton);

        GuiTokens.layoutFooter(width, height - FOOTER_H, footerActionSpecs, footerDoneSpec,
                this::addRenderableWidget, font);
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
        left.add(new GuiTokens.ButtonSpec("^", 24, () -> moveSelected(-1),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.group_edit.move_up.tooltip"))));
        left.add(new GuiTokens.ButtonSpec("v", 24, () -> moveSelected(1),
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
        manager.fireDataChanged();
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
        manager.fireDataChanged();
    }

        private void onGradientEndPicked(int picked) {
        group.setGradientEndColor(picked);
        group.setPaintEnabled(false);
        updateColorModeButtons();
        manager.fireDataChanged();
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
        manager.fireDataChanged();
    }

    private void bumpRadius(double delta) {
        group.setDefaultRadius(group.defaultRadius() + delta);
        manager.fireDataChanged();
    }

    private Component skipAheadLabel() {
        return Component.translatable("waypointer.screen.group_edit.skip_ahead",
                Component.translatable(group.skipAheadEnabled() ? "options.on" : "options.off"));
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
        manager.fireDataChanged();
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
        if (moveSelectedHereBtn != null) moveSelectedHereBtn.active = hasSelection;

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
        DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(
                editTarget, selectedIndex, pos.x(), pos.y(), pos.z());
        coordinateEditorIndex = -1;
        syncCoordinateEditors();
        manager.fireDataChanged();
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
        WaypointGroup editTarget = durableEditTarget();
        if (editTarget == null) return;

        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        int flags = defaultNewWaypointFlags(pos.x(), pos.y(), pos.z());
        editTarget.add(DungeonRoomWaypointPlacement.toStoredWaypoint(editTarget, new Waypoint(
                pos.x(), pos.y(), pos.z(),
                "", config.defaultWaypointColor(), flags, 0.0)));
        int newIndex = editTarget.size() - 1;
        new WaypointAddFlow().afterWaypointAdded(editTarget, newIndex,
                config.showWaypointChatShareButtons());
        if (skipAheadBtn != null) skipAheadBtn.setMessage(skipAheadLabel());
        if (editTarget == group) selectWaypoint(newIndex);
        manager.fireDataChanged();
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
        manager.fireDataChanged();
    }

    private void moveSelected(int delta) {
        if (editingIndex >= 0) commitLabelEdit();
        dungeonTypePickerIndex = -1;
        int movedTo = moveWaypointSelection(group, selectedIndex, delta);
        if (movedTo == selectedIndex) return;
        selectWaypoint(movedTo);
        manager.fireDataChanged();
    }

    private void export() {
        ExportScreen.openForGroup(this, config, group);
    }

    private void publish() {
        WaypointGroup editTarget = durableEditTarget();
        if (editTarget == null || editTarget.isEmpty()) return;
        RoutePublishScreen.open(this, config, editTarget);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        renderHeader(g, mouseX, mouseY);
        Layout layout = layout();
        renderSidebarPanel(g, layout.sidebarLeft(), layout.top(),
                layout.sidebarRight(), layout.bottom());
        renderMain(g, layout.mainLeft(), layout.top(),
                layout.mainRight(), layout.bottom(), mouseX, mouseY);
        renderDungeonTypePicker(g, mouseX, mouseY);
        // Screen renders widgets before custom panels, so panel widgets render again here.
        for (SidebarWidget slot : sidebarWidgets) {
            if (!slot.widget().visible) continue;
            if (slot.widget() instanceof GuiTokens.StyledButton button) {
                button.extractOverPanel(g, mouseX, mouseY, partial);
            } else if (slot.widget() instanceof ColorSwatchButton swatch) {
                swatch.extractOverPanel(g, mouseX, mouseY, partial);
            } else if (slot.widget() instanceof EditBox editBox) {
                editBox.extractWidgetRenderState(g, mouseX, mouseY, partial);
            }
        }
        if (labelEditor != null && labelEditor.visible) {
            labelEditor.extractWidgetRenderState(g, mouseX, mouseY, partial);
        }
        if (isHeaderInfoButtonHovered(mouseX, mouseY)) {
            renderRouteInfoTooltip(g, mouseX, mouseY);
        } else {
            String tooltip = dungeonTypeTooltipAt(mouseX, mouseY);
            if (tooltip == null) {
                tooltip = waypointControlTooltipAt(mouseX, mouseY);
            }
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
        String clippedTitle = clippedHeaderTitle(title);
        g.text(font, clippedTitle, PAD_OUTER, PAD_OUTER, TEXT, false);

        int infoX = headerInfoButtonX(clippedTitle);
        boolean infoHovered = isHeaderInfoButtonHovered(mouseX, mouseY);
        if (infoHovered) g.requestCursor(CursorTypes.POINTING_HAND);
        renderHeaderInfoButton(g, infoX, headerInfoButtonY(), infoHovered);

        if (coordinateEditError != null) {
            g.text(font, coordinateEditError, PAD_OUTER, PAD_OUTER + 14,
                    0xFFFF8A8A, false);
        }
    }

    private String headerTitleText() {
        return Component.translatable("waypointer.screen.group_edit.title",
                group.name()).getString();
    }

    private String clippedHeaderTitle(String title) {
        String safeTitle = title == null ? "" : title;
        int publishButtonLeft = width - PAD_OUTER - PUBLISH_BUTTON_W;
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
        int border = hovered ? 0xFFFFFFFF : BORDER;
        int fill = hovered ? 0xFF26343A : 0xFF1A1F24;
        g.fill(x, y, x + HEADER_INFO_BUTTON_SIZE, y + HEADER_INFO_BUTTON_SIZE, border);
        g.fill(x + 1, y + 1, x + HEADER_INFO_BUTTON_SIZE - 1,
                y + HEADER_INFO_BUTTON_SIZE - 1, fill);
        String glyph = "i";
        int glyphX = x + (HEADER_INFO_BUTTON_SIZE - font.width(glyph)) / 2;
        int glyphY = y + 2;
        g.text(font, glyph, glyphX, glyphY, hovered ? ACCENT : TEXT_DIM, false);
    }

    private void renderRouteInfoTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String infoTitle = Component.translatableWithFallback(
                "waypointer.screen.group_edit.info.title", "Route editor controls").getString();
        List<String> labels = routeInfoLabels(isDungeonRoomGroup());
        List<String> descriptions = routeInfoDescriptions(isDungeonRoomGroup());
        int lineCount = Math.min(labels.size(), descriptions.size());
        int pad = 7;
        int lineGap = 3;
        int maxLabelWidth = 0;
        int maxLineWidth = font.width(infoTitle);
        for (int i = 0; i < lineCount; i++) {
            maxLabelWidth = Math.max(maxLabelWidth, font.width(labels.get(i)));
        }
        for (int i = 0; i < lineCount; i++) {
            int lineWidth = maxLabelWidth + GAP
                    + font.width(descriptions.get(i));
            maxLineWidth = Math.max(maxLineWidth, lineWidth);
        }

        int tooltipW = maxLineWidth + pad * 2;
        int tooltipH = pad * 2 + font.lineHeight + 5
                + lineCount * font.lineHeight + Math.max(0, lineCount - 1) * lineGap;
        int x = Math.min(mouseX + 12, Math.max(PAD_OUTER, width - PAD_OUTER - tooltipW));
        int y = Math.min(mouseY + 12,
                Math.max(PAD_OUTER, layout().bottom() - tooltipH));
        x = Math.max(PAD_OUTER, x);
        y = Math.max(PAD_OUTER, y);

        g.fill(x, y, x + tooltipW, y + tooltipH, 0xF0101216);
        g.fill(x, y, x + tooltipW, y + 1, BORDER);
        g.fill(x, y + tooltipH - 1, x + tooltipW, y + tooltipH, BORDER);
        g.fill(x, y, x + 1, y + tooltipH, BORDER);
        g.fill(x + tooltipW - 1, y, x + tooltipW, y + tooltipH, BORDER);

        int textX = x + pad;
        int textY = y + pad;
        g.text(font, infoTitle, textX, textY, ACCENT, false);
        int separatorY = textY + font.lineHeight + 2;
        g.fill(textX, separatorY, x + tooltipW - pad, separatorY + 1, 0x55FFFFFF);

        int rowY = separatorY + 4;
        for (int i = 0; i < lineCount; i++) {
            int labelColor = i < ROUTE_INFO_LABEL_COLORS.length
                    ? ROUTE_INFO_LABEL_COLORS[i]
                    : TEXT;
            g.text(font, labels.get(i), textX, rowY, labelColor, false);
            g.text(font, descriptions.get(i),
                    textX + maxLabelWidth + GAP, rowY, TEXT_DIM, false);
            rowY += font.lineHeight + lineGap;
        }
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

        g.fill(x, y, x + tooltipW, y + tooltipH, 0xF0101216);
        g.fill(x, y, x + tooltipW, y + 1, BORDER);
        g.fill(x, y + tooltipH - 1, x + tooltipW, y + tooltipH, BORDER);
        g.fill(x, y, x + 1, y + tooltipH, BORDER);
        g.fill(x + tooltipW - 1, y, x + tooltipW, y + tooltipH, BORDER);
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
        g.fill(x, y1 + 2, x + 2, y2 - 2, BORDER);
        g.fill(x, thumbY, x + 2, thumbY + thumbHeight, TEXT_MUTED);
    }

    private static final int SIDEBAR_BG = 0xD0101216;
    private static final int ROUTE_LIST_BG = 0xB0101216;

    private void renderMain(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        List<Waypoint> pts = group.waypoints();
        g.fill(x1, y1, x2, y2, ROUTE_LIST_BG);
        if (pts.isEmpty()) {
            g.text(font, Component.translatable("waypointer.screen.group_edit.empty"),
                    x1 + GAP, y1 + 8, TEXT, false);
            g.text(font, Component.translatable("waypointer.screen.group_edit.empty.hint"),
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
        renderWaypointConnectors(g, x1 + ROUTE_LIST_INSET, y1, y2, y, pitch);
        for (int i = 0; i < pts.size(); i++, y += pitch) {
            if (y + ROW_H < y1 || y > y2) continue;
            renderWaypointRow(g, pts.get(i), i, x1 + ROUTE_LIST_INSET, y,
                    x2 - ROUTE_LIST_INSET,
                    mouseX, mouseY, hasSubwaypoints);
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

        int indent = subwaypoint ? 16 : 0;

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
        String label = ordinal + "  " + coordinateLabel(w, numbers);
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
        renderWaypointRowLabelAndName(g, label, w, index, textX, textRightX, y1, textColor);

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
            g.text(font, tag, rightTextX - font.width(tag), y1 + 7, 0xFFFF8A8A, false);
        } else if (w.customRadius() > 0) {
            String r = "r=" + numbers.oneDecimal(w.customRadius());
            g.text(font, r, rightTextX - font.width(r), y1 + 7, TEXT_DIM, false);
        } else if (isCurrent) {
            String tag = currentTagText();
            g.text(font, tag, rightTextX - font.width(tag), y1 + 7, 0xFFFFF080, false);
        }
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

        // Do not draw text behind the active EditBox.
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
            return "r=" + displayNumbers().oneDecimal(waypoint.customRadius());
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
        int border = hovered || open ? 0xFFFFFFFF : BORDER;
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

        g.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), 0xF0101216);
        g.fill(bounds.left(), bounds.top(), bounds.right(), bounds.top() + 1, BORDER);
        g.fill(bounds.left(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), BORDER);
        g.fill(bounds.left(), bounds.top(), bounds.left() + 1, bounds.bottom(), BORDER);
        g.fill(bounds.right() - 1, bounds.top(), bounds.right(), bounds.bottom(), BORDER);
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
            int border = hovered ? 0xFFFFFFFF : BORDER;
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
        int border = hovered ? 0xFFFFFFFF : BORDER;
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
        int border = hovered ? 0xFFFFFFFF : BORDER;
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
        int border = hovered ? 0xFFFFFFFF : BORDER;
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

    private static final int MOUSE_BUTTON_LEFT  = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;
    private static final int MOUSE_BUTTON_MIDDLE = 2;

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int selectedBeforeClick = selectedIndex;
        if (editingIndex >= 0 && !isOverLabelEditor(event.x(), event.y())) {
            commitLabelEdit();
        }

        if (event.button() == MOUSE_BUTTON_LEFT && dungeonTypePickerIndex >= 0) {
            int typeIndex = dungeonTypePickerTypeAt(event.x(), event.y());
            if (typeIndex >= 0) {
                toggleDungeonType(typeIndex);
                playUiClickSound();
                return true;
            }
            DungeonTypePickerBounds picker = dungeonTypePickerBounds();
            int anchorIndex = dungeonTypeButtonIndexAt(event.x(), event.y());
            if (anchorIndex == dungeonTypePickerIndex) {
                dungeonTypePickerIndex = -1;
                playUiClickSound();
                return true;
            }
            if (picker != null && picker.contains(event.x(), event.y())) return true;
            dungeonTypePickerIndex = -1;
            return true;
        }

        if (event.button() == MOUSE_BUTTON_LEFT
                && beginRouteScrollbarDrag(event.x(), event.y())) {
            return true;
        }

        if (event.button() == MOUSE_BUTTON_MIDDLE) {
            int idx = rowIndexAt(event.x(), event.y());
            if (idx >= 0) {
                selectWaypoint(idx);
                if (group.toggleWaypointDisabled(idx)) {
                    manager.fireDataChanged();
                    playUiClickSound();
                }
                DebugEventLog.record("GroupEditScreen", "waypoint", waypointDebugId(idx), idx,
                        selectionDebugLabel(selectedBeforeClick), selectionDebugLabel(selectedIndex),
                        doubleClick, hasShiftDown(), false, "middle-click-row",
                        group.isWaypointDisabled(idx) ? "disable" : "enable");
                return true;
            }
        }

        // Handle row right-clicks before widgets can consume the event.
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
                    DungeonRoomRouteLibrary.setManualCurrentIndex(manager, group, idx);
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
            int dungeonTypeIndex = dungeonTypeButtonIndexAt(event.x(), event.y());
            if (dungeonTypeIndex >= 0) {
                selectWaypoint(dungeonTypeIndex);
                dungeonTypePickerIndex = dungeonTypeIndex;
                playUiClickSound();
                return true;
            }

            int waypointControlAction = waypointControlActionAt(event.x(), event.y());
            if (waypointControlAction != WAYPOINT_CONTROL_ACTION_NONE) {
                int idx = rowIndexAt(event.x(), event.y());
                toggleWaypointControl(idx, waypointControlAction);
                selectWaypoint(idx);
                playUiClickSound();
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
                playUiClickSound();
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

        if (event.button() == MOUSE_BUTTON_LEFT) {
            int swatchIdx = swatchIndexAt(event.x(), event.y());
            if (swatchIdx >= 0) {
                playUiClickSound();
                // MouseButtonInfo does not expose modifier bits on these mappings.
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
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingRouteScrollbar && event.button() == MOUSE_BUTTON_LEFT) {
            draggingRouteScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
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
                || InputConstants.isKeyDown(win, 344 /* GLFW_KEY_RIGHT_SHIFT */);
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
                + (group.isSubwaypoint(idx) ? 16 : 0);
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
        manager.fireDataChanged();
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
        manager.fireDataChanged();
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
        manager.fireDataChanged();
    }

    public void toggleSelectedSubwaypointStyle(int action) {
        toggleSubwaypointStyle(selectedIndex, action);
    }

        private void openWaypointColorPicker(int idx) {
        if (idx < 0 || idx >= group.size()) return;
        waypointColorPickerIndex = idx;
        Waypoint w = group.get(idx);
        ColorPickerScreen.open(this, Component.translatable(
                        "waypointer.screen.group_edit.picker.waypoint",
                        displayNumbers().integer(idx + 1)),
                w.color(), this::onWaypointColorPicked);
    }

    private void onWaypointColorPicked(int picked) {
        int idx = waypointColorPickerIndex;
        waypointColorPickerIndex = -1;
        if (idx < 0 || idx >= group.size()) return;
        group.setPaintEnabled(false);
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
        manager.fireDataChanged();
        rebuildWidgets();
    }

    private void unlockWaypointColor(int idx) {
        Waypoint w = group.get(idx);
        int cleared = w.flags() & ~Waypoint.FLAG_LOCKED_COLOR;
        group.set(idx, w.withFlags(cleared));
        if (group.gradientMode() == WaypointGroup.GradientMode.AUTO) {
            GradientColorizer.apply(group);
        }
        manager.fireDataChanged();
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

    private void positionLabelEditor(int index) {
        Layout layout = layout();

        int pitch = waypointRowPitch();
        int rowY = layout.top() + ROUTE_LIST_INSET - scrollOffset + index * pitch;

        int rowX1 = layout.mainLeft() + ROUTE_LIST_INSET;
        int rowX2 = layout.mainRight() - ROUTE_LIST_INSET;
        int sx = rowX1 + GAP + 2 + (group.isSubwaypoint(index) ? 16 : 0);
        int labelStart = sx + 20;
        Waypoint w = group.get(index);
        LocalizedNumberFormatter numbers = displayNumbers();
        String prefix = numbers.waypointOrdinal(group.displayIndexLabel(index))
                + "  " + coordinateLabel(w, numbers);
        boolean subwaypoint = group.isSubwaypoint(index);
        boolean isCurrent = !subwaypoint && index == group.currentIndex();
        int textRightX = waypointRowTextRightEdge(rowX2, subwaypoint,
                isDungeonRoomGroup(), shouldShowWaypointControls(index, selectedIndex),
                waypointRightMetadataWidth(w, subwaypoint, isCurrent, ""));
        int editorX = Math.min(labelStart + font.width(prefix) + GAP, textRightX);
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
