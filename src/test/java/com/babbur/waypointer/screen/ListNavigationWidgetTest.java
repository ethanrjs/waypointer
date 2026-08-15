package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ListNavigationWidgetTest {

    @Test
    void keyboardMovementClampsToTheAvailableRows() {
        assertEquals(-1, ListNavigationWidget.moveIndex(0, 0, 1));
        assertEquals(0, ListNavigationWidget.moveIndex(-1, 4, -1));
        assertEquals(0, ListNavigationWidget.moveIndex(-1, 4, 1));
        assertEquals(0, ListNavigationWidget.moveIndex(0, 4, -10));
        assertEquals(3, ListNavigationWidget.moveIndex(2, 4, 10));
    }

    @Test
    void keyboardLayerIsPointerTransparentForThePaintedRowsBelowIt() {
        ListNavigationWidget widget = new ListNavigationWidget(
                10, 20, 100, 80, 0, 22, 20,
                () -> 3, () -> 0, ignored -> net.minecraft.network.chat.Component.empty(),
                ignored -> {}, () -> 0, ignored -> {});

        assertFalse(widget.isMouseOver(50, 50));
    }

    @Test
    void spaceRunsTheSecondaryActionInsteadOfActivatingTheRow() {
        java.util.concurrent.atomic.AtomicInteger toggled =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        ListNavigationWidget widget = new ListNavigationWidget(
                10, 20, 100, 80, 0, 22, 20,
                () -> 3, () -> 1, ignored -> net.minecraft.network.chat.Component.empty(),
                ignored -> {}, () -> 0, ignored -> {}, toggled::set);
        widget.active = true;
        widget.visible = true;

        assertEquals(true, widget.keyPressed(new net.minecraft.client.input.KeyEvent(
                org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0)));
        assertEquals(1, toggled.get(),
                "space must act on the cursor row without pressing the widget");
    }
}
