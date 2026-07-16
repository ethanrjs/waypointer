package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.compat.MinecraftCompat;
import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.codec.DecodeDebug;
import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.debug.DebugEventLog;
import dev.ethan.waypointer.debug.DebugSignals;
import dev.ethan.waypointer.debug.PerformanceStats;
import dev.ethan.waypointer.screen.settings.SettingsCatalog;
import dev.ethan.waypointer.dungeon.DungeonCoreSignature;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomZoneBridge;
import dev.ethan.waypointer.dungeon.DungeonRouteSession;
import dev.ethan.waypointer.dungeon.DungeonStateTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.ethan.waypointer.screen.GuiTokens.ACCENT;
import static dev.ethan.waypointer.screen.GuiTokens.BORDER;
import static dev.ethan.waypointer.screen.GuiTokens.FOOTER_H;
import static dev.ethan.waypointer.screen.GuiTokens.GAP;
import static dev.ethan.waypointer.screen.GuiTokens.GAP_SECTION;
import static dev.ethan.waypointer.screen.GuiTokens.GAP_TIGHT;
import static dev.ethan.waypointer.screen.GuiTokens.HOVER;
import static dev.ethan.waypointer.screen.GuiTokens.PAD_OUTER;
import static dev.ethan.waypointer.screen.GuiTokens.ROW_H;
import static dev.ethan.waypointer.screen.GuiTokens.SELECTED;
import static dev.ethan.waypointer.screen.GuiTokens.SIDEBAR_W;
import static dev.ethan.waypointer.screen.GuiTokens.SURFACE;
import static dev.ethan.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static dev.ethan.waypointer.screen.GuiTokens.TEXT;
import static dev.ethan.waypointer.screen.GuiTokens.TEXT_DIM;
import static dev.ethan.waypointer.screen.GuiTokens.TEXT_MUTED;

/**
 * Wire-level inspector for Waypointer codec strings. Mirrors the clinical sidebar/main
 * shape used by the other screens: a jump-list of sections on the left, a
 * structured, column-aligned report on the right. Never mutates the user's
 * groups or config -- read-only diagnostic surface for {@code /wp debug}.
 *
 * Rendering is a flat {@code List<Row>} with a uniform pixel pitch so scroll math
 * stays a single integer. Every section gets an anchor index so clicking the
 * sidebar jumps the report, and manual scrolling updates the sidebar highlight to
 * whatever section's header just crossed the top of the viewport.
 */
public final class DebugInspectScreen extends Screen {

    // --- report row model -----------------------------------------------------------------
    //
    // Using a sealed type instead of preformatted text strings so each row can render with
    // pixel-aligned columns (the default MC font is proportional -- space-padded keys never
    // line up cleanly). Every row renders in one lineH of vertical space so scroll can stay
    // an integer row index; breathing room above sections is a Blank row, not a tall row.

    private sealed interface Row {
        record Section(String title) implements Row {}
        record KV(String key, String value) implements Row {}
        record KVDim(String key, String value) implements Row {}
        record KVWarn(String key, String value) implements Row {}
        record Bit(int bit, String label, boolean set) implements Row {}
        record BitNote(String text) implements Row {}
        record PoolEntry(int index, String text) implements Row {}
        record WP(DecodeDebug.WaypointDebug wp) implements Row {}
        record Blank() implements Row {}
    }

    private record SectionAnchor(String label, String subtitle, int rowIndex) {}

    /** How many rows a single wheel notch advances the scroll. */
    private static final int SCROLL_ROWS_PER_NOTCH = 3;

    /** How long the Copy button keeps its confirmation label before reverting. */
    private static final long FEEDBACK_MS = 1500L;

    /** Pixel column where the value half of every key:value row starts (relative to the row's inner left). */
    private static final int KEY_COL_W = 140;

    /** Pixel column where a bit-row's label starts (after "bit N"). */
    private static final int BIT_LABEL_OFFSET = 30;

    /** Pixel column where a string-pool entry's content starts (after "[N]"). */
    private static final int POOL_CONTENT_OFFSET = 30;

    /** Subdued warm tone for error surfaces. Errors are signal, not decoration -- allowed as a one-off. */
    private static final int ERROR_TONE = 0xFFCA7A7A;
    private static final int SUCCESS_TONE = 0xFF8BD49C;
    private static final int WARN_TONE = 0xFFE6C07B;
    private static final int NUMBER_TONE = 0xFF82AAFF;
    private static final int STRING_TONE = 0xFFC3E88D;
    private static final int HEX_TONE = 0xFFFFCB6B;
    private static final int KEYWORD_TONE = 0xFFC792EA;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    private DecodeDebug debug;
    private PerformanceStats performanceStats;
    private String lastError;
    private String codecError;
    private final List<Row> rows = new ArrayList<>();
    private final List<SectionAnchor> sections = new ArrayList<>();
    private int scrollRows;
    private int selectedSection;

    private Button copyButton;
    private long copyFeedbackUntil;

    // Geometry recomputed each render(). Stashed so mouse handlers can hit-test.
    private int sidebarX1, sidebarX2, sidebarContentTop;
    private int mainX1, mainX2, mainTop, mainBottom;
    private int visibleRowCount;

    public DebugInspectScreen(Screen parent, ActiveGroupManager manager,
                              WaypointerConfig config) {
        super(Component.literal("Waypointer Debug"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
    }

    public static void open(Screen parent) {
        MinecraftCompat.setScreen(Minecraft.getInstance(), new DebugInspectScreen(parent));
    }

    public DebugInspectScreen(Screen parent) {
        this(parent, null, null);
    }

    public static void open(Screen parent, ActiveGroupManager manager,
                            WaypointerConfig config) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new DebugInspectScreen(parent, manager, config));
    }

    // --- lifecycle -------------------------------------------------------------------------

    @Override
    protected void init() {
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("Refresh", this::loadCombinedReport));
        if (config != null) {
            left.add(new GuiTokens.ButtonSpec("Perf test", 88, this::openPerfStressTest));
        }
        left.add(new GuiTokens.ButtonSpec("Copy report", this::copyReportToClipboard));
        GuiTokens.ButtonSpec back = new GuiTokens.ButtonSpec("Back", this::onClose);

        int footerY = height - FOOTER_H;
        GuiTokens.layoutFooter(width, footerY, left, back,
                b -> {
            // Stash the Copy button so we can swap its label on the copy-confirmation flash.
            // Matching on the label string is ugly but the footer helper doesn't expose
            // a better hook and this screen builds exactly one button with that label.
            if ("Copy report".contentEquals(b.getMessage().getString())) {
                copyButton = b;
            }
            addRenderableWidget(b);
        }, font);

        if (rows.isEmpty() && lastError == null) {
            loadCombinedReport();
        }
    }

    // --- actions ---------------------------------------------------------------------------

    /**
     * The FPS stress test lives on the settings screen (System > Diagnostics);
     * this deep-links there rather than duplicating the sweep UI here. Back
     * from settings returns to this inspector.
     */
    private void openPerfStressTest() {
        MinecraftCompat.setScreen(minecraft, SettingsScreen.atSetting(this, config,
                WaypointerClient.dungeonConfig(), SettingsCatalog.ACTION_PERF_TEST));
    }

    private void resetReportState() {
        this.debug = null;
        this.performanceStats = null;
        this.codecError = null;
        this.lastError = null;
        this.rows.clear();
        this.sections.clear();
        this.scrollRows = 0;
        this.selectedSection = 0;
    }

    private void loadCombinedReport() {
        resetReportState();

        if (manager == null || config == null) {
            addSection(rows, sections, "Performance Snapshot", "unavailable");
            rows.add(new Row.KVWarn("Unavailable",
                    "Open this screen through /wp debug to capture live Waypointer state."));
        } else {
            var player = Minecraft.getInstance().player;
            this.performanceStats = player == null
                    ? PerformanceStats.capture(manager, config)
                    : PerformanceStats.capture(manager, config,
                    player.getX(), player.getY(), player.getZ());
            buildPerformanceReport(this.performanceStats, config, rows, sections);
        }

        buildDungeonDiagnosticsReport(DebugSignals.dungeonDebugSnapshot(), rows, sections);

        String text = minecraft.keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            this.codecError = "Clipboard is empty. Copy a " + WaypointCodec.MAGIC
                    + " export to inspect its codec payload here.";
            buildCodecClipboardReport(rows, sections, codecError);
            return;
        }
        String trimmed = text.trim();
        if (!WaypointCodec.isCodecString(trimmed)) {
            this.codecError = "Clipboard does not start with " + WaypointCodec.MAGIC
                    + ". Copy a Waypointer export string to inspect codec details.";
            buildCodecClipboardReport(rows, sections, codecError);
            return;
        }

        try {
            this.debug = WaypointCodec.debugDecode(trimmed);
            buildReport(this.debug, rows, sections);
        } catch (IllegalArgumentException e) {
            this.codecError = "Decode failed: " + e.getMessage();
            buildCodecClipboardReport(rows, sections, codecError);
        }
    }

    private void copyReportToClipboard() {
        if (rows.isEmpty() || copyButton == null) return;
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) sb.append(rowAsPlainText(r)).append('\n');
        minecraft.keyboardHandler.setClipboard(sb.toString());
        copyFeedbackUntil = System.currentTimeMillis() + FEEDBACK_MS;
        // Plain label swap -- no color. The design system reserves the one accent for
        // "the currently selected thing", not for ephemeral UI feedback.
        copyButton.setMessage(Component.literal("Copied"));
    }

    // --- input -----------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (mouseX >= mainX1 && mouseX <= mainX2 && mouseY >= mainTop && mouseY <= mainBottom
                && !rows.isEmpty()) {
            int maxScroll = Math.max(0, rows.size() - visibleRowCount);
            scrollRows = Mth.clamp(scrollRows - (int) (vert * SCROLL_ROWS_PER_NOTCH), 0, maxScroll);
            syncSelectedSectionWithScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horiz, vert);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || sections.isEmpty()) return false;

        double mx = event.x();
        double my = event.y();
        if (mx < sidebarX1 || mx > sidebarX2 || my < sidebarContentTop) return false;

        int rowIdx = (int) ((my - sidebarContentTop) / ROW_H);
        if (rowIdx < 0 || rowIdx >= sections.size()) return false;
        jumpToSection(rowIdx);
        return true;
    }

    private void jumpToSection(int idx) {
        selectedSection = idx;
        int maxScroll = Math.max(0, rows.size() - visibleRowCount);
        scrollRows = Mth.clamp(sections.get(idx).rowIndex(), 0, maxScroll);
    }

    private void syncSelectedSectionWithScroll() {
        // The "current" section is the last one whose header has scrolled at-or-above
        // the top of the viewport. A binary search is overkill given sections <20;
        // a linear walk is honest about the workload.
        int best = 0;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).rowIndex() <= scrollRows) best = i;
            else break;
        }
        selectedSection = best;
    }

    // --- rendering -------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        if (copyFeedbackUntil != 0 && System.currentTimeMillis() > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            if (copyButton != null) copyButton.setMessage(Component.literal("Copy report"));
        }

        // --- header (title + right-aligned compact summary) ------------------------------
        g.text(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        String summary = buildHeaderSummary();
        if (summary != null) {
            int sw = font.width(summary);
            g.text(font, summary, width - PAD_OUTER - sw, PAD_OUTER, TEXT_DIM, false);
        }

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        this.sidebarX1 = PAD_OUTER;
        this.sidebarX2 = sidebarX1 + SIDEBAR_W;
        this.mainX1 = sidebarX2 + GAP_SECTION;
        this.mainX2 = width - PAD_OUTER;
        this.mainTop = top;
        this.mainBottom = bottom;

        renderSidebar(g, sidebarX1, top, sidebarX2, bottom, mouseX, mouseY);
        renderMain(g, mainX1, top, mainX2, bottom);
    }

    private String buildHeaderSummary() {
        StringBuilder summary = new StringBuilder();
        if (performanceStats != null) {
            summary.append(performanceStats.activeGroups()).append(" active groups")
                    .append("   .   ").append(performanceStats.activeWaypoints()).append(" active pts")
                    .append("   .   ").append(performanceStats.activeVisibleWaypoints()).append(" renderable")
                    .append("   .   ")
                    .append(performanceStats.estimatedProximityIndexVisitsPerTick())
                    .append(" proximity visits/tick");
        }
        if (debug != null) {
            if (!summary.isEmpty()) summary.append("   |   ");
            int wps = totalWaypoints(debug);
            summary.append("codec ")
                    .append(debug.inputChars()).append(" ch -> ")
                    .append(debug.compressedBytes()).append(" B")
                    .append("   .   ")
                    .append(debug.decodedGroups().size())
                    .append(debug.decodedGroups().size() == 1 ? " group" : " groups")
                    .append("   .   ")
                    .append(wps).append(wps == 1 ? " pt" : " pts");
        } else if (codecError != null) {
            if (!summary.isEmpty()) summary.append("   |   ");
            summary.append("codec unavailable");
        }
        return summary.isEmpty() ? null : summary.toString();
    }

    // --- sidebar ---------------------------------------------------------------------------

    private void renderSidebar(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, SURFACE);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        int labelY = y1 + 10;
        g.text(font, "Debug Report", x1 + GAP, labelY, TEXT_DIM, false);
        this.sidebarContentTop = labelY + 14;

        if (sections.isEmpty()) {
            g.text(font, debug == null ? "(no data loaded)" : "(empty report)",
                    x1 + GAP, sidebarContentTop + 4, TEXT_MUTED, false);
            return;
        }

        int rowY = sidebarContentTop;
        for (int i = 0; i < sections.size(); i++, rowY += ROW_H) {
            // Quietly stop drawing if the list exceeds the sidebar; sidebar overflow is
            // rare (few groups per payload) and the main panel stays scrollable regardless.
            if (rowY + ROW_H > y2) break;

            SectionAnchor s = sections.get(i);
            boolean selected = i == selectedSection;
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= rowY + ROW_H;
            drawSidebarRow(g, x1, rowY, x2, s, selected, hovered);
        }
    }

    private void drawSidebarRow(GuiGraphicsExtractor g, int x1, int y, int x2,
                                 SectionAnchor s, boolean selected, boolean hovered) {
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);
        if (selected) g.fill(x1, y, x1 + 2, y + ROW_H, ACCENT);

        int textColor = selected ? TEXT : TEXT_DIM;
        g.text(font, s.label(), x1 + GAP + 2, y + 6, textColor, false);

        if (s.subtitle() != null && !s.subtitle().isEmpty()) {
            int sw = font.width(s.subtitle());
            g.text(font, s.subtitle(), x2 - GAP - sw, y + 6, TEXT_MUTED, false);
        }
    }

    // --- main report -----------------------------------------------------------------------

    private void renderMain(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);

        if (lastError != null) {
            renderError(g, x1, y1, x2, y2);
            this.visibleRowCount = 0;
            return;
        }
        if (rows.isEmpty()) {
            renderEmpty(g, x1, y1, x2, y2);
            this.visibleRowCount = 0;
            return;
        }

        int lineH = font.lineHeight + 1;
        int innerX = x1 + GAP + GAP_TIGHT;
        int innerTop = y1 + 6;
        int innerH = y2 - y1 - 12;
        this.visibleRowCount = Math.max(1, innerH / lineH);

        int maxScroll = Math.max(0, rows.size() - visibleRowCount);
        scrollRows = Mth.clamp(scrollRows, 0, maxScroll);
        int start = scrollRows;
        int end = Math.min(rows.size(), start + visibleRowCount);

        g.enableScissor(x1 + 1, y1 + 1, x2 - 1, y2 - 1);
        int y = innerTop;
        for (int i = start; i < end; i++, y += lineH) {
            drawRow(g, rows.get(i), innerX, y, x2 - GAP);
        }
        g.disableScissor();

        if (rows.size() > visibleRowCount) {
            drawScrollbar(g, x2 - 4, y1 + 4, y2 - 4, start, visibleRowCount, rows.size());
        }
    }

    private void drawRow(GuiGraphicsExtractor g, Row row, int x, int y, int xEnd) {
        switch (row) {
            case Row.Section s -> {
                g.text(font, s.title(), x, y, ACCENT, false);
                int lineX = x + font.width(s.title()) + GAP;
                if (lineX < xEnd) g.fill(lineX, y + 5, xEnd, y + 6, BORDER);
            }
            case Row.KV kv -> {
                drawKey(g, kv.key(), x, y);
                g.text(font, kv.value(), x + KEY_COL_W, y,
                        valueColor(kv.key(), kv.value()), false);
            }
            case Row.KVDim kv -> {
                drawKey(g, kv.key(), x, y);
                g.text(font, kv.value(), x + KEY_COL_W, y, TEXT_DIM, false);
            }
            case Row.KVWarn kv -> {
                drawKey(g, kv.key(), x, y);
                g.text(font, kv.value(), x + KEY_COL_W, y, WARN_TONE, false);
            }
            case Row.Bit b -> {
                g.text(font, "bit " + b.bit(), x, y, TEXT_DIM, false);
                g.text(font, b.label(), x + BIT_LABEL_OFFSET, y, KEYWORD_TONE, false);
                g.text(font, b.set() ? "true" : "false", x + KEY_COL_W, y,
                        b.set() ? SUCCESS_TONE : TEXT_MUTED, false);
            }
            case Row.BitNote n -> g.text(font, n.text(), x, y, TEXT_MUTED, false);
            case Row.PoolEntry p -> {
                g.text(font, "[" + p.index() + "]", x, y, NUMBER_TONE, false);
                String content = p.text().isEmpty() ? "(empty)" : p.text();
                g.text(font, content, x + POOL_CONTENT_OFFSET, y,
                        p.text().isEmpty() ? TEXT_MUTED : STRING_TONE, false);
            }
            case Row.WP wp -> drawWaypointRow(g, wp.wp(), x, y, xEnd);
            case Row.Blank ignored -> { /* deliberate breathing room */ }
        }
    }

    private void drawKey(GuiGraphicsExtractor g, String key, int x, int y) {
        String shown = key;
        while (font.width(shown) > KEY_COL_W - GAP && shown.length() > 3) {
            shown = shown.substring(0, shown.length() - 4) + "...";
        }
        g.text(font, shown, x, y, TEXT_DIM, false);
    }

    private static int valueColor(String key, String value) {
        String lowerKey = key.toLowerCase(Locale.ROOT);
        String lowerValue = value.toLowerCase(Locale.ROOT);

        if (lowerValue.equals("on") || lowerValue.equals("enabled")
                || lowerValue.equals("true") || lowerValue.equals("unlimited")) {
            return SUCCESS_TONE;
        }
        if (lowerValue.equals("off") || lowerValue.equals("disabled")
                || lowerValue.equals("false") || lowerValue.equals("(none)")) {
            return TEXT_MUTED;
        }
        if (lowerValue.startsWith("0x") || lowerValue.contains("  0b")
                || lowerKey.contains("byte") || lowerKey.contains("flags")) {
            return HEX_TONE;
        }
        if (lowerValue.startsWith("\"") || lowerValue.startsWith("(")) {
            return STRING_TONE;
        }
        if (!value.isEmpty() && Character.isDigit(value.charAt(0))) {
            return NUMBER_TONE;
        }
        if (lowerKey.contains("mode") || lowerKey.contains("zone")) {
            return KEYWORD_TONE;
        }
        return TEXT;
    }

    private void drawWaypointRow(GuiGraphicsExtractor g, DecodeDebug.WaypointDebug wp,
                                  int x, int y, int xEnd) {
        // Column layout (measured in pixels, not spaces):
        //   [ #idx (3) ] [ coords (120) ] [ flags (50) ] [ swatch+hex (70) ] [ name+radius fill ]
        int xIdx    = x;
        int xCoords = x + 20;
        int xFlags  = x + 20 + 120;
        int xSwatch = x + 20 + 120 + 56;
        int xHex    = xSwatch + 10;
        int xExtras = xHex + 58;

        g.text(font, "#" + wp.index(), xIdx, y, NUMBER_TONE, false);

        String coords = String.format(Locale.ROOT, "%d, %d, %d", wp.x(), wp.y(), wp.z());
        g.text(font, coords, xCoords, y, NUMBER_TONE, false);

        g.text(font, shortByte(wp.wpFlagsByte()), xFlags, y, HEX_TONE, false);

        // 7x7 color swatch so the wire-level color is visible at a glance alongside the hex.
        // This is data, not chrome -- the ACCENT-only rule is about UI surface color,
        // and a waypoint's color is part of the payload we're inspecting.
        if (wp.hasColor()) {
            int swatchColor = 0xFF000000 | (wp.color() & 0xFFFFFF);
            g.fill(xSwatch, y + 1, xSwatch + 7, y + 8, swatchColor);
            g.text(font, String.format(Locale.ROOT, "#%06X", wp.color() & 0xFFFFFF),
                    xHex, y, HEX_TONE, false);
        }

        // Name and radius share the right tail. Name takes priority; if both, name wins
        // and radius is suppressed in the tabular view (it still shows in the Copy report).
        int cx = xExtras;
        if (wp.hasName()) {
            String name = "\"" + wp.name() + "\"";
            g.text(font, name, cx, y, STRING_TONE, false);
            cx += font.width(name) + GAP;
        }
        if (wp.hasRadius() && cx < xEnd) {
            String r = String.format(Locale.ROOT, "r=%.1f", wp.customRadius());
            g.text(font, r, cx, y, NUMBER_TONE, false);
            cx += font.width(r) + GAP;
        }
        if (wp.extended() && cx < xEnd) {
            g.text(font, "ext=" + shortByte(wp.extendedFlags()), cx, y, HEX_TONE, false);
        }
    }

    private void renderEmpty(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        String a = "No payload loaded.";
        String b = "Copy a " + WaypointCodec.MAGIC + " export string, then click \"Load from clipboard\".";
        int cy = y1 + (y2 - y1) / 2 - 8;
        int ax = x1 + ((x2 - x1) - font.width(a)) / 2;
        int bx = x1 + ((x2 - x1) - font.width(b)) / 2;
        g.text(font, a, ax, cy, TEXT, false);
        g.text(font, b, bx, cy + 14, TEXT_DIM, false);
    }

    private void renderError(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        String[] lines = lastError.split("\n");
        int lineH = font.lineHeight + 2;
        int totalH = lines.length * lineH;
        int cy = y1 + (y2 - y1 - totalH) / 2;
        for (int i = 0; i < lines.length; i++) {
            int tw = font.width(lines[i]);
            int tx = x1 + ((x2 - x1) - tw) / 2;
            // First line gets the warm error tone; the rest (hint / detail) stay neutral
            // so the color doesn't shout over every remediation instruction.
            g.text(font, lines[i], tx, cy + i * lineH, i == 0 ? ERROR_TONE : TEXT_DIM, false);
        }
    }

    private static void drawScrollbar(GuiGraphicsExtractor g, int x, int y1, int y2,
                                       int start, int visible, int total) {
        int trackH = y2 - y1;
        int thumbH = Math.max(8, (int) ((double) visible / total * trackH));
        int thumbY = y1 + (int) ((double) start / Math.max(1, total - visible) * (trackH - thumbH));
        g.fill(x, y1, x + 2, y2, 0x30FFFFFF);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF);
    }

    // --- report building --------------------------------------------------------------------

    private static void buildReport(DecodeDebug d, List<Row> rows, List<SectionAnchor> sections) {
        addSection(rows, sections, "Codec Pipeline", null);
        rows.add(new Row.KV("Input",       d.inputChars() + " chars"));
        rows.add(new Row.KVDim("Prefix",   d.magic()));
        rows.add(new Row.KV("Payload",     d.payloadChars() + " chars"));
        rows.add(new Row.KVDim("Encoding", d.textEncoding()));
        rows.add(new Row.KV("Compressed",  d.compressedBytes() + " bytes"));
        rows.add(new Row.KV("Raw body",    d.rawBodyBytes() + " bytes"));
        rows.add(new Row.KV("Density",     String.format(Locale.ROOT, "%.2f chars / raw byte", d.charsPerRawByte())));
        rows.add(new Row.KV("Decode time", formatNanos(d.decodeNanos())));

        addSection(rows, sections, "Codec Header", shortByte(d.headerByte()));
        rows.add(new Row.KV("Byte",    formatByteFull(d.headerByte())));
        rows.add(new Row.KV("Version", "v" + d.version() + " (bits 0..3)"));
        rows.add(new Row.Bit(4, "includesNames", d.includesNames()));
        rows.add(new Row.Bit(5, "hasLabel",      d.hasLabel()));
        rows.add(new Row.Bit(6, "reserved",      d.reservedBit6()));
        rows.add(new Row.Bit(7, "reserved",      d.reservedBit7()));
        if (d.hasLabel()) {
            // Show the sanitized label inline; useful both as proof the bit
            // matches reality and so debugging weird labels (truncation, hidden
            // codepoints) doesn't require a separate command.
            String shown = d.label().isEmpty() ? "(empty after sanitize)" : "\"" + d.label() + "\"";
            rows.add(new Row.KV("Label", shown));
        }

        String poolSub = d.stringPool().size() + (d.stringPool().size() == 1 ? " entry" : " entries");
        addSection(rows, sections, "Codec String Pool", poolSub);
        for (int i = 0; i < d.stringPool().size(); i++) {
            rows.add(new Row.PoolEntry(i, d.stringPool().get(i)));
        }

        for (DecodeDebug.GroupDebug gd : d.groups()) {
            String subtitle = gd.name().isEmpty() ? "(unnamed)" : gd.name();
            addSection(rows, sections, "Codec Group " + gd.index(), subtitle);

            rows.add(new Row.KV("Zone",          gd.zoneId().isEmpty() ? "(none)" : gd.zoneId()));
            rows.add(new Row.KV("Group flags",   formatByteFull(gd.groupFlagsByte())));
            rows.add(new Row.Bit(0, d.version() == 2 ? "enabled" : "bodyless",
                    d.version() == 2 ? gd.enabled() : (gd.groupFlagsByte() & 1) != 0));
            rows.add(new Row.Bit(1, "gradientAuto", gd.gradientAuto()));
            rows.add(new Row.Bit(2, "loadSequence", gd.loadSequence()));
            rows.add(new Row.Bit(3, "customRadius", gd.customRadius()));
            rows.add(new Row.BitNote("bits 4-5  coord mode = " + gd.coordMode()
                    + " (ord " + gd.coordModeOrdinal() + ")"));
            rows.add(new Row.KV("Default radius", String.format(Locale.ROOT, "%.1f", gd.defaultRadius())));
            rows.add(new Row.KV("Current index",  String.valueOf(gd.currentIndex())));
            rows.add(new Row.KV("Point count",    String.valueOf(gd.pointCount())));
            rows.add(new Row.KV("Coord bytes",    gd.coordBlockBytes() + " (" + gd.coordMode() + ")"));
            rows.add(new Row.KV("Body bytes",     String.valueOf(gd.bodyBlockBytes())));

            if (!gd.waypoints().isEmpty()) {
                rows.add(new Row.Blank());
                for (DecodeDebug.WaypointDebug wp : gd.waypoints()) {
                    rows.add(new Row.WP(wp));
                }
            }
        }
    }

    private static void buildCodecClipboardReport(List<Row> rows,
                                                  List<SectionAnchor> sections,
                                                  String message) {
        addSection(rows, sections, "Codec Clipboard", "not loaded");
        rows.add(new Row.KVWarn("Status", message));
        rows.add(new Row.KVDim("Hint", "Copy a Waypointer export and hit Refresh."));
    }

    private static void buildDungeonDiagnosticsReport(DebugSignals.DungeonDebugSnapshot snapshot,
                                                     List<Row> rows,
                                                     List<SectionAnchor> sections) {
        DungeonStateTracker.DebugSnapshot tracker = snapshot == null ? null : snapshot.tracker;
        addSection(rows, sections, "Dungeon Overview",
                tracker == null ? "not installed" : tracker.roomName);
        rows.add(new Row.KVDim("Config", DebugSignals.dungeonConfigLine()));
        rows.add(new Row.KVDim("Zone source", "Scoreboard sidebar"));
        if (tracker == null) {
            rows.add(new Row.KVWarn("Tracker", "Dungeon tracker is not installed."));
        } else {
            rows.add(new Row.KV("In dungeon", String.valueOf(tracker.inDungeon)));
            rows.add(new Row.KV("Room", tracker.roomPresent ? tracker.roomName : "(none)"));
            rows.add(new Row.KV("Room id", tracker.roomId));
            rows.add(new Row.KV("Confidence", DebugSignals.detectionConfidenceLabel(tracker.confidence)));
            rows.add(new Row.KV("Type/shape", tracker.roomType + " / " + tracker.roomShape));
            rows.add(new Row.KV("Direction", tracker.roomDirection
                    + " effective=" + tracker.effectiveDirection
                    + " override=" + tracker.directionOverride));
            rows.add(new Row.KV("Corner", tracker.physicalCornerX + ", " + tracker.physicalCornerZ));
            rows.add(new Row.KV("Segments", formatSegments(tracker.roomSegments)));
        }

        addSection(rows, sections, "Room Detection", tracker == null ? "unavailable" : tracker.lastScanStage);
        if (tracker == null) {
            rows.add(new Row.KVWarn("Unavailable", "No tracker snapshot is available."));
        } else {
            rows.add(new Row.KV("Last scan", tracker.lastScanStage + " -> " + tracker.lastScanResult));
            rows.add(new Row.KVDim("Scan age", formatAge(tracker.lastScanAtMillis)));
            rows.add(new Row.KV("Scan time", formatNanos(tracker.lastScanDurationNanos)));
            rows.add(new Row.KV("Player segment", formatSegment(tracker.lastPlayerSegment)));
            rows.add(new Row.KV("Core signature", formatCoreSignature(tracker.lastPlayerSegmentSignature)));
            rows.add(new Row.KV("Matched room", tracker.lastMatchedRoomName + " (" + tracker.lastMatchedRoomId + ")"));
            rows.add(new Row.KV("Matched pieces", String.valueOf(tracker.lastMatchedComponentCount)));
            rows.add(new Row.KV("Room cache", tracker.knownRoomCacheSize + " segment entries"));
            rows.add(new Row.KV("Core cache", tracker.coreSignatureCacheSize + " segment signatures"));
            rows.add(new Row.KVDim("Scoreboard text", DebugSignals.scoreboardLine()));
            rows.add(new Row.KVDim("Tab text", DebugSignals.tabListLine()));
        }

        DungeonRoomZoneBridge.DebugSnapshot bridge = snapshot == null ? null : snapshot.bridge;
        addSection(rows, sections, "Zone Bridge", bridge == null ? "unavailable" : bridge.lastAction);
        if (bridge == null) {
            rows.add(new Row.KVWarn("Unavailable", "No bridge snapshot is available."));
        } else {
            rows.add(new Row.KV("Installed", String.valueOf(bridge.installed)));
            rows.add(new Row.KV("Current zone", bridge.currentZone));
            rows.add(new Row.KV("Last broad", bridge.lastBroadZone));
            rows.add(new Row.KV("Applying room", String.valueOf(bridge.applyingRoomZone)));
            rows.add(new Row.KV("Action", bridge.lastAction));
            rows.add(new Row.KVDim("Reason", bridge.lastReason));
            rows.add(new Row.KVDim("Line", bridge.line));
        }

        DungeonRouteSession.DebugSnapshot route = snapshot == null ? null : snapshot.routeSession;
        addSection(rows, sections, "Route Progress", route == null ? "unavailable" : route.roomKey);
        if (route == null) {
            rows.add(new Row.KVWarn("Unavailable", "Dungeon route session is not installed."));
        } else {
            rows.add(new Row.KV("Room present", String.valueOf(route.roomPresent)));
            rows.add(new Row.KV("Room key", route.roomKey));
            rows.add(new Row.KVDim("Physical key", route.physicalKey));
            rows.add(new Row.KV("Initialized", String.valueOf(route.progressInitialized)));
            rows.add(new Row.KV("Current secret", String.valueOf(route.currentSecretIndex)));
            rows.add(new Row.KV("Counts", "total=" + route.totalProgressWaypoints
                    + ", found=" + route.foundCount
                    + ", current=" + route.currentCount
                    + ", upcoming=" + route.upcomingCount
                    + ", nonProgress=" + route.nonProgressCount));
            rows.add(new Row.KV("Found indexes", route.foundSecretIndices.isEmpty()
                    ? "(none)"
                    : route.foundSecretIndices.toString()));
            rows.add(new Row.KV("Complete", String.valueOf(route.complete)));
            rows.add(new Row.KV("Aliases", route.aliasCount + " for room, " + route.progressEntryCount + " stored"));
            rows.add(new Row.KVDim("Last reset", route.lastResetReason + " " + formatAge(route.lastResetAtMillis)));
        }

        List<DebugEventLog.Entry> events = snapshot == null ? List.of() : snapshot.inputEvents;
        addSection(rows, sections, "Trigger/Input", events.size() + " events");
        if (events.isEmpty()) {
            rows.add(new Row.KVDim("Input events", "No recent route-list or editor clicks recorded."));
        } else {
            for (DebugEventLog.Entry event : events) {
                rows.add(new Row.KVDim("Event", event.plainText()));
            }
        }
    }

    private static void buildPerformanceReport(PerformanceStats stats,
                                                WaypointerConfig config,
                                                List<Row> rows,
                                                List<SectionAnchor> sections) {
        addSection(rows, sections, "Performance Snapshot", null);
        rows.add(new Row.KV("Captured", stats.capturedAt().toString()));
        rows.add(new Row.KV("Zone", stats.currentZoneName() + " (" + stats.currentZoneId() + ")"));
        rows.add(new Row.KVDim("Meaning", "counts before camera/distance culling unless noted"));
        rows.add(new Row.KVDim("Java", System.getProperty("java.version", "(unknown)")));
        rows.add(new Row.KVDim("Memory used", formatBytes(stats.usedMemoryBytes())
                + " / " + formatBytes(stats.maxMemoryBytes())));

        addSection(rows, sections, "Route Library", stats.totalWaypoints() + " pts");
        rows.add(new Row.KV("Groups", stats.totalGroups() + " total, "
                + stats.enabledGroups() + " enabled, " + stats.tempGroups() + " temp"));
        rows.add(new Row.KV("Zones", stats.knownZoneCount() + " known"));
        rows.add(new Row.KV("Waypoints", stats.totalWaypoints() + " total, "
                + stats.tempWaypoints() + " temp"));
        rows.add(new Row.KV("Load modes", stats.staticGroups() + " static groups, "
                + stats.sequenceGroups() + " sequence groups"));
        rows.add(new Row.KV("Largest group", groupSummary(stats.largestGroup())));

        addSection(rows, sections, "Active Zone", stats.activeGroups() + " groups");
        rows.add(new Row.KV("Active points", stats.activeWaypoints() + " total"));
        rows.add(new Row.KV("Static points", String.valueOf(stats.activeStaticWaypoints())));
        rows.add(new Row.KV("Sequence points", String.valueOf(stats.activeSequenceWaypoints())));
        rows.add(new Row.KV("Renderable", stats.activeVisibleWaypoints() + " waypoint slots"));
        rows.add(new Row.KV("Label candidates", stats.activeLabelCandidates() + " before budget"));
        rows.add(new Row.KV("Largest active", groupSummary(stats.largestActiveGroup())));

        addSection(rows, sections, "Render Estimate", null);
        rows.add(new Row.KV("Box style", config.boxStyle().name()));
        rows.add(new Row.KV("Beacon mode", config.beaconBeamMode().name()));
        rows.add(new Row.KV("Line vertices", String.valueOf(stats.estimatedLineBoxVertices())));
        rows.add(new Row.KV("Fill vertices", String.valueOf(stats.estimatedFillBoxVertices())));
        rows.add(new Row.KV("Beam vertices", String.valueOf(stats.estimatedBeamVertices())));
        rows.add(new Row.KV("Label budget", config.maxWaypointLabels() == 0
                ? "unlimited" : String.valueOf(config.maxWaypointLabels())));
        rows.add(new Row.KV("Static distance", config.maxStaticWaypointRenderDistance() <= 0.0
                ? "unlimited" : String.format(Locale.ROOT, "%.1f blocks",
                config.maxStaticWaypointRenderDistance())));

        addSection(rows, sections, "Tick Estimate", null);
        rows.add(new Row.KV("Proximity visits", stats.estimatedProximityIndexVisitsPerTick()
                + " nearby candidates/tick"));
        rows.add(new Row.KVDim("Skip-ahead", config.skipAheadMechanicEnabled() ? "enabled" : "disabled"));
        rows.add(new Row.KVDim("Static reached hide",
                config.hideReachedStaticWaypointsUntilCycleComplete() ? "enabled" : "disabled"));

        addSection(rows, sections, "Config Toggles", null);
        rows.add(new Row.KV("Names", config.showWaypointNames() ? "on" : "off"));
        rows.add(new Row.KV("Distances", config.showWaypointDistances() ? "on" : "off"));
        rows.add(new Row.KV("Backdrop", config.showLabelBackdrop() ? "on" : "off"));
        rows.add(new Row.KV("Tracer", config.showTracer() ? "on" : "off"));
        rows.add(new Row.KV("Hide static tracer", config.hideTracerOnStaticRoutes() ? "on" : "off"));

        addSection(rows, sections, "Active Groups", String.valueOf(stats.activeGroupStats().size()));
        if (stats.activeGroupStats().isEmpty()) {
            rows.add(new Row.KVDim("None", "No active groups in the current zone."));
            return;
        }
        for (PerformanceStats.GroupStats group : stats.activeGroupStats()) {
            rows.add(new Row.KV(shortGroupName(group),
                    group.waypoints() + " pts, "
                            + group.renderableWaypoints() + " renderable, "
                            + group.labelCandidates() + " labels, "
                            + group.proximityIndexVisitsPerTick() + " tick candidates, "
                            + group.loadMode().toLowerCase(Locale.ROOT)));
        }
    }

    private static void addSection(List<Row> rows, List<SectionAnchor> sections,
                                    String label, String subtitle) {
        if (!rows.isEmpty()) rows.add(new Row.Blank());
        sections.add(new SectionAnchor(label, subtitle, rows.size()));
        rows.add(new Row.Section(label));
    }

    // --- text formatting --------------------------------------------------------------------

    private static String formatSegments(List<Long> segments) {
        if (segments == null || segments.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Long segment : segments) {
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(formatSegment(segment == null ? Long.MIN_VALUE : segment));
        }
        return builder.toString();
    }

    private static String formatSegment(long segment) {
        if (segment == Long.MIN_VALUE) return "(none)";
        return DungeonRoom.segmentX(segment) + "," + DungeonRoom.segmentZ(segment);
    }

    private static String formatCoreSignature(DungeonCoreSignature signature) {
        if (signature == null) return "(none)";
        return "hash=" + signature.hash()
                + ", topY=" + signature.topY()
                + ", samples=" + signature.sampleCount();
    }

    private static String formatAge(long timestampMillis) {
        if (timestampMillis <= 0L) return "(never)";
        long ageMillis = Math.max(0L, System.currentTimeMillis() - timestampMillis);
        if (ageMillis < 1_000L) return ageMillis + " ms ago";
        return String.format(Locale.ROOT, "%.1f s ago", ageMillis / 1_000.0);
    }

    private static String rowAsPlainText(Row row) {
        return switch (row) {
            case Row.Section s -> "== " + s.title() + " ==";
            case Row.KV kv -> String.format(Locale.ROOT, "  %-16s %s", kv.key() + ":", kv.value());
            case Row.KVDim kv -> String.format(Locale.ROOT, "  %-16s %s", kv.key() + ":", kv.value());
            case Row.KVWarn kv -> String.format(Locale.ROOT, "  %-16s %s", kv.key() + ":", kv.value());
            case Row.Bit b -> String.format(Locale.ROOT, "    bit %d  %-17s = %s",
                    b.bit(), b.label(), b.set() ? "true" : "false");
            case Row.BitNote n -> "    " + n.text();
            case Row.PoolEntry p -> String.format(Locale.ROOT, "  [%d] %s",
                    p.index(), p.text().isEmpty() ? "\"\"" : "\"" + p.text() + "\"");
            case Row.WP wp -> formatWaypointPlain(wp.wp());
            case Row.Blank ignored -> "";
        };
    }

    private static String groupSummary(PerformanceStats.GroupStats group) {
        if (group == null) return "(none)";
        return shortGroupName(group) + " -- " + group.waypoints()
                + " pts, " + group.loadMode().toLowerCase(Locale.ROOT);
    }

    private static String shortGroupName(PerformanceStats.GroupStats group) {
        String name = group.name() == null || group.name().isBlank()
                ? "(unnamed)"
                : group.name();
        if (name.length() <= 28) return name;
        return name.substring(0, 25) + "...";
    }

    private static String formatWaypointPlain(DecodeDebug.WaypointDebug wp) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "  #%-2d (%6d,%4d,%6d)  flags=%s",
                wp.index(), wp.x(), wp.y(), wp.z(), formatByteFull(wp.wpFlagsByte())));
        if (wp.hasName())   sb.append("  name=\"").append(wp.name()).append('"');
        if (wp.hasColor())  sb.append(String.format(Locale.ROOT, "  color=#%06X", wp.color() & 0xFFFFFF));
        if (wp.hasRadius()) sb.append(String.format(Locale.ROOT, "  r=%.1f", wp.customRadius()));
        if (wp.extended())  sb.append("  ext=").append(formatByteFull(wp.extendedFlags()));
        return sb.toString();
    }

    /** Compact hex form for table cells -- {@code "0xFF"}. */
    private static String shortByte(int byteValue) {
        return String.format(Locale.ROOT, "0x%02X", byteValue & 0xFF);
    }

    /** Full hex + binary form for key:value rows -- {@code "0xFF  0b11111111"}. */
    private static String formatByteFull(int byteValue) {
        int b = byteValue & 0xFF;
        // Pad the binary view independently so the space between "0x.." and "0b.." isn't
        // consumed by a blanket zero-fill.
        String bin = String.format(Locale.ROOT, "%8s", Integer.toBinaryString(b)).replace(' ', '0');
        return String.format(Locale.ROOT, "0x%02X  0b%s", b, bin);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024.0) return String.format(Locale.ROOT, "%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024.0) return String.format(Locale.ROOT, "%.1f MiB", mib);
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    private static String formatNanos(long nanos) {
        if (nanos < 1_000)              return nanos + " ns";
        if (nanos < 1_000_000)          return String.format(Locale.ROOT, "%.1f us", nanos / 1_000.0);
        if (nanos < 1_000_000_000L)     return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
    }

    private static int totalWaypoints(DecodeDebug d) {
        int n = 0;
        for (DecodeDebug.GroupDebug g : d.groups()) n += g.pointCount();
        return n;
    }

    // --- boilerplate -----------------------------------------------------------------------

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }
}
