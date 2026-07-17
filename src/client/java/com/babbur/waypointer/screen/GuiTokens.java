package com.babbur.waypointer.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared GUI tokens for Waypointer screens.
 *
 * Centralized so all three screens (WaypointerScreen, GroupEditScreen, SettingsScreen)
 * share the same spacing rhythm, surface colors, and footer behavior. Before this,
 * each screen invented its own PADDING and ad-hoc pixel gaps, which is how the
 * footer-overlap bug crept in: buttons laid out left-to-right with fixed widths
 * eventually walked under the right-anchored "Done" button at small GUI scales.
 *
 * Design principles:
 *   1. Space does the work -- hierarchy via gaps first, weight second, color last.
 *   2. One accent color -- ACCENT is for the currently selected thing only.
 *   3. Translucent surfaces -- single depth; no nested borders or drop shadows.
 */
public final class GuiTokens {

    private GuiTokens() {}

    // --- spacing (4px base scale) ----------------------------------------------------------

    /** Siblings inside a control group (e.g. between a - and + button). */
    public static final int GAP_TIGHT = 4;
    /** Between control groups on the same row. */
    public static final int GAP = 8;
    /** Between major regions: sidebar/main, header/list, list/footer. */
    public static final int GAP_SECTION = 16;
    /** Screen edge inset. */
    public static final int PAD_OUTER = 16;

    // --- sizes ------------------------------------------------------------------------------

    public static final int BTN_H = 20;
    public static final int ROW_H = 22;
    public static final int SIDEBAR_W = 156;
    public static final int FOOTER_H = 28;

    // --- colors (ARGB, fed to GuiGraphicsExtractor.fill) ---------------------------------------------
    // The world renders behind us, so every surface is intentionally translucent;
    // stacking opaque cards would fight the Minecraft aesthetic.

    /** Primary panel fill -- ~75% opaque dark over the world. */
    public static final int SURFACE        = 0xC0101216;
    /** Secondary fill used for list backdrops. Carries slightly less weight than SURFACE. */
    public static final int SURFACE_SUBTLE = 0x60000000;
    /** 1px separator between panels (sidebar/main). */
    public static final int BORDER         = 0x30FFFFFF;
    /** Row hover tint. */
    public static final int HOVER          = 0x18FFFFFF;
    /** Row selected tint -- paired with the ACCENT bar for the current selection. */
    public static final int SELECTED       = 0x30FFFFFF;
    /** The single accent used to mark the current selection. Everything else stays grayscale. */
    public static final int ACCENT         = 0xFF4FB3C4;

    public static final int TEXT       = 0xFFE6E9EC;
    public static final int TEXT_DIM   = 0xFFB0B6BE;
    // Was 0xFF5A6068 (near-invisible on SURFACE_SUBTLE). Bumped so "past" waypoint
    // rows in the group editor stay readable without competing with the active row.
    public static final int TEXT_MUTED = 0xFF80868E;

    // --- controls ---------------------------------------------------------------------------

    /**
     * Shared Waypointer button treatment. Minecraft's default button sprite is visually
     * heavier than the translucent panels, so screens use this restrained surface with
     * an accent focus ring and explicit hover/disabled states instead.
     */
    public static class StyledButton extends Button {
        private static final int VISIBLE_GLYPH_HEIGHT = 8;

        public StyledButton(int x, int y, int width, int height,
                            Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        public void extractOverPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            extractWidgetRenderState(g, mouseX, mouseY, partial);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            int x1 = getX();
            int y1 = getY();
            boolean highlighted = active && isHoveredOrFocused();
            drawControlFrame(g, x1, y1, getWidth(), getHeight(), active, highlighted, isFocused());

            var font = Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 10);
            int textX = x1 + (getWidth() - font.width(clipped)) / 2;
            int textY = opticalTextY(y1, getHeight());
            int styledText = getMessage().getStyle().getColor() == null
                    ? TEXT
                    : 0xFF000000 | getMessage().getStyle().getColor().getValue();
            g.text(font, clipped, textX, textY, active ? styledText : TEXT_MUTED, false);
        }
    }

    /** Compact boolean control using the same surface and focus treatment as every other button. */
    public static final class StyledCheckbox extends Checkbox {
        private StyledCheckbox(int x, int y, int size, Component label, boolean selected,
                               Consumer<Boolean> onValueChange) {
            super(x, y, size, label, Minecraft.getInstance().font, selected,
                    (checkbox, value) -> onValueChange.accept(value));
            width = size;
            height = size;
        }

        @Override
        public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            int x = getX();
            int y = getY();
            boolean highlighted = active && isHoveredOrFocused();
            drawControlFrame(g, x, y, getWidth(), getHeight(), active, highlighted, isFocused());
            if (!selected()) return;

            int color = active ? ACCENT : TEXT_MUTED;
            int inset = 4;
            g.fill(x + inset, y + inset,
                    x + getWidth() - inset, y + getHeight() - inset, color);
        }
    }

    private static final int CONTROL_IDLE = 0xE0181D22;
    private static final int CONTROL_HOVER = 0xF026343A;
    private static final int CONTROL_DISABLED = 0xB0121519;
    private static final int CONTROL_BORDER = 0x5AFFFFFF;
    private static final int CONTROL_HIGHLIGHT = 0x24FFFFFF;

    private static void drawControlFrame(GuiGraphicsExtractor g, int x, int y, int width, int height,
                                         boolean active, boolean highlighted, boolean focused) {
        int x2 = x + width;
        int y2 = y + height;
        int border = active && focused ? ACCENT
                : highlighted ? 0xB0FFFFFF : CONTROL_BORDER;
        int fill = !active ? CONTROL_DISABLED : highlighted ? CONTROL_HOVER : CONTROL_IDLE;

        g.fill(x, y, x2, y2, border);
        g.fill(x + 1, y + 1, x2 - 1, y2 - 1, fill);
        g.fill(x + 1, y + 1, x2 - 1, y + 2, CONTROL_HIGHLIGHT);
        if (highlighted) {
            g.fill(x + 1, y2 - 2, x2 - 1, y2 - 1, ACCENT);
        }
    }

    /** Minecraft's visible pixel glyphs are 8px tall even though the font line box is 9px. */
    static int opticalTextY(int controlY, int controlHeight) {
        return controlY + (controlHeight - StyledButton.VISIBLE_GLYPH_HEIGHT) / 2;
    }

    /** Align a 12px info control to the visible glyphs on a title drawn at {@code titleY}. */
    static int opticalInfoButtonY(int titleY) {
        return titleY - 2;
    }

    public static Button styledButton(int x, int y, int width, int height,
                                      Component message, Button.OnPress onPress,
                                      Tooltip tooltip) {
        Button button = new StyledButton(x, y, width, height, message, onPress);
        if (tooltip != null) button.setTooltip(tooltip);
        return button;
    }

    public static StyledCheckbox styledCheckbox(int x, int y, int size, Component label,
                                                boolean selected, Consumer<Boolean> onValueChange,
                                                Tooltip tooltip) {
        StyledCheckbox checkbox = new StyledCheckbox(x, y, size, label, selected, onValueChange);
        if (tooltip != null) checkbox.setTooltip(tooltip);
        return checkbox;
    }

    // --- responsive footer ------------------------------------------------------------------

    /**
     * A button to place in the footer. {@code width <= 0} means "auto-size from label".
     * {@code tooltip} is optional hover help (e.g. the settings screen Done button).
     */
    public record ButtonSpec(String label, int width, Runnable onClick, Tooltip tooltip) {
        public ButtonSpec(String label, Runnable onClick) {
            this(label, -1, onClick, null);
        }
        public ButtonSpec(String label, int width, Runnable onClick) {
            this(label, width, onClick, null);
        }
    }

    record FooterPlacement(ButtonSpec spec, int x, int y, int width) {
    }

    /**
     * Lays out a footer with a left cluster (primary actions) and a single right-anchored
     * button (typically "Done"). Returns the constructed Button widgets, ready for
     * {@code addRenderableWidget}.
     *
     * If the left cluster would collide with the right button, the overflowing buttons
     * wrap onto a row above the footer. This is the whole reason this helper exists:
     * the previous code grew the left cluster linearly and silently slid under the
     * pinned right button.
     *
     * @param screenW  total screen width
     * @param footerY  y of the primary (bottom) footer row
     * @param left     ordered list of left-cluster buttons
     * @param right    the single right-anchored button, or null if none
     * @param sink     consumer that receives each constructed Button (usually {@code addRenderableWidget})
     */
    public static void layoutFooter(int screenW, int footerY,
                                    List<ButtonSpec> left, ButtonSpec right,
                                    Consumer<Button> sink,
                                    net.minecraft.client.gui.Font font) {
        layoutFooter(screenW, footerY, left, right, sink, font, PAD_OUTER, GAP_SECTION);
    }

    public static int footerHeight(int screenW, List<ButtonSpec> left, ButtonSpec right,
                                   net.minecraft.client.gui.Font font) {
        return footerHeight(screenW, left, right, font, PAD_OUTER, GAP_SECTION);
    }

    public static int footerHeight(int screenW, List<ButtonSpec> left, ButtonSpec right,
                                   net.minecraft.client.gui.Font font,
                                   int startX, int rightInset) {
        return needsFooterWrap(screenW, left, right, font, startX, rightInset)
                ? FOOTER_H + BTN_H + GAP
                : FOOTER_H;
    }

    public static void layoutFooter(int screenW, int footerY,
                                    List<ButtonSpec> left, ButtonSpec right,
                                    Consumer<Button> sink,
                                    net.minecraft.client.gui.Font font,
                                    int startX, int rightInset) {
        for (FooterPlacement placement : footerPlacements(
                screenW, footerY, left, right, font, startX, rightInset)) {
            sink.accept(buildButton(placement.spec(), placement.x(), placement.y(), placement.width()));
        }
    }

    static List<FooterPlacement> footerPlacements(int screenW, int footerY,
                                                  List<ButtonSpec> left, ButtonSpec right,
                                                  net.minecraft.client.gui.Font font,
                                                  int startX, int rightInset) {
        int rightW = right == null ? 0 : measureWidth(right, font);
        int rightX = right == null ? screenW - rightInset : screenW - rightInset - rightW;
        int leftLimit = right == null ? screenW - rightInset : rightX - GAP_SECTION;

        // Measure everything first so we can decide what fits on the primary row.
        int[] widths = new int[left.size()];
        int needed = 0;
        for (int i = 0; i < left.size(); i++) {
            widths[i] = measureWidth(left.get(i), font);
            needed += widths[i];
        }
        if (!left.isEmpty()) needed += GAP * (left.size() - 1);

        // If everything fits, place it all on the primary row.
        List<ButtonSpec> primary = left;
        List<ButtonSpec> wrapped = List.of();
        int[] primaryW = widths;

        if (needed > leftLimit - startX) {
            // Walk from the end and push buttons onto the wrap row until the primary row fits.
            int cut = left.size();
            int running = needed;
            while (cut > 0 && running > leftLimit - startX) {
                cut--;
                running -= widths[cut];
                if (cut < left.size()) running -= GAP; // removed separator
            }
            primary = new ArrayList<>(left.subList(0, cut));
            wrapped = new ArrayList<>(left.subList(cut, left.size()));
            primaryW = new int[primary.size()];
            System.arraycopy(widths, 0, primaryW, 0, primary.size());
        }

        // Primary row.
        List<FooterPlacement> placements = new ArrayList<>(primary.size()
                + wrapped.size()
                + (right == null ? 0 : 1));
        int x = startX;
        for (int i = 0; i < primary.size(); i++) {
            placements.add(new FooterPlacement(primary.get(i), x, footerY, primaryW[i]));
            x += primaryW[i] + GAP;
        }
        if (right != null) {
            placements.add(new FooterPlacement(right, rightX, footerY, rightW));
        }

        // Wrap row (above the primary row).
        if (!wrapped.isEmpty()) {
            int wrapY = footerY - BTN_H - GAP;
            int wx = startX;
            for (ButtonSpec spec : wrapped) {
                int w = measureWidth(spec, font);
                placements.add(new FooterPlacement(spec, wx, wrapY, w));
                wx += w + GAP;
            }
        }
        return placements;
    }

    private static boolean needsFooterWrap(int screenW, List<ButtonSpec> left, ButtonSpec right,
                                           net.minecraft.client.gui.Font font,
                                           int startX, int rightInset) {
        int rightW = right == null ? 0 : measureWidth(right, font);
        int rightX = right == null ? screenW - rightInset : screenW - rightInset - rightW;
        int leftLimit = right == null ? screenW - rightInset : rightX - GAP_SECTION;
        int needed = 0;
        for (ButtonSpec spec : left) needed += measureWidth(spec, font);
        if (!left.isEmpty()) needed += GAP * (left.size() - 1);
        return needed > leftLimit - startX;
    }

    private static int measureWidth(ButtonSpec spec, net.minecraft.client.gui.Font font) {
        if (spec.width > 0) return spec.width;
        // 12px horizontal padding inside the button matches vanilla's visual weight.
        return Math.max(60, font.width(spec.label) + 16);
    }

    private static Button buildButton(ButtonSpec spec, int x, int y, int w) {
        Button button = new StyledButton(x, y, w, BTN_H,
                Component.literal(spec.label), b -> spec.onClick.run());
        if (spec.tooltip != null) {
            button.setTooltip(spec.tooltip);
        }
        return button;
    }
}
