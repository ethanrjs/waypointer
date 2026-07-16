package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.compat.MinecraftCompat;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Modal for creating a temporary waypoint. Three expiry modes are offered and
 * a duration (seconds) for the one mode that needs it; every mode produces a
 * waypoint at the configured player-relative position.
 *
 * <p>The mode + duration defaults come from
 * {@link WaypointerConfig#tempDefaultMode()} / {@link WaypointerConfig#tempDefaultDurationSec()}
 * so the keybind path ("Add temp waypoint here") and the sidebar button path
 * share a single "what did the user last pick" memory without the user having
 * to re-confirm in the GUI for the keybind variant.
 *
 * <p>The temp waypoint lands in the current zone's dedicated temp bucket
 * ({@link ActiveGroupManager#getOrCreateTempGroup()}), <em>not</em> into the
 * group the screen was opened from. Temps used to be appended to the caller's
 * group, which caused real routes to pick up stray temp entries that then
 * polluted gradient recolouring, proximity advance, and reorder history. The
 * dedicated bucket keeps that separation clean even when the player opens
 * this modal from a regular route's edit screen.
 */
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
        super(Component.literal("Add Temporary Waypoint"));
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

        modeBtn = Button.builder(modeLabel(), this::onModeButtonPressed)
                .bounds(inner, y, fieldW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Cycle through expiry modes:\n"
                      + "TIME: auto-delete after N seconds.\n"
                      + "REACH: delete when you step inside its radius.\n"
                      + "LEAVE: delete when you disconnect from the server.\n"
                      + "All temp waypoints vanish on disconnect regardless.")))
                .build();
        addRenderableWidget(modeBtn);
        y += BTN_H + GAP;

        durationBox = new EditBox(font, inner, y, fieldW, BTN_H, Component.literal("Duration (sec)"));
        durationBox.setMaxLength(5);
        durationBox.setValue(String.valueOf(durationSec));
        durationBox.setResponder(this::onDurationTextChanged);
        durationBox.setTooltip(Tooltip.create(Component.literal(
                "Seconds until a TIME-mode temp expires. Ignored for REACH and LEAVE.")));
        addRenderableWidget(durationBox);
        updateDurationVisibility();
        y += BTN_H + GAP;

        int footerY = panelY + PANEL_H - BTN_H - PAD_OUTER / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), this::onCancelButtonPressed)
                .bounds(panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY, 70, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Add"), this::onAddButtonPressed)
                .bounds(panelX + PANEL_W - PAD_OUTER - 70, footerY, 70, BTN_H).build());
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
            g.text(font, "Duration not used for " + Waypoint.tempModeName(mode),
                    panelX + PAD_OUTER, panelY + 38 + BTN_H + GAP,
                    TEXT_DIM, false);
        }
    }

    private void cycleMode() {
        // TIME (1) -> REACH (2) -> LEAVE (3) -> TIME (1).
        // We skip TEMP_NONE (0) because this whole screen is for temp creation;
        // picking "none" would mean "add a normal waypoint", which is already a
        // different button.
        mode = nextTempMode(mode);
        modeBtn.setMessage(modeLabel());
        updateDurationVisibility();
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + Waypoint.tempModeName(mode));
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

        // Persist the user's last picks so the next "add temp" (whether from
        // here or the keybind) starts on the same settings.
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
