package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static org.lwjgl.glfw.GLFW.*;

/** Pixel editor for one repeated face or the full six-face waypoint cubemap. */
public final class WaypointPainterScreen extends Screen {

    private enum FaceView { ONE, ALL }

    private static final int PALETTE_W = 84;
    private static final int PREVIEW_MAX = 176;
    private static final int SWATCH_H = 16;
    private static final int SWATCH_GAP = 2;

    private final Screen parent;
    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final WaypointGroup targetGroup;
    private final int[] palette;
    private final byte[] pixels;
    private FaceView faceView;
    private int selectedPalette;
    private WaypointPaint.Face cursorFace = WaypointPaint.Face.NORTH;
    private int cursorX;
    private int cursorY;
    private boolean painting;
    private int lastPaintOffset = -1;
    private WaypointPaint snapshot;
    private WaypointPaintPreviewTexture preview;
    private String status = "";
    private Button faceViewButton;
    private CanvasButton canvasButton;

    public WaypointPainterScreen(Screen parent, WaypointerConfig config,
                                 ActiveGroupManager manager) {
        this(parent, config, manager, null);
    }

    public WaypointPainterScreen(Screen parent, WaypointerConfig config,
                                 ActiveGroupManager manager, WaypointGroup targetGroup) {
        super(Component.literal("Waypoint Painter"));
        this.parent = parent;
        this.config = config;
        this.manager = manager;
        this.targetGroup = targetGroup;
        WaypointPaint initial = initialPaint(manager, config, targetGroup);
        if (!config.hasWaypointPainterPalette()) {
            config.setWaypointPainterPalette(initial.paletteCopy());
        }
        this.palette = config.waypointPainterPalette();
        this.pixels = initial.pixelsCopy();
        this.faceView = initial.hasIdenticalFaces() ? FaceView.ONE : FaceView.ALL;
    }

    @Override
    protected void init() {
        Layout layout = layout();
        if (preview == null) preview = new WaypointPaintPreviewTexture();

        int columns = layout.paletteColumns();
        int swatchW = (PALETTE_W - SWATCH_GAP * (columns - 1)) / columns;
        for (int slot = 0; slot < WaypointPaint.PALETTE_SIZE; slot++) {
            int column = slot % columns;
            int row = slot / columns;
            int x = layout.paletteX() + column * (swatchW + SWATCH_GAP);
            int y = layout.swatchesY() + row * (SWATCH_H + SWATCH_GAP);
            PaletteButton button = new PaletteButton(x, y, swatchW, SWATCH_H, slot);
            button.setTooltip(Tooltip.create(Component.literal(
                    "Select color " + (slot + 1) + " (#" + String.format("%06X", palette[slot]) + ").")));
            addRenderableWidget(button);
        }

        addRenderableWidget(styledButton(layout.paletteX(), layout.editColorY(),
                PALETTE_W, BTN_H, Component.literal("Colors"),
                b -> editSelectedColor(),
                Tooltip.create(Component.literal("Change the selected swatch."))));

        faceViewButton = styledButton(layout.paletteX(), layout.faceViewY(),
                PALETTE_W, BTN_H, Component.literal(faceViewLabel()),
                b -> toggleFaceView(),
                Tooltip.create(Component.literal(
                        "One repeats one face on every side. All Faces edits each side.")));
        addRenderableWidget(faceViewButton);

        canvasButton = new CanvasButton(layout.canvasLeft(), layout.contentTop(),
                layout.canvasRight() - layout.canvasLeft(),
                layout.contentBottom() - layout.contentTop());
        addRenderableWidget(canvasButton);

        addRenderableWidget(styledButton(PAD_OUTER, height - FOOTER_H,
                64, BTN_H, Component.literal("Back"), b -> onClose(), null));
        addRenderableWidget(styledButton(width - PAD_OUTER - 72, height - FOOTER_H,
                72, BTN_H, Component.literal("Apply"), b -> openApplyTargets(),
                Tooltip.create(Component.literal(targetGroup == null
                        ? "Choose which waypoint routes receive this paint."
                        : "Apply this paint to the current route."))));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Layout layout = layout();
        g.fill(0, 0, width, height, SURFACE);
        String title = getTitle().getString();
        g.text(font, title, (width - font.width(title)) / 2, PAD_OUTER, TEXT, false);

        g.fill(layout.paletteX() - 4, layout.contentTop(),
                layout.paletteX() + PALETTE_W + 4, layout.contentBottom(), SURFACE_SUBTLE);
        g.text(font, "Swatches", layout.paletteX(), layout.contentTop() + 6, TEXT_DIM, false);
        g.text(font, "Face View", layout.paletteX(), layout.faceViewY() - 11, TEXT_DIM, false);

        drawCanvas(g, layout, mouseX, mouseY);
        drawPreview(g, layout);
        super.extractRenderState(g, mouseX, mouseY, partial);

        if (!status.isEmpty()) {
            String clipped = font.plainSubstrByWidth(status, Math.max(0, width - 190));
            g.text(font, clipped, (width - font.width(clipped)) / 2,
                    height - FOOTER_H + 6, TEXT_DIM, false);
        }
    }

    private void drawCanvas(GuiGraphicsExtractor g, Layout layout, int mouseX, int mouseY) {
        g.fill(layout.canvasLeft(), layout.contentTop(),
                layout.canvasRight(), layout.contentBottom(), SURFACE_SUBTLE);
        String label = faceView == FaceView.ONE ? "16x16 face" : "All Faces";
        g.text(font, label, layout.canvasLeft() + 6, layout.contentTop() + 6, TEXT_DIM, false);

        if (faceView == FaceView.ONE) {
            drawFace(g, layout, WaypointPaint.Face.NORTH, 0, 0, mouseX, mouseY);
        } else {
            for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
                drawFace(g, layout, face, face.atlasX() / WaypointPaint.SIZE,
                        face.atlasY() / WaypointPaint.SIZE, mouseX, mouseY);
            }
        }
    }

    private void drawFace(GuiGraphicsExtractor g, Layout layout, WaypointPaint.Face face,
                          int faceColumn, int faceRow, int mouseX, int mouseY) {
        int left = layout.gridLeft() + faceColumn * WaypointPaint.SIZE * layout.cell();
        int top = layout.gridTop() + faceRow * WaypointPaint.SIZE * layout.cell();
        int faceSize = WaypointPaint.SIZE * layout.cell();
        g.fill(left - 1, top - 1, left + faceSize + 1, top + faceSize + 1, BORDER);
        for (int y = 0; y < WaypointPaint.SIZE; y++) {
            for (int x = 0; x < WaypointPaint.SIZE; x++) {
                int color = palette[Byte.toUnsignedInt(pixels[WaypointPaint.pixelOffset(face, x, y)])];
                int x1 = left + x * layout.cell();
                int y1 = top + y * layout.cell();
                g.fill(x1, y1, x1 + layout.cell(), y1 + layout.cell(), 0xFF000000 | color);
            }
        }
        if (layout.cell() >= 3) {
            for (int line = 1; line < WaypointPaint.SIZE; line++) {
                int lineX = left + line * layout.cell();
                int lineY = top + line * layout.cell();
                g.fill(lineX, top, lineX + 1, top + faceSize, 0x70000000);
                g.fill(left, lineY, left + faceSize, lineY + 1, 0x70000000);
            }
        }

        PaintCell hovered = cellAt(layout, mouseX, mouseY);
        if (hovered != null && hovered.face() == face) {
            outlineCell(g, left, top, hovered.x(), hovered.y(), layout.cell(), 0xFFFFFFFF);
        }
        if (getFocused() == canvasButton && cursorFace == face) {
            outlineCell(g, left, top, cursorX, cursorY, layout.cell(), ACCENT);
        }
    }

    private static void outlineCell(GuiGraphicsExtractor g, int faceLeft, int faceTop,
                                    int x, int y, int cell, int color) {
        int x1 = faceLeft + x * cell;
        int y1 = faceTop + y * cell;
        int x2 = x1 + cell;
        int y2 = y1 + cell;
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    private void drawPreview(GuiGraphicsExtractor g, Layout layout) {
        g.fill(layout.previewX(), layout.previewY(),
                layout.previewX() + layout.previewSize(),
                layout.previewY() + layout.previewSize(), SURFACE_SUBTLE);
        preview.update(snapshot(), (System.currentTimeMillis() % 12_000L) * 0.03f);
        g.blit(RenderPipelines.GUI_TEXTURED, preview.id(),
                layout.previewX(), layout.previewY(), 0f, 0f,
                layout.previewSize(), layout.previewSize(),
                WaypointPaintPreviewTexture.SIZE, WaypointPaintPreviewTexture.SIZE,
                WaypointPaintPreviewTexture.SIZE, WaypointPaintPreviewTexture.SIZE);
        String label = "Live preview";
        g.text(font, label,
                layout.previewX() + (layout.previewSize() - font.width(label)) / 2,
                layout.previewY() + layout.previewSize() + 5, TEXT_DIM, false);
    }

    private void editSelectedColor() {
        ColorPickerScreen.open(this, "Paint Swatch", palette[selectedPalette], picked -> {
            palette[selectedPalette] = picked & 0xFFFFFF;
            config.setWaypointPainterPalette(palette);
            snapshot = null;
            status = "";
        });
    }

    private void toggleFaceView() {
        if (faceView == FaceView.ALL) {
            faceView = FaceView.ONE;
            cursorFace = WaypointPaint.Face.NORTH;
        } else {
            faceView = FaceView.ALL;
        }
        snapshot = null;
        status = "";
        if (faceViewButton != null) faceViewButton.setMessage(Component.literal(faceViewLabel()));
    }

    static void repeatFace(byte[] pixels, WaypointPaint.Face source) {
        if (pixels == null || pixels.length != WaypointPaint.PIXEL_COUNT || source == null) {
            throw new IllegalArgumentException("complete waypoint paint and source face are required");
        }
        int sourceOffset = source.ordinal() * WaypointPaint.FACE_PIXELS;
        byte[] face = Arrays.copyOfRange(
                pixels, sourceOffset, sourceOffset + WaypointPaint.FACE_PIXELS);
        for (WaypointPaint.Face target : WaypointPaint.Face.values()) {
            System.arraycopy(face, 0, pixels,
                    target.ordinal() * WaypointPaint.FACE_PIXELS,
                    WaypointPaint.FACE_PIXELS);
        }
    }

    private String faceViewLabel() {
        return faceView == FaceView.ONE ? "One" : "All";
    }

    private void openApplyTargets() {
        if (targetGroup != null) {
            applyToGroup(targetGroup);
            onClose();
            return;
        }
        MinecraftCompat.setScreen(minecraft, new WaypointPaintApplyScreen(this, manager));
    }

    void applyToAllGroups() {
        int count = applyToAllGroups(manager, config, snapshot());
        status = count == 1 ? "Applied to 1 route." : "Applied to " + count + " routes.";
    }

    static int applyToAllGroups(ActiveGroupManager manager, WaypointerConfig config,
                                WaypointPaint paint) {
        int count = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (!isPaintTarget(group)) continue;
            group.setPaint(paint);
            group.setPaintEnabled(true);
            count++;
        }
        config.setWaypointPainterDefaultPaint(paint);
        if (count > 0) manager.fireDataChanged();
        return count;
    }

    void applyToGroup(WaypointGroup group) {
        if (!isPaintTarget(group) || manager.get(group.id()) != group) return;
        group.setPaint(snapshot());
        group.setPaintEnabled(true);
        manager.fireDataChangedFor(group);
        status = "Applied to " + group.name() + ".";
    }

    private static boolean isPaintTarget(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly();
    }

    private WaypointPaint snapshot() {
        if (snapshot == null) {
            byte[] snapshotPixels = faceView == FaceView.ONE
                    ? repeatedFaceCopy(pixels, WaypointPaint.Face.NORTH)
                    : pixels;
            snapshot = new WaypointPaint(palette, snapshotPixels);
        }
        return snapshot;
    }

    static byte[] repeatedFaceCopy(byte[] pixels, WaypointPaint.Face source) {
        if (pixels == null) throw new IllegalArgumentException("waypoint paint is required");
        byte[] repeated = pixels.clone();
        repeatFace(repeated, source);
        return repeated;
    }

    private void paint(PaintCell cell) {
        int offset = WaypointPaint.pixelOffset(cell.face(), cell.x(), cell.y());
        if (offset == lastPaintOffset) return;
        lastPaintOffset = offset;
        cursorFace = cell.face();
        cursorX = cell.x();
        cursorY = cell.y();
        if (faceView == FaceView.ONE) {
            for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
                pixels[WaypointPaint.pixelOffset(face, cell.x(), cell.y())] = (byte) selectedPalette;
            }
        } else {
            pixels[offset] = (byte) selectedPalette;
        }
        snapshot = null;
        status = "";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            PaintCell cell = cellAt(layout(), event.x(), event.y());
            if (cell != null) {
                setFocused(null);
                painting = true;
                lastPaintOffset = -1;
                paint(cell);
                return true;
            }
            Layout layout = layout();
            if (event.x() >= layout.canvasLeft() && event.x() < layout.canvasRight()
                    && event.y() >= layout.contentTop() && event.y() < layout.contentBottom()) {
                setFocused(null);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (painting && event.button() == 0) {
            PaintCell cell = cellAt(layout(), event.x(), event.y());
            if (cell != null) paint(cell);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        painting = false;
        lastPaintOffset = -1;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() == canvasButton && event.key() != GLFW_KEY_TAB) {
            switch (event.key()) {
                case GLFW_KEY_LEFT -> moveCursor(-1, 0);
                case GLFW_KEY_RIGHT -> moveCursor(1, 0);
                case GLFW_KEY_UP -> moveCursor(0, -1);
                case GLFW_KEY_DOWN -> moveCursor(0, 1);
                case GLFW_KEY_SPACE, GLFW_KEY_ENTER -> {
                    lastPaintOffset = -1;
                    paint(new PaintCell(cursorFace, cursorX, cursorY));
                }
                default -> { return super.keyPressed(event); }
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private void moveCursor(int dx, int dy) {
        if (faceView == FaceView.ONE) {
            cursorX = Math.max(0, Math.min(WaypointPaint.SIZE - 1, cursorX + dx));
            cursorY = Math.max(0, Math.min(WaypointPaint.SIZE - 1, cursorY + dy));
            return;
        }

        int atlasX = cursorFace.atlasX() + cursorX + dx;
        int atlasY = cursorFace.atlasY() + cursorY + dy;
        WaypointPaint.Face target = faceAtAtlasPixel(atlasX, atlasY);
        if (target == null) return;
        cursorFace = target;
        cursorX = atlasX - target.atlasX();
        cursorY = atlasY - target.atlasY();
    }

    static WaypointPaint.Face faceAtAtlasPixel(int atlasX, int atlasY) {
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            if (atlasX >= face.atlasX() && atlasX < face.atlasX() + WaypointPaint.SIZE
                    && atlasY >= face.atlasY() && atlasY < face.atlasY() + WaypointPaint.SIZE) {
                return face;
            }
        }
        return null;
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        if (preview != null) {
            preview.release();
            preview = null;
        }
        super.removed();
    }

    private PaintCell cellAt(Layout layout, double mouseX, double mouseY) {
        if (faceView == FaceView.ONE) {
            return cellInsideFace(layout, WaypointPaint.Face.NORTH, 0, 0, mouseX, mouseY);
        }
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            PaintCell cell = cellInsideFace(layout, face,
                    face.atlasX() / WaypointPaint.SIZE,
                    face.atlasY() / WaypointPaint.SIZE, mouseX, mouseY);
            if (cell != null) return cell;
        }
        return null;
    }

    private static PaintCell cellInsideFace(Layout layout, WaypointPaint.Face face,
                                            int column, int row,
                                            double mouseX, double mouseY) {
        int left = layout.gridLeft() + column * WaypointPaint.SIZE * layout.cell();
        int top = layout.gridTop() + row * WaypointPaint.SIZE * layout.cell();
        int size = WaypointPaint.SIZE * layout.cell();
        if (mouseX < left || mouseX >= left + size || mouseY < top || mouseY >= top + size) {
            return null;
        }
        return new PaintCell(face,
                (int) ((mouseX - left) / layout.cell()),
                (int) ((mouseY - top) / layout.cell()));
    }

    private Layout layout() {
        int contentTop = PAD_OUTER + font.lineHeight + GAP;
        int contentBottom = height - FOOTER_H - GAP;
        int paletteX = PAD_OUTER + 4;
        int paletteColumns = height < 300 ? 4 : 2;
        int paletteRows = (WaypointPaint.PALETTE_SIZE + paletteColumns - 1) / paletteColumns;
        int swatchesY = contentTop + 18;
        int editColorY = swatchesY + paletteRows * (SWATCH_H + SWATCH_GAP) + GAP_TIGHT;
        int faceViewY = Math.min(contentBottom - BTN_H,
                editColorY + BTN_H + font.lineHeight + GAP);

        int previewSize = Math.min(PREVIEW_MAX,
                Math.max(72, Math.min(contentBottom - contentTop - 18, width / 5)));
        int previewX = width - PAD_OUTER - previewSize;
        int previewY = contentTop + Math.max(0,
                (contentBottom - contentTop - previewSize - font.lineHeight) / 2);
        int canvasLeft = paletteX + PALETTE_W + GAP_SECTION;
        int canvasRight = previewX - GAP_SECTION;
        int canvasWidth = Math.max(16, canvasRight - canvasLeft - 12);
        int canvasHeight = Math.max(16, contentBottom - contentTop - 28);
        int columns = faceView == FaceView.ONE ? 1 : 4;
        int rows = faceView == FaceView.ONE ? 1 : 3;
        int cell = Math.max(1, Math.min(12,
                Math.min(canvasWidth / (columns * WaypointPaint.SIZE),
                        canvasHeight / (rows * WaypointPaint.SIZE))));
        int gridWidth = columns * WaypointPaint.SIZE * cell;
        int gridHeight = rows * WaypointPaint.SIZE * cell;
        int gridLeft = canvasLeft + Math.max(6, (canvasRight - canvasLeft - gridWidth) / 2);
        int gridTop = contentTop + 22 + Math.max(0,
                (contentBottom - contentTop - 22 - gridHeight) / 2);
        return new Layout(contentTop, contentBottom, paletteX, paletteColumns, swatchesY,
                editColorY, faceViewY, canvasLeft, canvasRight,
                gridLeft, gridTop, cell, previewX, previewY, previewSize);
    }

    private static WaypointPaint initialPaint(ActiveGroupManager manager, WaypointerConfig config,
                                              WaypointGroup targetGroup) {
        if (targetGroup != null) {
            if (targetGroup.paint() != null) return targetGroup.paint();
            WaypointPaint inherited = config.waypointPainterDefaultPaint();
            if (targetGroup.paintEnabled() && inherited != null) return inherited;
            return new WaypointPaint(config.waypointPainterPalette(),
                    new byte[WaypointPaint.PIXEL_COUNT]);
        }
        if (manager != null) {
            String activeZone = manager.currentZone() == null ? null : manager.currentZone().id();
            for (WaypointGroup group : manager.allGroups()) {
                if (isPaintTarget(group) && group.enabled()
                        && activeZone != null && activeZone.equals(group.zoneId())
                        && group.paint() != null) {
                    return group.paint();
                }
            }
            for (WaypointGroup group : manager.allGroups()) {
                if (isPaintTarget(group) && group.paint() != null) return group.paint();
            }
        }
        WaypointPaint inherited = config.waypointPainterDefaultPaint();
        return inherited != null ? inherited : new WaypointPaint(
                config.waypointPainterPalette(), new byte[WaypointPaint.PIXEL_COUNT]);
    }

    private record PaintCell(WaypointPaint.Face face, int x, int y) {}

    private record Layout(int contentTop, int contentBottom, int paletteX,
                          int paletteColumns, int swatchesY, int editColorY, int faceViewY,
                          int canvasLeft, int canvasRight,
                          int gridLeft, int gridTop, int cell,
                          int previewX, int previewY, int previewSize) {}

    private final class PaletteButton extends AbstractButton {
        private final int slot;

        private PaletteButton(int x, int y, int width, int height, int slot) {
            super(x, y, width, height, Component.literal("Paint color " + (slot + 1)));
            this.slot = slot;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            selectedPalette = slot;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            int border = selectedPalette == slot ? ACCENT
                    : isHoveredOrFocused() ? 0xFFFFFFFF : BORDER;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            g.fill(getX() + 2, getY() + 2,
                    getX() + getWidth() - 2, getY() + getHeight() - 2,
                    0xFF000000 | palette[slot]);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class CanvasButton extends AbstractButton {
        private CanvasButton(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(
                    "Waypoint paint canvas. Arrow keys move; Space paints."));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            // Focus is the action; painting is handled by the screen with pixel coordinates.
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            // Focus is shown on the keyboard cursor cell, not around the pane.
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
