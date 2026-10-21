package com.babbur.waypointer.screen;

import com.babbur.waypointer.i18n.Counts;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;

public final class ExportScreen extends Screen {

    private static final String DUNGEON_ROOM_LABEL_PREFIX = "Dungeons: ";
    private static final long COPIED_FEEDBACK_MS = 1500;
    private static final String SUMMARY_SEPARATOR = "  ·  ";

    private static final int LINE_H = 10;
    private static final int LABEL_H = LINE_H + 6;
    private static final int TIGHT_HEIGHT = 300;

    private static final int PANEL_MARGIN = 16;
    private static final int PANEL_MIN_W = 260;
    private static final int PANEL_MAX_W = 480;

    private static final int FORMAT_LABEL_PAD = 16;
    private static final int FOOTER_LABEL_PAD = 16;

    private static final int ROUTE_PICKER_TOGGLE_W = 86;
    private static final int ROUTE_PICKER_SELECT_ALL_W = 76;
    private static final int ROUTE_PICKER_COLLAPSE_THRESHOLD = 9;
    private static final int MAX_EXPANDED_ROUTE_ROWS = 6;
    private static final int ROUTE_HEADER_H = BTN_H;
    private static final int ROUTE_ROW_H = 24;
    private static final int ROUTE_ROW_GAP = 2;
    private static final int ROUTE_ROW_PITCH = ROUTE_ROW_H + ROUTE_ROW_GAP;
    private static final int ROUTE_PICKER_INSET = 4;
    private static final int ROUTE_SCROLLBAR_W = 3;
    private static final int ROW_SELECTED_TINT = 0x1C4FB3C4;

    private static final int CODE_INSET = 8;
    private static final int CODE_LINES = 4;

    private final Screen parent;
    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final List<WaypointGroup> groups;
    private final List<WaypointExportCodec.Target> formats;
    private final ExportRouteSelection routeSelection;
    private final String subtitle;
    private final boolean preserveFolderMetadata;
    private final ScopeKind scopeKind;
    private final String scopeLabel;

    private WaypointExportCodec.Target exportTarget = WaypointExportCodec.Target.WAYPOINTER;

    private final List<Button> formatButtons = new ArrayList<>();
    private Button routePickerToggleButton;
    private Button routeSelectAllButton;
    private Button copyButton;
    private Button copyCodeBlockButton;
    private ListNavigationWidget routeListNavigation;
    private boolean routePickerExpanded;
    private int routeScrollOffset;
    private long copyFeedbackUntil = 0L;
    private long copyCodeBlockFeedbackUntil = 0L;

    private String encoded = "";
    private String encodingError = "";
    private int encodeGeneration;
    private boolean encodePending;

    private LayoutPolicy layout;

    public ExportScreen(Screen parent, WaypointerConfig config, List<WaypointGroup> groups, String subtitle) {
        this(parent, config, null, groups, subtitle, false, ScopeKind.STATIC, "");
    }

    public ExportScreen(Screen parent, WaypointerConfig config, ActiveGroupManager manager,
                        List<WaypointGroup> groups, String subtitle) {
        this(parent, config, manager, groups, subtitle, false, ScopeKind.STATIC, "");
    }

    private ExportScreen(Screen parent, WaypointerConfig config, ActiveGroupManager manager,
                         List<WaypointGroup> groups, String subtitle,
                         boolean preserveFolderMetadata, ScopeKind scopeKind,
                         String scopeLabel) {
        super(Component.translatable("waypointer.screen.export.title"));
        this.parent = parent;
        this.config = config;
        this.manager = manager;
        this.groups = groups;
        this.formats = formatsFor(groups);
        this.routeSelection = new ExportRouteSelection(groups.size());
        this.subtitle = subtitle;
        this.preserveFolderMetadata = preserveFolderMetadata;
        this.scopeKind = scopeKind;
        this.scopeLabel = scopeLabel;
        this.routePickerExpanded = shouldStartRoutePickerExpanded(groups.size());
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
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                forGroups(parent, config, manager, groups, zoneLabel));
    }

    static ExportScreen forGroups(Screen parent, WaypointerConfig config, ActiveGroupManager manager,
                                  List<WaypointGroup> groups, String zoneLabel) {
        return new ExportScreen(parent, config, manager, groups, "", false, ScopeKind.ZONE, zoneLabel);
    }

    public static void openForSelectedGroups(Screen parent, WaypointerConfig config,
                                             ActiveGroupManager manager,
                                             List<WaypointGroup> groups) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ExportScreen(parent, config, manager, groups, "", false,
                        ScopeKind.SELECTION, ""));
    }

    public static void openForFolder(Screen parent, WaypointerConfig config,
                                     ActiveGroupManager manager, String folderName,
                                     List<WaypointGroup> groups) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ExportScreen(parent, config, manager, groups, "", true,
                        ScopeKind.FOLDER, folderName));
    }

    static List<WaypointExportCodec.Target> formatsFor(List<WaypointGroup> groups) {
        boolean rooms = allDungeonRooms(groups);
        return Arrays.stream(WaypointExportCodec.Target.values())
                .filter(target -> !rooms || target.supportsDungeonRooms())
                .toList();
    }

    static boolean allDungeonRooms(List<WaypointGroup> groups) {
        return !groups.isEmpty() && groups.stream()
                .allMatch(group -> group.routeKind() == WaypointGroup.RouteKind.DUNGEON);
    }

    @Override
    protected void init() {
        formatButtons.clear();
        routePickerToggleButton = null;
        routeSelectAllButton = null;
        routeListNavigation = null;
        if (encodeGeneration == 0) reencode();

        layout = layoutPolicy(width, height, formats.size(), widestFormatLabel(),
                isMultiRoute(), routePickerExpanded, groups.size(), codeLineHeight(),
                font.width(Component.translatable("gui.back")), copyLabelWidth());

        buildFormatButtons();
        buildRoutePicker();
        buildFooter();
        clampRouteScrollOffset();
        setInitialFocus(copyButton);
    }

    private void buildFormatButtons() {
        int columns = layout.formatColumns();
        int buttonW = Math.max(1, (layout.contentWidth() - (columns - 1) * GAP) / columns);
        for (int i = 0; i < formats.size(); i++) {
            WaypointExportCodec.Target target = formats.get(i);
            int column = i % columns;
            int row = i / columns;
            int x = layout.contentX() + column * (buttonW + GAP);
            int w = column == columns - 1 ? layout.contentX() + layout.contentWidth() - x : buttonW;
            int y = layout.formatRowsY() + row * (BTN_H + GAP);
            Button button = GuiTokens.styledButton(x, y, w, BTN_H, formatLabel(target),
                    pressed -> selectExportTarget(target), null);
            addRenderableWidget(button);
            formatButtons.add(button);
        }
    }

    private void buildRoutePicker() {
        if (!isMultiRoute()) return;
        int y = layout.routeBlockY();
        int rightEdge = layout.contentX() + layout.contentWidth();
        int selectAllX = rightEdge - ROUTE_PICKER_SELECT_ALL_W;
        int toggleX = Math.max(layout.contentX(), selectAllX - GAP - ROUTE_PICKER_TOGGLE_W);
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

        if (routePickerExpanded && layout.routeRows() > 0) {
            routeListNavigation = new ListNavigationWidget(
                    layout.contentX(), layout.routeListY(), layout.contentWidth(), routeListHeight(),
                    ROUTE_PICKER_INSET, ROUTE_ROW_PITCH, ROUTE_ROW_H,
                    groups::size, this::initialRouteNavigationIndex,
                    this::routeSelectionNarration, this::toggleRouteSelection,
                    () -> routeScrollOffset, this::revealRouteIndex);
            addRenderableWidget(routeListNavigation);
        }
        refreshRoutePickerButtons();
    }

    private void buildFooter() {
        int contentX = layout.contentX();
        int contentRight = contentX + layout.contentWidth();
        Component back = Component.translatable("gui.back");
        Component copy = Component.translatable("waypointer.export.copy_clipboard");
        Component codeBlock = Component.translatable("waypointer.export.copy_code_block");
        Tooltip codeBlockTooltip = Tooltip.create(Component.translatable(
                "waypointer.screen.export.copy_code_block.tooltip"));
        int footerY = layout.footerY();

        if (layout.footerRows() == 1) {
            int backW = layout.footerBackWidth();
            addRenderableWidget(GuiTokens.styledButton(contentX, footerY, backW, BTN_H,
                    back, button -> onClose(), null));
            copyCodeBlockButton = GuiTokens.styledButton(contentX + backW + GAP, footerY,
                    layout.footerCopyWidth(), BTN_H, codeBlock, this::copyAsCodeBlock, codeBlockTooltip);
            int copyX = contentX + backW + GAP + layout.footerCopyWidth() + GAP;
            copyButton = GuiTokens.styledButton(copyX, footerY, contentRight - copyX, BTN_H,
                    copy, this::copyToClipboard, null);
        } else {
            int halfW = Math.max(1, (layout.contentWidth() - GAP) / 2);
            copyCodeBlockButton = GuiTokens.styledButton(contentX, footerY, halfW, BTN_H,
                    codeBlock, this::copyAsCodeBlock, codeBlockTooltip);
            copyButton = GuiTokens.styledButton(contentX + halfW + GAP, footerY,
                    contentRight - contentX - halfW - GAP, BTN_H, copy, this::copyToClipboard, null);
            addRenderableWidget(GuiTokens.styledButton(contentX, footerY + BTN_H + GAP,
                    layout.contentWidth(), BTN_H, back, button -> onClose(), null));
        }
        addRenderableWidget(copyCodeBlockButton);
        addRenderableWidget(copyButton);
        updateCopyButtons();
    }

    private int widestFormatLabel() {
        int widest = 0;
        for (WaypointExportCodec.Target target : formats) {
            widest = Math.max(widest, font.width(target.displayName()));
        }
        return widest;
    }

    private int copyLabelWidth() {
        return Math.max(font.width(Component.translatable("waypointer.export.copy_clipboard")),
                font.width(Component.translatable("waypointer.export.copy_code_block")));
    }

    private int codeLineHeight() {
        return font.lineHeight + 1;
    }

    static int formatColumns(int contentWidth, int formatCount, int widestLabel) {
        int count = Math.max(1, formatCount);
        for (int rows = 1; rows <= count; rows++) {
            int columns = (count + rows - 1) / rows;
            int buttonW = (contentWidth - (columns - 1) * GAP) / columns;
            if (buttonW >= widestLabel + FORMAT_LABEL_PAD) return columns;
        }
        return 1;
    }

    static int[] footerColumns(int contentWidth, int backLabel, int copyLabel) {
        int third = (contentWidth - GAP * 2) / 3;
        if (third >= Math.max(backLabel, copyLabel) + FOOTER_LABEL_PAD) return new int[]{third, third, 1};
        int backW = backLabel + FOOTER_LABEL_PAD;
        int copyW = (contentWidth - backW - GAP * 2) / 2;
        if (copyW >= copyLabel + FOOTER_LABEL_PAD) return new int[]{backW, copyW, 1};
        return new int[]{contentWidth, (contentWidth - GAP) / 2, 2};
    }

    static LayoutPolicy layoutPolicy(int screenWidth, int screenHeight,
                                     int formatCount, int widestFormatLabel,
                                     boolean multiRoute, boolean routePickerExpanded, int routeCount,
                                     int codeLineH, int backLabel, int copyLabel) {
        int safeWidth = Math.max(1, screenWidth);
        int safeHeight = Math.max(1, screenHeight);
        int lineH = Math.max(1, codeLineH);
        boolean tight = safeHeight < TIGHT_HEIGHT;
        int margin = tight ? GAP : PANEL_MARGIN;
        int pad = tight ? GAP : PAD_OUTER;
        int sectionGap = tight ? GAP : GAP_SECTION;
        int fieldGap = tight ? GAP : GAP_SECTION;

        int panelW = Math.min(safeWidth, MathUtil.clamp(safeWidth - margin * 2, PANEL_MIN_W, PANEL_MAX_W));
        int contentW = Math.max(1, panelW - pad * 2);

        int columns = formatColumns(contentW, formatCount, widestFormatLabel);
        int formatRows = (Math.max(1, formatCount) + columns - 1) / columns;
        int formatH = LABEL_H + formatRows * BTN_H + (formatRows - 1) * GAP;
        int[] footer = footerColumns(contentW, backLabel, copyLabel);
        int footerRows = footer[2];
        int footerH = footerRows * BTN_H + (footerRows - 1) * GAP;
        int routeHeaderH = multiRoute ? ROUTE_HEADER_H + fieldGap : 0;

        int fixedH = pad * 2 + LINE_H * 2 + sectionGap + formatH + fieldGap
                + routeHeaderH + LABEL_H + fieldGap + footerH;
        int spare = Math.max(0, safeHeight - margin * 2 - fixedH);

        int routeRows = 0;
        int listH = 0;
        if (multiRoute && routePickerExpanded) {
            int wanted = Math.min(MAX_EXPANDED_ROUTE_ROWS, Math.max(1, routeCount));
            int listOverhead = GAP + ROUTE_PICKER_INSET * 2;
            int oneCodeLine = lineH + CODE_INSET * 2;
            routeRows = MathUtil.clamp((spare - listOverhead - oneCodeLine) / ROUTE_ROW_PITCH, 1, wanted);
            listH = listOverhead + routeRows * ROUTE_ROW_PITCH;
        }
        int codeLines = MathUtil.clamp((spare - listH - CODE_INSET * 2) / lineH, 0, CODE_LINES);
        int codeH = codeLines == 0 ? 0 : codeLines * lineH + CODE_INSET * 2;

        int panelH = fixedH + listH + codeH;
        int panelX = Math.max(0, (safeWidth - panelW) / 2);
        int panelY = Math.max(0, (safeHeight - panelH) / 2);

        int contentX = panelX + pad;
        int titleY = panelY + pad;
        int formY = titleY + LINE_H * 2 + sectionGap;
        int formatRowsY = formY + LABEL_H;
        int routeBlockY = formatRowsY + formatRows * BTN_H + (formatRows - 1) * GAP + fieldGap;
        int routeListY = routeBlockY + ROUTE_HEADER_H + GAP;
        int sizeY = routeBlockY + (multiRoute ? routeHeaderH + listH : 0);
        int codeY = sizeY + LABEL_H;
        int footerY = codeY + codeH + fieldGap;

        return new LayoutPolicy(panelX, panelY, panelW, panelH, contentX, contentW,
                titleY, formY, sectionGap, columns, formatRows, formatRowsY,
                routeBlockY, routeListY, routeRows, sizeY, codeY, codeH,
                footerY, footerRows, footer[0], footer[1]);
    }

    record LayoutPolicy(
            int panelX, int panelY, int panelWidth, int panelHeight,
            int contentX, int contentWidth,
            int titleY, int formY, int sectionGap,
            int formatColumns, int formatRows, int formatRowsY,
            int routeBlockY, int routeListY, int routeRows,
            int sizeY, int codeY, int codeHeight,
            int footerY, int footerRows, int footerBackWidth, int footerCopyWidth) {

        int panelBottom() {
            return panelY + panelHeight;
        }

        int footerBottom() {
            return footerY + footerRows * BTN_H + (footerRows - 1) * GAP;
        }
    }

    static boolean shouldStartRoutePickerExpanded(int groupCount) {
        return groupCount > 1 && groupCount < ROUTE_PICKER_COLLAPSE_THRESHOLD;
    }

    private void selectExportTarget(WaypointExportCodec.Target target) {
        if (target == exportTarget) return;
        exportTarget = target;
        for (int i = 0; i < formatButtons.size(); i++) {
            formatButtons.get(i).setMessage(formatLabel(formats.get(i)));
        }
        reencode();
    }

    private Component formatLabel(WaypointExportCodec.Target target) {
        Component name = Component.literal(target.displayName());
        return target == exportTarget ? GuiTokens.colored(name, ACCENT) : name;
    }

    private void reencode() {
        List<WaypointGroup> selected = selectedGroupsForExport();
        RouteLibraryMetadata metadata = captureLibraryMetadata(
                manager, selected, exportTarget, preserveFolderMetadata);
        List<WaypointGroup> snapshot = new ArrayList<>();
        for (WaypointGroup group : selected) {
            if (group != null) snapshot.add(group.exportSnapshot());
        }
        WaypointCodec.Options options = ExportPolicy.optionsFromConfig(config, selected).build();
        WaypointExportCodec.Target target = exportTarget;
        WaypointerConfig appearance = target == WaypointExportCodec.Target.SKYSOFT
                ? config.exportSnapshot() : config;
        int generation = ++encodeGeneration;

        encodePending = true;
        encodingError = "";
        updateCopyButtons();

        CodecWorker.runLatestPreview(
                () -> encodeResult(snapshot, options, target, metadata, appearance),
                result -> applyEncodeResult(generation, result));
    }

    static EncodeResult encodeResult(List<WaypointGroup> routes, WaypointCodec.Options options,
                                     WaypointExportCodec.Target target, RouteLibraryMetadata metadata,
                                     WaypointerConfig appearance) {
        try {
            return new EncodeResult(encode(routes, options, target, metadata, appearance), "");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            String reason = failure.getMessage();
            return new EncodeResult("", reason == null || reason.isBlank()
                    ? Component.translatable("waypointer.command.export.failed", "").getString()
                    : reason);
        }
    }

    static String encode(List<WaypointGroup> routes, WaypointCodec.Options options,
                         WaypointExportCodec.Target target, RouteLibraryMetadata metadata,
                         WaypointerConfig appearance) {
        if (target == WaypointExportCodec.Target.WAYPOINTER && allDungeonRooms(routes)) {
            return UniversalShareCodec.encodeDungeon(routes);
        }
        return WaypointExportCodec.encode(routes, options, target, metadata, appearance);
    }

    static RouteLibraryMetadata captureLibraryMetadata(
            ActiveGroupManager manager,
            List<WaypointGroup> selected,
            WaypointExportCodec.Target target) {
        return captureLibraryMetadata(manager, selected, target,
                selected != null && selected.size() > 1);
    }

    static RouteLibraryMetadata captureLibraryMetadata(
            ActiveGroupManager manager,
            List<WaypointGroup> selected,
            WaypointExportCodec.Target target,
            boolean preserveFolders) {
        if (target != WaypointExportCodec.Target.WAYPOINTER) {
            return RouteLibraryMetadata.empty();
        }
        return RouteLibraryMetadata.captureForExport(
                manager, selected, preserveFolders || selected.size() > 1);
    }

    record EncodeResult(String code, String error) {}

    enum ScopeKind {
        STATIC,
        ZONE,
        SELECTION,
        FOLDER
    }

    private void applyEncodeResult(int generation, EncodeResult result) {
        if (generation != encodeGeneration) return;
        this.encoded = result == null ? "" : result.code();
        this.encodingError = result == null
                ? Component.translatable("waypointer.command.export.failed", "").getString()
                : result.error();
        this.encodePending = false;
        updateCopyButtons();
    }

    private void updateCopyButtons() {
        boolean canCopy = !encodePending && encodingError.isEmpty() && !encoded.isEmpty();
        if (copyButton != null) copyButton.active = canCopy;
        if (copyCodeBlockButton != null) copyCodeBlockButton.active = canCopy;
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

    private void updateCopyFeedback() {
        long now = System.currentTimeMillis();
        if (copyFeedbackUntil != 0 && now > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            copyButton.setMessage(Component.translatable("waypointer.export.copy_clipboard"));
        }
        if (copyCodeBlockFeedbackUntil != 0 && now > copyCodeBlockFeedbackUntil) {
            copyCodeBlockFeedbackUntil = 0;
            copyCodeBlockButton.setMessage(Component.translatable("waypointer.export.copy_code_block"));
        }
    }

    private void refreshRoutePickerButtons() {
        if (routePickerToggleButton != null) routePickerToggleButton.setMessage(routePickerToggleLabel());
        if (routeSelectAllButton != null) routeSelectAllButton.active = hasExcludedRoutes();
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
        reencode();
    }

    private void toggleRouteSelection(int idx) {
        if (!routeSelection.toggle(idx)) return;
        refreshRoutePickerButtons();
        reencode();
    }

    private int initialRouteNavigationIndex() {
        return Math.max(0, routeSelection.firstSelectedIndex());
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

    private boolean hasExcludedRoutes() {
        return routeSelection.hasExcludedRoutes();
    }

    private boolean isMultiRoute() {
        return groups.size() > 1;
    }

    private int selectedGroupCount() {
        return routeSelection.count();
    }

    private int selectedWaypointCount() {
        int total = 0;
        for (WaypointGroup group : selectedGroupsForExport()) total += group.size();
        return total;
    }

    private List<WaypointGroup> selectedGroupsForExport() {
        return routeSelection.selectedGroups(groups);
    }

    private int routeListHeight() {
        return layout.routeRows() * ROUTE_ROW_PITCH + ROUTE_PICKER_INSET * 2;
    }

    private int routeViewportHeight() {
        return layout.routeRows() * ROUTE_ROW_PITCH;
    }

    private int maxRouteScrollOffset() {
        return Math.max(0, groups.size() * ROUTE_ROW_PITCH - routeViewportHeight());
    }

    private void clampRouteScrollOffset() {
        if (layout == null) return;
        routeScrollOffset = MathUtil.clamp(routeScrollOffset, 0, maxRouteScrollOffset());
    }

    private boolean routeListShown() {
        return isMultiRoute() && routePickerExpanded && layout.routeRows() > 0;
    }

    private boolean isInsideRouteList(double mouseX, double mouseY) {
        if (!routeListShown()) return false;
        int x1 = layout.contentX();
        int y1 = layout.routeListY();
        return mouseX >= x1 && mouseX <= x1 + layout.contentWidth()
                && mouseY >= y1 && mouseY <= y1 + routeListHeight();
    }

    private int routeIndexAt(double mouseX, double mouseY) {
        if (!isInsideRouteList(mouseX, mouseY)) return -1;
        int localY = (int) (mouseY - layout.routeListY() - ROUTE_PICKER_INSET + routeScrollOffset);
        if (localY < 0 || localY % ROUTE_ROW_PITCH >= ROUTE_ROW_H) return -1;
        int idx = localY / ROUTE_ROW_PITCH;
        return idx < groups.size() ? idx : -1;
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        updateCopyFeedback();
        int contentX = layout.contentX();
        int contentW = layout.contentWidth();

        g.fill(0, 0, width, height, 0x80000000);
        g.fill(layout.panelX(), layout.panelY(), layout.panelX() + layout.panelWidth(),
                layout.panelBottom(), SURFACE);

        drawClipped(g, getTitle().getString(), layout.titleY(), TEXT);
        drawClipped(g, subtitleText(), layout.titleY() + LINE_H, TEXT_DIM);
        int dividerY = layout.formY() - layout.sectionGap() / 2;
        g.fill(contentX, dividerY, contentX + contentW, dividerY + 1, BORDER);

        drawClipped(g, Component.translatable("waypointer.screen.export.format").getString(),
                layout.formY(), TEXT_DIM);
        if (isMultiRoute()) renderRoutePicker(g, mouseX, mouseY);
        drawSizeSummary(g, contentX, layout.sizeY());
        if (layout.codeHeight() > 0) {
            drawCode(g, contentX, layout.codeY(), contentX + contentW, layout.codeY() + layout.codeHeight());
        }

        super.extractRenderState(g, mouseX, mouseY, partial);
        renderFormatSelection(g);
    }

    private void renderFormatSelection(GuiGraphicsExtractor g) {
        int index = formats.indexOf(exportTarget);
        if (index < 0 || index >= formatButtons.size()) return;
        Button selected = formatButtons.get(index);
        g.fill(selected.getX() + 1, selected.getY() + selected.getHeight() - 2,
                selected.getX() + selected.getWidth() - 1, selected.getY() + selected.getHeight() - 1,
                ACCENT);
    }

    private void drawClipped(GuiGraphicsExtractor g, String text, int y, int color) {
        g.text(font, font.plainSubstrByWidth(text, layout.contentWidth()), layout.contentX(), y, color, false);
    }

    private String subtitleText() {
        if (scopeKind == ScopeKind.STATIC) return subtitle;
        List<WaypointGroup> selected = selectedGroupsForExport();
        String name = selected.isEmpty() ? "" : routeDisplayName(selected.getFirst());
        return subtitleComponent(scopeKind, scopeLabel, name, selectedGroupCount(),
                selectedWaypointCount()).getString();
    }

    static Component subtitleComponent(ScopeKind scopeKind, String scopeLabel,
                                       String routeName, int routeCount, int waypointCount) {
        String key = subtitleKey(scopeKind, routeCount, waypointCount);
        if (scopeKind == ScopeKind.FOLDER) {
            return Component.translatable(key, scopeLabel, routeCount, waypointCount);
        }
        if (routeCount == 1) {
            return Component.translatable(key, routeName, waypointCount);
        }
        if (scopeKind == ScopeKind.SELECTION) {
            return Component.translatable(key, routeCount, waypointCount);
        }
        return Component.translatable(key, scopeLabel, routeCount, waypointCount);
    }

    static String subtitleKey(ScopeKind scopeKind, int routeCount, int waypointCount) {
        if (scopeKind == ScopeKind.STATIC) return "";
        if (scopeKind == ScopeKind.FOLDER) {
            return routeCount == 1
                    ? "waypointer.screen.export.subtitle.folder.one"
                    : "waypointer.screen.export.subtitle.folder.many";
        }
        if (routeCount == 1) {
            return waypointCount == 1
                    ? "waypointer.screen.export.subtitle.route.one"
                    : "waypointer.screen.export.subtitle.route.many";
        }
        return scopeKind == ScopeKind.SELECTION
                ? "waypointer.screen.export.subtitle.selection.many"
                : "waypointer.screen.export.subtitle.zone.many";
    }

    private void renderRoutePicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        clampRouteScrollOffset();
        int headerY = layout.routeBlockY();
        int textW = (routePickerToggleButton == null ? layout.contentX() + layout.contentWidth()
                : routePickerToggleButton.getX() - GAP) - layout.contentX();
        g.text(font, font.plainSubstrByWidth(
                        Component.translatable("waypointer.screen.export.routes").getString(), textW),
                layout.contentX(), headerY, TEXT_DIM, false);
        g.text(font, font.plainSubstrByWidth(Component.translatable(
                        "waypointer.screen.export.routes.summary", selectedGroupCount(), groups.size(),
                        Counts.of("waypoint", selectedWaypointCount())).getString(), textW),
                layout.contentX(), headerY + LINE_H, TEXT_MUTED, false);

        if (!routeListShown()) return;
        int x1 = layout.contentX();
        int y1 = layout.routeListY();
        int x2 = x1 + layout.contentWidth();
        int y2 = y1 + routeListHeight();
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        g.enableScissor(x1, y1 + ROUTE_PICKER_INSET, x2, y2 - ROUTE_PICKER_INSET);
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

    private void renderRouteRow(GuiGraphicsExtractor g, WaypointGroup group, int index,
                                int x1, int y1, int x2, boolean hovered) {
        boolean selected = routeSelection.isSelected(index);
        int rowBottom = y1 + ROUTE_ROW_H;
        int bg = selected ? ROW_SELECTED_TINT : hovered ? GuiTokens.HOVER : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBottom, bg);
        if (selected) g.fill(x1, y1, x1 + 2, rowBottom, ACCENT);

        int textColor = selected ? TEXT : TEXT_MUTED;
        int metaColor = selected ? TEXT_DIM : TEXT_MUTED;
        int textX = x1 + GAP;
        int centerY = y1 + (ROUTE_ROW_H - 8) / 2;
        GuiTokens.drawMiniCheck(g, textX, y1 + (ROUTE_ROW_H - GuiTokens.MINI_CHECK) / 2, selected, true);

        String rawMeta = RouteListPresentation.joinParts(List.of(displayZoneLabel(group.zoneId()),
                RouteListPresentation.waypointCount(group.size()).getString(),
                Component.translatable(group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                        ? "waypointer.screen.main.route.mode.sequence"
                        : "waypointer.screen.main.route.mode.static").getString()));
        int metaRight = x2 - GAP - (maxRouteScrollOffset() > 0 ? ROUTE_SCROLLBAR_W + GAP : 0);
        int nameX = textX + GuiTokens.MINI_CHECK + GAP;
        int metaMaxW = Math.max(40, metaRight - nameX - 60 - GAP);
        String meta = font.plainSubstrByWidth(rawMeta, metaMaxW);
        int metaW = font.width(meta);
        int nameMaxW = Math.max(20, metaRight - metaW - GAP - nameX);
        g.text(font, font.plainSubstrByWidth(routeDisplayName(group), nameMaxW), nameX, centerY, textColor, false);
        g.text(font, meta, metaRight - metaW, centerY, metaColor, false);
    }

    private void drawRouteScrollbar(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int maxScroll = maxRouteScrollOffset();
        if (maxScroll <= 0) return;
        int trackX = x2 - ROUTE_SCROLLBAR_W - 2;
        int trackY = y1 + 3;
        int trackH = Math.max(1, y2 - y1 - 6);
        int viewport = routeViewportHeight();
        int content = Math.max(viewport, groups.size() * ROUTE_ROW_PITCH);
        int thumbH = Math.max(10, trackH * viewport / content);
        int thumbY = trackY + Math.max(0, trackH - thumbH) * routeScrollOffset / maxScroll;
        g.fill(trackX, trackY, trackX + ROUTE_SCROLLBAR_W, trackY + trackH, BORDER);
        g.fill(trackX, thumbY, trackX + ROUTE_SCROLLBAR_W, thumbY + thumbH, TEXT_MUTED);
    }

    private void drawSizeSummary(GuiGraphicsExtractor g, int x, int y) {
        if (encodePending) {
            drawClipped(g, Component.translatable("waypointer.screen.export.encoding").getString(), y, TEXT_MUTED);
        } else if (!encodingError.isEmpty()) {
            drawClipped(g, encodingError, y, GuiTokens.DANGER);
        } else {
            drawSizeLine(g, font, x, y, x + layout.contentWidth(), encoded);
        }
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

    private void drawCode(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, BORDER);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, SURFACE_SUBTLE);
        if (encodePending || encoded.isEmpty()) return;

        int innerX = x1 + CODE_INSET;
        int lineH = codeLineHeight();
        List<FormattedCharSequence> lines = font.split(FormattedText.of(encoded), x2 - x1 - CODE_INSET * 2);
        int available = (y2 - y1 - CODE_INSET * 2) / lineH;
        int shown = lines.size() <= available ? lines.size() : Math.max(0, available - 1);
        int y = y1 + CODE_INSET;
        for (int i = 0; i < shown; i++, y += lineH) {
            g.text(font, lines.get(i), innerX, y, TEXT, false);
        }
        if (shown < lines.size()) {
            g.text(font, ExportPolicy.previewOverflowText(lines.size() - shown), innerX, y, TEXT_MUTED, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;
        int idx = routeIndexAt(event.x(), event.y());
        if (idx < 0) return false;
        if (routeListNavigation != null) routeListNavigation.setCursor(idx);
        toggleRouteSelection(idx);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (isInsideRouteList(mouseX, mouseY)) {
            int maxScroll = maxRouteScrollOffset();
            if (maxScroll > 0 && Double.isFinite(vert) && vert != 0.0) {
                int target = MathUtil.clamp(routeScrollOffset - (int) (vert * ROUTE_ROW_PITCH), 0, maxScroll);
                if (target != routeScrollOffset) {
                    routeScrollOffset = target;
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horiz, vert);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }
}
