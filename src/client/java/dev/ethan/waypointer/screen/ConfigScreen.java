package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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

        int headerY = top;
        int rowsY = top + 16;

        // --- Column 1: Rendering -----------------------------------------------------------
        int y = rowsY;
        addNumberRow(col1, y, colW, "Default reach radius (blocks)",
                config.defaultReachRadius(), config::setDefaultReachRadius,
                "How close you must stand (in blocks) to mark the current waypoint reached,\n"
              + "when a waypoint does not set its own radius. Group default radius can\n"
              + "override this in the group editor.");
        y += rowH;
        addNumberRow(col1, y, colW, "Tracer opacity (0-1)",
                config.tracerOpacity(), config::setTracerOpacity,
                "Opacity of the line drawn from the crosshair to the active waypoint.\n"
              + "0 is fully transparent, 1 is solid.");
        y += rowH;
        addNumberRow(col1, y, colW, "Beacon opacity (0-1)",
                config.beaconOpacity(), config::setBeaconOpacity,
                "Opacity of each waypoint's world-space box / beacon. 0 hides the volume,\n"
              + "1 is the strongest fill; labels can still show separately.");
        y += rowH;
        addNumberRow(col1, y, colW, "Tracer color (hex RRGGBB)",
                config.tracerColor(), v -> config.setTracerColor((int) v), true,
                "Fixed tracer color as hex RRGGBB (e.g. 4FE05A). Only used when\n"
              + "\"Tracer inherits waypoint color\" is off.");
        y += rowH;
        addBoolRow(col1, y, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                "When on, the tracer uses each waypoint's rendered color (gradient routes\n"
              + "shift hue as you progress). When off, every tracer uses the hex color above.");
        y += rowH;
        addBoolRow(col1, y, "Show tracers", config.showTracer(), config::setShowTracer,
                "Master switch for crosshair tracers. When off, no tracer lines are drawn\n"
              + "for any group (other waypoint rendering is unchanged).");
        y += rowH;
        addBoolRow(col1, y, "Hide tracer on static routes",
                config.hideTracerOnStaticRoutes(), config::setHideTracerOnStaticRoutes,
                "When on (default), groups in STATIC load mode skip the tracer: every\n"
              + "waypoint is already visible, so the line is often clutter. SEQUENCE\n"
              + "routes still get a tracer to the current target.");
        y += rowH;
        addBoolRow(col1, y, "Show label backdrop", config.showLabelBackdrop(), config::setShowLabelBackdrop,
                "Draws a dark rectangle behind floating waypoint names for readability.\n"
              + "Turn off for a lighter HUD when labels stack in busy areas.");
        y += rowH;
        addBoxStyleRow(col1, y, colW);

        // --- Column 2: Behavior ------------------------------------------------------------
        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names", config.showWaypointNames(), config::setShowWaypointNames,
                "Floating name labels at each rendered waypoint. Off keeps beacons without text.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show completed waypoints", config.showCompleted(), config::setShowCompleted,
                "When on, waypoints you have already reached still draw (usually faded).\n"
              + "When off, completed stops disappear from the world HUD.");
        y2 += rowH;
        addBoolRow(col2, y2, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection,
                "Scans incoming chat for coordinates and can offer quick-add flows for\n"
              + "temporary or permanent waypoints (no effect when chat has no coords).");
        y2 += rowH;
        addBoolRow(col2, y2, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection,
                "Detects Waypointer share codes pasted in chat so you can import routes\n"
              + "without opening the main menu.");
        y2 += rowH;
        addBoolRow(col2, y2, "Include names in default export",
                config.exportIncludeNames(), config::setExportIncludeNames,
                "When exporting, include waypoint names in the payload. Makes shared codes\n"
              + "longer but preserves labels for the recipient.");
        y2 += rowH;
        addBoolRow(col2, y2, "Enable waypoint skip-ahead mechanic",
                config.skipAheadMechanicEnabled(), config::setSkipAheadMechanicEnabled,
                "Allows proximity to advance to a later waypoint on the route when you walk\n"
              + "into its radius, skipping intermediates. Off forces strict one-by-one order\n"
              + "for every group (per-group toggles still apply when this is on).");
        y2 += rowH;
        addBoolRow(col2, y2, "Disable skip-ahead on new waypoints",
                config.disableGroupSkipAheadOnWaypointAdd(), config::setDisableGroupSkipAheadOnWaypointAdd,
                "When you add a waypoint to a group, that group's skip-ahead turns off so you\n"
              + "are not instantly advanced past the new point. Re-enable in the group editor.");
        y2 += rowH;
        addBoolRow(col2, y2, "Reset progress when joining a world",
                config.resetProgressOnWorldJoin(), config::setResetProgressOnWorldJoin,
                "On world load or multiplayer join, every group's \"current\" waypoint resets\n"
              + "to the start. Off keeps saved progress across reconnects.");
        y2 += rowH;
        addBoolRow(col2, y2, "Restart route after last waypoint",
                config.restartRouteWhenComplete(), config::setRestartRouteWhenComplete,
                "After you complete the final waypoint, progress wraps to the first point\n"
              + "so loop and farm routes do not sit in a \"finished\" state.");
        y2 += rowH;
        addBoolRow(col2, y2, "Always use scoreboard for zone detection",
                config.preferScoreboardFallback(), config::setPreferScoreboardFallback,
                "Prefer Hypixel-style scoreboard hints when resolving the current zone ID,\n"
              + "even when other signals exist. Use if tab/location detection misbehaves.");
        y2 += rowH;
        addBoolRow(col2, y2, "Check for updates on startup",
                config.checkForUpdates(), config::setCheckForUpdates,
                "On client start, checks GitHub once for a newer Waypointer release.\n"
              + "Off avoids any update HTTP request.");

        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> empty = new ArrayList<>();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", -1, this::onClose,
                Tooltip.create(Component.literal(
                        "Return to the previous screen.\n"
                      + "Every change on this page is saved as you type or click.")));
        GuiTokens.layoutFooter(width, footerY, empty, done, this::addRenderableWidget, font);

        this.renderingHeaderX = col1;
        this.behaviorHeaderX = col2;
        this.sectionHeaderY = headerY;
    }

    private int renderingHeaderX;
    private int behaviorHeaderX;
    private int sectionHeaderY;

    private interface DoubleSetter { void accept(double value); }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, tooltip);
    }

    /**
     * Label + inline editor. The hex flag switches the parse path so colors round-trip
     * through user-friendly {@code RRGGBB} strings without us needing to expose a real
     * color picker.
     */
    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              boolean hex, String tooltip) {
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
            }
        });
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        addRenderableWidget(box);
    }

    private void addBoxStyleRow(int x, int y, int colW) {
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, "Box style", labelW));
        Button btn = Button.builder(Component.literal(boxStyleLabel(config.boxStyle())), b -> {
            WaypointerConfig.BoxStyle[] values = WaypointerConfig.BoxStyle.values();
            WaypointerConfig.BoxStyle next = values[(config.boxStyle().ordinal() + 1) % values.length];
            config.setBoxStyle(next);
            b.setMessage(Component.literal(boxStyleLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(Tooltip.create(Component.literal(
                        "How each waypoint is drawn in the world:\n"
                      + "Outlined — edge lines only.\n"
                      + "Filled — translucent faces (easier to see at distance).\n"
                      + "Filled + Outline — both for maximum contrast.")))
                .build();
        addRenderableWidget(btn);
    }

    private static String boxStyleLabel(WaypointerConfig.BoxStyle s) {
        return switch (s) {
            case OUTLINED -> "Outlined";
            case FILLED -> "Filled";
            case FILLED_OUTLINED -> "Filled + Outline";
        };
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter, String tooltip) {
        Checkbox cb = Checkbox.builder(Component.literal(label), font)
                .pos(x, y)
                .selected(initial)
                .onValueChange((b, v) -> setter.accept(v))
                .build();
        cb.setTooltip(Tooltip.create(Component.literal(tooltip)));
        addRenderableWidget(cb);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
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
            g.drawString(net.minecraft.client.Minecraft.getInstance().font, text, x, y, TEXT, false);
        }
    }

    private static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) return Integer.toString((int) v);
        return String.format("%.2f", v);
    }
}
