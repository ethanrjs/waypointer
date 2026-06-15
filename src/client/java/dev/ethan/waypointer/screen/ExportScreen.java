package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.codec.WaypointExportCodec;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 *   6. Preview box labelled "Encoded preview (this is what gets copied)".
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
    private static final int ROUTE_TOGGLE_W = 148;

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
    private final List<Button> routeButtons = new ArrayList<>();
    private Button exportForButton;
    private Button copyButton;
    private Button copyCodeBlockButton;
    private long copyFeedbackUntil = 0L;
    private long copyCodeBlockFeedbackUntil = 0L;

    private String encoded = "";

    /** Builds an export for every group in {@code groups} with a readable subtitle. */
    public ExportScreen(Screen parent, WaypointerConfig config, List<WaypointGroup> groups, String subtitle) {
        super(Component.literal("Export Waypoints"));
        this.parent = parent;
        this.config = config;
        this.groups = groups;
        this.selectedGroups = new boolean[groups.size()];
        Arrays.fill(this.selectedGroups, true);
        this.subtitle = subtitle;
        this.optsBuilder = builderFromConfig(config);
    }

    /*[[AI-FN-DOC
Function:
openForGroup.
Purpose:
Open the export review screen for one route group.
Why this exists:
Single-route exports need the same review/copy UI as zone exports while presenting a concise title for the selected route.
When to use:
Use from group-level export actions. Do not use for exporting all visible groups in a sidebar zone; use openForGroups for that.
Inputs:
parent is the screen to return to; config provides export defaults; group is the non-null route group being exported and may belong to a normal island or dungeon room zone.
Outputs:
No return value; opens an ExportScreen.
Side effects:
Mutates Minecraft's current screen.
Failure modes:
Blank route names fall back to displayZoneLabel, which handles dungeon room prefixes and normal zone labels.
Important invariants:
The title must identify room-scoped unnamed routes with the same "Dungeons:" prefix used by the zone sidebar.
Internal logic:
Build a title from routeDisplayName and waypoint count, wrap the group in a singleton list, and set the current screen.
Pseudocode:
title = "Route: " + routeDisplayName(group) + waypoint count suffix
set Minecraft screen to new ExportScreen(parent, config, List.of(group), title)
Implementation notes:
routeDisplayName centralizes the blank-name fallback so buttons, tooltips, and titles stay consistent.
AI self-check:
Verify this method does not mutate the group or export options.
]]*/
    public static void openForGroup(Screen parent, WaypointerConfig config, WaypointGroup group) {
        String title = "Route: " + routeDisplayName(group)
                + "  --  " + group.size() + " waypoint" + (group.size() == 1 ? "" : "s");
        Minecraft.getInstance().setScreen(new ExportScreen(parent, config, List.of(group), title));
    }

    /** Entry point for a multi-group export (e.g. the whole zone). */
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
        routeButtons.clear();

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

        this.exportForButton = Button.builder(exportForButtonLabel(), b -> openExportTargetMenu())
                .bounds(PAD_OUTER + labelW + GAP, labelY, EXPORT_FOR_W, BTN_H)
                .tooltip(Tooltip.create(Component.literal("Choose who this export is for")))
                .build();
        addRenderableWidget(exportForButton);
        updateLabelInputState();

        // Toggle row: one button per granular option. Buttons live in the order
        // they're declared, wrapping to a second row if the screen is too narrow
        // to fit them all. Each button's label flips between "X On" / "X Off"
        // and is colored to match the state so the row reads at a glance.
        registerToggle("Names", optsBuilder.includeNames(), () -> exportTarget.supportsNames(),
                "Include waypoint names in export",
                "This format can only preserve coordinates and colors.",
                v -> { optsBuilder.includeNames(v); reencode(); });
        registerToggle("Colors", optsBuilder.includeColors(), () -> exportTarget.supportsColors(),
                "Include waypoint colors in export",
                "This format does not support waypoint colors.",
                v -> { optsBuilder.includeColors(v); reencode(); });
        registerToggle("Radii", optsBuilder.includeRadii(), () -> exportTarget.supportsRadii(),
                "Include reach radius of each waypoint in export",
                "Only Waypointer exports can preserve custom reach radii.",
                v -> { optsBuilder.includeRadii(v); reencode(); });
        registerToggle("WP Flags", optsBuilder.includeWaypointFlags(), () -> exportTarget.supportsWaypointFlags(),
                "Per-waypoint flag bits, safe to leave off for now.",
                "Only Waypointer exports can preserve hide/through-wall flags.",
                v -> { optsBuilder.includeWaypointFlags(v); reencode(); });
        registerToggle("Group Meta", optsBuilder.includeGroupMeta(), () -> exportTarget.supportsGroupMeta(),
                "Include group settings (gradient, ordered/sequenced, etc) in export",
                "This format keeps basic route/category names, but not Waypointer group settings.",
                v -> { optsBuilder.includeGroupMeta(v); reencode(); });

        layoutToggles();
        layoutRouteToggles();

        // Footer: Back/Reset on the left, copy actions on the right. The plain
        // copy button stays far-right because it is the most common action;
        // the Discord-friendly wrapper sits immediately beside it.
        // Reset is in the footer rather than near the toggles because it's a
        // destructive-looking action ("did I just lose my settings?") and
        // grouping it with Back makes its scope (the whole screen) clearer.
        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("Back", () -> minecraft.setScreen(parent)));
        left.add(new GuiTokens.ButtonSpec("Reset", this::resetToConfigDefaults));

        int copyW = 140;
        int codeBlockCopyW = 136;
        int rightClusterW = codeBlockCopyW + GAP + copyW;
        int codeBlockCopyX = width - PAD_OUTER - rightClusterW;
        int copyX = width - PAD_OUTER - copyW;
        Tooltip codeBlockTooltip = Tooltip.create(Component.literal(
                "Wraps export code in 3 backticks. Useful for sending waypoints over Discord"));
        this.copyCodeBlockButton = Button.builder(Component.literal("Copy as code block"),
                        b -> copyAsCodeBlock())
                .bounds(codeBlockCopyX, footerY, codeBlockCopyW, BTN_H)
                .tooltip(codeBlockTooltip)
                .build();
        this.copyButton = Button.builder(Component.literal("Copy to Clipboard"), b -> copyToClipboard())
                .bounds(copyX, footerY, copyW, BTN_H).build();

        GuiTokens.layoutFooter(width, footerY, left, null, this::addRenderableWidget,
                font, PAD_OUTER, PAD_OUTER + rightClusterW + GAP_SECTION);
        addRenderableWidget(copyCodeBlockButton);
        addRenderableWidget(copyButton);

        setInitialFocus(labelInput);
        reencode();
    }

    private void registerToggle(String label, boolean initialValue,
                                java.util.function.BooleanSupplier supported,
                                String tooltip, String unsupportedTooltip,
                                java.util.function.Consumer<Boolean> sink) {
        toggleSpecs.add(new ToggleSpec(label, initialValue, supported,
                tooltip, unsupportedTooltip, sink));
    }

    /**
     * Build buttons from {@link #toggleSpecs} in document order. The fixed
     * TOGGLE_W keeps each cell scannable; on a narrow window we wrap to a
     * second row so the layout stays usable instead of overflowing the
     * preview area.
     */
    private void layoutToggles() {
        int rowY = PAD_OUTER + HEADER_H + BTN_H + GAP + EXPORT_SETTINGS_HEADER_H;
        int x = PAD_OUTER;
        int rightEdge = width - PAD_OUTER;

        for (ToggleSpec spec : toggleSpecs) {
            if (x + TOGGLE_W > rightEdge) {
                x = PAD_OUTER;
                rowY += BTN_H + GAP;
            }
            // Capture spec.value() as a stable reference so the lambda toggles
            // the live state stored on the spec, not a snapshot taken at
            // construction time.
            Button b = Button.builder(toggleLabel(spec), btn -> {
                        spec.value = !spec.value;
                        spec.sink.accept(spec.value);
                        btn.setMessage(toggleLabel(spec));
                    })
                    .bounds(x, rowY, TOGGLE_W, BTN_H)
                    .tooltip(Tooltip.create(Component.literal(spec.tooltip())))
                    .build();
            b.active = spec.supported();
            addRenderableWidget(b);
            toggleButtons.add(b);
            x += TOGGLE_W + GAP;
        }
    }

    private void layoutRouteToggles() {
        if (!isZoneExport()) return;

        int y = controlsBottom() + GAP_SECTION + LINE_H * 2;
        int x = PAD_OUTER;
        int rightEdge = width - PAD_OUTER;

        for (int i = 0; i < groups.size(); i++) {
            if (x + ROUTE_TOGGLE_W > rightEdge) {
                x = PAD_OUTER;
                y += BTN_H + GAP;
            }
            final int idx = i;
            Button b = Button.builder(routeToggleLabel(idx), btn -> {
                        if (selectedGroups[idx] && selectedGroupCount() == 1) return;
                        selectedGroups[idx] = !selectedGroups[idx];
                        refreshRouteButtons();
                        reencode();
                    })
                    .bounds(x, y, ROUTE_TOGGLE_W, BTN_H)
                    .tooltip(Tooltip.create(Component.literal(routeTooltip(idx))))
                    .build();
            addRenderableWidget(b);
            routeButtons.add(b);
            x += ROUTE_TOGGLE_W + GAP;
        }
    }

    private static Component toggleLabel(ToggleSpec spec) {
        if (!spec.supported()) {
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

    private void openExportTargetMenu() {
        minecraft.setScreen(new ExportTargetScreen(this));
    }

    private void selectExportTarget(WaypointExportCodec.Target target) {
        exportTarget = target;
        if (exportForButton != null) exportForButton.setMessage(exportForButtonLabel());
        if (!toggleButtons.isEmpty()) refreshToggleButtons();
        if (labelInput != null) updateLabelInputState();
        reencode();
    }

    private void resetToConfigDefaults() {
        optsBuilder = builderFromConfig(config);
        currentLabel = "";
        labelInput.setValue("");
        // Re-apply each toggle's value from the freshly-built options and
        // refresh its button label so the UI matches the new state.
        applyBuilderToToggleSpecs();
        refreshToggleButtons();
        reencode();
    }

    /**
     * Refresh the {@link ToggleSpec#value} cache from the current builder.
     * The toggle specs hold their own boolean so the button label can be
     * recomputed without re-introspecting the builder; this keeps them in
     * sync after a reset.
     */
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
            button.active = spec.supported();
            button.setTooltip(Tooltip.create(Component.literal(spec.tooltip())));
            button.setMessage(toggleLabel(spec));
        }
    }

    private void refreshRouteButtons() {
        for (int i = 0; i < routeButtons.size(); i++) {
            Button button = routeButtons.get(i);
            button.setMessage(routeToggleLabel(i));
            button.setTooltip(Tooltip.create(Component.literal(routeTooltip(i))));
        }
    }

    private boolean isZoneExport() {
        return groups.size() > 1;
    }

    private int selectedGroupCount() {
        if (!isZoneExport()) return groups.size();

        int count = 0;
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
        if (!isZoneExport()) return groups;

        List<WaypointGroup> selected = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            if (selectedGroups[i]) selected.add(groups.get(i));
        }
        return selected;
    }

    /*[[AI-FN-DOC
Function:
routeToggleLabel.
Purpose:
Build the visible label for a route include/exclude toggle in zone exports.
Why this exists:
Zone exports can contain many routes, including unnamed dungeon room routes, so each toggle needs a compact but recognizable label.
When to use:
Use when creating or refreshing route selection buttons. Do not use for tooltips, which can show more detail.
Inputs:
idx is the zero-based route index into groups and selectedGroups; it must be in range.
Outputs:
Returns a styled Component with [x] or [ ] plus a clipped route display name.
Side effects:
None.
Failure modes:
Out-of-range idx would throw through groups.get, matching existing internal widget assumptions.
Important invariants:
Selected routes render aqua, excluded routes render dark gray, and unnamed dungeon room routes show the "Dungeons:" room fallback before clipping.
Internal logic:
Read the group, choose the state color, compute routeDisplayName, clip it to the fixed button width, and prepend the state marker.
Pseudocode:
group = groups[idx]
color = selected ? AQUA : DARK_GRAY
name = routeDisplayName(group)
clipped = font-aware clipped name
return component(marker + clipped).withStyle(color)
Implementation notes:
The button row still wraps across multiple lines through layoutRouteToggles; this method only controls text inside one button.
AI self-check:
Verify the visible string cannot overflow ROUTE_TOGGLE_W.
]]*/
    private Component routeToggleLabel(int idx) {
        WaypointGroup group = groups.get(idx);
        ChatFormatting color = selectedGroups[idx] ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY;
        String name = routeDisplayName(group);
        String clipped = font == null ? name : font.plainSubstrByWidth(name, ROUTE_TOGGLE_W - 28);
        return Component.literal((selectedGroups[idx] ? "[x] " : "[ ] ") + clipped)
                .withStyle(color);
    }

    /*[[AI-FN-DOC
Function:
routeTooltip.
Purpose:
Build the hover tooltip for a route include/exclude toggle.
Why this exists:
The route toggle button text is intentionally clipped, so the tooltip carries the full route name, room/island label, route size, load mode, and action state.
When to use:
Use when creating or refreshing route selection buttons. Do not use for the compact button label.
Inputs:
idx is the zero-based route index into groups and selectedGroups; it must be in range.
Outputs:
Returns a newline-delimited tooltip string.
Side effects:
None.
Failure modes:
Out-of-range idx would throw through groups.get, matching existing internal widget assumptions.
Important invariants:
The tooltip must prevent the final selected route from being excluded by explaining the disabled no-op behavior.
Internal logic:
Read the group, compute routeDisplayName and displayZoneLabel, choose the action text based on selection count, then concatenate detailed lines.
Pseudocode:
group = groups[idx]
name = routeDisplayName(group)
action = final selected route ? at least one route message : include/exclude message
return name + zone label + waypoint count/load mode + action
Implementation notes:
Including the room/island line makes multi-room dungeon exports diagnosable even when several route names are generic.
AI self-check:
Verify dungeon room tooltips contain the "Dungeons:" prefix.
]]*/
    private String routeTooltip(int idx) {
        WaypointGroup group = groups.get(idx);
        String name = routeDisplayName(group);
        String action = selectedGroups[idx] && selectedGroupCount() == 1
                ? "At least one route must stay selected."
                : "Click to " + (selectedGroups[idx] ? "exclude" : "include") + " this route.";
        return name + "\n" + displayZoneLabel(group.zoneId()) + "\n"
                + group.size() + " waypoints, "
                + group.loadMode().name().toLowerCase(java.util.Locale.ROOT) + "\n" + action;
    }

    /*[[AI-FN-DOC
Function:
routeDisplayName.
Purpose:
Return the best human-readable name for a route choice in export UI.
Why this exists:
Route names can be blank, especially for room-scoped route data, and showing "(unnamed)" makes multi-room export choices impossible to distinguish.
When to use:
Use for export titles, route toggle labels, and tooltips. Do not use for persisted route names because it is a display fallback only.
Inputs:
group is the route group whose name and zone id should be inspected.
Outputs:
Returns the trimmed route name when present, otherwise displayZoneLabel(group.zoneId()).
Side effects:
None.
Failure modes:
If the zone id is unknown, displayZoneLabel falls back to Zone.fromId prettification.
Important invariants:
Blank dungeon room route names must display as "Dungeons: <room>".
Internal logic:
Trim the route name; if non-empty return it, otherwise return the display label for the group's zone.
Pseudocode:
name = group.name.trim
if name not empty, return name
return displayZoneLabel(group.zoneId)
Implementation notes:
This avoids writing fallback text into the route itself.
AI self-check:
Verify no route data is mutated and blank names remain blank in storage/export payloads unless names are explicitly included by codec options.
]]*/
    private static String routeDisplayName(WaypointGroup group) {
        String name = group.name().trim();
        if (!name.isEmpty()) return name;
        return displayZoneLabel(group.zoneId());
    }

    /*[[AI-FN-DOC
Function:
displayZoneLabel.
Purpose:
Return the UI label for a route group's zone inside the export screen.
Why this exists:
Dungeon room zones need catalog display names plus the "Dungeons:" prefix, while normal islands should keep using Zone.fromId.
When to use:
Use for export-screen labels and tooltips. Do not use for codec zone ids or third-party island ids.
Inputs:
zoneId may be a dungeon room id, normal island id, or unknown id.
Outputs:
Returns "Dungeons: <room name>" for room definitions, otherwise Zone.fromId(zoneId).displayName().
Side effects:
May read DungeonRoomData's bundled/custom definition maps; does not mutate state.
Failure modes:
Unknown ids fall back through Zone.fromId.
Important invariants:
This must mirror WaypointerScreen's room-prefix behavior for user-facing consistency.
Internal logic:
Look up a dungeon room definition; if present, prefix its display name; otherwise fall back to Zone.fromId.
Pseudocode:
definition = DungeonRoomData.definition(zoneId)
if definition exists, return "Dungeons: " + definition.displayName
return Zone.fromId(zoneId).displayName
Implementation notes:
The helper is intentionally local to the export screen to avoid changing codec or storage semantics.
AI self-check:
Verify the prefixed label is never passed as a persisted id.
]]*/
    private static String displayZoneLabel(String zoneId) {
        DungeonRoomDefinition definition = DungeonRoomData.definition(zoneId);
        if (definition != null) return DUNGEON_ROOM_LABEL_PREFIX + definition.displayName();
        return Zone.fromId(zoneId).displayName();
    }

    private void updateLabelInputState() {
        labelInput.active = exportTarget.supportsLabel();
        labelInput.setTooltip(Tooltip.create(Component.literal(exportTarget.supportsLabel()
                ? "Optional title shown by Waypointer imports"
                : exportTarget.displayName() + " exports do not support Waypointer labels")));
    }

    private Component exportForButtonLabel() {
        return Component.literal("Export for...").withStyle(ChatFormatting.AQUA);
    }

    private void copyToClipboard() {
        minecraft.keyboardHandler.setClipboard(encoded);
        copyFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyButton.setMessage(Component.literal("Copied!").withStyle(ChatFormatting.GREEN));
    }

    private void copyAsCodeBlock() {
        minecraft.keyboardHandler.setClipboard("```\n" + encoded + "\n```");
        copyCodeBlockFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyCodeBlockButton.setMessage(Component.literal("Copied!").withStyle(ChatFormatting.GREEN));
    }

    // --- rendering ----------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        if (copyFeedbackUntil != 0 && System.currentTimeMillis() > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            copyButton.setMessage(Component.literal("Copy to Clipboard"));
        }
        if (copyCodeBlockFeedbackUntil != 0 && System.currentTimeMillis() > copyCodeBlockFeedbackUntil) {
            copyCodeBlockFeedbackUntil = 0;
            copyCodeBlockButton.setMessage(Component.literal("Copy as code block"));
        }

        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.drawString(font, subtitle, PAD_OUTER, PAD_OUTER + LINE_H, TEXT_DIM, false);

        int settingsY = PAD_OUTER + HEADER_H + BTN_H + GAP;
        g.drawString(font, "Export Settings", PAD_OUTER, settingsY, TEXT_DIM, false);
        int settingsHelpColor = showSubwaypointCompatibilityWarning() ? 0xFFFFB060 : TEXT_MUTED;
        g.drawString(font, settingsHelpText(), PAD_OUTER, settingsY + LINE_H,
                settingsHelpColor, false);

        // Rows after the toggle grid: size summary, then preview. The toggle
        // grid's actual bottom depends on how many rows it wrapped to, so we
        // recompute by walking the registered button positions instead of
        // hard-coding a y offset.
        if (isZoneExport()) {
            int routeY = controlsBottom(toggleButtons) + GAP_SECTION;
            g.drawString(font, "Routes", PAD_OUTER, routeY, TEXT_DIM, false);
            String routeSummary = selectedGroupCount() + " of " + groups.size()
                    + " selected, " + selectedWaypointCount() + " waypoints";
            g.drawString(font, routeSummary, PAD_OUTER, routeY + LINE_H, TEXT_MUTED, false);
        }

        int y = controlsBottom() + GAP_SECTION;

        drawSizeSummary(g, PAD_OUTER, y);
        // Size summary spans two lines (counter + paste fit). Keep the gap tight
        // so the preview still has room at small window sizes.
        y += LINE_H * 2 + GAP_SECTION;

        g.drawString(font, WaypointExportCodec.previewLabel(exportTarget), PAD_OUTER, y, TEXT_DIM, false);
        y += LINE_H;
        drawPreview(g, PAD_OUTER, y, width - PAD_OUTER, height - FOOTER_H - GAP);
    }

    /**
     * Render a neutral character count plus a single paste-fit summary.
     *
     * User-visible strings are deliberately plain-language -- no byte
     * counts, no "cap", no "wire" -- because almost nobody pasting a route
     * to a friend wants to reason about UTF-8 size limits.
     */
    private void drawSizeSummary(GuiGraphics g, int x, int y) {
        int chars = encoded.length();
        int wireBytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int commandBytes = REFERENCE_COMMAND_PREFIX_BYTES + wireBytes;

        boolean chatOk = chars <= CHAT_INPUT_LIMIT;
        boolean commandOk = commandBytes <= COMMAND_WIRE_LIMIT_BYTES;

        g.drawString(font, "Characters: " + chars, x, y, TEXT_DIM, false);

        int fitY = y + LINE_H;

        int fitColor = chatOk ? 0xFF88DD88 : 0xFFDD7070;
        String fitLine;
        if (commandOk) {
            fitLine = "Can fit in chat and commands";
        } else if (chatOk) {
            fitLine = "Can fit in chat";
        } else {
            fitLine = "Too long for chat or commands (like /pc)";
        }
        g.drawString(font, fitLine, x, fitY, fitColor, false);

        String sanitized = WaypointCodec.Options.sanitizeLabel(currentLabel);
        if (exportTarget.supportsLabel() && !sanitized.isEmpty()) {
            int gap = font.width("  ");
            g.drawString(font,
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
        return exportTarget != WaypointExportCodec.Target.WAYPOINTER
                && selectedExportHasSubwaypoints();
    }

    private boolean selectedExportHasSubwaypoints() {
        for (WaypointGroup group : selectedGroupsForExport()) {
            if (group.hasSubwaypoints()) return true;
        }
        return false;
    }

    private void drawPreview(GuiGraphics g, int x1, int y1, int x2, int y2) {
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
            g.drawString(font, lines.get(i), innerX, y, TEXT, false);
        }
        if (shown < lines.size()) {
            String ellipsis = "...(" + (lines.size() - shown) + " more line"
                    + (lines.size() - shown == 1 ? "" : "s") + ", full payload goes to clipboard)";
            g.drawString(font, ellipsis, innerX, y, TEXT_MUTED, false);
        }
    }

    private int controlsBottom() {
        return Math.max(controlsBottom(toggleButtons), controlsBottom(routeButtons));
    }

    private static int controlsBottom(List<Button> buttons) {
        int bottom = 0;
        for (Button b : buttons) bottom = Math.max(bottom, b.getY() + b.getHeight());
        return bottom;
    }

    // --- helpers ------------------------------------------------------------------------------

    private static WaypointCodec.Options.Builder builderFromConfig(WaypointerConfig config) {
        return WaypointCodec.Options.builder()
                .includeNames(config.exportIncludeNames())
                .includeColors(config.exportIncludeColors())
                .includeRadii(config.exportIncludeRadii())
                .includeWaypointFlags(config.exportIncludeWaypointFlags())
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
                Button button = Button.builder(targetLabel(target), b -> {
                            owner.selectExportTarget(target);
                            minecraft.setScreen(owner);
                        })
                        .bounds(x, y, MENU_W, BTN_H)
                        .tooltip(Tooltip.create(Component.literal(targetTooltip(target))))
                        .build();
                addRenderableWidget(button);
                y += BTN_H + GAP;
            }

            addRenderableWidget(Button.builder(Component.literal("Back"),
                            b -> minecraft.setScreen(owner))
                    .bounds(x, height - FOOTER_H, MENU_W, BTN_H)
                    .build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            super.render(g, mouseX, mouseY, partial);
            int x = (width - MENU_W) / 2;
            g.drawString(font, getTitle(), x, PAD_OUTER, TEXT, false);
            g.drawString(font, "Current: " + owner.exportTarget.displayName(),
                    x, PAD_OUTER + LINE_H, TEXT_DIM, false);
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        public void onClose() { minecraft.setScreen(owner); }

        private Component targetLabel(WaypointExportCodec.Target target) {
            boolean selected = target == owner.exportTarget;
            ChatFormatting color = selected ? ChatFormatting.AQUA : ChatFormatting.WHITE;
            String marker = selected ? "[x] " : "[ ] ";
            return Component.literal(marker + target.displayName()).withStyle(color);
        }

        private static String targetTooltip(WaypointExportCodec.Target target) {
            return switch (target) {
                case WAYPOINTER -> "Native format. Preserves every enabled Waypointer option.";
                case SKYBLOCKER -> "For Skyblocker. Preserves coordinates, names, and colors.\n"
                        + "Subwaypoints export as regular waypoints.";
                case SKYTILS -> "For Skytils. Preserves coordinates, names, and colors.\n"
                        + "Subwaypoints export as regular waypoints.";
                case SKYHANNI -> "For SkyHanni. Preserves coordinates, names, and colors.\n"
                        + "Subwaypoints export as regular waypoints.";
            };
        }
    }

    /**
     * Holds the live state for a single export-option toggle. Exists so the
     * button render lambda can mutate one shared place (rather than chasing
     * the option through three callbacks) and so a Reset can rewrite all
     * toggle values without touching the buttons themselves.
     */
    private static final class ToggleSpec {
        final String label;
        final String tooltip;
        final String unsupportedTooltip;
        final java.util.function.BooleanSupplier supported;
        final java.util.function.Consumer<Boolean> sink;
        boolean value;

        ToggleSpec(String label, boolean value,
                   java.util.function.BooleanSupplier supported,
                   String tooltip, String unsupportedTooltip,
                   java.util.function.Consumer<Boolean> sink) {
            this.label = label;
            this.value = value;
            this.supported = supported;
            this.tooltip = tooltip;
            this.unsupportedTooltip = unsupportedTooltip;
            this.sink = sink;
        }

        boolean supported() {
            return supported.getAsBoolean();
        }

        String tooltip() {
            return supported() ? tooltip : unsupportedTooltip;
        }
    }
}
