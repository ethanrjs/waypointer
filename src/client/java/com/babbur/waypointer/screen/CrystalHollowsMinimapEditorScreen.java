package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.InputCompat;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.CrystalHollowsMinimap;
import com.babbur.waypointer.crystal.CrystalHollowsMinimapLayout;
import com.babbur.waypointer.crystal.StructureSighting;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static com.babbur.waypointer.compat.InputCompat.*;

public final class CrystalHollowsMinimapEditorScreen extends Screen {
    private final Screen parent;
    private final WaypointerConfig config;
    private double scale;
    private double positionX;
    private double positionY;
    private boolean customPosition;
    private boolean dragging;
    private double grabX;
    private double grabY;
    private MapHandle handle;

    public CrystalHollowsMinimapEditorScreen(Screen parent, WaypointerConfig config) {
        super(text("title"));
        this.parent = parent;
        this.config = config;
        scale = config.minimapScale();
        customPosition = config.minimapCustomPosition();
        positionX = config.minimapPositionX();
        positionY = config.minimapPositionY();
    }

    @Override
    protected void init() {
        dragging = false;
        handle = addRenderableWidget(new MapHandle());
        updateHandle();
        int buttonWidth = Math.min(100, (width - 32) / 3);
        int left = (width - buttonWidth * 3 - GAP * 2) / 2;
        int footer = height - BTN_H - 6;
        addRenderableWidget(styledButton(left, footer, buttonWidth, BTN_H, text("reset"), b -> {
            scale = 1;
            positionX = 0;
            positionY = 0;
            customPosition = true;
            updateHandle();
        }, null));
        addRenderableWidget(styledButton(left + buttonWidth + GAP, footer, buttonWidth, BTN_H,
                Component.translatable("gui.cancel"), b -> onClose(), null));
        addRenderableWidget(styledButton(left + (buttonWidth + GAP) * 2, footer, buttonWidth, BTN_H,
                text("save"), b -> save(), null));
        setInitialFocus(handle);
    }

    private StructureSighting target() {
        var tracker = WaypointerClient.crystalHollowsTracker();
        return tracker != null && tracker.active() ? tracker.compassNavigationTarget() : null;
    }

    private CrystalHollowsMinimapLayout layout() {
        return CrystalHollowsMinimapLayout.fit(width, height, config.crystalHollowsMinimapSize(),
                config.crystalHollowsMinimapCorner(), target() != null, scale,
                customPosition, positionX, positionY);
    }

    private void updateHandle() {
        var layout = layout();
        handle.visible = layout != null;
        if (layout == null) return;
        handle.setX(layout.x());
        handle.setY(layout.y());
        handle.setWidth(layout.width());
        handle.setHeight(layout.height());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0x60000000);
        updateHandle();
        var layout = layout();
        if (layout != null) {
            var tracker = WaypointerClient.crystalHollowsTracker();
            var player = minecraft.player;
            boolean live = tracker != null && tracker.active() && player != null;
            CrystalHollowsMinimap.draw(graphics, layout,
                    live ? player.getX() : 0, live ? player.getY() : 100,
                    live ? player.getZ() : 0, live ? player.getYRot() : 0,
                    live && tracker.lobby() != null ? tracker.lobby().sightings().stream()
                            .filter(tracker::detectionEnabled).toList() : List.of(),
                    live ? target() : null, config.crystalHollowsMinimapLabels(),
                    config.crystalHollowsShowRoughMarkers());
            drawDragOutline(graphics, layout.x() - 2, layout.y() - 2,
                    layout.x() + layout.width() + 2, layout.y() + layout.height() + 2,
                    dragging || handle.isHoveredOrFocused() ? ACCENT : BORDER, false);
            if (dragging && !shiftDown()) drawSnapGuides(graphics, layout);
            if (!live) centeredText(graphics, text("sample"), 33, TEXT_MUTED);
        }
        centeredText(graphics, getTitle(), 8, TEXT);
        centeredText(graphics, text("instructions"), height - 54, TEXT_DIM);
        centeredText(graphics, Component.translatable("waypointer.minimap.editor.scale",
                String.format(Locale.ROOT, "%.2f", scale)), height - 41, ACCENT);
        super.extractRenderState(graphics, mouseX, mouseY, partial);
    }

    private void centeredText(GuiGraphicsExtractor graphics, Component text, int y, int color) {
        String label = font.plainSubstrByWidth(text.getString(), Math.max(0, width - 16));
        int textWidth = font.width(label);
        int x = (width - textWidth) / 2;
        graphics.fill(x - 3, y - 2, x + textWidth + 3, y + 10, 0xB0101216);
        graphics.text(font, label, x, y, color, false);
    }

    private void drawSnapGuides(GuiGraphicsExtractor graphics, CrystalHollowsMinimapLayout layout) {
        int snapX = snappedAnchor(positionX);
        int snapY = snappedAnchor(positionY);
        if (snapX >= 0) {
            int x = layout.x() + layout.width() * snapX / 2;
            graphics.fill(x, 8, x + 1, height - 36, 0x904FB3C4);
        }
        if (snapY >= 0) {
            int y = layout.y() + layout.height() * snapY / 2;
            graphics.fill(8, y, width - 8, y + 1, 0x904FB3C4);
        }
    }

    private static int snappedAnchor(double value) {
        for (int i = 0; i <= 2; i++) if (Math.abs(value - i / 2.0) < 1e-9) return i;
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        updateHandle();
        if (event.button() == MOUSE_BUTTON_LEFT && handle.isMouseOver(event.x(), event.y())) {
            setFocused(handle);
            dragging = true;
            grabX = event.x() - handle.getX();
            grabY = event.y() - handle.getY();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging || event.button() != MOUSE_BUTTON_LEFT) return super.mouseDragged(event, dx, dy);
        moveTo(event.x() - grabX, event.y() - grabY, !shiftDown());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging || event.button() != MOUSE_BUTTON_LEFT) return super.mouseReleased(event);
        moveTo(event.x() - grabX, event.y() - grabY, !shiftDown());
        dragging = false;
        return true;
    }

    private void moveTo(double x, double y, boolean snap) {
        var layout = layout();
        if (layout == null) return;
        int availableX = Math.max(0, width - layout.width() - 16);
        int availableY = Math.max(0, height - layout.height() - 44);
        positionX = snapPosition(CrystalHollowsMinimapLayout.normalizeX(width, layout.width(), x), availableX, snap);
        positionY = snapPosition(CrystalHollowsMinimapLayout.normalizeY(height, layout.height(), y), availableY, snap);
        customPosition = true;
        updateHandle();
    }

    static double snapPosition(double normalized, int available, boolean snap) {
        double value = Math.clamp(normalized, 0, 1);
        double nearest = Math.round(value * 2) / 2.0;
        return snap && Math.abs(nearest - value) * available <= 8 ? nearest : value;
    }

    static double scrolledScale(double scale, double scroll) {
        if (!Double.isFinite(scroll) || scroll == 0) return scale;
        return Math.clamp((Math.round(scale * 20) + Math.signum(scroll)) / 20.0, 0.5, 2);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!handle.isMouseOver(mouseX, mouseY) || vertical == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        changeScale(vertical);
        if (dragging) {
            grabX = mouseX - handle.getX();
            grabY = mouseY - handle.getY();
        }
        return true;
    }

    private void changeScale(double scroll) {
        scale = scrolledScale(scale, scroll);
        updateHandle();
    }

    private boolean shiftDown() {
        var window = minecraft.getWindow();
        return InputCompat.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputCompat.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    private void save() {
        config.setMinimapScale(scale);
        config.setMinimapPositionX(positionX);
        config.setMinimapPositionY(positionY);
        config.setMinimapCustomPosition(customPosition);
        onClose();
    }

    @Override
    public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    private static Component text(String key) {
        return Component.translatable("waypointer.minimap.editor." + key);
    }

    private final class MapHandle extends AbstractWidget {
        MapHandle() { super(0, 0, 1, 1, text("instructions")); }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {}

        @Override
        public boolean keyPressed(KeyEvent event) {
            int step = shiftDown() ? 10 : 1;
            switch (event.key()) {
                case KEY_LEFT -> moveTo(getX() - step, getY(), false);
                case KEY_RIGHT -> moveTo(getX() + step, getY(), false);
                case KEY_UP -> moveTo(getX(), getY() - step, false);
                case KEY_DOWN -> moveTo(getX(), getY() + step, false);
                case KEY_EQUAL, KEY_KP_ADD -> changeScale(1);
                case KEY_MINUS, KEY_KP_SUBTRACT -> changeScale(-1);
                default -> { return false; }
            }
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
