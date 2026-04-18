package dev.ethan.waypointer.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared GUI tokens for Waypointer screens.
 *
 * Centralized so all three screens (WaypointerScreen, GroupEditScreen, ConfigScreen)
 * share the same spacing rhythm, surface colors, and footer behavior. Before this,
 * each screen invented its own PADDING and ad-hoc pixel gaps, which is how the
 * footer-overlap bug crept in: buttons laid out left-to-right with fixed widths
 * eventually walked under the right-anchored "Done" button at small GUI scales.
 *
 * Design principles (see .impeccable.md):
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
    public static final int SIDEBAR_W = 180;
    public static final int FOOTER_H = 28;

    // --- colors (ARGB, fed to GuiGraphics.fill) ---------------------------------------------
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

    // --- responsive footer ------------------------------------------------------------------

    /** A button to place in the footer. {@code width <= 0} means "auto-size from label". */
    public record ButtonSpec(String label, int width, Runnable onClick) {
        public ButtonSpec(String label, Runnable onClick) { this(label, -1, onClick); }
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
        int rightW = right == null ? 0 : measureWidth(right, font);
        int rightX = right == null ? screenW - PAD_OUTER : screenW - PAD_OUTER - rightW;
        int leftLimit = right == null ? screenW - PAD_OUTER : rightX - GAP_SECTION;

        // Measure everything first so we can decide what fits on the primary row.
        int[] widths = new int[left.size()];
        int needed = 0;
        for (int i = 0; i < left.size(); i++) {
            widths[i] = measureWidth(left.get(i), font);
            needed += widths[i];
        }
        if (!left.isEmpty()) needed += GAP * (left.size() - 1);

        // If everything fits, place it all on the primary row.
        int startX = PAD_OUTER;
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
        int x = startX;
        for (int i = 0; i < primary.size(); i++) {
            sink.accept(buildButton(primary.get(i), x, footerY, primaryW[i]));
            x += primaryW[i] + GAP;
        }
        if (right != null) {
            sink.accept(buildButton(right, rightX, footerY, rightW));
        }

        // Wrap row (above the primary row).
        if (!wrapped.isEmpty()) {
            int wrapY = footerY - BTN_H - GAP;
            int wx = startX;
            for (ButtonSpec spec : wrapped) {
                int w = measureWidth(spec, font);
                sink.accept(buildButton(spec, wx, wrapY, w));
                wx += w + GAP;
            }
        }
    }

    private static int measureWidth(ButtonSpec spec, net.minecraft.client.gui.Font font) {
        if (spec.width > 0) return spec.width;
        // 12px horizontal padding inside the button matches vanilla's visual weight.
        return Math.max(60, font.width(spec.label) + 16);
    }

    private static Button buildButton(ButtonSpec spec, int x, int y, int w) {
        return Button.builder(Component.literal(spec.label), b -> spec.onClick.run())
                .bounds(x, y, w, BTN_H).build();
    }
}
