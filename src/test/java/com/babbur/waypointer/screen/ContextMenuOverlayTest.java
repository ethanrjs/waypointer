package com.babbur.waypointer.screen;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

class ContextMenuOverlayTest {

    private static ContextMenuOverlay.Item item(String label, Runnable action) {
        ContextMenuOverlay.Item hinted = ContextMenuOverlay.Item.of(
                Component.literal(label), "Space", action);
        assertTrue(hinted.enabled());
        return ContextMenuOverlay.Item.of(Component.literal(label), action);
    }

    private static ContextMenuOverlay openedMenu(List<ContextMenuOverlay.Item> items) {
        ContextMenuOverlay menu = new ContextMenuOverlay();
        menu.openMeasured(items, 100, 40, 40, 0, 0, 400, 300);
        return menu;
    }

    @Test
    void menuGeometryClampsWidthAndFlipsAboveWhenClippingTheBottom() {
        assertEquals(130, ContextMenuOverlay.menuWidth(20));
        assertEquals(180, ContextMenuOverlay.menuWidth(164));
        assertEquals(250, ContextMenuOverlay.menuWidth(900));
        assertEquals(3 * GuiTokens.ROW_H + 2, ContextMenuOverlay.menuHeight(3));

        assertEquals(16, ContextMenuOverlay.clampedX(16.0, 16, 400, 130));
        assertEquals(270, ContextMenuOverlay.clampedX(390.0, 16, 400, 130));

        assertEquals(100, ContextMenuOverlay.openY(100.0, 68, 16, 300));
        assertEquals(232, ContextMenuOverlay.openY(290.0, 58, 16, 300));
        assertEquals(16, ContextMenuOverlay.openY(20.0, 500, 16, 300));
    }

    @Test
    void openingRequiresItemsAndOpenAboveSitsOnTheAnchor() {
        ContextMenuOverlay menu = new ContextMenuOverlay();
        menu.openMeasured(List.of(), 100, 0, 0, 0, 0, 400, 300);
        assertFalse(menu.isOpen());

        menu.openAboveMeasured(List.of(item("a", () -> {})), 60,
                20, 200, 16, 16, 400);
        assertTrue(menu.isOpen());
        menu.close();
        assertFalse(menu.isOpen());
    }

    @Test
    void clicksRunEnabledItemsAndAlwaysConsumeWhileOpen() {
        AtomicInteger ran = new AtomicInteger();
        ContextMenuOverlay menu = openedMenu(List.of(
                item("first", ran::incrementAndGet),
                ContextMenuOverlay.Item.disabled(Component.literal("second"), null)));

        assertTrue(menu.mouseClicked(45, 40 + 1 + GuiTokens.ROW_H + 4));
        assertEquals(0, ran.get(), "disabled rows must not run");
        assertTrue(menu.isOpen(), "disabled rows keep the menu open");

        assertTrue(menu.mouseClicked(45, 44));
        assertEquals(1, ran.get());
        assertFalse(menu.isOpen(), "running an item closes the menu");

        assertFalse(menu.mouseClicked(45, 44), "closed menus ignore clicks");

        menu = openedMenu(List.of(item("only", ran::incrementAndGet)));
        assertTrue(menu.mouseClicked(5, 5), "outside clicks are consumed");
        assertFalse(menu.isOpen(), "outside clicks dismiss");
        assertEquals(1, ran.get());
    }

    @Test
    void keyboardCursorSkipsDisabledRowsAndWrapsInBothDirections() {
        AtomicInteger ran = new AtomicInteger();
        ContextMenuOverlay menu = openedMenu(List.of(
                item("first", ran::incrementAndGet),
                ContextMenuOverlay.Item.disabled(Component.literal("second"), null),
                item("third", () -> ran.addAndGet(10))));

        assertTrue(menu.keyPressed(GLFW_KEY_UP), "up from nowhere lands on the last enabled row");
        assertTrue(menu.keyPressed(GLFW_KEY_ENTER));
        assertEquals(10, ran.get());
        assertFalse(menu.isOpen());

        menu = openedMenu(List.of(
                item("first", ran::incrementAndGet),
                ContextMenuOverlay.Item.disabled(Component.literal("second"), null),
                item("third", () -> ran.addAndGet(10))));
        assertTrue(menu.keyPressed(GLFW_KEY_DOWN));
        assertTrue(menu.keyPressed(GLFW_KEY_DOWN));
        assertTrue(menu.keyPressed(GLFW_KEY_ENTER), "down skips the disabled middle row");
        assertEquals(20, ran.get());

        menu = openedMenu(List.of(item("only", ran::incrementAndGet)));
        assertTrue(menu.keyPressed(GLFW_KEY_ESCAPE));
        assertFalse(menu.isOpen());

        menu = openedMenu(List.of(item("only", ran::incrementAndGet)));
        assertTrue(menu.keyPressed(GLFW_KEY_ENTER),
                "enter with no cursor just dismisses");
        assertFalse(menu.isOpen());
        assertEquals(20, ran.get(), "nothing runs without a cursor");

        menu = openedMenu(List.of(item("only", ran::incrementAndGet)));
        assertFalse(menu.keyPressed(GLFW_KEY_A), "unhandled keys close and fall through");
        assertFalse(menu.isOpen());
        assertFalse(menu.keyPressed(GLFW_KEY_ENTER), "closed menus ignore keys");
    }
}
