package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.dungeon.DungeonRoomWaypointPlacement;
import dev.ethan.waypointer.input.WaypointAddFlow;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import static dev.ethan.waypointer.screen.GuiTokens.*;

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

    private EditBox nameBox;
    private Button confirmButton;
    private boolean nameErrorVisible;
    private String enteredName = "";

    public AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                  WaypointerConfig config, WaypointGroup targetGroup) {
        this(parent, manager, config, targetGroup, false, 0, 0, 0, 0);
    }

    private AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                   WaypointerConfig config, WaypointGroup targetGroup,
                                   boolean useFixedPosition, int fixedX, int fixedY, int fixedZ,
                                   int fixedFlags) {
        super(Component.literal("Create Named Waypoint"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.targetGroup = targetGroup;
        this.useFixedPosition = useFixedPosition;
        this.fixedX = fixedX;
        this.fixedY = fixedY;
        this.fixedZ = fixedZ;
        this.fixedFlags = fixedFlags;
    }

    public static void open(Screen parent, ActiveGroupManager manager,
                            WaypointerConfig config, WaypointGroup targetGroup) {
        Minecraft.getInstance().setScreen(
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
        Minecraft.getInstance().setScreen(
                new AddNamedWaypointScreen(parent, manager, config, targetGroup, true, x, y, z, flags));
    }

    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
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

        int footerY = panelY + PANEL_H - BTN_H - PAD_OUTER / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), this::onCancelButtonClicked)
                .bounds(panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY, 70, BTN_H)
                .build());
        confirmButton = Button.builder(Component.literal("Confirm"), this::onConfirmButtonClicked)
                .bounds(panelX + PANEL_W - PAD_OUTER - 70, footerY, 70, BTN_H)
                .build();
        addRenderableWidget(confirmButton);
        updateConfirmState();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, SURFACE);
        g.text(font, getTitle(), panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);

        super.extractRenderState(g, mouseX, mouseY, partial);
        if (nameErrorVisible) {
            g.text(font, "Name required", panelX + PAD_OUTER, panelY + 60,
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
        if (useFixedPosition) {
            x = fixedX;
            y = fixedY;
            z = fixedZ;
            flags = fixedFlags;
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
        target.add(DungeonRoomWaypointPlacement.toStoredWaypoint(target,
                new Waypoint(x, y, z, name, config.defaultWaypointColor(), flags, 0.0)));
        new WaypointAddFlow().afterWaypointAdded(target, target.size() - 1);
        manager.fireDataChanged();

        onClose();
    }

    private void onNameEdited(String value) {
        enteredName = value == null ? "" : value;
        updateConfirmState();
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

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
