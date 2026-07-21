package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.*;

/**
 * One-field modal for creating a waypoint with a name.
 *
 * <p>The normal create action stays instant and unnamed. This prompt is the
 * explicit "I want to name it now" path, so it does only that: focus a single
 * text box, commit on Enter, and close on Cancel or Confirm.
 */
public final class AddNamedWaypointScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 136;
    private static final int OPTIONS_PANEL_H = 126;
    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_KP_ENTER = 335;
    private static final int MAX_NAME_LENGTH = 64;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup targetGroup;
    /** When true, waypoint coords come from {@link #fixedX}/{@link #fixedY}/{@link #fixedZ} (keybind press). */
    private final boolean useFixedPosition;
    private final int fixedX;
    private final int fixedY;
    private final int fixedZ;
    private final int fixedFlags;
    private final boolean showSubtypeOptions;
    private final int fixedPreciseX;
    private final int fixedPreciseY;
    private final int fixedPreciseZ;
    private final boolean subwaypointAvailable;

    private EditBox nameBox;
    private Button confirmButton;
    private GuiTokens.StyledCheckbox subwaypointCheckbox;
    private GuiTokens.StyledCheckbox smallCheckbox;
    private boolean nameErrorVisible;
    private String enteredName = "";
    private boolean subwaypointSelected;
    private boolean smallSelected;
    private WaypointGroup previewGroup;

    public AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                  WaypointerConfig config, WaypointGroup targetGroup) {
        this(parent, manager, config, targetGroup, false, 0, 0, 0, 0);
    }

    private AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                   WaypointerConfig config, WaypointGroup targetGroup,
                                   boolean useFixedPosition, int fixedX, int fixedY, int fixedZ,
                                   int fixedFlags) {
        this(parent, manager, config, targetGroup, useFixedPosition,
                fixedX, fixedY, fixedZ, fixedFlags, false,
                fixedX * Waypoint.PRECISE_SCALE,
                fixedY * Waypoint.PRECISE_SCALE,
                fixedZ * Waypoint.PRECISE_SCALE);
    }

    private AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                   WaypointerConfig config, WaypointGroup targetGroup,
                                   boolean useFixedPosition, int fixedX, int fixedY, int fixedZ,
                                   int fixedFlags, boolean showSubtypeOptions,
                                   int fixedPreciseX, int fixedPreciseY, int fixedPreciseZ) {
        super(Component.literal(showSubtypeOptions ? "Create Waypoint" : "Create Named Waypoint"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.targetGroup = targetGroup;
        this.useFixedPosition = useFixedPosition;
        this.fixedX = fixedX;
        this.fixedY = fixedY;
        this.fixedZ = fixedZ;
        this.fixedFlags = fixedFlags;
        this.showSubtypeOptions = showSubtypeOptions;
        this.fixedPreciseX = fixedPreciseX;
        this.fixedPreciseY = fixedPreciseY;
        this.fixedPreciseZ = fixedPreciseZ;
        this.subwaypointAvailable = canCreateSubwaypoint(targetGroup);
    }

    public static void open(Screen parent, ActiveGroupManager manager,
                            WaypointerConfig config, WaypointGroup targetGroup) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new AddNamedWaypointScreen(parent, manager, config, targetGroup));
    }

    public static void openAt(Screen parent, ActiveGroupManager manager,
                              WaypointerConfig config, WaypointGroup targetGroup,
                              int x, int y, int z) {
        openAt(parent, manager, config, targetGroup, x, y, z, 0);
    }

    public static void openAt(Screen parent, ActiveGroupManager manager,
                              WaypointerConfig config, WaypointGroup targetGroup,
                              int x, int y, int z, int flags) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new AddNamedWaypointScreen(parent, manager, config, targetGroup, true, x, y, z, flags));
    }

    public static void openWhereLooking(Screen parent, ActiveGroupManager manager,
                                        WaypointerConfig config, WaypointGroup targetGroup,
                                        int x, int y, int z,
                                        int preciseX, int preciseY, int preciseZ,
                                        int flags) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new AddNamedWaypointScreen(parent, manager, config, targetGroup,
                        true, x, y, z, flags, true, preciseX, preciseY, preciseZ));
    }

    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelH = panelHeight();
        int panelY = (height - panelH) / 2;
        int inner = panelX + PAD_OUTER;
        int fieldW = PANEL_W - PAD_OUTER * 2;

        nameBox = new EditBox(font, inner, panelY + 34, fieldW, BTN_H,
                Component.literal("Waypoint name"));
        nameBox.setMaxLength(MAX_NAME_LENGTH);
        nameBox.setValue(enteredName);
        nameBox.setResponder(this::onNameEdited);
        addRenderableWidget(nameBox);
        setFocused(nameBox);
        nameBox.setFocused(true);

        if (showSubtypeOptions) {
            int optionsY = panelY + 66;
            int subwaypointBoxX = inner + font.width("Subwaypoint") + GAP_TIGHT;
            subwaypointCheckbox = styledCheckbox(subwaypointBoxX, optionsY, BTN_H,
                    Component.literal("Subwaypoint"), subwaypointSelected,
                    this::onSubwaypointChanged, null);
            addRenderableWidget(subwaypointCheckbox);

            int smallLabelX = subwaypointBoxX + BTN_H + GAP_SECTION;
            int smallBoxX = smallLabelX + font.width("Small") + GAP_TIGHT;
            smallCheckbox = styledCheckbox(smallBoxX, optionsY, BTN_H,
                    Component.literal("Small"), smallSelected,
                    this::onSmallChanged, null);
            addRenderableWidget(smallCheckbox);
            updateSubtypeControls();
        }

        int footerY = panelY + panelH - BTN_H - PAD_OUTER / 2;
        addRenderableWidget(styledButton(
                panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY, 70, BTN_H,
                Component.literal("Cancel"), this::onCancelButtonClicked, null));
        confirmButton = styledButton(
                panelX + PANEL_W - PAD_OUTER - 70, footerY, 70, BTN_H,
                Component.literal("Confirm"), this::onConfirmButtonClicked, null);
        addRenderableWidget(confirmButton);
        updateConfirmState();
        updatePreview();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);

        int panelX = (width - PANEL_W) / 2;
        int panelH = panelHeight();
        int panelY = (height - panelH) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, SURFACE);
        g.text(font, getTitle(), panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);

        if (showSubtypeOptions) {
            int optionsY = panelY + 66;
            int labelY = opticalTextY(optionsY, BTN_H);
            int inner = panelX + PAD_OUTER;
            int subwaypointBoxX = inner + font.width("Subwaypoint") + GAP_TIGHT;
            int smallLabelX = subwaypointBoxX + BTN_H + GAP_SECTION;
            g.text(font, "Subwaypoint", inner, labelY,
                    subwaypointAvailable ? TEXT : TEXT_MUTED, false);
            g.text(font, "Small", smallLabelX, labelY,
                    subwaypointSelected ? TEXT : TEXT_MUTED, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partial);
        if (nameErrorVisible) {
            int errorY = showSubtypeOptions ? panelY + 56 : panelY + 60;
            g.text(font, "Name required", panelX + PAD_OUTER, errorY,
                    0xFFFF8A8A, false);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            createAndClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void onCancelButtonClicked(Button button) {
        onClose();
    }

    private void onConfirmButtonClicked(Button button) {
        createAndClose();
    }

    private void createAndClose() {
        String draft = nameBox == null ? enteredName : nameBox.getValue();
        String name = sanitizeWaypointName(draft);
        if (name == null) {
            nameErrorVisible = true;
            updateConfirmState();
            return;
        }

        int x;
        int y;
        int z;
        int flags = 0;
        CreationOptions options = creationOptions(
                subwaypointAvailable, subwaypointSelected, smallSelected);
        if (useFixedPosition) {
            x = fixedX;
            y = fixedY;
            z = fixedZ;
            flags = creationFlags(fixedFlags, options);
        } else {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                onClose();
                return;
            }
            PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                    player.getX(), player.getY(), player.getZ(), config);
            x = pos.x();
            y = pos.y();
            z = pos.z();
        }
        WaypointGroup target = targetGroup == null
                ? manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled())
                : targetGroup;

        // Stored dungeon-room routes keep room-local coordinates.
        Waypoint waypoint;
        if (useFixedPosition && options.small()) {
            waypoint = new Waypoint(
                    Math.floorDiv(fixedPreciseX, Waypoint.PRECISE_SCALE),
                    Math.floorDiv(fixedPreciseY, Waypoint.PRECISE_SCALE),
                    Math.floorDiv(fixedPreciseZ, Waypoint.PRECISE_SCALE),
                    name, config.defaultWaypointColor(), flags, 0.0,
                    Waypoint.TEMP_NONE, 0L,
                    fixedPreciseX, fixedPreciseY, fixedPreciseZ);
        } else {
            waypoint = new Waypoint(x, y, z, name,
                    config.defaultWaypointColor(), flags, 0.0);
        }
        target.add(DungeonRoomWaypointPlacement.toStoredWaypoint(target, waypoint));
        new WaypointAddFlow().afterWaypointAdded(target, target.size() - 1,
                config.showWaypointChatShareButtons());
        manager.fireDataChanged();

        onClose();
    }

    private void onNameEdited(String value) {
        enteredName = value == null ? "" : value;
        updateConfirmState();
        updatePreview();
    }

    private void onSubwaypointChanged(boolean selected) {
        subwaypointSelected = subwaypointAvailable && selected;
        updateSubtypeControls();
        updatePreview();
    }

    private void onSmallChanged(boolean selected) {
        smallSelected = subwaypointAvailable && subwaypointSelected && selected;
        updatePreview();
    }

    private void updateSubtypeControls() {
        if (subwaypointCheckbox != null) subwaypointCheckbox.active = subwaypointAvailable;
        if (smallCheckbox != null) smallCheckbox.active = subwaypointAvailable && subwaypointSelected;
    }

    private int panelHeight() {
        return showSubtypeOptions ? OPTIONS_PANEL_H : PANEL_H;
    }

    static CreationOptions creationOptions(boolean subwaypoint, boolean small) {
        return new CreationOptions(subwaypoint, subwaypoint && small);
    }

    static CreationOptions creationOptions(boolean parentAvailable,
                                           boolean subwaypoint, boolean small) {
        return creationOptions(parentAvailable && subwaypoint, small);
    }

    static boolean canCreateSubwaypoint(WaypointGroup group) {
        return group != null && group.mainWaypointCount() > 0;
    }

    static int creationFlags(int baseFlags, CreationOptions options) {
        if (options == null || !options.subwaypoint()) return baseFlags;
        int flags = baseFlags | Waypoint.FLAG_SUBWAYPOINT;
        return options.small() ? flags | Waypoint.FLAG_SMALL_SUBWAYPOINT : flags;
    }

    static String sanitizeWaypointName(String rawName) {
        if (rawName == null) return null;
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_NAME_LENGTH
                ? trimmed.substring(0, MAX_NAME_LENGTH)
                : trimmed;
    }

    private void updateConfirmState() {
        String draft = nameBox == null ? enteredName : nameBox.getValue();
        boolean hasName = sanitizeWaypointName(draft) != null;
        if (hasName) nameErrorVisible = false;
        if (confirmButton != null) confirmButton.active = hasName;
    }

    private void updatePreview() {
        if (!showSubtypeOptions || manager == null || targetGroup == null) return;

        if (previewGroup == null) {
            previewGroup = WaypointGroup.create("Waypoint preview", targetGroup.zoneId());
            previewGroup.setTemp(true);
            previewGroup.setRuntimeOnly(true);
            previewGroup.setLoadMode(WaypointGroup.LoadMode.STATIC);
            previewGroup.setGradientStartColor(targetGroup.gradientStartColor());
            previewGroup.setGradientEndColor(targetGroup.gradientEndColor());
            previewGroup.setStaticColor(targetGroup.staticColor());
            previewGroup.setGradientMode(targetGroup.gradientMode());
            previewGroup.setPaint(targetGroup.paint());
            previewGroup.setPaintEnabled(targetGroup.paintEnabled());
            manager.setWaypointPreview(previewGroup);
        }

        CreationOptions options = creationOptions(
                subwaypointAvailable, subwaypointSelected, smallSelected);
        int flags = creationFlags(fixedFlags, options);
        String previewName = enteredName == null ? "" : enteredName.trim();
        Waypoint preview = options.small()
                ? new Waypoint(
                        Math.floorDiv(fixedPreciseX, Waypoint.PRECISE_SCALE),
                        Math.floorDiv(fixedPreciseY, Waypoint.PRECISE_SCALE),
                        Math.floorDiv(fixedPreciseZ, Waypoint.PRECISE_SCALE),
                        previewName, config.defaultWaypointColor(), flags, 0.0,
                        Waypoint.TEMP_NONE, 0L,
                        fixedPreciseX, fixedPreciseY, fixedPreciseZ)
                : new Waypoint(fixedX, fixedY, fixedZ, previewName,
                        config.defaultWaypointColor(), flags, 0.0);

        List<Waypoint> previewWaypoints = new ArrayList<>(targetGroup.waypoints());
        previewWaypoints.add(preview);
        previewGroup.replaceWaypoints(previewWaypoints);
        previewGroup.focusOnlyVisibleIndex(previewWaypoints.size() - 1);
    }

    private void clearPreview() {
        if (previewGroup == null || manager == null) return;
        manager.clearWaypointPreview(previewGroup);
        previewGroup = null;
    }

    @Override
    public void onClose() {
        clearPreview();
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        clearPreview();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    record CreationOptions(boolean subwaypoint, boolean small) {}
}
