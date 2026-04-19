package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.WaypointGroup;
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
 * optional sender label, and a single "Copy to Clipboard" action.
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
 *   2. Sanitized label EditBox -- shown to recipients on hover and on import.
 *   3. Toggle row per export option (Names, Colors, Radii, Waypoint Flags,
 *      Group Meta). Each carries a tooltip explaining the trade-off.
 *   4. Reset-to-defaults button so users who experimented can recover the
 *      sensible config preset without leaving the screen.
 *   5. Size summary: char count + chat-fit indicator.
 *   6. Preview box labelled "Encoded preview (this is what gets copied)".
 */
public final class ExportScreen extends Screen {

    private static final int PREVIEW_INSET = 6;

    /** Minecraft chat input cap. Exports longer than this can't be pasted directly. */
    private static final int CHAT_INPUT_LIMIT = 256;

    /**
     * UTF-8 byte ceiling a server enforces on serverbound command packets.
     *
     * Chat INPUT is measured in characters (256 chars fit in the textbox), but
     * the wire packet is measured in bytes, and Hypixel's Watchdog closes the
     * socket the instant a {@code ServerboundChatCommandPacket} serialises past
     * this cap. CJK glyphs are 3 UTF-8 bytes each so a visibly-short codec can
     * silently punch over the ceiling and disconnect the sender.
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

    /** Fixed width of each toggle button so the row stays scannable across screen sizes. */
    private static final int TOGGLE_W = 96;

    private final Screen parent;
    private final WaypointerConfig config;
    private final List<WaypointGroup> groups;
    private final String subtitle;

    /** Mutable export options the user is currently building. */
    private WaypointCodec.Options.Builder optsBuilder;
    private String currentLabel = "";

    private EditBox labelInput;
    private final List<ToggleSpec> toggleSpecs = new ArrayList<>();
    private final List<Button> toggleButtons = new ArrayList<>();
    private Button copyButton;
    private long copyFeedbackUntil = 0L;

    private String encoded = "";

    /** Builds an export for every group in {@code groups} with a readable subtitle. */
    public ExportScreen(Screen parent, WaypointerConfig config, List<WaypointGroup> groups, String subtitle) {
        super(Component.literal("Export Waypoints"));
        this.parent = parent;
        this.config = config;
        this.groups = groups;
        this.subtitle = subtitle;
        this.optsBuilder = builderFromConfig(config);
    }

    /** Entry point for a single-group export; wraps the group in a list with a title. */
    public static void openForGroup(Screen parent, WaypointerConfig config, WaypointGroup group) {
        String title = "Route: " + (group.name().isEmpty() ? "(unnamed)" : group.name())
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

        // Label input lives directly under the header so it reads as the
        // primary "what is this export for?" field. Vanilla EditBox enforces
        // its own visual selection/cursor handling; we only need to size it
        // and forward changes through sanitizeLabel() before re-encoding.
        int labelY = PAD_OUTER + HEADER_H;
        int labelW = width - PAD_OUTER * 2;
        labelInput = new EditBox(font, PAD_OUTER, labelY, labelW, BTN_H,
                Component.literal("Label (optional)"));
        // MAX_LABEL_CHARS bounds the visible character count; on-the-wire we
        // cap bytes too. The widget cap matches the visible-character cap so
        // users see exactly when they hit the limit instead of being truncated
        // silently at encode time.
        labelInput.setMaxLength(WaypointCodec.Options.MAX_LABEL_CHARS);
        labelInput.setHint(Component.literal("Label (optional, e.g. 'F7 dragon path')").withStyle(ChatFormatting.DARK_GRAY));
        labelInput.setValue(currentLabel);
        labelInput.setResponder(this::onLabelChanged);
        addRenderableWidget(labelInput);

        // Toggle row: one button per granular option. Buttons live in the order
        // they're declared, wrapping to a second row if the screen is too narrow
        // to fit them all. Each button's label flips between "X On" / "X Off"
        // and is colored to match the state so the row reads at a glance.
        registerToggle("Names", optsBuilder.includeNames(),
                "Include each waypoint's display name. Recipients will see the\n"
              + "labels you typed. Strip if names contain personal info.",
                v -> { optsBuilder.includeNames(v); reencode(); });
        registerToggle("Colors", optsBuilder.includeColors(),
                "Per-waypoint colors. Strip to share a route the recipient will\n"
              + "recolor to match their own palette.",
                v -> { optsBuilder.includeColors(v); reencode(); });
        registerToggle("Radii", optsBuilder.includeRadii(),
                "Per-waypoint reach radii. Off by default because most routes use\n"
              + "the group default; only matters when individual waypoints were tuned.",
                v -> { optsBuilder.includeRadii(v); reencode(); });
        registerToggle("WP Flags", optsBuilder.includeWaypointFlags(),
                "Per-waypoint flag bits (currently just 'shown'). Almost always\n"
              + "default; safe to leave off.",
                v -> { optsBuilder.includeWaypointFlags(v); reencode(); });
        registerToggle("Group Meta", optsBuilder.includeGroupMeta(),
                "Group-level settings: gradient mode, load mode, custom default\n"
              + "radius. Strip for a barebones path; recipients will see your\n"
              + "groups with default settings.",
                v -> { optsBuilder.includeGroupMeta(v); reencode(); });

        layoutToggles();

        // Footer: Back on the left, Reset in the middle, Copy on the right.
        // Reset is in the footer rather than near the toggles because it's a
        // destructive-looking action ("did I just lose my settings?") and
        // grouping it with Back makes its scope (the whole screen) clearer.
        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("Back", () -> minecraft.setScreen(parent)));
        left.add(new GuiTokens.ButtonSpec("Reset", this::resetToConfigDefaults));

        int copyW = 140;
        this.copyButton = Button.builder(Component.literal("Copy to Clipboard"), b -> copyToClipboard())
                .bounds(width - PAD_OUTER - copyW, footerY, copyW, 20).build();

        GuiTokens.layoutFooter(width, footerY, left, null, this::addRenderableWidget, font);
        addRenderableWidget(copyButton);

        setInitialFocus(labelInput);
        reencode();
    }

    private void registerToggle(String label, boolean initialValue, String tooltip,
                                java.util.function.Consumer<Boolean> sink) {
        toggleSpecs.add(new ToggleSpec(label, initialValue, tooltip, sink));
    }

    /**
     * Build buttons from {@link #toggleSpecs} in document order. The fixed
     * TOGGLE_W keeps each cell scannable; on a narrow window we wrap to a
     * second row so the layout stays usable instead of overflowing the
     * preview area.
     */
    private void layoutToggles() {
        int rowY = PAD_OUTER + HEADER_H + BTN_H + GAP;
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
                    .tooltip(Tooltip.create(Component.literal(spec.tooltip)))
                    .build();
            addRenderableWidget(b);
            toggleButtons.add(b);
            x += TOGGLE_W + GAP;
        }
    }

    private static Component toggleLabel(ToggleSpec spec) {
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

    private void resetToConfigDefaults() {
        optsBuilder = builderFromConfig(config);
        currentLabel = "";
        labelInput.setValue("");
        // Re-apply each toggle's value from the freshly-built options and
        // refresh its button label so the UI matches the new state.
        applyBuilderToToggleSpecs();
        for (int i = 0; i < toggleSpecs.size(); i++) {
            toggleButtons.get(i).setMessage(toggleLabel(toggleSpecs.get(i)));
        }
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
        this.encoded = WaypointCodec.encode(groups, optsBuilder.build());
    }

    private void copyToClipboard() {
        minecraft.keyboardHandler.setClipboard(encoded);
        copyFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyButton.setMessage(Component.literal("Copied!").withStyle(ChatFormatting.GREEN));
    }

    // --- rendering ----------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        if (copyFeedbackUntil != 0 && System.currentTimeMillis() > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            copyButton.setMessage(Component.literal("Copy to Clipboard"));
        }

        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.drawString(font, subtitle, PAD_OUTER, PAD_OUTER + LINE_H, TEXT_DIM, false);

        // Rows after the toggle grid: size summary, then preview. The toggle
        // grid's actual bottom depends on how many rows it wrapped to, so we
        // recompute by walking the registered button positions instead of
        // hard-coding a y offset.
        int togglesBottom = 0;
        for (Button b : toggleButtons) togglesBottom = Math.max(togglesBottom, b.getY() + b.getHeight());
        int y = togglesBottom + GAP_SECTION;

        drawSizeSummary(g, PAD_OUTER, y);
        // Size summary spans two lines (chat-char fit + command-wire-byte
        // fit). Keep the section gap tight so the preview still has room
        // to show multiple wrap lines at small window sizes.
        y += LINE_H * 2 + GAP_SECTION;

        g.drawString(font, "Encoded preview (this is what gets copied)", PAD_OUTER, y, TEXT_DIM, false);
        y += LINE_H;
        drawPreview(g, PAD_OUTER, y, width - PAD_OUTER, height - FOOTER_H - GAP);
    }

    /**
     * Render the two-line fit summary: chars-in-chat-textbox on the first line,
     * wire-bytes-in-a-command on the second.
     *
     * Both checks matter and they fail independently. The chat textbox rejects
     * at 256 characters regardless of byte count, so a purely-ASCII export can
     * squeak through the textbox but still never be relevant; meanwhile the
     * server's command packet cap is 256 BYTES, which with 3-byte CJK glyphs
     * trips long before the chat-char cap does. Surfacing only the char cap
     * (as this screen previously did) meant routes that looked "safe to share"
     * disconnected the sender the moment they typed {@code /pc <codec>}.
     */
    private void drawSizeSummary(GuiGraphics g, int x, int y) {
        int chars = encoded.length();
        int wireBytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int commandBytes = REFERENCE_COMMAND_PREFIX_BYTES + wireBytes;

        boolean chatOk = chars <= CHAT_INPUT_LIMIT;
        boolean commandOk = commandBytes <= COMMAND_WIRE_LIMIT_BYTES;

        int chatColor = chatOk ? 0xFF88DD88 : 0xFFDD7070;
        String chatFit = chatOk ? "fits in a chat message" : "exceeds " + CHAT_INPUT_LIMIT + "-char chat input cap";
        String chatLine = chars + " chars (" + chatFit + ")";
        g.drawString(font, chatLine, x, y, chatColor, false);

        String sanitized = WaypointCodec.Options.sanitizeLabel(currentLabel);
        if (!sanitized.isEmpty()) {
            int gap = font.width("  ");
            int labelBytes = sanitized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            g.drawString(font,
                    "label: " + labelBytes + "B sanitized",
                    x + font.width(chatLine) + gap, y, 0xFF88AACC, false);
        }

        // Second line: wire-byte budget for sharing via a command. The user
        // thinks in "can I paste this into /pc?" terms; we answer that
        // directly instead of making them translate a character count into
        // UTF-8 bytes in their head.
        int cmdColor = commandOk ? 0xFF88DD88 : 0xFFDD7070;
        String cmdFit = commandOk
                ? "fits after /pc (" + commandBytes + "/" + COMMAND_WIRE_LIMIT_BYTES + " B on the wire)"
                : "too long to share via /pc -- would disconnect you ("
                        + commandBytes + "/" + COMMAND_WIRE_LIMIT_BYTES + " B)";
        g.drawString(font, wireBytes + " B  " + cmdFit, x, y + LINE_H, cmdColor, false);
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
     * Holds the live state for a single export-option toggle. Exists so the
     * button render lambda can mutate one shared place (rather than chasing
     * the option through three callbacks) and so a Reset can rewrite all
     * toggle values without touching the buttons themselves.
     */
    private static final class ToggleSpec {
        final String label;
        final String tooltip;
        final java.util.function.Consumer<Boolean> sink;
        boolean value;

        ToggleSpec(String label, boolean value, String tooltip,
                   java.util.function.Consumer<Boolean> sink) {
            this.label = label;
            this.value = value;
            this.tooltip = tooltip;
            this.sink = sink;
        }
    }
}
