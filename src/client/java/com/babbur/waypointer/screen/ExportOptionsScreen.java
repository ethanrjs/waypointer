package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;
import static com.babbur.waypointer.screen.GuiTokens.styledCheckbox;

final class ExportOptionsScreen extends Screen {

    private static final int PANEL_MAX_W = 400;
    private static final int PANEL_MARGIN = 8;
    private static final int CHECKBOX_SIZE = 16;
    private static final int ENTRY_MIN_H = 18;
    private static final int ENTRY_MAX_H = 22;

    enum Option {
        NAMES(WaypointExportCodec.Target::supportsNames,
                WaypointerConfig::exportIncludeNames, WaypointerConfig::setExportIncludeNames),
        COLORS(WaypointExportCodec.Target::supportsColors,
                WaypointerConfig::exportIncludeColors, WaypointerConfig::setExportIncludeColors),
        ZONE(WaypointExportCodec.Target::supportsIslandChoice,
                WaypointerConfig::exportIncludeZone, WaypointerConfig::setExportIncludeZone),
        RADII(WaypointExportCodec.Target::supportsRadii,
                WaypointerConfig::exportIncludeRadii, WaypointerConfig::setExportIncludeRadii),
        WAYPOINT_FLAGS(WaypointExportCodec.Target::supportsWaypointFlags,
                WaypointerConfig::exportIncludeWaypointFlags, WaypointerConfig::setExportIncludeWaypointFlags),
        GROUP_META(WaypointExportCodec.Target::supportsGroupMeta,
                WaypointerConfig::exportIncludeGroupMeta, WaypointerConfig::setExportIncludeGroupMeta);

        private final Predicate<WaypointExportCodec.Target> supported;
        private final Predicate<WaypointerConfig> included;
        private final BiConsumer<WaypointerConfig, Boolean> setIncluded;

        Option(Predicate<WaypointExportCodec.Target> supported,
               Predicate<WaypointerConfig> included,
               BiConsumer<WaypointerConfig, Boolean> setIncluded) {
            this.supported = supported;
            this.included = included;
            this.setIncluded = setIncluded;
        }

        String key(String suffix) {
            return "waypointer.screen.export.toggle." + name().toLowerCase(Locale.ROOT) + "." + suffix;
        }
    }

    private final Screen parent;
    private final WaypointerConfig config;
    private final WaypointExportCodec.Target target;
    private final boolean subwaypointsForceFlags;
    private final Runnable onDone;
    private final List<Option> options = List.of(Option.values());
    private final List<GuiTokens.StyledCheckbox> checkboxes = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int entriesTop;
    private int entryH;

    ExportOptionsScreen(Screen parent, WaypointerConfig config, WaypointExportCodec.Target target,
                        boolean subwaypointsForceFlags, Runnable onDone) {
        super(Component.translatable("waypointer.screen.export.options.title"));
        this.parent = parent;
        this.config = config;
        this.target = target;
        this.subwaypointsForceFlags = subwaypointsForceFlags;
        this.onDone = onDone;
    }

    static boolean editable(Option option, WaypointExportCodec.Target target, boolean subwaypointsForceFlags) {
        if (!option.supported.test(target)) return false;
        return option != Option.WAYPOINT_FLAGS || !subwaypointsForceFlags;
    }

    static boolean shownIncluded(Option option, WaypointerConfig config, boolean subwaypointsForceFlags) {
        return option.included.test(config)
                || (option == Option.WAYPOINT_FLAGS && subwaypointsForceFlags);
    }

    @Override
    protected void init() {
        checkboxes.clear();
        panelW = Math.min(PANEL_MAX_W, Math.max(260, width - PANEL_MARGIN * 2));
        panelH = Math.min(height - PANEL_MARGIN * 2,
                32 + options.size() * ENTRY_MAX_H + BTN_H + GAP_SECTION);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        entriesTop = panelY + 28;
        int footerSpace = BTN_H + GAP + GAP_TIGHT;
        entryH = Math.max(ENTRY_MIN_H, Math.min(ENTRY_MAX_H,
                (panelH - (entriesTop - panelY) - footerSpace) / Math.max(1, options.size())));

        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            int checkboxY = entriesTop + i * entryH + Math.max(0, (entryH - CHECKBOX_SIZE) / 2);
            GuiTokens.StyledCheckbox checkbox = styledCheckbox(
                    panelX + GAP_SECTION, checkboxY, CHECKBOX_SIZE,
                    Component.translatable(option.key("label")),
                    shownIncluded(option, config, subwaypointsForceFlags),
                    selected -> option.setIncluded.accept(config, selected),
                    unavailableReason(option));
            checkbox.active = editable(option, target, subwaypointsForceFlags);
            checkboxes.add(checkbox);
            addRenderableWidget(checkbox);
        }

        Component doneLabel = Component.translatable("gui.done");
        int doneW = Math.max(70, font.width(doneLabel) + 18);
        Button done = styledButton(panelX + panelW - GAP_SECTION - doneW, panelY + panelH - BTN_H - GAP,
                doneW, BTN_H, doneLabel, ignored -> onClose(), null);
        addRenderableWidget(done);
    }

    private Tooltip unavailableReason(Option option) {
        if (!option.supported.test(target)) {
            return Tooltip.create(Component.translatable(option.key("unsupported"), target.displayName()));
        }
        if (option == Option.WAYPOINT_FLAGS && subwaypointsForceFlags) {
            return Tooltip.create(Component.translatable(option.key("forced")));
        }
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);

        int titleX = panelX + GAP_SECTION;
        int titleY = panelY + GAP;
        g.text(font, getTitle(), titleX, titleY, TEXT, false);
        g.fill(titleX, titleY + font.lineHeight + GAP_TIGHT,
                panelX + panelW - GAP_SECTION, titleY + font.lineHeight + GAP_TIGHT + 1, ACCENT);

        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            int rowY = entriesTop + i * entryH;
            if ((i & 1) == 1) {
                g.fill(panelX + GAP, rowY, panelX + panelW - GAP, rowY + entryH, SURFACE_SUBTLE);
            }
            boolean editable = checkboxes.get(i).active;
            int textX = panelX + GAP_SECTION + CHECKBOX_SIZE + GAP;
            g.text(font, Component.translatable(option.key("label")), textX,
                    rowY + (entryH - font.lineHeight + 1) / 2, editable ? TEXT : TEXT_MUTED, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT
                || event.x() < panelX + GAP
                || event.x() > panelX + panelW - GAP
                || event.y() < entriesTop) {
            return false;
        }
        int index = (int) ((event.y() - entriesTop) / entryH);
        if (index < 0 || index >= checkboxes.size()
                || event.y() >= entriesTop + options.size() * entryH) {
            return false;
        }
        GuiTokens.StyledCheckbox checkbox = checkboxes.get(index);
        if (!checkbox.active) return false;
        checkbox.onPress(event);
        return true;
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
        onDone.run();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
