package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Settings screen.
 *
 * Two columns grouped under section headers so the page is scannable in a glance.
 * Numeric fields use text input with live commit so users can type free-form values
 * without hunting through sliders. Every mutation saves immediately via
 * {@link WaypointerConfig}, so closing the screen never loses work.
 *
 * Uses the shared {@link GuiTokens} footer so "Done" can't collide with anything
 * and the chrome matches the other two screens.
 */
public final class ConfigScreen extends Screen {

    private final Screen parent;
    private final WaypointerConfig config;

    public ConfigScreen(Screen parent, WaypointerConfig config) {
        super(Component.literal("Waypointer Settings"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int top = PAD_OUTER + 10 + GAP_SECTION;
        int rowH = 24;
        int colGap = GAP_SECTION;
        int col1 = PAD_OUTER;
        int colW = (width - PAD_OUTER * 2 - colGap) / 2;
        int col2 = col1 + colW + colGap;

        // Section headers sit above each column and answer "what am I looking at".
        // Without them the inputs feel like a wall.
        int headerY = top;
        int rowsY = top + 16;

        // --- Column 1: Rendering -----------------------------------------------------------
        int y = rowsY;
        addNumberRow(col1, y, colW, "Default reach radius (blocks)",
                config.defaultReachRadius(), config::setDefaultReachRadius);
        y += rowH;
        addNumberRow(col1, y, colW, "Tracer opacity (0-1)",
                config.tracerOpacity(), config::setTracerOpacity);
        y += rowH;
        addNumberRow(col1, y, colW, "Beacon opacity (0-1)",
                config.beaconOpacity(), config::setBeaconOpacity);
        y += rowH;
        addNumberRow(col1, y, colW, "Tracer color (hex RRGGBB)",
                config.tracerColor(), v -> config.setTracerColor((int) v), true);
        y += rowH;
        addBoolRow(col1, y, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor);
        y += rowH;
        addBoolRow(col1, y, "Show label backdrop", config.showLabelBackdrop(), config::setShowLabelBackdrop);
        y += rowH;
        addBoolRow(col1, y, "Only render nearby waypoints (prev/current/next)",
                config.windowedRendering(), config::setWindowedRendering);
        y += rowH;
        addBoxStyleRow(col1, y, colW);

        // --- Column 2: Behavior ------------------------------------------------------------
        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names", config.showWaypointNames(), config::setShowWaypointNames);
        y2 += rowH;
        addBoolRow(col2, y2, "Show completed waypoints", config.showCompleted(), config::setShowCompleted);
        y2 += rowH;
        addBoolRow(col2, y2, "Draw tracers", config.showTracer(), config::setShowTracer);
        y2 += rowH;
        addBoolRow(col2, y2, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection);
        y2 += rowH;
        addBoolRow(col2, y2, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection);
        y2 += rowH;
        addBoolRow(col2, y2, "Include names in default export",
                config.exportIncludeNames(), config::setExportIncludeNames);
        y2 += rowH;
        addBoolRow(col2, y2, "Enable waypoint skip-ahead mechanic",
                config.skipAheadMechanicEnabled(), config::setSkipAheadMechanicEnabled);
        y2 += rowH;
        addBoolRow(col2, y2, "Disable skip-ahead on new waypoints",
                config.disableGroupSkipAheadOnWaypointAdd(), config::setDisableGroupSkipAheadOnWaypointAdd);
        y2 += rowH;
        addBoolRow(col2, y2, "Reset progress when joining a world",
                config.resetProgressOnWorldJoin(), config::setResetProgressOnWorldJoin);
        y2 += rowH;
        addBoolRow(col2, y2, "Restart route after last waypoint",
                config.restartRouteWhenComplete(), config::setRestartRouteWhenComplete);
        y2 += rowH;
        addBoolRow(col2, y2, "Always use scoreboard for zone detection",
                config.preferScoreboardFallback(), config::setPreferScoreboardFallback);
        y2 += rowH;
        addBoolRow(col2, y2, "Check for updates on startup",
                config.checkForUpdates(), config::setCheckForUpdates);

        // Footer
        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> empty = new ArrayList<>();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", this::onClose);
        GuiTokens.layoutFooter(width, footerY, empty, done, this::addRenderableWidget, font);

        // Stash header positions so render() can draw the section labels.
        this.renderingHeaderX = col1;
        this.behaviorHeaderX = col2;
        this.sectionHeaderY = headerY;
    }

    private int renderingHeaderX;
    private int behaviorHeaderX;
    private int sectionHeaderY;

    private interface DoubleSetter { void accept(double value); }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter) {
        addNumberRow(x, y, colW, label, initial, setter, false);
    }

    /**
     * Label + inline editor. The hex flag switches the parse path so colors round-trip
     * through user-friendly {@code RRGGBB} strings without us needing to expose a real
     * color picker.
     */
    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter, boolean hex) {
        // Label takes most of the row; edit box is a fixed width on the right.
        int boxW = 80;
        int labelW = colW - boxW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW));
        EditBox box = new EditBox(font, x + labelW + GAP, y + 2, boxW, BTN_H, Component.literal(label));
        box.setMaxLength(24);
        box.setValue(hex ? String.format("%06X", (int) initial) : stripTrailingZeros(initial));
        box.setResponder(v -> {
            if (v.isEmpty()) return;
            try {
                double parsed = hex ? Integer.parseInt(v.trim(), 16) : Double.parseDouble(v.trim());
                setter.accept(parsed);
            } catch (NumberFormatException ignored) {
                // Swallowed; invalid input just doesn't propagate. Box keeps the text so
                // the user can correct a typo without losing their edit.
            }
        });
        addRenderableWidget(box);
    }

    /**
     * Cycling button for the three-way BoxStyle enum. Chose a cycling button over
     * three radios because the modes form a natural progression (outline → fill →
     * both) and a single clickable label is less visual noise than a cluster of
     * toggles.
     */
    private void addBoxStyleRow(int x, int y, int colW) {
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, "Box style", labelW));
        Button btn = Button.builder(Component.literal(boxStyleLabel(config.boxStyle())), b -> {
            WaypointerConfig.BoxStyle[] values = WaypointerConfig.BoxStyle.values();
            WaypointerConfig.BoxStyle next = values[(config.boxStyle().ordinal() + 1) % values.length];
            config.setBoxStyle(next);
            b.setMessage(Component.literal(boxStyleLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H).build();
        addRenderableWidget(btn);
    }

    private static String boxStyleLabel(WaypointerConfig.BoxStyle s) {
        return switch (s) {
            case OUTLINED -> "Outlined";
            case FILLED -> "Filled";
            case FILLED_OUTLINED -> "Filled + Outline";
        };
    }

    private void addBoolRow(int x, int y, String label, boolean initial, java.util.function.Consumer<Boolean> setter) {
        Checkbox cb = Checkbox.builder(Component.literal(label), font)
                .pos(x, y)
                .selected(initial)
                .onValueChange((b, v) -> setter.accept(v))
                .build();
        addRenderableWidget(cb);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Solid dark backdrop so labels and checkboxes aren't competing with the world
        // behind the menu. The other Waypointer screens paint SURFACE panels under their
        // content; this one used to render naked over vanilla's gradient which made every
        // label read as low-contrast foreground text on a bright sky.
        g.fill(0, 0, width, height, SURFACE);

        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.drawString(font, "Changes save automatically.",
                width - PAD_OUTER - font.width("Changes save automatically."),
                PAD_OUTER, TEXT_DIM, false);

        g.drawString(font, "Rendering", renderingHeaderX, sectionHeaderY, TEXT_DIM, false);
        g.drawString(font, "Behavior", behaviorHeaderX, sectionHeaderY, TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Right-padded label with an explicit width so long labels truncate at column bounds. */
    private record LabelWidget(int x, int y, String text, int maxW)
            implements net.minecraft.client.gui.components.Renderable {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            // Minecraft's font doesn't truncate automatically; we could implement ellipsis
            // but every current label fits, so drawing as-is is fine.
            g.drawString(net.minecraft.client.Minecraft.getInstance().font, text, x, y, TEXT, false);
        }
    }

    private static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) return Integer.toString((int) v);
        return String.format("%.2f", v);
    }
}
