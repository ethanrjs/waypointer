package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class GuiTokens {

    private GuiTokens() {}

    public static final int GAP_TIGHT = 4;
    public static final int GAP = 8;
    public static final int GAP_SECTION = 16;
    public static final int PAD_OUTER = 16;

    public static final int BTN_H = 20;
    public static final int ROW_H = 22;
    public static final int SIDEBAR_W = 156;
    public static final int FOOTER_H = 28;

    public static final int SURFACE        = 0xC0101216;
    public static final int SURFACE_SUBTLE = 0x60000000;
    public static final int OVERLAY        = 0xF0101216;
    public static final int BORDER         = 0x30FFFFFF;
    public static final int HOVER          = 0x18FFFFFF;
    public static final int SELECTED       = 0x30FFFFFF;
    public static final int ACCENT         = 0xFF4FB3C4;

    public static final int TEXT       = 0xFFE6E9EC;
    public static final int TEXT_DIM   = 0xFFB0B6BE;
    public static final int TEXT_MUTED = 0xFF80868E;

    public static final int SUCCESS = 0xFF7ACB89;
    public static final int WARNING = 0xFFFFB060;
    public static final int DANGER  = 0xFFE47B7B;

    /** Message tinted with a token color, e.g. {@code colored(label, ACCENT)}. */
    public static MutableComponent colored(Component message, int argb) {
        return message.copy().withStyle(
                style -> style.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
    }

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

    public enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private static final class DirectionButton extends StyledButton {
        private final Direction direction;

        private DirectionButton(int x, int y, int width, int height,
                                Component message, Direction direction, OnPress onPress) {
            super(x, y, width, height, message, onPress);
            this.direction = direction;
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            int x = getX();
            int y = getY();
            boolean highlighted = active && isHoveredOrFocused();
            drawControlFrame(g, x, y, getWidth(), getHeight(),
                    active, highlighted, isFocused());
            int color = !active ? TEXT_MUTED : highlighted ? ACCENT : TEXT;
            drawDirectionGlyph(g, direction,
                    x + getWidth() / 2, y + getHeight() / 2, color);
        }
    }

    public static final class StyledCheckbox extends Checkbox {
        private static final int CHECKMARK_TEXTURE_SIZE = 16;
        private static final Identifier CHECKMARK = Identifier.fromNamespaceAndPath(
                Waypointer.MOD_ID, "textures/gui/checkmark.png");

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

            int checkX = x + (getWidth() - CHECKMARK_TEXTURE_SIZE) / 2;
            int checkY = y + (getHeight() - CHECKMARK_TEXTURE_SIZE) / 2;
            g.blit(RenderPipelines.GUI_TEXTURED, CHECKMARK,
                    checkX, checkY, 0.0f, 0.0f,
                    CHECKMARK_TEXTURE_SIZE, CHECKMARK_TEXTURE_SIZE,
                    CHECKMARK_TEXTURE_SIZE, CHECKMARK_TEXTURE_SIZE);
            if (!active) {
                g.fill(checkX, checkY,
                        checkX + CHECKMARK_TEXTURE_SIZE,
                        checkY + CHECKMARK_TEXTURE_SIZE, 0x80000000);
            }
        }
    }

    private static final int CONTROL_IDLE = 0xE0181D22;
    private static final int CONTROL_HOVER = 0xF026343A;
    private static final int CONTROL_DISABLED = 0xB0121519;
    private static final int CONTROL_BORDER = 0x5AFFFFFF;
    private static final int CONTROL_HIGHLIGHT = 0x24FFFFFF;

    static void drawControlFrame(GuiGraphicsExtractor g, int x, int y, int width, int height,
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

    static int opticalInfoButtonY(int titleY) {
        return titleY - 2;
    }

    public static Button styledButton(int x, int y, int width, int height,
                                      Component message, Button.OnPress onPress,
                                      Tooltip tooltip) {
        Direction direction = directionForLabel(message.getString());
        Button button = direction == null
                ? new StyledButton(x, y, width, height, message, onPress)
                : new DirectionButton(x, y, width, height, message, direction, onPress);
        if (tooltip != null) button.setTooltip(tooltip);
        return button;
    }

    static Direction directionForLabel(String label) {
        return switch (label) {
            case "\u25b2" -> Direction.UP;
            case "\u25bc" -> Direction.DOWN;
            case "\u25c0" -> Direction.LEFT;
            case "\u25b6" -> Direction.RIGHT;
            default -> null;
        };
    }

    public static void drawDirectionGlyph(
            GuiGraphicsExtractor g, Direction direction,
            int centerX, int centerY, int color) {
        for (int step = 0; step < 5; step++) {
            int span = step * 2 + 1;
            switch (direction) {
                case UP -> g.fill(centerX - step, centerY - 2 + step,
                        centerX - step + span, centerY - 1 + step, color);
                case DOWN -> g.fill(centerX - step, centerY + 2 - step,
                        centerX - step + span, centerY + 3 - step, color);
                case LEFT -> g.fill(centerX - 2 + step, centerY - step,
                        centerX - 1 + step, centerY - step + span, color);
                case RIGHT -> g.fill(centerX + 2 - step, centerY - step,
                        centerX + 3 - step, centerY - step + span, color);
            }
        }
    }

    private static final int INFO_FILL       = 0xFF1A1F24;
    private static final int INFO_FILL_HOVER = 0xFF26343A;
    private static final int TOOLTIP_SEPARATOR = 0x55FFFFFF;

    /** Floating panel used by tooltips, dropdowns, and pickers: OVERLAY fill, BORDER frame. */
    public static void drawTooltipPanel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, OVERLAY);
        g.fill(x1, y1, x2, y1 + 1, BORDER);
        g.fill(x1, y2 - 1, x2, y2, BORDER);
        g.fill(x1, y1, x1 + 1, y2, BORDER);
        g.fill(x2 - 1, y1, x2, y2, BORDER);
    }

    public static void drawInfoButton(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                      int x, int y, int size, boolean hovered) {
        g.fill(x, y, x + size, y + size, hovered ? 0xB0FFFFFF : BORDER);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1,
                hovered ? INFO_FILL_HOVER : INFO_FILL);
        int glyphX = x + (size - font.width("i")) / 2;
        g.text(font, "i", glyphX, y + (size - StyledButton.VISIBLE_GLYPH_HEIGHT) / 2,
                hovered ? ACCENT : TEXT_DIM, false);
    }

    public static void drawInfoTooltip(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                       String title, String[] labels, String[] descriptions,
                                       int[] labelColors, int mouseX, int mouseY,
                                       int maxRight, int maxBottom) {
        int lineCount = Math.min(labels.length, descriptions.length);
        int pad = GAP;
        int lineGap = 3;
        int maxLabelWidth = 0;
        int maxLineWidth = font.width(title);
        for (int i = 0; i < lineCount; i++) {
            maxLabelWidth = Math.max(maxLabelWidth, font.width(labels[i]));
        }
        for (int i = 0; i < lineCount; i++) {
            maxLineWidth = Math.max(maxLineWidth,
                    maxLabelWidth + GAP + font.width(descriptions[i]));
        }

        int w = maxLineWidth + pad * 2;
        int h = pad * 2 + font.lineHeight + 5
                + lineCount * font.lineHeight + Math.max(0, lineCount - 1) * lineGap;
        int x = Math.max(PAD_OUTER, Math.min(mouseX + 12, Math.max(PAD_OUTER, maxRight - w)));
        int y = Math.max(PAD_OUTER, Math.min(mouseY + 12, Math.max(PAD_OUTER, maxBottom - h)));
        drawTooltipPanel(g, x, y, x + w, y + h);

        int textX = x + pad;
        int textY = y + pad;
        g.text(font, title, textX, textY, ACCENT, false);
        int separatorY = textY + font.lineHeight + 2;
        g.fill(textX, separatorY, x + w - pad, separatorY + 1, TOOLTIP_SEPARATOR);
        int rowY = separatorY + 4;
        for (int i = 0; i < lineCount; i++) {
            int labelColor = labelColors != null && i < labelColors.length
                    ? labelColors[i] : TEXT;
            g.text(font, labels[i], textX, rowY, labelColor, false);
            g.text(font, descriptions[i], textX + maxLabelWidth + GAP, rowY, TEXT_DIM, false);
            rowY += font.lineHeight + lineGap;
        }
    }

    public static StyledCheckbox styledCheckbox(int x, int y, int size, Component label,
                                                boolean selected, Consumer<Boolean> onValueChange,
                                                Tooltip tooltip) {
        StyledCheckbox checkbox = new StyledCheckbox(x, y, size, label, selected, onValueChange);
        if (tooltip != null) checkbox.setTooltip(tooltip);
        return checkbox;
    }

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

        int[] widths = new int[left.size()];
        int needed = 0;
        for (int i = 0; i < left.size(); i++) {
            widths[i] = measureWidth(left.get(i), font);
            needed += widths[i];
        }
        if (!left.isEmpty()) needed += GAP * (left.size() - 1);

        List<ButtonSpec> primary = left;
        List<ButtonSpec> wrapped = List.of();
        int[] primaryW = widths;

        if (needed > leftLimit - startX) {
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
        return Math.max(60, font.width(spec.label) + 16);
    }

    private static Button buildButton(ButtonSpec spec, int x, int y, int w) {
        return styledButton(x, y, w, BTN_H,
                Component.literal(spec.label), b -> spec.onClick.run(), spec.tooltip);
    }
}
