package com.babbur.waypointer.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

/** Keyboard and narration layer for a fixed-row list */
final class ListNavigationWidget extends AbstractButton {

    private final IntSupplier itemCount;
    private final IntSupplier initialIndex;
    private final IntFunction<Component> itemNarration;
    private final IntConsumer activateItem;
    private final IntSupplier scrollOffset;
    private final IntConsumer ensureVisible;
    private final IntConsumer spaceAction;
    private final int rowInset;
    private final int rowPitch;
    private final int rowHeight;
    private int cursor = -1;

    ListNavigationWidget(int x, int y, int width, int height,
                         int rowInset, int rowPitch, int rowHeight,
                         IntSupplier itemCount, IntSupplier initialIndex,
                         IntFunction<Component> itemNarration,
                         IntConsumer activateItem, IntSupplier scrollOffset,
                         IntConsumer ensureVisible) {
        this(x, y, width, height, rowInset, rowPitch, rowHeight, itemCount, initialIndex,
                itemNarration, activateItem, scrollOffset, ensureVisible, null);
    }

    ListNavigationWidget(int x, int y, int width, int height,
                         int rowInset, int rowPitch, int rowHeight,
                         IntSupplier itemCount, IntSupplier initialIndex,
                         IntFunction<Component> itemNarration,
                         IntConsumer activateItem, IntSupplier scrollOffset,
                         IntConsumer ensureVisible, IntConsumer spaceAction) {
        super(x, y, width, height, Component.empty());
        this.rowInset = rowInset;
        this.rowPitch = Math.max(1, rowPitch);
        this.rowHeight = Math.max(1, rowHeight);
        this.itemCount = itemCount;
        this.initialIndex = initialIndex;
        this.itemNarration = itemNarration;
        this.activateItem = activateItem;
        this.scrollOffset = scrollOffset;
        this.ensureVisible = ensureVisible;
        this.spaceAction = spaceAction;
    }

    void setCursor(int index) {
        int count = count();
        int next = count == 0 ? -1 : Math.max(0, Math.min(index, count - 1));
        if (next == cursor) return;
        cursor = next;
        if (cursor >= 0) ensureVisible.accept(cursor);
        refreshMessage();
    }

    int cursor() {
        syncCursor();
        return cursor;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (!active || !visible) return;
        syncCursor();
        if (cursor >= 0) activateItem.accept(cursor);
        refreshMessage();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !visible) return false;
        syncCursor();
        int count = count();
        if (count == 0) return false;
        if (event.key() == GLFW_KEY_SPACE && spaceAction != null) {
            if (cursor >= 0) {
                spaceAction.accept(cursor);
                refreshMessage();
            }
            return true;
        }
        int page = Math.max(1, getHeight() / rowPitch);
        int next = switch (event.key()) {
            case GLFW_KEY_UP -> moveIndex(cursor, count, -1);
            case GLFW_KEY_DOWN -> moveIndex(cursor, count, 1);
            case GLFW_KEY_PAGE_UP -> moveIndex(cursor, count, -page);
            case GLFW_KEY_PAGE_DOWN -> moveIndex(cursor, count, page);
            case GLFW_KEY_HOME -> 0;
            case GLFW_KEY_END -> count - 1;
            default -> -1;
        };
        if (next >= 0) {
            setCursor(next);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return false;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        syncCursor();
        if (!isFocused() || cursor < 0) return;
        int top = getY() + rowInset - scrollOffset.getAsInt() + cursor * rowPitch;
        int bottom = top + rowHeight;
        int clipTop = Math.max(getY(), top);
        int clipBottom = Math.min(getY() + getHeight(), bottom);
        if (clipBottom <= clipTop) return;
        int left = getX();
        int right = getX() + getWidth();
        graphics.fill(left, clipTop, right, Math.min(clipTop + 1, clipBottom), GuiTokens.ACCENT);
        graphics.fill(left, Math.max(clipTop, clipBottom - 1), right, clipBottom, GuiTokens.ACCENT);
        graphics.fill(left, clipTop, Math.min(left + 1, right), clipBottom, GuiTokens.ACCENT);
        graphics.fill(Math.max(left, right - 1), clipTop, right, clipBottom, GuiTokens.ACCENT);
    }

    void extractOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                        float partialTick) {
        extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        syncCursor();
        refreshMessage();
        defaultButtonNarrationText(output);
    }

    static int moveIndex(int current, int count, int delta) {
        if (count <= 0) return -1;
        int safeCurrent = current < 0 ? (delta > 0 ? -1 : 0) : Math.min(current, count - 1);
        return Math.max(0, Math.min(safeCurrent + delta, count - 1));
    }

    private void syncCursor() {
        int count = count();
        if (count == 0) {
            cursor = -1;
        } else if (cursor < 0 || cursor >= count) {
            int preferred = initialIndex.getAsInt();
            cursor = preferred >= 0 && preferred < count ? preferred : 0;
        }
    }

    private void refreshMessage() {
        int count = count();
        if (cursor < 0 || cursor >= count) {
            setMessage(Component.empty());
            return;
        }
        Component item = itemNarration.apply(cursor);
        setMessage((item == null ? Component.empty() : item.copy())
                .append(Component.literal(". "))
                .append(Component.translatable(
                        "waypointer.screen.list.position", cursor + 1, count)));
    }

    private int count() {
        return Math.max(0, itemCount.getAsInt());
    }
}
