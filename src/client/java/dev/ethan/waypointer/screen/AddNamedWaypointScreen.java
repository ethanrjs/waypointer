package dev.ethan.waypointer.screen;

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
    private static final int PANEL_H = 118;
    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_KP_ENTER = 335;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup targetGroup;
    /** When true, waypoint coords come from {@link #fixedX}/{@link #fixedY}/{@link #fixedZ} (keybind press). */
    private final boolean useFixedPosition;
    private final int fixedX;
    private final int fixedY;
    private final int fixedZ;

    private EditBox nameBox;

    public AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                  WaypointerConfig config, WaypointGroup targetGroup) {
        this(parent, manager, config, targetGroup, false, 0, 0, 0);
    }

    private AddNamedWaypointScreen(Screen parent, ActiveGroupManager manager,
                                   WaypointerConfig config, WaypointGroup targetGroup,
                                   boolean useFixedPosition, int fixedX, int fixedY, int fixedZ) {
        super(Component.literal("Create Named Waypoint"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.targetGroup = targetGroup;
        this.useFixedPosition = useFixedPosition;
        this.fixedX = fixedX;
        this.fixedY = fixedY;
        this.fixedZ = fixedZ;
    }

    public static void open(Screen parent, ActiveGroupManager manager,
                            WaypointerConfig config, WaypointGroup targetGroup) {
        Minecraft.getInstance().setScreen(
                new AddNamedWaypointScreen(parent, manager, config, targetGroup));
    }

    public static void openAt(Screen parent, ActiveGroupManager manager,
                              WaypointerConfig config, WaypointGroup targetGroup,
                              int x, int y, int z) {
        Minecraft.getInstance().setScreen(
                new AddNamedWaypointScreen(parent, manager, config, targetGroup, true, x, y, z));
    }

    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        int inner = panelX + PAD_OUTER;
        int fieldW = PANEL_W - PAD_OUTER * 2;

        nameBox = new EditBox(font, inner, panelY + 34, fieldW, BTN_H,
                Component.literal("Waypoint name"));
        nameBox.setMaxLength(64);
        addRenderableWidget(nameBox);
        setFocused(nameBox);
        nameBox.setFocused(true);

        int footerY = panelY + PANEL_H - BTN_H - PAD_OUTER / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY, 70, BTN_H)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> createAndClose())
                .bounds(panelX + PANEL_W - PAD_OUTER - 70, footerY, 70, BTN_H)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, SURFACE);
        g.drawString(font, getTitle(), panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);

        super.render(g, mouseX, mouseY, partial);
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

    private void createAndClose() {
        int x;
        int y;
        int z;
        if (useFixedPosition) {
            x = fixedX;
            y = fixedY;
            z = fixedZ;
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
        WaypointGroup target = targetGroup == null ? manager.getOrCreateActiveGroup() : targetGroup;

        target.add(new Waypoint(x, y, z, nameBox.getValue().trim(),
                Waypoint.DEFAULT_COLOR, 0, 0.0));
        new WaypointAddFlow().afterWaypointAdded(target, target.size() - 1);
        manager.fireDataChanged();

        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
