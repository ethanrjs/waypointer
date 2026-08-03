package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * Dedicated screen for reviewing an export before pasting it elsewhere. Shows the
 * encoded codec string, its size, a plain-language list of what gets included, an
 * optional sender label, plus plain and Discord-friendly copy actions.
 *
 * The granular options exist because the wire format really does carry all of
 * this, and a sender who wants a small "just the path" payload needs a way to
 * drop the rest. What they did not need was the vocabulary: the previous row of
 * "[+] WP Flags" / "[+] Route Meta" buttons named wire-format concepts, so the
 * common reaction was "do I need that enabled?" rather than a decision. The
 * options are unchanged; every one of them is now stated as the thing a player
 * would actually miss, on a full-width row whose [x] marker matches the route
 * and target pickers elsewhere in this screen.
 *
 * Everything lives in one centered panel sized to its own content, matching the
 * other focused Waypointer dialogs. Top to bottom:
 *
 *   1. Title + subtitle describing what's being exported.
 *   2. Sanitized label EditBox plus the export-target chooser, which names the
 *      target it will produce rather than hiding it behind "Export for...".
 *   3. "Include" list, two columns, one row per option. Everyday choices
 *      (names, colors, island) lead the left column; the fiddly per-waypoint
 *      and per-route ones follow on the right. A single helper line under the
 *      heading carries whichever caveat currently applies.
 *   4. Size summary on one line: char count + where it can be pasted.
 *   5. A fixed-height preview of the code itself.
 *   6. Footer: Back / Reset on the left, copy actions on the right.
 */
public final class ExportScreen extends Screen {

    private static final int PREVIEW_INSET = 6;
    private static final String DUNGEON_ROOM_LABEL_PREFIX = "Dungeons: ";

    /** Reference chat textbox size used only for paste-fit messaging. */
    private static final int CHAT_INPUT_LIMIT = 256;

    /**
     * UTF-8 byte ceiling Minecraft enforces on serverbound command packets.
     * The vanilla client refuses to send a chat command whose serialized
     * command string runs past this cap, so pastes into {@code /pc}, {@code
     * /msg}, and friends silently fail when the export is too large.
     *
     * Chat input is measured in characters, but the command wire packet is
     * measured in bytes. The native codec uses a printable ASCII alphabet
     * (1 UTF-8 byte per char), so command framing ({@code /pc }, etc.) is
     * what makes "fits in chat" differ from "fits in commands".
     */
    private static final int COMMAND_WIRE_LIMIT_BYTES = 256;

    /**
     * UTF-8 bytes occupied by the command name + separator when the codec is
     * pasted after a typical short chat command like {@code /pc }. The leading
     * {@code /} is stripped by the client before the packet is sent, so only
     * {@code "pc "} (3 bytes) actually travels on the wire. This is the
     * reference prefix used to decide whether the export can be shared inline
     * via a chat command -- longer prefixes (e.g. {@code /msg <name> }) will
     * fit fewer codec bytes; we surface the shortest-realistic case here.
     */
    private static final int REFERENCE_COMMAND_PREFIX_BYTES = "pc ".length();

    /** How long to show the "Copied!" state on the copy button before reverting. */
    private static final long COPIED_FEEDBACK_MS = 1500;

    /** Separator between the facts sharing the size summary line. */
    private static final String SUMMARY_SEPARATOR = "  ·  ";

    /** Vertical space used by the title + subtitle block above the label input. */
    private static final int HEADER_H = 28;

    /** Row height for a line of text in the size summary. */
    private static final int LINE_H = 12;

    /** Vertical block above the include rows: section title + helper copy. */
    private static final int EXPORT_SETTINGS_HEADER_H = LINE_H * 2 + GAP_TIGHT;

    /**
     * Gap between stacked blocks inside the panel.
     *
     * GuiTokens' section gap separates major regions of a whole screen; inside a
     * panel the panel itself already does that job, so section-sized gaps just
     * pushed the content apart and made the export feel scattered.
     */
    private static final int BLOCK_GAP = GAP;

    /**
     * The screen is one centered panel, like every other focused Waypointer
     * dialog. Spanning the whole window meant a 36-character export sat between
     * controls pinned to opposite edges of a 2560px monitor, with the copy
     * buttons a full screen away from the thing they copy.
     *
     * {@link #PANEL_MAX_W} is exactly two include columns plus the panel's own
     * padding, so the widest element decides the panel and nothing floats.
     */
    private static final int PANEL_MARGIN = 16;
    private static final int PANEL_MIN_W = 260;
    private static final int PANEL_MAX_W = 448;

    /**
     * Include rows are laid out in two columns of {@link #INCLUDE_ROWS_PER_COL}.
     * Below {@link #INCLUDE_COL_MIN_W} per column the labels would clip, so the
     * list collapses to a single full-width column instead.
     */
    private static final int INCLUDE_ROWS_PER_COL = 3;
    private static final int INCLUDE_COL_MIN_W = 150;
    private static final int INCLUDE_COL_MAX_W = 200;
    private static final int INCLUDE_ROW_PITCH = BTN_H + GAP_TIGHT;
    /** Horizontal inset of an include row's marker from its own frame. */
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

    /**
     * The preview is a sanity check, not the content: a fixed three lines with
     * an overflow marker beats both the old full-height box and a box that
     * resizes the panel under the cursor every time a toggle changes the code.
     */
    private static final int PREVIEW_LINES = 3;

    private final Screen parent;
    private final WaypointerConfig config;
    private final List<WaypointGroup> groups;
    private final boolean[] selectedGroups;
    private final String subtitle;

    /** Mutable export options the user is currently building. */
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
    private boolean routePickerExpanded;
    private int routeScrollOffset;
    private long copyFeedbackUntil = 0L;
    private long copyCodeBlockFeedbackUntil = 0L;

    private String encoded = "";
    private String encodingError = "";

    // Panel geometry, resolved once per init() so the widget pass and the render
    // pass cannot disagree about where anything is.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
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

    public ExportScreen(Screen parent, WaypointerConfig config, List<WaypointGroup> groups, String subtitle) {
        super(Component.translatable("waypointer.screen.export.title"));
        this.parent = parent;
        this.config = config;
        this.groups = groups;
        this.selectedGroups = initialRouteSelection(groups.size());
        this.subtitle = subtitle;
        this.routePickerExpanded = shouldStartRoutePickerExpanded(groups.size());
        this.optsBuilder = builderFromConfig(config, selectedGroupsForExport(groups, selectedGroups));
    }

    public static void openForGroup(Screen parent, WaypointerConfig config, WaypointGroup group) {
        String title = Component.translatable(group.size() == 1
                ? "waypointer.screen.export.subtitle.route.one"
                : "waypointer.screen.export.subtitle.route.many",
                routeDisplayName(group), group.size()).getString();
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ExportScreen(parent, config, List.of(group), title));
    }

    public static void openForGroups(Screen parent, WaypointerConfig config,
                                     List<WaypointGroup> groups, String zoneLabel) {
        int totalPts = groups.stream().mapToInt(WaypointGroup::size).sum();
        String title = Component.translatable(groups.size() == 1
                ? "waypointer.screen.export.subtitle.zone.one"
                : "waypointer.screen.export.subtitle.zone.many",
                zoneLabel, groups.size(), totalPts).getString();
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new ExportScreen(parent, config, groups, title));
    }

    // --- lifecycle ----------------------------------------------------------------------------

    @Override
    protected void init() {
        toggleSpecs.clear();
        toggleButtons.clear();
        routePickerToggleButton = null;
        routeSelectAllButton = null;

        // Declaration order is column-major: the first three rows fill the left
        // column, the rest fill the right one. The left column holds the choices
        // a player makes on purpose (what the waypoints are called, what color
        // they are, where they land); the right holds the fine-grained ones that
        // are almost always just left on.
        registerToggle(ToggleKind.NAMES, optsBuilder.includeNames());
        registerToggle(ToggleKind.COLORS, optsBuilder.includeColors());
        registerToggle(ToggleKind.ZONE, optsBuilder.includeZone());
        registerToggle(ToggleKind.RADII, optsBuilder.includeRadii());
        registerToggle(ToggleKind.WAYPOINT_FLAGS, optsBuilder.includeWaypointFlags());
        registerToggle(ToggleKind.GROUP_META, optsBuilder.includeGroupMeta());

        computeLayout();

        // Label input leads the panel so it reads as the primary "what is this
        // export for?" field. Vanilla EditBox enforces its own visual
        // selection/cursor handling; we only need to size it and forward
        // changes through sanitizeLabel() before re-encoding.
        int labelY = labelRowY;
        int exportForW = exportForButtonWidth();
        int labelW = Math.max(80, contentW - exportForW - GAP);
        labelInput = new EditBox(font, contentX, labelY, labelW, BTN_H,
                Component.translatable("waypointer.screen.export.label"));
        // MAX_LABEL_CHARS bounds the visible character count; on-the-wire we
        // cap bytes too. The widget cap matches the visible-character cap so
        // users see exactly when they hit the limit instead of being truncated
        // silently at encode time.
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

        // Footer sits inside the panel: Back/Reset on the left, copy actions on
        // the right. The plain copy button stays furthest right because it is
        // the most common action; the Discord wrapper sits beside it.
        // Reset is here rather than beside the include list because it's a
        // destructive-looking action ("did I just lose my settings?") and
        // grouping it with Back makes its scope (the whole screen) clearer.
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

        GuiTokens.layoutFooter(panelX + panelW, footerY, left, null, this::addRenderableWidget,
                font, contentX, PAD_OUTER + rightClusterW + GAP);
        addRenderableWidget(copyCodeBlockButton);
        addRenderableWidget(copyButton);

        setInitialFocus(labelInput);
        clampRouteScrollOffset();
    }

    private int footerButtonWidth(Component label) {
        return Math.max(60, font.width(label) + 16);
    }

    /**
     * Resolve the panel and every section inside it.
     *
     * The panel is sized to its content and then centered, so it has to be
     * measured before any widget is placed. Only the route list can change
     * height after that (expanding the picker), and that path rebuilds.
     */
    private void computeLayout() {
        reencode();

        panelW = MathUtil.clamp(width - PANEL_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
        panelW = Math.min(panelW, Math.max(PANEL_MIN_W, width));
        contentW = panelW - PAD_OUTER * 2;

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
    }

    /** Every block in the panel except the preview, which is the flexible one. */
    static int panelFixedHeight(int includeRowsH, int routeBlockH) {
        return PAD_OUTER * 2 + HEADER_H + BTN_H + BLOCK_GAP + EXPORT_SETTINGS_HEADER_H
                + includeRowsH + routeBlockH + BLOCK_GAP + LINE_H + GAP_TIGHT
                + BLOCK_GAP + BTN_H;
    }

    /**
     * The preview is the one block that can give ground.
     *
     * Every other section is as tall as its content demands, so at small window
     * sizes (GUI scale 4 leaves about 270 units of height) the preview shrinks
     * toward a single line rather than pushing the copy buttons off-screen. It
     * never grows past {@link #PREVIEW_LINES} even when there is room, so
     * toggling an option cannot resize the panel under the cursor.
     */
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

    /** Column width and rows-per-column for the include list. */
    record IncludeGrid(int columnWidth, int rowsPerColumn) {}

    /**
     * Two columns while each stays wide enough for a full label, one column
     * below that. Collapsing is the structural fallback rather than letting
     * labels clip, which is what the old fixed-width toggle row did when the
     * buttons wrapped.
     */
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

    private static Component toggleLabel(ToggleSpec spec) {
        return Component.translatable(toggleLabelTranslationKey(spec));
    }

    /**
     * Marker drawn ahead of an include row's label. Same [x] / [ ] vocabulary
     * as the route list and the target picker; [-] marks an option the chosen
     * target cannot carry, so the row reads as "not available" rather than
     * "off".
     */
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

    // --- state transitions --------------------------------------------------------------------

    private void onLabelChanged(String raw) {
        // Sanitize on the way in so the encoded payload never carries hidden
        // formatting. We don't mirror the sanitized value back into the input
        // because that would fight the user's cursor on every keystroke; the
        // wire copy is what matters and the preview shows real bytes.
        currentLabel = raw;
        optsBuilder.label(WaypointCodec.Options.sanitizeLabel(raw));
        reencode();
    }

    private void openExportTargetMenu(Button button) {
        MinecraftCompat.setScreen(minecraft, new ExportTargetScreen(this));
    }

    private void selectExportTarget(WaypointExportCodec.Target target) {
        exportTarget = target;
        if (exportForButton != null) exportForButton.setMessage(exportForButtonLabel());
        if (!toggleButtons.isEmpty()) refreshToggleButtons();
        if (labelInput != null) updateLabelInputState();
        reencode();
    }

    private void goBackToParent() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    private void resetToConfigDefaults() {
        optsBuilder = builderFromConfig(config, selectedGroupsForExport());
        currentLabel = "";
        labelInput.setValue("");
        // Re-apply each toggle's value from the freshly-built options and
        // refresh its button label so the UI matches the new state.
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
        WaypointCodec.Options options = optsBuilder.build();
        try {
            this.encoded = WaypointExportCodec.encode(selected, options, exportTarget);
            this.encodingError = "";
        } catch (IllegalArgumentException error) {
            this.encoded = "";
            this.encodingError = error.getMessage() == null ? "Export is not supported" : error.getMessage();
        }
        boolean canCopy = encodingError.isEmpty();
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
        return Component.translatable(routePickerExpanded
                ? "waypointer.screen.export.routes.hide"
                : "waypointer.screen.export.routes.show")
                .withStyle(ChatFormatting.AQUA);
    }

    private void toggleRoutePicker(Button button) {
        routePickerExpanded = !routePickerExpanded;
        clampRouteScrollOffset();
        // The list is inside the panel, so showing it changes how tall the panel
        // is and where everything below it sits. Rebuild rather than let the
        // widgets and the painted panel drift apart.
        rebuildWidgets();
    }

    private void selectAllRoutes(Button button) {
        selectAllRouteSelectionState(selectedGroups);
        refreshRoutePickerButtons();
        reencode();
    }

    static boolean[] initialRouteSelection(int groupCount) {
        int safeCount = Math.max(0, groupCount);
        boolean[] selected = new boolean[safeCount];
        Arrays.fill(selected, true);
        return selected;
    }

    static boolean shouldStartRoutePickerExpanded(int groupCount) {
        return groupCount > 1 && groupCount < ROUTE_PICKER_COLLAPSE_THRESHOLD;
    }

    static void selectAllRouteSelectionState(boolean[] selectedGroups) {
        if (selectedGroups == null) return;
        Arrays.fill(selectedGroups, true);
    }

    private void toggleRouteSelection(int idx) {
        if (!toggleRouteSelectionState(selectedGroups, idx)) return;
        refreshRoutePickerButtons();
        reencode();
    }

    static boolean toggleRouteSelectionState(boolean[] selectedGroups, int idx) {
        if (selectedGroups == null || idx < 0 || idx >= selectedGroups.length) return false;
        if (selectedGroups[idx] && selectedGroupCount(selectedGroups) == 1) return false;
        selectedGroups[idx] = !selectedGroups[idx];
        return true;
    }

    private boolean hasExcludedRoutes() {
        return hasExcludedRoutes(selectedGroups);
    }

    static boolean hasExcludedRoutes(boolean[] selectedGroups) {
        if (selectedGroups == null) return false;
        for (boolean selected : selectedGroups) {
            if (!selected) return true;
        }
        return false;
    }

    private boolean isZoneExport() {
        return groups.size() > 1;
    }

    private int selectedGroupCount() {
        if (!isZoneExport()) return groups.size();
        return selectedGroupCount(selectedGroups);
    }

    static int selectedGroupCount(boolean[] selectedGroups) {
        int count = 0;
        if (selectedGroups == null) return count;
        for (boolean selected : selectedGroups) {
            if (selected) count++;
        }
        return count;
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
        return selectedGroupsForExport(groups, selectedGroups);
    }

    static List<WaypointGroup> selectedGroupsForExport(List<WaypointGroup> groups,
                                                       boolean[] selectedGroups) {
        if (groups == null) return List.of();
        if (groups.size() <= 1) return groups;
        List<WaypointGroup> selected = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            if (selectedGroups != null && i < selectedGroups.length && selectedGroups[i]) {
                selected.add(groups.get(i));
            }
        }
        return selected;
    }

    private static String routeDisplayName(WaypointGroup group) {
        String name = group.name().trim();
        if (!name.isEmpty()) return name;
        return displayZoneLabel(group.zoneId());
    }

    private static String displayZoneLabel(String zoneId) {
        DungeonRoomDefinition definition = DungeonRoomData.definition(zoneId);
        if (definition != null) return DUNGEON_ROOM_LABEL_PREFIX + definition.displayName();
        return Zone.fromId(zoneId).displayName();
    }

    private void updateLabelInputState() {
        labelInput.active = exportTarget.supportsLabel();
        labelInput.setTooltip(Tooltip.create(exportTarget.supportsLabel()
                ? Component.translatable("waypointer.screen.export.label.tooltip")
                : Component.translatable("waypointer.screen.export.label.unsupported",
                        exportTarget.displayName())));
    }

    static String labelInputTooltipText(WaypointExportCodec.Target target) {
        return target.supportsLabel()
                ? "Optional title shown by Waypointer imports"
                : target.displayName() + " exports do not support Waypointer labels";
    }

    /**
     * The button names the format it will produce. It used to read only
     * "Export for...", so the single most consequential choice on the screen --
     * which mod the code is for -- was invisible until you opened the picker,
     * and the greyed-out include rows next to it had no visible cause.
     */
    private Component exportForButtonLabel() {
        return Component.translatable("waypointer.screen.export.target",
                exportTarget.displayName()).withStyle(ChatFormatting.AQUA);
    }

    /** Width that fits the longest target name, so the row never reflows on switch. */
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
        copyButton.setMessage(Component.translatable("waypointer.common.copied")
                .withStyle(ChatFormatting.GREEN));
    }

    private void copyAsCodeBlock(Button button) {
        minecraft.keyboardHandler.setClipboard(codeBlockPayload(encoded));
        copyCodeBlockFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyCodeBlockButton.setMessage(Component.translatable("waypointer.common.copied")
                .withStyle(ChatFormatting.GREEN));
    }

    static String codeBlockPayload(String payload) {
        return "```\n" + (payload == null ? "" : payload) + "\n```";
    }

    // --- rendering ----------------------------------------------------------------------------

    /**
     * Panel and its own painting first, widgets last.
     *
     * SURFACE is ~75% opaque, so anything drawn before the panel fill gets
     * greyed out by it -- which is exactly what happened to every button and
     * the label box when this called super first. The sibling panel screens
     * (AddNamedWaypointScreen, DebugReportConsentScreen) order it this way for
     * the same reason.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        updateCopyFeedback();

        // Same scrim the other focused dialogs use: the world stays readable
        // behind it, but the panel edge reads as an edge.
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);

        int top = panelY + PAD_OUTER;
        drawClipped(g, getTitle().getString(), top, TEXT);
        drawClipped(g, subtitle, top + LINE_H, TEXT_DIM);

        drawClipped(g, Component.translatable("waypointer.screen.export.settings").getString(),
                includeHeadY, TEXT_DIM);
        SettingsHelp help = settingsHelp();
        drawClipped(g, help.text(), includeHeadY + LINE_H, help.color());

        if (isZoneExport()) renderRoutePicker(g, mouseX, mouseY);

        drawSizeSummary(g, contentX, sizeY);
        // No preview caption: the target button above already names the format,
        // so a "Waypointer code" heading over the box only repeated it.
        drawPreview(g, contentX, previewY, contentX + contentW, previewY + previewH);

        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    /**
     * Draw a full-width line of panel text, clipped to the panel.
     *
     * The panel is sized to its widest control, not to its longest sentence, and
     * translations of the same line vary by a wide margin -- so every free line
     * of text goes through here rather than trusting the English to fit.
     */
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

    private void renderRouteRow(GuiGraphicsExtractor g, WaypointGroup group, int index,
                                int x1, int y1, int x2, boolean hovered) {
        boolean selected = selectedGroups[index];
        int rowBottom = y1 + ROUTE_ROW_H;
        int bg = selected ? 0x1C4FB3C4 : hovered ? 0x18FFFFFF : 0;
        if (bg != 0) g.fill(x1, y1, x2, rowBottom, bg);
        if (selected) g.fill(x1, y1, x1 + 2, rowBottom, 0xFF4FB3C4);

        String marker = selected ? "[x]" : "[ ]";
        int markerColor = selected ? 0xFF4FB3C4 : TEXT_MUTED;
        int textColor = selected ? TEXT : TEXT_MUTED;
        int metaColor = selected ? TEXT_DIM : TEXT_MUTED;
        int textX = x1 + GAP;
        int centerY = y1 + 7;
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
        g.fill(trackX, trackY, trackX + ROUTE_SCROLLBAR_W, trackY + trackH, 0x40000000);
        g.fill(trackX, thumbY, trackX + ROUTE_SCROLLBAR_W, thumbY + thumbH, TEXT_MUTED);
    }

    /**
     * Size and paste-fit on one line. These two facts are read together -- the
     * character count only matters because of where it will and won't fit -- and
     * splitting them across two lines made the fit verdict look like a separate
     * status message.
     */
    private void drawSizeSummary(GuiGraphicsExtractor g, int x, int y) {
        if (!encodingError.isEmpty()) {
            drawClipped(g, encodingError, y, 0xFFDD7070);
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
        g.text(font, chip, chipX, y, 0xFF88AACC, false);
    }

    /**
     * Draws "&lt;n&gt; characters · &lt;where it fits&gt;" and returns the x just past
     * it so callers can append more facts to the same line. Shared with
     * {@link DungeonRoomExportScreen} so both export screens state size the
     * same way, in the same place, with the same colors.
     *
     * Everything is clipped to {@code right}: the character count is the fact
     * that must survive a narrow window, so the verdict after it gives way
     * first rather than running off the edge of the screen.
     */
    static int drawSizeLine(GuiGraphicsExtractor g, Font font, int x, int y, int right, String payload) {
        ExportFitSummary fit = exportFitSummary(payload);

        String chars = Component.translatable("waypointer.export.characters",
                payload == null ? 0 : payload.length()).getString();
        g.text(font, chars, x, y, TEXT_DIM, false);

        int separatorX = x + font.width(chars);
        int fitX = separatorX + font.width(SUMMARY_SEPARATOR);
        // Amber for "chat only": not an error, but a /pc paste will silently
        // fail, so it cannot read as the same green as a clean fit.
        int fitColor = fit.commandOk() ? 0xFF88DD88 : fit.chatOk() ? 0xFFE0C070 : 0xFFDD7070;
        String fitLine = font.plainSubstrByWidth(
                Component.translatable(fit.messageKey()).getString(), Math.max(0, right - fitX));
        if (fitLine.isEmpty()) return separatorX;

        g.text(font, SUMMARY_SEPARATOR, separatorX, y, TEXT_MUTED, false);
        g.text(font, fitLine, fitX, y, fitColor, false);
        return fitX + font.width(fitLine);
    }

    private record SettingsHelp(String text, int color) {}

    /**
     * The one helper line under "Include", showing whichever caveat currently
     * applies. Ordered by how much it can surprise the recipient: a format that
     * silently flattens subwaypoints first, then options the target will drop,
     * then a route that will not land on the island it was built for, then the
     * ordinary size hint. Three weights, so a live consequence of the current
     * settings never looks like the same static hint that is always there.
     */
    private SettingsHelp settingsHelp() {
        if (showSubwaypointCompatibilityWarning()) {
            return new SettingsHelp(Component.translatable(
                    "waypointer.screen.export.settings_help.subwaypoints").getString(),
                    0xFFFFB060);
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
        return showSubwaypointCompatibilityWarning(exportTarget, selectedGroupsForExport());
    }

    static ExportFitSummary exportFitSummary(String payload) {
        String safePayload = payload == null ? "" : payload;
        int chars = safePayload.length();
        int wireBytes = safePayload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int commandBytes = REFERENCE_COMMAND_PREFIX_BYTES + wireBytes;

        boolean chatOk = chars <= CHAT_INPUT_LIMIT;
        boolean commandOk = commandBytes <= COMMAND_WIRE_LIMIT_BYTES;
        if (commandOk) {
            return new ExportFitSummary(chars, wireBytes, commandBytes, chatOk, commandOk,
                    "waypointer.export.fit.chat_and_commands");
        }
        if (chatOk) {
            return new ExportFitSummary(chars, wireBytes, commandBytes, chatOk, false,
                    "waypointer.export.fit.chat_only");
        }
        return new ExportFitSummary(chars, wireBytes, commandBytes, false, false,
                "waypointer.export.fit.too_long");
    }

    static boolean showSubwaypointCompatibilityWarning(WaypointExportCodec.Target target,
                                                       List<WaypointGroup> selectedGroups) {
        return target != WaypointExportCodec.Target.WAYPOINTER
                && selectedExportHasSubwaypoints(selectedGroups);
    }

    private boolean selectedExportHasSubwaypoints() {
        return selectedExportHasSubwaypoints(selectedGroupsForExport());
    }

    static boolean selectedExportHasSubwaypoints(List<WaypointGroup> selectedGroups) {
        if (selectedGroups == null) return false;
        for (WaypointGroup group : selectedGroups) {
            if (group.hasSubwaypoints()) return true;
        }
        return false;
    }

    /** Fit verdict. {@code messageKey} is a translation key, not display text. */
    record ExportFitSummary(int characters, int wireBytes, int commandBytes,
                            boolean chatOk, boolean commandOk, String messageKey) {}

    private void drawPreview(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);

        int innerX = x1 + PREVIEW_INSET;
        int innerY = y1 + PREVIEW_INSET;
        int innerW = x2 - x1 - PREVIEW_INSET * 2;
        int lineH = previewLineHeight();
        List<FormattedCharSequence> lines = font.split(FormattedText.of(encoded), innerW);

        // Reserve the last visible line for the "N more lines" marker so it can
        // never be drawn past the box it belongs to.
        int available = (y2 - y1 - PREVIEW_INSET * 2) / lineH;
        int shown = lines.size() <= available ? lines.size() : Math.max(0, available - 1);

        int y = innerY;
        for (int i = 0; i < shown; i++, y += lineH) {
            g.text(font, lines.get(i), innerX, y, TEXT, false);
        }
        if (shown < lines.size()) {
            String ellipsis = previewOverflowText(lines.size() - shown);
            g.text(font, ellipsis, innerX, y, TEXT_MUTED, false);
        }
    }

    static String previewOverflowText(int hiddenLines) {
        int safeHidden = Math.max(0, hiddenLines);
        return "..." + safeHidden + " more line" + (safeHidden == 1 ? "" : "s");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;
        if (!isZoneExport() || !routePickerExpanded) return false;

        int idx = routeIndexAt(event.x(), event.y());
        if (idx < 0) return false;
        toggleRouteSelection(idx);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
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

    /**
     * How many route rows the window can actually show.
     *
     * Everything else in the panel has a fixed height, so the route list is the
     * only thing that can push the panel past the screen. Measure the fixed part
     * and give the list whatever is left.
     */
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

    // --- helpers ------------------------------------------------------------------------------

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

    static WaypointCodec.Options.Builder builderFromConfig(WaypointerConfig config,
                                                           List<WaypointGroup> selectedGroups) {
        boolean includeWaypointFlags = config.exportIncludeWaypointFlags()
                || selectedExportHasSubwaypoints(selectedGroups);
        return WaypointCodec.Options.builder()
                .includeNames(config.exportIncludeNames())
                .includeColors(config.exportIncludeColors())
                .includeRadii(config.exportIncludeRadii())
                .includeWaypointFlags(includeWaypointFlags)
                .includeGroupMeta(config.exportIncludeGroupMeta())
                .includeZone(config.exportIncludeZone());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }

    /**
     * Standalone target picker for the export screen. A dedicated screen keeps
     * the choice deliberate: users see every supported target before switching,
     * instead of cycling past formats whose disabled options can surprise them.
     */
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
            // No "Current: X" line -- the chosen row already carries [x], and the
            // button that opened this screen already reads "For: <target>".
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

        /** Same row treatment as the include list, so a chosen thing reads the same. */
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

    /**
     * A full-width row: the shared control frame, a state marker, and a
     * left-aligned label.
     *
     * It is a plain button rather than a checkbox plus a separate text label so
     * the whole row is the click target and keyboard focus lands on one widget
     * per option. Both places the player picks something in this flow -- the
     * include list and the target picker -- use it, so a chosen thing looks the
     * same on both screens.
     */
    private abstract static class MarkerRow extends GuiTokens.StyledButton {
        MarkerRow(int x, int y, int width, Component label, Button.OnPress onPress) {
            super(x, y, width, BTN_H, label, onPress);
        }

        /** [x] chosen, [ ] not chosen, [-] unavailable. */
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

            // Unchosen is dimmed rather than near-black: it still has to be
            // readable enough to pick.
            int markerColor = !active ? TEXT_MUTED : chosen() ? ACCENT : TEXT_MUTED;
            int labelColor = !active ? TEXT_MUTED : chosen() ? TEXT : TEXT_DIM;
            g.text(font, marker, markerX, textY, markerColor, false);
            g.text(font, font.plainSubstrByWidth(getMessage().getString(), labelMaxW),
                    labelX, textY, labelColor, false);
        }

        /**
         * State is carried by the marker, which is drawn rather than part of the
         * message, so narration has to put it back or the row reads as a bare
         * label with no state.
         */
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
