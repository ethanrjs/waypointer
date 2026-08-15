package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import static com.babbur.waypointer.screen.GuiTokens.*;

public final class AddTempScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 168;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    private int mode;
    private int durationSec;
    private Button modeBtn;
    private EditBox durationBox;
    public AddTempScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config) {
        super(Component.translatable("waypointer.screen.add_temp.title"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.mode = Waypoint.normalizeTempMode(config.tempDefaultMode());
        this.durationSec = Math.max(1, config.tempDefaultDurationSec());
    }

    public static void open(Screen parent, ActiveGroupManager manager, WaypointerConfig config) {
        MinecraftCompat.setScreen(Minecraft.getInstance(), new AddTempScreen(parent, manager, config));
    }
    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        int inner = panelX + PAD_OUTER;
        int fieldW = PANEL_W - PAD_OUTER * 2;
        int y = panelY + 32;

        modeBtn = styledButton(inner, y, fieldW, BTN_H, modeLabel(),
                this::onModeButtonPressed,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.add_temp.mode.tooltip")));
        addRenderableWidget(modeBtn);
        y += BTN_H + GAP;

        durationBox = new EditBox(font, inner, y, fieldW, BTN_H,
                Component.translatable("waypointer.screen.add_temp.duration"));
        durationBox.setMaxLength(5);
        durationBox.setValue(String.valueOf(durationSec));
        durationBox.setResponder(this::onDurationTextChanged);
        durationBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.add_temp.duration.tooltip")));
        addRenderableWidget(durationBox);
        updateDurationVisibility();
        y += BTN_H + GAP;

        int footerY = panelY + PANEL_H - BTN_H - PAD_OUTER;
        addRenderableWidget(styledButton(panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY,
                70, BTN_H, Component.translatable("gui.cancel"),
                this::onCancelButtonPressed, null));
        addRenderableWidget(styledButton(panelX + PANEL_W - PAD_OUTER - 70, footerY,
                70, BTN_H, Component.translatable("gui.add"),
                this::onAddButtonPressed, null));
    }
    private void onModeButtonPressed(Button button) {
        cycleMode();
    }
    private void onDurationTextChanged(String rawValue) {
        durationSec = durationSecondsAfterEdit(durationSec, rawValue);
    }
    private void onCancelButtonPressed(Button button) {
        onClose();
    }
    private void onAddButtonPressed(Button button) {
        createAndClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, SURFACE);
        g.text(font, getTitle(), panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);

        super.extractRenderState(g, mouseX, mouseY, partial);
        if (!usesDurationField(mode)) {
            g.text(font, Component.translatable("waypointer.screen.add_temp.duration.unused",
                            tempModeComponent(mode)),
                    panelX + PAD_OUTER, panelY + 38 + BTN_H + GAP,
                    TEXT_DIM, false);
        }
    }

    private void cycleMode() {
        mode = nextTempMode(mode);
        modeBtn.setMessage(modeLabel());
        updateDurationVisibility();
    }

    private Component modeLabel() {
        return Component.translatable("waypointer.screen.add_temp.mode", tempModeComponent(mode));
    }

    private static Component tempModeComponent(int mode) {
        return Component.translatable(switch (Waypoint.normalizeTempMode(mode)) {
            case Waypoint.TEMP_TIME -> "waypointer.temp_mode.time";
            case Waypoint.TEMP_UNTIL_REACHED -> "waypointer.temp_mode.reach";
            case Waypoint.TEMP_UNTIL_LEAVE -> "waypointer.temp_mode.leave";
            default -> "waypointer.temp_mode.none";
        });
    }

    static boolean usesDurationField(int tempMode) {
        return Waypoint.normalizeTempMode(tempMode) == Waypoint.TEMP_TIME;
    }

    static int nextTempMode(int currentMode) {
        if (currentMode >= Waypoint.TEMP_UNTIL_LEAVE) return Waypoint.TEMP_TIME;
        return Math.max(Waypoint.TEMP_TIME, currentMode + 1);
    }
    static int durationSecondsAfterEdit(int currentDurationSec, String rawValue) {
        int fallback = Math.max(1, Math.min(24 * 60 * 60, currentDurationSec));
        if (rawValue == null) return fallback;
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return Math.max(1, Math.min(24 * 60 * 60, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void updateDurationVisibility() {
        if (durationBox == null) return;
        boolean usesDuration = usesDurationField(mode);
        durationBox.active = usesDuration;
        durationBox.visible = usesDuration;
    }
    private void createAndClose() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) { onClose(); return; }

        long expiresAt = mode == Waypoint.TEMP_TIME
                ? System.currentTimeMillis() + (long) durationSec * 1_000L
                : 0L;

        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                p.getX(), p.getY(), p.getZ(), config);
        Waypoint base = Waypoint.at(pos.x(), pos.y(), pos.z());
        WaypointGroup target = manager.getOrCreateTempGroup();
        target.add(base.withTemp(mode, expiresAt));
        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(target, target.size() - 1);
        }
        manager.fireTransientDataChanged();

        config.setTempDefaultMode(mode);
        if (mode == Waypoint.TEMP_TIME) config.setTempDefaultDurationSec(durationSec);

        onClose();
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
