package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
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
import com.babbur.waypointer.compat.ImageFilePicker;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static com.babbur.waypointer.compat.InputCompat.*;

public final class WaypointPainterScreen extends Screen {

    private enum FaceView { ONE, ALL }
    private enum Tool { BRUSH, FILL }

    private static final int PALETTE_W = 84;
    private static final int PREVIEW_MAX = 176;
    private static final float PREVIEW_MIN_FILL = 0.2f;
    private static final int SWATCH_H = 16;
    private static final int SWATCH_GAP = 2;

    private final Screen parent;
    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final PaintScope scope;
    private final int[] palette;
    private final byte[] pixels;
    private FaceView faceView;
    private Tool tool = Tool.BRUSH;
    private int selectedPalette;
    private WaypointPaint.Face cursorFace = WaypointPaint.Face.NORTH;
    private int cursorX;
    private int cursorY;
    private boolean painting;
    private final EditHistory<PaintState> history;
    private HistoryButton undoButton, redoButton;
    private record PaintState(WaypointPaint paint, FaceView faceView) {}
    private int lastPaintOffset = -1;
    private WaypointPaint snapshot;
    private boolean importing;
    private WaypointPaintPreviewTexture preview;
    private final long previewStartedNanos = System.nanoTime();
    private String status = "";
    private Button faceViewButton;
    private Button toolButton;
    private Button colorButton;
    private Button importButton;
    private Button applyButton;
    private Button removeButton;
    private final PaletteButton[] paletteButtons = new PaletteButton[WaypointPaint.PALETTE_SIZE];
    private CanvasButton canvasButton;

    public WaypointPainterScreen(Screen parent, WaypointerConfig config,
                                 ActiveGroupManager manager) {
        this(parent, config, manager, PaintScope.GLOBAL);
    }

    public WaypointPainterScreen(Screen parent, WaypointerConfig config,
                                 ActiveGroupManager manager, WaypointGroup targetGroup) {
        this(parent, config, manager, targetGroup == null ? PaintScope.GLOBAL : PaintScope.route(targetGroup));
    }

    WaypointPainterScreen(Screen parent, WaypointerConfig config,
                          ActiveGroupManager manager, PaintScope scope) {
        super(Component.translatable("waypointer.screen.painter.title"));
        this.parent = parent;
        this.config = config;
        this.manager = manager;
        this.scope = scope;
        WaypointPaint initial = initialPaint(config, scope);
        if (!config.hasWaypointPainterPalette()) {
            config.setWaypointPainterPalette(initial.paletteCopy());
        }
        this.palette = config.waypointPainterPalette();
        this.pixels = initial.pixelsCopy();
        this.faceView = initial.hasIdenticalFaces() ? FaceView.ONE : FaceView.ALL;
        history = new EditHistory<>(capture());
    }

    @Override
    protected void init() {
        Layout layout = layout();
        int previewPixels = WaypointPaintPreviewTexture.clampSize(
                layout.previewSize() * minecraft.getWindow().getGuiScale());
        if (preview != null && preview.size() != previewPixels) {
            preview.release();
            preview = null;
        }
        if (preview == null) preview = new WaypointPaintPreviewTexture(previewPixels);

        int columns = layout.paletteColumns();
        int swatchW = (PALETTE_W - SWATCH_GAP * (columns - 1)) / columns;
        for (int slot = 0; slot < WaypointPaint.PALETTE_SIZE; slot++) {
            int column = slot % columns;
            int row = slot / columns;
            int x = layout.paletteX() + column * (swatchW + SWATCH_GAP);
            int y = layout.swatchesY() + row * (SWATCH_H + SWATCH_GAP);
            PaletteButton button = new PaletteButton(x, y, swatchW, SWATCH_H, slot);
            paletteButtons[slot] = button;
            refreshPaletteTooltip(slot);
            addRenderableWidget(button);
        }

        colorButton = addRenderableWidget(styledButton(layout.paletteX(), layout.editColorY(),
                PALETTE_W, BTN_H, Component.translatable("waypointer.screen.painter.colors"),
                b -> editSelectedColor(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.painter.colors.tooltip"))));

        toolButton = addRenderableWidget(styledButton(layout.paletteX(), layout.toolY(),
                PALETTE_W, BTN_H, toolComponent(), b -> toggleTool(),
                Tooltip.create(Component.translatable("waypointer.screen.painter.tool.tooltip"))));

        faceViewButton = styledButton(layout.paletteX(), layout.faceViewY(),
                PALETTE_W, BTN_H, faceViewComponent(),
                b -> toggleFaceView(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.painter.face_view.tooltip")));
        addRenderableWidget(faceViewButton);

        canvasButton = new CanvasButton(layout.canvasLeft(), layout.contentTop(),
                layout.canvasRight() - layout.canvasLeft(),
                layout.contentBottom() - layout.contentTop());
        addRenderableWidget(canvasButton);

        addRenderableWidget(styledButton(PAD_OUTER, height - FOOTER_H,
                64, BTN_H, Component.translatable("gui.back"), b -> onClose(), null));
        importButton = addRenderableWidget(styledButton((width - 112) / 2, height - FOOTER_H,
                112, BTN_H, Component.translatable("waypointer.screen.painter.import"),
                b -> importFromFiles(), null));
        applyButton = addRenderableWidget(styledButton(width - PAD_OUTER - 72, height - FOOTER_H,
                72, BTN_H, Component.translatable("waypointer.screen.painter.apply"),
                b -> apply(), Tooltip.create(Component.translatable(
                        "waypointer.screen.painter.apply." + scopeKey() + ".tooltip"))));
        removeButton = addRenderableWidget(styledButton(width - PAD_OUTER - 72 - GAP - 72, height - FOOTER_H,
                72, BTN_H, Component.translatable("waypointer.screen.painter.remove"),
                b -> removeOwnPaint(), Tooltip.create(Component.translatable(
                        "waypointer.screen.painter.remove." + scopeKey() + ".tooltip"))));
        undoButton = addRenderableWidget(new HistoryButton(width - PAD_OUTER - 48, 4, false, () -> moveHistory(false)));
        redoButton = addRenderableWidget(new HistoryButton(width - PAD_OUTER - 22, 4, true, () -> moveHistory(true)));
        refreshEditingState();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Layout layout = layout();
        g.fill(0, 0, width, height, SURFACE);
        String title = getTitle().getString();
        g.text(font, title, (width - font.width(title)) / 2, PAD_OUTER, TEXT, false);

        g.fill(layout.paletteX() - 4, layout.contentTop(),
                layout.paletteX() + PALETTE_W + 4, layout.contentBottom(), SURFACE_SUBTLE);
        g.text(font, Component.translatable("waypointer.screen.painter.swatches"),
                layout.paletteX(), layout.contentTop() + 6, TEXT_DIM, false);
        g.text(font, Component.translatable("waypointer.screen.painter.tool"),
                layout.paletteX(), layout.toolY() - 11, TEXT_DIM, false);
        g.text(font, Component.translatable("waypointer.screen.painter.face_view"),
                layout.paletteX(), layout.faceViewY() - 11, TEXT_DIM, false);

        drawCanvas(g, layout, mouseX, mouseY);
        drawPreview(g, layout);
        super.extractRenderState(g, mouseX, mouseY, partial);

        if (!status.isEmpty()) {
            String clipped = font.plainSubstrByWidth(status, Math.max(0, width - 190));
            g.text(font, clipped, (width - font.width(clipped)) / 2,
                    height - FOOTER_H - font.lineHeight - GAP_TIGHT, TEXT_DIM, false);
        }
    }

    private void drawCanvas(GuiGraphicsExtractor g, Layout layout, int mouseX, int mouseY) {
        g.fill(layout.canvasLeft(), layout.contentTop(),
                layout.canvasRight(), layout.contentBottom(), SURFACE_SUBTLE);
        String label = Component.translatable(faceView == FaceView.ONE
                ? "waypointer.screen.painter.canvas.one"
                : "waypointer.screen.painter.canvas.all").getString();
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
        g.nextStratum();
        g.fill(left - 1, top - 1, left + faceSize + 1, top + faceSize + 1, BORDER);
        for (int y = 0; y < WaypointPaint.SIZE; y++) {
            int y1 = top + y * layout.cell();
            for (int x = 0; x < WaypointPaint.SIZE; ) {
                int color = palette[Byte.toUnsignedInt(pixels[WaypointPaint.pixelOffset(face, x, y)])];
                int end = x + 1;
                while (end < WaypointPaint.SIZE
                        && palette[Byte.toUnsignedInt(pixels[WaypointPaint.pixelOffset(face, end, y)])] == color) {
                    end++;
                }
                g.fill(left + x * layout.cell(), y1, left + end * layout.cell(), y1 + layout.cell(),
                        0xFF000000 | color);
                x = end;
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
        long elapsed = System.nanoTime() - previewStartedNanos;
        preview.update(snapshot(), (elapsed % 12_000_000_000L) * 0.00000003f, previewLook());
        int textureSize = preview.size();
        g.blit(RenderPipelines.GUI_TEXTURED, preview.id(),
                layout.previewX(), layout.previewY(), 0f, 0f,
                layout.previewSize(), layout.previewSize(),
                textureSize, textureSize, textureSize, textureSize);
        String label = Component.translatable("waypointer.screen.painter.preview").getString();
        g.text(font, label,
                layout.previewX() + (layout.previewSize() - font.width(label)) / 2,
                layout.previewY() + layout.previewSize() + 5, TEXT_DIM, false);
    }

    private WaypointPaintPreviewTexture.Look previewLook() {
        WaypointerConfig style = scope.style(config);
        WaypointerConfig.BoxStyle box = style.effectiveBoxStyle();
        boolean outline = box == WaypointerConfig.BoxStyle.OUTLINED
                || box == WaypointerConfig.BoxStyle.FILLED_OUTLINED;
        int waypointColor = scope.previewColor();

        float fill = Math.max(PREVIEW_MIN_FILL, (float) style.beaconOpacity());
        return new WaypointPaintPreviewTexture.Look(fill, outline,
                style.resolvedWaypointOutlineColor(waypointColor),
                (float) style.waypointOutlineOpacity(), (float) style.waypointOutlineThickness());
    }

    private void editSelectedColor() {
        if (importing) return;
        ColorPickerScreen.open(this,
                Component.translatable("waypointer.screen.painter.picker.swatch"),
                palette[selectedPalette], picked -> {
            palette[selectedPalette] = picked & 0xFFFFFF;
            config.setWaypointPainterPalette(palette);
            refreshPaletteTooltip(selectedPalette);
            remember();
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
        if (faceViewButton != null) faceViewButton.setMessage(faceViewComponent());
        remember();
    }

    private void importFromFiles() {
        if (importing) return;
        importing = true;
        refreshEditingState();
        ImageFilePicker.open(Component.translatable("waypointer.screen.painter.file_dialog.title").getString(),
                List.of("png", "jpg", "jpeg", "bmp"),
                Component.translatable("waypointer.screen.painter.file_dialog.filter").getString())
                .whenComplete((path, failure) -> {
                    importing = false;
                    if (MinecraftCompat.screen(minecraft) != this) return;
                    if (failure != null) {
                        status = Component.translatable("waypointer.screen.painter.status.picker_failed").getString();
                    } else if (path != null) importFromFile(path);
                    refreshEditingState();
                });
    }

    private void importFromFile(String path) {

        WaypointPaint current = new WaypointPaint(palette, pixels);
        boolean atlas = faceView == FaceView.ALL;
        importing = true;
        status = Component.translatable("waypointer.screen.painter.status.importing").getString();
        refreshEditingState();
        CompletableFuture.supplyAsync(() -> {
            try {
                return WaypointPaintImageImporter.importFile(new File(path), current, atlas);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((imported, failure) -> minecraft.execute(() -> {
            importing = false;
            if (MinecraftCompat.screen(minecraft) != this) return;
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                status = Component.translatable("waypointer.screen.painter.status.import_failed_reason",
                        cause.getMessage()).getString();
                refreshEditingState();
                return;
            }
            System.arraycopy(imported.paletteCopy(), 0, palette, 0, palette.length);
            System.arraycopy(imported.pixelsCopy(), 0, pixels, 0, pixels.length);
            config.setWaypointPainterPalette(palette);
            remember();
            refreshPaletteTooltips();
            snapshot = null;
            status = Component.translatable(faceView == FaceView.ONE
                    ? "waypointer.screen.painter.status.imported_face"
                    : "waypointer.screen.painter.status.imported_uv").getString();
            refreshEditingState();
        }));
    }

    private void refreshEditingState() {
        boolean editable = !importing;
        if (colorButton != null) colorButton.active = editable;
        if (canvasButton != null) canvasButton.active = editable;
        if (faceViewButton != null) faceViewButton.active = editable;
        if (toolButton != null) toolButton.active = editable;
        if (importButton != null) importButton.active = !importing;
        if (applyButton != null) applyButton.active = !importing;
        if (removeButton != null) removeButton.visible = scope.ownPaint(config) != null;
        if (undoButton != null) undoButton.active = editable && history.canUndo();
        if (redoButton != null) redoButton.active = editable && history.canRedo();
    }

    static boolean acceptsImageDimensions(int width, int height) {
        return WaypointPaintImageImporter.acceptsImageDimensions(width, height);
    }

    private void refreshPaletteTooltips() {
        for (int slot = 0; slot < paletteButtons.length; slot++) {
            refreshPaletteTooltip(slot);
        }
    }

    private void refreshPaletteTooltip(int slot) {
        PaletteButton button = paletteButtons[slot];
        if (button != null) {
            button.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.painter.palette.tooltip",
                    slot + 1, String.format("%06X", palette[slot]))));
        }
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

    private Component faceViewComponent() {
        return Component.translatable(faceView == FaceView.ONE
                ? "waypointer.screen.painter.face_view.one"
                : "waypointer.screen.painter.face_view.all");
    }

    private void apply() {
        scope.apply(config, manager, snapshot());
        onClose();
    }

    private void removeOwnPaint() {
        scope.clear(config, manager);
        onClose();
    }

    private String scopeKey() {
        return scope.level().name().toLowerCase(Locale.ROOT);
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
        if (importing) return;
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

    private void fill(PaintCell cell) {
        if (importing) return;
        cursorFace = cell.face();
        cursorX = cell.x();
        cursorY = cell.y();
        boolean changed = faceView == FaceView.ONE
                ? fillOnEveryFace(pixels, cell.x(), cell.y(), selectedPalette)
                : floodFill(pixels, cell.face(), cell.x(), cell.y(), selectedPalette) > 0;
        if (!changed) return;
        snapshot = null;
        status = "";
        remember();
    }

    static int floodFill(byte[] pixels, WaypointPaint.Face face, int x, int y, int slot) {
        int size = WaypointPaint.SIZE;
        byte target = pixels[WaypointPaint.pixelOffset(face, x, y)];
        if (target == (byte) slot) return 0;
        int[] stack = new int[size * size];
        int depth = 0;
        int changed = 1;
        int faceOffset = face.ordinal() * WaypointPaint.FACE_PIXELS;
        stack[depth++] = y * size + x;

        pixels[faceOffset + y * size + x] = (byte) slot;
        while (depth > 0) {
            int cell = stack[--depth];
            int cx = cell % size, cy = cell / size;
            for (int direction = 0; direction < 4; direction++) {
                int neighbor;
                if (direction == 0 && cx > 0) neighbor = cell - 1;
                else if (direction == 1 && cx < size - 1) neighbor = cell + 1;
                else if (direction == 2 && cy > 0) neighbor = cell - size;
                else if (direction == 3 && cy < size - 1) neighbor = cell + size;
                else continue;
                if (pixels[faceOffset + neighbor] != target) continue;
                pixels[faceOffset + neighbor] = (byte) slot;
                stack[depth++] = neighbor;
                changed++;
            }
        }
        return changed;
    }

    static boolean fillOnEveryFace(byte[] pixels, int x, int y, int slot) {
        int north = WaypointPaint.Face.NORTH.ordinal() * WaypointPaint.FACE_PIXELS;
        byte[] filled = pixels.clone();
        if (floodFill(filled, WaypointPaint.Face.NORTH, x, y, slot) == 0) return false;
        for (int i = 0; i < WaypointPaint.FACE_PIXELS; i++) {
            if (filled[north + i] == pixels[north + i]) continue;
            for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
                pixels[face.ordinal() * WaypointPaint.FACE_PIXELS + i] = (byte) slot;
            }
        }
        return true;
    }

    private void toggleTool() {
        tool = tool == Tool.BRUSH ? Tool.FILL : Tool.BRUSH;
        if (toolButton != null) toolButton.setMessage(toolComponent());
    }

    private Component toolComponent() {
        return Component.translatable(tool == Tool.BRUSH
                ? "waypointer.screen.painter.tool.brush"
                : "waypointer.screen.painter.tool.fill");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            PaintCell cell = cellAt(layout(), event.x(), event.y());
            if (cell != null) {
                setFocused(null);
                if (importing) return true;
                if (tool == Tool.FILL) {
                    fill(cell);
                    return true;
                }
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
        if (painting && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            PaintCell cell = cellAt(layout(), event.x(), event.y());
            if (cell != null) paint(cell);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        finishStroke();
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.modifiers() & MOD_CONTROL) != 0) {
            if (event.key() == KEY_Z || event.key() == KEY_Y) {
                moveHistory(event.key() == KEY_Y || (event.modifiers() & MOD_SHIFT) != 0);
                return true;
            }
        }
        if (getFocused() == canvasButton && event.key() != KEY_TAB) {
            switch (event.key()) {
                case KEY_LEFT -> moveCursor(-1, 0);
                case KEY_RIGHT -> moveCursor(1, 0);
                case KEY_UP -> moveCursor(0, -1);
                case KEY_DOWN -> moveCursor(0, 1);
                case KEY_SPACE, KEY_ENTER -> {
                    PaintCell cell = new PaintCell(cursorFace, cursorX, cursorY);
                    if (tool == Tool.FILL) {
                        fill(cell);
                    } else {
                        lastPaintOffset = -1;
                        paint(cell);
                        remember();
                    }
                }
                default -> { return super.keyPressed(event); }
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private PaintState capture() {
        return new PaintState(new WaypointPaint(palette, pixels), faceView);
    }

    private void remember() {
        history.record(capture());
        refreshEditingState();
    }

    private void finishStroke() {
        if (painting) remember();
        painting = false;
        lastPaintOffset = -1;
    }

    private void moveHistory(boolean forward) {
        if (importing) return;
        finishStroke();
        PaintState state = forward ? history.redo() : history.undo();
        System.arraycopy(state.paint().paletteCopy(), 0, palette, 0, palette.length);
        System.arraycopy(state.paint().pixelsCopy(), 0, pixels, 0, pixels.length);
        faceView = state.faceView();
        if (faceView == FaceView.ONE) cursorFace = WaypointPaint.Face.NORTH;
        config.setWaypointPainterPalette(palette);
        refreshPaletteTooltips();
        if (faceViewButton != null) faceViewButton.setMessage(faceViewComponent());
        snapshot = null;
        status = "";
        refreshEditingState();
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
        finishStroke();
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
        int contentBottom = height - FOOTER_H - font.lineHeight - GAP;
        int paletteX = PAD_OUTER + 4;
        int paletteColumns = height < 300 ? 4 : 2;
        int paletteRows = (WaypointPaint.PALETTE_SIZE + paletteColumns - 1) / paletteColumns;
        int swatchesY = contentTop + 18;
        int editColorY = swatchesY + paletteRows * (SWATCH_H + SWATCH_GAP) + GAP_TIGHT;
        int rowStep = BTN_H + font.lineHeight + GAP;
        int toolY = Math.min(contentBottom - BTN_H - rowStep, editColorY + rowStep);
        int faceViewY = Math.min(contentBottom - BTN_H, toolY + rowStep);

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
                editColorY, toolY, faceViewY, canvasLeft, canvasRight,
                gridLeft, gridTop, cell, previewX, previewY, previewSize);
    }

    private static WaypointPaint initialPaint(WaypointerConfig config, PaintScope scope) {
        WaypointPaint shown = scope.shownPaint(config);
        return shown != null ? shown : new WaypointPaint(
                config.waypointPainterPalette(), new byte[WaypointPaint.PIXEL_COUNT]);
    }

    private record PaintCell(WaypointPaint.Face face, int x, int y) {}

    private record Layout(int contentTop, int contentBottom, int paletteX,
                          int paletteColumns, int swatchesY, int editColorY, int toolY, int faceViewY,
                          int canvasLeft, int canvasRight,
                          int gridLeft, int gridTop, int cell,
                          int previewX, int previewY, int previewSize) {}

    private final class PaletteButton extends AbstractButton {
        private final int slot;

        private PaletteButton(int x, int y, int width, int height, int slot) {
            super(x, y, width, height, Component.translatable(
                    "waypointer.screen.painter.palette.narration", slot + 1));
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
            super(x, y, width, height, Component.translatable(
                    "waypointer.screen.painter.canvas.narration"));
        }

        @Override
        public void onPress(InputWithModifiers input) {
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
