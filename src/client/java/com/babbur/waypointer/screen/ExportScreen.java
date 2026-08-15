package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import com.babbur.waypointer.screen.preview.RoutePreviewOrbit;
import com.babbur.waypointer.screen.preview.RoutePreviewScene;
import com.babbur.waypointer.screen.preview.RoutePreviewWidget;
import com.babbur.waypointer.screen.preview.RoutePreviewZoom;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;

public final class ExportScreen extends Screen {

    private static final int PREVIEW_INSET = 6;
    private static final String DUNGEON_ROOM_LABEL_PREFIX = "Dungeons: ";

    private static final long COPIED_FEEDBACK_MS = 1500;

    private static final String SUMMARY_SEPARATOR = "  ·  ";

    private static final int HEADER_H = 28;

    private static final int LINE_H = 12;

    private static final int EXPORT_SETTINGS_HEADER_H = LINE_H * 2 + GAP_TIGHT;

    private static final int BLOCK_GAP = GAP;

    private static final int PANEL_MARGIN = 16;
    private static final int PANEL_MIN_W = 260;
    private static final int PANEL_MAX_W = 448;
    private static final int WIDE_PREVIEW_BREAKPOINT = 736;
    private static final int PREVIEW_PANE_MIN_W = 240;
    private static final int PREVIEW_PANE_MAX_W = 448;
    private static final int PREVIEW_SPLIT_GAP = GAP;
    private static final int HEADER_SWITCH_W = 70;
    private static final int PREVIEW_NAV_W = RoutePreviewWidget.NAV_BUTTON_W;

    private static final int INCLUDE_ROWS_PER_COL = 3;
    private static final int INCLUDE_COL_MIN_W = 150;
    private static final int INCLUDE_COL_MAX_W = 200;
    private static final int INCLUDE_ROW_PITCH = BTN_H + GAP_TIGHT;
    private static final int INCLUDE_ROW_INSET = 6;

    private static final int EXPORT_FOR_MIN_W = 112;
    private static final int EXPORT_FOR_MAX_W = 168;
    private static final int ROUTE_PICKER_TOGGLE_W = 86;
    private static final int ROUTE_PICKER_SELECT_ALL_W = 76;
    private static final int ROUTE_PICKER_COLLAPSE_THRESHOLD = 9;
    private static final int MAX_EXPANDED_ROUTE_ROWS = 6;
    private static final int ROUTE_ROW_H = 24;
    private static final int ROUTE_ROW_GAP = 2;
    private static final int ROUTE_ROW_PITCH = ROUTE_ROW_H + ROUTE_ROW_GAP;
    private static final int ROUTE_PICKER_INSET = 4;
    private static final int ROUTE_SCROLLBAR_W = 3;

    private static final int PREVIEW_LINES = 3;

    private final Screen parent;
    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final List<WaypointGroup> groups;
    private final ExportRouteSelection routeSelection;
    private final String subtitle;

    private WaypointCodec.Options.Builder optsBuilder;
    private WaypointExportCodec.Target exportTarget = WaypointExportCodec.Target.WAYPOINTER;
    private String currentLabel = "";

    private EditBox labelInput;
    private final List<ToggleSpec> toggleSpecs = new ArrayList<>();
    private final List<Button> toggleButtons = new ArrayList<>();
    private Button exportForButton;
    private Button routePickerToggleButton;
    private Button routeSelectAllButton;
    private Button copyButton;
    private Button copyCodeBlockButton;
    private Button previewPageButton;
    private Button previewPreviousButton;
    private Button previewNextButton;
    private Button previewZoomResetButton;
    private RoutePreviewWidget routePreviewWidget;
    private ListNavigationWidget routeListNavigation;
    private boolean routePickerExpanded;
    private boolean compactPreviewPage;
    private int routeScrollOffset;
    private long copyFeedbackUntil = 0L;
    private long copyCodeBlockFeedbackUntil = 0L;

    private String encoded = "";
    private String encodingError = "";

    private int encodeGeneration;
    private boolean encodePending;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int controlPanelW;
    private int contentX;
    private int contentW;
    private int labelRowY;
    private int includeHeadY;
    private int includeRowsY;
    private int routeBlockY;
    private int sizeY;
    private int previewY;
    private int previewH;
    private int footerY;
    private boolean widePreviewLayout;
    private int routePreviewX;
    private int routePreviewY;
    private int routePreviewW;
    private int routePreviewH;

    private final RoutePreviewOrbit previewOrbit = new RoutePreviewOrbit();
    private final RoutePreviewZoom previewZoom = new RoutePreviewZoom();
    private RoutePreviewScene previewScene;
    private int previewGroupIndex;

    private final boolean previewEnabled;

    public ExportScreen(Screen parent, WaypointerConfig config, List<WaypointGroup> groups, String subtitle) {
        this(parent, config, null, groups, subtitle);
    }

    public ExportScreen(Screen parent, WaypointerConfig config, ActiveGroupManager manager,
                        List<WaypointGroup> groups, String subtitle) {
        super(Component.translatable("waypointer.screen.export.title"));
        this.parent = parent;
        this.config = config;
        this.manager = manager;
        this.groups = groups;
        this.routeSelection = new ExportRouteSelection(groups.size());
        this.subtitle = subtitle;
        this.routePickerExpanded = shouldStartRoutePickerExpanded(groups.size());
        this.optsBuilder = ExportPolicy.optionsFromConfig(config, selectedGroupsForExport());
        this.previewEnabled = config.showExportRoutePreview();
        this.previewGroupIndex = firstSelectedGroupIndex();
        this.previewScene = previewEnabled ? buildPreviewScene() : RoutePreviewScene.empty();
    }

    public static void openForGroup(Screen parent, WaypointerConfig config, WaypointGroup group) {
        openForGroup(parent, config, WaypointerClient.manager(), group);
    }

    public static void openForGroup(Screen parent, WaypointerConfig config,
                                    ActiveGroupManager manager, WaypointGroup group) {
        String title = Component.translatable(group.size() == 1
                ? "waypointer.screen.export.subtitle.route.one"
                : "waypointer.screen.export.subtitle.route.many",
                routeDisplayName(group), group.size()).getString();
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ExportScreen(parent, config, manager, List.of(group), title));
    }

    public static void openForGroups(Screen parent, WaypointerConfig config,
                                     List<WaypointGroup> groups, String zoneLabel) {
        openForGroups(parent, config, WaypointerClient.manager(), groups, zoneLabel);
    }

    public static void openForGroups(Screen parent, WaypointerConfig config,
                                     ActiveGroupManager manager,
                                     List<WaypointGroup> groups, String zoneLabel) {
        int totalPts = groups.stream().mapToInt(WaypointGroup::size).sum();
        String title = Component.translatable(groups.size() == 1
                ? "waypointer.screen.export.subtitle.zone.one"
                : "waypointer.screen.export.subtitle.zone.many",
                zoneLabel, groups.size(), totalPts).getString();
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ExportScreen(parent, config, manager, groups, title));
    }

    @Override
    protected void init() {
        if (routePreviewWidget != null) routePreviewWidget.releaseResources();
        toggleSpecs.clear();
        toggleButtons.clear();
        routePickerToggleButton = null;
        routeSelectAllButton = null;
        previewPageButton = null;
        previewPreviousButton = null;
        previewNextButton = null;
        previewZoomResetButton = null;
        routePreviewWidget = null;
        routeListNavigation = null;

        registerToggle(ToggleKind.NAMES, optsBuilder.includeNames());
        registerToggle(ToggleKind.COLORS, optsBuilder.includeColors());
        registerToggle(ToggleKind.ZONE, optsBuilder.includeZone());
        registerToggle(ToggleKind.RADII, optsBuilder.includeRadii());
        registerToggle(ToggleKind.WAYPOINT_FLAGS, optsBuilder.includeWaypointFlags());
        registerToggle(ToggleKind.GROUP_META, optsBuilder.includeGroupMeta());

        computeLayout();

        if (previewEnabled) buildPreviewWidgets();

        if (previewEnabled && !widePreviewLayout) {
            previewPageButton = GuiTokens.styledButton(
                    contentX + contentW - HEADER_SWITCH_W,
                    panelY + PAD_OUTER, HEADER_SWITCH_W, BTN_H,
                    previewPageButtonLabel(), this::toggleCompactPreviewPage,
                    Tooltip.create(Component.translatable(
                            "waypointer.screen.export.preview.switch.tooltip")));
            addRenderableWidget(previewPageButton);
        }

        int labelY = labelRowY;
        int exportForW = exportForButtonWidth();
        int labelW = Math.max(80, contentW - exportForW - GAP);
        labelInput = new EditBox(font, contentX, labelY, labelW, BTN_H,
                Component.translatable("waypointer.screen.export.label"));
        labelInput.setMaxLength(WaypointCodec.Options.MAX_LABEL_CHARS);
        labelInput.setHint(Component.translatable("waypointer.screen.export.label.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        labelInput.setValue(currentLabel);
        labelInput.setResponder(this::onLabelChanged);
        addRenderableWidget(labelInput);

        this.exportForButton = GuiTokens.styledButton(
                contentX + labelW + GAP, labelY, exportForW, BTN_H,
                exportForButtonLabel(), this::openExportTargetMenu,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.export.target.tooltip")));
        addRenderableWidget(exportForButton);
        updateLabelInputState();

        layoutToggles();
        layoutRoutePickerControls();
        buildRouteListNavigation();

        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("gui.back").getString(), this::goBackToParent));
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("controls.reset").getString(),
                this::resetToConfigDefaults));

        Component copyLabel = Component.translatable("waypointer.export.copy_clipboard");
        Component codeBlockLabel = Component.translatable("waypointer.export.copy_code_block");
        int copyW = footerButtonWidth(copyLabel);
        int codeBlockCopyW = footerButtonWidth(codeBlockLabel);
        int rightClusterW = codeBlockCopyW + GAP + copyW;
        int contentRight = contentX + contentW;
        Tooltip codeBlockTooltip = Tooltip.create(Component.translatable(
                "waypointer.screen.export.copy_code_block.tooltip"));
        this.copyCodeBlockButton = GuiTokens.styledButton(
                contentRight - rightClusterW, footerY, codeBlockCopyW, BTN_H,
                codeBlockLabel, this::copyAsCodeBlock, codeBlockTooltip);
        this.copyButton = GuiTokens.styledButton(
                contentRight - copyW, footerY, copyW, BTN_H,
                copyLabel, this::copyToClipboard, null);

        GuiTokens.layoutFooter(panelX + controlPanelW, footerY, left, null, this::addRenderableWidget,
                font, contentX, PAD_OUTER + rightClusterW + GAP);
        addRenderableWidget(copyCodeBlockButton);
        addRenderableWidget(copyButton);

        applyPreviewPageVisibility();
        setInitialFocus(previewPageButton != null && compactPreviewPage
                ? previewPageButton : labelInput);
        clampRouteScrollOffset();
    }

    private void buildPreviewWidgets() {
        routePreviewWidget = new RoutePreviewWidget(
                routePreviewX, routePreviewY, routePreviewW, routePreviewH,
                previewScene, previewRouteCounter(), previewOrbit, previewZoom);
        routePreviewWidget.setRouteName(previewRouteName());
        addRenderableWidget(routePreviewWidget);

        int navY = routePreviewY + RoutePreviewWidget.NAV_BUTTON_Y_OFFSET;
        previewPreviousButton = GuiTokens.styledButton(
                routePreviewX + RoutePreviewWidget.NAV_BUTTON_INSET, navY, PREVIEW_NAV_W, BTN_H,
                Component.literal("\u25c0"), button -> navigatePreviewRoute(-1),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.export.preview.previous")));
        previewNextButton = GuiTokens.styledButton(
                routePreviewX + routePreviewW - PREVIEW_NAV_W - RoutePreviewWidget.NAV_BUTTON_INSET,
                navY, PREVIEW_NAV_W, BTN_H,
                Component.literal("\u25b6"), button -> navigatePreviewRoute(1),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.export.preview.next")));
        addRenderableWidget(previewPreviousButton);
        addRenderableWidget(previewNextButton);

        previewZoomResetButton = GuiTokens.styledButton(
                routePreviewX + routePreviewW
                        - RoutePreviewWidget.ZOOM_BUTTON_W - RoutePreviewWidget.ZOOM_BUTTON_INSET,
                routePreviewY + routePreviewH
                        - RoutePreviewWidget.ZOOM_BUTTON_H - RoutePreviewWidget.ZOOM_BUTTON_INSET,
                RoutePreviewWidget.ZOOM_BUTTON_W, RoutePreviewWidget.ZOOM_BUTTON_H,
                previewZoomLabel(), this::resetPreviewZoom,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.export.preview.zoom.tooltip")));
        addRenderableWidget(previewZoomResetButton);
    }

    private int footerButtonWidth(Component label) {
        return Math.max(60, font.width(label) + 16);
    }

    private void computeLayout() {
        reencode();

        widePreviewLayout = isWidePreviewLayout(previewEnabled, width);
        if (widePreviewLayout) {
            int available = Math.max(PANEL_MIN_W, width - PANEL_MARGIN * 2);
            controlPanelW = PANEL_MAX_W;
            int previewSectionW = MathUtil.clamp(
                    available - controlPanelW - PREVIEW_SPLIT_GAP,
                    PREVIEW_PANE_MIN_W + PAD_OUTER, PREVIEW_PANE_MAX_W + PAD_OUTER);
            panelW = Math.min(available,
                    controlPanelW + PREVIEW_SPLIT_GAP + previewSectionW);
            controlPanelW = panelW - PREVIEW_SPLIT_GAP - previewSectionW;
        } else {
            controlPanelW = MathUtil.clamp(
                    width - PANEL_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
            controlPanelW = Math.min(controlPanelW, Math.max(PANEL_MIN_W, width));
            panelW = controlPanelW;
        }
        contentW = controlPanelW - PAD_OUTER * 2;

        int includeH = includeRowsHeight();
        int routeH = isZoneExport() ? BLOCK_GAP + routePickerBlockHeight() : 0;
        int fixedH = panelFixedHeight(includeH, routeH);
        previewH = previewHeight(height, fixedH, previewLineHeight());

        panelH = fixedH + previewH;
        panelX = (width - panelW) / 2;
        panelY = Math.max(0, (height - panelH) / 2);
        contentX = panelX + PAD_OUTER;

        int top = panelY + PAD_OUTER;
        labelRowY = top + HEADER_H;
        includeHeadY = labelRowY + BTN_H + BLOCK_GAP;
        includeRowsY = includeHeadY + EXPORT_SETTINGS_HEADER_H;
        routeBlockY = includeRowsY + includeH + BLOCK_GAP;
        sizeY = includeRowsY + includeH + routeH + BLOCK_GAP;
        previewY = sizeY + LINE_H + GAP_TIGHT;
        footerY = previewY + previewH + BLOCK_GAP;

        if (widePreviewLayout) {
            routePreviewX = panelX + controlPanelW + PREVIEW_SPLIT_GAP;
            routePreviewY = panelY + PAD_OUTER;
            routePreviewW = widePreviewWidth(panelW, controlPanelW);
            routePreviewH = Math.max(48, panelH - PAD_OUTER * 2);
        } else {
            routePreviewX = contentX;
            routePreviewY = labelRowY;
            routePreviewW = contentW;
            routePreviewH = Math.max(48, footerY - routePreviewY - BLOCK_GAP);
        }
    }

    static boolean isWidePreviewLayout(int screenWidth) {
        return screenWidth >= WIDE_PREVIEW_BREAKPOINT;
    }

    static boolean isWidePreviewLayout(boolean previewEnabled, int screenWidth) {
        return previewEnabled && isWidePreviewLayout(screenWidth);
    }

    static int widePreviewWidth(int resolvedPanelWidth, int resolvedControlWidth) {
        return Math.max(1, resolvedPanelWidth - resolvedControlWidth
                - PREVIEW_SPLIT_GAP - PAD_OUTER);
    }

    static int panelFixedHeight(int includeRowsH, int routeBlockH) {
        return PAD_OUTER * 2 + HEADER_H + BTN_H + BLOCK_GAP + EXPORT_SETTINGS_HEADER_H
                + includeRowsH + routeBlockH + BLOCK_GAP + LINE_H + GAP_TIGHT
                + BLOCK_GAP + BTN_H;
    }

    static int previewHeight(int screenHeight, int fixedH, int previewLineH) {
        int oneLine = previewLineH + PREVIEW_INSET * 2;
        int preferred = PREVIEW_LINES * previewLineH + PREVIEW_INSET * 2;
        return MathUtil.clamp(screenHeight - fixedH, oneLine, preferred);
    }

    private int previewLineHeight() {
        return font.lineHeight + 1;
    }

    private int includeRowsHeight() {
        return includeGrid(contentW, toggleSpecs.size()).rowsPerColumn()
                * INCLUDE_ROW_PITCH - GAP_TIGHT;
    }

    private void registerToggle(ToggleKind kind, boolean initialValue) {
        toggleSpecs.add(new ToggleSpec(kind, initialValue));
    }

    private void layoutToggles() {
        IncludeGrid grid = includeGrid(contentW, toggleSpecs.size());

        for (int i = 0; i < toggleSpecs.size(); i++) {
            ToggleSpec spec = toggleSpecs.get(i);
            IncludeRow button = new IncludeRow(
                    contentX + (i / grid.rowsPerColumn()) * (grid.columnWidth() + GAP),
                    includeRowsY + (i % grid.rowsPerColumn()) * INCLUDE_ROW_PITCH,
                    grid.columnWidth(), spec, new TogglePressHandler(spec));
            button.active = toggleSupported(spec);
            button.setTooltip(toggleTooltip(spec));
            addRenderableWidget(button);
            toggleButtons.add(button);
        }
    }

    record IncludeGrid(int columnWidth, int rowsPerColumn) {}

    static IncludeGrid includeGrid(int availableWidth, int rowCount) {
        int rows = Math.max(1, rowCount);
        int colW = Math.min(INCLUDE_COL_MAX_W, (availableWidth - GAP) / 2);
        if (colW < INCLUDE_COL_MIN_W) return new IncludeGrid(Math.max(1, availableWidth), rows);
        return new IncludeGrid(colW, Math.min(rows, INCLUDE_ROWS_PER_COL));
    }

    private void layoutRoutePickerControls() {
        if (!isZoneExport()) return;

        int y = routeBlockY;
        int rightEdge = contentX + contentW;
        int selectAllX = rightEdge - ROUTE_PICKER_SELECT_ALL_W;
        int toggleX = Math.max(contentX, selectAllX - GAP - ROUTE_PICKER_TOGGLE_W);

        routePickerToggleButton = GuiTokens.styledButton(
                toggleX, y, ROUTE_PICKER_TOGGLE_W, BTN_H,
                routePickerToggleLabel(), this::toggleRoutePicker,
                Tooltip.create(Component.translatable(
                        "waypointer.screen.export.routes.toggle.tooltip")));
        routeSelectAllButton = GuiTokens.styledButton(
                selectAllX, y, ROUTE_PICKER_SELECT_ALL_W, BTN_H,
                Component.translatable("waypointer.screen.export.routes.select_all"),
                this::selectAllRoutes, Tooltip.create(Component.translatable(
                        "waypointer.screen.export.routes.select_all.tooltip")));
        addRenderableWidget(routePickerToggleButton);
        addRenderableWidget(routeSelectAllButton);
        refreshRoutePickerButtons();
    }

    private void buildRouteListNavigation() {
        if (!isZoneExport() || !routePickerExpanded || groups.isEmpty()) return;
        routeListNavigation = new ListNavigationWidget(
                contentX, routeListTop(), contentW, routeListHeight(),
                ROUTE_PICKER_INSET, ROUTE_ROW_PITCH, ROUTE_ROW_H,
                groups::size, this::initialRouteNavigationIndex,
                this::routeSelectionNarration, this::toggleRouteSelection,
                () -> routeScrollOffset, this::revealRouteIndex);
        addRenderableWidget(routeListNavigation);
    }

    private int initialRouteNavigationIndex() {
        if (previewGroupIndex >= 0 && previewGroupIndex < groups.size()) {
            return previewGroupIndex;
        }
        return Math.max(0, firstSelectedGroupIndex());
    }

    private Component routeSelectionNarration(int index) {
        if (index < 0 || index >= groups.size()) return Component.empty();
        return Component.translatable(routeSelection.isSelected(index)
                        ? "waypointer.screen.export.routes.selected"
                        : "waypointer.screen.export.routes.not_selected",
                routeDisplayName(groups.get(index)));
    }

    private void revealRouteIndex(int index) {
        int rowTop = index * ROUTE_ROW_PITCH;
        int rowBottom = rowTop + ROUTE_ROW_H;
        int viewportHeight = routeViewportHeight();
        if (rowTop < routeScrollOffset) {
            routeScrollOffset = rowTop;
        } else if (rowBottom > routeScrollOffset + viewportHeight) {
            routeScrollOffset = rowBottom - viewportHeight;
        }
        clampRouteScrollOffset();
    }

    private static Component toggleLabel(ToggleSpec spec) {
        return Component.translatable(toggleLabelTranslationKey(spec));
    }

    static String toggleMarker(boolean supported, boolean on) {
        if (!supported) return "[-]";
        return on ? "[x]" : "[ ]";
    }

    private static String toggleLabelTranslationKey(ToggleSpec spec) {
        return "waypointer.screen.export.toggle."
                + spec.kind.name().toLowerCase(Locale.ROOT) + ".label";
    }

    private Tooltip toggleTooltip(ToggleSpec spec) {
        return Tooltip.create(Component.translatable(
                "waypointer.screen.export.toggle."
                        + spec.kind.name().toLowerCase(Locale.ROOT)
                        + (toggleSupported(spec) ? ".tooltip" : ".unsupported"),
                exportTarget.displayName()));
    }


    private void onLabelChanged(String raw) {
        currentLabel = raw;
        optsBuilder.label(WaypointCodec.Options.sanitizeLabel(raw));
        if (routePreviewWidget != null) routePreviewWidget.setRouteName(previewRouteName());
        reencode();
    }

    private void openExportTargetMenu(Button button) {
        if (routePreviewWidget != null) routePreviewWidget.pauseOrbit();
        MinecraftCompat.setScreen(minecraft, new ExportTargetScreen(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (routePreviewWidget != null && !Minecraft.getInstance().isWindowActive()) {
            routePreviewWidget.pauseOrbit();
        }
    }

    private void selectExportTarget(WaypointExportCodec.Target target) {
        exportTarget = target;
        if (exportForButton != null) exportForButton.setMessage(exportForButtonLabel());
        if (!toggleButtons.isEmpty()) refreshToggleButtons();
        if (labelInput != null) updateLabelInputState();
        if (routePreviewWidget != null) routePreviewWidget.setRouteName(previewRouteName());
        reencode();
    }

    private void goBackToParent() {
        if (routePreviewWidget != null) routePreviewWidget.releaseResources();
        MinecraftCompat.setScreen(minecraft, parent);
    }

    private void resetToConfigDefaults() {
        optsBuilder = ExportPolicy.optionsFromConfig(config, selectedGroupsForExport());
        currentLabel = "";
        labelInput.setValue("");
        applyBuilderToToggleSpecs();
        refreshToggleButtons();
        reencode();
    }

    private void applyBuilderToToggleSpecs() {
        for (ToggleSpec spec : toggleSpecs) {
            spec.value = switch (spec.kind) {
                case NAMES -> optsBuilder.includeNames();
                case COLORS -> optsBuilder.includeColors();
                case ZONE -> optsBuilder.includeZone();
                case RADII -> optsBuilder.includeRadii();
                case WAYPOINT_FLAGS -> optsBuilder.includeWaypointFlags();
                case GROUP_META -> optsBuilder.includeGroupMeta();
            };
        }
    }

    private void reencode() {
        List<WaypointGroup> selected = selectedGroupsForExport();
        RouteLibraryMetadata metadata = captureLibraryMetadata(
                manager, selected, exportTarget);
        List<WaypointGroup> snapshot = new ArrayList<>();
        for (WaypointGroup group : selected) {
            if (group != null) snapshot.add(group.exportSnapshot());
        }
        WaypointCodec.Options options = optsBuilder.build();
        WaypointExportCodec.Target target = exportTarget;
        int generation = ++encodeGeneration;

        encodePending = true;
        encodingError = "";
        updateCopyButtons();

        if (!CodecWorker.run(
                () -> new EncodeResult(WaypointExportCodec.encode(
                        snapshot, options, target, metadata), ""),
                encoded -> applyEncodeResult(generation, encoded))) {
            applyEncodeResult(generation, new EncodeResult("",
                    Component.translatable("waypointer.codec.busy").getString()));
        }
    }

    static RouteLibraryMetadata captureLibraryMetadata(
            ActiveGroupManager manager,
            List<WaypointGroup> selected,
            WaypointExportCodec.Target target) {
        if (target != WaypointExportCodec.Target.WAYPOINTER) {
            return RouteLibraryMetadata.empty();
        }
        RouteLibraryMetadata captured = RouteLibraryMetadata.capture(manager, selected);
        // Folder membership is the sender's local organization. Sharing one
        // route must not recreate it on the recipient's side, and dropping it
        // keeps single-route codes out of the WPL wrapper (issue #114). The
        // public API keeps full metadata; this trim is a share-screen choice.
        if (selected.size() > 1) return captured;
        return new RouteLibraryMetadata(
                captured.manualColors(), List.of(), captured.paints());
    }

    private record EncodeResult(String code, String error) {}

    private void applyEncodeResult(int generation, EncodeResult result) {
        if (generation != encodeGeneration) return;
        this.encoded = result == null ? "" : result.code();
        this.encodingError = result == null ? "Export is not supported" : result.error();
        this.encodePending = false;
        updateCopyButtons();
    }

    private void updateCopyButtons() {
        boolean canCopy = !encodePending && encodingError.isEmpty() && !encoded.isEmpty();
        if (copyButton != null) copyButton.active = canCopy;
        if (copyCodeBlockButton != null) copyCodeBlockButton.active = canCopy;
    }

    private void refreshToggleButtons() {
        for (int i = 0; i < toggleSpecs.size(); i++) {
            ToggleSpec spec = toggleSpecs.get(i);
            Button button = toggleButtons.get(i);
            button.active = toggleSupported(spec);
            button.setTooltip(toggleTooltip(spec));
        }
    }

    private void refreshRoutePickerButtons() {
        if (routePickerToggleButton != null) {
            routePickerToggleButton.setMessage(routePickerToggleLabel());
        }
        if (routeSelectAllButton != null) {
            routeSelectAllButton.active = hasExcludedRoutes();
        }
    }

    private Component routePickerToggleLabel() {
        return GuiTokens.colored(Component.translatable(routePickerExpanded
                ? "waypointer.screen.export.routes.hide"
                : "waypointer.screen.export.routes.show"), ACCENT);
    }

    private void toggleRoutePicker(Button button) {
        routePickerExpanded = !routePickerExpanded;
        clampRouteScrollOffset();
        rebuildWidgets();
    }

    private void selectAllRoutes(Button button) {
        routeSelection.selectAll();
        refreshRoutePickerButtons();
        refreshPreviewRouteCounter();
        reencode();
    }

    static boolean shouldStartRoutePickerExpanded(int groupCount) {
        return groupCount > 1 && groupCount < ROUTE_PICKER_COLLAPSE_THRESHOLD;
    }

    private void toggleRouteSelection(int idx) {
        if (!routeSelection.toggle(idx)) return;
        reconcilePreviewRouteAfterSelectionChange();
        refreshRoutePickerButtons();
        reencode();
    }

    private Component previewPageButtonLabel() {
        return Component.translatable(compactPreviewPage
                ? "waypointer.screen.export.preview.options"
                : "waypointer.screen.export.preview.show");
    }

    private void toggleCompactPreviewPage(Button button) {
        compactPreviewPage = !compactPreviewPage;
        button.setMessage(previewPageButtonLabel());
        applyPreviewPageVisibility();
        if (compactPreviewPage) {
            setFocused(button);
            button.setFocused(true);
        } else {
            setFocused(labelInput);
            labelInput.setFocused(true);
        }
    }

    private void applyPreviewPageVisibility() {
        boolean optionsVisible = !previewEnabled || widePreviewLayout || !compactPreviewPage;
        boolean previewVisible = previewEnabled && (widePreviewLayout || compactPreviewPage);
        labelInput.visible = optionsVisible;
        exportForButton.visible = optionsVisible;
        for (Button toggle : toggleButtons) toggle.visible = optionsVisible;
        if (routePickerToggleButton != null) routePickerToggleButton.visible = optionsVisible;
        if (routeSelectAllButton != null) routeSelectAllButton.visible = optionsVisible;
        if (routeListNavigation != null) {
            routeListNavigation.visible = optionsVisible && routePickerExpanded;
            routeListNavigation.active = routeListNavigation.visible;
        }
        if (routePreviewWidget == null) return;

        routePreviewWidget.setPreviewVisible(previewVisible);
        boolean showNavigation = previewVisible && selectedGroupCount() > 1;
        routePreviewWidget.setNavigationVisible(showNavigation);
        previewPreviousButton.visible = showNavigation;
        previewNextButton.visible = showNavigation;
        previewPreviousButton.active = showNavigation;
        previewNextButton.active = showNavigation;
        refreshPreviewZoomButton();
    }

    private void refreshPreviewZoomButton() {
        if (previewZoomResetButton == null || routePreviewWidget == null) return;
        boolean shown = routePreviewWidget.visible && routePreviewWidget.zoomed();
        previewZoomResetButton.visible = shown;
        previewZoomResetButton.active = shown;
        if (shown) previewZoomResetButton.setMessage(previewZoomLabel());
    }

    private Component previewZoomLabel() {
        return Component.translatable("waypointer.screen.export.preview.zoom",
                routePreviewWidget == null
                        ? RoutePreviewWidget.zoomFactorText(1.0)
                        : routePreviewWidget.zoomLabel());
    }

    private void resetPreviewZoom(Button button) {
        if (routePreviewWidget == null) return;
        routePreviewWidget.resetZoom();
        refreshPreviewZoomButton();
        setFocused(null);
    }

    private void navigatePreviewRoute(int delta) {
        int next = routeSelection.navigate(previewGroupIndex, delta);
        if (next == previewGroupIndex || next < 0) return;
        previewGroupIndex = next;
        previewScene = buildPreviewScene();
        if (routePreviewWidget != null) {
            routePreviewWidget.setScene(previewScene, previewRouteCounter());
            routePreviewWidget.setRouteName(previewRouteName());
            refreshPreviewZoomButton();
        }
    }

    private void reconcilePreviewRouteAfterSelectionChange() {
        if (routeSelection.isSelected(previewGroupIndex)) {
            refreshPreviewRouteCounter();
            return;
        }
        int replacement = routeSelection.replacementFor(previewGroupIndex);
        previewGroupIndex = replacement >= 0 ? replacement : firstSelectedGroupIndex();
        previewScene = buildPreviewScene();
        if (routePreviewWidget != null) {
            routePreviewWidget.setScene(previewScene, previewRouteCounter());
            routePreviewWidget.setRouteName(previewRouteName());
            applyPreviewPageVisibility();
        }
    }

    private void refreshPreviewRouteCounter() {
        if (routePreviewWidget != null) {
            routePreviewWidget.setRouteCounter(previewRouteCounter());
            routePreviewWidget.setRouteName(previewRouteName());
            applyPreviewPageVisibility();
        }
    }

    private RoutePreviewScene buildPreviewScene() {
        if (!previewEnabled || groups.isEmpty()) return RoutePreviewScene.empty();
        int safeIndex = MathUtil.clamp(previewGroupIndex, 0, groups.size() - 1);
        WaypointGroup group = groups.get(safeIndex);
        return RoutePreviewScene.build(group, config, previewLoadedLevel(group));
    }

    private String previewRouteName() {
        return ExportPolicy.previewRouteName(previewScene.routeName(), currentLabel,
                exportTarget, selectedGroupCount());
    }

    private ClientLevel previewLoadedLevel(WaypointGroup group) {
        if (group.routeKind() == WaypointGroup.RouteKind.DUNGEON) return null;
        if (WaypointerClient.manager() == null || WaypointerClient.manager().currentZone() == null) {
            return null;
        }
        if (!group.zoneId().equals(WaypointerClient.manager().currentZone().id())) return null;
        return Minecraft.getInstance().level;
    }

    private int firstSelectedGroupIndex() {
        return routeSelection.firstSelectedIndex();
    }

    private String previewRouteCounter() {
        return routeSelection.counter(previewGroupIndex);
    }

    private boolean hasExcludedRoutes() {
        return routeSelection.hasExcludedRoutes();
    }

    private boolean isZoneExport() {
        return groups.size() > 1;
    }

    private int selectedGroupCount() {
        return routeSelection.count();
    }

    private int selectedWaypointCount() {
        int total = 0;
        List<WaypointGroup> selected = selectedGroupsForExport();
        for (WaypointGroup group : selected) {
            total += group.size();
        }
        return total;
    }

    private List<WaypointGroup> selectedGroupsForExport() {
        return routeSelection.selectedGroups(groups);
    }

    private static String routeDisplayName(WaypointGroup group) {
        String name = group.name().trim();
        if (!name.isEmpty()) return name;
        return displayZoneLabel(group.zoneId());
    }

    private static String displayZoneLabel(String zoneId) {
        DungeonRoomCatalogEntry catalogEntry = DungeonRoomData.entry(zoneId);
        if (catalogEntry != null) return DUNGEON_ROOM_LABEL_PREFIX + catalogEntry.displayName();
        return Zone.fromId(zoneId).displayName();
    }

    private void updateLabelInputState() {
        labelInput.active = exportTarget.supportsLabel();
        labelInput.setTooltip(Tooltip.create(exportTarget.supportsLabel()
                ? Component.translatable("waypointer.screen.export.label.tooltip")
                : Component.translatable("waypointer.screen.export.label.unsupported",
                        exportTarget.displayName())));
    }

    private Component exportForButtonLabel() {
        return GuiTokens.colored(Component.translatable("waypointer.screen.export.target",
                exportTarget.displayName()), ACCENT);
    }

    private int exportForButtonWidth() {
        int widest = 0;
        for (WaypointExportCodec.Target target : WaypointExportCodec.Target.values()) {
            widest = Math.max(widest, font.width(Component.translatable(
                    "waypointer.screen.export.target", target.displayName())));
        }
        return MathUtil.clamp(widest + 16, EXPORT_FOR_MIN_W, EXPORT_FOR_MAX_W);
    }

    private void copyToClipboard(Button button) {
        minecraft.keyboardHandler.setClipboard(encoded);
        copyFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyButton.setMessage(GuiTokens.colored(
                Component.translatable("waypointer.common.copied"), GuiTokens.SUCCESS));
    }

    private void copyAsCodeBlock(Button button) {
        minecraft.keyboardHandler.setClipboard(ExportPolicy.codeBlockPayload(encoded));
        copyCodeBlockFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyCodeBlockButton.setMessage(GuiTokens.colored(
                Component.translatable("waypointer.common.copied"), GuiTokens.SUCCESS));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        updateCopyFeedback();

        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);

        int top = panelY + PAD_OUTER;
        int headerWidth = !widePreviewLayout && previewPageButton != null
                ? contentW - HEADER_SWITCH_W - GAP
                : contentW;
        g.text(font, font.plainSubstrByWidth(getTitle().getString(), headerWidth),
                contentX, top, TEXT, false);
        g.text(font, font.plainSubstrByWidth(subtitle, headerWidth),
                contentX, top + LINE_H, TEXT_DIM, false);

        if (widePreviewLayout || !compactPreviewPage) {
            drawClipped(g, Component.translatable("waypointer.screen.export.settings").getString(),
                    includeHeadY, TEXT_DIM);
            SettingsHelp help = settingsHelp();
            drawClipped(g, help.text(), includeHeadY + LINE_H, help.color());

            if (isZoneExport()) renderRoutePicker(g, mouseX, mouseY);

            drawSizeSummary(g, contentX, sizeY);
            drawPreview(g, contentX, previewY, contentX + contentW, previewY + previewH);
        }

        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    private void drawClipped(GuiGraphicsExtractor g, String text, int y, int color) {
        g.text(font, font.plainSubstrByWidth(text, contentW), contentX, y, color, false);
    }

    private void updateCopyFeedback() {
        long now = System.currentTimeMillis();
        if (copyFeedbackUntil != 0 && now > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            copyButton.setMessage(Component.translatable("waypointer.export.copy_clipboard"));
        }
        if (copyCodeBlockFeedbackUntil != 0 && now > copyCodeBlockFeedbackUntil) {
            copyCodeBlockFeedbackUntil = 0;
            copyCodeBlockButton.setMessage(Component.translatable(
                    "waypointer.export.copy_code_block"));
        }
    }

    private void renderRoutePicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        clampRouteScrollOffset();

        drawClipped(g, Component.translatable("waypointer.screen.export.routes").getString(),
                routeBlockY, TEXT_DIM);
        drawClipped(g, Component.translatable(
                "waypointer.screen.export.routes.summary",
                selectedGroupCount(), groups.size(), selectedWaypointCount()).getString(),
                routeBlockY + LINE_H, TEXT_MUTED);

        if (!routePickerExpanded) return;

        int x1 = contentX;
        int y1 = routeListTop();
        int x2 = contentX + contentW;
        int y2 = y1 + routeListHeight();
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        g.enableScissor(x1, y1, x2, y2);

        int rowY = y1 + ROUTE_PICKER_INSET - routeScrollOffset;
        for (int i = 0; i < groups.size(); i++, rowY += ROUTE_ROW_PITCH) {
            if (rowY + ROUTE_ROW_H < y1 || rowY > y2) continue;
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= rowY + ROUTE_ROW_H;
            renderRouteRow(g, groups.get(i), i, x1 + 2, rowY, x2 - 2, hovered);
        }
        g.disableScissor();
        drawRouteScrollbar(g, x1, y1, x2, y2);
    }

    private static final int ROW_SELECTED_TINT = 0x1C4FB3C4;

    private void renderRouteRow(GuiGraphicsExtractor g, WaypointGroup group, int index,
                                int x1, int y1, int x2, boolean hovered) {
        boolean selected = routeSelection.isSelected(index);
        int rowBottom = y1 + ROUTE_ROW_H;
        int bg = selected ? ROW_SELECTED_TINT : hovered ? GuiTokens.HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBottom, bg);
        if (selected) g.fill(x1, y1, x1 + 2, rowBottom, ACCENT);

        String marker = selected ? "[x]" : "[ ]";
        int markerColor = selected ? ACCENT : TEXT_MUTED;
        int textColor = selected ? TEXT : TEXT_MUTED;
        int metaColor = selected ? TEXT_DIM : TEXT_MUTED;
        int textX = x1 + GAP;
        int centerY = y1 + (ROUTE_ROW_H - 8) / 2;
        g.text(font, marker, textX, centerY, markerColor, false);

        String rawMeta = displayZoneLabel(group.zoneId()) + "  " + group.size() + " pts  "
                + group.loadMode().name().toLowerCase(Locale.ROOT);
        int metaRight = x2 - GAP - (maxRouteScrollOffset() > 0 ? ROUTE_SCROLLBAR_W + GAP : 0);
        int nameX = textX + font.width(marker) + GAP;
        int metaMaxW = Math.max(40, metaRight - nameX - 60 - GAP);
        String meta = font.plainSubstrByWidth(rawMeta, metaMaxW);
        int metaW = font.width(meta);
        int nameMaxW = Math.max(20, metaRight - metaW - GAP - nameX);
        String name = font.plainSubstrByWidth(routeDisplayName(group), nameMaxW);
        g.text(font, name, nameX, centerY, textColor, false);
        g.text(font, meta, metaRight - metaW, centerY, metaColor, false);
    }

    private void drawRouteScrollbar(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int maxScroll = maxRouteScrollOffset();
        if (maxScroll <= 0) return;

        int trackX = x2 - ROUTE_SCROLLBAR_W - 2;
        int trackY = y1 + 3;
        int trackH = Math.max(1, y2 - y1 - 6);
        int viewport = routeViewportHeight();
        int content = Math.max(viewport, routeContentHeight());
        int thumbH = Math.max(10, trackH * viewport / content);
        int travel = Math.max(0, trackH - thumbH);
        int thumbY = trackY + (maxScroll == 0 ? 0 : travel * routeScrollOffset / maxScroll);
        g.fill(trackX, trackY, trackX + ROUTE_SCROLLBAR_W, trackY + trackH, GuiTokens.BORDER);
        g.fill(trackX, thumbY, trackX + ROUTE_SCROLLBAR_W, thumbY + thumbH, TEXT_MUTED);
    }

    private void drawSizeSummary(GuiGraphicsExtractor g, int x, int y) {
        if (encodePending) {
            drawClipped(g, Component.translatable(
                    "waypointer.screen.export.encoding").getString(), y, TEXT_MUTED);
            return;
        }
        if (!encodingError.isEmpty()) {
            drawClipped(g, encodingError, y, GuiTokens.DANGER);
            return;
        }
        int right = contentX + contentW;
        int cursor = drawSizeLine(g, font, x, y, right, encoded);

        String sanitized = WaypointCodec.Options.sanitizeLabel(currentLabel);
        if (!exportTarget.supportsLabel() || sanitized.isEmpty()) return;

        int chipX = cursor + font.width(SUMMARY_SEPARATOR);
        String chip = font.plainSubstrByWidth("\"" + sanitized + "\"", Math.max(0, right - chipX));
        if (chip.isEmpty()) return;
        g.text(font, SUMMARY_SEPARATOR, cursor, y, TEXT_MUTED, false);
        g.text(font, chip, chipX, y, TEXT_DIM, false);
    }

    static int drawSizeLine(GuiGraphicsExtractor g, Font font, int x, int y, int right, String payload) {
        ExportPolicy.FitSummary fit = ExportPolicy.fitSummary(payload);

        String chars = Component.translatable("waypointer.export.characters",
                payload == null ? 0 : payload.length()).getString();
        g.text(font, chars, x, y, TEXT_DIM, false);

        int separatorX = x + font.width(chars);
        int fitX = separatorX + font.width(SUMMARY_SEPARATOR);
        int fitColor = fit.commandOk() ? GuiTokens.SUCCESS
                : fit.chatOk() ? GuiTokens.WARNING : GuiTokens.DANGER;
        String fitLine = font.plainSubstrByWidth(
                Component.translatable(fit.messageKey()).getString(), Math.max(0, right - fitX));
        if (fitLine.isEmpty()) return separatorX;

        g.text(font, SUMMARY_SEPARATOR, separatorX, y, TEXT_MUTED, false);
        g.text(font, fitLine, fitX, y, fitColor, false);
        return fitX + font.width(fitLine);
    }

    private record SettingsHelp(String text, int color) {}

    private SettingsHelp settingsHelp() {
        if (showSubwaypointCompatibilityWarning()) {
            return new SettingsHelp(Component.translatable(
                    "waypointer.screen.export.settings_help.subwaypoints").getString(),
                    GuiTokens.WARNING);
        }
        if (exportTarget != WaypointExportCodec.Target.WAYPOINTER) {
            return new SettingsHelp(Component.translatable(
                    "waypointer.screen.export.settings_help.unavailable",
                    exportTarget.displayName()).getString(), TEXT_DIM);
        }
        if (!optsBuilder.includeZone()) {
            return new SettingsHelp(Component.translatable(
                    "waypointer.screen.export.settings_help.no_island").getString(), TEXT_DIM);
        }
        return new SettingsHelp(Component.translatable(
                "waypointer.screen.export.settings_help.shorter").getString(), TEXT_MUTED);
    }

    private boolean showSubwaypointCompatibilityWarning() {
        return ExportPolicy.showSubwaypointWarning(exportTarget, selectedGroupsForExport());
    }

    private void drawPreview(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, GuiTokens.BORDER);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, SURFACE_SUBTLE);

        int innerX = x1 + PREVIEW_INSET;
        int innerY = y1 + PREVIEW_INSET;
        int innerW = x2 - x1 - PREVIEW_INSET * 2;
        int lineH = previewLineHeight();
        if (encodePending) {
            g.text(font, Component.translatable("waypointer.screen.export.encoding").getString(),
                    innerX, innerY, TEXT_MUTED, false);
            return;
        }
        List<FormattedCharSequence> lines = font.split(FormattedText.of(encoded), innerW);

        int available = (y2 - y1 - PREVIEW_INSET * 2) / lineH;
        int shown = lines.size() <= available ? lines.size() : Math.max(0, available - 1);

        int y = innerY;
        for (int i = 0; i < shown; i++, y += lineH) {
            g.text(font, lines.get(i), innerX, y, TEXT, false);
        }
        if (shown < lines.size()) {
            String ellipsis = ExportPolicy.previewOverflowText(lines.size() - shown);
            g.text(font, ellipsis, innerX, y, TEXT_MUTED, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;
        if (!isZoneExport() || !routePickerExpanded) return false;

        int idx = routeIndexAt(event.x(), event.y());
        if (idx < 0) return false;
        if (routeListNavigation != null) routeListNavigation.setCursor(idx);
        toggleRouteSelection(idx);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (routePreviewWidget != null
                && routePreviewWidget.scrollZoom(mouseX, mouseY, vert)) {
            refreshPreviewZoomButton();
            return true;
        }
        if (!isZoneExport() || !routePickerExpanded || !isInsideRouteList(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horiz, vert);
        }
        int maxScroll = maxRouteScrollOffset();
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, horiz, vert);
        routeScrollOffset = MathUtil.clamp(
                routeScrollOffset - (int) (vert * ROUTE_ROW_PITCH), 0, maxScroll);
        return true;
    }

    private int routeIndexAt(double mouseX, double mouseY) {
        if (!isInsideRouteList(mouseX, mouseY)) return -1;
        int localY = (int) (mouseY - routeListTop() - ROUTE_PICKER_INSET + routeScrollOffset);
        if (localY < 0) return -1;
        int withinRow = localY % ROUTE_ROW_PITCH;
        if (withinRow > ROUTE_ROW_H) return -1;
        int idx = localY / ROUTE_ROW_PITCH;
        return idx >= 0 && idx < groups.size() ? idx : -1;
    }

    private boolean isInsideRouteList(double mouseX, double mouseY) {
        if (!isZoneExport() || !routePickerExpanded) return false;
        int x1 = contentX;
        int y1 = routeListTop();
        int x2 = contentX + contentW;
        int y2 = y1 + routeListHeight();
        return mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
    }

    private static int routePickerHeaderHeight() {
        return Math.max(BTN_H, LINE_H * 2);
    }

    private int routeListTop() {
        return routeBlockY + routePickerHeaderHeight() + GAP;
    }

    private int routePickerBlockHeight() {
        return routePickerHeaderHeight()
                + (routePickerExpanded ? GAP + routeListHeight() : 0);
    }

    private int routeListHeight() {
        return routeVisibleRowCount() * ROUTE_ROW_PITCH + ROUTE_PICKER_INSET * 2;
    }

    private int routeVisibleRowCount() {
        int byCount = Math.min(MAX_EXPANDED_ROUTE_ROWS, Math.max(1, groups.size()));
        int fixed = PAD_OUTER * 2 + HEADER_H + BTN_H + BLOCK_GAP + EXPORT_SETTINGS_HEADER_H
                + includeRowsHeight() + BLOCK_GAP + routePickerHeaderHeight() + GAP
                + ROUTE_PICKER_INSET * 2
                + BLOCK_GAP + LINE_H + GAP_TIGHT
                + previewLineHeight() + PREVIEW_INSET * 2
                + BLOCK_GAP + BTN_H;
        int bySpace = Math.max(1, (height - fixed) / ROUTE_ROW_PITCH);
        return Math.max(1, Math.min(byCount, bySpace));
    }

    private int routeViewportHeight() {
        return routeVisibleRowCount() * ROUTE_ROW_PITCH;
    }

    private int routeContentHeight() {
        return groups.size() * ROUTE_ROW_PITCH;
    }

    private int maxRouteScrollOffset() {
        return Math.max(0, routeContentHeight() - routeViewportHeight());
    }

    private void clampRouteScrollOffset() {
        routeScrollOffset = MathUtil.clamp(routeScrollOffset, 0, maxRouteScrollOffset());
    }


    private boolean toggleSupported(ToggleSpec spec) {
        return switch (spec.kind) {
            case NAMES -> exportTarget.supportsNames();
            case COLORS -> exportTarget.supportsColors();
            case ZONE -> exportTarget.supportsIslandChoice();
            case RADII -> exportTarget.supportsRadii();
            case WAYPOINT_FLAGS -> exportTarget.supportsWaypointFlags();
            case GROUP_META -> exportTarget.supportsGroupMeta();
        };
    }

    private void applyToggleValue(ToggleSpec spec) {
        switch (spec.kind) {
            case NAMES -> optsBuilder.includeNames(spec.value);
            case COLORS -> optsBuilder.includeColors(spec.value);
            case ZONE -> optsBuilder.includeZone(spec.value);
            case RADII -> optsBuilder.includeRadii(spec.value);
            case WAYPOINT_FLAGS -> optsBuilder.includeWaypointFlags(spec.value);
            case GROUP_META -> optsBuilder.includeGroupMeta(spec.value);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (routePreviewWidget != null) routePreviewWidget.releaseResources();
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        if (routePreviewWidget != null) routePreviewWidget.releaseResources();
        super.removed();
    }

    private static final class ExportTargetScreen extends Screen {
        private static final int MENU_W = 220;
        private static final int PANEL_W = MENU_W + PAD_OUTER * 2;

        private final ExportScreen owner;

        private int panelX;
        private int panelY;
        private int panelH;

        ExportTargetScreen(ExportScreen owner) {
            super(Component.translatable("waypointer.screen.export.target.title"));
            this.owner = owner;
        }

        @Override
        protected void init() {
            int targets = WaypointExportCodec.Target.values().length;
            int rowsH = targets * INCLUDE_ROW_PITCH - GAP_TIGHT;
            panelH = PAD_OUTER * 2 + LINE_H + BLOCK_GAP + rowsH + BLOCK_GAP + BTN_H;
            panelX = (width - PANEL_W) / 2;
            panelY = Math.max(0, (height - panelH) / 2);

            int x = panelX + PAD_OUTER;
            int y = panelY + PAD_OUTER + LINE_H + BLOCK_GAP;

            for (WaypointExportCodec.Target target : WaypointExportCodec.Target.values()) {
                addRenderableWidget(new TargetRow(x, y, target));
                y += INCLUDE_ROW_PITCH;
            }

            addRenderableWidget(GuiTokens.styledButton(
                    x, panelY + panelH - PAD_OUTER - BTN_H, MENU_W, BTN_H,
                    Component.translatable("gui.back"), this::returnToOwner, null));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            g.fill(0, 0, width, height, 0x80000000);
            g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, SURFACE);
            g.text(font, getTitle(), panelX + PAD_OUTER, panelY + PAD_OUTER, TEXT, false);
            super.extractRenderState(g, mouseX, mouseY, partial);
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        public void onClose() { MinecraftCompat.setScreen(minecraft, owner); }

        private void returnToOwner(Button button) {
            MinecraftCompat.setScreen(minecraft, owner);
        }

        private static String targetTooltipKey(WaypointExportCodec.Target target) {
            return switch (target) {
                case WAYPOINTER -> "waypointer.screen.export.target.waypointer.tooltip";
                case SKYBLOCKER -> "waypointer.screen.export.target.skyblocker.tooltip";
                case SKYTILS -> "waypointer.screen.export.target.skytils.tooltip";
                case SKYHANNI -> "waypointer.screen.export.target.skyhanni.tooltip";
            };
        }

        private final class TargetRow extends MarkerRow {
            private final WaypointExportCodec.Target target;

            TargetRow(int x, int y, WaypointExportCodec.Target target) {
                super(x, y, MENU_W, Component.literal(target.displayName()), button -> {
                    owner.selectExportTarget(target);
                    MinecraftCompat.setScreen(Minecraft.getInstance(), owner);
                });
                this.target = target;
                setTooltip(Tooltip.create(Component.translatable(targetTooltipKey(target))));
            }

            @Override
            protected String marker() {
                return chosen() ? "[x]" : "[ ]";
            }

            @Override
            protected boolean chosen() {
                return target == owner.exportTarget;
            }
        }
    }

    private enum ToggleKind {
        NAMES,
        COLORS,
        ZONE,
        RADII,
        WAYPOINT_FLAGS,
        GROUP_META
    }

    private static final class ToggleSpec {
        final ToggleKind kind;
        boolean value;

        ToggleSpec(ToggleKind kind, boolean value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private abstract static class MarkerRow extends GuiTokens.StyledButton {
        MarkerRow(int x, int y, int width, Component label, Button.OnPress onPress) {
            super(x, y, width, BTN_H, label, onPress);
        }

        protected abstract String marker();

        protected abstract boolean chosen();

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            boolean highlighted = active && isHoveredOrFocused();
            GuiTokens.drawControlFrame(g, getX(), getY(), getWidth(), getHeight(),
                    active, highlighted, isFocused());

            var font = Minecraft.getInstance().font;
            String marker = marker();
            int textY = GuiTokens.opticalTextY(getY(), getHeight());
            int markerX = getX() + INCLUDE_ROW_INSET;
            int labelX = markerX + font.width(marker) + GAP_TIGHT;
            int labelMaxW = Math.max(0, getX() + getWidth() - INCLUDE_ROW_INSET - labelX);

            int markerColor = !active ? TEXT_MUTED : chosen() ? ACCENT : TEXT_MUTED;
            int labelColor = !active ? TEXT_MUTED : chosen() ? TEXT : TEXT_DIM;
            g.text(font, marker, markerX, textY, markerColor, false);
            g.text(font, font.plainSubstrByWidth(getMessage().getString(), labelMaxW),
                    labelX, textY, labelColor, false);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.literal(marker() + " ").append(getMessage());
        }
    }

    private static final class IncludeRow extends MarkerRow {
        private final ToggleSpec spec;

        IncludeRow(int x, int y, int width, ToggleSpec spec, Button.OnPress onPress) {
            super(x, y, width, toggleLabel(spec), onPress);
            this.spec = spec;
        }

        @Override
        protected String marker() {
            return toggleMarker(active, spec.value);
        }

        @Override
        protected boolean chosen() {
            return spec.value;
        }
    }

    private final class TogglePressHandler implements Button.OnPress {
        private final ToggleSpec spec;

        TogglePressHandler(ToggleSpec spec) {
            this.spec = spec;
        }

        @Override
        public void onPress(Button button) {
            button.active = toggleSupported(spec);
            if (!button.active) return;
            spec.value = !spec.value;
            applyToggleValue(spec);
            button.setTooltip(toggleTooltip(spec));
            reencode();
        }
    }
}
