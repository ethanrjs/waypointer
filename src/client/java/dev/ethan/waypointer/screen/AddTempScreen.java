package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Modal for creating a temporary waypoint. Three expiry modes are offered and
 * a duration (minutes) for the one mode that needs it; every mode produces a
 * waypoint at the player's current block position.
 *
 * <p>The mode + duration defaults come from
 * {@link WaypointerConfig#tempDefaultMode()} / {@link WaypointerConfig#tempDefaultDurationMin()}
 * so the keybind path ("Add temp waypoint here") and the sidebar button path
 * share a single "what did the user last pick" memory without the user having
 * to re-confirm in the GUI for the keybind variant.
 *
 * <p>Screen is intentionally small and centred. Putting it over the editor
 * lets the user see the list it's being added to, reinforcing that temp
 * waypoints belong to the same group (they just auto-delete later).
 */
public final class AddTempScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 168;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointGroup group;

    private int mode;
    private int durationMin;
    private Button modeBtn;
    private EditBox durationBox;

    public AddTempScreen(Screen parent, ActiveGroupManager manager, WaypointerConfig config, WaypointGroup group) {
        super(Component.literal("Add Temporary Waypoint"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
        this.group = group;
        this.mode = clampMode(config.tempDefaultMode());
        this.durationMin = Math.max(1, config.tempDefaultDurationMin());
    }

    public static void open(Screen parent, ActiveGroupManager manager,
                            WaypointerConfig config, WaypointGroup group) {
        Minecraft.getInstance().setScreen(new AddTempScreen(parent, manager, config, group));
    }

    @Override
    protected void init() {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        int inner = panelX + PAD_OUTER;
        int fieldW = PANEL_W - PAD_OUTER * 2;
        int y = panelY + 32;

        modeBtn = Button.builder(modeLabel(), b -> cycleMode())
                .bounds(inner, y, fieldW, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "Cycle through expiry modes:\n"
                      + "TIME: auto-delete after N minutes.\n"
                      + "REACH: delete when you step inside its radius.\n"
                      + "LEAVE: delete when you disconnect from the server.\n"
                      + "All temp waypoints vanish on disconnect regardless.")))
                .build();
        addRenderableWidget(modeBtn);
        y += BTN_H + GAP;

        durationBox = new EditBox(font, inner, y, fieldW, BTN_H, Component.literal("Duration (min)"));
        durationBox.setMaxLength(5);
        durationBox.setValue(String.valueOf(durationMin));
        durationBox.setResponder(v -> {
            try { durationMin = Math.max(1, Integer.parseInt(v.trim())); }
            catch (NumberFormatException ignored) {
                // Swallow: partial edits are fine, we re-check on Save.
            }
        });
        durationBox.setTooltip(Tooltip.create(Component.literal(
                "Minutes until a TIME-mode temp expires. Ignored for REACH and LEAVE.")));
        addRenderableWidget(durationBox);
        y += BTN_H + GAP;

        int footerY = panelY + PANEL_H - BTN_H - PAD_OUTER / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + PANEL_W - PAD_OUTER - 140 - GAP, footerY, 70, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> createAndClose())
                .bounds(panelX + PANEL_W - PAD_OUTER - 70, footerY, 70, BTN_H).build());
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

    private void cycleMode() {
        // TIME (1) -> REACH (2) -> LEAVE (3) -> TIME (1).
        // We skip TEMP_NONE (0) because this whole screen is for temp creation;
        // picking "none" would mean "add a normal waypoint", which is already a
        // different button.
        mode = mode >= Waypoint.TEMP_UNTIL_LEAVE ? Waypoint.TEMP_TIME : mode + 1;
        modeBtn.setMessage(modeLabel());
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + modeName(mode));
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case Waypoint.TEMP_TIME          -> "TIME";
            case Waypoint.TEMP_UNTIL_REACHED -> "REACH";
            case Waypoint.TEMP_UNTIL_LEAVE   -> "LEAVE";
            default -> "?";
        };
    }

    private static int clampMode(int v) {
        // Defaults outside the valid temp range fall back to REACH, the least
        // surprising "default temp": no timer to reason about, no server-scope
        // tie-in, just "get rid of it when I've been there".
        if (v < Waypoint.TEMP_TIME || v > Waypoint.TEMP_UNTIL_LEAVE) return Waypoint.TEMP_UNTIL_REACHED;
        return v;
    }

    private void createAndClose() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) { onClose(); return; }

        long expiresAt = mode == Waypoint.TEMP_TIME
                ? System.currentTimeMillis() + durationMin * 60_000L
                : 0L;

        Waypoint base = Waypoint.at(
                (int) Math.floor(p.getX()),
                (int) Math.floor(p.getY()),
                (int) Math.floor(p.getZ()));
        group.add(base.withTemp(mode, expiresAt));
        manager.fireDataChanged();

        // Persist the user's last picks so the next "add temp" (whether from
        // here or the keybind) starts on the same settings.
        config.setTempDefaultMode(mode);
        if (mode == Waypoint.TEMP_TIME) config.setTempDefaultDurationMin(durationMin);

        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
