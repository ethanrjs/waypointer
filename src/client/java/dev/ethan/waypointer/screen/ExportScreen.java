package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.codec.WaypointExportCodec;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.util.MathUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static dev.ethan.waypointer.screen.GuiTokens.BTN_H;
import static dev.ethan.waypointer.screen.GuiTokens.PAD_OUTER;
import static dev.ethan.waypointer.screen.GuiTokens.FOOTER_H;
import static dev.ethan.waypointer.screen.GuiTokens.GAP;
import static dev.ethan.waypointer.screen.GuiTokens.GAP_SECTION;
import static dev.ethan.waypointer.screen.GuiTokens.TEXT;
import static dev.ethan.waypointer.screen.GuiTokens.TEXT_DIM;
import static dev.ethan.waypointer.screen.GuiTokens.TEXT_MUTED;
import static dev.ethan.waypointer.screen.GuiTokens.SURFACE_SUBTLE;

/**
 * Dedicated screen for reviewing an export before pasting it elsewhere. Shows the
 * encoded codec string, its size, granular toggles for what gets included, an
 * optional sender label, plus plain and Discord-friendly copy actions.
 *
 * The previous version offered only Names / No Names. That worked but lied about
 * the wire format -- exports always carried colors, radii, group metadata, and a
 * fixed-format header regardless of what the user picked. The recipient had no
 * way to opt out, and a sender who wanted a small "just the path" payload had no
 * choice. Granular toggles let the sender pay only for what's worth sharing;
 * defaults are preserved so the common case still produces a sensible mid-size
 * export with names dropped.
 *
 * Layout, top to bottom:
 *
 *   1. Title + subtitle describing what's being exported.
 *   2. Sanitized label EditBox plus export-target chooser.
 *   3. Toggle row per export option (Names, Colors, Radii, Waypoint Flags,
 *      Group Meta). Each carries a tooltip explaining the trade-off.
 *   4. Reset-to-defaults button so users who experimented can recover the
 *      sensible config preset without leaving the screen.
 *   5. Size summary: char count + paste-fit indicator.
 *   6. Preview box labelled "Export Preview".
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

    /** Vertical space used by the title + subtitle block above the label input. */
    private static final int HEADER_H = 28;

    /** Row height for a line of text in the size summary. */
    private static final int LINE_H = 12;

    /** Vertical block above the toggle buttons: section title + helper copy. */
    private static final int EXPORT_SETTINGS_HEADER_H = LINE_H * 2 + GAP;

    /** Fixed width of each toggle button so the row stays scannable across screen sizes. */
    private static final int TOGGLE_W = 96;
    private static final int EXPORT_FOR_W = 124;
    private static final int ROUTE_PICKER_TOGGLE_W = 86;
    private static final int ROUTE_PICKER_SELECT_ALL_W = 76;
    private static final int ROUTE_PICKER_COLLAPSE_THRESHOLD = 9;
    private static final int MAX_EXPANDED_ROUTE_ROWS = 6;
    private static final int ROUTE_ROW_H = 24;
    private static final int ROUTE_ROW_GAP = 2;
    private static final int ROUTE_ROW_PITCH = ROUTE_ROW_H + ROUTE_ROW_GAP;
    private static final int ROUTE_PICKER_INSET = 4;
    private static final int ROUTE_SCROLLBAR_W = 3;
    private static final int MIN_PREVIEW_H = 34;

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

    public ExportScreen(Screen parent, WaypointerConfig config, List<WaypointGroup> groups, String subtitle) {
        super(Component.literal("Export Waypoints"));
        this.parent = parent;
        this.config = config;
        this.groups = groups;
        this.selectedGroups = initialRouteSelection(groups.size());
        this.subtitle = subtitle;
        this.routePickerExpanded = shouldStartRoutePickerExpanded(groups.size());
        this.optsBuilder = builderFromConfig(config, selectedGroupsForExport(groups, selectedGroups));
    }

    public static void openForGroup(Screen parent, WaypointerConfig config, WaypointGroup group) {
        String title = "Route: " + routeDisplayName(group)
                + "  --  " + group.size() + " waypoint" + (group.size() == 1 ? "" : "s");
        Minecraft.getInstance().setScreen(new ExportScreen(parent, config, List.of(group), title));
    }

    public static void openForGroups(Screen parent, WaypointerConfig config,
                                     List<WaypointGroup> groups, String zoneLabel) {
        int totalPts = groups.stream().mapToInt(WaypointGroup::size).sum();
        String title = "Zone: " + zoneLabel + "  --  " + groups.size() + " group"
                + (groups.size() == 1 ? "" : "s") + ", " + totalPts + " waypoints";
        Minecraft.getInstance().setScreen(new ExportScreen(parent, config, groups, title));
    }

    // --- lifecycle ----------------------------------------------------------------------------

    @Override
    protected void init() {
        toggleSpecs.clear();
        toggleButtons.clear();
        routePickerToggleButton = null;
        routeSelectAllButton = null;

        // Label input lives directly under the header so it reads as the
        // primary "what is this export for?" field. Vanilla EditBox enforces
        // its own visual selection/cursor handling; we only need to size it
        // and forward changes through sanitizeLabel() before re-encoding.
        int labelY = PAD_OUTER + HEADER_H;
        int labelW = Math.max(80, width - PAD_OUTER * 2 - EXPORT_FOR_W - GAP);
        labelInput = new EditBox(font, PAD_OUTER, labelY, labelW, BTN_H,
                Component.literal("Label (optional)"));
        // MAX_LABEL_CHARS bounds the visible character count; on-the-wire we
        // cap bytes too. The widget cap matches the visible-character cap so
        // users see exactly when they hit the limit instead of being truncated
        // silently at encode time.
        labelInput.setMaxLength(WaypointCodec.Options.MAX_LABEL_CHARS);
        labelInput.setHint(Component.literal("Label (optional, e.g. 'Ruby Picko Topaz route')").withStyle(ChatFormatting.DARK_GRAY));
        labelInput.setValue(currentLabel);
        labelInput.setResponder(this::onLabelChanged);
        addRenderableWidget(labelInput);

        this.exportForButton = Button.builder(exportForButtonLabel(), this::openExportTargetMenu)
                .bounds(PAD_OUTER + labelW + GAP, labelY, EXPORT_FOR_W, BTN_H)
                .tooltip(Tooltip.create(Component.literal("Choose who this export is for")))
                .build();
        addRenderableWidget(exportForButton);
        updateLabelInputState();

        // Toggle row: one button per granular option. Buttons live in the order
        // they're declared, wrapping to a second row if the screen is too narrow
        // to fit them all. Each button's label flips between "X On" / "X Off"
        // and is colored to match the state so the row reads at a glance.
        registerToggle(ToggleKind.NAMES, "Names", optsBuilder.includeNames(),
                "Include waypoint names in export",
                "This format can only preserve coordinates and colors.");
        registerToggle(ToggleKind.COLORS, "Colors", optsBuilder.includeColors(),
                "Include waypoint colors in export",
                "This format does not support waypoint colors.");
        registerToggle(ToggleKind.RADII, "Radii", optsBuilder.includeRadii(),
                "Include reach radius of each waypoint in export",
                "Only Waypointer exports can preserve custom reach radii.");
        registerToggle(ToggleKind.WAYPOINT_FLAGS, "WP Flags", optsBuilder.includeWaypointFlags(),
                "Preserve subwaypoints and per-waypoint flag bits.",
                "Only Waypointer exports can preserve hide/through-wall flags.");
        registerToggle(ToggleKind.GROUP_META, "Group Meta", optsBuilder.includeGroupMeta(),
                "Include group settings (gradient, ordered/sequenced, etc) in export",
                "This format keeps basic route/category names, but not Waypointer group settings.");

        layoutToggles();
        layoutRoutePickerControls();

        // Footer: Back/Reset on the left, copy actions on the right. The plain
        // copy button stays far-right because it is the most common action;
        // the Discord-friendly wrapper sits immediately beside it.
        // Reset is in the footer rather than near the toggles because it's a
        // destructive-looking action ("did I just lose my settings?") and
        // grouping it with Back makes its scope (the whole screen) clearer.
        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("Back", this::goBackToParent));
        left.add(new GuiTokens.ButtonSpec("Reset", this::resetToConfigDefaults));

        int copyW = 140;
        int codeBlockCopyW = 136;
        int rightClusterW = codeBlockCopyW + GAP + copyW;
        int codeBlockCopyX = width - PAD_OUTER - rightClusterW;
        int copyX = width - PAD_OUTER - copyW;
        Tooltip codeBlockTooltip = Tooltip.create(Component.literal(
                "Wraps export code in 3 backticks. Useful for sending waypoints over Discord"));
        this.copyCodeBlockButton = Button.builder(Component.literal("Copy as code block"),
                        this::copyAsCodeBlock)
                .bounds(codeBlockCopyX, footerY, codeBlockCopyW, BTN_H)
                .tooltip(codeBlockTooltip)
                .build();
        this.copyButton = Button.builder(Component.literal("Copy to Clipboard"), this::copyToClipboard)
                .bounds(copyX, footerY, copyW, BTN_H).build();

        GuiTokens.layoutFooter(width, footerY, left, null, this::addRenderableWidget,
                font, PAD_OUTER, PAD_OUTER + rightClusterW + GAP_SECTION);
        addRenderableWidget(copyCodeBlockButton);
        addRenderableWidget(copyButton);

        setInitialFocus(labelInput);
        clampRouteScrollOffset();
        reencode();
    }

    private void registerToggle(ToggleKind kind, String label, boolean initialValue,
                                String tooltip, String unsupportedTooltip) {
        toggleSpecs.add(new ToggleSpec(kind, label, initialValue, tooltip, unsupportedTooltip));
    }

    private void layoutToggles() {
        int rowY = PAD_OUTER + HEADER_H + BTN_H + GAP + EXPORT_SETTINGS_HEADER_H;
        int x = PAD_OUTER;
        int rightEdge = width - PAD_OUTER;

        for (ToggleSpec spec : toggleSpecs) {
            if (x + TOGGLE_W > rightEdge) {
                x = PAD_OUTER;
                rowY += BTN_H + GAP;
            }
            Button b = Button.builder(toggleLabel(spec), new TogglePressHandler(spec))
                    .bounds(x, rowY, TOGGLE_W, BTN_H)
                    .tooltip(Tooltip.create(Component.literal(toggleTooltip(spec))))
                    .build();
            b.active = toggleSupported(spec);
            addRenderableWidget(b);
            toggleButtons.add(b);
            x += TOGGLE_W + GAP;
        }
    }

    private void layoutRoutePickerControls() {
        if (!isZoneExport()) return;

        int y = routePickerTop();
        int rightEdge = width - PAD_OUTER;
        int selectAllX = rightEdge - ROUTE_PICKER_SELECT_ALL_W;
        int toggleX = Math.max(PAD_OUTER, selectAllX - GAP - ROUTE_PICKER_TOGGLE_W);

        routePickerToggleButton = Button.builder(routePickerToggleLabel(), this::toggleRoutePicker)
                .bounds(toggleX, y, ROUTE_PICKER_TOGGLE_W, BTN_H)
                .tooltip(Tooltip.create(Component.literal("Show or hide the route selection list")))
                .build();
        routeSelectAllButton = Button.builder(Component.literal("Select all"), this::selectAllRoutes)
                .bounds(selectAllX, y, ROUTE_PICKER_SELECT_ALL_W, BTN_H)
                .tooltip(Tooltip.create(Component.literal("Include every route in this export")))
                .build();
        addRenderableWidget(routePickerToggleButton);
        addRenderableWidget(routeSelectAllButton);
        refreshRoutePickerButtons();
    }

    private Component toggleLabel(ToggleSpec spec) {
        if (!toggleSupported(spec)) {
            return Component.literal("[-] " + spec.label).withStyle(ChatFormatting.DARK_GRAY);
        }
        ChatFormatting fmt = spec.value ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY;
        String marker = spec.value ? "[+] " : "[ ] ";
        return Component.literal(marker + spec.label).withStyle(fmt);
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
        minecraft.setScreen(new ExportTargetScreen(this));
    }

    private void selectExportTarget(WaypointExportCodec.Target target) {
        exportTarget = target;
        if (exportForButton != null) exportForButton.setMessage(exportForButtonLabel());
        if (!toggleButtons.isEmpty()) refreshToggleButtons();
        if (labelInput != null) updateLabelInputState();
        reencode();
    }

    private void goBackToParent() {
        minecraft.setScreen(parent);
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
        boolean[] values = {
                optsBuilder.includeNames(),
                optsBuilder.includeColors(),
                optsBuilder.includeRadii(),
                optsBuilder.includeWaypointFlags(),
                optsBuilder.includeGroupMeta(),
        };
        for (int i = 0; i < toggleSpecs.size() && i < values.length; i++) {
            toggleSpecs.get(i).value = values[i];
        }
    }

    private void reencode() {
        List<WaypointGroup> selected = selectedGroupsForExport();
        WaypointCodec.Options options = optsBuilder.build();
        this.encoded = WaypointExportCodec.encode(selected, options, exportTarget);
    }

    private void refreshToggleButtons() {
        for (int i = 0; i < toggleSpecs.size(); i++) {
            ToggleSpec spec = toggleSpecs.get(i);
            Button button = toggleButtons.get(i);
            button.active = toggleSupported(spec);
            button.setTooltip(Tooltip.create(Component.literal(toggleTooltip(spec))));
            button.setMessage(toggleLabel(spec));
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
        return Component.literal(routePickerExpanded ? "Hide routes" : "Show routes")
                .withStyle(ChatFormatting.AQUA);
    }

    private void toggleRoutePicker(Button button) {
        routePickerExpanded = !routePickerExpanded;
        clampRouteScrollOffset();
        refreshRoutePickerButtons();
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
        labelInput.setTooltip(Tooltip.create(Component.literal(labelInputTooltipText(exportTarget))));
    }

    static String labelInputTooltipText(WaypointExportCodec.Target target) {
        return target.supportsLabel()
                ? "Optional title shown by Waypointer imports"
                : target.displayName() + " exports do not support Waypointer labels";
    }

    private Component exportForButtonLabel() {
        return Component.literal("Export for...").withStyle(ChatFormatting.AQUA);
    }

    private void copyToClipboard(Button button) {
        minecraft.keyboardHandler.setClipboard(encoded);
        copyFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyButton.setMessage(Component.literal("Copied!").withStyle(ChatFormatting.GREEN));
    }

    private void copyAsCodeBlock(Button button) {
        minecraft.keyboardHandler.setClipboard(codeBlockPayload(encoded));
        copyCodeBlockFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyCodeBlockButton.setMessage(Component.literal("Copied!").withStyle(ChatFormatting.GREEN));
    }

    static String codeBlockPayload(String payload) {
        return "```\n" + (payload == null ? "" : payload) + "\n```";
    }

    // --- rendering ----------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        updateCopyFeedback();

        g.text(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.text(font, subtitle, PAD_OUTER, PAD_OUTER + LINE_H, TEXT_DIM, false);

        int settingsY = PAD_OUTER + HEADER_H + BTN_H + GAP;
        g.text(font, "Export Settings", PAD_OUTER, settingsY, TEXT_DIM, false);
        int settingsHelpColor = showSubwaypointCompatibilityWarning() ? 0xFFFFB060 : TEXT_MUTED;
        g.text(font, settingsHelpText(), PAD_OUTER, settingsY + LINE_H,
                settingsHelpColor, false);

        int contentBottom = controlsBottom();
        if (isZoneExport()) {
            renderRoutePicker(g, mouseX, mouseY);
            contentBottom = routePickerBottom();
        }

        int y = contentBottom + GAP_SECTION;

        drawSizeSummary(g, PAD_OUTER, y);
        // Size summary spans two lines (counter + paste fit). Keep the gap tight
        // so the preview still has room at small window sizes.
        y += LINE_H * 2 + GAP_SECTION;

        g.text(font, WaypointExportCodec.previewLabel(exportTarget), PAD_OUTER, y, TEXT_DIM, false);
        y += LINE_H;
        drawPreview(g, PAD_OUTER, y, width - PAD_OUTER, height - FOOTER_H - GAP);
    }

    private void updateCopyFeedback() {
        long now = System.currentTimeMillis();
        if (copyFeedbackUntil != 0 && now > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            copyButton.setMessage(Component.literal("Copy to Clipboard"));
        }
        if (copyCodeBlockFeedbackUntil != 0 && now > copyCodeBlockFeedbackUntil) {
            copyCodeBlockFeedbackUntil = 0;
            copyCodeBlockButton.setMessage(Component.literal("Copy as code block"));
        }
    }

    private void renderRoutePicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        clampRouteScrollOffset();

        int top = routePickerTop();
        g.text(font, "Routes", PAD_OUTER, top, TEXT_DIM, false);
        String routeSummary = selectedGroupCount() + " of " + groups.size()
                + " selected, " + selectedWaypointCount() + " waypoints";
        g.text(font, routeSummary, PAD_OUTER, top + LINE_H, TEXT_MUTED, false);

        if (!routePickerExpanded) return;

        int x1 = PAD_OUTER;
        int y1 = routeListTop();
        int x2 = width - PAD_OUTER;
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

    private void drawSizeSummary(GuiGraphicsExtractor g, int x, int y) {
        int chars = encoded.length();
        ExportFitSummary fit = exportFitSummary(encoded);

        g.text(font, "Characters: " + chars, x, y, TEXT_DIM, false);

        int fitY = y + LINE_H;

        int fitColor = fit.chatOk() ? 0xFF88DD88 : 0xFFDD7070;
        String fitLine = fit.message();
        g.text(font, fitLine, x, fitY, fitColor, false);

        String sanitized = WaypointCodec.Options.sanitizeLabel(currentLabel);
        if (exportTarget.supportsLabel() && !sanitized.isEmpty()) {
            int gap = font.width("  ");
            g.text(font,
                    "label: \"" + sanitized + "\"",
                    x + font.width(fitLine) + gap, fitY, 0xFF88AACC, false);
        }
    }

    private String settingsHelpText() {
        if (showSubwaypointCompatibilityWarning()) {
            return "Warning: other mods do not support subwaypoints; they export as regular waypoints";
        }
        if (exportTarget == WaypointExportCodec.Target.WAYPOINTER) {
            return "Disabling more can make your export text shorter";
        }
        return "Unavailable options are disabled for " + exportTarget.displayName();
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
                    "Can fit in chat and commands");
        }
        if (chatOk) {
            return new ExportFitSummary(chars, wireBytes, commandBytes, chatOk, false,
                    "Can fit in chat");
        }
        return new ExportFitSummary(chars, wireBytes, commandBytes, false, false,
                "Too long for chat or commands (like /pc)");
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

    record ExportFitSummary(int characters, int wireBytes, int commandBytes,
                            boolean chatOk, boolean commandOk, String message) {}

    private void drawPreview(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);

        int innerX = x1 + PREVIEW_INSET;
        int innerY = y1 + PREVIEW_INSET;
        int innerW = x2 - x1 - PREVIEW_INSET * 2;

        List<FormattedCharSequence> lines = font.split(FormattedText.of(encoded), innerW);
        int lineH = font.lineHeight + 1;
        int available = (y2 - y1 - PREVIEW_INSET * 2) / lineH;
        int shown = Math.min(lines.size(), available);

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
        int x1 = PAD_OUTER;
        int y1 = routeListTop();
        int x2 = width - PAD_OUTER;
        int y2 = y1 + routeListHeight();
        return mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
    }

    private int routePickerTop() {
        return controlsBottom() + GAP_SECTION;
    }

    private static int routePickerHeaderHeight() {
        return Math.max(BTN_H, LINE_H * 2);
    }

    private int routeListTop() {
        return routePickerTop() + routePickerHeaderHeight() + GAP;
    }

    private int routePickerBottom() {
        if (!isZoneExport()) return controlsBottom();
        if (!routePickerExpanded) return routePickerTop() + routePickerHeaderHeight();
        return routeListTop() + routeListHeight();
    }

    private int routeListHeight() {
        return routeVisibleRowCount() * ROUTE_ROW_PITCH + ROUTE_PICKER_INSET * 2;
    }

    private int routeVisibleRowCount() {
        int byCount = Math.min(MAX_EXPANDED_ROUTE_ROWS, Math.max(1, groups.size()));
        int reservedAfterList = GAP_SECTION + LINE_H * 2 + GAP_SECTION + LINE_H + MIN_PREVIEW_H;
        int available = height - FOOTER_H - GAP - routeListTop()
                - ROUTE_PICKER_INSET * 2 - reservedAfterList;
        int bySpace = Math.max(1, available / ROUTE_ROW_PITCH);
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

    private int controlsBottom() {
        return controlsBottom(toggleButtons);
    }

    private static int controlsBottom(List<Button> buttons) {
        int bottom = 0;
        for (Button b : buttons) bottom = Math.max(bottom, b.getY() + b.getHeight());
        return bottom;
    }

    // --- helpers ------------------------------------------------------------------------------

    private boolean toggleSupported(ToggleSpec spec) {
        return switch (spec.kind) {
            case NAMES -> exportTarget.supportsNames();
            case COLORS -> exportTarget.supportsColors();
            case RADII -> exportTarget.supportsRadii();
            case WAYPOINT_FLAGS -> exportTarget.supportsWaypointFlags();
            case GROUP_META -> exportTarget.supportsGroupMeta();
        };
    }

    private String toggleTooltip(ToggleSpec spec) {
        return toggleSupported(spec) ? spec.tooltip : spec.unsupportedTooltip;
    }

    private void applyToggleValue(ToggleSpec spec) {
        switch (spec.kind) {
            case NAMES -> optsBuilder.includeNames(spec.value);
            case COLORS -> optsBuilder.includeColors(spec.value);
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
                .includeGroupMeta(config.exportIncludeGroupMeta());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    /**
     * Standalone target picker for the export screen. A dedicated screen keeps
     * the choice deliberate: users see every supported target before switching,
     * instead of cycling past formats whose disabled options can surprise them.
     */
    private static final class ExportTargetScreen extends Screen {
        private static final int MENU_W = 220;

        private final ExportScreen owner;

        ExportTargetScreen(ExportScreen owner) {
            super(Component.literal("Export For"));
            this.owner = owner;
        }

        @Override
        protected void init() {
            int x = (width - MENU_W) / 2;
            int y = PAD_OUTER + 32;

            for (WaypointExportCodec.Target target : WaypointExportCodec.Target.values()) {
                Button button = Button.builder(targetLabel(target), new TargetPressHandler(target))
                        .bounds(x, y, MENU_W, BTN_H)
                        .tooltip(Tooltip.create(Component.literal(targetTooltip(target))))
                        .build();
                addRenderableWidget(button);
                y += BTN_H + GAP;
            }

            addRenderableWidget(Button.builder(Component.literal("Back"),
                            this::returnToOwner)
                    .bounds(x, height - FOOTER_H, MENU_W, BTN_H)
                    .build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            super.extractRenderState(g, mouseX, mouseY, partial);
            int x = (width - MENU_W) / 2;
            g.text(font, getTitle(), x, PAD_OUTER, TEXT, false);
            g.text(font, "Current: " + owner.exportTarget.displayName(),
                    x, PAD_OUTER + LINE_H, TEXT_DIM, false);
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        public void onClose() { minecraft.setScreen(owner); }

        private void returnToOwner(Button button) {
            minecraft.setScreen(owner);
        }

        private Component targetLabel(WaypointExportCodec.Target target) {
            boolean selected = target == owner.exportTarget;
            ChatFormatting color = selected ? ChatFormatting.AQUA : ChatFormatting.WHITE;
            String marker = selected ? "[x] " : "[ ] ";
            return Component.literal(marker + target.displayName()).withStyle(color);
        }

        private static String targetTooltip(WaypointExportCodec.Target target) {
            return switch (target) {
                case WAYPOINTER -> "Native format. Preserves every enabled Waypointer option.";
                case SKYBLOCKER -> "For Skyblocker. Preserves coordinates and colors.\n"
                        + "Subwaypoints export as regular waypoints.";
                case SKYTILS -> "For Skytils. Preserves coordinates and colors.\n"
                        + "Subwaypoints export as regular waypoints.";
                case SKYHANNI -> "For SkyHanni. Preserves coordinates and colors.\n"
                        + "Subwaypoints export as regular waypoints.";
            };
        }

        private final class TargetPressHandler implements Button.OnPress {
            private final WaypointExportCodec.Target target;

            TargetPressHandler(WaypointExportCodec.Target target) {
                this.target = target;
            }

            @Override
            public void onPress(Button button) {
                owner.selectExportTarget(target);
                minecraft.setScreen(owner);
            }
        }
    }

    private enum ToggleKind {
        NAMES,
        COLORS,
        RADII,
        WAYPOINT_FLAGS,
        GROUP_META
    }

    private static final class ToggleSpec {
        final ToggleKind kind;
        final String label;
        final String tooltip;
        final String unsupportedTooltip;
        boolean value;

        ToggleSpec(ToggleKind kind, String label, boolean value,
                   String tooltip, String unsupportedTooltip) {
            this.kind = kind;
            this.label = label;
            this.value = value;
            this.tooltip = tooltip;
            this.unsupportedTooltip = unsupportedTooltip;
        }
    }

    private final class TogglePressHandler implements Button.OnPress {
        private final ToggleSpec spec;

        TogglePressHandler(ToggleSpec spec) {
            this.spec = spec;
        }

        @Override
        public void onPress(Button button) {
            if (!toggleSupported(spec)) {
                button.active = false;
                button.setTooltip(Tooltip.create(Component.literal(toggleTooltip(spec))));
                button.setMessage(toggleLabel(spec));
                return;
            }
            spec.value = !spec.value;
            applyToggleValue(spec);
            button.active = toggleSupported(spec);
            button.setTooltip(Tooltip.create(Component.literal(toggleTooltip(spec))));
            button.setMessage(toggleLabel(spec));
            reencode();
        }
    }
}
