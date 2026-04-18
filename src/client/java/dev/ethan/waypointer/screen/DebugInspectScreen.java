package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.codec.DecodeDebug;
import dev.ethan.waypointer.codec.WaypointCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final int KEY_COL_W = 120;

    /** Pixel column where a bit-row's label starts (after "bit N"). */
    private static final int BIT_LABEL_OFFSET = 30;

    /** Pixel column where a string-pool entry's content starts (after "[N]"). */
    private static final int POOL_CONTENT_OFFSET = 30;

    /** Subdued warm tone for error surfaces. Errors are signal, not decoration -- allowed as a one-off. */
    private static final int ERROR_TONE = 0xFFCA7A7A;

    private final Screen parent;

    private DecodeDebug debug;
    private String lastError;
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

    public DebugInspectScreen(Screen parent) {
        super(Component.literal("Codec Debug Inspector"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new DebugInspectScreen(parent));
    }

    // --- lifecycle -------------------------------------------------------------------------

    @Override
    protected void init() {
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec("Load from clipboard", this::loadFromClipboard));
        left.add(new GuiTokens.ButtonSpec("Copy report", this::copyReportToClipboard));
        GuiTokens.ButtonSpec back = new GuiTokens.ButtonSpec("Back", this::onClose);

        int footerY = height - FOOTER_H;
        GuiTokens.layoutFooter(width, footerY, left, back, b -> {
            // Stash the Copy button so we can swap its label on the copy-confirmation flash.
            // Matching on the label string is ugly but the footer helper doesn't expose
            // a better hook and this screen builds exactly one button with that label.
            if ("Copy report".contentEquals(b.getMessage().getString())) {
                copyButton = b;
            }
            addRenderableWidget(b);
        }, font);

        // First-open affordance: try to load whatever is already on the clipboard so the
        // user doesn't have to click twice for the common case. Subsequent re-inits (e.g.
        // window resize) preserve whatever state was already there.
        if (debug == null && lastError == null) {
            loadFromClipboard();
        }
    }

    // --- actions ---------------------------------------------------------------------------

    private void loadFromClipboard() {
        loadFromString(minecraft.keyboardHandler.getClipboard());
    }

    private void loadFromString(String text) {
        this.debug = null;
        this.lastError = null;
        this.rows.clear();
        this.sections.clear();
        this.scrollRows = 0;
        this.selectedSection = 0;

        if (text == null || text.isBlank()) {
            this.lastError = "Clipboard is empty.\nCopy a " + WaypointCodec.MAGIC + " export, then click Load from clipboard.";
            return;
        }
        String trimmed = text.trim();
        if (!WaypointCodec.isCodecString(trimmed)) {
            this.lastError = "Clipboard doesn't start with " + WaypointCodec.MAGIC + "\n"
                    + "Copy a Waypointer export string and try again.";
            return;
        }
        try {
            this.debug = WaypointCodec.debugDecode(trimmed);
            buildReport(this.debug, rows, sections);
        } catch (IllegalArgumentException e) {
            this.lastError = "Decode failed.\n" + e.getMessage();
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        if (copyFeedbackUntil != 0 && System.currentTimeMillis() > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            if (copyButton != null) copyButton.setMessage(Component.literal("Copy report"));
        }

        // --- header (title + right-aligned compact summary) ------------------------------
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        String summary = buildHeaderSummary();
        if (summary != null) {
            int sw = font.width(summary);
            g.drawString(font, summary, width - PAD_OUTER - sw, PAD_OUTER, TEXT_DIM, false);
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
        if (debug == null) return null;
        int wps = totalWaypoints(debug);
        return debug.inputChars() + " ch  ->  "
                + debug.compressedBytes() + " B  ->  "
                + debug.rawBodyBytes() + " B"
                + "   .   " + debug.decodedGroups().size() + (debug.decodedGroups().size() == 1 ? " group" : " groups")
                + "   .   " + wps + (wps == 1 ? " pt" : " pts")
                + "   .   " + formatNanos(debug.decodeNanos());
    }

    // --- sidebar ---------------------------------------------------------------------------

    private void renderSidebar(GuiGraphics g, int x1, int y1, int x2, int y2,
                                int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, SURFACE);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        int labelY = y1 + 10;
        g.drawString(font, "Sections", x1 + GAP, labelY, TEXT_DIM, false);
        this.sidebarContentTop = labelY + 14;

        if (sections.isEmpty()) {
            g.drawString(font, debug == null ? "(no data loaded)" : "(empty report)",
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

    private void drawSidebarRow(GuiGraphics g, int x1, int y, int x2,
                                 SectionAnchor s, boolean selected, boolean hovered) {
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);
        if (selected) g.fill(x1, y, x1 + 2, y + ROW_H, ACCENT);

        int textColor = selected ? TEXT : TEXT_DIM;
        g.drawString(font, s.label(), x1 + GAP + 2, y + 6, textColor, false);

        if (s.subtitle() != null && !s.subtitle().isEmpty()) {
            int sw = font.width(s.subtitle());
            g.drawString(font, s.subtitle(), x2 - GAP - sw, y + 6, TEXT_MUTED, false);
        }
    }

    // --- main report -----------------------------------------------------------------------

    private void renderMain(GuiGraphics g, int x1, int y1, int x2, int y2) {
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

    private void drawRow(GuiGraphics g, Row row, int x, int y, int xEnd) {
        switch (row) {
            case Row.Section s -> g.drawString(font, s.title(), x, y, TEXT, false);
            case Row.KV kv -> {
                g.drawString(font, kv.key(), x, y, TEXT_DIM, false);
                g.drawString(font, kv.value(), x + KEY_COL_W, y, TEXT, false);
            }
            case Row.KVDim kv -> {
                g.drawString(font, kv.key(), x, y, TEXT_DIM, false);
                g.drawString(font, kv.value(), x + KEY_COL_W, y, TEXT_DIM, false);
            }
            case Row.Bit b -> {
                g.drawString(font, "bit " + b.bit(), x, y, TEXT_DIM, false);
                g.drawString(font, b.label(), x + BIT_LABEL_OFFSET, y,
                        b.set() ? TEXT_DIM : TEXT_MUTED, false);
                g.drawString(font, b.set() ? "true" : "false", x + KEY_COL_W, y,
                        b.set() ? TEXT : TEXT_MUTED, false);
            }
            case Row.BitNote n -> g.drawString(font, n.text(), x, y, TEXT_MUTED, false);
            case Row.PoolEntry p -> {
                g.drawString(font, "[" + p.index() + "]", x, y, TEXT_DIM, false);
                String content = p.text().isEmpty() ? "(empty)" : p.text();
                g.drawString(font, content, x + POOL_CONTENT_OFFSET, y,
                        p.text().isEmpty() ? TEXT_MUTED : TEXT, false);
            }
            case Row.WP wp -> drawWaypointRow(g, wp.wp(), x, y, xEnd);
            case Row.Blank ignored -> { /* deliberate breathing room */ }
        }
    }

    private void drawWaypointRow(GuiGraphics g, DecodeDebug.WaypointDebug wp,
                                  int x, int y, int xEnd) {
        // Column layout (measured in pixels, not spaces):
        //   [ #idx (3) ] [ coords (120) ] [ flags (50) ] [ swatch+hex (70) ] [ name+radius fill ]
        int xIdx    = x;
        int xCoords = x + 20;
        int xFlags  = x + 20 + 120;
        int xSwatch = x + 20 + 120 + 56;
        int xHex    = xSwatch + 10;
        int xExtras = xHex + 58;

        g.drawString(font, "#" + wp.index(), xIdx, y, TEXT_DIM, false);

        String coords = String.format(Locale.ROOT, "%d, %d, %d", wp.x(), wp.y(), wp.z());
        g.drawString(font, coords, xCoords, y, TEXT, false);

        g.drawString(font, shortByte(wp.wpFlagsByte()), xFlags, y, TEXT_DIM, false);

        // 7x7 color swatch so the wire-level color is visible at a glance alongside the hex.
        // This is data, not chrome -- the ACCENT-only rule is about UI surface color,
        // and a waypoint's color is part of the payload we're inspecting.
        if (wp.hasColor()) {
            int swatchColor = 0xFF000000 | (wp.color() & 0xFFFFFF);
            g.fill(xSwatch, y + 1, xSwatch + 7, y + 8, swatchColor);
            g.drawString(font, String.format(Locale.ROOT, "#%06X", wp.color() & 0xFFFFFF),
                    xHex, y, TEXT_DIM, false);
        }

        // Name and radius share the right tail. Name takes priority; if both, name wins
        // and radius is suppressed in the tabular view (it still shows in the Copy report).
        int cx = xExtras;
        if (wp.hasName()) {
            String name = "\"" + wp.name() + "\"";
            g.drawString(font, name, cx, y, TEXT, false);
            cx += font.width(name) + GAP;
        }
        if (wp.hasRadius() && cx < xEnd) {
            String r = String.format(Locale.ROOT, "r=%.1f", wp.customRadius());
            g.drawString(font, r, cx, y, TEXT_DIM, false);
            cx += font.width(r) + GAP;
        }
        if (wp.extended() && cx < xEnd) {
            g.drawString(font, "ext=" + shortByte(wp.extendedFlags()), cx, y, TEXT_MUTED, false);
        }
    }

    private void renderEmpty(GuiGraphics g, int x1, int y1, int x2, int y2) {
        String a = "No payload loaded.";
        String b = "Copy a " + WaypointCodec.MAGIC + " export string, then click \"Load from clipboard\".";
        int cy = y1 + (y2 - y1) / 2 - 8;
        int ax = x1 + ((x2 - x1) - font.width(a)) / 2;
        int bx = x1 + ((x2 - x1) - font.width(b)) / 2;
        g.drawString(font, a, ax, cy, TEXT, false);
        g.drawString(font, b, bx, cy + 14, TEXT_DIM, false);
    }

    private void renderError(GuiGraphics g, int x1, int y1, int x2, int y2) {
        String[] lines = lastError.split("\n");
        int lineH = font.lineHeight + 2;
        int totalH = lines.length * lineH;
        int cy = y1 + (y2 - y1 - totalH) / 2;
        for (int i = 0; i < lines.length; i++) {
            int tw = font.width(lines[i]);
            int tx = x1 + ((x2 - x1) - tw) / 2;
            // First line gets the warm error tone; the rest (hint / detail) stay neutral
            // so the color doesn't shout over every remediation instruction.
            g.drawString(font, lines[i], tx, cy + i * lineH, i == 0 ? ERROR_TONE : TEXT_DIM, false);
        }
    }

    private static void drawScrollbar(GuiGraphics g, int x, int y1, int y2,
                                       int start, int visible, int total) {
        int trackH = y2 - y1;
        int thumbH = Math.max(8, (int) ((double) visible / total * trackH));
        int thumbY = y1 + (int) ((double) start / Math.max(1, total - visible) * (trackH - thumbH));
        g.fill(x, y1, x + 2, y2, 0x30FFFFFF);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF);
    }

    // --- report building --------------------------------------------------------------------

    private static void buildReport(DecodeDebug d, List<Row> rows, List<SectionAnchor> sections) {
        addSection(rows, sections, "Pipeline", null);
        rows.add(new Row.KV("Input",       d.inputChars() + " chars"));
        rows.add(new Row.KVDim("Prefix",   d.magic()));
        rows.add(new Row.KV("Payload",     d.payloadChars() + " chars"));
        rows.add(new Row.KVDim("Encoding", "CJK base-16384"));
        rows.add(new Row.KV("Compressed",  d.compressedBytes() + " bytes"));
        rows.add(new Row.KV("Raw body",    d.rawBodyBytes() + " bytes"));
        rows.add(new Row.KV("Density",     String.format(Locale.ROOT, "%.2f chars / raw byte", d.charsPerRawByte())));
        rows.add(new Row.KV("Decode time", formatNanos(d.decodeNanos())));

        addSection(rows, sections, "Header", shortByte(d.headerByte()));
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
        addSection(rows, sections, "String pool", poolSub);
        for (int i = 0; i < d.stringPool().size(); i++) {
            rows.add(new Row.PoolEntry(i, d.stringPool().get(i)));
        }

        for (DecodeDebug.GroupDebug gd : d.groups()) {
            String subtitle = gd.name().isEmpty() ? "(unnamed)" : gd.name();
            addSection(rows, sections, "Group " + gd.index(), subtitle);

            rows.add(new Row.KV("Zone",          gd.zoneId().isEmpty() ? "(none)" : gd.zoneId()));
            rows.add(new Row.KV("Group flags",   formatByteFull(gd.groupFlagsByte())));
            rows.add(new Row.Bit(0, "enabled",      gd.enabled()));
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

    private static void addSection(List<Row> rows, List<SectionAnchor> sections,
                                    String label, String subtitle) {
        if (!rows.isEmpty()) rows.add(new Row.Blank());
        sections.add(new SectionAnchor(label, subtitle, rows.size()));
        rows.add(new Row.Section(label));
    }

    // --- text formatting --------------------------------------------------------------------

    private static String rowAsPlainText(Row row) {
        return switch (row) {
            case Row.Section s -> "== " + s.title() + " ==";
            case Row.KV kv -> String.format(Locale.ROOT, "  %-16s %s", kv.key() + ":", kv.value());
            case Row.KVDim kv -> String.format(Locale.ROOT, "  %-16s %s", kv.key() + ":", kv.value());
            case Row.Bit b -> String.format(Locale.ROOT, "    bit %d  %-17s = %s",
                    b.bit(), b.label(), b.set() ? "true" : "false");
            case Row.BitNote n -> "    " + n.text();
            case Row.PoolEntry p -> String.format(Locale.ROOT, "  [%d] %s",
                    p.index(), p.text().isEmpty() ? "\"\"" : "\"" + p.text() + "\"");
            case Row.WP wp -> formatWaypointPlain(wp.wp());
            case Row.Blank ignored -> "";
        };
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
    public void onClose() { minecraft.setScreen(parent); }
}
