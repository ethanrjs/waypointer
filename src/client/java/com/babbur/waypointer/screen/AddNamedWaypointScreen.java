package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.*;

/**
 * One-field modal for creating a waypoint with an optional name.
 */
public final class AddNamedWaypointScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 136;
    private static final int OPTIONS_PANEL_H = 158;
    private static final int DUNGEON_OPTIONS_PANEL_H = 174;
    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_KP_ENTER = 335;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int DUNGEON_TYPE_BUTTON_SIZE = 20;
    private static final int DUNGEON_TYPE_ROW_WIDTH = PANEL_W - PAD_OUTER * 2;
    private static final Identifier DUNGEON_TYPE_ICONS = Identifier.fromNamespaceAndPath(
            Waypointer.MOD_ID, "textures/gui/dungeon_waypoint_types.png");
    private static String rememberedDungeonRoomKey;
    private static DungeonWaypointType rememberedDungeonType;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup targetGroup;
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
    private final boolean showDungeonTypes;
    private final String dungeonRoomKey;

    private EditBox nameBox;
    private GuiTokens.StyledCheckbox subwaypointCheckbox;
    private GuiTokens.StyledCheckbox smallCheckbox;
    private String enteredName = "";
    private boolean subwaypointSelected;
    private boolean smallSelected;
    private DungeonWaypointType selectedDungeonType;
    private boolean dungeonTypeIconsAvailable;
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
        super(Component.translatable(showSubtypeOptions
                ? "waypointer.screen.add_named.title.waypoint"
                : "waypointer.screen.add_named.title.named"));
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
        this.showDungeonTypes = showSubtypeOptions
                && targetGroup != null
                && targetGroup.routeKind() == WaypointGroup.RouteKind.DUNGEON;
        this.dungeonRoomKey = showDungeonTypes
                ? dungeonRoomSelectionKey(targetGroup, currentDungeonRoomIdentity())
                : null;
        this.selectedDungeonType = showDungeonTypes
                ? selectionForRoom(dungeonRoomKey, DungeonWaypointType.firstType(fixedFlags))
                : null;
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
        dungeonTypeIconsAvailable = minecraft != null
                && minecraft.getResourceManager().getResource(DUNGEON_TYPE_ICONS).isPresent();

        nameBox = new EditBox(font, inner, panelY + 34, fieldW, BTN_H,
                Component.translatable("waypointer.screen.add_named.name"));
        nameBox.setMaxLength(MAX_NAME_LENGTH);
        nameBox.setValue(enteredName);
        nameBox.setResponder(this::onNameEdited);
        addRenderableWidget(nameBox);

        if (showSubtypeOptions) {
            int optionsY = panelY + 66;
            Component subwaypointLabel = Component.translatable(
                    "waypointer.screen.add_named.subwaypoint");
            int subwaypointBoxX = inner + font.width(subwaypointLabel) + GAP_TIGHT;
            subwaypointCheckbox = styledCheckbox(subwaypointBoxX, optionsY, BTN_H,
                    subwaypointLabel, subwaypointSelected,
                    this::onSubwaypointChanged, null);
            addRenderableWidget(subwaypointCheckbox);

            int smallLabelX = subwaypointBoxX + BTN_H + GAP_SECTION;
            Component smallLabel = Component.translatable("waypointer.screen.add_named.small");
            int smallBoxX = smallLabelX + font.width(smallLabel) + GAP_TIGHT;
            smallCheckbox = styledCheckbox(smallBoxX, optionsY, BTN_H,
                    smallLabel, smallSelected,
                    this::onSmallChanged, null);
            addRenderableWidget(smallCheckbox);
            updateSubtypeControls();
        }

        int footerY = panelY + panelH - BTN_H - PAD_OUTER / 2;
        addRenderableWidget(styledButton(
                panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY, 70, BTN_H,
                Component.translatable("gui.cancel"), this::onCancelButtonClicked, null));
        Button confirmButton = styledButton(
                panelX + PANEL_W - PAD_OUTER - 70, footerY, 70, BTN_H,
                Component.translatableWithFallback(
                        "waypointer.screen.add_named.confirm", "Confirm"),
                this::onConfirmButtonClicked, null);
        addRenderableWidget(confirmButton);
        updatePreview();
    }

    @Override
    protected void setInitialFocus() {
        if (nameBox != null) {
            setInitialFocus(nameBox);
            return;
        }
        super.setInitialFocus();
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
            Component subwaypointLabel = Component.translatable(
                    "waypointer.screen.add_named.subwaypoint");
            int subwaypointBoxX = inner + font.width(subwaypointLabel) + GAP_TIGHT;
            int smallLabelX = subwaypointBoxX + BTN_H + GAP_SECTION;
            g.text(font, subwaypointLabel, inner, labelY,
                    subwaypointAvailable ? TEXT : TEXT_MUTED, false);
            g.text(font, Component.translatable("waypointer.screen.add_named.small"),
                    smallLabelX, labelY,
                    subwaypointSelected ? TEXT : TEXT_MUTED, false);
        }
        if (showDungeonTypes) {
            Component selectedType = selectedDungeonType == null
                    ? Component.translatable("waypointer.screen.add_named.none")
                    : Component.literal(selectedDungeonType.label());
            g.text(font, Component.translatable("waypointer.screen.add_named.waypoint_type", selectedType),
                    panelX + PAD_OUTER, panelY + 91, TEXT_DIM, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partial);
        if (showDungeonTypes) {
            renderDungeonTypes(g, mouseX, mouseY);
            int hoveredType = dungeonTypeAt(mouseX, mouseY);
            if (hoveredType >= 0) {
                renderDungeonTypeTooltip(g,
                        DungeonWaypointType.values()[hoveredType].label(), mouseX, mouseY);
            }
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
            flags = creationFlags(fixedFlags, options, selectedDungeonType);
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
        updatePreview();
    }

    private void onSubwaypointChanged(boolean selected) {
        subwaypointSelected = subwaypointAvailable && selected;
        if (!subwaypointSelected && smallSelected) {
            smallSelected = false;
            rebuildWidgets();
            return;
        }
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
        if (showDungeonTypes) return DUNGEON_OPTIONS_PANEL_H;
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

    static int creationFlags(int baseFlags, CreationOptions options,
                             DungeonWaypointType dungeonType) {
        int flags = creationFlags(baseFlags, options) & ~Waypoint.DUNGEON_METADATA_FLAGS;
        return dungeonType == null ? flags : dungeonType.applyExclusive(flags);
    }

    static DungeonWaypointType toggleDungeonType(DungeonWaypointType selected,
                                                   DungeonWaypointType clicked) {
        return selected == clicked ? null : clicked;
    }

    static String dungeonRoomSelectionKey(WaypointGroup group, String physicalRoomIdentity) {
        if (group == null || group.routeKind() != WaypointGroup.RouteKind.DUNGEON) return null;
        if (physicalRoomIdentity != null && !physicalRoomIdentity.isBlank()) {
            return physicalRoomIdentity;
        }
        return group.zoneId();
    }

    static synchronized DungeonWaypointType selectionForRoom(
            String roomKey, DungeonWaypointType suggestedType) {
        if (!java.util.Objects.equals(rememberedDungeonRoomKey, roomKey)) {
            rememberedDungeonRoomKey = roomKey;
            rememberedDungeonType = suggestedType;
        }
        return rememberedDungeonType;
    }

    static synchronized void rememberSelection(String roomKey, DungeonWaypointType type) {
        rememberedDungeonRoomKey = roomKey;
        rememberedDungeonType = type;
    }

    static synchronized void resetRememberedSelectionForTests() {
        rememberedDungeonRoomKey = null;
        rememberedDungeonType = null;
    }

    static String sanitizeWaypointName(String rawName) {
        String trimmed = rawName == null ? "" : rawName.trim();
        return trimmed.length() > MAX_NAME_LENGTH
                ? trimmed.substring(0, MAX_NAME_LENGTH)
                : trimmed;
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
        int flags = creationFlags(fixedFlags, options, selectedDungeonType);
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

    private static String currentDungeonRoomIdentity() {
        var tracker = WaypointerClient.dungeonTracker();
        var room = tracker == null ? null : tracker.currentRoom();
        return room == null ? null : room.identityKey();
    }

    private int dungeonTypeRowX() {
        return (width - DUNGEON_TYPE_ROW_WIDTH) / 2;
    }

    private int dungeonTypeRowY() {
        return (height - panelHeight()) / 2 + 104;
    }

    static int dungeonTypeCellX(int rowX, int typeIndex) {
        int gapCount = DungeonWaypointType.values().length - 1;
        if (gapCount <= 0) return rowX;
        int usableSpan = DUNGEON_TYPE_ROW_WIDTH - DUNGEON_TYPE_BUTTON_SIZE;
        return rowX + (typeIndex * usableSpan + gapCount / 2) / gapCount;
    }

    private int dungeonTypeAt(double mouseX, double mouseY) {
        return dungeonTypeAt(showDungeonTypes, dungeonTypeRowX(), dungeonTypeRowY(),
                mouseX, mouseY);
    }

    static int dungeonTypeAt(boolean showDungeonTypes, int rowX, int rowY,
                             double mouseX, double mouseY) {
        if (!showDungeonTypes) return -1;
        for (int i = 0; i < DungeonWaypointType.values().length; i++) {
            int x = dungeonTypeCellX(rowX, i);
            if (mouseX >= x && mouseX < x + DUNGEON_TYPE_BUTTON_SIZE
                    && mouseY >= rowY && mouseY < rowY + DUNGEON_TYPE_BUTTON_SIZE) {
                return i;
            }
        }
        return -1;
    }

    private void renderDungeonTypes(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int rowX = dungeonTypeRowX();
        int rowY = dungeonTypeRowY();
        DungeonWaypointType[] types = DungeonWaypointType.values();
        for (int i = 0; i < types.length; i++) {
            DungeonWaypointType type = types[i];
            int x = dungeonTypeCellX(rowX, i);
            boolean active = type == selectedDungeonType;
            boolean hovered = dungeonTypeAt(mouseX, mouseY) == i;
            int border = hovered ? 0xFFFFFFFF : BORDER;
            int fill = active ? 0xFF2D6B3E : hovered ? 0xFF303844 : 0xFF20242A;
            g.fill(x, rowY, x + DUNGEON_TYPE_BUTTON_SIZE,
                    rowY + DUNGEON_TYPE_BUTTON_SIZE, border);
            g.fill(x + 1, rowY + 1, x + DUNGEON_TYPE_BUTTON_SIZE - 1,
                    rowY + DUNGEON_TYPE_BUTTON_SIZE - 1, fill);
            renderDungeonTypeIcon(g, x, rowY, type, active ? TEXT : TEXT_DIM);
        }
    }

    private void renderDungeonTypeIcon(GuiGraphicsExtractor g, int x, int y,
                                       DungeonWaypointType type, int color) {
        int inset = (DUNGEON_TYPE_BUTTON_SIZE - DungeonWaypointType.ICON_SIZE) / 2;
        if (dungeonTypeIconsAvailable) {
            g.blit(RenderPipelines.GUI_TEXTURED, DUNGEON_TYPE_ICONS,
                    x + inset, y + inset,
                    type.iconIndex() * (float) DungeonWaypointType.ICON_SIZE, 0f,
                    DungeonWaypointType.ICON_SIZE, DungeonWaypointType.ICON_SIZE,
                    DungeonWaypointType.ICON_ATLAS_WIDTH, DungeonWaypointType.ICON_SIZE);
            return;
        }
        String glyph = type.fallbackGlyph();
        g.text(font, glyph,
                x + (DUNGEON_TYPE_BUTTON_SIZE - font.width(glyph)) / 2,
                y + (DUNGEON_TYPE_BUTTON_SIZE - font.lineHeight) / 2,
                color, false);
    }

    private void renderDungeonTypeTooltip(GuiGraphicsExtractor g, String text,
                                          int mouseX, int mouseY) {
        int pad = 4;
        int tooltipW = font.width(text) + pad * 2;
        int tooltipH = font.lineHeight + pad * 2;
        int x = Math.max(PAD_OUTER,
                Math.min(mouseX + 12, width - PAD_OUTER - tooltipW));
        int y = Math.max(PAD_OUTER,
                Math.min(mouseY + 12, height - PAD_OUTER - tooltipH));
        g.fill(x, y, x + tooltipW, y + tooltipH, 0xF0101216);
        g.fill(x, y, x + tooltipW, y + 1, BORDER);
        g.fill(x, y + tooltipH - 1, x + tooltipW, y + tooltipH, BORDER);
        g.fill(x, y, x + 1, y + tooltipH, BORDER);
        g.fill(x + tooltipW - 1, y, x + tooltipW, y + tooltipH, BORDER);
        g.text(font, text, x + pad, y + pad, TEXT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int typeIndex = dungeonTypeAt(event.x(), event.y());
            if (typeIndex >= 0) {
                selectedDungeonType = toggleDungeonType(
                        selectedDungeonType, DungeonWaypointType.values()[typeIndex]);
                rememberSelection(dungeonRoomKey, selectedDungeonType);
                updatePreview();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
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
