package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import static dev.ethan.waypointer.screen.GuiTokens.*;

/**
 * Tabbed settings screen.
 *
 * <p>Direct tabs keep the growing settings surface quickly navigable without
 * returning to one dense wall of checkboxes. Numeric fields use text input with
 * live commit so users can type free-form values without hunting through sliders.
 * Every mutation saves immediately via {@link WaypointerConfig}, so closing the
 * screen never loses work.
 *
 * Uses the shared {@link GuiTokens} footer so "Done" can't collide with anything
 * and the chrome matches the other two screens.
 */
public final class ConfigScreen extends Screen {

    private enum Page {
        VISUALS("Visuals"),
        PERFORMANCE("Performance"),
        ROUTES("Routes"),
        CHAT("Chat"),
        SYSTEM("System");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private final Screen parent;
    private final WaypointerConfig config;
    private final Page page;
    private final List<DependentControl> dependentControls = new ArrayList<>();

    public ConfigScreen(Screen parent, WaypointerConfig config) {
        this(parent, config, Page.VISUALS);
    }

    private ConfigScreen(Screen parent, WaypointerConfig config, Page page) {
        super(Component.literal("Waypointer Settings"));
        this.parent = parent;
        this.config = config;
        this.page = page == null ? Page.VISUALS : page;
    }

    @Override
    protected void init() {
        dependentControls.clear();

        int navY = PAD_OUTER + font.lineHeight + GAP;
        int top = navY + BTN_H + GAP_SECTION;
        int rowH = 24;
        int colGap = GAP_SECTION;
        int col1 = PAD_OUTER;
        int colW = (width - PAD_OUTER * 2 - colGap) / 2;
        int col2 = col1 + colW + colGap;

        int headerY = top;
        int rowsY = top + 16;

        addPageTabs(navY);

        switch (page) {
            case VISUALS -> addVisualsPage(col1, col2, colW, rowsY, rowH);
            case PERFORMANCE -> addPerformancePage(col1, col2, colW, rowsY, rowH);
            case ROUTES -> addRoutesPage(col1, col2, colW, rowsY, rowH);
            case CHAT -> addChatPage(col1, col2, colW, rowsY, rowH);
            case SYSTEM -> addSystemPage(col1, col2, colW, rowsY, rowH);
        }

        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> empty = new ArrayList<>();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", -1, this::onClose,
                Tooltip.create(Component.literal(
                        "Return to the previous screen.\n"
                      + "Every change on this page is saved as you type or click.")));
        GuiTokens.layoutFooter(width, footerY, empty, done, this::addRenderableWidget, font);

        this.leftHeaderX = col1;
        this.rightHeaderX = col2;
        this.sectionHeaderY = headerY;
    }

    private void addPageTabs(int y) {
        int x = PAD_OUTER;
        for (Page target : Page.values()) {
            int tabW = Math.max(64, font.width(target.label) + 18);
            Button btn = Button.builder(Component.literal(target.label), b -> {
                if (target != page) {
                    minecraft.setScreen(new ConfigScreen(parent, config, target));
                }
            }).bounds(x, y, tabW, BTN_H).build();
            btn.active = target != page;
            addRenderableWidget(btn);
            x += tabW + GAP;
        }
    }

    private void addVisualsPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Markers";
        rightHeader = "Labels & Tracers";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Waypoint box opacity (0-1)",
                config.beaconOpacity(), config::setBeaconOpacity,
                "Opacity of each waypoint's world-space box. 0 hides the volume,\n"
              + "1 is the strongest fill; labels can still show separately.");
        y += rowH;
        addBoxStyleRow(col1, y, colW);
        y += rowH;
        addBeamModeRow(col1, y, colW);
        y += rowH;
        addBoolRow(col1, y, "Beam extends below waypoint",
                config.beaconBeamExtendsBelowWaypoint(), config::setBeaconBeamExtendsBelowWaypoint,
                () -> config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF,
                "When beacon beams are enabled, start each beam at the world's bottom\n"
              + "instead of at the waypoint's Y level. Useful for finding targets\n"
              + "above or below you through terrain.");
        y += rowH;
        addBoolRow(col1, y, "Show completed waypoints", config.showCompleted(), config::setShowCompleted,
                "When on, waypoints you have already reached still draw (usually faded).\n"
              + "When off, completed stops disappear from the world HUD.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Show waypoint names", config.showWaypointNames(), config::setShowWaypointNames,
                "Floating name labels at each rendered waypoint. Off keeps boxes without text.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint distances",
                config.showWaypointDistances(), config::setShowWaypointDistances,
                "Distance text below each waypoint label. Can stay on even when names\n"
              + "are hidden, giving compact distance-only markers.");
        y2 += rowH;
        addBoolRow(col2, y2, "Waypoint text inherits color",
                config.matchWaypointTextToWaypointColor(), config::setMatchWaypointTextToWaypointColor,
                config::showWaypointNames,
                "When on, each floating waypoint name uses that waypoint's color.\n"
              + "When off, names stay white for maximum contrast.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show label backdrop", config.showLabelBackdrop(), config::setShowLabelBackdrop,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                "Draws a dark rectangle behind floating waypoint names for readability.\n"
              + "Turn off for a lighter HUD when labels stack in busy areas.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Label height offset (blocks)",
                config.labelHeightOffset(), config::setLabelHeightOffset,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                "Extra blocks to push each waypoint label above its marker. 0 keeps the\n"
              + "default placement. Use large values if distant labels still cover the\n"
              + "box; finite numbers only, no arbitrary clamp.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show tracers", config.showTracer(), config::setShowTracer,
                "Master switch for crosshair tracers. When off, no tracer lines are drawn\n"
              + "for any group (other waypoint rendering is unchanged).");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Tracer opacity (0-1)",
                config.tracerOpacity(), config::setTracerOpacity,
                config::showTracer,
                "Opacity of the line drawn from the crosshair to the active waypoint.\n"
              + "0 is fully transparent, 1 is solid.");
        y2 += rowH;
        addNumberRow(col2, y2, colW, "Tracer color (hex RRGGBB)",
                config.tracerColor(), v -> config.setTracerColor((int) v), true,
                () -> config.showTracer() && !config.matchTracerToWaypointColor(),
                "Fixed tracer color as hex RRGGBB (e.g. 4FE05A). Only used when\n"
              + "\"Tracer inherits waypoint color\" is off.");
        y2 += rowH;
        addBoolRow(col2, y2, "Tracer inherits waypoint color",
                config.matchTracerToWaypointColor(), config::setMatchTracerToWaypointColor,
                config::showTracer,
                "When on, the tracer uses each waypoint's rendered color (gradient routes\n"
              + "shift hue as you progress). When off, every tracer uses the hex color above.");
    }

    private void addPerformancePage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Budgets";
        rightHeader = "Cosmetic Cost";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Max waypoint labels (0 = unlimited)",
                config.maxWaypointLabels(),
                this::setMaxWaypointLabels,
                "High impact when many waypoints are on screen. Keeps only the nearest\n"
              + "N floating labels, while boxes and tracers can still render normally.");
        y += rowH;
        addNumberRow(col1, y, colW, "Static marker distance (0 = unlimited)",
                config.maxStaticWaypointRenderDistance(),
                config::setMaxStaticWaypointRenderDistance,
                "High impact on huge STATIC overlays. Skips boxes, beams, and labels\n"
              + "for static waypoints farther than this many blocks from the camera.\n"
              + "SEQUENCE targets stay uncapped so navigation does not disappear.");

        int y2 = rowsY;
        addBoxStyleRow(col2, y2, colW,
                "Medium/high impact: filled cubes add translucent faces to every marker.");
        y2 += rowH;
        addBeamModeRow(col2, y2, colW,
                "High impact in \"All visible\" mode: each visible waypoint draws a tall beam.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint names",
                config.showWaypointNames(), config::setShowWaypointNames,
                "High impact in dense routes because every name submits text to the HUD.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show waypoint distances",
                config.showWaypointDistances(), config::setShowWaypointDistances,
                "Medium/high impact in dense routes because each visible distance is text.");
        y2 += rowH;
        addBoolRow(col2, y2, "Show label backdrop",
                config.showLabelBackdrop(), config::setShowLabelBackdrop,
                () -> config.showWaypointNames() || config.showWaypointDistances(),
                "Medium impact when many labels are visible. Turning this off removes\n"
              + "one rectangle draw behind every label row.");
    }

    private void addRoutesPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Progression";
        rightHeader = "Route Display";

        int y = rowsY;
        addNumberRow(col1, y, colW, "Default reach radius (blocks)",
                config.defaultReachRadius(), config::setDefaultReachRadius,
                "How close you must stand (in blocks) to mark the current waypoint reached,\n"
              + "when a waypoint does not set its own radius. Group default radius can\n"
              + "override this in the group editor.");
        y += rowH;
        addBoolRow(col1, y, "Enable waypoint skip-ahead mechanic",
                config.skipAheadMechanicEnabled(), config::setSkipAheadMechanicEnabled,
                "Skip waypoints by walking to a waypoint further in a sequenced route.");
        y += rowH;
        addBoolRow(col1, y, "Reset progress when joining a world",
                config.resetProgressOnWorldJoin(), config::setResetProgressOnWorldJoin,
                "On world load or multiplayer join, every group's \"current\" waypoint resets\n"
              + "to the start. Off keeps saved progress across reconnects.");
        y += rowH;
        addBoolRow(col1, y, "Restart route after last waypoint",
                config.restartRouteWhenComplete(), config::setRestartRouteWhenComplete,
                "After you complete the final waypoint, progress wraps to the first point\n"
              + "so loop and farm routes do not sit in a \"finished\" state.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Dim sequence context waypoints",
                config.dimSequenceContextWaypoints(), config::setDimSequenceContextWaypoints,
                "Dim waypoints surrounding your current one in a sequenced route.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide tracer on static routes",
                config.hideTracerOnStaticRoutes(), config::setHideTracerOnStaticRoutes,
                config::showTracer,
                "Disable the waypoint tracer on static routes.");
        y2 += rowH;
        addBoolRow(col2, y2, "Hide reached static waypoints",
                config.hideReachedStaticWaypointsUntilCycleComplete(),
                config::setHideReachedStaticWaypointsUntilCycleComplete,
                "For STATIC groups, hide each waypoint when you enter its reach radius.\n"
              + "After every waypoint in the group has been reached, all of them show again.");
        y2 += rowH;
        addBoolRow(col2, y2, "Focus mode for temp waypoints",
                config.focusTempWaypoints(), config::setFocusTempWaypoints,
                "When on, adding a temporary waypoint hides other waypoints in the\n"
              + "active zone and forces a tracer to the temp until you leave the server.");
    }

    private void addChatPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Chat Detection";
        rightHeader = "Export Defaults";

        int y = rowsY;
        addBoolRow(col1, y, "Chat coord detection", config.chatCoordDetection(), config::setChatCoordDetection,
                "Scans incoming chat for coordinates and can offer quick-add flows for\n"
              + "temporary or permanent waypoints (no effect when chat has no coords).");
        y += rowH;
        addBoolRow(col1, y, "Auto-add chat temp waypoints",
                config.autoAddChatTempWaypoints(), config::setAutoAddChatTempWaypoints,
                config::chatCoordDetection,
                "When chat coord detection finds a coordinate, immediately creates a\n"
              + "session-scoped temp waypoint. Turn off to keep click-to-add behavior only.");
        y += rowH;
        addBoolRow(col1, y, "Chat codec detection (imports)",
                config.chatCodecDetection(), config::setChatCodecDetection,
                "Detects Waypointer share codes pasted in chat so you can import routes\n"
              + "without opening the main menu.");

        int y2 = rowsY;
        addBoolRow(col2, y2, "Include names in default export",
                config.exportIncludeNames(), config::setExportIncludeNames,
                "When exporting, include waypoint names in the payload. Makes shared codes\n"
              + "longer but preserves labels for the recipient.");
    }

    private void addSystemPage(int col1, int col2, int colW, int rowsY, int rowH) {
        leftHeader = "Location";
        rightHeader = "Maintenance";

        addBoolRow(col1, rowsY, "Always use scoreboard for zone detection",
                config.preferScoreboardFallback(), config::setPreferScoreboardFallback,
                "Prefer Hypixel-style scoreboard hints when resolving the current zone ID,\n"
              + "even when other signals exist. Use if tab/location detection misbehaves.");

        addBoolRow(col2, rowsY, "Check for updates on startup",
                config.checkForUpdates(), config::setCheckForUpdates,
                "On client start, checks GitHub once for a newer Waypointer release.\n"
              + "Off avoids any update HTTP request.");
    }

    private int leftHeaderX;
    private int rightHeaderX;
    private int sectionHeaderY;
    private String leftHeader = "";
    private String rightHeader = "";

    private interface DoubleSetter { void accept(double value); }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, () -> true, tooltip);
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              BooleanSupplier enabled, String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, false, enabled, tooltip);
    }

    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              boolean hex, String tooltip) {
        addNumberRow(x, y, colW, label, initial, setter, hex, () -> true, tooltip);
    }

    /**
     * Label + inline editor. The hex flag switches the parse path so colors round-trip
     * through user-friendly {@code RRGGBB} strings without us needing to expose a real
     * color picker.
     */
    private void addNumberRow(int x, int y, int colW, String label, double initial, DoubleSetter setter,
                              boolean hex, BooleanSupplier enabled, String tooltip) {
        int boxW = 80;
        int labelW = colW - boxW - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, label, labelW, enabled));
        EditBox box = new EditBox(font, x + labelW + GAP, y + 2, boxW, BTN_H, Component.literal(label));
        box.setMaxLength(24);
        box.setValue(hex ? String.format("%06X", (int) initial) : stripTrailingZeros(initial));
        box.setResponder(v -> {
            if (v.isEmpty()) return;
            try {
                double parsed = hex ? Integer.parseInt(v.trim(), 16) : Double.parseDouble(v.trim());
                setter.accept(parsed);
            } catch (NumberFormatException ignored) {
                // Partial edits are expected while typing; keep the last valid value.
            }
        });
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        trackDependent(box, enabled);
        addRenderableWidget(box);
    }

    private void addBoxStyleRow(int x, int y, int colW) {
        addBoxStyleRow(x, y, colW,
                "How each waypoint is drawn in the world:\n"
              + "Outlined — edge lines only.\n"
              + "Filled — translucent faces (easier to see at distance).\n"
              + "Filled + Outline — both for maximum contrast.");
    }

    private void addBoxStyleRow(int x, int y, int colW, String tooltip) {
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, "Box style", labelW, () -> true));
        Button btn = Button.builder(Component.literal(boxStyleLabel(config.boxStyle())), b -> {
            WaypointerConfig.BoxStyle[] values = WaypointerConfig.BoxStyle.values();
            WaypointerConfig.BoxStyle next = values[(config.boxStyle().ordinal() + 1) % values.length];
            config.setBoxStyle(next);
            b.setMessage(Component.literal(boxStyleLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(Tooltip.create(Component.literal(tooltip)))
                .build();
        addRenderableWidget(btn);
    }

    private void addBeamModeRow(int x, int y, int colW) {
        addBeamModeRow(x, y, colW,
                "Optional vertical guide beams:\n"
              + "Off — no beams.\n"
              + "Current — only each active group's target.\n"
              + "All visible — every rendered waypoint.");
    }

    private void addBeamModeRow(int x, int y, int colW, String tooltip) {
        int labelW = colW - 140 - GAP;
        addRenderableOnly(new LabelWidget(x, y + 6, "Beacon beams", labelW, () -> true));
        Button btn = Button.builder(Component.literal(beamModeLabel(config.beaconBeamMode())), b -> {
            WaypointerConfig.BeaconBeamMode[] values = WaypointerConfig.BeaconBeamMode.values();
            WaypointerConfig.BeaconBeamMode next =
                    values[(config.beaconBeamMode().ordinal() + 1) % values.length];
            config.setBeaconBeamMode(next);
            b.setMessage(Component.literal(beamModeLabel(next)));
        }).bounds(x + labelW + GAP, y, 140, BTN_H)
                .tooltip(Tooltip.create(Component.literal(tooltip)))
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

    private static String beamModeLabel(WaypointerConfig.BeaconBeamMode s) {
        return switch (s) {
            case OFF -> "Off";
            case CURRENT -> "Current";
            case ALL_VISIBLE -> "All visible";
        };
    }

    private void setMaxWaypointLabels(double value) {
        if (!Double.isFinite(value)) return;

        long rounded = Math.round(value);
        if (rounded <= 0) {
            config.setMaxWaypointLabels(0);
            return;
        }

        int clamped = rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
        config.setMaxWaypointLabels(clamped);
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter, String tooltip) {
        addBoolRow(x, y, label, initial, setter, () -> true, tooltip);
    }

    private void addBoolRow(int x, int y, String label, boolean initial,
                            java.util.function.Consumer<Boolean> setter,
                            BooleanSupplier enabled, String tooltip) {
        Checkbox cb = Checkbox.builder(Component.literal(label), font)
                .pos(x, y)
                .selected(initial)
                .onValueChange((b, v) -> setter.accept(v))
                .build();
        cb.setTooltip(Tooltip.create(Component.literal(tooltip)));
        trackDependent(cb, enabled);
        addRenderableWidget(cb);
    }

    private void trackDependent(AbstractWidget widget, BooleanSupplier enabled) {
        widget.active = enabled.getAsBoolean();
        dependentControls.add(new DependentControl(widget, enabled));
    }

    private void refreshDependentControls() {
        for (DependentControl control : dependentControls) {
            control.widget().active = control.enabled().getAsBoolean();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        refreshDependentControls();
        g.fill(0, 0, width, height, SURFACE);

        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.drawString(font, "Changes save automatically.",
                width - PAD_OUTER - font.width("Changes save automatically."),
                PAD_OUTER, TEXT_DIM, false);

        g.drawString(font, leftHeader, leftHeaderX, sectionHeaderY, TEXT_DIM, false);
        g.drawString(font, rightHeader, rightHeaderX, sectionHeaderY, TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Right-padded label with an explicit width so long labels truncate at column bounds. */
    private record DependentControl(AbstractWidget widget, BooleanSupplier enabled) {}

    private record LabelWidget(int x, int y, String text, int maxW, BooleanSupplier enabled)
            implements net.minecraft.client.gui.components.Renderable {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(text, maxW);
            g.drawString(font, clipped, x, y, enabled.getAsBoolean() ? TEXT : TEXT_DIM, false);
        }
    }

    private static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
